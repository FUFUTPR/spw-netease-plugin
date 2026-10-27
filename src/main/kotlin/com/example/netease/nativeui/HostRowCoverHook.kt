@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package com.example.netease.nativeui

import com.example.netease.core.PluginLog
import com.example.netease.svc.RowCoverSource
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*

internal object HostRowCoverHook {
    private var handle: HookHandle? = null
    var failureMessage: String? = null
        private set
    fun install() {
        if (handle != null) return
        failureMessage = null
        try {
            val type = RowCoverSource.validate()
            handle = WorkshopApi.hookRegistrar.hookMethod(type.name, "readFromDiskCache", emptyList(),
                object : MethodHook(100) {
                    override fun after(param: MethodHookParam) {
                        val fetcher = param.thisObject ?: return
                        if (param.throwable != null) return
                        try {
                            val previous = param.result
                            val result = RowCoverSource.refill(fetcher, previous)
                            if (result !== previous) param.setResult(result)
                        } catch (error: Throwable) {
                            PluginLog.d("rowcover", "按需封面读取失败，交回宿主：$error")
                        }
                    }
                })
            RowCoverSource.setActive(true)
            PluginLog.i("rowcover", "已启用列表封面按需补图：复用本地原图，遵循宿主缓存淘汰")
        } catch (error: Throwable) {
            PluginLog.w("rowcover", "按需封面适配失败，保留 live 缓存修复：$error")
            failureMessage = "列表封面按需适配失败，请查看插件日志"
            NativeUiState.status = failureMessage!!
        }
    }
    fun uninstall() {
        RowCoverSource.setActive(false)
        runCatching { handle?.unhook() }.onFailure { PluginLog.w("rowcover", "移除按需封面钩子失败：$it") }
        handle = null
    }
}
