@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import com.example.netease.core.PluginLog
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Method

/**
 * 挂载范围判定（REVIEW issue2）。
 *
 * <p>宿主的 ModConfigScreen 是所有工坊插件共用的配置页容器：Hook 注入的
 * [ComposableHook.Content] 只在“当前打开的配置页属于本插件”时才允许绘制原生界面。
 * 当前配置页由 Hook 调用的第 0 个业务参数（宿主 ScreenRoute.ModConfigContent）给出，
 * 插件 ID 必须精确等于 [PLUGIN_ID]；其它插件、路由缺失、类型不认识或反射失败一律
 * 返回 false（不建入口、不消费指针事件）。</p>
 *
 * <p>整个对象只用反射，不依赖 Compose，可直接在 JVM 探针里验证。</p>
 */
internal object HostUiMountScope {
    /** 本插件在宿主工坊里的插件 ID（与插件元数据一致）。 */
    const val PLUGIN_ID = "com.example.netease"

    /** 宿主配置路由类名（宿主 1.19.0 混淆前的真实类名）。 */
    const val MOD_CONFIG_ROUTE_CLASS =
        "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent"

    private const val PLUGIN_ID_GETTER = "getPluginId"

    /** 取本次 Hook 调用的第 0 个业务参数（即当前屏幕路由）；异常时返回 null。 */
    @JvmStatic
    fun routeOf(call: UiHookCall?): Any? = try {
        call?.arguments?.firstOrNull()
    } catch (t: Throwable) {
        null
    }

    /** 读取路由里的插件 ID；不是宿主 ModConfigContent、字段缺失或空串都返回 null。 */
    @JvmStatic
    fun pluginIdOf(route: Any?): String? {
        if (route == null || route.javaClass.name != MOD_CONFIG_ROUTE_CLASS) {
            return null
        }
        return try {
            (route.javaClass.getMethod(PLUGIN_ID_GETTER).invoke(route) as? String)
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }

    /** 只有本插件自己的配置路由才允许挂载。 */
    @JvmStatic
    fun shouldMountRoute(route: Any?): Boolean = pluginIdOf(route) == PLUGIN_ID

    /** 只有本插件自己的 Hook 调用才允许挂载。 */
    @JvmStatic
    fun shouldMount(call: UiHookCall?): Boolean = shouldMountRoute(routeOf(call))

    /** 诊断描述：只用于日志与开发者页，不参与绘制。 */
    @JvmStatic
    fun describe(route: Any?): String = when {
        route == null -> "路由缺失"
        route.javaClass.name != MOD_CONFIG_ROUTE_CLASS ->
            "非 ModConfigContent 路由：" + route.javaClass.name
        else -> "pluginId=" + (pluginIdOf(route) ?: "<不可读或空>")
    }
}

/**
 * 宿主 SaltTheme（com.moriafly.salt）桥接（REVIEW issue6）。
 *
 * <p>已核实（Python 扫描宿主安装 jar + javap -c）：宿主 1.19.0 的全部
 * com.xuncorp.* 界面都没有引用 androidx.compose.material3.MaterialTheme，颜色/字体/
 * 圆角全部来自宿主自己的 CompositionLocal（混淆类 androidx.compose.ui.ʶ，Kotlin 源即
 * SaltTheme），深浅色由宿主 AppConfig/LightDarkTheme 决定。Hook 注入点位于宿主 AppTheme
 * 之内，因此可以在组合期用 Composer.consume 读到宿主真实提供的 SaltConfigs /
 * SaltDynamicColors / SaltTextStyles / SaltShapes。</p>
 *
 * <p>混淆名只在宿主 1.19.0 上核对过。安装 Hook 前核对 ABI；组合期读取失败则停止绘制
 * 并记录原因，不切换到另一套组件或默认主题。</p>
 */
internal object HostSaltTheme {
    private const val TAG = "native-ui"

