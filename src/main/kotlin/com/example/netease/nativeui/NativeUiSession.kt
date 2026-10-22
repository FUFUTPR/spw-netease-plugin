package com.example.netease.nativeui

import com.example.netease.NeteasePlugin
import com.example.netease.core.PluginLog
import com.example.netease.net.Dto
import com.example.netease.net.NeteaseApi
import com.example.netease.svc.AccountService
import com.example.netease.svc.LocalPlaylists
import com.example.netease.svc.SmsLogin
import com.example.netease.svc.SyncCoordinator
import com.example.netease.ui.LatestTask
import com.example.netease.ui.VerifyWindow
import com.example.netease.ui.controller.LocalSaveController
import com.example.netease.ui.controller.LoginController
import com.example.netease.ui.controller.PlaylistController
import com.example.netease.ui.controller.SearchController
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Callable
import java.util.function.Consumer

/**
 * 原生页面的控制器宿主：创建搜索/歌单/登录/本地命名控制器（业务逻辑全在 Java 控制器里，
 * 页面只渲染 State），并维护一个 250ms 的后台 tick：
 * <ul>
 *   <li>搜索/歌单/登录控制器各 tick 一次（只轮询宿主播放回报、账号变化与二维码状态，不做网络请求）；</li>
 *   <li>每 4 次 tick（1 秒）刷新一次只读同步状态与账号快照；</li>
 *   <li>按需刷新本地歌单列表（读盘在 tick 线程上，不阻塞 UI 线程）。</li>
 * </ul>
 *
 * <p>关闭页面只改 [NativeUiState.visible]，不停止 tick、不取消已提交的同步；
 * 停用插件时 [stop] 关闭控制器并取消定时器。登录流程只有一个（[loginController]），
 * 页面关闭时 [closeLogin] 会停二维码轮询并走 AccountService 原有的取消/恢复语义。</p>
 */
internal object NativeUiSession {
    private const val TAG = "native-ui"

    @Volatile
    private var searchController: SearchController? = null

    @Volatile
    private var playlistController: PlaylistController? = null

    @Volatile
    private var loginController: LoginController? = null

    @Volatile
    private var localSaveController: LocalSaveController? = null

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var ticks: Long = 0L

    @Volatile
    private var localRefreshRequested: Boolean = false

    @Volatile
    private var lastLocalSource: String = ""

    /** 上一次 tick 看到的账号 epoch；变化时让搜索页作废旧请求（PLAN §8.2）。 */
    @Volatile
    private var lastAccountEpoch: Long = -1L

    @Volatile
    private var started: Boolean = false

