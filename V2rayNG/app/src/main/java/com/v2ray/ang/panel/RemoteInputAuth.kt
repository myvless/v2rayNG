package com.v2ray.ang.panel

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

/**
 * 远程输入（局域网手机页）用的面板登录序列。
 *
 * 只供 RemoteInputServer 调用，不重构 PanelAuthViewModel / PanelAuthActivity
 * 现有的本地登录流程：这里复现的是 PanelAuthActivity.autoImportSubscriptionAndGo()
 * 的最终态（清掉全部旧订阅与节点，只留一条 remarks="面板订阅" 的分组），
 * 而不是 ViewModel.ensurePanelSubscription 的中间态（remarks="机场订阅"、
 * 不清空），否则远程登录后会残留旧订阅。
 *
 * 密码只由调用方以当次内存变量传入，本文件不存储、不记录、不回显密码。
 */
object RemoteInputAuth {

    sealed interface RemoteLoginResult {
        /** 登录并导入完成。 */
        data object Success : RemoteLoginResult

        /** 电视端已经是登录态：什么都不做，不重复清库。 */
        data object AlreadyLoggedIn : RemoteLoginResult

        /** 登录或取订阅失败，message 已由 PanelApi 归一化，保证非空可见。 */
        data class Failure(val message: String) : RemoteLoginResult
    }

    /**
     * 远程登录全序列：登录 → 取订阅链接 → 保存登录态 → 清库重建面板订阅。
     * 每一步失败都直接返回，不写半截状态（登录成功但取订阅失败时，
     * saveLogin 尚未执行，电视端仍停留在未登录态，可重试）。
     */
    suspend fun loginAndImport(email: String, password: String): RemoteLoginResult {
        if (PanelSession.isLoggedIn()) return RemoteLoginResult.AlreadyLoggedIn

        val loginResult = PanelApi.login(email, password)
        if (loginResult is PanelApi.ApiResult.Failure) {
            return RemoteLoginResult.Failure(loginResult.message)
        }
        val subResult = PanelApi.fetchSubscriptionUrl()
        if (subResult !is PanelApi.ApiResult.Success) {
            val message = (subResult as? PanelApi.ApiResult.Failure)?.message ?: "获取订阅失败"
            return RemoteLoginResult.Failure(message)
        }
        val subUrl = subResult.message
        val normalizedEmail = email.trim().lowercase()
        PanelSession.saveLogin(normalizedEmail, subUrl)
        PanelSession.saveSubscriptionUrl(subUrl)
        // 与 PanelAuthActivity 收尾完全一致：清掉全部旧订阅和节点，只保留面板订阅。
        MmkvManager.decodeSubscriptions().forEach {
            MmkvManager.removeSubscription(it.guid)
        }
        MmkvManager.decodeAllServerList().forEach {
            MmkvManager.removeServer(it)
        }
        val subItem = SubscriptionItem().apply {
            remarks = "面板订阅"
            url = subUrl
        }
        val guid = java.util.UUID.randomUUID().toString()
        MmkvManager.encodeSubscription(guid, subItem)
        AngConfigManager.updateConfigViaSubAll()
        return RemoteLoginResult.Success
    }
}
