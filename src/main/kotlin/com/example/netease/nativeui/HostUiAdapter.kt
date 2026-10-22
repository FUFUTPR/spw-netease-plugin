@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import com.example.netease.core.PluginLog
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall

/**
 * 集中式宿主 UI 适配层（docs/PLAN.md 批次 1）。
 *
 * <p>职责：把「宿主版本 + 目标类/方法/调用位置 + 匹配数量」集中在这里记录；
 * 混淆名只允许出现在本文件。只接受唯一匹配：缺类、缺方法、多匹配都不装 Hook，
 * 并把可定位原因写进插件日志（绝不碰宿主 logs.txt）。任何异常都在这里吃掉，
 * 不允许影响播放与插件启动。</p>
 */
internal object HostUiAdapter {
    private const val TAG = "native-ui"

    // --- 宿主 1.19.0（Java 25）实测指纹 ------------------------------------
    // androidx.compose.ui.ȫ 实为 com.xuncorp.voxzen.ui.screen.workshop.ModConfigScreenKt。
    // 挂载目标：公共 @Composable ModConfigScreen(route: ScreenRoute.ModConfigContent, viewModel)。
    // 选它的原因（静态核对：build/_tmp/cpscan.py 与 javap -c）：
    //   1) 内容区私有 composable Ϳ(BoxScope, List, String, PaddingValues) 在 ȫ 内有 2 个
    //      invokestatic（真实调用 + 供传递用的合成 lambda 包装器），Hook 引擎要求唯一调用点，
    //      实测报 "UI call missing or ambiguous ... (2 calls)"；
    //   2) 公共 ModConfigScreen 在整个宿主里只在
    //      com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt
    //      被调用一次（导航目标的内容 lambda），调用点唯一；
    //   3) afterComposable 只追加、不改写原调用，所以调用点上的默认参数 mask=2 不影响挂载。
    // 关闭页面只是让插件层不渲染，宿主配置内容始终由宿主自己绘制。
    private const val HOST_VERSION_VERIFIED = "1.19.0"
    private const val TARGET_CLASS = "androidx.compose.ui.ȫ"
    private const val TARGET_METHOD = "Ϳ"
    /** 调用点所在类：宿主导航的 ModConfigContent 目标内容 lambda。 */
    private const val TARGET_IN_CLASS =
        "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt"
    /** 业务参数（Kotlin 声明层面；Hook API 用这一组）。\u0936 = ModConfigViewModel 的混淆名。 */
    private val TARGET_PARAMETERS = listOf(
        "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent",
        "androidx.compose.ui.\u0936",
    )

    /** JVM 实际签名：Compose 编译器追加 Composer + int changed + int defaultMask。 */
    private val TARGET_JVM_PARAMETERS =
        TARGET_PARAMETERS + listOf("androidx.compose.runtime.Composer", "int", "int")
    private const val PRIORITY = 100

    @Volatile
    private var handle: HookHandle? = null

    /** 安装（幂等）。失败时只更新页面状态与日志，不抛异常。 */
    fun install(hostVersion: String, pluginVersion: String) {
        NativeUiRuntime.hostVersion = hostVersion.ifBlank { "unknown" }
        NativeUiRuntime.pluginVersion = pluginVersion.ifBlank { "unknown" }
        if (handle != null) {
            NativeUiState.status = "已挂载（重复注册已忽略）"
            PluginLog.i(TAG, "install 跳过：Hook 已存在")
            return
        }
        try {
            val clazz = try {
                Class.forName(TARGET_CLASS)
            } catch (t: Throwable) {
                fail(
                    "宿主 " + hostVersion + " 中找不到目标类 " + TARGET_CLASS +
                        "：宿主 UI 混淆名可能已变化，请在 1.19.0 上重新核对适配层"
                )
                return
            }
            val matches = clazz.declaredMethods.filter { m ->
                m.name == TARGET_METHOD &&
                    m.parameterCount == TARGET_JVM_PARAMETERS.size &&
                    m.parameterTypes.map { it.name } == TARGET_JVM_PARAMETERS
            }
            if (matches.isEmpty()) {
                fail(
                    "目标方法未找到：" + TARGET_CLASS + "#" + TARGET_METHOD +
                        TARGET_JVM_PARAMETERS + "（宿主 " + hostVersion + "）"
                )
                return
            }
            if (matches.size > 1) {
                fail(
                    "目标方法匹配到 " + matches.size + " 个（要求唯一）：" + TARGET_CLASS +
                        "#" + TARGET_METHOD + TARGET_JVM_PARAMETERS + "（宿主 " + hostVersion + "）"
                )
                return
            }
            if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
                fail(
                    "未获授 class-transform 权限（元数据已声明，需在工坊插件页授权后重启插件）"
                )
                return
            }
            val h = WorkshopApi.hookRegistrar.afterComposable(
                TARGET_CLASS,
                TARGET_METHOD,
                TARGET_IN_CLASS,
                TARGET_PARAMETERS,
                PRIORITY,
                PanelHook,
            )
            handle = h
            NativeUiSession.start()
            NativeUiState.visible = true
            NativeUiState.status =
                "已挂载（唯一匹配，宿主 " + hostVersion + "，priority=" + PRIORITY + "）"
            PluginLog.i(
                TAG,
                "install 成功：宿主=" + hostVersion + " 插件=" + pluginVersion +
                    " 目标=" + TARGET_CLASS + "#" + TARGET_METHOD + TARGET_JVM_PARAMETERS +
                    " inClass=" + TARGET_IN_CLASS +
                    " 匹配数=" + matches.size + "（适配基线 " + HOST_VERSION_VERIFIED + "）"
            )
        } catch (t: Throwable) {
            fail("注册 Hook 抛出异常：" + t)
        }
    }

    /** 卸载（幂等）：停用插件后不留页面。 */
    fun uninstall() {
        val h = handle
        if (h == null) {
            return
        }
        handle = null
        NativeUiSession.stop()
        try {
            h.unhook()
            NativeUiState.status = "已卸载"
            PluginLog.i(TAG, "uninstall 完成：Hook 已摘除")
        } catch (t: Throwable) {
            PluginLog.w(TAG, "uninstall 异常（已忽略）：" + t)
        }
    }

    private fun fail(reason: String) {
        NativeUiState.status = "未挂载：" + reason
        PluginLog.w(TAG, "install 失败：" + reason)
    }

    /** 宿主侧唯一注入点：替换配置页内容区的渲染。 */
    private object PanelHook : ComposableHook() {
        @Composable
        override fun Content(call: UiHookCall) {
            NativeUiPanel()
        }
    }
}
