package com.v2ray.ang.ui.panel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.panel.PanelApi
import com.v2ray.ang.panel.PanelSession
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AirportNodeItem(
    val guid: String,
    val remarks: String,
    val isSelected: Boolean,
    val tcpDelayMs: Long = -1L,   // -1=未测, -2=测试中, >=0=延迟ms, -3=超时/失败
    val realDelayMs: Long = -1L,  // 真连接延迟（HTTP）
    val subscriptionId: String = "",
    val subscriptionRemarks: String = ""
)

data class AirportMainUiState(
    val nodes: List<AirportNodeItem> = emptyList(),
    val isRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
    val email: String = "",
    val userInfo: PanelApi.UserInfo = PanelApi.UserInfo()
)

class AirportMainViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(AirportMainUiState())
    val uiState: StateFlow<AirportMainUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(email = PanelSession.getEmail())
        refreshNodes()
        refreshRunning()
        refreshUserInfo()
    }

    fun refreshUserInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { PanelApi.fetchUserInfo() }
            _uiState.value = _uiState.value.copy(userInfo = info)
        }
    }

    fun refreshNodes() {
        viewModelScope.launch(Dispatchers.Default) {
            val guids = MmkvManager.decodeAllServerList()
            val selected = MmkvManager.getSelectServer()
            val prev = _uiState.value.nodes.associateBy { it.guid }
            // 取订阅备注用于分组
            val subMap = MmkvManager.decodeSubscriptions().associate { it.guid to it.subscription.remarks }
            val items = guids.mapNotNull { guid ->
                val config: ProfileItem = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                val old = prev[guid]
                AirportNodeItem(
                    guid = guid,
                    remarks = config.remarks.ifBlank { guid.take(8) },
                    isSelected = guid == selected,
                    tcpDelayMs = old?.tcpDelayMs ?: -1L,
                    realDelayMs = old?.realDelayMs ?: -1L,
                    subscriptionId = config.subscriptionId,
                    subscriptionRemarks = subMap[config.subscriptionId] ?: "默认分组"
                )
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(nodes = items)
            }
        }
    }

    /**
     * 测试全部节点 TCP 延迟
     */
    fun testTcpDelay() {
        if (_uiState.value.isBusy) return
        val nodes = _uiState.value.nodes
        if (nodes.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            nodes = nodes.map { it.copy(tcpDelayMs = -2L) },
            message = "正在测试 TCP 延迟..."
        )
        viewModelScope.launch(Dispatchers.Default) {
            nodes.forEach { node ->
                val delay = tcpPing(node.guid)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        nodes = _uiState.value.nodes.map {
                            if (it.guid == node.guid) it.copy(tcpDelayMs = delay) else it
                        }
                    )
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(message = "TCP 延迟测试完成")
            }
        }
    }

    /**
     * 测试全部节点真连接延迟（HTTP GET 到服务器）
     */
    fun testRealDelay() {
        if (_uiState.value.isBusy) return
        val nodes = _uiState.value.nodes
        if (nodes.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            nodes = nodes.map { it.copy(realDelayMs = -2L) },
            message = "正在测试真连接延迟..."
        )
        viewModelScope.launch(Dispatchers.Default) {
            nodes.forEach { node ->
                val delay = realPing(node.guid)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        nodes = _uiState.value.nodes.map {
                            if (it.guid == node.guid) it.copy(realDelayMs = delay) else it
                        }
                    )
                }
            }
            withContext(Dispatchers.Main) {
                // 按延迟排序（未测到的排最后）
                val sorted = _uiState.value.nodes.sortedWith(compareBy(
                    { if (it.realDelayMs < 0) Long.MAX_VALUE else it.realDelayMs },
                    { it.remarks }
                ))
                _uiState.value = _uiState.value.copy(
                    nodes = sorted,
                    message = "真连接延迟测试完成，已按延迟排序"
                )
            }
        }
    }

    /**
     * TCP 连接测延迟，超时返回 -3
     */
    private fun tcpPing(guid: String): Long {
        return try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return -3L
            val host = config.server ?: return -3L
            val port = config.serverPort?.toIntOrNull() ?: return -3L
            val start = System.currentTimeMillis()
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 3000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -3L
        }
    }

    /**
     * 真连接延迟：向服务器发 HTTP 请求测应用层延迟
     * 超时返回 -3
     */
    private fun realPing(guid: String): Long {
        return try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return -3L
            val host = config.server ?: return -3L
            // 用 HTTP GET 测试服务器响应（5秒超时）
            val url = java.net.URL("http://$host/")
            val start = System.currentTimeMillis()
            (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                connect()
                // 只要能连上就算成功，不要求 200
                responseCode
                disconnect()
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -3L
        }
    }

    fun refreshRunning(context: android.content.Context? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            val running = isServiceRunning(context)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isRunning = running, isConnecting = false)
            }
        }
    }

    /**
     * 通过 ActivityManager 检查 VPN 服务是否在运行
     */
    private fun isServiceRunning(context: android.content.Context?): Boolean {
        if (context == null) return false
        return try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Integer.MAX_VALUE)
            services.any {
                it.service.className == "com.v2ray.ang.service.CoreVpnService" ||
                it.service.className.endsWith(".CoreVpnService")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 开始连接：设为连接中状态，并轮询服务状态
     */
    fun markConnecting(context: android.content.Context? = null) {
        _uiState.value = _uiState.value.copy(isConnecting = true, message = null)
        // 轮询服务状态，最多 15 秒
        viewModelScope.launch {
            repeat(30) {
                kotlinx.coroutines.delay(500)
                val running = withContext(Dispatchers.Default) { isServiceRunning(context) }
                if (running) {
                    _uiState.value = _uiState.value.copy(isRunning = true, isConnecting = false)
                    return@launch
                }
            }
            // 超时仍未启动
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                message = "连接超时，请检查节点是否可用"
            )
        }
    }

    fun markDisconnected() {
        _uiState.value = _uiState.value.copy(isRunning = false, isConnecting = false)
    }

    fun selectNode(guid: String) {
        viewModelScope.launch(Dispatchers.Default) {
            MmkvManager.setSelectServer(guid)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    nodes = _uiState.value.nodes.map { it.copy(isSelected = it.guid == guid) }
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * 从面板重新拉取订阅链接并更新节点
     */
    fun updateSubscription() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, message = null)
        viewModelScope.launch {
            val subResult = PanelApi.fetchSubscriptionUrl()
            val subUrl = (subResult as? PanelApi.ApiResult.Success)?.message.orEmpty()
            if (subUrl.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    message = (subResult as? PanelApi.ApiResult.Failure)?.message ?: "获取订阅失败"
                )
                return@launch
            }
            val count = withContext(Dispatchers.IO) {
                PanelSession.saveSubscriptionUrl(subUrl)
                ensurePanelSubscription(subUrl)
                val result = AngConfigManager.updateConfigViaSubAll()
                result.successCount
            }
            refreshNodes()
            val nodeCount = withContext(Dispatchers.Default) {
                MmkvManager.decodeAllServerList().size
            }
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                message = if (nodeCount > 0) "订阅更新完成，共 ${nodeCount} 个节点" else "订阅更新完成，但未获取到节点"
            )
        }
    }

    /**
     * 确保面板订阅已加入 v2rayNG 订阅列表（若尚未添加）
     */
    private fun ensurePanelSubscription(subUrl: String) {
        val existing = MmkvManager.decodeSubscriptions()
        if (existing.any { it.subscription.url == subUrl }) return
        val item = com.v2ray.ang.dto.entities.SubscriptionItem().apply {
            url = subUrl
            autoUpdate = true
            remarks = "机场订阅"
        }
        val guid = com.v2ray.ang.util.Utils.getUuid()
        MmkvManager.encodeSubscription(guid, item)
    }

    /**
     * 手动导入订阅链接
     */
    fun importSubscription(subUrl: String) {
        if (_uiState.value.isBusy) return
        if (subUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "订阅链接不能为空")
            return
        }
        _uiState.value = _uiState.value.copy(isBusy = true, message = null)
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                PanelSession.saveSubscriptionUrl(subUrl)
                ensurePanelSubscription(subUrl)
                val result = AngConfigManager.updateConfigViaSubAll()
                result.successCount
            }
            refreshNodes()
            val nodeCount = withContext(Dispatchers.Default) {
                MmkvManager.decodeAllServerList().size
            }
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                message = if (nodeCount > 0) "导入成功，共 ${nodeCount} 个节点" else "导入完成，但未获取到节点，请检查链接"
            )
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            PanelSession.logout()
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
