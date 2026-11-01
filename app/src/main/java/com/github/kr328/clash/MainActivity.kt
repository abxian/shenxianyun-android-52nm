package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.github.kr328.clash.design.R

class MainActivity : BaseActivity<MainDesign>() {
    private var selectedMode = TunnelState.Mode.Rule
    private val subscriptionRefreshMutex = Mutex()
    private val accessStateMutex = Mutex()
    private var subscriptionCheckNotBefore = 0L
    private var powerOperationInFlight = false
    private var hadRunningTunnel = false
    private var subscriptionRefreshJob: Deferred<Boolean>? = null
    private var subscriptionRefreshCode = ""

    private data class TrafficTotals(val upload: Long, val download: Long) {
        operator fun minus(other: TrafficTotals) = TrafficTotals(
            (upload - other.upload).coerceAtLeast(0L),
            (download - other.download).coerceAtLeast(0L),
        )

        operator fun plus(other: TrafficTotals) = TrafficTotals(
            upload + other.upload,
            download + other.download,
        )

        fun total() = upload + download

        fun regressedFrom(previous: TrafficTotals) =
            upload < previous.upload || download < previous.download
    }

    private companion object {
        const val ACTIVATION_STORE = ACTIVATION_STORE_NAME
        const val KEY_CODE = "code"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_PROFILE_UUID = "profile_uuid"
        const val KEY_UPDATE_VERSION = "update_version"
        const val KEY_SUBSCRIPTION_REVISION = "subscription_revision"
        const val KEY_ACCESS_RESTRICTION = "access_restriction"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_PENDING_PRESENCE = "pending_presence"
        const val KEY_EXPIRED_PROFILE_UUID = "expired_profile_uuid"
        // 前台或VPN运行均检查：基础30秒，失败指数退避，后台返回立即唤醒。
        const val UPDATE_CHECK_INTERVAL_MILLIS = 30_000L
        const val UPDATE_CHECK_MAX_INTERVAL_MILLIS = 300_000L
        const val HEARTBEAT_INTERVAL_MILLIS = 120_000L
        const val HEARTBEAT_JITTER_MILLIS = 20_000L
        const val EXPIRED_NODE_NAME = "提取码到期，请续费使用"
        const val EXPIRED_PROFILE_NAME = "提取码已到期"

        // 2 means a download succeeded; foreground opens still force a fresh download.
        val startupSubscriptionRefreshState = AtomicInteger(0)
        val openingRefreshRequested = AtomicBoolean(false)
    }

    private fun nextHeartbeatDelayMillis(): Long =
        HEARTBEAT_INTERVAL_MILLIS + (Math.random() * HEARTBEAT_JITTER_MILLIS).toLong()

    private fun savePendingPresence(code: String, status: String): String {
        val id = UUID.randomUUID().toString()
        activationStore().edit()
            .putString(KEY_PENDING_PRESENCE, "$id|$code|$status")
            .apply()
        return id
    }

    private fun clearPendingPresence(id: String) {
        val current = activationStore().getString(KEY_PENDING_PRESENCE, "").orEmpty()
        if (current.startsWith("$id|")) {
            activationStore().edit().remove(KEY_PENDING_PRESENCE).apply()
        }
    }

    private data class SubscriptionUpdateState(
        val updateVersion: Long?,
        val expiresAt: String?,
        val revision: String?,
    )

