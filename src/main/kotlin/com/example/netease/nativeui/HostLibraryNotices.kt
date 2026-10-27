@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package com.example.netease.nativeui

import com.example.netease.NeteasePlugin
import com.example.netease.core.PluginLog
import com.example.netease.host.HostLibraryNoticeAbi
import com.example.netease.host.LibraryRefreshNoticePolicy
import com.example.netease.svc.SyncCoordinator
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.coroutines.*

/** Preserve native library updates; admit their completion toast only for an explicit user command. */
internal object HostLibraryNotices {
    private val handles = mutableListOf<HookHandle>()
    private val policy = LibraryRefreshNoticePolicy()
    private var scope: CoroutineScope? = null
    @Volatile var progressVisible: Boolean = false
    var failureMessage: String? = null
        private set
    private fun now() = System.nanoTime() / 1_000_000L

    fun install() {
        if (handles.isNotEmpty()) return
        failureMessage = null
        try {
            val toast = HostLibraryNoticeAbi.toastMethod()
            val refresh = HostLibraryNoticeAbi.refreshMethod()
            handles += WorkshopApi.hookRegistrar.hookMethod(toast.declaringClass.name, toast.name,
                listOf("java.lang.String"), object : MethodHook(100) {
                    override fun before(param: MethodHookParam) {
                        if (!HostLibraryNoticeAbi.libraryToastCaller()) return
                        policy.updated(param.args.firstOrNull() as? String ?: return, now())
                        param.setResult(null)
                    }
                })
            handles += WorkshopApi.hookRegistrar.hookMethod(refresh.declaringClass.name, refresh.name,
                emptyList(), object : MethodHook(100) {
                    override fun before(param: MethodHookParam) {
                        if (HostLibraryNoticeAbi.manualCaller()) policy.request(now())
                    }
                })
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { owner ->
                owner.launch {
                    while (isActive) {
                        delay(200)
                        try {
                            val phase = NeteasePlugin.syncStatusSnapshot().phase()
                            val syncing = phase == SyncCoordinator.Phase.WAITING ||
                                phase == SyncCoordinator.Phase.FETCHING || phase == SyncCoordinator.Phase.WRITING
                            val message = policy.poll(now(), HostLibraryNoticeAbi.scanning(), syncing || progressVisible)
                            if (message != null && isActive) toast.invoke(null, message)
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            PluginLog.w("library-notice", "刷新提示状态读取失败：$error")
                            policy.reset()
                        }
                    }
                }
            }
            PluginLog.i("library-notice", "已启用：自动更新静默；手动刷新完成提示等待扫描和同步结束")
        } catch (error: Throwable) {
            uninstall()
            PluginLog.w("library-notice", "宿主刷新提示适配失败：$error")
            failureMessage = "刷新提示适配失败，请查看插件日志"
            NativeUiState.status = failureMessage!!
        }
    }

    fun uninstall() {
        scope?.cancel(); scope = null
        progressVisible = false
        policy.reset()
        HostLibraryNoticeAbi.clear()
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
    }
}
