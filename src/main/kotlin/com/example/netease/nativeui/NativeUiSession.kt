package com.example.netease.nativeui

import com.example.netease.NeteasePlugin
import com.example.netease.core.PluginLog
import com.example.netease.net.Dto
import com.example.netease.svc.LocalPlaylists
import com.example.netease.svc.SyncCoordinator
import com.example.netease.ui.controller.PlaylistController
import com.example.netease.ui.controller.SearchController
import java.util.Timer
import java.util.TimerTask

/**
 * 原生页面的控制器宿主：创建搜索/歌单控制器（业务逻辑全在 Java 控制器里，页面只渲染 State），
 * 并维护一个 250ms 的后台 tick：
 * <ul>
 *   <li>搜索/歌单控制器各 tick 一次（只轮询宿主播放回报与账号变化，不做网络请求）；</li>
 *   <li>每 4 次 tick（1 秒）刷新一次只读同步状态；</li>
 *   <li>按需刷新本地歌单列表（读盘在 tick 线程上，不阻塞 UI 线程）。</li>
 * </ul>
 *
 * <p>关闭页面只改 [NativeUiState.visible]，不停止 tick、不取消已提交的同步；
 * 停用插件时 [stop] 关闭控制器并取消定时器。</p>
 */
internal object NativeUiSession {
    private const val TAG = "native-ui"

    @Volatile
    private var searchController: SearchController? = null

    @Volatile
    private var playlistController: PlaylistController? = null

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var ticks: Long = 0L

    @Volatile
    private var localRefreshRequested: Boolean = false

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
            NativeUiState.search = searchController?.state()
            NativeUiState.playlist = playlistController?.state()
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
        searchController = null
        playlistController = null
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
    }

    fun search(): SearchController? = searchController

    fun playlist(): PlaylistController? = playlistController

    private fun tickOnce() {
        try {
            searchController?.tick()
            playlistController?.tick()
            val n = ticks++
            if (n % 4L == 0L) {
                refreshSync()
            }
            if (localRefreshRequested) {
                localRefreshRequested = false
                refreshLocalNow()
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

    /** 「保存当前曲目为本地歌单」：沿用旧窗口的 LocalPlaylists.saveManual 通路。 */
    fun saveCurrentAsLocal(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            NativeUiState.localNote = "请输入本地歌单名"
            return
        }
        val tracks: List<Dto.Song> = NativeUiState.playlist?.tracks() ?: emptyList()
        if (tracks.isEmpty()) {
            NativeUiState.localNote = "当前曲目表是空的，先载入一张歌单"
            return
        }
        Thread({
            try {
                val key = LocalPlaylists.saveManual(trimmed, tracks)
                NativeUiState.localNote =
                    if (key != null) "已存为本地歌单「" + trimmed + "」（" + tracks.size + " 首）" else "保存本地歌单失败，详情见插件日志"
                refreshLocalNow()
            } catch (t: Throwable) {
                PluginLog.w(TAG, "保存本地歌单失败（已隔离）：$t")
                NativeUiState.localNote = "保存本地歌单失败，详情见插件日志"
            }
        }, "native-ui-save-local").start()
    }
}
