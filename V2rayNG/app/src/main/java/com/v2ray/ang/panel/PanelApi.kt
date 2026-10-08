package com.v2ray.ang.panel


import com.tencent.mmkv.MMKV
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
data class PanelUserInfo(
    val planName: String = "",
    val expireDate: String = "",
    val trafficUsed: String = "",
    val trafficTotal: String = "",
    val trafficPercent: Float = 0f
)

/**
 * 机场面板 API 客户端
 *
 * 直接调用面板 Web 接口（SSPanel-Uim），session 机制：
 * 登录/注册成功后服务端 Set-Cookie，后续请求自动携带。
 * Cookie 持久化到 MMKV，App 重启后仍有效。
 */
object PanelApi {
    private const val TAG = "PanelApi"
    private const val MMKV_ID = "panel_cookies"

    private val cookieStore: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    private val cookieJar = object : CookieJar {
        private val memoryCache = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            memoryCache[url.host] = cookies.toMutableList()
            // 持久化
            val serialized = cookies.joinToString(";") { "${it.name}=${it.value}" }
            cookieStore.encode("cookies_${url.host}", serialized)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            memoryCache[url.host]?.let { return it }
            val serialized = cookieStore.decodeString("cookies_${url.host}") ?: return emptyList()
            if (serialized.isEmpty()) return emptyList()
            val cookies = serialized.split(";").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else Cookie.Builder()
                    .name(pair.substring(0, idx))
                    .value(pair.substring(idx + 1))
                    .domain(url.host)
                    .path("/")
                    .build()
            }
            memoryCache[url.host] = cookies.toMutableList()
            return cookies
        }

        fun clear() {
            memoryCache.clear()
            cookieStore.clearAll()
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    sealed class ApiResult {
        data class Success(val message: String = "") : ApiResult()
        data class Failure(val message: String) : ApiResult()
    }

    /**
     * 登录面板
     * POST /auth/login {email, password, remember_me}
     * 成功：返回 HX-Redirect 头；失败：JSON {ret:0, msg}
     */
    suspend fun login(email: String, password: String): ApiResult = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("email", email.trim().lowercase())
                .add("password", password)
                .add("remember_me", "true")
                .build()
            val request = okhttp3.Request.Builder()
                .url(PanelConfig.PANEL_BASE_URL + PanelConfig.PATH_LOGIN)
                .post(body)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body.string()
                // 成功时服务端返回 HX-Redirect 头
                if (resp.header("HX-Redirect") != null) {
                    LogUtil.i(TAG, "login success")
                    return@withContext ApiResult.Success()
                }
                val msg = try {
                    JSONObject(respBody).optString("msg", "登录失败")
                } catch (_: Exception) {
                    "登录失败 (${resp.code})"
                }
                LogUtil.i(TAG, "login failed: $msg")
                ApiResult.Failure(msg)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "login error", e)
            ApiResult.Failure("网络错误：${e.message}")
        }
    }

    /**
     * 注册面板账号
     * POST /auth/register {email, name, password, confirm_password, tos, invite_code}
     */
    suspend fun register(
        email: String,
        password: String,
        inviteCode: String = ""
    ): ApiResult = withContext(Dispatchers.IO) {
        try {
            val builder = FormBody.Builder()
                .add("email", email.trim().lowercase())
                .add("name", email.substringBefore("@"))
                .add("password", password)
                .add("confirm_password", password)
                .add("tos", "true")
            if (inviteCode.isNotBlank()) builder.add("invite_code", inviteCode.trim())
            val request = okhttp3.Request.Builder()
                .url(PanelConfig.PANEL_BASE_URL + PanelConfig.PATH_REGISTER)
                .post(builder.build())
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body.string()
                if (resp.header("HX-Redirect") != null) {
                    LogUtil.i(TAG, "register success")
                    return@withContext ApiResult.Success()
                }
                val msg = try {
                    JSONObject(respBody).optString("msg", "注册失败")
                } catch (_: Exception) {
                    "注册失败 (${resp.code})"
                }
                LogUtil.i(TAG, "register failed: $msg")
                ApiResult.Failure(msg)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "register error", e)
            ApiResult.Failure("网络错误：${e.message}")
        }
    }

    /**
     * 获取当前账号的订阅链接
     * GET /user（需登录态），从页面 HTML 中解析订阅地址
     */
    suspend fun fetchSubscriptionUrl(): ApiResult = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(PanelConfig.PANEL_BASE_URL + PanelConfig.PATH_USER)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Failure("获取订阅失败 (${resp.code})，请重新登录")
                }
                val html = resp.body.string()
                // 尝试多种订阅链接格式
                // 1. 面板用户中心页面内嵌订阅链接，形如 https://panel.020178.xyz/link/xxx
                //    V2Ray 格式是在默认订阅地址后加 /v2ray
                val patterns = listOf(
                    Regex("""https?://[^"'<>\s]+/link/[^"'<>\s?/]+"""),
                    Regex("""data-clipboard-text\s*=\s*["'](https?://[^"'<>]+)["']"""),
                    Regex("""["'](https?://[^"'<>]*?/link/[^"'<>?/]+)["']""")
                )
                for (pattern in patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        // 取第一个捕获组（如果是 data-clipboard-text 格式），否则取整个匹配
                        var baseUrl = if (match.groups.size > 1 && match.groups[1] != null) {
                            match.groups[1]!!.value
                        } else {
                            match.value
                        }
                        // 去掉可能已有的格式后缀
                        baseUrl = baseUrl.replace(Regex("/(v2ray|clash|surge|singbox|json|sip008|shadowsocks|trojan)$"), "")
                        baseUrl = baseUrl.replace(Regex("""\?.*$"""), "")
                        val url = "$baseUrl/v2ray"
                        LogUtil.i(TAG, "got subscription url")
                        return@withContext ApiResult.Success(url)
                    }
                }
                ApiResult.Failure("未找到订阅链接，请确认账号有效")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "fetchSubscriptionUrl error", e)
            ApiResult.Failure("网络错误：${e.message}")
        }
    }

    suspend fun fetchUserInfo(): PanelUserInfo = withContext(Dispatchers.IO) {
        // 先尝试从订阅的 Subscription-Userinfo 头获取（最可靠）
        try {
            val subUrl = PanelSession.getSubscriptionUrl()
            if (subUrl.isNotBlank()) {
                val subRequest = okhttp3.Request.Builder().url(subUrl).get().build()
                client.newCall(subRequest).execute().use { subResp ->
                    val userinfoHeader = subResp.header("Subscription-Userinfo")
                        ?: subResp.header("subscription-userinfo")
                    if (!userinfoHeader.isNullOrBlank()) {
                        // 格式: upload=123; download=456; total=789; expire=1234567890
                        val map = userinfoHeader.split(";").mapNotNull { part ->
                            val kv = part.trim().split("=", limit = 2)
                            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                        }.toMap()
                        val upload = map["upload"]?.toLongOrNull() ?: 0L
                        val download = map["download"]?.toLongOrNull() ?: 0L
                        val total = map["total"]?.toLongOrNull() ?: 0L
                        val expireTs = map["expire"]?.toLongOrNull() ?: 0L
                        if (total > 0) {
                            val used = upload + download
                            val percent = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            val expireDate = if (expireTs > 0) {
                                try {
                                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                        .format(java.util.Date(expireTs * 1000))
                                } catch (_: Exception) { "" }
                            } else ""
                            return@withContext PanelUserInfo(
                                "", expireDate,
                                formatBytes(used), formatBytes(total), percent
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // 回退：解析 /user 页面 HTML
        try {
            val request = okhttp3.Request.Builder()
                .url(PanelConfig.PANEL_BASE_URL + PanelConfig.PATH_USER)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext PanelUserInfo()
                val html = resp.body.string()
                var used = ""
                var total = ""
                var percent = 0f
                Regex("""([\d.]+\s*[KMGT]?B)\s*/\s*([\d.]+\s*[KMGT]?B)""").find(html)?.let {
                    used = it.groups[1]?.value ?: ""
                    total = it.groups[2]?.value ?: ""
                }
                Regex("""(\d+(?:\.\d+)?)\s*%""").find(html)?.let {
                    percent = it.groups[1]?.value?.toFloatOrNull()?.div(100f) ?: 0f
                }
                var expire = ""
                Regex("""到期[^<]{0,30}?(\d{4}-\d{2}-\d{2})""").find(html)?.let {
                    expire = it.groups[1]?.value ?: ""
                }
                PanelUserInfo("", expire, used, total, percent.coerceIn(0f, 1f))
            }
        } catch (_: Exception) {
            PanelUserInfo()
        }
    }

    fun clearCookies() {
        cookieJar.clear()
    }

    /**
     * 获取用于 WebView 的 Cookie 字符串
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.size - 1) {
            value /= 1024
            idx++
        }
        return String.format(java.util.Locale.getDefault(), "%.2f %s", value, units[idx])
    }

    fun getCookieHeader(): String {
        return try {
            val host = PanelConfig.PANEL_BASE_URL.toHttpUrlOrNull()?.host ?: return ""
            val serialized = cookieStore.decodeString("cookies_${host}") ?: return ""
            serialized.split(";").joinToString("; ") { it.trim() }
        } catch (_: Exception) {
            ""
        }
    }

}
