package com.example.netease.nativeui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.svc.AccountService

/**
 * 账号页（docs/PLAN.md §8.3）：只渲染 [AccountDisplay] 这份快照，不读全局 NativeUiState。
 *
 * <p>展示行由 [captureAccountDisplay] 统一用 AccountReport 生成（与旧窗口同一文案真源）；
 * 退场时 active=false，页面继续画关闭前那一刻的行，不会因为全局账号状态变化而闪成另一页。
 * 本页不新增任何 cookie / 登录状态 / 会员状态。</p>
 */
@Composable
internal fun NativeAccountPage(display: AccountDisplay, interactive: Boolean) {
    val st = display.status
    if (st == null) {
        SaltText("正在读取账号状态…", style = HostStyle.typography.bodyMedium)
        return
    }
    var confirmLogout by remember { mutableStateOf(false) }
    // 账号状态快照（报告行 = 登录态的唯一真源）：未登录 ↔ 已登录之间切换只做 120ms 交叉淡入。
    val snapshot = remember(display.rows) { AccountSnapshot(display.rows) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (display.syncProgress.isNotBlank()) SaltText(display.syncProgress, style = HostStyle.typography.bodyMedium)
        // 报告行直接铺在宿主对话框的材质上，不再套一层 SaltGroup——对话框本身就是原生卡片，
        // 再嵌一层会出现双重底色/内边距。整块文本仍可框选复制，行内容与顺序完全照旧。
        AnimatedContent(
            targetState = snapshot,
            transitionSpec = { fadeIn(Motion.state()) togetherWith fadeOut(Motion.state()) },
            label = "account-state",
        ) { shown ->
            SelectionContainer {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.rows.forEach { line ->
                        SaltText(
                            text = line,
                            style = if (line.startsWith("账号名字")) HostStyle.typography.titleMedium
                            else HostStyle.typography.bodyMedium,
                            fontWeight = if (line.startsWith("账号名字")) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltButton(
                modifier = Modifier.focusReturnTarget(),
                enabled = interactive,
                onClick = {
                    if (interactive) {
                        NativeUiSession.refreshAccount()
                        NativeUiPresentation.openPage(NativePage.LOGIN)
                    }
                },
            ) {
                SaltText(if (st.state() == AccountService.State.LOGGED_IN) "切换账号" else "登录")
            }
            SaltSecondaryButton(
                modifier = Modifier.focusReturnTarget(),
                enabled = interactive,
                onClick = { if (interactive) NativeUiSession.refreshAccount() },
            ) {
                SaltText("刷新")
            }
            // 「退出登录」与「确认退出登录 / 取消」之间只做一次淡入淡出；按钮回调不变。
            AnimatedContent(
                targetState = confirmLogout,
                transitionSpec = { fadeIn(Motion.state()) togetherWith fadeOut(Motion.state()) },
                label = "account-logout-confirm",
            ) { confirming ->
                // 退场内容（旧的「退出登录」或旧的「确认/取消」）在 120ms 内仍叠在上面；
                // 若可点击，会拦住新内容同坐标的按钮。非当前 confirming 的内容一律吞事件、清语义。
                val active = interactive && confirming == confirmLogout
                Box(
                    Modifier.fillMaxWidth().then(
                        if (active) Modifier
                        else Modifier.blockAllPointerEvents().clearAndSetSemantics {},
                    ),
                ) {
                    if (confirming) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SaltButton(
                                modifier = Modifier.focusReturnTarget(),
                                enabled = active,
                                onClick = {
                                    if (active) {
                                        confirmLogout = false
                                        NativeUiSession.logout()
                                    }
                                },
                            ) {
                                SaltText("确认退出登录")
                            }
                            SaltSecondaryButton(enabled = active, onClick = { if (active) confirmLogout = false }) {
                                SaltText("取消")
                            }
                        }
                    } else {
                        SaltSecondaryButton(
                            modifier = Modifier.focusReturnTarget(),
                            enabled = active,
                            onClick = { if (active) confirmLogout = true },
                        ) {
                            SaltText("退出登录")
                        }
                    }
                }
            }
            if (display.note.isNotBlank()) {
                SaltText(display.note, style = HostStyle.typography.labelMedium)
            }
        }
        SaltText("退出登录会清除本机保存的登录凭据。", style = HostStyle.typography.labelSmall)
    }
}

/** 账号状态快照：只用于 AnimatedContent 的目标比较（报告行），不改任何文案。 */
@Immutable private data class AccountSnapshot(val rows: List<String>)
