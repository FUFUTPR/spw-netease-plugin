package com.example.netease.nativeui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.netease.ui.controller.LoginController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

/**
 * 登录页（docs/PLAN.md §8.1/§8.2）：宿主主窗口内呈现，不创建独立窗口。
 *
 * <p>0.12.18：本页只做「渲染快照 + 转发动作」，不再读 [NativeUiState] 里的任何字段。
 * [display] 由父层在页面仍是当前目标时每帧刷新、退场后冻结——所以 closeLogin() 把控制器改成
 * CLOSED 时，正在淡出的旧内容仍画「最后可交互帧」，不会闪出“登录流程已关闭”。
 * 所有动作都走 [LoginActions]（每个方法自带会话代次校验），退场内容即使被误触也发不出请求、
 * 开不了验证窗、关不掉新会话；登录成功后的 closeLogin/openPage 由 Shell 的常驻 effect 负责，
 * 本页不再持有任何会跨越退场执行业务的 effect。</p>
 */
@Composable
internal fun NativeLoginPage(display: LoginDisplay, actions: LoginActions, interactive: Boolean) {
    val st = display.state
    if (st == null) {
        SaltText("登录控制器尚未就绪（插件可能正在启动或已停用）", style = HostStyle.typography.bodyMedium)
        return
    }
    val initialFocus = remember { FocusRequester() }
    // 唯一的 effect：可交互后把焦点放进扫码按钮。登录流程的生命周期归导航（openLogin/closeLogin），
    // 不归组合；退场（interactive=false）时不再抢焦点，也不在这里关登录或切页。
    LaunchedEffect(interactive) { if (interactive) runCatching { initialFocus.requestFocus() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SegmentButton(st.mode() == LoginController.Mode.QR, "扫码登录", Modifier.focusRequester(initialFocus), interactive) {
                actions.switchTo(LoginController.Mode.QR)
            }
            SegmentButton(st.mode() == LoginController.Mode.SMS, "短信登录", interactive = interactive) {
                actions.switchTo(LoginController.Mode.SMS)
            }
            if (st.mode() == LoginController.Mode.RISK) {
                SaltText("行为验证", style = HostStyle.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            SaltText(
                text = if (st.busy()) "处理中…" else "",
                style = HostStyle.typography.labelSmall,
            )
        }
        val message = loginMessage(display, st)
        Crossfade(targetState = message, animationSpec = Motion.state(), label = "login-message") { text ->
            if (text.isNotBlank()) SaltText(
                text = text,
                style = HostStyle.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedContent(
            targetState = st.mode(),
            // 尺寸也限制在 120ms：Crossfade 默认的 SizeTransform 弹簧会让对话框高度在切档后
            // 继续移动几百毫秒，顶部分段按钮跟着上下走；用户在这段时间点击会在按下/抬起之间
            // 离开按钮而丢事件。这里只做 120ms 高度过渡，之后布局完全静止。
            transitionSpec = {
                ContentTransform(
                    fadeIn(Motion.state()),
                    fadeOut(Motion.state()),
                    0f,
                    SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.state() }),
                )
            },
            label = "login-mode",
        ) { mode ->
            // Crossfade 会在 120ms 内同时保留新旧两页；旧页若仍可点击，其顶部「扫码登录/短信登录」
            // 分段按钮会拦截新页同坐标的点击（旧 QR 页的扫码按钮回调是 switchTo(SMS)，表现为点了没反应）。
            // 只有与当前 mode 相同且整体可交互的那一页才能收事件；退场页吞掉指针并清空语义。
            val active = interactive && mode == st.mode()
            Box(
                Modifier.fillMaxWidth().then(
                    if (active) Modifier
                    else Modifier.blockAllPointerEvents().clearAndSetSemantics {},
                ),
                // SMS/RISK 页比 QR 窄（widthIn(max = 400.dp)），外层 AnimatedContent 的默认
                // TopStart 会把它们贴左边；显式居中，三段切换时表单始终在对话框正中。
                contentAlignment = Alignment.TopCenter,
            ) {
                when (mode) {
                    LoginController.Mode.QR -> QrPane(st, actions, active)
                    LoginController.Mode.SMS -> SmsPane(display, st, actions, active)
                    LoginController.Mode.RISK -> RiskPane(display, st, actions, active)
                }
            }
        }
        if (st.loggedIn()) {
            // 0.12.18：删掉原来的「查看账号」按钮——它直接改 NativeUiState.page，等于页面自己导航。
            // 登录成功的跳转统一由 NativeUiPage 的常驻 effect 做（closeLogin + openPage(ACCOUNT)）。
            if (display.syncProgress.isNotBlank()) SaltText(display.syncProgress, style = HostStyle.typography.bodyMedium)
            SaltText(
                text = "已登录" + (if (st.loginVia().isBlank()) "" else "（" + st.loginVia() + "）"),
                style = HostStyle.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 顶部分段按钮：退场快照里 disabled（父层遮罩之外的第二道保险），回调也不会触发业务动作。 */
@Composable
private fun SegmentButton(selected: Boolean, label: String, modifier: Modifier = Modifier, interactive: Boolean, onClick: () -> Unit) {
    val action = { if (interactive) onClick() }
    if (selected) {
        SaltButton(onClick = action, modifier = modifier, enabled = interactive) { SaltText(label) }
    } else {
        SaltSecondaryButton(onClick = action, modifier = modifier, enabled = interactive) { SaltText(label) }
    }
}

private fun loginMessage(display: LoginDisplay, st: LoginController.State): String =
    if (st.mode() == LoginController.Mode.RISK && !display.nativeVerifySupported) {
        "此次登录需要行为验证，请选择其他登录方式"
    } else if (st.phase() == LoginController.Phase.QR_READY) {
        phaseHint(st)
    } else {
        st.message().ifBlank { phaseHint(st) }
    }

private fun phaseHint(st: LoginController.State): String = when (st.phase()) {
    LoginController.Phase.WAITING_QR -> "正在获取二维码…"
    LoginController.Phase.QR_READY -> "使用网易云音乐APP扫码登录"
    LoginController.Phase.SCANNED -> "已扫码，请在手机上确认"
    LoginController.Phase.EXPIRED -> "二维码已过期，点「刷新二维码」"
    LoginController.Phase.SENDING -> "正在发送验证码…"
    LoginController.Phase.CODE_SENT -> "验证码已发送"
    LoginController.Phase.LOGGING_IN -> "正在登录…"
    LoginController.Phase.RISK -> "需要先完成行为验证"
    LoginController.Phase.LOGGED_IN -> "登录成功"
    LoginController.Phase.CLOSED -> "登录流程已关闭，可重新打开"
    LoginController.Phase.IDLE -> ""
    LoginController.Phase.ERROR -> "操作失败，可重试"
}

@Composable
private fun QrPane(st: LoginController.State, actions: LoginActions, interactive: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            // 解码仍在 IO 线程：produceState 的 block 跑在组合协程里，这里显式 withContext(IO) 不阻塞 UI。
            val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, st.qrKey()) {
                value = withContext(Dispatchers.IO) { decodeQr(st.qrPng()) }
            }
            val decoded = bitmap
            // 二维码刷新只换图，不让外层白框/按钮跟着跳：交叉淡入固定 200dp 的区域。
            Crossfade(targetState = decoded, animationSpec = Motion.state(), label = "login-qr") { shown ->
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    if (shown == null) {
                        SaltText(
                            text = if (st.busy()) "正在获取二维码…" else "暂无二维码，点下方「刷新二维码」",
                            style = HostStyle.typography.bodySmall,
                            color = Color.Black,
                        )
                    } else {
                        Image(
                            bitmap = shown,
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(200.dp),
                        )
                    }
                }
            }
        }
        SaltButton(onClick = { actions.refreshQr() }, modifier = Modifier.width(216.dp), enabled = interactive && !st.busy()) {
            SaltText("刷新二维码")
        }
    }
}

private fun decodeQr(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) {
        return null
    }
    return runCatching {
        ImageIO.read(ByteArrayInputStream(bytes)).toComposeImageBitmap()
    }.getOrNull()
}

@Composable
private fun SmsPane(display: LoginDisplay, st: LoginController.State, actions: LoginActions, interactive: Boolean) {
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SaltText("手机号")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 值只读 display.phone（退场冻结），写入走 actions.setPhone（代次不符直接丢弃）。
            SaltInput(display.phone,
                { if (interactive) actions.setPhone(it.filter { c -> c.isDigit() }.take(11)) },
                Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small), hint = "11 位手机号")
            SaltSecondaryButton(onClick = { actions.sendCode() },
                enabled = interactive && !st.busy() && st.cooldownLeft() == 0) {
                SaltText(if (st.cooldownLeft() > 0) "重新获取（${st.cooldownLeft()} 秒）" else "获取验证码")
            }
        }
        SaltText("验证码")
        SaltInput(display.code,
            { if (interactive) actions.setCode(it.filter { c -> c.isDigit() }.take(6)) },
            Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small), hint = "4-6 位数字")
        SaltButton(onClick = { actions.submitCode() }, modifier = Modifier.fillMaxWidth(), enabled = interactive && !st.busy()) {
            SaltText("登录")
        }
    }
}

