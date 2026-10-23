package com.example.netease.nativeui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Presentation history only; controllers continue to own every business action. */
internal object NativeUiPresentation {
    private var returnPage by mutableStateOf(NativePage.HOME)
    private var returnVisible by mutableStateOf(false)
    var localSaveOpen by mutableStateOf(false)
        private set

    fun isPage(page: NativePage) = page == NativePage.SEARCH || page == NativePage.PLAYLIST
    fun modalOpen() = NativeUiState.visible && NativeUiState.page != NativePage.HOME && !isPage(NativeUiState.page)
    fun anyDialogOpen() = modalOpen() || localSaveOpen || HostCloudSongMenu.removal != null || HostCloudSongMenu.message.isNotBlank()
    fun openLocalSave() { localSaveOpen = true }
    fun closeLocalSave(cancel: Boolean) {
        if (localSaveOpen && cancel) NativeUiSession.cancelLocalSave()
        localSaveOpen = false
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
        if (NativeUiState.page == NativePage.LOGIN) NativeUiSession.closeLogin()
        NativeUiState.page = returnPage
        NativeUiState.visible = returnVisible
    }

    fun leaveHostRoute() {
        // Search is presentation state of this route, never a second back stack.
        // A later entry through the plugin list must open configuration directly.
        showConfiguration()
    }

    fun showConfiguration() {
        NativeSongMenu.dismiss()
        closeLocalSave(cancel = true)
        if (NativeUiState.page == NativePage.LOGIN) NativeUiSession.closeLogin()
        returnPage = NativePage.HOME
        returnVisible = false
        NativeUiState.page = NativePage.HOME
        NativeUiState.visible = false
    }
}
