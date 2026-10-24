package com.example.netease.nativeui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.ui.controller.LoginController
import com.example.netease.ui.controller.VerifyEmbedController
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

/**
 * 登录页（docs/PLAN.md §8.1/§8.2）：宿主主窗口内呈现，不创建独立窗口。
 *
 * <p>所有登录动作都转给 [LoginController]（唯一登录流程）；账号事实仍只在 AccountService，
 * 本页只渲染控制器 State，不保存 cookie / 登录状态 / 会员状态。二维码图片按 key 解码并缓存，
 * 刷新或过期后 key 变化才重新解码。</p>
 */
@Composable
internal fun NativeLoginPage() {
    val controller = NativeUiSession.login()
    val st = NativeUiState.login
    if (controller == null || st == null) {
        SaltText("登录控制器尚未就绪（插件可能正在启动或已停用）", style = HostStyle.typography.bodyMedium)
        return
    }
    val initialFocus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { NativeUiSession.closeLogin() } }
    LaunchedEffect(Unit) {
        // 进入页面才打开流程；open() 是幂等的，不会重复创建二维码轮询。
        controller.open()
        runCatching { initialFocus.requestFocus() }
    }
    LaunchedEffect(st.phase(), st.loginVia()) {
        if (st.phase() == LoginController.Phase.LOGGED_IN) {
            NativeUiSession.refreshAccount()
            NativeUiState.page = NativePage.ACCOUNT
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SegmentButton(st.mode() == LoginController.Mode.QR, "扫码登录", Modifier.focusRequester(initialFocus)) { controller.switchTo(LoginController.Mode.QR) }
            SegmentButton(st.mode() == LoginController.Mode.SMS, "短信登录") { controller.switchTo(LoginController.Mode.SMS) }
            if (st.mode() == LoginController.Mode.RISK) {
                SaltText("行为验证", style = HostStyle.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            SaltText(
                text = if (st.busy()) "处理中…" else "",
                style = HostStyle.typography.labelSmall,
            )
        }
        val message = if (st.mode() == LoginController.Mode.RISK && !VerifyEmbedController.nativeSupported())
            "此次登录需要行为验证，请选择其他登录方式"
            else if (st.phase() == LoginController.Phase.QR_READY) phaseHint(st)
            else st.message().ifBlank { phaseHint(st) }
        if (message.isNotBlank()) SaltText(
            text = message,
            style = HostStyle.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        when (st.mode()) {
            LoginController.Mode.QR -> QrPane(controller, st)
            LoginController.Mode.SMS -> SmsPane(controller, st)
            LoginController.Mode.RISK -> RiskPane(controller, st)
        }
        if (st.loggedIn()) {
            val progress = syncProgressMessage(NativeUiState.sync)
            if (progress.isNotBlank()) SaltText(progress, style = HostStyle.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SaltText(
                    text = "已登录" + (if (st.loginVia().isBlank()) "" else "（" + st.loginVia() + "）"),
                    style = HostStyle.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SaltButton(onClick = { NativeUiState.page = NativePage.ACCOUNT }) { SaltText("查看账号") }
            }
        }

    }
}

@Composable
private fun SegmentButton(selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        SaltButton(onClick = onClick, modifier = modifier) { SaltText(label) }
    } else {
        SaltSecondaryButton(onClick = onClick, modifier = modifier) { SaltText(label) }
    }
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
private fun QrPane(controller: LoginController, st: LoginController.State) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            val bitmap: ImageBitmap? = remember(st.qrKey()) { decodeQr(st.qrPng()) }
            if (bitmap == null) {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    SaltText(
                        text = if (st.busy()) "正在获取二维码…" else "暂无二维码，点下方「刷新二维码」",
                        style = HostStyle.typography.bodySmall,
                        color = Color.Black,
                    )
                }
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(200.dp),
                )
            }
        }
        SaltButton(onClick = { controller.refreshQr() }, modifier = Modifier.width(216.dp), enabled = !st.busy()) {
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
private fun SmsPane(controller: LoginController, st: LoginController.State) {
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SaltText("手机号")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltInput(NativeUiState.loginPhone,
                { NativeUiState.loginPhone = it.filter { c -> c.isDigit() }.take(11) },
                Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small), hint = "11 位手机号")
            SaltSecondaryButton(onClick = { controller.sendCode(NativeUiState.loginPhone) },
                enabled = !st.busy() && st.cooldownLeft() == 0) {
                SaltText(if (st.cooldownLeft() > 0) "重新获取（${st.cooldownLeft()} 秒）" else "获取验证码")
            }
        }
        SaltText("验证码")
        SaltInput(NativeUiState.loginCode,
            { NativeUiState.loginCode = it.filter { c -> c.isDigit() }.take(6) },
            Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small), hint = "4-6 位数字")
        SaltButton(onClick = { controller.submitCode(NativeUiState.loginPhone, NativeUiState.loginCode) }, modifier = Modifier.fillMaxWidth(), enabled = !st.busy()) {
            SaltText("登录")
        }
    }
}

@Composable
private fun RiskPane(controller: LoginController, st: LoginController.State) {
    if (!VerifyEmbedController.nativeSupported()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltText("当前系统暂不支持内嵌行为验证", style = HostStyle.typography.bodyMedium)
            SaltText("网易云要求完成行为验证后才能继续此次登录。可以返回选择其他登录方式；若仍要求行为验证，该流程暂不可用。",
                style = HostStyle.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaltSecondaryButton(onClick = { controller.switchTo(LoginController.Mode.QR) }) { SaltText("返回扫码") }
                SaltSecondaryButton(onClick = { controller.switchTo(LoginController.Mode.SMS) }) { SaltText("验证码登录") }
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
        EmbeddedVerifyArea(
            url = st.riskUrl(),
            source = if (st.riskFromQr()) "扫码" else "验证码登录",
        ) { controller.openVerify() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltButton(onClick = { controller.retryAfterVerify() }) { SaltText("我已完成，重试") }
            SaltSecondaryButton(onClick = { controller.switchTo(LoginController.Mode.QR) }) { SaltText("返回扫码") }
            if (st.verifyOpened()) {
                SaltText(
                    text = "验证区域已请求打开；若未显示，请看区域内的原因并重试。",
                    style = HostStyle.typography.labelSmall,
                )
            }
        }
    }
}
