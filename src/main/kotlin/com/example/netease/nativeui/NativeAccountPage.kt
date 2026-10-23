package com.example.netease.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.svc.AccountService
import com.example.netease.ui.AccountReport

/**
 * 账号页（docs/PLAN.md §8.3）：直接消费 AccountService.status() 的同一份快照，
 * 展示行复用 [AccountReport]（纯 Java 的文案唯一真源，旧 Swing 窗口已在 0.12.0 删除），
 * 保证「自动检查的文本」与「实际显示」不是两套算法；本页不新增任何 cookie / 登录状态 / 会员状态。
 */
@Composable
internal fun NativeAccountPage() {
    val st = NativeUiState.account
    if (st == null) {
        SaltText("正在读取账号状态…", style = HostStyle.typography.bodyMedium)
        return
    }
    var confirmLogout by remember { mutableStateOf(false) }
    val rows = remember(st) { AccountReport.render(st).split("\n") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { line ->
                    SaltText(
                        text = line,
                        style = if (line.startsWith("账号名字")) HostStyle.typography.titleMedium
                        else HostStyle.typography.bodyMedium,
                        fontWeight = if (line.startsWith("账号名字")) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltButton(
                onClick = {
                    NativeUiSession.refreshAccount()
                    NativeUiPresentation.openPage(NativePage.LOGIN)
                },
            ) {
                SaltText(if (st.state() == AccountService.State.LOGGED_IN) "切换账号" else "登录")
            }
            SaltSecondaryButton(onClick = { NativeUiSession.refreshAccount() }) { SaltText("刷新") }
            if (!confirmLogout) {
                SaltSecondaryButton(onClick = { confirmLogout = true }) { SaltText("退出登录") }
            } else {
                SaltButton(onClick = {
                    confirmLogout = false
                    NativeUiSession.logout()
                }) {
                    SaltText("确认退出登录")
                }
                SaltSecondaryButton(onClick = { confirmLogout = false }) { SaltText("取消") }
            }
            if (NativeUiState.accountNote.isNotBlank()) {
                SaltText(NativeUiState.accountNote, style = HostStyle.typography.labelMedium)
            }
        }
        SaltText("退出登录会清除本机保存的登录凭据。", style = HostStyle.typography.labelSmall)
    }
}
