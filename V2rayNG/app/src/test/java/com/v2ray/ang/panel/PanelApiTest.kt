package com.v2ray.ang.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelApiTest {

    @Test
    fun failureMessage_passthroughMeaningfulMessage() {
        assertEquals("邮箱或者密码错误", PanelApi.failureMessage("邮箱或者密码错误", "登录失败"))
    }

    @Test
    fun failureMessage_blankFallsBackToDefault() {
        assertEquals("登录失败", PanelApi.failureMessage("", "登录失败"))
        assertEquals("登录失败", PanelApi.failureMessage("   ", "登录失败"))
        assertEquals("登录失败", PanelApi.failureMessage(null, "登录失败"))
    }

    @Test
    fun failureMessage_registerUsesItsOwnDefault() {
        assertEquals("注册失败", PanelApi.failureMessage("", "注册失败"))
    }
}
