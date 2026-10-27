@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package com.example.netease.nativeui

import com.example.netease.core.PluginLog
import com.example.netease.host.HostSystemMediaCover
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*

internal object HostSystemMediaCoverHook {
    private var handle: HookHandle? = null
    var failureMessage: String? = null
        private set
    fun install() {
        if (!HostSystemMediaCover.supported() || handle != null) return
        failureMessage = null
        try {
            val target = HostSystemMediaCover.validate()
            handle = WorkshopApi.hookRegistrar.hookMethod(target.name, "setMediaProperties",
                listOf(HostSystemMediaCover.propertiesParameter().name), object : MethodHook(100) {
                    override fun after(param: MethodHookParam) {
                        try {
                            if (param.throwable == null) HostSystemMediaCover.onMetadata(param.thisObject, param.args[0])
                        } catch (error: Throwable) {
                            PluginLog.d("system-cover", "系统封面适配失败，交回宿主：$error")
                        }
                    }
                })
            HostSystemMediaCover.setActive(true)
            PluginLog.i("system-cover", "已启用 Windows 系统媒体卡片封面：复用本地原图与宿主媒体会话")
        } catch (error: Throwable) {
            failureMessage = "Windows 系统媒体封面适配失败，请查看插件日志"
            NativeUiState.status = failureMessage!!
            PluginLog.w("system-cover", "$failureMessage：$error")
        }
    }
    fun uninstall() {
        HostSystemMediaCover.setActive(false)
        runCatching { handle?.unhook() }.onFailure { PluginLog.w("system-cover", "移除系统封面钩子失败：$it") }
        handle = null
    }
}
