@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)

package com.example.netease.nativeui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 对话框卡片缩放是否已完成：原生验证子窗只在 true 时才允许 show，避免网页窗口跟着卡片缩放。 */
internal val LocalDialogMotionSettled = staticCompositionLocalOf { true }

/** 离屏回归探针的只读快照：只记录卡片当前的缩放/落定状态，不参与任何业务判断。
 *  生产逻辑仍只读 [Animatable]；测试用它断言“退场中重开也必须回到 scale=1 且 settled”。 */
internal object DialogMotionProbe {
    @JvmField @Volatile var scale: Float = 0.96f
    @JvmField @Volatile var settled: Boolean = false
    @JvmField @Volatile var interactive: Boolean = true
}

/** Uses the host dialog's frame and ColumnDialog body in this same composition.
 * Calling only these content functions keeps LocalAwtWindow and WebView2's parent
 * unchanged; there is no Compose DialogWindow or native secondary window.
 *
 * interactive=false 只用于退场快照：视觉还在，但点击、键盘、焦点全部失效，
 * 避免 120ms 退场里触发第二次关闭或把焦点交给正在消失的控件。 */
@Composable
internal fun EmbeddedSaltDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = {},
    confirmVisible: Boolean = false,
    cancelText: String = "关闭",
    confirmText: String = "确定",
    interactive: Boolean = true,
    dismissVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // 遮罩始终全屏，只有卡片做缩放：全屏 scrim 参与缩放会在窗口边缘露底，
    // 也会把原生验证子窗的实际位置带着一起动。
    val scale = remember { Animatable(0.96f) }
    // 入场和退场必须放在同一个可取消 effect 里：关闭后 120ms 内重开时，
    // AnimatedContent 会复用原来的 slot，LaunchedEffect(Unit) 不会重跑；
    // 只把“退场”挂在 interactive 上会让 scale 永久停在 0.98，settled 永远 false。
    LaunchedEffect(interactive) {
        if (interactive) {
            // 等一帧布局落定再抢焦点：首帧 focusRequester 可能还没附着到节点。
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            focusManager.moveFocus(FocusDirection.Next)
            // 从当前值（可能是退场中的 0.98）反向动画回 1；effect 取消保证不会两条动画并发。
            scale.animateTo(1f, tween(Motion.DIALOG_ENTER_MS, easing = Motion.easing()))
        } else {
            // 退场只收缩卡片，遮罩由外层整层淡出；此时内容已不可交互。
            scale.animateTo(0.98f, tween(Motion.DIALOG_EXIT_MS, easing = Motion.easing()))
        }
    }
    val settled = !scale.isRunning && scale.value == 1f
    SideEffect {
        DialogMotionProbe.scale = scale.value
        DialogMotionProbe.settled = settled
        DialogMotionProbe.interactive = interactive
    }
    val modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = .60f))
        .then(if (interactive) Modifier else Modifier.blockAllPointerEvents())
        .consumeAllPointerEvents()
        .onPreviewKeyEvent { event ->
            if (!interactive) {
                true
            } else {
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Escape -> { onDismiss(); true }
                    event.key == Key.Tab -> {
                        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    }
                    else -> false
                }
            }
        }
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().focusRequester(focus).focusable(enabled = interactive))
        Box(
            Modifier.padding(16.dp).widthIn(max = HostSaltComponents.dialogMaxWidth.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = TransformOrigin.Center
                }
                // 退场快照从语义树里摘除：辅助功能/自动化不能绕过指针拦截点到正在消失的确认按钮。
                .then(if (interactive) Modifier.semantics { dialog() } else Modifier.clearAndSetSemantics { }),
        ) {
            val padded: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp)) { content() } }
            val body: @Composable ColumnScope.() -> Unit = {
                HostSaltComponents.drawDialogBody(title, padded, onDismiss, onConfirm,
                    confirmVisible && interactive, cancelText, confirmText, this, currentComposer, dismissVisible)
            }
            CompositionLocalProvider(LocalDialogMotionSettled provides settled) {
                HostSaltComponents.drawDialogFrame(body, currentComposer)
            }
        }
    }
}
