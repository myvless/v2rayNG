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
    val isSelected: Boolean
)

data class AirportMainUiState(
    val nodes: List<AirportNodeItem> = emptyList(),
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
    val email: String = ""
)

class AirportMainViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(AirportMainUiState())
    val uiState: StateFlow<AirportMainUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(email = PanelSession.getEmail())
        refreshNodes()
        refreshRunning()
    }

    fun refreshNodes() {
        viewModelScope.launch(Dispatchers.Default) {
            val guids = MmkvManager.decodeAllServerList()
            val selected = MmkvManager.getSelectServer()
            val items = guids.mapNotNull { guid ->
                val config: ProfileItem = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                AirportNodeItem(
                    guid = guid,
                    remarks = config.remarks.ifBlank { guid.take(8) },
                    isSelected = guid == selected
                )
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(nodes = items)
            }
        }
    }

    fun refreshRunning() {
        viewModelScope.launch(Dispatchers.Default) {
            val running = try {
                CoreServiceManager.isRunning()
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isRunning = running)
            }
        }
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
                val result = AngConfigManager.updateConfigViaSubAll()
                result.successCount
            }
            refreshNodes()
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                message = "订阅更新完成，${count} 个订阅成功"
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
