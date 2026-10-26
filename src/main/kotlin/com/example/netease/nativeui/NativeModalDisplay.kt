package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.netease.ui.AccountReport
import com.example.netease.svc.AccountService
import com.example.netease.svc.SyncCoordinator
import com.example.netease.ui.controller.LoginController
import com.example.netease.ui.controller.VerifyEmbedController

/** 页面内容是否允许交互：退场页与页面切换动画期间一律 false，子页不要再读全局导航状态。 */
internal val LocalNativePageInteractive = staticCompositionLocalOf { true }

/**
 * 模态页的展示快照：退场内容必须继续画「关闭前那一刻」的数据，不能读已经变化的全局状态。
 *
 * <p>快照在一页仍是当前目标时每次重组刷新（引用不可变 record，不拷贝二维码字节）；
 * 一旦该页变成退场内容（active=false），只返回最后一次快照，之后 NativeUiState 怎么变都不再影响它。</p>
 */
@Immutable
internal data class LoginDisplay(
    val generation: Long,
    val state: LoginController.State?,
    val phone: String,
    val code: String,
    val syncProgress: String,
    val nativeVerifySupported: Boolean,
    val verify: VerifyEmbedController.State?,
)

@Immutable
internal data class AccountDisplay(
    val status: AccountService.LoginStatus?,
    val rows: List<String>,
    val note: String,
    val syncProgress: String,
)

@Immutable
internal data class SyncDisplay(
    val status: SyncCoordinator.Status?,
    val requestNote: String,
)

internal fun captureLoginDisplay(): LoginDisplay = LoginDisplay(
    generation = NativeUiSession.loginGeneration(),
    state = NativeUiState.login,
    phone = NativeUiState.loginPhone,
    code = NativeUiState.loginCode,
    syncProgress = syncProgressMessage(NativeUiState.sync),
    nativeVerifySupported = VerifyEmbedController.nativeSupported(),
    verify = NativeUiState.verify,
)

@Composable
internal fun rememberLoginDisplay(active: Boolean): LoginDisplay {
    val holder = remember { arrayOfNulls<LoginDisplay>(1) }
    if (active) holder[0] = captureLoginDisplay()
    return holder[0] ?: captureLoginDisplay()
}

internal fun captureAccountDisplay(): AccountDisplay {
    val status = NativeUiState.account
    return AccountDisplay(
        status = status,
        rows = status?.let { runCatching { AccountReport.render(it) }.getOrNull() }?.split("\n") ?: emptyList(),
        note = NativeUiState.accountNote,
        syncProgress = syncProgressMessage(NativeUiState.sync),
    )
}

@Composable
internal fun rememberAccountDisplay(active: Boolean): AccountDisplay {
    val holder = remember { arrayOfNulls<AccountDisplay>(1) }
    if (active) holder[0] = captureAccountDisplay()
    return holder[0] ?: captureAccountDisplay()
}

internal fun captureSyncDisplay(): SyncDisplay = SyncDisplay(
    status = NativeUiState.sync,
    requestNote = NativeUiState.syncRequestNote,
)

@Composable
internal fun rememberSyncDisplay(active: Boolean): SyncDisplay {
    val holder = remember { arrayOfNulls<SyncDisplay>(1) }
    if (active) holder[0] = captureSyncDisplay()
    return holder[0] ?: captureSyncDisplay()
}

/**
 * 登录动作的唯一入口：每个动作都校验「打开这一页时的代次」是否仍是当前代次。
 * 退场页即使被误触/迟到回调，也不会去开关新一代登录窗或验证窗。
 */
internal class LoginActions(private val generation: Long) {
    private fun controller(): LoginController? =
        if (NativeUiSession.loginGeneration() == generation) NativeUiSession.login() else null

    fun switchTo(mode: LoginController.Mode) { controller()?.switchTo(mode) }
    fun refreshQr() { controller()?.refreshQr() }
    fun setPhone(value: String) {
        if (NativeUiSession.loginGeneration() != generation) return
        if (NativeUiState.loginPhone != value) NativeUiState.loginPhone = value
    }

    fun setCode(value: String) {
        if (NativeUiSession.loginGeneration() != generation) return
        if (NativeUiState.loginCode != value) NativeUiState.loginCode = value
    }

    fun sendCode() {
        if (NativeUiSession.loginGeneration() != generation) return
        controller()?.sendCode(NativeUiState.loginPhone)
    }

    fun submitCode() {
        if (NativeUiSession.loginGeneration() != generation) return
        controller()?.submitCode(NativeUiState.loginPhone, NativeUiState.loginCode)
    }
    fun openVerify() { controller()?.openVerify() }
    fun retryAfterVerify() { controller()?.retryAfterVerify() }
    fun backToQr() = switchTo(LoginController.Mode.QR)
    fun backToSms() = switchTo(LoginController.Mode.SMS)
}