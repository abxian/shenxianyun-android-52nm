package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.compat.versionCodeCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotalBytes
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class MainActivity : BaseActivity<MainDesign>() {
    private var selectedMode = TunnelState.Mode.Rule
    private enum class TrafficReportResult {
        Success,
        CounterReset,
        Limited,
        Failure,
    }

    private companion object {
        const val ACTIVATION_STORE = ACTIVATION_STORE_NAME
        const val KEY_CODE = "code"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_PROFILE_UUID = "profile_uuid"
        const val KEY_UPDATE_VERSION = "update_version"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_EXPIRED_PROFILE_UUID = "expired_profile_uuid"
        // 订阅更新轮询：仅在已连接时运行，基础 10 分钟，失败指数退避到最多 1 小时。
        const val UPDATE_CHECK_INTERVAL_MILLIS = 600_000L
        const val UPDATE_CHECK_MAX_INTERVAL_MILLIS = 3_600_000L
        const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val TRAFFIC_REPORT_INTERVAL_MILLIS = 60_000L
        const val MAX_TRAFFIC_REPORT_DELTA_BYTES = 5L * 1024 * 1024 * 1024
        const val EXPIRED_NODE_NAME = "提取码到期，请续费使用"
        const val EXPIRED_PROFILE_NAME = "提取码已到期"
    }

    override suspend fun main() {
        val design = MainDesign(this)
        stableClientId()

        // 端点发现：后台拉取 endpoints.json 并探测可用 API 基址（失败静默，用缓存/内置默认）。
        // 必须放独立协程，绝不能阻塞下面的 select 事件循环。
        launch {
            EndpointResolver.initialize()
            refreshLineStatus(design)
        }

        setContentDesign(design)

        runCatching { ensureDefaultMetaFeatures() }
            .onFailure { design.showExceptionToast(it.asException()) }

        runCatching { design.resetModeForLaunch() }
            .onFailure { design.showExceptionToast(it.asException()) }
        runCatching { design.fetch() }
            .onFailure { design.showExceptionToast(it.asException()) }
        runCatching { design.checkAppUpdate() }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))
        var lastSubscriptionUpdateCheck = 0L
        var lastHeartbeatAt = 0L
        var lastTrafficReportAt = 0L
        var lastReportedTrafficTotal: Long? = null
        var trafficCounterId = UUID.randomUUID().toString()
        var trafficCounterSequence = 0L
        var trafficCounterBase = 0L
        var pendingTrafficTotal: Long? = null
        // 订阅更新轮询：在途互斥 + 失败指数退避（叠加抖动），避免 web 掉线恢复后惊群打满服务器。
        var subscriptionUpdateInFlight = false
        var subscriptionFailures = 0
        var subscriptionCheckDelay = UPDATE_CHECK_INTERVAL_MILLIS

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // fetch() 会通过 withClash/withProfile 等待后台服务，绑定慢时可能耗时较久。
                    // 必须放进独立协程，否则会阻塞这个 select 事件循环，导致后续所有点击
                    // （design.requests）排队却不被处理，界面看起来“点击失效、什么都点不了”。
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> launch { design.safeFetch() }
                        Event.ClashStart -> {
                            trafficCounterId = UUID.randomUUID().toString()
                            trafficCounterSequence = 0L
                            trafficCounterBase = 0L
                            pendingTrafficTotal = null
                            launch { design.safeFetch() }
                            launch { sendClientHeartbeat("online") }
                        }
                        Event.ClashStop -> {
                            launch { design.safeFetch() }
                            lastHeartbeatAt = 0L
                            lastTrafficReportAt = 0L
                            lastReportedTrafficTotal = null
                            pendingTrafficTotal = null
                            launch { sendClientHeartbeat("offline") }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            runCatching {
                                if (clashRunning)
                                    stopClashService()
                                else
                                    design.startClash()
                            }.onFailure { design.showExceptionToast(it.asException()) }
                        }
                        MainDesign.Request.ImportByCode ->
                            design.showCodeImportDialog()
                        MainDesign.Request.UpdateSubscription -> {
                            val code = savedActivationCode()
                            if (code.isBlank()) {
                                design.showCodeImportDialog()
                            } else {
                                // 网络更新放进独立协程，避免阻塞事件循环导致点击失效。
                                launch {
                                    runCatching { design.importSubscriptionCode(code, silent = true) }
                                        .onFailure { design.showExceptionToast(it.asException()) }
                                }
                            }
                        }
                        MainDesign.Request.RenewCode ->
                            openCodeStorePage()
                        MainDesign.Request.SelectLine ->
                            // 探测+弹窗都是耗时操作，放独立协程，不阻塞事件循环。
                            launch { showLineSelector(design) }
                        MainDesign.Request.SetRuleMode ->
                            runCatching { design.patchMode(TunnelState.Mode.Rule) }
                                .onFailure { design.showExceptionToast(it.asException()) }
                        MainDesign.Request.SetGlobalMode ->
                            runCatching { design.patchMode(TunnelState.Mode.Global) }
                                .onFailure { design.showExceptionToast(it.asException()) }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                ticker.onReceive {
                    val now = System.currentTimeMillis()
                    if (clashRunning) {
                        val trafficTotal = runCatching { design.fetchTraffic() }
                            .getOrElse { lastReportedTrafficTotal ?: 0L }
                        if (lastReportedTrafficTotal == null) {
                            lastReportedTrafficTotal = trafficTotal
                            trafficCounterBase = trafficTotal
                        }
                        if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS) {
                            lastHeartbeatAt = now
                            launch { sendClientHeartbeat("online") }
                        }
                        if (now - lastTrafficReportAt >= TRAFFIC_REPORT_INTERVAL_MILLIS) {
                            val previous = lastReportedTrafficTotal ?: trafficTotal
                            val delta = (trafficTotal - previous).coerceAtLeast(0L)
                            val cumulative = (trafficTotal - trafficCounterBase).coerceAtLeast(0L)
                            lastTrafficReportAt = now
                            if (delta > 0L && delta <= MAX_TRAFFIC_REPORT_DELTA_BYTES) {
                                val reportTotal = pendingTrafficTotal ?: cumulative
                                val reportSequence = trafficCounterSequence + 1L
                                pendingTrafficTotal = reportTotal
                                launch {
                                    when (
                                        sendClientTraffic(
                                            delta,
                                            trafficCounterId,
                                            reportSequence,
                                            reportTotal,
                                        )
                                    ) {
                                        TrafficReportResult.Success -> {
                                            trafficCounterSequence = reportSequence
                                            pendingTrafficTotal = null
                                            lastReportedTrafficTotal = trafficTotal
                                        }
                                        TrafficReportResult.CounterReset -> {
                                            trafficCounterId = UUID.randomUUID().toString()
                                            trafficCounterSequence = 0L
                                            trafficCounterBase = trafficTotal
                                            pendingTrafficTotal = null
                                            lastReportedTrafficTotal = trafficTotal
                                        }
                                        TrafficReportResult.Limited -> {
                                            pendingTrafficTotal = null
                                        }
                                        TrafficReportResult.Failure -> Unit
                                    }
                                }
                            } else if (delta > MAX_TRAFFIC_REPORT_DELTA_BYTES) {
                                lastReportedTrafficTotal = trafficTotal
                            }
                        }
                    }
                    // 仅在已连接、无在途请求、且到达（含退避后的）间隔时才发起更新检查。
                    if (clashRunning &&
                        !subscriptionUpdateInFlight &&
                        now - lastSubscriptionUpdateCheck >= subscriptionCheckDelay
                    ) {
                        lastSubscriptionUpdateCheck = now
                        subscriptionUpdateInFlight = true
                        launch {
                            val ok = runCatching { design.checkSubscriptionUpdate() }.isSuccess
                            subscriptionFailures = if (ok) 0 else minOf(subscriptionFailures + 1, 4)
                            // 失败可能是当前 API 线路失联：后台切到下一条可用线路（api_bases 顺延）。
                            if (!ok) runCatching { EndpointResolver.rotate() }
                            val backoff = minOf(
                                UPDATE_CHECK_INTERVAL_MILLIS shl subscriptionFailures,
                                UPDATE_CHECK_MAX_INTERVAL_MILLIS,
                            )
                            // 50%~100% 抖动，打散各客户端的请求时间。
                            subscriptionCheckDelay = (backoff * (0.5 + Math.random() * 0.5)).toLong()
                            subscriptionUpdateInFlight = false
                        }
                    }
                }
            }
        }
    }

    // ===== 服务线路（web/api_bases 地址）：顶部显示当前线路，点开可探测/切换 =====

    /**
     * 线路状态：平时隐藏。只有写死的两条主线路（神仙云1 国内 + 神仙云2 国外）都探测不通时，
     * 才显示「服务线路异常，点此切换」入口，让用户手动切到神仙云3/4。
     */
    private suspend fun refreshLineStatus(design: MainDesign) {
        val pinned = EndpointResolver.basesForUi().take(EndpointResolver.PINNED_COUNT)
        if (pinned.isEmpty()) { design.setLineStatus(null); return }
        val anyOk = withContext(Dispatchers.IO) {
            coroutineScope { pinned.map { async { EndpointResolver.probeBase(it) } }.awaitAll() }
        }.any { it }
        if (anyOk) {
            design.setLineStatus(null)                       // 主线路可用，隐藏
        } else {
            design.setLineStatus("服务线路异常，点此切换 ▾")   // 两条主线路都不通，显示切换
        }
    }

    /** 探测全部线路连通性并弹窗选择，点选后立即生效。 */
    private suspend fun showLineSelector(design: MainDesign) {
        val bases = EndpointResolver.basesForUi()
        val active = EndpointResolver.apiBase()
        design.setLineStatus("服务线路：检测中…")
        // 并发探测所有线路
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                bases.map { base -> async { EndpointResolver.probeBase(base) } }.awaitAll()
            }
        }
        val items = bases.mapIndexed { i, base ->
            MainDesign.LineItem("${EndpointResolver.clientName()}${i + 1}", results[i], base == active)
        }
        val picked = design.showLineSelector(items)
        if (picked != null && picked in bases.indices) {
            if (results[picked]) {
                EndpointResolver.setActive(bases[picked])
                design.showToast("已切换到${EndpointResolver.clientName()}${picked + 1}", ToastDuration.Short)
            } else {
                design.showToast("${EndpointResolver.clientName()}${picked + 1}不通，未切换", ToastDuration.Short)
            }
        }
        refreshLineStatus(design)
    }

    private fun stableClientId(): String {
        val store = activationStore()
        val saved = store.getString(KEY_CLIENT_ID, "")?.trim().orEmpty()
        if (saved.isNotBlank()) {
            return saved
        }
        val generated = UUID.randomUUID().toString()
        store.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    private suspend fun sendClientHeartbeat(status: String) = withContext(Dispatchers.IO) {
        val code = savedActivationCode()
        if (code.isBlank()) {
            return@withContext
        }
        val appVersion = queryAppVersionName().asHeaderValue()
        val credentials = loadManagedCredentials()
            ?.takeIf { it.accessCode == code }
        if (credentials != null) {
            val path = if (status == "offline") "offline" else "heartbeat"
            val body = JSONObject().apply {
                put("platform", "安卓手机")
                put("app_name", "${EndpointResolver.clientName()}安卓端")
                put("app_version", appVersion)
                put("device_name", "${Build.BRAND} ${Build.MODEL}")
            }.toString().toByteArray(Charsets.UTF_8)
            val connection = (URL("${credentials.apiBase}/api/v2/client/$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${credentials.deviceToken}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Shenxianyun-Android/$appVersion")
                setRequestProperty("X-Client-Id", stableClientId())
            }
            try {
                connection.outputStream.use { it.write(body) }
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val json = runCatching {
                    JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                }.getOrNull()
                if (
                    status != "offline" &&
                    statusCode == 403 &&
                    (json?.optString("code") == "device_limit" ||
                        json?.optString("code") == "traffic_limit")
                ) {
                    enforceClientLimit(
                        json?.optString("message", "套餐设备或流量额度已达到上限")
                            ?: "套餐设备或流量额度已达到上限",
                    )
                }
            } catch (_: Exception) {
            } finally {
                connection.disconnect()
            }
            return@withContext
        }
        val encoded = URLEncoder.encode(code, "UTF-8").replace("+", "%20")
        val path = if (status == "offline") "offline" else "heartbeat"
        val params = listOf(
            "client_id=${URLEncoder.encode(stableClientId(), "UTF-8")}",
            "platform=${URLEncoder.encode("安卓手机", "UTF-8")}",
            "app_name=${URLEncoder.encode("${EndpointResolver.clientName()}安卓端", "UTF-8")}",
            "app_version=${URLEncoder.encode(appVersion, "UTF-8")}",
            "device_name=${URLEncoder.encode("${Build.BRAND} ${Build.MODEL}", "UTF-8")}",
        ).joinToString("&")
        val connection = (URL("${EndpointResolver.apiBase()}/api/client/$path/$encoded?$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Shenxianyun-Android/$appVersion")
            setRequestProperty("X-Client-Id", stableClientId())
        }
        try {
            connection.responseCode
        } catch (_: Exception) {
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sendClientTraffic(
        deltaBytes: Long,
        counterId: String,
        sequence: Long,
        cumulativeBytes: Long,
    ): TrafficReportResult = withContext(Dispatchers.IO) {
        val code = savedActivationCode()
        if (code.isBlank() || deltaBytes <= 0L) {
            return@withContext TrafficReportResult.Failure
        }
        val appVersion = queryAppVersionName().asHeaderValue()
        val credentials = loadManagedCredentials()
            ?.takeIf { it.accessCode == code }
        if (credentials != null) {
            val body = JSONObject().apply {
                put("counter_id", counterId)
                put("sequence", sequence)
                put("upload_total", 0L)
                put("download_total", cumulativeBytes)
                put("platform", "安卓手机")
                put("app_name", "${EndpointResolver.clientName()}安卓端")
                put("app_version", appVersion)
                put("device_name", "${Build.BRAND} ${Build.MODEL}")
            }.toString().toByteArray(Charsets.UTF_8)
            val connection = (URL("${credentials.apiBase}/api/v2/client/traffic").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${credentials.deviceToken}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Shenxianyun-Android/$appVersion")
                setRequestProperty("X-Client-Id", stableClientId())
            }
            try {
                connection.outputStream.use { it.write(body) }
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val json = runCatching {
                    JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                }.getOrNull()
                when {
                    statusCode in 200..299 && json?.optBoolean("ok", false) == true ->
                        TrafficReportResult.Success
                    statusCode == 409 && json?.optString("code") == "counter_reset" ->
                        TrafficReportResult.CounterReset
                    statusCode == 403 && json?.optString("code") == "traffic_limit" -> {
                        enforceClientLimit(
                            json?.optString("message", "流量额度已用尽，代理已停止")
                                ?: "流量额度已用尽，代理已停止",
                        )
                        TrafficReportResult.Limited
                    }
                    else -> TrafficReportResult.Failure
                }
            } catch (_: Exception) {
                TrafficReportResult.Failure
            } finally {
                connection.disconnect()
            }
        } else {
        val encoded = URLEncoder.encode(code, "UTF-8").replace("+", "%20")
        val body = JSONObject().apply {
            put("client_id", stableClientId())
            put("platform", "安卓手机")
            put("app_name", "${EndpointResolver.clientName()}安卓端")
            put("app_version", appVersion)
            put("device_name", "${Build.BRAND} ${Build.MODEL}")
            put("upload_bytes", 0)
            put("download_bytes", deltaBytes)
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL("${EndpointResolver.apiBase()}/api/client/traffic/$encoded").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Shenxianyun-Android/$appVersion")
            setRequestProperty("X-Client-Id", stableClientId())
        }
        try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode in 200..299) {
                TrafficReportResult.Success
            } else {
                TrafficReportResult.Failure
            }
        } catch (_: Exception) {
            TrafficReportResult.Failure
        } finally {
            connection.disconnect()
        }
        }
    }

    private suspend fun enforceClientLimit(message: String) {
        withContext(Dispatchers.Main) {
            stopClashService()
            design?.showToast(message, ToastDuration.Long)
        }
    }

    private fun String.asHeaderValue(): String {
        return lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun Throwable.asException(): Exception {
        return this as? Exception ?: RuntimeException(this)
    }

    // fetch 的安全包装：吞掉异常并提示，供事件循环用 launch 调用，确保单次刷新失败
    // 不会让协程崩溃或阻塞后续事件处理。
    private suspend fun MainDesign.safeFetch() {
        runCatching { fetch() }.onFailure { showExceptionToast(it.asException()) }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)
        setActivationCode(savedActivationCode().ifBlank { null })

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun ensureDefaultMetaFeatures() {
        withClash {
            val override = queryOverride(Clash.OverrideSlot.Persist)
            if (override.allowLan == null) {
                override.allowLan = false
            }
            if (override.unifiedDelay != true || override.tcpConcurrent != true) {
                override.unifiedDelay = true
                override.tcpConcurrent = true
                patchOverride(Clash.OverrideSlot.Persist, override)
            } else if (override.allowLan == false) {
                patchOverride(Clash.OverrideSlot.Persist, override)
            }
        }
    }

    private suspend fun MainDesign.fetchTraffic(): Long {
        return withClash {
            queryTrafficTotal().also { setForwarded(it) }.trafficTotalBytes()
        }
    }

    private suspend fun MainDesign.patchMode(mode: TunnelState.Mode) {
        selectedMode = mode
        withClash {
            val override = queryOverride(Clash.OverrideSlot.Session)
            override.mode = mode
            patchOverride(Clash.OverrideSlot.Session, override)
        }
        setMode(mode)
    }

    private suspend fun MainDesign.resetModeForLaunch() {
        withClash {
            // 模式只在本次打开期间有效。清掉旧版本可能留下的持久 mode，
            // 每次重新打开默认规则模式，用户仍可在当前页面手动切换全局模式。
            val persistedOverride = queryOverride(Clash.OverrideSlot.Persist)
            if (persistedOverride.mode != null) {
                persistedOverride.mode = null
                patchOverride(Clash.OverrideSlot.Persist, persistedOverride)
            }
            val sessionOverride = queryOverride(Clash.OverrideSlot.Session)
            sessionOverride.mode = TunnelState.Mode.Rule
            patchOverride(Clash.OverrideSlot.Session, sessionOverride)
        }
        selectedMode = TunnelState.Mode.Rule
        setMode(selectedMode)
    }

    private suspend fun applySelectedModeBeforeStart() {
        withClash {
            val sessionOverride = queryOverride(Clash.OverrideSlot.Session)
            sessionOverride.mode = selectedMode
            patchOverride(Clash.OverrideSlot.Session, sessionOverride)
        }
    }

    private fun MainDesign.showCodeImportDialog() {
        val input = EditText(this@MainActivity).apply {
            hint = getString(R.string.import_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(savedActivationCode())
            selectAll()
        }

        AlertDialog.Builder(this@MainActivity)
            .setTitle(R.string.import_code_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text.toString().trim()

                if (code.isBlank()) {
                    launch {
                        showToast(R.string.import_code_empty, ToastDuration.Long)
                    }
                    return@setPositiveButton
                }

                launch {
                    importSubscriptionCode(code)
                }
            }
            .show()
    }

    private fun MainDesign.showExpiredDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle(R.string.import_code_expired_title)
            .setMessage(R.string.import_code_expired_message)
            .setPositiveButton(R.string.renew_code) { _, _ -> openCodeStorePage() }
            .setNeutralButton(R.string.import_by_code) { _, _ ->
                showCodeImportDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun MainDesign.importSubscriptionCode(code: String, silent: Boolean = false) {
        try {
            if (!silent) {
                showToast(R.string.import_code_fetching, ToastDuration.Long)
            }

            val existing = loadManagedCredentials()
                ?.takeIf { it.accessCode == code }
            var credentials = if (silent && existing != null) {
                existing
            } else {
                var lastError: Exception? = null
                var exchanged: ManagedCredentials? = null
                for (attempt in 0 until SUBSCRIPTION_NETWORK_ATTEMPTS) {
                    if (attempt > 0) {
                        if (!silent) {
                            showToast(
                                getString(
                                    R.string.import_code_retrying,
                                    attempt,
                                    SUBSCRIPTION_NETWORK_RETRIES,
                                ),
                                ToastDuration.Long,
                            )
                        }
                        delay(subscriptionRetryDelayMillis(attempt))
                        EndpointResolver.rotate()
                    }
                    try {
                        val apiBase = EndpointResolver.apiBase()
                        val ticket = issueManagedImportTicket(code, apiBase)
                        exchanged = exchangeManagedImportTicket(
                            ticket,
                            apiBase,
                            stableClientId(),
                        ).copy(accessCode = code)
                        break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                exchanged ?: throw (
                    lastError ?: IllegalStateException("Unable to exchange import ticket")
                )
            }
            if (isExpired(credentials.expiresAt)) {
                if (!silent) {
                    showToast(R.string.import_code_expired, ToastDuration.Long)
                }
                return
            }
            val content = try {
                fetchManagedSubscription(credentials)
            } catch (error: Exception) {
                if (!silent || existing == null) throw error
                val apiBase = existing.apiBase.ifBlank { EndpointResolver.apiBase() }
                val ticket = issueManagedImportTicket(code, apiBase)
                credentials = exchangeManagedImportTicket(
                    ticket,
                    apiBase,
                    stableClientId(),
                ).copy(accessCode = code)
                fetchManagedSubscription(credentials)
            }
            val uuid = installManagedProfile(code, content)
            saveManagedCredentials(credentials)
            val expiresAt = credentials.expiresAt
            val updateVersion = queryUpdateVersion(code)
                ?: activationStore().getLong(KEY_UPDATE_VERSION, 0L)
            activationStore().edit()
                .putString(KEY_CODE, code)
                .putString(KEY_EXPIRES_AT, expiresAt)
                .putString(KEY_PROFILE_UUID, uuid.toString())
                .putLong(KEY_UPDATE_VERSION, updateVersion)
                .apply()
            fetch()
            showToast(
                if (silent) R.string.subscription_updated else R.string.import_code_success,
                ToastDuration.Long
            )
        } catch (e: Exception) {
            if (!silent) {
                showToast(R.string.import_code_failed_after_retries, ToastDuration.Long)
            }
        }
    }

    private suspend fun MainDesign.startClash() {
        val code = savedActivationCode()
        var active = withProfile { queryActive() }
        // 到期占位只在“当前正使用提取码那条订阅（或已是到期占位）”时才触发：切到只含一个
        // 不可上网节点的占位配置并提示续费，续费后由轮询自动恢复正式订阅。
        // 如果用户切到了自己导入的 Clash 链接配置，即使提取码过期也照常启动上网，不再被劫持。
        val codeProfileUuid = activationStore().getString(KEY_PROFILE_UUID, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val onCodeTrack = active != null &&
            (active.uuid == codeProfileUuid || active.uuid == expiredProfileUuid())
        if (code.isNotBlank() && onCodeTrack && !isActivationStillValid(code)) {
            activateExpiredProfile()
            showExpiredDialog()
            active = withProfile { queryActive() }
        }

        if (active == null || !active.imported) {
            // 既没有提取码、也没有任何已导入的本地订阅：引导去本地配置页粘贴订阅链接，
            // 或用提取码导入。
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.import_by_code) {
                    if (savedActivationCode().isBlank()) {
                        startActivity(ProfilesActivity::class.intent)
                    } else {
                        showCodeImportDialog()
                    }
                }
            }

            return
        }

        applySelectedModeBeforeStart()
        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private fun activationStore() =
        getSharedPreferences(ACTIVATION_STORE, Context.MODE_PRIVATE)

    private fun savedActivationCode(): String =
        activationStore().getString(KEY_CODE, "")?.trim().orEmpty()

    private fun openCodeStorePage() {
        val code = savedActivationCode()
        val encoded = URLEncoder.encode(code, "UTF-8").replace("+", "%20")
        val url = if (code.isBlank()) {
            "${EndpointResolver.apiBase()}/pay?action=new"
        } else {
            "${EndpointResolver.apiBase()}/pay?action=renew&code=$encoded"
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private suspend fun queryUpdateVersion(code: String): Long? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(code, "UTF-8").replace("+", "%20")
        val clientId = URLEncoder.encode(stableClientId(), "UTF-8")
        val connection = (URL("${EndpointResolver.apiBase()}/api/update-state/$encoded?client_id=$clientId").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("X-Client-Id", stableClientId())
        }

        try {
            if (connection.responseCode !in 200..299) {
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                return@withContext null
            }

            json.optLong("update_version", 0L).takeIf { it > 0L }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun MainDesign.checkAppUpdate() {
        val update = withContext(Dispatchers.IO) {
            val connection = (URL("${EndpointResolver.apiBase()}/api/app-version").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }

            try {
                if (connection.responseCode !in 200..299) {
                    return@withContext null
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    return@withContext null
                }

                val latestCode = json.optLong("latest_version_code", 0L)
                val apkUrl = json.optString("apk_url", "")
                val versionName = json.optString("latest_version_name", "").ifBlank { latestCode.toString() }
                val currentCode = packageManager.getPackageInfo(packageName, 0).versionCodeCompat

                if (latestCode > currentCode && apkUrl.isNotBlank()) {
                    Pair(versionName, apkUrl)
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } ?: return

        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.app_update_title)
                .setMessage(
                    getString(
                        R.string.app_update_message,
                        EndpointResolver.clientName(),
                        update.first,
                    )
                )
                .setPositiveButton(R.string.app_update_now) { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.second)))
                }
                .setNegativeButton(R.string.app_update_later, null)
                .show()
        }
    }

    // 订阅更新轮询：仅在已连接时执行，空闲时不请求，降低服务器压力。
    // 网络异常会向上抛出，交由调用方做指数退避。
    private suspend fun MainDesign.checkSubscriptionUpdate() {
        if (!clashRunning) {
            return
        }

        val code = savedActivationCode()
        if (code.isBlank()) {
            return
        }

        // 处于到期占位配置时，低频探测提取码是否已续费，成功则自动恢复正式订阅。
        if (onExpiredPlaceholder()) {
            recoverFromExpired(code)
            return
        }

        val remoteVersion = queryUpdateVersion(code) ?: return

        val localVersion = activationStore().getLong(KEY_UPDATE_VERSION, 0L)
        if (localVersion > 0L && remoteVersion <= localVersion) {
            return
        }

        importSubscriptionCode(code, silent = true)
    }

    // 生成只含单个本地不可上网节点的占位配置：除订阅/续费域名直连外，其余流量全部
    // 指向不可达的本地 socks5（无法上网），节点名直接提示续费。
    private fun buildExpiredProfileYaml(): String = """
        mixed-port: 7890
        mode: rule
        proxies:
          - name: "$EXPIRED_NODE_NAME"
            type: socks5
            server: 127.0.0.1
            port: 1
        proxy-groups:
          - name: "节点选择"
            type: select
            proxies:
              - "$EXPIRED_NODE_NAME"
        rules:
          - DOMAIN-SUFFIX,${DomainProfile.OFFICIAL_DOMAIN_SUFFIX},DIRECT
          - MATCH,节点选择
    """.trimIndent()

    private fun writeExpiredProfileConfig(uuid: UUID) {
        val config = filesDir.resolve("pending").resolve(uuid.toString()).resolve("config.yaml")
        config.parentFile?.mkdirs()
        config.writeText(buildExpiredProfileYaml())
    }

    private fun expiredProfileUuid(): UUID? =
        activationStore().getString(KEY_EXPIRED_PROFILE_UUID, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private suspend fun onExpiredPlaceholder(): Boolean {
        val expiredUuid = expiredProfileUuid() ?: return false
        val active = withProfile { queryActive() } ?: return false
        return active.uuid == expiredUuid
    }

    // 提取码到期：切换到占位配置（保持可“开启”但不可上网），而不是直接拒绝。
    private suspend fun MainDesign.activateExpiredProfile() {
        val existing = expiredProfileUuid()
        withProfile {
            val uuid = if (existing != null && queryByUUID(existing) != null) {
                existing
            } else {
                val created = create(Profile.Type.File, EXPIRED_PROFILE_NAME)
                this@MainActivity.writeExpiredProfileConfig(created)
                commit(created, null)
                activationStore().edit()
                    .putString(KEY_EXPIRED_PROFILE_UUID, created.toString())
                    .apply()
                created
            }
            queryByUUID(uuid)?.let { setActive(it) }
        }
        fetch()
    }

    // 续费恢复：签发一次性票据并轮换设备凭据，成功后替换正式订阅并删除占位配置。
    private suspend fun MainDesign.recoverFromExpired(code: String) {
        val apiBase = loadManagedCredentials()?.apiBase
            ?.ifBlank { EndpointResolver.apiBase() }
            ?: EndpointResolver.apiBase()
        val ticket = issueManagedImportTicket(code, apiBase)
        val credentials = exchangeManagedImportTicket(
            ticket,
            apiBase,
            stableClientId(),
        ).copy(accessCode = code)
        if (isExpired(credentials.expiresAt)) return
        val content = fetchManagedSubscription(credentials)
        val expiredUuid = expiredProfileUuid()
        val uuid = installManagedProfile(code, content)
        saveManagedCredentials(credentials)
        withProfile {
            if (expiredUuid != null) {
                runCatching { delete(expiredUuid) }
            }
        }
        activationStore().edit()
            .putString(KEY_CODE, code)
            .putString(KEY_EXPIRES_AT, credentials.expiresAt)
            .putString(KEY_PROFILE_UUID, uuid.toString())
            .putLong(KEY_UPDATE_VERSION, queryUpdateVersion(code) ?: 0L)
            .remove(KEY_EXPIRED_PROFILE_UUID)
            .apply()
        fetch()
        showToast(R.string.subscription_recovered, ToastDuration.Long)
    }

    private suspend fun isActivationStillValid(code: String): Boolean {
        val expiresAt = activationStore().getString(KEY_EXPIRES_AT, "").orEmpty()
        if (expiresAt.isNotBlank() && expiresAt != "null") {
            return !isExpired(expiresAt)
        }

        return code.isNotBlank()
    }

    private fun isExpired(value: String): Boolean {
        if (value.isBlank() || value == "null") {
            return false
        }

        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
        val expiry = patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value.take(19))
            }.getOrNull()
        } ?: return false

        return expiry.before(Date())
    }

    private suspend fun MainDesign.showNodeSelector() {
        if (!clashRunning) {
            showToast(R.string.select_node_start_first, ToastDuration.Long)
            return
        }

        try {
            val managed = withProfile {
                queryActive()?.uuid == managedProfileUuid()
            }
            val (groupName, nodes, selected) = withClash {
                val mode = queryTunnelState().mode
                val names = queryProxyGroupNames(true)
                val preferredName = if (managed) {
                    ProxyGroupResolver.managedGroupNames(mode, names).firstOrNull()
                } else {
                    val groups = names.map { name ->
                        name to queryProxyGroup(name, ProxySort.Delay)
                    }
                    groups.firstOrNull { (name, group) ->
                        val lower = name.lowercase(Locale.ROOT)
                        group.type == Proxy.Type.Selector &&
                            group.proxies.any {
                                !it.type.group &&
                                    it.type != Proxy.Type.Direct &&
                                    it.type != Proxy.Type.Reject
                            } &&
                            (name.contains("节点") ||
                                name.contains("选择") ||
                                lower.contains("proxy") ||
                                lower.contains("select"))
                    }?.first ?: groups.firstOrNull { (_, group) ->
                        group.type == Proxy.Type.Selector &&
                            group.proxies.any {
                                !it.type.group &&
                                    it.type != Proxy.Type.Direct &&
                                    it.type != Proxy.Type.Reject
                            }
                    }?.first
                }

                if (preferredName == null) {
                    Triple("", emptyList<Proxy>(), "")
                } else {
                    val group = queryProxyGroup(preferredName, ProxySort.Delay)
                    Triple(
                        preferredName,
                        group.proxies.filter { !it.type.group && it.type != Proxy.Type.Direct && it.type != Proxy.Type.Reject },
                        group.now
                    )
                }
            }

            if (groupName.isBlank() || nodes.isEmpty()) {
                showToast(R.string.select_node_empty, ToastDuration.Long)
                return
            }

            val labels = nodes.map {
                val delay = if (it.delay > 0) " - ${it.delay}ms" else ""
                "${it.title.ifBlank { it.name }}$delay"
            }.toTypedArray()
            val checked = nodes.indexOfFirst { it.name == selected }

            withContext(Dispatchers.Main) {
                var pending = checked.coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.select_node)
                    .setSingleChoiceItems(labels, checked) { _, which ->
                        pending = which
                    }
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        launch {
                            val applied = withClash {
                                patchSelector(groupName, nodes[pending].name)
                            }
                            showToast(
                                if (applied) R.string.select_node_applied else R.string.select_node_failed,
                                ToastDuration.Long
                            )
                            fetch()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            showExceptionToast(e)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