    // --- 宿主 1.19.0 混淆名（javap -p 核实） --------------------------------
    /** SaltTheme 的 CompositionLocal 持有者。 */
    private const val THEME_CLASS = "androidx.compose.ui.ʶ"
    /** SaltColors（11 个 long 颜色）。 */
    private const val COLORS_CLASS = "androidx.compose.ui.ʹ"
    /** SaltDynamicColors（light/dark 两套 SaltColors）。 */
    private const val DYNAMIC_COLORS_CLASS = "androidx.compose.ui.ʴ"
    /** SaltConfigs（isDarkTheme 等）。 */
    private const val CONFIGS_CLASS = "androidx.compose.ui.ˤ"
    /** SaltTextStyles（main/sub/paragraph/largeTitle）。 */
    private const val TEXT_STYLES_CLASS = "androidx.compose.ui.ʲ"
    /** SaltShapes（small/medium/large）。 */
    private const val SHAPES_CLASS = "androidx.compose.ui.ˮ"

    /** 主题持有者上 4 个目标 local 的静态 getter：configs、dynamicColors、textStyles、shapes。 */
    private const val LOCALS_CONFIGS = "Ϳ"
    private const val LOCALS_DYNAMIC_COLORS = "Ԩ"
    private const val LOCALS_TEXT_STYLES = "ԩ"
    private const val LOCALS_SHAPES = "ԫ"

    private const val CONFIGS_IS_DARK = "Ϳ"
    private const val DYNAMIC_LIGHT = "Ϳ"
    private const val DYNAMIC_DARK = "Ԩ"

    /** SaltColors：highlight,text,subText,background,subBackground,popup,stroke,onHighlight,success,warning,error。 */
    private val COLOR_GETTERS = listOf(
        "Ϳ", "Ԩ", "ԩ", "Ԫ", "ԫ", "Ԭ",
        "ԭ", "Ԯ", "ԯ", "ՠ", "ֈ",
    )

    /** SaltTextStyles：main,sub,paragraph,largeTitle。 */
    private val TEXT_STYLE_GETTERS = listOf("Ϳ", "Ԩ", "ԩ", "Ԫ")

    /** SaltShapes：small,medium,large。 */
    private val SHAPE_GETTERS = listOf("Ϳ", "Ԩ", "ԩ")

    /** SaltTheme 下的实际配色（来自宿主当前 dark/light 的一套 SaltColors）。 */
    internal class Palette(
        val highlight: Color,
        val text: Color,
        val subText: Color,
        val background: Color,
        val subBackground: Color,
        val popup: Color,
        val stroke: Color,
        val onHighlight: Color,
        val success: Color,
        val warning: Color,
        val error: Color,
    )

    /** SaltTextStyles 的四个字体样式（已包含宿主自定义 FontFamily）。 */
    internal class TypeSet(
        val main: TextStyle,
        val sub: TextStyle,
        val paragraph: TextStyle,
        val largeTitle: TextStyle,
    )

    /** SaltShapes 的三个圆角形状。 */
    internal class ShapeSet(
        val small: Shape,
        val medium: Shape,
        val large: Shape,
    )

    /** 一次成功读取的宿主主题快照。 */
    internal class Snapshot(
        val dark: Boolean,
        val palette: Palette,
        val textStyles: TypeSet,
        val shapes: ShapeSet,
    )

    /**
     * 宿主 SaltColors getter 返回的是 Compose Color 的 ULong 打包位模式：sRGB 的 AARRGGBB
     * 位于高 32 位，低 32 位是颜色空间标记。必须走 ULong 构造入口原样保留位模式；
     * `Color(Long)` 是 ARGB 工厂（字节码 `lshl 32`），会把已编码的位模式再左移一次而变成 0。
     *
     * <p>REVIEW-2 P1：此前误用 ARGB 工厂，11 个主题色全部透明。数值回归见
     * UiMountScopeProbe 的宿主主题颜色位模式检查。</p>
     */
    internal fun colorFromPacked(packed: Long): Color = Color(packed.toULong())

