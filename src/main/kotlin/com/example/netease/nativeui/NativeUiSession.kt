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
import com.example.netease.ui.controller.LocalSaveController
import com.example.netease.ui.controller.LoginController
import com.example.netease.ui.controller.PlaylistController
import com.example.netease.ui.controller.SearchController
import com.example.netease.ui.controller.VerifyEmbedController
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Consumer

/**
 * 原生页面的控制器宿主：创建搜索/歌单/登录/本地命名控制器（业务逻辑全在 Java 控制器里，
 * 页面只渲染 State），并维护一个 250ms 的后台 tick：
 * <ul>
 *   <li>搜索/歌单/登录控制器各 tick 一次（只轮询宿主播放回报、账号变化与二维码状态，不做网络请求）；</li>
 *   <li>每 4 次 tick（1 秒）刷新一次只读同步状态与账号快照；</li>
 *   <li>按需刷新本地歌单列表（读盘在独立单线程 daemon "native-ui-local-read" 上，tick 只投递合并信号，不阻塞 UI 线程也不拖延同一拍的二维码/登录轮询）。</li>
 * </ul>
 *
 * <p>关闭页面只改 [NativeUiState.visible]，不停止 tick、不取消已提交的同步；
 * 停用插件时 [stop] 关闭控制器、取消定时器，并在锁外关闭登录/本地保存的异步通道
 * （LatestTask 的线程池与超时定时器随之释放），再次 [start] 会重建全新的异步通道。
 * 登录流程只有一个（[loginController]），页面关闭时 [closeLogin] 只关登录面板
 * （停二维码轮询、关验证窗，异步通道保留），用户可以再次打开；销毁整个控制器集合才走 [stop]。</p>
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

    /** 批次 4：内嵌验证区域（VerifyHost 助手进程 + WebView2），登录风控时启动。 */
    @Volatile
    private var verifyController: VerifyEmbedController? = null

    /** 验证区域的 Compose 占位当前是否在屏幕上（决定 show/hide）。 */
    @Volatile
    private var verifyAreaLaidOut: Boolean = false

    /** 验证窗属于哪一代登录；旧代次迟到的 READY/布局回调不得把新会话的窗显示出来。 */
    private var verifyGeneration: Long = -1L

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var ticks: Long = 0L

    @Volatile
    private var localRefreshRequested: Boolean = false

    @Volatile
    private var lastLocalSource: String = ""

    /** 上一次刷新看到的同步阶段；用于只失效一次云端菜单缓存。 */
    @Volatile
    private var lastSyncPhase: SyncCoordinator.Phase? = null

    /** 上一次 tick 看到的账号 epoch；变化时让搜索页作废旧请求（PLAN §8.2）。 */
    @Volatile
    private var lastAccountEpoch: Long = -1L

    @Volatile
    private var started: Boolean = false

    /**
     * 登录展示代次：每次打开/关闭登录页或停用插件都会递增。
     * 退场页按住旧代次，它的按钮回调/DisposableEffect 只能影响旧代次，不能开关新一代登录或验证窗。
     */
    @Volatile
    private var loginGeneration: Long = 0L

    /**
     * 登录异步通道：与 [loginController] 同生命周期。关登录面板（[closeLogin]）只关控制器、
     * 不关本通道；停用（[stop]）才在锁外 close——通道里的 LatestTask 线程与超时定时器必须在这里释放。
     */
    @Volatile
    private var loginAsync: LoginAsync? = null

    /** 本地保存异步通道：与 [localSaveController] 同生命周期，[stop] 时在锁外关闭。 */
    @Volatile
    private var localSaveAsync: LocalSaveAsync? = null

    /**
     * 本地歌单读盘的唯一线程（250ms tick 只投递信号）。读盘曾直接跑在 tick 线程上，
     * 大目录扫描会推迟同一拍里的二维码/登录轮询；现在读盘与 tick 解耦，信号队列自然合并重复请求。
     */
    @Volatile
    private var localReader: ExecutorService? = null

    /** 读盘请求信号；[stop] 用 shutdownNow 中断 take()，队列无需额外关闭。 */
    private val localReadSignals = LinkedBlockingQueue<Unit>()

    /** 安装 Hook 成功后启动；失败只记日志，绝不影响插件其它功能。 */
    fun start() {
        val failed = startLocked()
        if (failed != null) {
            // 启动失败的半成品不在会话锁内关闭：LatestTask 的回调会去取控制器锁，锁内等待可能反向加锁。
            release(failed)
        }
    }

    /** 创建本代控制器与异步通道；失败时在锁内摘除半成品，交给 [start] 在锁外释放。 */
    @Synchronized
    private fun startLocked(): Closing? {
        if (started) {
            return null
        }
        try {
            // 异步通道与控制器一起重建：stop() 关闭过的 LatestTask 不能复用（closed 后 run 静默丢弃请求），
            // 所以每次 start 都造一对全新的通道，并作为会话字段持有，供 stop 在锁外显式关闭。
            val loginChannel = LoginAsync()
            val localSaveChannel = LocalSaveAsync()
            loginAsync = loginChannel
            localSaveAsync = localSaveChannel
            searchController = SearchController.createDefault { state -> NativeUiState.search = state }
            playlistController = PlaylistController.createDefault { state -> NativeUiState.playlist = state }
            loginController = LoginController(
                AccountLoginPorts,
                loginChannel,
            ) { state ->
                NativeUiState.login = state
                if (state.phase() == LoginController.Phase.LOGGED_IN) {
                    refreshAccount()
                }
            }
            localSaveController = LocalSaveController(
                LocalSavePorts,
                localSaveChannel,
            ) { state ->
                NativeUiState.localSave = state
                NativeUiState.localEntries = state.entries()
                NativeUiState.localNote = state.message()
                if (state.saved()) {
                    NativeUiState.saveName = ""
                }
            }
            verifyController = VerifyEmbedController(VerifyListener)
            NativeUiState.search = searchController?.state()
            NativeUiState.playlist = playlistController?.state()
            NativeUiState.login = loginController?.state()
            NativeUiState.localSave = localSaveController?.state()
            NativeUiState.verify = verifyController?.state()
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
            startLocalReader()
            refreshSync()
            refreshAccount()
            PluginLog.i(TAG, "原生页面控制器已启动（tick=250ms）")
            return null
        } catch (t: Throwable) {
            PluginLog.w(TAG, "原生页面控制器启动失败（已隔离）：$t")
            // 启动失败不留半成品：控制器与已创建的异步通道都要释放；锁内只摘除，关闭在 [start] 锁外做。
            return detachLocked()
        }
    }

    /**
     * 停用插件时调用：销毁整个控制器集合——取消定时器、关闭控制器，并在会话锁外关闭
     * 登录/本地保存的异步通道（有界等待线程退出）；页面随后不再更新。
     *
     * <p>与 [closeLogin] 的区别：关面板只动 [loginController]（见其 close 契约），异步通道保留，
     * 用户可以再次打开登录页；本方法关闭整代对象，[start] 会重建全新实例。所有关闭动作都在
     * 会话锁外执行：LatestTask 的回调会去取控制器锁，反过来在持锁时等 LatestTask 监视器会形成
     * 反向锁序；顺序固定为「先控制器（停回调），后异步通道」。</p>
     */
    fun stop() {
        release(detachLocked())
    }

    /** 在会话锁内摘除当前一代控制器/异步通道；返回的对象必须在锁外关闭，避免锁序反转。 */
    @Synchronized
    private fun detachLocked(): Closing {
        val closing = Closing(
            timer = timer,
            search = searchController,
            playlist = playlistController,
            login = loginController,
            localSave = localSaveController,
            verify = verifyController,
            loginAsync = loginAsync,
            localSaveAsync = localSaveAsync,
            localReader = localReader,
        )
        timer = null
        searchController = null
        playlistController = null
        loginController = null
        localSaveController = null
        verifyController = null
        loginAsync = null
        localSaveAsync = null
        localReader = null
        verifyAreaLaidOut = false
        started = false
        // 停用后任何仍挂着的旧登录页/验证页回调都失效，不能再启动助手进程。
        loginGeneration++
        return closing
    }

    /** 关闭一代控制器与异步通道（不持有会话锁；[stop] 与 [start] 失败回滚共用）。 */
    private fun release(closing: Closing) {
        NativeSongMenu.stop()
        try {
            closing.timer?.cancel()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "取消原生页面定时器失败（已忽略）：$t")
        }
        try {
            closing.search?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭搜索控制器失败（已忽略）：$t")
        }
        try {
            closing.playlist?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭歌单控制器失败（已忽略）：$t")
        }
        try {
            // 只关面板：停二维码轮询、关验证窗、走 AccountService 的取消/恢复语义（不会退出账号）。
            closing.login?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭登录控制器失败（已忽略）：$t")
        }
        try {
            closing.localSave?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭本地命名控制器失败（已忽略）：$t")
        }
        try {
            // 有界关闭助手进程（close 内部 600ms + 400ms 后强制结束），失败也不影响停用流程。
            closing.verify?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭内嵌验证控制器失败（已忽略）：$t")
        }
        // 必须等控制器都关完、且不持有任何控制器锁时再关异步通道：LatestTask.publish 在
        // synchronized(this) 里回调控制器，若此时还持有控制器锁就会互相等待。
        try {
            closing.loginAsync?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭登录异步通道失败（已忽略）：$t")
        }
        try {
            closing.localSaveAsync?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭本地保存异步通道失败（已忽略）：$t")
        }
        // 读盘线程是 daemon；shutdownNow 中断 take()，读盘中的 LocalPlaylists.list() 返回后自行退出。
        try {
            closing.localReader?.shutdownNow()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭本地歌单读盘线程失败（已忽略）：$t")
        }
        // LatestTask.close 只 shutdownNow、不 join；这里做有界回收确认，避免反复启停累积线程。
        awaitWorkersGone(LOGIN_THREAD_NAME)
        awaitWorkersGone(LOCAL_SAVE_THREAD_NAME)
        awaitWorkersGone(LOCAL_READ_THREAD_NAME)
    }

    /** 等待指定名字的 daemon 线程退出（有界；超时只记日志，绝不阻塞停用流程）。 */
    private fun awaitWorkersGone(threadName: String) {
        val deadline = System.nanoTime() + ASYNC_CLOSE_WAIT_MS * 1_000_000L
        while (true) {
            val alive = Thread.getAllStackTraces().keys.any { it.isAlive && it.name == threadName }
            if (!alive) {
                return
            }
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) {
                PluginLog.d(TAG, "异步通道线程未在 ${ASYNC_CLOSE_WAIT_MS}ms 内退出（daemon，不阻塞停用）：$threadName")
                return
            }
            try {
                Thread.sleep(minOf(remainingMs, 20L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** 一次 [stop]/启动失败要释放的整代对象；顺序固定：先控制器，后异步通道。 */
    private class Closing(
        val timer: Timer?,
        val search: SearchController?,
        val playlist: PlaylistController?,
        val login: LoginController?,
        val localSave: LocalSaveController?,
        val verify: VerifyEmbedController?,
        val loginAsync: LoginAsync?,
        val localSaveAsync: LocalSaveAsync?,
        val localReader: ExecutorService?,
    )

    fun search(): SearchController? = searchController

    fun playlist(): PlaylistController? = playlistController

    fun login(): LoginController? = loginController

    /** 当前登录展示代次；页面把它冻结进快照，所有动作先比对这个值。 */
    fun loginGeneration(): Long = loginGeneration

    fun localSave(): LocalSaveController? = localSaveController

    fun verify(): VerifyEmbedController? = verifyController

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
            NativePage.SEARCH, NativePage.HOME, NativePage.DEVELOPER -> Unit
        }
        return true
    }

    /** 打开唯一的登录流程（幂等；重复调用不会重复创建二维码轮询）。 */
    @Synchronized
    fun openLogin() {
        loginGeneration++
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
    @Synchronized
    fun closeLogin() {
        // 先换代次：退场页的 DisposableEffect/Actions 立刻失效；真正的控制器关闭仍在本方法内完成。
        loginGeneration++
        try {
            loginController?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭登录流程失败（已忽略）：$t")
        }
        // 登录会话一结束就关掉原生验证子窗：不能等退场页 120ms 后的 onDispose（那时代次已换）。
        closeEmbeddedVerify()
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
            NativeUiState.daily = com.example.netease.svc.DailyRecommendations.state()
            // PLAN §8.2：账号一变化，旧搜索请求与旧播放反馈立即失效（列表/选中保留，见 SearchController）。
            val epoch = sessionEpoch()
            if (epoch >= 0L && epoch != lastAccountEpoch) {
                lastAccountEpoch = epoch
                searchController?.onAccountChanged()
                NativeTopSearch.accountChanged()
                NativeSongMenu.stop()
                // 账号切换时旧账号的移除确认/结果提示必须立即关闭，并让 (epoch,uid) 菜单缓存失效。
                HostCloudSongMenu.stop()
                HostManagedPlaylists.accountChanged()
            }
            searchController?.tick()
            NativeTopSearch.tick()
            playlistController?.tick()
            loginController?.tick()
            val n = ticks++
            if (n % 4L == 0L) {
                refreshSync()
                refreshAccount()
            }
            if (localRefreshRequested) {
                localRefreshRequested = false
                localReadSignals.offer(Unit)
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
            val status = NeteasePlugin.syncStatusSnapshot()
            NativeUiState.sync = status
            val phase = status?.phase()
            // 同步刚完成时云歌单/成员关系可能已变；只在 COMPLETED 的上升沿失效一次。
            if (phase == SyncCoordinator.Phase.COMPLETED && lastSyncPhase != SyncCoordinator.Phase.COMPLETED) {
                CloudMenuCache.invalidateAll()
            }
            lastSyncPhase = phase
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

    /**
     * 启动读盘线程：信号到达后清空队列（合并读盘期间的重复请求），读盘前后各校验一次
     * started 与账号 epoch——账号在扫描期间切换时结果属于旧账号，直接丢弃并重新排队。
     */
    private fun startLocalReader() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, LOCAL_READ_THREAD_NAME).apply { isDaemon = true }
        }
        localReader = executor
        executor.execute {
            // 线程身份也参与退出判断：stop() 后立刻 start() 会重建 executor，
            // 旧线程不能继续消费新会话的信号、更不能把旧一代的列表写进新会话。
            while (started && localReader === executor) {
                try {
                    localReadSignals.take()
                } catch (e: InterruptedException) {
                    return@execute
                }
                localReadSignals.clear()
                if (!started || localReader !== executor) {
                    return@execute
                }
                val epoch = sessionEpoch()
                val entries = try {
                    LocalPlaylists.list()
                } catch (t: Throwable) {
                    PluginLog.d(TAG, "读取本地歌单列表失败（已忽略）：$t")
                    null
                } ?: continue
                if (!started || localReader !== executor) {
                    return@execute
                }
                if (epoch != sessionEpoch()) {
                    localReadSignals.offer(Unit)
                    continue
                }
                NativeUiState.localEntries = entries
            }
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

    /**
     * 「存为本地歌单」：在用户确认保存的调用栈内，从当前歌单状态**一次性**取出来源名与曲目列表
     * （同一个 [PlaylistController.State] 快照，不会一个旧一个新），交给保存控制器在锁内固定后写盘。
     *
     * <p>不能依赖 [captureLocalSave] 的「来源名变了才刷新」快照：同一歌单重载内容、刷新同一来源
     * 的列表或切到另一张同名歌单时来源名不变，旧列表会盖掉用户此刻看到的列表（REVIEW issue 5）。
     * 提交后切页只改 [NativeUiState.playlist]；控制器已在 submit 调用栈内拷贝了曲目，写盘不受影响。</p>
     */
    fun submitLocalSave(name: String) {
        try {
            val controller = localSaveController ?: return
            // 只读一次 State：sourceName 与 tracks 必须来自同一份页面快照，避免「来源新、列表旧」。
            val current = NativeUiState.playlist
            controller.submit(
                name,
                current?.tracks() ?: emptyList(),
                current?.sourceName() ?: "",
            )
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

    // ------------------------------------------------------------ 内嵌验证（批次 4）

    /**
     * 打开验证区域：父窗标题/内容尺寸/缩放来自 [VerifyHostWindow]（Compose 侧捕获的真实宿主窗口）。
     * 助手会先做「进程 + 窗口身份」唯一性核对；采用后由 [updateEmbeddedVerify] 送出客户区物理矩形。
     */
    internal fun openEmbeddedVerify(url: String, source: String) {
        val vc = verifyController ?: return
        if (url.isBlank()) {
            NativeUiState.verify = vc.state()
            return
        }
        verifyGeneration = loginGeneration
        try {
            vc.open(
                url,
                source,
                // 宿主窗口标题是当前播放曲名、随时会变：拿它做身份核对会因标题过期而错过唯一窗口。
                // 身份核对退化为「同进程 + 顶层 SunAwtFrame + 唯一」，匹配不唯一时助手会拒绝挂载并回报原因。
                "",
                VerifyHostWindow.contentWidth,
                VerifyHostWindow.contentHeight,
                VerifyHostWindow.scale,
            )
            NativeUiState.verify = vc.state()
        } catch (t: Throwable) {
            PluginLog.w(TAG, "启动内嵌验证区域失败（已隔离）：$t")
        }
    }

    /**
     * Compose 布局变化：逻辑坐标（window 内）只在这里交给控制器换算一次物理像素；
     * 网页区域就绪后按「页面开着 且 仍停在登录页」决定显示。
     */
    internal fun updateEmbeddedVerify(left: Float, top: Float, width: Float, height: Float, scale: Double) {
        val vc = verifyController ?: return
        verifyAreaLaidOut = true
        try {
            vc.setBounds(
                Math.round(left),
                Math.round(top),
                Math.round(width),
                Math.round(height),
                scale,
            )
            applyVerifyVisibility()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "更新验证区域位置失败（已忽略）：$t")
        }
    }

    /** 页面切走/关闭时隐藏网页区域（不关闭助手进程，返回登录页可以再显示）。 */
    internal fun hideEmbeddedVerify() {
        verifyAreaLaidOut = false
        try {
            verifyController?.setVisible(false)
        } catch (t: Throwable) {
            PluginLog.d(TAG, "隐藏验证区域失败（已忽略）：$t")
        }
    }

    /** 只有仍属于当前登录代次时才隐藏；旧退场页晚到的布局回调不能盖掉新会话。 */
    internal fun hideEmbeddedVerifyFor(generation: Long) {
        if (loginGeneration != generation) return
        hideEmbeddedVerify()
    }

    /** 只有仍属于当前登录代次时才关闭；旧退场页的 onDispose 不能关掉新一代验证窗。 */
    internal fun closeEmbeddedVerifyFor(generation: Long) {
        if (loginGeneration != generation) return
        closeEmbeddedVerify()
    }

    /** 关闭登录流程/停用插件时关闭验证区域（有界退出助手进程）。 */
    internal fun closeEmbeddedVerify() {
        verifyAreaLaidOut = false
        verifyGeneration = -1L
        try {
            verifyController?.close()
        } catch (t: Throwable) {
            PluginLog.d(TAG, "关闭验证区域失败（已忽略）：$t")
        }
        NativeUiState.verify = verifyController?.state()
    }

    private fun applyVerifyVisibility() {
        val vc = verifyController ?: return
        if (vc.state().phase() != VerifyEmbedController.Phase.READY) {
            return
        }
        val show = verifyAreaLaidOut && verifyGeneration == loginGeneration &&
            NativeUiState.visible && NativeUiState.page == NativePage.LOGIN
        try {
            vc.setVisible(show)
        } catch (t: Throwable) {
            PluginLog.d(TAG, "切换验证区域可见性失败（已忽略）：$t")
        }
    }

    private object VerifyListener : VerifyEmbedController.Listener {
        override fun onDismissRequested() { java.awt.EventQueue.invokeLater { closeLogin() } }
        override fun onStateChanged(state: VerifyEmbedController.State) {
            NativeUiState.verify = state
            // 就绪/失败都可能发生在后台线程，这里只更新 Compose state 并同步可见性（控制器内部有锁）。
            applyVerifyVisibility()
        }
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
            // 批次 4：验证页改在登录页内部由 WebView2 助手渲染，不再创建独立验证窗口。
            // onRetry 是旧验证窗按钮的回调；原生页直接调 controller.retryAfterVerify()，这里不需要它。
            openEmbeddedVerify(url, if (fromQr) "扫码" else "验证码登录")
        }

        override fun closeVerify() {
            closeEmbeddedVerify()
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
        private val task = LatestTask(LOGIN_THREAD_NAME, REQUEST_TIMEOUT_MS) { r -> r.run() }

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
        private val task = LatestTask(LOCAL_SAVE_THREAD_NAME, REQUEST_TIMEOUT_MS) { r -> r.run() }

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

    /** stop() 等待异步通道线程退出的上限（LatestTask.close 只 shutdownNow、不 join）。 */
    private const val ASYNC_CLOSE_WAIT_MS = 1_000L

    private const val LOGIN_THREAD_NAME = "native-ui-login"

    private const val LOCAL_SAVE_THREAD_NAME = "native-ui-local-save"

    /** 本地歌单读盘线程；tick 只投递合并信号，读盘不占 250ms 拍子。 */
    private const val LOCAL_READ_THREAD_NAME = "native-ui-local-read"
}
