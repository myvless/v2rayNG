package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.BindException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网手机远程输入的前台服务（只在电视设备上可用）。
 *
 * 门控分两层：设置页的开关只在电视上显示；服务在 onStartCommand 里
 * 再判一次，非电视设备直接拒绝启动。同一套代码因此不会在手机上
 * 出现该功能，也不需要为此拆分 build flavor。
 *
 * 生命周期（不自动恢复）：开关开 → 前台服务起内嵌 HTTP 服务；
 * 开关关 → 停服务；进程被杀后系统不拉起（START_NOT_STICKY），
 * 用户下次进设置页会看到“服务未运行，请关闭后重新开启”。
 */
class RemoteInputService : Service() {

    /** 服务对外状态：设置页订阅它来显示运行状态、地址与配对码。 */
    sealed interface RemoteInputState {
        data object Stopped : RemoteInputState
        data class Running(val address: String, val pairingCode: String) : RemoteInputState
        data class Failed(val portInUse: Boolean) : RemoteInputState
    }

    companion object {
        private const val TAG = "RemoteInputService"

        /** 远程输入端口（与方案一致，默认 12346；被占用时明示失败，不自动换端口）。 */
        const val PORT = 12346

        private val _state = MutableStateFlow<RemoteInputState>(RemoteInputState.Stopped)
        val state: StateFlow<RemoteInputState> = _state.asStateFlow()

        /**
         * 远程登录完成事件：PanelAuthActivity 订阅它，收到后先判
         * PanelSession.isLoggedIn() 再跳转（事件本身只起唤醒作用，
         * 不携带任何账号、密码或订阅信息）。
         */
        private val _loginEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val loginEvents: SharedFlow<Unit> = _loginEvents.asSharedFlow()

        /** 电视判断：代码里此前没有现成的电视检测，这里用 Configuration 的电视类型。 */
        fun isTelevision(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        }

        /** 启动服务；非电视设备返回 false 且不启动。 */
        fun start(context: Context): Boolean {
            if (!isTelevision(context)) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteInputService::class.java)
            )
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteInputService::class.java))
        }

        internal fun emitLoginCompleted() {
            _loginEvents.tryEmit(Unit)
        }

        internal fun publishState(newState: RemoteInputState) {
            _state.value = newState
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val stopping = AtomicBoolean(false)
    private var server: RemoteInputServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 非电视设备拒绝启动（设置页门控之外的第二道闸）。
        if (!isTelevision(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 空 intent 只可能来自系统重投；本服务返回 NOT_STICKY，不应无动作启动。
        if (intent == null && state.value !is RemoteInputState.Running) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 幂等启动：已运行则直接返回当前地址与配对码，不重建服务、不换配对码。
        if (state.value is RemoteInputState.Running) {
            return START_NOT_STICKY
        }
        // 先进前台再开 socket（service/AGENTS.md 强制顺序）。
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.REMOTE_INPUT,
            getString(R.string.notification_remote_input_title),
            getString(R.string.app_name)
        )
        stopping.set(false)
        serviceScope.launch {
            startServer(startId)
        }
        // 不自动恢复：进程被杀后不由系统拉起，用户下次手动再开。
        return START_NOT_STICKY
    }

    private fun startServer(startId: Int) {
        val lanIp = RemoteInputValidation.findLanIpv4()
        if (lanIp == null || stopping.get()) {
            failStart(startId, portInUse = false)
            return
        }
        val pairingCode = RemoteInputValidation.generatePairingCode()
        val newServer = RemoteInputServer(
            lanIp,
            PORT,
            pairingCode,
            buildTexts()
        ) {
            emitLoginCompleted()
        }
        try {
            newServer.start()
        } catch (e: BindException) {
            failStart(startId, portInUse = true)
            return
        } catch (e: IOException) {
            LogUtil.e(TAG, "Remote input server failed to start", e)
            failStart(startId, portInUse = false)
            return
        }
        if (stopping.get()) {
            // 停止命令在启动途中到达：直接拆掉刚起的服务，不发布 Running。
            runCatching { newServer.stop() }
            return
        }
        server = newServer
        publishState(RemoteInputState.Running("http://$lanIp:$PORT", pairingCode))
        LogUtil.i(TAG, "Remote input server started")
    }

    private fun failStart(startId: Int, portInUse: Boolean) {
        publishState(RemoteInputState.Failed(portInUse))
        NotificationHelper.stopForeground(this)
        stopSelf(startId)
    }

    override fun onDestroy() {
        // 先拒新请求（关监听 socket），再取消任务、释放资源；重复 stop 是 no-op。
        stopping.set(true)
        serviceJob.cancel()
        runCatching { server?.stop() }
        server = null
        NotificationHelper.stopForeground(this)
        // 只有运行中的服务被关掉才回到 Stopped；启动失败态保留，
        // 供设置页在开关仍开着时显示失败原因。
        if (state.value is RemoteInputState.Running) {
            publishState(RemoteInputState.Stopped)
        }
        super.onDestroy()
    }

    private fun buildTexts(): RemoteInputServer.RemoteInputTexts {
        return RemoteInputServer.RemoteInputTexts(
            pageTitle = getString(R.string.title_remote_input),
            pairingTitle = getString(R.string.remote_input_pairing_title),
            pairingHint = getString(R.string.remote_input_pairing_hint),
            pairingButton = getString(R.string.remote_input_pairing_button),
            pairingError = getString(R.string.remote_input_pairing_error),
            pairingSuccess = getString(R.string.remote_input_pairing_success),
            notPaired = getString(R.string.remote_input_not_paired),
            loginTitle = getString(R.string.panel_login_title),
            emailLabel = getString(R.string.panel_email),
            passwordLabel = getString(R.string.panel_password),
            loginButton = getString(R.string.panel_btn_login),
            loginEmptyError = getString(R.string.panel_error_empty),
            loginSuccess = getString(R.string.remote_input_login_success),
            alreadyLoggedIn = getString(R.string.remote_input_already_logged_in),
            subscriptionsTitle = getString(R.string.remote_input_subscriptions_title),
            subRemarks = getString(R.string.remote_input_sub_remarks),
            subUrl = getString(R.string.remote_input_sub_url),
            subAutoUpdate = getString(R.string.remote_input_sub_auto_update),
            subInterval = getString(R.string.remote_input_sub_interval),
            subAddButton = getString(R.string.remote_input_sub_add),
            subAdded = getString(R.string.remote_input_sub_added),
            subInvalid = getString(R.string.remote_input_sub_invalid),
            loginRequired = getString(R.string.remote_input_login_required),
            goLogin = getString(R.string.remote_input_go_login),
            internalError = getString(R.string.remote_input_internal_error)
        )
    }
}