    private class Reader(
        val configs: CompositionLocal<Any?>,
        val dynamicColors: CompositionLocal<Any?>,
        val textStyles: CompositionLocal<Any?>,
        val shapes: CompositionLocal<Any?>,
        val isDark: Method,
        val lightColors: Method,
        val darkColors: Method,
        val colorGetters: List<Method>,
        val textStyleGetters: List<Method>,
        val shapeGetters: List<Method>,
    ) {
        fun read(composer: Composer): Snapshot? {
            val configsValue = composer.consume(configs) ?: return null
            val dynamicValue = composer.consume(dynamicColors) ?: return null
            val stylesValue = composer.consume(textStyles) ?: return null
            val shapesValue = composer.consume(shapes) ?: return null
            val dark = isDark.invoke(configsValue) as? Boolean ?: return null
            val saltColors = (if (dark) darkColors else lightColors).invoke(dynamicValue) ?: return null
            val colors = colorGetters.map {
                val value = it.invoke(saltColors) as? Long ?: return null
                colorFromPacked(value)
            }
            val styles = textStyleGetters.map {
                it.invoke(stylesValue) as? TextStyle ?: return null
            }
            val shapeValues = shapeGetters.map {
                it.invoke(shapesValue) as? Shape ?: return null
            }
            return Snapshot(
                dark = dark,
                palette = Palette(
                    highlight = colors[0],
                    text = colors[1],
                    subText = colors[2],
                    background = colors[3],
                    subBackground = colors[4],
                    popup = colors[5],
                    stroke = colors[6],
                    onHighlight = colors[7],
                    success = colors[8],
                    warning = colors[9],
                    error = colors[10],
                ),
                textStyles = TypeSet(
                    main = styles[0],
                    sub = styles[1],
                    paragraph = styles[2],
                    largeTitle = styles[3],
                ),
                shapes = ShapeSet(
                    small = shapeValues[0],
                    medium = shapeValues[1],
                    large = shapeValues[2],
                ),
            )
        }
    }

    private val reader: Reader? by lazy {
        runCatching { load() }
            .onFailure { PluginLog.w(TAG, "宿主 SaltTheme 不可用（暂停绘制原生页面）：" + it) }
            .getOrNull()
    }

    fun validate() { check(reader != null) { "宿主 SaltTheme ABI 不可用" } }

    @Suppress("UNCHECKED_CAST")
    private fun load(): Reader {
        val theme = Class.forName(THEME_CLASS)
        fun local(name: String): CompositionLocal<Any?> =
            theme.getMethod(name).invoke(null) as CompositionLocal<Any?>
        val colors = Class.forName(COLORS_CLASS)
        val dynamic = Class.forName(DYNAMIC_COLORS_CLASS)
        val configs = Class.forName(CONFIGS_CLASS)
        val styles = Class.forName(TEXT_STYLES_CLASS)
        val shapes = Class.forName(SHAPES_CLASS)
        return Reader(
            configs = local(LOCALS_CONFIGS),
            dynamicColors = local(LOCALS_DYNAMIC_COLORS),
            textStyles = local(LOCALS_TEXT_STYLES),
            shapes = local(LOCALS_SHAPES),
            isDark = configs.getMethod(CONFIGS_IS_DARK),
            lightColors = dynamic.getMethod(DYNAMIC_LIGHT),
            darkColors = dynamic.getMethod(DYNAMIC_DARK),
            colorGetters = COLOR_GETTERS.map(colors::getMethod),
            textStyleGetters = TEXT_STYLE_GETTERS.map(styles::getMethod),
            shapeGetters = SHAPE_GETTERS.map(shapes::getMethod),
        )
    }

    /** 组合期读取宿主主题；不可用返回 null，不绘制错误主题。 */
    @Composable
    fun read(): Snapshot? {
        val current = reader ?: return null
        val composer = currentComposer
        return try {
            current.read(composer)
        } catch (t: Throwable) {
            PluginLog.w(TAG, "读取宿主 SaltTheme 失败（已暂停绘制）：" + t)
            null
        }
    }

    /** 开发者页使用的主题来源说明（不参与正式页面布局）。 */
    fun describe(snapshot: Snapshot?): String = if (snapshot == null) {
        "不可用：未读到宿主 SaltTheme（混淆名可能已随宿主版本变化，已暂停绘制）"
    } else {
        "宿主 SaltTheme（" + (if (snapshot.dark) "深色" else "浅色") +
            "；颜色/字体/圆角来自宿主 CompositionLocal）"
    }
}

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
    //   3) replaceComposable replaces this navigation call site only. Active pages use
    //      the host BasicScreenBox; no configuration rows or solid overlay sit below.
    //      Other plugins and the collapsed state invoke the original method directly.
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
        TARGET_PARAMETERS.map(HostUiAbi::name) + listOf("androidx.compose.runtime.Composer", "int", "int")
    private const val PRIORITY = 100

