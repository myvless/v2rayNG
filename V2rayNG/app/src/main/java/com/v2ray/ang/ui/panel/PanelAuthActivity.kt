package com.v2ray.ang.ui.panel

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 机场面板登录/注册页
 *
 * 首次启动未登录时展示；登录或注册成功后自动拉取订阅并进入主界面。
 */
class PanelAuthActivity : BaseComponentActivity() {

    private val viewModel: PanelAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (state.authDone) {
                    // 登录成功后自动导入面板订阅，然后进原生主界面
                    autoImportSubscriptionAndGo()
                }
            }
        }
    }

    /**
     * 自动从面板获取订阅并导入，然后进入原生主界面
     */
    private fun autoImportSubscriptionAndGo() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = com.v2ray.ang.panel.PanelApi.fetchSubscriptionUrl()
                if (result is com.v2ray.ang.panel.PanelApi.ApiResult.Success) {
                    val subUrl = result.message
                    com.v2ray.ang.panel.PanelSession.saveSubscriptionUrl(subUrl)
                    // 清理所有旧订阅和节点，只保留面板订阅一个分组
                    com.v2ray.ang.handler.MmkvManager.decodeSubscriptions().forEach {
                        com.v2ray.ang.handler.MmkvManager.removeSubscription(it.guid)
                    }
                    com.v2ray.ang.handler.MmkvManager.decodeAllServerList().forEach {
                        com.v2ray.ang.handler.MmkvManager.removeServer(it)
                    }
                    // 加入面板订阅
                    val subItem = com.v2ray.ang.dto.entities.SubscriptionItem().apply {
                        remarks = "面板订阅"
                        url = subUrl
                    }
                    val guid = java.util.UUID.randomUUID().toString()
                    com.v2ray.ang.handler.MmkvManager.encodeSubscription(guid, subItem)
                    com.v2ray.ang.handler.AngConfigManager.updateConfigViaSubAll()
                }
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@PanelAuthActivity, com.v2ray.ang.ui.main.MainActivity::class.java))
                finish()
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }

        state.errorMessage?.let { msg ->
            val display = when (msg) {
                "empty_input" -> stringResource(R.string.panel_error_empty)
                "password_too_short" -> stringResource(R.string.panel_error_password_short)
                else -> msg
            }
            LaunchedEffect(msg) {
                snackbarHostState.showSnackbar(display)
                viewModel.clearError()
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(
                        if (state.isRegisterMode) R.string.panel_register_title
                        else R.string.panel_login_title
                    ),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(24.dp))

                var email by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var inviteCode by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.panel_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.panel_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.isRegisterMode) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        label = { Text(stringResource(R.string.panel_invite_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.submit(email, password, inviteCode) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        stringResource(
                            if (state.isRegisterMode) R.string.panel_btn_register
                            else R.string.panel_btn_login
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { viewModel.switchMode(!state.isRegisterMode) }) {
                        Text(
                            stringResource(
                                if (state.isRegisterMode) R.string.panel_btn_go_login
                                else R.string.panel_btn_go_register
                            )
                        )
                    }
                }
            }
        }
    }
}