    /** 安装 Hook 成功后启动；失败只记日志，绝不影响插件其它功能。 */
    @Synchronized
    fun start() {
        if (started) {
            return
        }
        try {
            searchController = SearchController.createDefault { state -> NativeUiState.search = state }
            playlistController = PlaylistController.createDefault { state -> NativeUiState.playlist = state }
            loginController = LoginController(
                AccountLoginPorts,
                LoginAsync(),
            ) { state ->
                NativeUiState.login = state
                if (state.phase() == LoginController.Phase.LOGGED_IN) {
                    refreshAccount()
                }
            }
            localSaveController = LocalSaveController(
                LocalSavePorts,
                LocalSaveAsync(),
            ) { state ->
                NativeUiState.localSave = state
                NativeUiState.localEntries = state.entries()
                NativeUiState.localNote = state.message()
                if (state.saved()) {
                    NativeUiState.saveName = ""
                }
            }
            NativeUiState.search = searchController?.state()
            NativeUiState.playlist = playlistController?.state()
            NativeUiState.login = loginController?.state()
            NativeUiState.localSave = localSaveController?.state()
            lastAccountEpoch = sessionEpoch()
            val t = Timer("native-ui-tick", true)
            t.scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() = tickOnce()
                },
                250L,
                250L,
            )
            timer = t
            started = true
            refreshSync()
            refreshAccount()
            PluginLog.i(TAG, "原生页面控制器已启动（tick=250ms）")
        } catch (t: Throwable) {
            PluginLog.w(TAG, "原生页面控制器启动失败（已隔离）：$t")
        }
    }

    /** 停用插件时调用：关闭控制器、取消定时器；页面随后不再更新。 */
    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
        val search = searchController
        val playlist = playlistController
        val login = loginController
        val localSave = localSaveController
        searchController = null
        playlistController = null
        loginController = null
        localSaveController = null
        started = false
        try {
            search?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭搜索控制器失败（已忽略）：$t")
        }
        try {
            playlist?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭歌单控制器失败（已忽略）：$t")
        }
        try {
            // close() 会停二维码轮询并走 AccountService 的取消/恢复语义（不会退出账号）。
            login?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭登录控制器失败（已忽略）：$t")
        }
        try {
            localSave?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭本地命名控制器失败（已忽略）：$t")
        }
    }

    fun search(): SearchController? = searchController

    fun playlist(): PlaylistController? = playlistController

    fun login(): LoginController? = loginController

    fun localSave(): LocalSaveController? = localSaveController

    /** 宿主配置页入口路由：打开指定原生页；返回 false 表示原生层未就绪，调用方回退旧窗口。 */
    @Synchronized
    fun openPage(page: NativePage): Boolean {
        if (!started) {
            return false
        }
        NativeUiState.visible = true
        NativeUiState.page = page
        when (page) {
            NativePage.LOGIN -> openLogin()
            NativePage.ACCOUNT -> refreshAccount()
            NativePage.PLAYLIST -> {
                requestLocalRefresh()
                captureLocalSave()
            }
            NativePage.SYNC -> refreshSync()
            NativePage.SEARCH, NativePage.HOME -> Unit
        }
        return true
    }

    /** 打开唯一的登录流程（幂等；重复调用不会重复创建二维码轮询）。 */
    @Synchronized
    fun openLogin() {
        NativeUiState.visible = true
        NativeUiState.page = NativePage.LOGIN
        try {
            refreshAccount()
            loginController?.open()
        } catch (t: Throwable) {
            PluginLog.w(TAG, "打开原生登录页失败（已隔离）：$t")
        }
    }

    /** 关闭登录流程：停轮询、关验证窗，不清登录态（取消 ≠ 退出账号）。 */
    fun closeLogin() {
        try {
            loginController?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭登录流程失败（已忽略）：$t")
        }
        refreshAccount()
    }

    fun refreshAccount() {
        try {
            NativeUiState.account = AccountService.status()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "读取账号快照失败（已忽略）：$t")
        }
    }

    /** 账号页「退出登录」：沿用旧入口的清理路径（清凭据 + 清配置页账号行）。 */
    fun logout() {
        try {
            val had = AccountService.loggedIn()
            NeteasePlugin.logout()
            NativeUiState.accountNote = if (had) "已退出登录，本地凭据已清除" else "当前未登录（本地凭据也已清空）"
        } catch (t: Throwable) {
            PluginLog.w(TAG, "退出登录失败（已隔离）：$t")
            NativeUiState.accountNote = "退出登录失败，详情见插件日志"
        }
        refreshAccount()
    }

    /** 账号 epoch：登录/退出/账号切换都会让它自增（见 AccountService.setStatus/logout）。 */
    private fun sessionEpoch(): Long =
        try {
            AccountService.sessionEpoch()
        } catch (t: Throwable) {
            -1L
        }

    private fun tickOnce() {
        try {
            // PLAN §8.2：账号一变化，旧搜索请求与旧播放反馈立即失效（列表/选中保留，见 SearchController）。
            val epoch = sessionEpoch()
            if (epoch >= 0L && epoch != lastAccountEpoch) {
                lastAccountEpoch = epoch
                searchController?.onAccountChanged()
            }
            searchController?.tick()
            playlistController?.tick()
            loginController?.tick()
            val n = ticks++
            if (n % 4L == 0L) {
                refreshSync()
                refreshAccount()
            }
            if (localRefreshRequested) {
                localRefreshRequested = false
                refreshLocalNow()
            }
            val source = NativeUiState.playlist?.sourceName() ?: ""
            if (source != lastLocalSource) {
                lastLocalSource = source
                captureLocalSave()
            }
        } catch (t: Throwable) {
            // tick 不允许把异常抛回 Timer 线程（会静默取消后续任务）。
            PluginLog.d(TAG, "tick 异常（已忽略）：$t")
        }
    }

    /** 只读同步状态：与配置页 syncStatus() 读同一份 Status，不新增同步流程。 */
    fun refreshSync() {
        try {
            NativeUiState.sync = NeteasePlugin.syncStatusSnapshot()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "读取同步状态失败（已忽略）：$t")
        }
    }

    /**
     * 页面「立即同步」：只有 STARTED 才算已接收；BUSY 表示已有任务，必须提示不能开第二轮；
     * 其它结果把原因直接写给用户（未登录 / 合并已关闭 / 已停用）。
     */
    fun requestSync() {
        val note = try {
            when (NeteasePlugin.syncRequestFromNativeUi()) {
                SyncCoordinator.RequestResult.STARTED -> "已提交同步请求（宿主已接收），进度见本页状态"
                SyncCoordinator.RequestResult.BUSY -> "已有同步任务在运行，不能再创建第二轮"
                SyncCoordinator.RequestResult.DISABLED -> "合并歌单已关闭，未提交同步"
                SyncCoordinator.RequestResult.UNAUTHENTICATED -> "未登录，未提交同步"
                SyncCoordinator.RequestResult.STOPPED -> "插件已停用，未提交同步"
                SyncCoordinator.RequestResult.FAILED -> "提交同步失败，详情见插件日志"
            }
        } catch (t: Throwable) {
            PluginLog.w(TAG, "提交同步请求失败（已隔离）：$t")
            "提交同步失败，详情见插件日志"
        }
        NativeUiState.syncRequestNote = note
        refreshSync()
    }

    /** 请求下一次 tick 刷新本地歌单列表（读盘不在 UI 线程）。 */
    fun requestLocalRefresh() {
        localRefreshRequested = true
    }

    private fun refreshLocalNow() {
        try {
            NativeUiState.localEntries = LocalPlaylists.list()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "读取本地歌单列表失败（已忽略）：$t")
        }
    }

    /** 把当前曲目表/来源名交给命名控制器（默认名取当前来源，空白时为「我的本地歌单」）。 */
    fun captureLocalSave() {
        try {
            localSaveController?.capture()
            if (NativeUiState.saveName.isBlank()) {
                NativeUiState.saveName = localSaveController?.defaultName() ?: ""
            }
        } catch (t: Throwable) {
            PluginLog.d(TAG, "捕获本地歌单快照失败（已忽略）：$t")
        }
    }

    /** 「存为本地歌单」：控制器负责空白校验、快照隔离、后台写盘与成功后刷新。 */
    fun submitLocalSave(name: String) {
        try {
            localSaveController?.submit(name)
        } catch (t: Throwable) {
            PluginLog.w(TAG, "保存本地歌单失败（已隔离）：$t")
            NativeUiState.localNote = "保存本地歌单失败，请重试"
        }
    }

    /** 命名框「取消」：只清空输入与提示，不写盘、不取消已提交的写盘任务。 */
    fun cancelLocalSave() {
        try {
            localSaveController?.cancelPending()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "取消本地命名失败（已忽略）：$t")
        }
        NativeUiState.saveName = ""
        NativeUiState.localNote = "已取消，未写入任何文件"
    }

    // ------------------------------------------------------------ 生产端口

    private object AccountLoginPorts : LoginController.Ports {
        override fun startQr(force: Boolean): Boolean = AccountService.startQr(force)

        override fun status(): AccountService.LoginStatus = AccountService.status()

        override fun qrImage(): Dto.QrSession = AccountService.qrImage()

        override fun qrRiskRequired(): Boolean = AccountService.qrRiskRequired()

        override fun qrVerifyUrl(): String = AccountService.qrVerifyUrl()

        override fun retryQrAfterRisk() {
            AccountService.retryQrAfterRisk()
        }

        override fun pauseQrWatch(): Boolean = AccountService.pauseQrWatch()

        override fun wasLoggedInAtOpen(): Boolean = AccountService.wasLoggedInAtOpen()

        override fun sendCode(phone: String, source: String): Dto.SmsResult = SmsLogin.sendCodeNow(phone, source)

        override fun loginSms(phone: String, code: String, source: String): NeteaseApi.LoginOutcome =
            SmsLogin.loginNow(phone, code, source)

        override fun openVerify(url: String, fromQr: Boolean, onRetry: Runnable) {
            // 批次 3 暂沿用宿主已有的验证窗（批次 4 迁 WebView2）；源码只在适配层出现。
            VerifyWindow.open(null, url, null, null, if (fromQr) "扫码" else "验证码登录", onRetry)
        }

        override fun closeVerify() {
            VerifyWindow.closeIfOpen()
        }

        override fun maskPhone(phone: String): String = AccountService.maskPhone(phone)

        override fun nowMs(): Long = System.currentTimeMillis()
    }

    private object LocalSavePorts : LocalSaveController.Ports {
        override fun tracks(): List<Dto.Song> = NativeUiState.playlist?.tracks() ?: emptyList()

        override fun sourceName(): String = NativeUiState.playlist?.sourceName() ?: ""

        override fun save(name: String, songs: List<Dto.Song>): String? =
            LocalPlaylists.saveManual(name, songs)

        override fun list(): List<LocalPlaylists.Entry> = LocalPlaylists.list()
    }

    private class LoginAsync : LoginController.Async {
        private val task = LatestTask("native-ui-login", REQUEST_TIMEOUT_MS) { r -> r.run() }

        override fun <T> run(
            work: LoginController.Fetcher<T>,
            success: Consumer<T>,
            failure: Consumer<Throwable>,
        ) {
            task.run(Callable { work.fetch() }, success, failure)
        }

        override fun cancel() {
            task.cancelCurrent()
        }

        override fun close() {
            task.close()
        }
    }

    private class LocalSaveAsync : LocalSaveController.Async {
        private val task = LatestTask("native-ui-local-save", REQUEST_TIMEOUT_MS) { r -> r.run() }

        override fun <T> run(
            work: LocalSaveController.Fetcher<T>,
            success: Consumer<T>,
            failure: Consumer<Throwable>,
        ) {
            task.run(Callable { work.fetch() }, success, failure)
        }

        override fun cancel() {
            task.cancelCurrent()
        }

        override fun close() {
            task.close()
        }
    }

    private const val REQUEST_TIMEOUT_MS = 20_000L
}
