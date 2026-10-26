@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.netease.nativeui

import androidx.compose.ui.ExperimentalComposeUiApi

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.core.DataPaths
import com.example.netease.ui.LinuxVerifySurface
import com.example.netease.ui.controller.VerifyEmbedController
import javax.swing.JPanel
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window

/**
 * 宿主父窗口的定位信息：助手（VerifyHost）要用它核对「进程身份 + 窗口身份」。
 *
 * <p>只从 Compose 的 [LocalAwtWindow] 取一次真实宿主窗口：标题用于唯一性核对，
 * 内容区逻辑尺寸用于 --expect-client（助手会把它和 GetClientRect 的物理尺寸比，
 * 差超过 12px 就拒绝挂载），scale 是 AWT defaultTransform 的缩放（逻辑 → 物理只换算一次）。</p>
 */
internal object VerifyHostWindow {
    @Volatile
    var title: String = ""

    @Volatile
    var contentWidth: Int = 0

    @Volatile
    var contentHeight: Int = 0

    @Volatile
    var scale: Double = 1.0

    fun capture(window: Window?) {
        if (window == null) {
            // 极早期（Compose 还没挂到 AWT 窗口上）也要有一个可用的缩放值：
            // 取屏幕默认配置的 defaultTransform，之后真正捕获到窗口时会覆盖它。
            if (scale <= 0.0) {
                scale = runCatching {
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
                }.getOrDefault(1.0)
            }
            return
        }
        try {
            title = when (window) {
                is Frame -> window.title ?: ""
                is Dialog -> window.title ?: ""
                else -> ""
            }
            scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            val insets = window.insets ?: return
            contentWidth = (window.width - insets.left - insets.right).coerceAtLeast(0)
            contentHeight = (window.height - insets.top - insets.bottom).coerceAtLeast(0)
        } catch (t: Throwable) {
            // 窗口正在销毁：保留上一次的值即可，不能因为取不到父窗信息就中断登录流程。
        }
    }
}

/**
 * 登录页「行为验证」内嵌区域（docs/PLAN.md §9）。
 *
 * <p>这里只是占位 + 状态面板：真正的网页由 VerifyHost.exe（WebView2）渲染成宿主主窗口的
 * **原生子窗口**，按本 Box 的 boundsInWindow 精确覆盖。原生子窗口不能做位置/缩放动画，所以：
 * 入场等卡片缩放落定（[LocalDialogMotionSettled]）才 show，退场一变成不可交互就立即 hide；
 * 页面切走/关闭/停用不会让它压住别的原生页面。</p>
 *
 * <p>0.12.18：一切开关都带「会话代次」。旧登录页的 dispose 晚 120ms 才跑，它只能关掉自己那一代
 * （[NativeUiSession.closeEmbeddedVerifyFor]），藏在代次校验后面，绝不会关掉刚打开的新验证；
 * 显示数据来自父层冻结的 [state] 快照，退场时不会被 CLOSED 之类的迟到状态改写。</p>
 */
