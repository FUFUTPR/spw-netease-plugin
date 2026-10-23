@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)

package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Uses the host dialog's frame and ColumnDialog body in this same composition.
 * Calling only these content functions keeps LocalAwtWindow and WebView2's parent
 * unchanged; there is no Compose DialogWindow or native secondary window. */
@Composable
internal fun EmbeddedSaltDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = {},
    confirmVisible: Boolean = false,
    cancelText: String = "关闭",
    confirmText: String = "确定",
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .60f))
        .consumeAllPointerEvents().onPreviewKeyEvent {
            when {
                it.type != KeyEventType.KeyDown -> false
                it.key == Key.Escape -> { onDismiss(); true }
                it.key == Key.Tab -> {
                    focusManager.moveFocus(if (it.isShiftPressed) androidx.compose.ui.focus.FocusDirection.Previous else androidx.compose.ui.focus.FocusDirection.Next)
                    true
                }
                else -> false
            }
        }.focusProperties { onExit = { cancelFocusChange() } }.focusGroup(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().focusRequester(focus).focusable())
        Box(Modifier.padding(16.dp).widthIn(max = HostSaltComponents.dialogMaxWidth.dp).semantics { dialog() }) {
            val padded: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp)) { content() } }
            val body: @Composable ColumnScope.() -> Unit = {
                HostSaltComponents.drawDialogBody(title, padded, onDismiss, onConfirm, confirmVisible,
                    cancelText, confirmText, this, currentComposer)
            }
            HostSaltComponents.drawDialogFrame(body, currentComposer)
        }
    }
}
