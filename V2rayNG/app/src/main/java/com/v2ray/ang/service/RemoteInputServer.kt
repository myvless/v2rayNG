package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.panel.PanelSession
import com.v2ray.ang.panel.RemoteInputAuth
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.UUID

/**
 * 远程输入的内嵌 HTTP 服务（NanoHTTPD 子类）。
 *
 * 只绑局域网 IP（由 RemoteInputService 传入），handler 层再校验远端地址
 * 为局域网/回环才放行；手机页是内联 CSS/JS 的静态 HTML，不依赖外网 CDN。
 *
 * 安全边界（调用方与使用者都必须清楚）：
 * - 手机 → 盒子这一段是局域网 HTTP 明文，只在可信家庭 Wi-Fi 下用。
 * - 密码只存在于当次 POST 的内存变量，用完即清，不写 MMKV、不记日志、
 *   不返回给手机页；NanoHTTPD 的参数表会在 serve() 返回后随会话丢弃。
 * - 配对码与 session token 只存在于内存，服务停止即失效。
 */
class RemoteInputServer(
    hostname: String,
    port: Int,
    private val pairingCode: String,
    private val texts: RemoteInputTexts,
    private val onLoginCompleted: () -> Unit
) : NanoHTTPD(hostname, port) {

    companion object {
        private const val TAG = "RemoteInputServer"
        private const val HTML_MIME = "text/html; charset=utf-8"
    }

    /** 手机页文案：由 Service 从 Android strings 资源构造，禁止在 HTML 里写死文案。 */
    data class RemoteInputTexts(
        val pageTitle: String,
        val pairingTitle: String,
        val pairingHint: String,
        val pairingButton: String,
        val pairingError: String,
        val pairingSuccess: String,
        val notPaired: String,
        val loginTitle: String,
        val emailLabel: String,
        val passwordLabel: String,
        val loginButton: String,
        val loginEmptyError: String,
        val loginSuccess: String,
        val alreadyLoggedIn: String,
        val subscriptionsTitle: String,
        val subRemarks: String,
        val subUrl: String,
        val subAutoUpdate: String,
        val subInterval: String,
        val subAddButton: String,
        val subAdded: String,
        val subInvalid: String,
        val loginRequired: String,
        val goLogin: String,
        val internalError: String
    )

    private val sessionTokens = Collections.synchronizedSet(mutableSetOf<String>())

    override fun serve(session: IHTTPSession): Response {
        // 先做远端校验：非局域网/回环一律 403，与绑定地址形成双保险。
        if (!RemoteInputValidation.isAllowedRemote(session.remoteIpAddress)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        return try {
            serveRouted(session)
        } catch (e: Exception) {
            // 只记操作与异常类型，不记任何请求内容、配对码、token、密码或订阅链接。
            LogUtil.e(TAG, "Remote input request failed", e)
            htmlResponse(
                Response.Status.INTERNAL_ERROR,
                page(texts.pageTitle, "<p>${texts.internalError}</p>")
            )
        }
    }

    private fun serveRouted(session: IHTTPSession): Response {
        val token = RemoteInputValidation.parseSessionToken(session.headers["cookie"])
        val paired = token != null && sessionTokens.contains(token)
        return when {
            session.uri == "/" && session.method == Method.GET ->
                htmlResponse(Response.Status.OK, pairingPage(null))

            session.uri == "/pair" && session.method == Method.POST -> {
                if (!parseForm(session)) return badRequest()
                val code = param(session, "code")
                if (RemoteInputValidation.isValidPairingCode(code, pairingCode)) {
                    val newToken = UUID.randomUUID().toString()
                    sessionTokens.add(newToken)
                    val response = htmlResponse(
                        Response.Status.OK,
                        page(
                            texts.pageTitle,
                            "<p>${texts.pairingSuccess}</p>" +
                                "<p><a href=\"/login\">${texts.goLogin}</a></p>"
                        )
                    )
                    response.addHeader(
                        "Set-Cookie",
                        "${RemoteInputValidation.SESSION_COOKIE_NAME}=$newToken; Path=/; HttpOnly"
                    )
                    response
                } else {
                    htmlResponse(Response.Status.OK, pairingPage(texts.pairingError))
                }
            }

            session.uri == "/login" && session.method == Method.GET -> {
                if (!paired) return forbiddenPage()
                if (PanelSession.isLoggedIn()) {
                    htmlResponse(Response.Status.OK, loggedInPage())
                } else {
                    htmlResponse(Response.Status.OK, loginPage(null))
                }
            }

            session.uri == "/login" && session.method == Method.POST -> {
                if (!paired) return forbiddenPage()
                if (!parseForm(session)) return badRequest()
                val email = param(session, "email").orEmpty()
                var password: String? = param(session, "password")
                try {
                    if (email.isBlank() || password.isNullOrBlank()) {
                        return htmlResponse(
                            Response.Status.OK,
                            loginPage(texts.loginEmptyError)
                        )
                    }
                    // runBlocking 只阻塞当前 NanoHTTPD 工作线程；PanelApi 内部
                    // 已切 Dispatchers.IO，登录与订阅导入都在该线程上完成。
                    val result = runBlocking {
                        RemoteInputAuth.loginAndImport(email, password!!)
                    }
                    when (result) {
                        is RemoteInputAuth.RemoteLoginResult.Success -> {
                            onLoginCompleted()
                            LogUtil.i(TAG, "Remote login succeeded")
                            htmlResponse(
                                Response.Status.OK,
                                page(
                                    texts.pageTitle,
                                    "<p>${texts.loginSuccess}</p>" +
                                        "<p><a href=\"/subscriptions\">${texts.subscriptionsTitle}</a></p>"
                                )
                            )
                        }
                        is RemoteInputAuth.RemoteLoginResult.AlreadyLoggedIn ->
                            htmlResponse(Response.Status.OK, loggedInPage())
                        is RemoteInputAuth.RemoteLoginResult.Failure -> {
                            LogUtil.i(TAG, "Remote login failed")
                            htmlResponse(
                                Response.Status.OK,
                                loginPage(RemoteInputValidation.escapeHtml(result.message))
                            )
                        }
                    }
                } finally {
                    // 密码用完即清引用；参数表随 NanoHTTPD 会话结束而丢弃。
                    password = null
                }
            }

            session.uri == "/subscriptions" && session.method == Method.GET -> {
                if (!paired) return forbiddenPage()
                if (!PanelSession.isLoggedIn()) {
                    htmlResponse(
                        Response.Status.OK,
                        page(
                            texts.pageTitle,
                            "<p>${texts.loginRequired}</p>" +
                                "<p><a href=\"/login\">${texts.goLogin}</a></p>"
                        )
                    )
                } else {
                    htmlResponse(Response.Status.OK, subscriptionsPage(null, null))
                }
            }

            session.uri == "/subscriptions" && session.method == Method.POST -> {
                if (!paired) return forbiddenPage()
                if (!PanelSession.isLoggedIn()) {
                    htmlResponse(
                        Response.Status.OK,
                        page(
                            texts.pageTitle,
                            "<p>${texts.loginRequired}</p>" +
                                "<p><a href=\"/login\">${texts.goLogin}</a></p>"
                        )
                    )
                } else {
                    if (!parseForm(session)) return badRequest()
                    val remarks = param(session, "remarks").orEmpty()
                    val url = param(session, "url").orEmpty()
                    val autoUpdate = param(session, "auto_update") == "on"
                    val interval = param(session, "interval")?.toLongOrNull()
                    val formError = RemoteInputValidation.validateSubscriptionForm(
                        remarks, url, autoUpdate, interval,
                        AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
                    )
                    // 纯校验之外，再过与 SubEditActivity.saveServer 完全一致的
                    // Utils 校验（依赖 android.util.Patterns，只能在设备上跑）。
                    if (formError != null || !Utils.isValidUrl(url) || !Utils.isValidSubUrl(url)) {
                        htmlResponse(
                            Response.Status.OK,
                            subscriptionsPage(null, texts.subInvalid)
                        )
                    } else {
                        val item = SubscriptionItem().apply {
                            this.remarks = remarks
                            this.url = url
                            this.autoUpdate = autoUpdate
                            this.updateInterval = interval ?: 1440
                        }
                        val guid = Utils.getUuid()
                        MmkvManager.encodeSubscription(guid, item)
                        SubscriptionUpdater.syncOne(subId = guid)
                        SettingsChangeManager.makeSetupGroupTab()
                        LogUtil.i(TAG, "Remote subscription added")
                        htmlResponse(
                            Response.Status.OK,
                            subscriptionsPage(texts.subAdded, null)
                        )
                    }
                }
            }

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun parseForm(session: IHTTPSession): Boolean {
        return try {
            session.parseBody(mutableMapOf())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun param(session: IHTTPSession, name: String): String? {
        return session.parameters[name]?.firstOrNull()
    }

    private fun forbiddenPage(): Response {
        return htmlResponse(
            Response.Status.FORBIDDEN,
            page(
                texts.pageTitle,
                "<p>${texts.notPaired}</p>" +
                    "<p><a href=\"/\">${texts.pairingTitle}</a></p>"
            )
        )
    }

    private fun badRequest(): Response {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad Request")
    }

    private fun htmlResponse(status: Response.Status, html: String): Response {
        return newFixedLengthResponse(status, HTML_MIME, html)
    }

    private fun page(title: String, body: String): String {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title>" +
            "<style>body{font-family:sans-serif;font-size:18px;padding:16px}" +
            "input{font-size:18px;padding:8px;margin:6px 0;width:100%;box-sizing:border-box}" +
            "button{font-size:18px;padding:10px 20px;margin-top:12px}" +
            ".err{color:#b00020}</style></head><body>$body</body></html>"
    }

    private fun pairingPage(error: String?): String {
        val errorHtml = if (error != null) {
            "<p class=\"err\">${RemoteInputValidation.escapeHtml(error)}</p>"
        } else {
            ""
        }
        return page(
            texts.pageTitle,
            "<h1>${texts.pairingTitle}</h1>$errorHtml" +
                "<p>${texts.pairingHint}</p>" +
                "<form method=\"post\" action=\"/pair\">" +
                "<input name=\"code\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\">" +
                "<button type=\"submit\">${texts.pairingButton}</button></form>"
        )
    }

    private fun loginPage(error: String?): String {
        val errorHtml = if (error != null) {
            "<p class=\"err\">${RemoteInputValidation.escapeHtml(error)}</p>"
        } else {
            ""
        }
        return page(
            texts.pageTitle,
            "<h1>${texts.loginTitle}</h1>$errorHtml" +
                "<form method=\"post\" action=\"/login\">" +
                "<label>${texts.emailLabel}</label>" +
                "<input name=\"email\" type=\"email\" autocomplete=\"off\">" +
                "<label>${texts.passwordLabel}</label>" +
                "<input name=\"password\" type=\"password\" autocomplete=\"off\">" +
                "<button type=\"submit\">${texts.loginButton}</button></form>"
        )
    }

    private fun loggedInPage(): String {
        return page(
            texts.pageTitle,
            "<p>${texts.alreadyLoggedIn}</p>" +
                "<p><a href=\"/subscriptions\">${texts.subscriptionsTitle}</a></p>"
        )
    }

    private fun subscriptionsPage(note: String?, error: String?): String {
        val noteHtml = if (note != null) "<p>${RemoteInputValidation.escapeHtml(note)}</p>" else ""
        val errorHtml = if (error != null) {
            "<p class=\"err\">${RemoteInputValidation.escapeHtml(error)}</p>"
        } else {
            ""
        }
        val listHtml = MmkvManager.decodeSubscriptions().joinToString("") {
            val remarks = RemoteInputValidation.escapeHtml(it.subscription.remarks.orEmpty())
            val url = RemoteInputValidation.escapeHtml(it.subscription.url.orEmpty())
            "<li>$remarks<br>$url</li>"
        }
        return page(
            texts.pageTitle,
            "<h1>${texts.subscriptionsTitle}</h1>$noteHtml$errorHtml" +
                "<ul>$listHtml</ul>" +
                "<form method=\"post\" action=\"/subscriptions\">" +
                "<label>${texts.subRemarks}</label>" +
                "<input name=\"remarks\" autocomplete=\"off\">" +
                "<label>${texts.subUrl}</label>" +
                "<input name=\"url\" autocomplete=\"off\">" +
                "<label><input type=\"checkbox\" name=\"auto_update\" style=\"width:auto\">" +
                "${texts.subAutoUpdate}</label>" +
                "<label>${texts.subInterval}</label>" +
                "<input name=\"interval\" type=\"number\" value=\"1440\">" +
                "<button type=\"submit\">${texts.subAddButton}</button></form>"
        )
    }
}
