package com.v2ray.ang.ui.panel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.panel.PanelApi
import com.v2ray.ang.panel.PanelConfig
import com.v2ray.ang.panel.PanelSession
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * 机场主界面：底部导航（节点 / 商店 / 我的）
 *
 * 登录成功后进入。节点页显示订阅节点列表，点击选中并连接 VPN。
 */
class AirportMainActivity : BaseComponentActivity() {

    private val viewModel: AirportMainViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                LauncherManager.startService(this)
                viewModel.refreshRunning()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 未登录则回登录页
        if (!PanelSession.isLoggedIn()) {
            startActivity(Intent(this, PanelAuthActivity::class.java))
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNodes()
        viewModel.refreshRunning()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        var tabIndex by remember { mutableIntStateOf(0) }

        state.message?.let { msg ->
            LaunchedEffect(msg) {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearMessage()
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    bottomTabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            icon = { Text(if (index == 0) "🌐" else if (index == 1) "🛒" else "👤", fontSize = 20.sp) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                when (tabIndex) {
                    0 -> NodesTab(
                        state = state,
                        onSelect = { viewModel.selectNode(it) },
                        onToggleVpn = { toggleVpn(state.isRunning) },
                        onRefresh = { viewModel.updateSubscription() },
                        onManualImport = { viewModel.importSubscription(it) }
                    )
                    1 -> ShopTab()
                    2 -> ProfileTab(
                        state = state,
                        onUpdateSub = { viewModel.updateSubscription() },
                        onLogout = {
                            viewModel.logout {
                                LauncherManager.stopService(this@AirportMainActivity)
                                startActivity(Intent(this@AirportMainActivity, PanelAuthActivity::class.java))
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun toggleVpn(isRunning: Boolean) {
        if (isRunning) {
            LauncherManager.stopService(this)
            viewModel.refreshRunning()
        } else {
            // 确保有选中的节点（同步选择，避免异步未完成就启动服务）
            var selected = com.v2ray.ang.handler.MmkvManager.getSelectServer()
            if (selected.isNullOrEmpty()) {
                val first = viewModel.uiState.value.nodes.firstOrNull()
                if (first != null) {
                    com.v2ray.ang.handler.MmkvManager.setSelectServer(first.guid)
                    viewModel.selectNode(first.guid)
                    selected = first.guid
                }
            }
            if (selected.isNullOrEmpty()) {
                android.widget.Toast.makeText(this, "请先选择节点", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val intent = VpnService.prepare(this)
            if (intent == null) {
                LauncherManager.startService(this)
                // 延迟刷新状态，等服务启动
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    viewModel.refreshRunning()
                }, 1000)
            } else {
                requestVpnPermission.launch(intent)
            }
        }
    }

    private data class BottomTab(val label: String)

    private val bottomTabs = listOf(
        BottomTab("节点"),
        BottomTab("商店"),
        BottomTab("我的")
    )
}

@Composable
private fun NodesTab(
    state: AirportMainUiState,
    onSelect: (String) -> Unit,
    onToggleVpn: () -> Unit,
    onRefresh: () -> Unit,
    onManualImport: (String) -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 连接状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.isRunning)
                    Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (state.isRunning) "已连接" else "未连接",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.isRunning) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val selectedName = state.nodes.firstOrNull { it.isSelected }?.remarks
                Text(
                    text = selectedName?.let { "当前节点：$it" } ?: "请选择节点",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onToggleVpn,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.nodes.isNotEmpty()
                ) {
                    Text(if (state.isRunning) "断开连接" else "连接")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("节点列表（${state.nodes.size}）", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(onClick = { showImportDialog = true }, enabled = !state.isBusy) {
                    Text("手动导入")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onRefresh, enabled = !state.isBusy) {
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Text("⟳", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("更新订阅")
                }
            }
        }

        // 手动导入订阅链接对话框
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("导入订阅链接") },
                text = {
                    Column {
                        Text("从面板复制订阅链接粘贴到下面：", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (importUrl.isNotBlank()) {
                            onManualImport(importUrl.trim())
                            showImportDialog = false
                            importUrl = ""
                        }
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) { Text("取消") }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.nodes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("暂无节点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "点「更新订阅」自动获取，或点「手动导入」粘贴面板的订阅链接",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.nodes, key = { it.guid }) { node ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(node.guid) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (node.isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = if (node.isSelected) CardDefaults.outlinedCardBorder() else null
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (node.isSelected) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 20.sp,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(node.remarks, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopTab() {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                // 同步面板登录态 cookie
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val cookieHeader = PanelApi.getCookieHeader()
                if (cookieHeader.isNotEmpty()) {
                    cookieHeader.split(";").forEach { pair ->
                        val trimmed = pair.trim()
                        if (trimmed.contains("=")) {
                            cookieManager.setCookie(
                                PanelConfig.PANEL_BASE_URL,
                                "$trimmed; path=/; domain=${PanelConfig.PANEL_HOST}"
                            )
                        }
                    }
                    cookieManager.flush()
                }
                loadUrl(PanelConfig.PANEL_BASE_URL + PanelConfig.PATH_SHOP)
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.destroy() }
    )
}

@Composable
private fun ProfileTab(
    state: AirportMainUiState,
    onUpdateSub: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("👤", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(state.email.ifBlank { "已登录" }, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onUpdateSub, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy) {
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("更新订阅")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "节点数：${state.nodes.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
