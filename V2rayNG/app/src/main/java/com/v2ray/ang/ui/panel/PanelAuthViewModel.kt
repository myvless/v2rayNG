package com.v2ray.ang.ui.panel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.panel.PanelApi
import com.v2ray.ang.panel.PanelSession
import com.v2ray.ang.util.Utils
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PanelAuthUiState(
    val isRegisterMode: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val authDone: Boolean = false
)

class PanelAuthViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(PanelAuthUiState())
    val uiState: StateFlow<PanelAuthUiState> = _uiState.asStateFlow()

    fun switchMode(toRegister: Boolean) {
        _uiState.value = _uiState.value.copy(isRegisterMode = toRegister, errorMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun submit(email: String, password: String, inviteCode: String) {
        val state = _uiState.value
        if (state.isBusy) return
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "empty_input")
            return
        }
        if (state.isRegisterMode && password.length < 8) {
            _uiState.value = state.copy(errorMessage = "password_too_short")
            return
        }
        _uiState.value = state.copy(isBusy = true, errorMessage = null)
        viewModelScope.launch {
            val result = if (state.isRegisterMode) {
                PanelApi.register(email, password, inviteCode)
            } else {
                PanelApi.login(email, password)
            }
            when (result) {
                is PanelApi.ApiResult.Success -> {
                    // 登录/注册成功后拉取订阅链接并保存
                    val subResult = PanelApi.fetchSubscriptionUrl()
                    val subUrl = (subResult as? PanelApi.ApiResult.Success)?.message.orEmpty()
                    withContext(Dispatchers.IO) {
                        PanelSession.saveLogin(email.trim().lowercase(), subUrl)
                        if (subUrl.isNotEmpty()) {
                            ensurePanelSubscription(subUrl)
                        }
                    }
                    _uiState.value = _uiState.value.copy(isBusy = false, authDone = true)
                }
                is PanelApi.ApiResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isBusy = false, errorMessage = result.message)
                }
            }
        }
    }

    /**
     * 登录后自动添加面板订阅（若尚未添加）
     */
    private fun ensurePanelSubscription(subUrl: String) {
        val existing = MmkvManager.decodeSubscriptions()
        if (existing.any { it.subscription.url == subUrl }) return
        val item = SubscriptionItem().apply {
            url = subUrl
            autoUpdate = true
            remarks = "机场订阅"
        }
        val guid = Utils.getUuid()
        MmkvManager.encodeSubscription(guid, item)
        AngConfigManager.updateConfigViaSub(SubscriptionCache(guid, item))
    }
}
