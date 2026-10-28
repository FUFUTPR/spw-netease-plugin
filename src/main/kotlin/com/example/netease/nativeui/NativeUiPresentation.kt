package com.example.netease.nativeui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Presentation history only; controllers continue to own every business action.
 *
 * 0.12.18 起，业务可见状态与“视觉退场”分离：关闭请求立即清业务状态（取消登录、
 * 取消本地保存），动画宿主只保留最后一份不可交互快照。遮罩拦截、Esc 消费与焦点
 * 约束通过 *Animating 标志延续到退场结束，避免退场期间点击穿透/焦点失守。
 */
internal object NativeUiPresentation {
    private var returnPage by mutableStateOf(NativePage.HOME)
    private var returnVisible by mutableStateOf(false)
    var localSaveOpen by mutableStateOf(false)
        private set

    var modalAnimating by mutableStateOf(false)
        internal set
    var localSaveAnimating by mutableStateOf(false)
        internal set
    var pageAnimating by mutableStateOf(false)
        internal set

    // 关闭请求发生的瞬间就置位，不等 snapshotFlow/下一帧：否则「关闭首帧」会出现
    // modalOpen=false 但退场动画尚未被观察到，导致遮罩、Esc、焦点短暂失守。
    private var modalClosing by mutableStateOf(false)
    private var pageClosing by mutableStateOf(false)
    private var localSaveClosing by mutableStateOf(false)
    private var focusReturnPending by mutableStateOf(false)

    /** 页面/模态的退场已真正落到终态（由 NativeUiPage 的 Transition 观察者调用）。 */
    fun modalSettled() {
        modalClosing = false
        modalAnimating = false
    }

    fun pageSettled() {
        pageClosing = false
        pageAnimating = false
    }

    fun localSaveSettled() {
        localSaveClosing = false
        localSaveAnimating = false
    }

    fun resetMotion() {
        modalClosing = false
        pageClosing = false
        localSaveClosing = false
        modalAnimating = false
        pageAnimating = false
        localSaveAnimating = false
        focusReturnPending = false
    }

    fun isPage(page: NativePage) = page == NativePage.SEARCH || page == NativePage.PLAYLIST
    fun modalOpen() = NativeUiState.visible && NativeUiState.page != NativePage.HOME && !isPage(NativeUiState.page)
    fun anyDialogOpen() = modalOpen() || modalClosing || localSaveOpen || localSaveClosing ||
        modalAnimating || localSaveAnimating || HostCloudSongMenu.dialogOpen() || NativeUpdateUi.dialogOpen() || HostDailyRecommendations.dialogOpen() || HostManagedPlaylists.dialogOpen()


    /** 页面转场期间也要阻止背景（宿主配置页）获得焦点/点击。 */
    fun backgroundBlocked() = anyDialogOpen() || pageAnimating || pageClosing

    fun openLocalSave() {
        localSaveOpen = true
        localSaveClosing = false
    }

    fun closeLocalSave(cancel: Boolean) {
        if (localSaveOpen) {
            if (cancel) NativeUiSession.cancelLocalSave()
            localSaveOpen = false
            // 本地保存没有独立的 Transition 包装；同步置 closing，避免关闭首帧到下一帧之间
            // anyDialogOpen() 短暂为 false（与 modal/page 的 closing 兜底一致）。
            localSaveClosing = true
            // 只有真的关掉了本地保存才归还焦点；openPage() 也会调用本函数，
            // 无条件置 pending 会在新页面入场后把焦点抢回旧控件。
            requestFocusReturn()
        }
    }
    fun backgroundPage(): NativePage = when {
        !NativeUiState.visible -> NativePage.HOME
        isPage(NativeUiState.page) -> NativeUiState.page
        modalOpen() && returnVisible -> returnPage
        else -> NativePage.HOME
    }
    fun showsConfiguration() = backgroundPage() == NativePage.HOME

    fun openPage(page: NativePage): Boolean {
        val background = backgroundPage()
        if (!NativeUiSession.openPage(page)) return false
        closeLocalSave(cancel = true)
        if (!isPage(page) && page != NativePage.HOME) {
            returnPage = background
            returnVisible = isPage(background)
        }
        return true
    }

    fun dismissModal() {
        // 业务关闭立即发生；视觉退场是不可交互快照，但遮罩/Esc/焦点必须持续到退场结束。
        if (modalOpen()) {
            modalClosing = true
            modalAnimating = true
        }
        if (NativeUiState.page == NativePage.LOGIN) NativeUiSession.closeLogin()
        NativeUiState.page = returnPage
        NativeUiState.visible = returnVisible
        requestFocusReturn()
    }

    fun leaveHostRoute() {
        // Search is presentation state of this route, never a second back stack.
        // A later entry through the plugin list must open configuration directly.
        showConfiguration()
    }

    fun showConfiguration() {
        if (modalOpen()) {
            modalClosing = true
            modalAnimating = true
        }
        if (isPage(NativeUiState.page) && NativeUiState.visible) {
            pageClosing = true
            pageAnimating = true
        }
        NativeSongMenu.dismiss()
        closeLocalSave(cancel = true)
        if (NativeUiState.page == NativePage.LOGIN) NativeUiSession.closeLogin()
        returnPage = NativePage.HOME
        returnVisible = false
        NativeUiState.page = NativePage.HOME
        NativeUiState.visible = false
        requestFocusReturn()
    }

    /** 旧退场内容不可再操作；只有目标态仍是当前页才允许交互。 */
    fun dialogInteractive(page: NativePage) =
        modalOpen() && NativeUiState.page == page

    fun requestFocusReturn() { focusReturnPending = true }

    fun consumeFocusReturn(): Boolean {
        if (!focusReturnPending) return false
        focusReturnPending = false
        return true
    }
}
