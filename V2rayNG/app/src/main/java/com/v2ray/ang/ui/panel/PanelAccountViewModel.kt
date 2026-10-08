package com.v2ray.ang.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.panel.PanelApi
import com.v2ray.ang.panel.PanelUserInfo

data class PanelAccountUiState(
    val email: String = "",
    val userInfo: PanelUserInfo = PanelUserInfo(),
    val isLoading: Boolean = false
)

class PanelAccountViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PanelAccountUiState())
    val uiState: StateFlow<PanelAccountUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(email = PanelSession.getEmail())
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { PanelApi.fetchUserInfo() }
            _uiState.value = _uiState.value.copy(userInfo = info, isLoading = false)
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // PanelApi.logout not needed
            PanelSession.logout()
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
