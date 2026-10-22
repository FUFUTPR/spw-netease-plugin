package com.example.netease.nativeui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.ui.controller.LoginController
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
        Text("登录控制器尚未就绪（插件可能正在启动或已停用）", style = MaterialTheme.typography.bodyMedium)
        return
    }
    LaunchedEffect(Unit) {
        // 进入页面才打开流程；open() 是幂等的，不会重复创建二维码轮询。
        controller.open()
    }
    LaunchedEffect(st.phase(), st.loginVia()) {
        if (st.phase() == LoginController.Phase.LOGGED_IN) {
            NativeUiSession.refreshAccount()
            NativeUiState.page = NativePage.ACCOUNT
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentButton(st.mode() == LoginController.Mode.QR, "扫码登录") { controller.switchTo(LoginController.Mode.QR) }
            SegmentButton(st.mode() == LoginController.Mode.SMS, "短信登录") { controller.switchTo(LoginController.Mode.SMS) }
            if (st.mode() == LoginController.Mode.RISK) {
                Text("行为验证", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = if (st.busy()) "处理中…" else "同一时间只有一个登录流程",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = if (st.message().isBlank()) phaseHint(st) else st.message(),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (st.mode()) {
            LoginController.Mode.QR -> QrPane(controller, st)
            LoginController.Mode.SMS -> SmsPane(controller, st)
            LoginController.Mode.RISK -> RiskPane(controller, st)
        }
        if (st.loggedIn()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已登录" + (if (st.loginVia().isBlank()) "" else "（" + st.loginVia() + "）"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = { NativeUiState.page = NativePage.ACCOUNT }) { Text("查看账号") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    // 取消登录 = 关闭本次流程并走 AccountService 现有恢复语义，不等于退出账号。
                    NativeUiSession.closeLogin()
                    NativeUiState.page = NativePage.HOME
                },
            ) {
                Text("取消登录")
            }
            Text(
                text = "取消不会退出账号；已登录状态下重开再取消不会丢失原账号。",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SegmentButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun phaseHint(st: LoginController.State): String = when (st.phase()) {
    LoginController.Phase.WAITING_QR -> "正在获取二维码…"
    LoginController.Phase.QR_READY -> "用网易云音乐 App 扫一下这个码"
    LoginController.Phase.SCANNED -> "已扫码，请在手机上确认"
    LoginController.Phase.EXPIRED -> "二维码已过期，点「刷新二维码」"
    LoginController.Phase.SENDING -> "正在发送验证码…"
    LoginController.Phase.CODE_SENT -> "验证码已发送"
    LoginController.Phase.LOGGING_IN -> "正在登录…"
    LoginController.Phase.RISK -> "需要先完成行为验证"
    LoginController.Phase.LOGGED_IN -> "登录成功"
    LoginController.Phase.CLOSED -> "登录流程已关闭，可重新打开"
    LoginController.Phase.IDLE -> "选择登录方式后开始"
    LoginController.Phase.ERROR -> "操作失败，可重试"
}

@Composable
private fun QrPane(controller: LoginController, st: LoginController.State) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            val bitmap: ImageBitmap? = remember(st.qrKey()) { decodeQr(st.qrPng()) }
            if (bitmap == null) {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (st.busy()) "正在获取二维码…" else "暂无二维码，点右侧「刷新二维码」",
                        style = MaterialTheme.typography.bodySmall,
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
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (st.qrExpired()) "二维码已过期，需要刷新后重新扫码。" else "打开网易云音乐 App → 扫一扫，扫描左侧二维码。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "有效期内重复进入本页或来回切换登录方式会复用同一张码，不会重复请求；" +
                    "二维码过期后才需要刷新。",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.refreshQr() }, enabled = !st.busy()) {
                    Text("刷新二维码")
                }
                if (st.qrExpired()) {
                    OutlinedButton(onClick = { controller.refreshQr() }, enabled = !st.busy()) {
                        Text("重新获取")
                    }
                }
            }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = NativeUiState.loginPhone,
                onValueChange = { NativeUiState.loginPhone = it.filter { c -> c.isDigit() }.take(11) },
                singleLine = true,
                label = { Text("手机号") },
                placeholder = { Text("11 位手机号") },
                modifier = Modifier.width(240.dp),
            )
            OutlinedButton(
                onClick = { controller.sendCode(NativeUiState.loginPhone) },
                enabled = !st.busy() && st.cooldownLeft() == 0,
            ) {
                Text(if (st.cooldownLeft() > 0) "重新获取（" + st.cooldownLeft() + " 秒）" else "获取验证码")
            }
            if (st.cooldownLeft() > 0) {
                Text("已发送，稍后可重新获取", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = NativeUiState.loginCode,
                onValueChange = { NativeUiState.loginCode = it.filter { c -> c.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("验证码") },
                placeholder = { Text("4-6 位数字") },
                modifier = Modifier.width(240.dp),
            )
            Button(
                onClick = { controller.submitCode(NativeUiState.loginPhone, NativeUiState.loginCode) },
                enabled = !st.busy(),
            ) {
                Text("登录")
            }
            Text(
                text = "验证码只用于本次登录，插件不保存密码；本页不提供密码登录。",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RiskPane(controller: LoginController, st: LoginController.State) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "网易云要求先完成一次行为验证才能继续登录。验证网页在下方区域内渲染（宿主主窗口内的 WebView2），" +
                "完成后点「我已完成，重试」。不会跳到外部浏览器。",
            style = MaterialTheme.typography.bodyMedium,
        )
        EmbeddedVerifyArea(
            url = st.riskUrl(),
            source = if (st.riskFromQr()) "扫码" else "验证码登录",
        ) { controller.openVerify() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.retryAfterVerify() }) { Text("我已完成，重试") }
            OutlinedButton(onClick = { controller.switchTo(LoginController.Mode.QR) }) { Text("返回扫码") }
            if (st.verifyOpened()) {
                Text(
                    text = "验证区域已请求打开；若未显示，请看区域内的原因并重试。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
