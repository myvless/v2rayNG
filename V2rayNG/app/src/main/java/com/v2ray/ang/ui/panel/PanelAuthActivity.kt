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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.service.RemoteInputService
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.SettingsSwitchItem
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
        lifecycleScope.launch {
            // 远程登录完成信号：只起唤醒作用，收到后先确认登录态再跳转；
            // 本地登录进行中（isBusy）时不触发，避免打断正在进行的订阅导入。
            com.v2ray.ang.service.RemoteInputService.loginEvents.collect {
                if (com.v2ray.ang.panel.PanelSession.isLoggedIn()
                    && !viewModel.uiState.value.isBusy
                ) {
                    startActivity(Intent(this@PanelAuthActivity, com.v2ray.ang.ui.main.MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 远程登录发生在应用切后台时：后台启动 Activity 可能被系统拦截，
        // 回到前台时靠这里补一次跳转。本地登录进行中（isBusy 或 authDone）
        // 时不触发，避免打断正在进行的订阅导入。
        val state = viewModel.uiState.value
        if (com.v2ray.ang.panel.PanelSession.isLoggedIn() && !state.isBusy && !state.authDone) {
            startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java))
            finish()
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

        // 电视专用的远程输入入口：未登录时应用直接进本页，按返回键是退到系统桌面，
        // 根本到不了主界面和设置页，所以开关必须也在这里。门控与设置页一致：
        // 只在电视上渲染，非电视设备永不显示、永不启动。开关状态与设置页共用
        // 同一 MMKV 键，两边看到的是同一个开关。
        val context = LocalContext.current
        val isTelevision = remember(context) { RemoteInputService.isTelevision(context) }
        var remoteInputEnabled by rememberMmkvBool(AppConfig.PREF_REMOTE_INPUT_ENABLED, false)
        val remoteInputState by RemoteInputService.state.collectAsStateWithLifecycle()

        Scaffold { innerPadding ->
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
                // 登录/注册失败时常驻显示错误文案：之前只用短时 Snackbar，
                // 软键盘展开时会把它挡在后面，用户看不到任何提示。
                state.errorMessage?.let { msg ->
                    val display = when (msg) {
                        "empty_input" -> stringResource(R.string.panel_error_empty)
                        "password_too_short" -> stringResource(R.string.panel_error_password_short)
                        else -> msg
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = display,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
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

                // 电视专用的远程输入区域：放在登录/注册 UI 下方，用分隔线独立分隔。
                // 只在电视上渲染；手机上本段代码不执行，UI 无任何改变。
                if (isTelevision) {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_remote_input),
                        summary = stringResource(R.string.summary_remote_input),
                        checked = remoteInputEnabled,
                        onCheckedChange = {
                            remoteInputEnabled = it
                            if (it) RemoteInputService.start(context)
                            else RemoteInputService.stop(context)
                        }
                    )
                    // 状态行只是信息展示（不可点击），与设置页一致，遥控器焦点只落在开关行上。
                    when (val remoteState = remoteInputState) {
                        is RemoteInputService.RemoteInputState.Running -> {
                            RemoteInputInfoRow(
                                label = stringResource(R.string.remote_input_status_label),
                                value = stringResource(R.string.remote_input_status_running)
                            )
                            RemoteInputInfoRow(
                                label = stringResource(R.string.remote_input_address_label),
                                value = remoteState.address
                            )
                            RemoteInputInfoRow(
                                label = stringResource(R.string.remote_input_pairing_code_label),
                                value = remoteState.pairingCode
                            )
                        }
                        is RemoteInputService.RemoteInputState.Failed -> {
                            RemoteInputInfoRow(
                                label = stringResource(R.string.remote_input_status_label),
                                value = stringResource(
                                    if (remoteState.portInUse) R.string.remote_input_status_failed_port
                                    else R.string.remote_input_status_failed
                                )
                            )
                        }
                        is RemoteInputService.RemoteInputState.Stopped -> {
                            RemoteInputInfoRow(
                                label = stringResource(R.string.remote_input_status_label),
                                value = stringResource(
                                    if (remoteInputEnabled) R.string.remote_input_status_restart_needed
                                    else R.string.remote_input_status_stopped
                                )
                            )
                        }
                        // RemoteInputState 只有 Running/Failed/Stopped 三种子类型，均已覆盖；
                        // else 分支仅用于满足 Kotlin 穷尽性检查，不可达（与设置页一致）。
                        else -> {
                        }
                    }
                }
            }
        }
    }
}

/**
 * 远程输入的状态信息行：纯展示，不可点击、不进焦点链。
 * 与设置页的同名行一致（内边距 16.dp、bodyLarge/titleMedium），
 * 电视上 3 米外能看清地址与配对码。
 */
@Composable
private fun RemoteInputInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
