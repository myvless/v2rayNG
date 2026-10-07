package com.v2ray.ang.panel

import com.tencent.mmkv.MMKV

/**
 * 面板登录态管理
 */
object PanelSession {
    private const val MMKV_ID = "panel_session"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_EMAIL = "email"
    private const val KEY_SUB_URL = "sub_url"

    private val store: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun isLoggedIn(): Boolean = store.decodeBool(KEY_LOGGED_IN, false)

    fun getEmail(): String = store.decodeString(KEY_EMAIL).orEmpty()

    fun getSubscriptionUrl(): String = store.decodeString(KEY_SUB_URL).orEmpty()

    fun saveLogin(email: String, subUrl: String) {
        store.encode(KEY_LOGGED_IN, true)
        store.encode(KEY_EMAIL, email)
        store.encode(KEY_SUB_URL, subUrl)
    }

    fun saveSubscriptionUrl(subUrl: String) {
        store.encode(KEY_SUB_URL, subUrl)
    }

    fun logout() {
        store.encode(KEY_LOGGED_IN, false)
        store.removeValueForKey(KEY_SUB_URL)
        PanelApi.clearCookies()
    }
}