    override suspend fun main() {
        val design = MainDesign(this)
        stableClientId()

        setContentDesign(design)
        launch { configureServiceTrafficReporting() }
        // Enter the request loop immediately; service binding and discovery must
        // never hold every click behind their network/startup waits.
        launch {
            try { ensureDefaultMetaFeatures() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            try { design.resetModeForLaunch() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            design.safeFetch()
        }
        launch { runCatching { design.checkAppUpdate() } }
        launch { runCatching { EndpointResolver.initialize(); refreshLineStatus(design) } }
        if (savedActivationCode().isNotBlank() && openingRefreshRequested.compareAndSet(false, true))
            design.requestSubscriptionRefresh(force = true)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))
        var lastSubscriptionUpdateCheck = 0L
        var nextHeartbeatAt = 0L
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
                        Event.ActivityStart -> {
                            launch { design.safeFetch() }
                            // Returning from payment must not wait for the periodic timer.
                            lastSubscriptionUpdateCheck = 0L
                        }
                        Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> launch { design.safeFetch() }
                        Event.ClashStart -> {
                            hadRunningTunnel = true
                            nextHeartbeatAt = System.currentTimeMillis() + nextHeartbeatDelayMillis()
                            launch { design.safeFetch() }
                            launch { sendClientHeartbeat("online") }
                        }
                        Event.ClashStop -> {
                            launch { design.safeFetch() }
                            nextHeartbeatAt = 0L
                            launch { sendClientHeartbeat("offline") }
                            if (hadRunningTunnel) {
                                hadRunningTunnel = false
                                launch {
                                    val code = savedActivationCode()
                                    if (code.isNotBlank()) design.importSubscriptionCode(code, silent = true, notifySuccess = false)
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (!powerOperationInFlight) {
                                powerOperationInFlight = true
                                launch {
                                    design.setStarting(true)
                                    try {
                                        if (clashRunning) stopClashService()
                                        else {
                                            val entered = design.enteredAccessCode()
                                            if (savedActivationCode().isBlank()) {
                                                if (entered.isBlank()) {
                                                    design.setSubscriptionStatus(getString(R.string.import_code_empty), false)
                                                } else if (design.importSubscriptionCode(entered)) {
                                                    design.startClash()
                                                }
                                            } else design.startClash()
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        design.showExceptionToast(e)
                                    } finally {
                                        powerOperationInFlight = false
                                        design.setStarting(false)
                                    }
                                }
                            }
                        }
                        MainDesign.Request.ImportByCode ->
                            design.showCodeImportDialog()
                        MainDesign.Request.UpdateSubscription -> {
                            val code = savedActivationCode()
                            if (code.isBlank()) {
                                design.showCodeImportDialog()
                            } else {
                                design.requestSubscriptionRefresh(force = true)
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
                        runCatching { design.fetchTraffic() }
                        if (now >= nextHeartbeatAt) {
                            nextHeartbeatAt = now + nextHeartbeatDelayMillis()
                            launch { sendClientHeartbeat("online") }
                        }
                        // Accounting belongs to the foreground VPN service, not this Activity.
                    } else {
                        // Subscription checks below continue with the VPN stopped.
                    }
                    // VPN停止也继续检查；不修改用户连接意图。
                    if ((clashRunning || activityStarted) &&
                        !powerOperationInFlight && subscriptionRefreshJob?.isActive != true && !subscriptionRefreshMutex.isLocked &&
                        !subscriptionUpdateInFlight &&
                        now - lastSubscriptionUpdateCheck >= subscriptionCheckDelay
                    ) {
                        lastSubscriptionUpdateCheck = now
                        subscriptionUpdateInFlight = true
                        launch {
                            val ok = try {
                                design.checkSubscriptionUpdate()
                                true
                            } catch (error: CancellationException) { throw error
                            } catch (_: Exception) { false }
                            subscriptionFailures = if (ok) 0 else minOf(subscriptionFailures + 1, 4)
                            // 失败可能是当前 API 线路失联：后台切到下一条可用线路（api_bases 顺延）。
                            if (!ok) runCatching { EndpointResolver.rotate() }
                            val backoff = minOf(
                                UPDATE_CHECK_INTERVAL_MILLIS shl subscriptionFailures,
                                UPDATE_CHECK_MAX_INTERVAL_MILLIS,
                            )
                            // 50%~100% 抖动，打散各客户端的请求时间。
                            subscriptionCheckDelay = (backoff * (0.8 + Math.random() * 0.4)).toLong()
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
        // 只保存最后一次期望状态，不堆积历史心跳；离线会覆盖更早的在线。
        val pendingId = savePendingPresence(code, status)
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
            try {
                val primary = runCatching {
                    com.github.kr328.clash.service.traffic.RelayTransport.request(
                        this@MainActivity, "${credentials.apiBase}/api/v2/client/$path", "POST",
                        credentials.deviceToken, JSONObject(String(body, Charsets.UTF_8)))
                }.getOrNull()
                val reply = if (primary != null && primary.status < 500) primary else
                    com.github.kr328.clash.service.traffic.RelayTransport.presence(
                        this@MainActivity, credentials.deviceToken, path, JSONObject(String(body, Charsets.UTF_8)))
                val statusCode = reply.status
                val json = reply.json
                if (
                    status != "offline" &&
                    statusCode == 403 &&
                    (json?.optString("code") == "device_limit" ||
                        json?.optString("code") == "traffic_limit")
                ) {
                    if (savedActivationCode() == code && loadManagedCredentials()?.deviceToken == credentials.deviceToken) {
                        activationStore().edit().putString(KEY_ACCESS_RESTRICTION, json?.optString("code", "unavailable")).apply()
                        design?.setRenewalNeeded(json?.optString("code") == "traffic_limit")
                        enforceClientLimit(
                        json?.optString("message", "套餐设备或流量额度已达到上限")
                            ?: "套餐设备或流量额度已达到上限",
                        )
                    }
                } else if (
                    status != "offline" &&
                    statusCode in 200..299 &&
                    json?.optBoolean("ok", false) == true
                ) {
                    syncActivationExpiresAt(code, json.optString("expires_at", ""))
                }
                if (statusCode in 200..299 && json?.optBoolean("ok", false) == true) {
                    clearPendingPresence(pendingId)
                }
            } catch (_: Exception) {
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
                statusCode in 200..299 &&
                json?.optBoolean("ok", false) == true
            ) {
                syncActivationExpiresAt(code, json.optString("expires_at", ""))
            }
            if (statusCode in 200..299 && json?.optBoolean("ok", false) == true) {
                clearPendingPresence(pendingId)
            }
        } catch (_: Exception) {
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun configureServiceTrafficReporting() {
        val code=savedActivationCode()
        val credentials=loadManagedCredentials()?.takeIf {it.accessCode==code} ?: return
        val profile=activationStore().getString(KEY_PROFILE_UUID,null) ?: return
        val config=JSONObject().put("code",code).put("client_id",stableClientId())
            .put("api_base",credentials.apiBase).put("device_token",credentials.deviceToken)
            .put("profile",profile).put("app_name", "${EndpointResolver.clientName()}安卓端")
            .put("version",queryAppVersionName().asHeaderValue())
            .put("device","${Build.BRAND} ${Build.MODEL}")
        com.github.kr328.clash.service.traffic.ServiceTrafficAccounting.configure(this,config.toString())
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
        try { fetch() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Local service refresh does not open technical error layers. */ }
    }

    private suspend fun MainDesign.fetch() {
        if (clashRunning) hadRunningTunnel = true
        setClashRunning(clashRunning)
        setActivationCode(savedActivationCode().ifBlank { null })
        setActivationExpiresAt(normalizedExpiresAt(activationStore().getString(KEY_EXPIRES_AT, null)))
        setRenewalNeeded(savedActivationCode().isNotBlank() && (
            activationStore().getString(KEY_ACCESS_RESTRICTION, "") in listOf("expired", "traffic_limit") ||
                isExpired(activationStore().getString(KEY_EXPIRES_AT, "").orEmpty())
        ))

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

    private suspend fun MainDesign.fetchTraffic(): TrafficTotals {
        return withClash {
            queryTrafficTotal().also { setForwarded(it) }
            val totals = queryTrafficTotalBytes()
            TrafficTotals(
                upload = totals.getOrElse(0) { 0L }.coerceAtLeast(0L),
                download = totals.getOrElse(1) { 0L }.coerceAtLeast(0L),
            )
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
        val content = layoutInflater.inflate(R.layout.dialog_code_import, null)
        val input = content.findViewById<EditText>(R.id.code_dialog_input)
        val status = content.findViewById<android.widget.TextView>(R.id.code_dialog_status)
        input.setText(savedActivationCode())
        input.selectAll()
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_code_confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_sxy_code_dialog)
            )
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isBlank()) {
                    status.setText(R.string.import_code_empty)
                    input.requestFocus()
                    return@setOnClickListener
                }
                confirm.isEnabled = false
                input.isEnabled = false
                launch {
                    try {
                        val imported = importSubscriptionCode(code, onStatus = { status.text = it })
                        // A failed import leaves the input and explanation visible.
                        if (imported) dialog.dismiss()
                    } finally {
                        confirm.isEnabled = true
                        input.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun MainDesign.showExpiredDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle(if (activationStore().getString(KEY_ACCESS_RESTRICTION, "") == "traffic_limit") "流量额度已用尽" else getString(R.string.import_code_expired_title))
            .setMessage("网站续费、管理员加时或额度恢复后，将自动更新当前订阅，无需重新导入。")
            .setPositiveButton(R.string.renew_code) { _, _ -> openCodeStorePage() }
            .setNeutralButton(R.string.import_by_code) { _, _ ->
                showCodeImportDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun MainDesign.requestSubscriptionRefresh(
        force: Boolean,
    ): Deferred<Boolean>? {
        val code = savedActivationCode()
        if (code.isBlank()) return null
        val existing = subscriptionRefreshJob
        if (shouldReuseSubscriptionRefresh(subscriptionRefreshCode, code, existing?.isActive == true)) {
            return existing
        }
        subscriptionRefreshCode = code
        return async {
            setSubscriptionStatus("正在更新配置…", true)
            try {
                coroutineScope {
                    val progress = launch {
                        var elapsed = 0
                        while (isActive && savedActivationCode() == code) {
                            val message = if (elapsed >= 5)
                                "配置正在后台更新（${elapsed}秒）"
                            else "正在更新配置…（${elapsed}秒）"
                            setSubscriptionStatus(message, true)
                            delay(1_000)
                            elapsed++
                        }
                    }
                    try {
                        checkSubscriptionUpdate(forceDownload = force)
                    } finally {
                        progress.cancel()
                    }
                }
                if (savedActivationCode() != code) return@async false
                setSubscriptionStatus("配置已更新", false)
                true
            } catch (error: CancellationException) {
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (savedActivationCode() == code)
                    setSubscriptionStatus("配置更新失败，已保留原配置，请稍后重试", false)
                false
            } catch (error: Exception) {
                if (savedActivationCode() == code) {
                    val message = if (error is AccessStateRejected)
                        codeImportFailureMessage(error.reason)
                    else "配置更新失败，已保留原配置，请稍后重试"
                    setSubscriptionStatus(message, false)
                }
                false
            } finally {
                if (subscriptionRefreshCode == code) setSubscriptionUpdating(false)
            }
        }.also { subscriptionRefreshJob = it }
    }

    private suspend fun MainDesign.importSubscriptionCode(
        code: String,
        silent: Boolean = false,
        notifySuccess: Boolean = true,
        knownState: SubscriptionUpdateState? = null,
        onStatus: ((String) -> Unit)? = null,
    ): Boolean = subscriptionRefreshMutex.withLock {
        // A queued refresh must not restore the code that the user has just replaced.
        if (silent && savedActivationCode() != code) return@withLock false
        try {
            if (!silent) {
                setSubscriptionStatus("正在验证提取码…", true)
                onStatus?.invoke("正在验证提取码…")
            }
            // Read authoritative status before registration: invalid and expired
            // codes must not collapse into the legacy ticket endpoint's 403.
            // Snapshot revision before download; do not consume a newer revision later.
            val updateState = knownState ?: queryUpdateState(code)

            val activeUuidBefore = withProfile { queryActive()?.uuid }
            val managedUuidBefore = managedProfileUuid()
            val expiredUuidBefore = expiredProfileUuid()
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
                            val message = "网络连接不稳定，正在重试（${attempt}/${SUBSCRIPTION_NETWORK_RETRIES}）…"
                            setSubscriptionStatus(message, true)
                            onStatus?.invoke(message)
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ManagedImportRejected) {
                        throw e // Definitive rejection must not register four times.
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
                    val message = codeImportFailureMessage("expired")
                    setSubscriptionStatus(message, false)
                    onStatus?.invoke(message)
                    return@withLock false
                }
                // 自动刷新不能只相信客户端内缓存的旧日期。续费后服务端可能已经延长
                // 设备令牌，继续尝试拉取；若确实过期，服务端会拒绝且旧配置保持不变。
            }
            if (!silent) {
                setSubscriptionStatus("提取码有效，正在获取配置…", true)
                onStatus?.invoke("提取码有效，正在获取配置…")
            }
            val content = withTimeout(20_000) { fetchManagedSubscription(credentials) }
            if (silent && savedActivationCode() != code) return@withLock false
            val expiresAt = updateState.expiresAt.orEmpty()
            if (expiresAt != credentials.expiresAt) {
                credentials = credentials.copy(expiresAt = expiresAt)
            }
            val shouldActivate = !silent ||
                activeUuidBefore == null ||
                activeUuidBefore == managedUuidBefore ||
                activeUuidBefore == expiredUuidBefore
            val uuid = installManagedProfile(code, content, activate = shouldActivate)
            saveManagedCredentials(credentials)
            val updateVersion = updateState.updateVersion
                ?: activationStore().getLong(KEY_UPDATE_VERSION, 0L)
            val editor = activationStore().edit()
                .putString(KEY_CODE, code)
                .putString(KEY_EXPIRES_AT, expiresAt)
                .putString(KEY_PROFILE_UUID, uuid.toString())
                .putLong(KEY_UPDATE_VERSION, updateVersion)
                .remove(KEY_ACCESS_RESTRICTION)
            updateState.revision?.let { editor.putString(KEY_SUBSCRIPTION_REVISION, it) }
            if (activeUuidBefore == expiredUuidBefore && expiredUuidBefore != null) {
                withProfile { runCatching { delete(expiredUuidBefore) } }
                editor.remove(KEY_EXPIRED_PROFILE_UUID)
            }
            editor.apply()
            configureServiceTrafficReporting()
            startupSubscriptionRefreshState.set(2)
            launch { safeFetch() }
            if (!silent) {
                setSubscriptionStatus("配置已导入", false)
                onStatus?.invoke("配置已导入")
            } else if (notifySuccess) {
                showToast(R.string.subscription_updated, ToastDuration.Long)
            }
            true
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            if (!silent) {
                val message = codeImportFailureMessage(networkFailure = true)
                setSubscriptionStatus(message, false)
                onStatus?.invoke(message)
            }
            false
        } catch (e: Exception) {
            if (!silent) {
                val reason = when (e) {
                    is AccessStateRejected -> e.reason
                    is ManagedImportRejected -> e.reason
                    is ManagedStateRetryAfter -> "busy"
                    else -> null
                }
                val message = codeImportFailureMessage(reason, e is java.io.IOException)
                setSubscriptionStatus(message, false)
                onStatus?.invoke(message)
            }
            false
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
        if (code.isNotBlank() && onCodeTrack) {
            // Every Start requests current nodes. Share an in-flight same-code
            // refresh, but a previously completed check never skips this update.
            val refresh = requestSubscriptionRefresh(force = true)
            when (refresh?.let { awaitStartupRefresh(it) }) {
                null -> {
                    // Keep the same refresh in the background; only an existing,
                    // still-valid managed profile may start after the UI wait expires.
                    setSubscriptionStatus("配置正在后台更新", true)
                }
                false -> return
                true -> Unit
            }
            if (activationStore().getString(KEY_ACCESS_RESTRICTION, "").orEmpty().isNotBlank() ||
                !isActivationStillValid(code) || onExpiredPlaceholder()) {
                val restriction = activationStore().getString(KEY_ACCESS_RESTRICTION, "").orEmpty()
                val message = when {
                    restriction.isNotBlank() -> codeImportFailureMessage(restriction)
                    !isActivationStillValid(code) -> codeImportFailureMessage("expired")
                    else -> "请联网更新配置后重试"
                }
                setSubscriptionStatus(message, false)
                return
            }
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

        configureServiceTrafficReporting()
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

    private fun normalizedExpiresAt(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private suspend fun syncActivationExpiresAt(code: String, value: String?): Boolean {
        val expiresAt = normalizedExpiresAt(value).orEmpty()
        if (savedActivationCode() != code) return false

        val store = activationStore()
        val storedChanged = normalizedExpiresAt(store.getString(KEY_EXPIRES_AT, null)) != expiresAt
        if (storedChanged) {
            store.edit().putString(KEY_EXPIRES_AT, expiresAt).apply()
        }

        val credentials = loadManagedCredentials()
            ?.takeIf { it.accessCode == code }
        if (credentials != null && credentials.expiresAt != expiresAt) {
            runCatching { saveManagedCredentials(credentials.copy(expiresAt = expiresAt)) }
        }

        if (storedChanged) {
            design?.setActivationExpiresAt(expiresAt)
        }
        return storedChanged
    }

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

    private class AccessStateRejected(val reason: String, message: String) : IllegalStateException(message)

    private suspend fun queryUpdateState(code: String): SubscriptionUpdateState = withTimeout(30_000L) {
        val (status, json) = readManagedUpdateState(code, stableClientId())
        if (status !in 200..299 || !json.optBoolean("ok", false)) {
            if (status == 403 || status == 404) throw AccessStateRejected(
                json.optString("code", "unavailable"), json.optString("message", "订阅暂不可用"))
            throw IllegalStateException("订阅状态检查暂不可用")
        }
        SubscriptionUpdateState(
            updateVersion = json.optLong("update_version", 0L).takeIf { it > 0L },
            expiresAt = normalizedExpiresAt(json.optString("expires_at", "")),
            revision = json.optString("subscription_revision", "").takeIf { it.isNotBlank() },
        )
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
                .setMessage(getString(R.string.app_update_message, EndpointResolver.clientName(), update.first))
                .setPositiveButton(R.string.app_update_now) { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.second)))
                }
                .setNegativeButton(R.string.app_update_later, null)
                .show()
        }
    }

    // A lightweight authoritative status read is independent of VPN state.
    private suspend fun MainDesign.checkSubscriptionUpdate(forceDownload: Boolean = false) {
        accessStateMutex.withLock {
        if (System.currentTimeMillis() < subscriptionCheckNotBefore) throw IllegalStateException("服务端要求稍后检查")
        val code = savedActivationCode()
        if (code.isBlank()) return
        val active = withProfile { queryActive() } ?: throw IllegalStateException("尚未选择提取码订阅")
        if (active.uuid != managedProfileUuid() && active.uuid != expiredProfileUuid())
            throw IllegalStateException("当前使用本地配置")
        val store = activationStore()
        val wasRestricted = store.getString(KEY_ACCESS_RESTRICTION, "").orEmpty().isNotBlank() || onExpiredPlaceholder()
        val oldExpiry = store.getString(KEY_EXPIRES_AT, "").orEmpty()
        val state = try { withTimeout(10_000) { queryUpdateState(code) } } catch (error: ManagedStateRetryAfter) {
            subscriptionCheckNotBefore = System.currentTimeMillis() + error.delayMillis
            throw error
        } catch (error: AccessStateRejected) {
            if (savedActivationCode() != code) return
            store.edit().putString(KEY_ACCESS_RESTRICTION, error.reason).apply()
            setRenewalNeeded(error.reason == "expired" || error.reason == "traffic_limit")
            if (clashRunning) enforceClientLimit(error.message ?: "订阅暂不可用")
            throw error
        }
        if (savedActivationCode() != code) return
        val expiryChanged = normalizedExpiresAt(oldExpiry) != state.expiresAt
        val needsRefresh = shouldRefreshAccessSubscription(
            startup = forceDownload || startupSubscriptionRefreshState.get() != 2,
            restricted = wasRestricted, expiryChanged = expiryChanged,
            localVersion = store.getLong(KEY_UPDATE_VERSION, 0L), remoteVersion = state.updateVersion ?: 0L,
            localRevision = store.getString(KEY_SUBSCRIPTION_REVISION, ""), remoteRevision = state.revision,
        )
        if (needsRefresh) {
            if (!importSubscriptionCode(code, silent = true, notifySuccess = false, knownState = state)) {
                throw IllegalStateException("恢复订阅暂未完成，将自动重试")
            }
        }
        if (savedActivationCode() != code) return
        store.edit().remove(KEY_ACCESS_RESTRICTION).apply()
        syncActivationExpiresAt(code, state.expiresAt)
        setRenewalNeeded(false)
        }
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
