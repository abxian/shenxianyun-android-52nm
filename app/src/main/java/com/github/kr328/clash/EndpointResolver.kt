package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端点发现（去掉写死 sub.jc116.com）：
 * 启动时从「发现源」拉取 endpoints.json，得到 api_bases / sub_base / download_base，
 * 之后换域名 / 换内穿 / 切线路只改后台 + endpoints.json，客户端下次启动自动跟随。
 *
 * 发现失败 → 用本地缓存；再没有 → 用内置默认。任何一步都不阻塞启动、不抛异常。
 */
object EndpointResolver {
    /** 52nm 国内主线路与两个国外 443 备用域名。始终按配置顺序排列。 */
    private val PINNED_API_BASES = DomainProfile.API_BASES

    /** 写死主线路条数，UI 仅在全部固定线路不可用时显示动态兜底。 */
    val PINNED_COUNT = PINNED_API_BASES.size

    /** 内置兜底默认值（发现源全挂时用第一条写死线路）。 */
    const val DEFAULT_API_BASE = DomainProfile.DOMESTIC_API_BASE

    /** 完整线路 = 写死主线路(神仙云1/2) + 发现的 api_bases(神仙云3/4…, 去重排后)。 */
    private fun allApiBases(): List<String> {
        val merged = PINNED_API_BASES.toMutableList()
        for (b in cachedBases()) if (b !in merged) merged.add(b)
        return merged
    }

    // 发现锚点：独立站动态接口优先，GitHub 与国外站点作为备用。
    private val DISCOVERY_URLS = DomainProfile.DISCOVERY_URLS

    // 使用独立存储，覆盖安装旧客户端时不会继续读取旧 sxnn/jc116 缓存。
    private const val STORE = "shenxianyun_52nm_endpoints"
    private const val KEY_API_BASES = "api_bases"
    private const val KEY_ACTIVE_BASE = "api_base_active"
    private const val KEY_SUB_BASE = "sub_base"
    private const val KEY_DOWNLOAD_BASE = "download_base"
    private const val KEY_BOOTSTRAP_PROXY = "bootstrap_proxy"

    private const val DISCOVERY_TIMEOUT_MS = 8_000
    private const val PROBE_TIMEOUT_MS = 5_000

    private val prefs
        get() = Global.application.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun normalizeBase(value: String?): String {
        val v = value?.trim()?.trimEnd('/') ?: return ""
        return if (v.startsWith("http://") || v.startsWith("https://")) v else ""
    }

    private fun cachedBases(): List<String> =
        (prefs.getString(KEY_API_BASES, null) ?: "")
            .split('\n')
            .map { normalizeBase(it) }
            .filter { it.isNotEmpty() }

    /** 当前应使用的 API 基址：探测出的可用地址 > 缓存列表第一个 > 内置默认。同步、永不抛错。 */
    fun apiBase(): String {
        normalizeBase(prefs.getString(KEY_ACTIVE_BASE, null)).takeIf { it.isNotEmpty() }?.let { return it }
        return allApiBases().firstOrNull() ?: DEFAULT_API_BASE
    }

    fun downloadBase(): String = normalizeBase(prefs.getString(KEY_DOWNLOAD_BASE, null))

    /** 后台下发的兜底代理（http://[user:pass@]host:port，也支持 socks5://）。无则空串。 */
    fun bootstrapProxy(): String {
        val v = prefs.getString(KEY_BOOTSTRAP_PROXY, null)?.trim() ?: return ""
        return if (Regex("^(https?|socks5h?)://").containsMatchIn(v)) v else ""
    }

    /** 用兜底代理打开一个连接；无代理配置时退回直连。解析 http/socks5 与可选账号密码。 */
    fun openViaBootstrap(url: String, timeoutMs: Int): HttpURLConnection? {
        val proxyUrl = bootstrapProxy().ifEmpty { return null }
        return try {
            // 用 java.net.URI 稳妥解析 scheme/host/port/userinfo
            val uri = java.net.URI(proxyUrl)
            val type = if (uri.scheme.startsWith("socks", true))
                java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
            val port = if (uri.port > 0) uri.port else if (type == java.net.Proxy.Type.SOCKS) 1080 else 8080
            val proxy = java.net.Proxy(type, java.net.InetSocketAddress(uri.host, port))
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            // HTTP 代理的账号密码走 Proxy-Authorization 头（Basic）
            val userInfo = uri.userInfo
            if (type == java.net.Proxy.Type.HTTP && !userInfo.isNullOrEmpty()) {
                val token = android.util.Base64.encodeToString(
                    userInfo.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                conn.setRequestProperty("Proxy-Authorization", "Basic $token")
            }
            conn
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "JC116-Shenxianyun-Android")
        }
        return try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    /** 拉取发现源（依次尝试），成功则写入缓存。全部失败静默返回（保留旧缓存）。 */
    private fun refresh() {
        for (url in DISCOVERY_URLS) {
            try {
                val (code, body) = httpGet(url, DISCOVERY_TIMEOUT_MS)
                if (code !in 200..299 || body.isBlank()) continue
                val json = JSONObject(body)
                val basesJson = json.optJSONArray("api_bases") ?: continue
                val bases = (0 until basesJson.length())
                    .map { normalizeBase(basesJson.optString(it)) }
                    .filter { it.isNotEmpty() }
                if (bases.isEmpty()) continue
                prefs.edit()
                    .putString(KEY_API_BASES, bases.joinToString("\n"))
                    .putString(KEY_SUB_BASE, normalizeBase(json.optString("sub_base")))
                    .putString(KEY_DOWNLOAD_BASE, normalizeBase(json.optString("download_base")))
                    .putString(KEY_BOOTSTRAP_PROXY, json.optString("bootstrap_proxy").trim())
                    .apply()
                return
            } catch (_: Exception) {
                // 下一个发现源
            }
        }
    }

    private fun probe(base: String): Boolean = try {
        httpGet("$base/api/app-version", PROBE_TIMEOUT_MS).first in 200..299
    } catch (_: Exception) {
        false
    }

    /** 逐个探测 api_bases，第一个通的设为 active。 */
    private fun pickApiBase() {
        val bases = allApiBases()
        for (base in bases) {
            if (probe(base)) {
                prefs.edit().putString(KEY_ACTIVE_BASE, base).apply()
                return
            }
        }
    }

    /** 启动时后台调用一次：刷新发现源 + 探测可用基址。IO 线程执行，静默失败。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            refresh()
            pickApiBase()
        } catch (_: Exception) {
            // 发现失败不影响任何功能，继续用缓存/默认
        }
    }

    /** 当前全部候选线路（发现缓存优先，否则内置列表），给线路选择 UI 用。 */
    fun basesForUi(): List<String> = allApiBases()

    /** 手动指定当前线路（线路选择弹窗点选时用）。 */
    fun setActive(base: String) {
        normalizeBase(base).takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString(KEY_ACTIVE_BASE, it).apply()
        }
    }

    /** 探测单条线路是否可用（UI 用，IO 线程执行）。 */
    suspend fun probeBase(base: String): Boolean = withContext(Dispatchers.IO) { probe(base) }

    /** 请求失败时调用：把当前 active 基址作废并顺延到下一条可用线路。IO 线程执行。 */
    suspend fun rotate(): String = withContext(Dispatchers.IO) {
        val bad = apiBase()
        try {
            for (base in allApiBases().filter { it != bad }) {
                if (probe(base)) {
                    prefs.edit().putString(KEY_ACTIVE_BASE, base).apply()
                    return@withContext base
                }
            }
        } catch (_: Exception) {
            // 保持原地址
        }
        bad
    }
}
