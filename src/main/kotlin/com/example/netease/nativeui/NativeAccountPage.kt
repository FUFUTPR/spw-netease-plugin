package com.example.netease.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
        Text("正在读取账号状态…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    var confirmLogout by remember { mutableStateOf(false) }
    val rows = remember(st) { AccountReport.render(st).split("\n") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "账号信息来自 AccountService.status() 的同一份快照（状态 " + st.state().name +
                "，会话代 " + AccountService.sessionEpoch() + "），不另存登录态。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { line ->
                    Text(
                        text = line,
                        style = if (line.startsWith("账号名字")) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (line.startsWith("账号名字")) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (st.message().isNotBlank()) {
            Text("状态说明：" + st.message(), style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    NativeUiSession.refreshAccount()
                    NativeUiState.page = NativePage.LOGIN
                    NativeUiSession.openLogin()
                },
            ) {
                Text(if (st.state() == AccountService.State.LOGGED_IN) "切换账号" else "登录")
            }
            OutlinedButton(onClick = { NativeUiSession.refreshAccount() }) { Text("刷新账号快照") }
            if (!confirmLogout) {
                OutlinedButton(onClick = { confirmLogout = true }) { Text("退出登录") }
            } else {
                Button(onClick = {
                    confirmLogout = false
                    NativeUiSession.logout()
                }) {
                    Text("确认退出登录")
                }
                OutlinedButton(onClick = { confirmLogout = false }) { Text("取消") }
            }
            if (NativeUiState.accountNote.isNotBlank()) {
                Text(NativeUiState.accountNote, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = "说明：退出登录会清除本机保存的登录凭据并让账号会话失效；" +
                "关闭登录页或取消登录不会退出账号。账号变化会让旧的搜索/歌单结果与验证回调失效" +
                "（由控制器与 AccountService 的会话代共同保证）。",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