@Composable
internal fun EmbeddedVerifyArea(
    url: String,
    source: String,
    generation: Long,
    interactive: Boolean,
    state: VerifyEmbedController.State?,
    onRetry: () -> Unit,
) {
    val controller = NativeUiSession.verify()
    val awtWindow = LocalAwtWindow.current
    // 卡片缩放/淡入还没落定（stable=false）时不 show 子窗口：子窗口不参与动画，
    // 一入场就贴上去会「先出现在半透明卡片外，再瞬移回位」，等于给原生子窗做了位移动画。
    val stable = LocalDialogMotionSettled.current
    SideEffect { VerifyHostWindow.capture(awtWindow) }
    LaunchedEffect(generation, url, interactive, stable) {
        if (interactive && stable && generation == NativeUiSession.loginGeneration() && url.isNotBlank()) {
            NativeUiSession.openEmbeddedVerify(url, source)
        } else {
            // 退场/收起立即隐藏（不等 120ms 淡出结束）；旧代次调用在 NativeUiSession 里直接 no-op。
            NativeUiSession.hideEmbeddedVerifyFor(generation)
        }
    }
    DisposableEffect(generation) {
        onDispose {
            // 只关同代次：旧页面晚 120ms 才销毁，无代次关闭会打断刚打开的新一轮验证。
            NativeUiSession.closeEmbeddedVerifyFor(generation)
            // Linux 画布的静态引用同理：本代次仍是当前会话时才释放，别把新实例刚建的 surface 置空。
            if (generation == NativeUiSession.loginGeneration()) LinuxVerifySurface.release()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 验证区内部只允许文案 Crossfade，任何几何变化都不做动画（原生子窗口跟不动）。
            Crossfade(targetState = headline(state), animationSpec = Motion.state(), label = "login-verify-headline") { text ->
                SaltText(
                    text = text,
                    style = HostStyle.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state?.retryable() == true) {
                SaltSecondaryButton(onClick = onRetry, enabled = interactive) { SaltText("重试") }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .border(1.dp, HostStyle.colors.stroke, RoundedCornerShape(6.dp))
                .onGloballyPositioned { coords ->
                    // 旧代次的退场内容不写全局矩形：它画在动画中的位置，写进去会让新会话的子窗口乱跳。
                    if (generation != NativeUiSession.loginGeneration()) return@onGloballyPositioned
                    val bounds = coords.boundsInWindow()
                    // The host dialog scrolls its content. Hide the native child
                    // when its placeholder is completely clipped by that viewport.
                    if (bounds.width < 1f || bounds.height < 1f) {
                        NativeUiSession.hideEmbeddedVerifyFor(generation)
                    } else {
                        NativeUiSession.updateEmbeddedVerify(
                            bounds.left, bounds.top, bounds.width, bounds.height, VerifyHostWindow.scale,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (DataPaths.nativeLinux() && LinuxVerifySurface.supported()) {
                SwingPanel(
                    factory = { JPanel(BorderLayout()).apply {
                        add(LinuxVerifySurface.create().apply {
                            addFocusListener(object : java.awt.event.FocusAdapter() {
                                override fun focusGained(event: java.awt.event.FocusEvent) {
                                    // 迟到的焦点事件不能把焦点抢到旧会话的验证页上。
                                    if (interactive && generation == NativeUiSession.loginGeneration()) controller?.focus()
                                }
                            })
                        }, BorderLayout.CENTER)
                    } },
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                )
            }
            // 网页区域就绪后会被原生子窗口盖住；这里只在启动中/失败/关闭时显示占位文案。
            val phase = state?.phase()
            if (phase != VerifyEmbedController.Phase.READY) {
                SaltText(
                    text = when (phase) {
                        VerifyEmbedController.Phase.STARTING -> "正在启动内嵌验证组件…"
                        VerifyEmbedController.Phase.FAILED -> "验证区域未能显示（原因见下方），可点「重试」"
                        VerifyEmbedController.Phase.CLOSED -> "验证区域已关闭"
                        null -> "正在准备验证区域…"
                        else -> "验证区域准备中…"
                    },
                    style = HostStyle.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Crossfade(targetState = failureText(state), animationSpec = Motion.state(), label = "login-verify-failure") { text ->
            if (text.isNotBlank()) {
                SaltText(
                    text = text,
                    style = HostStyle.typography.labelMedium,
                    color = HostStyle.colors.error,
                )
            }
        }
        SaltText(
            text = (if (DataPaths.nativeLinux()) "验证页由系统 WebKitGTK 在主窗口内渲染；" else "验证页由本机 WebView2 在宿主主窗口内渲染；") +
                "加载失败或验证组件异常时在本区域内重试。验证完成后点下方「我已完成，重试」。",
            style = HostStyle.typography.labelSmall,
        )
        if (url.isBlank()) {
            SaltText("（当前没有验证地址）", style = HostStyle.typography.labelSmall)
        }
        if (state == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaltSecondaryButton(onClick = onRetry, enabled = interactive) { SaltText("重新获取验证地址") }
            }
        }
    }
}

private fun headline(state: VerifyEmbedController.State?): String = when (state?.phase()) {
    VerifyEmbedController.Phase.STARTING -> "内嵌验证区域：启动中"
    VerifyEmbedController.Phase.READY -> "内嵌验证区域：网页已就绪"
    VerifyEmbedController.Phase.FAILED -> "内嵌验证区域：不可用"
    VerifyEmbedController.Phase.CLOSED -> "内嵌验证区域：已关闭"
    VerifyEmbedController.Phase.IDLE -> "内嵌验证区域：尚未启动"
    null -> "内嵌验证区域"
}

private fun failureText(state: VerifyEmbedController.State?): String =
    if (state?.phase() == VerifyEmbedController.Phase.FAILED) state.message() else ""
