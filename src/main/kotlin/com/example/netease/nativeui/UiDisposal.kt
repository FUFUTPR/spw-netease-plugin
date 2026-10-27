package com.example.netease.nativeui

import androidx.compose.runtime.DisposableEffectResult
import com.example.netease.core.PluginLog

/** Construct while the plugin is alive; cleanup failures must never cancel the host frame loop. */
internal class UiCleanup(private val action: () -> Unit) : DisposableEffectResult {
    override fun dispose() {
        try { action() }
        catch (error: Throwable) {
            // Logging is best effort too: the old module may already have lost its backing directory.
            try { PluginLog.w("native-ui", "旧界面清理失败（已隔离，宿主渲染继续）：$error") }
            catch (_: Throwable) { }
        }
    }
}

/** Unlike inline onDispose, the owning result and its exception boundary exist before unload. */
internal fun guardedCleanup(action: () -> Unit): DisposableEffectResult = UiCleanup(action)