@Composable
private fun RiskPane(display: LoginDisplay, st: LoginController.State, actions: LoginActions, interactive: Boolean) {
    if (!display.nativeVerifySupported) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltText("当前系统暂不支持内嵌行为验证", style = HostStyle.typography.bodyMedium)
            SaltText("网易云要求完成行为验证后才能继续此次登录。可以返回选择其他登录方式；若仍要求行为验证，该流程暂不可用。",
                style = HostStyle.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaltSecondaryButton(onClick = { actions.backToQr() }, enabled = interactive) { SaltText("返回扫码") }
                SaltSecondaryButton(onClick = { actions.backToSms() }, enabled = interactive) { SaltText("验证码登录") }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SaltText(
            text = "网易云要求先完成一次行为验证才能继续登录。验证网页在下方区域内渲染（宿主主窗口内的 WebView2），" +
                "完成后点「我已完成，重试」。不会跳到外部浏览器。",
            style = HostStyle.typography.bodyMedium,
        )
        // 验证区只做文本的交叉淡入淡出：原生子窗口不能跟着做位置/缩放动画，父层也把它排除在 scale 之外。
        EmbeddedVerifyArea(
            url = st.riskUrl(),
            source = if (st.riskFromQr()) "扫码" else "验证码登录",
            generation = display.generation,
            interactive = interactive,
            state = display.verify,
            onRetry = { actions.openVerify() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltButton(onClick = { actions.retryAfterVerify() }, enabled = interactive) { SaltText("我已完成，重试") }
            SaltSecondaryButton(onClick = { actions.backToQr() }, enabled = interactive) { SaltText("返回扫码") }
            if (st.verifyOpened()) {
                SaltText(
                    text = "验证区域已请求打开；若未显示，请看区域内的原因并重试。",
                    style = HostStyle.typography.labelSmall,
                )
            }
        }
    }
}