    @Volatile
    private var handle: HookHandle? = null
    private var defaultsCompatibility: AutoCloseable? = null

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
            HostSaltComponents.validate()
            HostSaltTheme.validate()
            val clazz = try {
                HostUiAbi.type(TARGET_CLASS)
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
            HostConfigContent.bind(matches.single())
            val h = WorkshopApi.hookRegistrar.replaceComposable(
                HostUiAbi.name(TARGET_CLASS),
                TARGET_METHOD,
                TARGET_IN_CLASS,
                TARGET_PARAMETERS.map(HostUiAbi::name),
                PRIORITY,
                PanelHook,
            )
            try {
                defaultsCompatibility = HostReplacementDefaults.prepare(WorkshopApi.hookRegistrar, matches.single(), TARGET_IN_CLASS)
                HostGlobalSearch.install()
            } catch (failure: Throwable) {
                defaultsCompatibility?.close()
                defaultsCompatibility = null
                h.unhook()
                throw failure
            }
            handle = h
            NativeUiSession.start()
            // 不在这里打开整页界面：是否显示由 PanelHook 按当前配置路由的 pluginId 决定，
            // 避免其它插件的配置页（共享同一个 ModConfigScreen）被本插件覆盖（REVIEW issue2）。
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
        HostGlobalSearch.uninstall()
        val h = handle
        if (h == null) {
            return
        }
        handle = null
        NativeUiSession.stop()
        try {
            try {
                defaultsCompatibility?.close()
            } finally {
                defaultsCompatibility = null
                h.unhook()
            }
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

    /**
     * 宿主侧唯一注入点：只在本插件自己的配置路由上绘制原生页面。
     *
     * <p>宿主 ModConfigScreen 被所有插件共用，因此必须先读 [UiHookCall] 里的当前路由
     * （ScreenRoute.ModConfigContent.pluginId）再决定是否绘制；其它插件、路由缺失或
     * 类型不认识时直接返回，不创建入口、不消费指针事件（REVIEW issue2）。</p>
     */
    private object PanelHook : ComposableHook() {
        /** 跳过日志去重：同一个路由只记录一次，避免每次重组都写插件日志。 */
        @Volatile
        private var lastSkippedRoute: String? = null
        private var observed = false

        @Composable
        override fun Content(call: UiHookCall) {
            val route = HostUiMountScope.routeOf(call)
            if (!observed) {
                observed = true
                PluginLog.i(TAG, "PanelHook 已实际执行：" + HostUiMountScope.describe(route))
            }
            if (!HostUiMountScope.shouldMountRoute(route)) {
                val desc = HostUiMountScope.describe(route)
                if (desc != lastSkippedRoute) {
                    lastSkippedRoute = desc
                    PluginLog.i(TAG, "PanelHook 跳过（非本插件配置页）：" + desc)
                }
                HostConfigContent.draw(call, currentComposer)
                return
            }
            if (NativeUiPresentation.showsConfiguration()) {
                NativeUiBackground { HostConfigContent.draw(call, currentComposer) }
            }
            NativeUiPanel()
        }
    }
}

/** The hook replaces one navigation call site, not this method's implementation.
 * A direct invocation therefore preserves the host configuration page for every
 * other plugin and for this plugin's collapsed state, without recursive hooks.
 */
internal object HostConfigContent {
    private lateinit var original: Method
    fun bind(method: Method) { original = method.apply { isAccessible = true } }
    fun draw(call: UiHookCall, composer: Composer) {
        val args = call.arguments
        val viewModel = args.getOrNull(1)
        // The installed navigation call uses the default ViewModel (mask=2).
        // Preserve an explicit ViewModel if one was supplied by the host.
        try {
            original.invoke(null, args.getOrNull(0), viewModel, composer, 0, if (viewModel == null) 2 else 0)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
