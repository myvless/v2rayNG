package com.v2ray.ang.panel

/**
 * 机场面板配置（写死，用户无需手动填）
 */
object PanelConfig {
    const val PANEL_BASE_URL = "https://panel.020178.xyz"

    /** 仅支持 VMess 协议，订阅/导入时过滤掉其他协议 */
    const val VMESS_ONLY = true

    // 面板 Web 接口
    const val PATH_LOGIN = "/auth/login"
    const val PATH_REGISTER = "/auth/register"
    const val PATH_USER = "/user"
}
