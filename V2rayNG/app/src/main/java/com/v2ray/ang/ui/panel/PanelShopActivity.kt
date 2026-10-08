package com.v2ray.ang.ui.panel

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.v2ray.ang.panel.PanelApi
import com.v2ray.ang.panel.PanelConfig
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * 面板商店页（购买套餐）
 * 用 WebView 打开面板的商店页面，复用登录态 Cookie
 */
class PanelShopActivity : BaseComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun ScreenContent() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    // 同步登录 Cookie
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    val cookieHeader = PanelApi.getCookieHeader()
                    if (cookieHeader.isNotBlank()) {
                        cookieHeader.split(";").forEach { cookie ->
                            cookieManager.setCookie(PanelConfig.PANEL_BASE_URL, cookie.trim())
                        }
                    }
                    loadUrl(PanelConfig.PANEL_BASE_URL + "/user/shop")
                }
            }
        )
    }
}
