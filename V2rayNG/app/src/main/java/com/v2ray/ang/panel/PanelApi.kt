package com.v2ray.ang.panel

import com.tencent.mmkv.MMKV
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
                // 面板用户中心页面内嵌订阅链接，形如 https://panel.020178.xyz/link/xxx?sub=1
                val regex = Regex("""https?://[^"'<>\s]+/link/[^"'<>\s?]+(?:\?[^"'<>\s]*)?""")
                val match = regex.find(html)
                if (match != null) {
                    var url = match.value
                    // 确保带 sub 参数以获取订阅格式
                    if (!url.contains("sub=")) {
                        url += if (url.contains("?")) "&sub=1" else "?sub=1"
                    }
                    LogUtil.i(TAG, "got subscription url")
                    return@withContext ApiResult.Success(url)
                }
                ApiResult.Failure("未找到订阅链接，请确认账号有效")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "fetchSubscriptionUrl error", e)
            ApiResult.Failure("网络错误：${e.message}")
        }
    }

    fun clearCookies() {
        cookieJar.clear()
    }

    /**
     * 获取面板域名的 cookie 字符串，用于 WebView 同步登录态
     */
    fun getCookieHeader(): String {
        val serialized = cookieStore.decodeString("cookies_${PanelConfig.PANEL_HOST}")
        if (serialized.isNullOrEmpty()) return ""
        // MMKV 里存的是 "name=value;name=value" 格式，转成 Cookie 头格式
        return serialized.split(";").joinToString("; ") { it.trim() }
    }
}
