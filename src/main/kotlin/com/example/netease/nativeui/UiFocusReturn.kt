@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.awt.LocalAwtWindow
import java.awt.Window
import java.util.WeakHashMap

/**
 * 焦点归还：只有「真正被用户触发、且当前仍挂在组合上」的控件才有资格接收归还。
 *
 * <p>按窗口隔离（WeakHashMap<Window, Entry>）：主窗口/配置窗口各自记住最后一个触发点，
 * 不会把一个窗口的焦点串到另一个窗口。登记发生在真实指针按下或获得焦点时，
 * 而不是组合完成时；控件离开组合会注销，避免归还给已卸载的节点。</p>
 */
internal object FocusReturn {
    private class Entry(val requester: FocusRequester, val order: Long)

    private val targets = WeakHashMap<Window, Entry>()
    private var sequence: Long = 0L

    fun note(window: Window?, requester: FocusRequester) {
        if (window == null) return
        synchronized(targets) {
            sequence += 1
            targets[window] = Entry(requester, sequence)
        }
    }

    fun forget(window: Window?, requester: FocusRequester) {
        if (window == null) return
        synchronized(targets) {
            if (targets[window]?.requester === requester) targets.remove(window)
        }
    }

    /** 归还到该窗口最后一个仍存活的触发点；控件已卸载或窗口不在焦点时返回 false。 */
    fun restore(window: Window?): Boolean {
        if (window == null) return false
        val entry = synchronized(targets) { targets[window] } ?: return false
        if (!window.isDisplayable) {
            synchronized(targets) { targets.remove(window) }
            return false
        }
        return runCatching { entry.requester.requestFocus() }.isSuccess
    }

    fun clear(window: Window?) {
        if (window == null) return
        synchronized(targets) { targets.remove(window) }
    }
}

/**
 * 给可聚焦的触发控件装上「登记 + 焦点归还」：指针按下时登记（真实触发点），
 * 获得焦点时也登记（键盘 Tab 触发），组合离开时注销。
 */
@Composable
internal fun Modifier.focusReturnTarget(window: Window? = LocalAwtWindow.current): Modifier {
    val requester = remember { FocusRequester() }
    val owner = remember { FocusReturn }
    DisposableEffect(window, requester) {
        guardedCleanup { owner.forget(window, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) owner.note(window, requester) }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
            owner.note(window, requester)
        }
}
