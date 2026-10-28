package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/** The host handles Escape above the plugin's Compose subtree. Intercept only
 * our main window's modal Escape before that root handler sees it. Registration
 * follows the plugin route; release and auto-repeat must not navigate the host. */
@Composable
internal fun NativeModalEscapeGate(window: Window?) {
    DisposableEffect(window) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        var consuming = false
        val dispatcher = KeyEventDispatcher { event ->
            val sourceWindow = event.component?.let { if (it is Window) it else SwingUtilities.getWindowAncestor(it) }
            if (window == null || event.keyCode != KeyEvent.VK_ESCAPE || sourceWindow !== window) {
                false
            } else if (consuming) {
                if (event.id == KeyEvent.KEY_RELEASED) consuming = false
                true
            } else if (event.id == KeyEvent.KEY_PRESSED && (NativeUiPresentation.anyDialogOpen() || NativeTopSearch.expanded || NativeTopSearch.focused)) {
                consuming = true
                if (!NativeUiPresentation.anyDialogOpen()) NativeTopSearch.collapse()
                else if (NativeUpdateUi.dialogOpen()) NativeUpdateUi.dismiss()
                else if (HostDailyRecommendations.dialogOpen()) HostDailyRecommendations.dismiss()
                else if (HostManagedPlaylists.dialogOpen()) HostManagedPlaylists.dismiss()
                else if (HostCloudSongMenu.removal != null) HostCloudSongMenu.dismissRemoval()
                else if (HostCloudSongMenu.message.isNotBlank()) HostCloudSongMenu.dismissMessage()
                else if (NativeUiPresentation.localSaveOpen) NativeUiPresentation.closeLocalSave(cancel = true)
                else NativeUiPresentation.dismissModal()
                true
            } else false
        }
        if (window != null) manager.addKeyEventDispatcher(dispatcher)
        guardedCleanup { if (window != null) manager.removeKeyEventDispatcher(dispatcher) }
    }
}
