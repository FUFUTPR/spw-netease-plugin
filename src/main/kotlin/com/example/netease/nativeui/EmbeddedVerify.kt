@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.netease.nativeui

import androidx.compose.ui.ExperimentalComposeUiApi

import androidx.compose.foundation.background
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.ui.controller.VerifyEmbedController
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
 * <p>这里只是一个占位 + 状态面板：真正的网页由 VerifyHost.exe（WebView2）渲染成宿主主窗口的
 * **原生子窗口**，按本 Box 的 boundsInWindow 精确覆盖。布局变化 → [NativeUiSession.updateEmbeddedVerify]
 * 只在边界换算一次物理像素；页面切走/关闭/停用时 [DisposableEffect] 会把网页区域隐藏，
 * 不会让它压住别的原生页面。</p>
 */
@Composable
internal fun EmbeddedVerifyArea(url: String, source: String, onRetry: () -> Unit) {
    val controller = NativeUiSession.verify()
    val st = NativeUiState.verify
    val awtWindow = LocalAwtWindow.current
    SideEffect { VerifyHostWindow.capture(awtWindow) }
    DisposableEffect(Unit) {
        onDispose { NativeUiSession.hideEmbeddedVerify() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltText(
                text = headline(st),
                style = HostStyle.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (st?.retryable() == true) {
                SaltSecondaryButton(onClick = { controller?.retry() }) { SaltText("重试") }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .border(1.dp, HostStyle.colors.stroke, RoundedCornerShape(6.dp))
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInWindow()
                    // The host dialog scrolls its content. Hide the native child
                    // when its placeholder is completely clipped by that viewport.
                    if (bounds.width < 1f || bounds.height < 1f) {
                        NativeUiSession.hideEmbeddedVerify()
                    } else {
                        NativeUiSession.updateEmbeddedVerify(
                            bounds.left, bounds.top, bounds.width, bounds.height, VerifyHostWindow.scale,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 网页区域就绪后会被原生子窗口盖住；这里只在启动中/失败/关闭时显示占位文案。
            val phase = st?.phase()
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
        if (st?.phase() == VerifyEmbedController.Phase.FAILED && !st.message().isBlank()) {
            SaltText(
                text = st.message(),
                style = HostStyle.typography.labelMedium,
                color = HostStyle.colors.error,
            )
        }
        SaltText(
            text = "验证页由本机 WebView2 在宿主主窗口内渲染，不会打开外部浏览器；" +
                "加载失败或验证组件异常时在本区域内重试。验证完成后点下方「我已完成，重试」。",
            style = HostStyle.typography.labelSmall,
        )
        if (url.isBlank()) {
            SaltText("（当前没有验证地址）", style = HostStyle.typography.labelSmall)
        }
        if (st == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaltSecondaryButton(onClick = onRetry) { SaltText("重新获取验证地址") }
            }
        }
    }
}

private fun headline(st: VerifyEmbedController.State?): String = when (st?.phase()) {
    VerifyEmbedController.Phase.STARTING -> "内嵌验证区域：启动中"
    VerifyEmbedController.Phase.READY -> "内嵌验证区域：网页已就绪"
    VerifyEmbedController.Phase.FAILED -> "内嵌验证区域：不可用"
    VerifyEmbedController.Phase.CLOSED -> "内嵌验证区域：已关闭"
    VerifyEmbedController.Phase.IDLE -> "内嵌验证区域：尚未启动"
    null -> "内嵌验证区域"
}
