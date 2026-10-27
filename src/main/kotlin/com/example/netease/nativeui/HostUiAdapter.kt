@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package com.example.netease.nativeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
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
    internal const val CONFIGS_CLASS = "androidx.compose.ui.ˤ"
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

    /** 颜色/字体/圆角的中文名：只用于失败原因，让界面与日志能指出是哪一个宿主符号。 */
    private val COLOR_NAMES = listOf(
        "强调色", "主文字", "次文字", "背景", "次级背景", "弹层",
        "描边", "强调前景", "成功", "警告", "错误",
    )
    private val TEXT_STYLE_NAMES = listOf("main", "sub", "paragraph", "largeTitle")
    private val SHAPE_NAMES = listOf("small", "medium", "large")

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

    /**
     * 一次 [Reader.measureInto] 的实测值，供缓存按键比较。
     *
     * <p>比较的是“读到的值”而不是 4 个 Local 的实例身份。宿主 1.19.2 的 AppTheme
     * （com.xuncorp.voxzen.ui.theme.Ԩ#Ϳ）每次执行都会重新构造 SaltThemeConfigs /
     * SaltDynamicColors / SaltTextStyles / SaltShapes 并作为 staticCompositionLocalOf 的值
     * 传进 SaltTheme：Local 身份每次都变，但主题值通常不变。反过来，SaltColors / SaltShapes
     * 的字段虽然是 final，内部却是 MutableState（getter 走 State.getValue()），所以不能只
     * 比对身份、跳过 getter —— 每次组合都要把 23 个 getter 读一遍，让 Snapshot 的真实
     * 响应依赖全部建立。字节码证据见 harness/theme/THEME-IMMUTABILITY.md。</p>
     */
    private class Measured {
        val colors = LongArray(COLOR_GETTERS.size)
        val textStyles = arrayOfNulls<TextStyle>(TEXT_STYLE_GETTERS.size)
        val shapes = arrayOfNulls<Shape>(SHAPE_GETTERS.size)
        var dark = false

        /** 值相等：颜色比较位模式，字体/圆角比较宿主提供的不可变值对象（equals）。 */
        fun sameValuesAs(other: Measured): Boolean {
            if (dark != other.dark) return false
            if (!colors.contentEquals(other.colors)) return false
            for (i in textStyles.indices) {
                if (textStyles[i] != other.textStyles[i]) return false
            }
            for (i in shapes.indices) {
                if (shapes[i] != other.shapes[i]) return false
            }
            return true
        }

        /** 只在值变化、确定要重建 Snapshot 时复制一次，避免每次组合都分配数组。 */
        fun copyFrom(other: Measured) {
            dark = other.dark
            System.arraycopy(other.colors, 0, colors, 0, colors.size)
            for (i in textStyles.indices) textStyles[i] = other.textStyles[i]
            for (i in shapes.indices) shapes[i] = other.shapes[i]
        }
    }

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
        /** 只读 isDarkTheme；四个 Local 为空的判定需要它（缺值时返回 null）。 */
        fun darkOf(configsValue: Any?): Boolean? =
            if (configsValue == null) null else isDark.invoke(configsValue) as? Boolean

        /**
         * 一次完整读取（11 个颜色 + 4 个字体 + 3 个圆角 getter，共 23 次反射），写进调用方
         * 传入的 [Measured]；不分配 Snapshot，供缓存按值逐项比较。
         *
         * <p>读不到时抛出带宿主符号名的异常，由调用方记录成界面/日志可读的失败原因。</p>
         */
        fun measureInto(
            values: Measured,
            dynamicValue: Any,
            stylesValue: Any,
            shapesValue: Any,
            dark: Boolean,
        ) {
            values.dark = dark
            val colorsMethod = if (dark) darkColors else lightColors
            val saltColors = colorsMethod.invoke(dynamicValue)
                ?: throw IllegalStateException("宿主配色不可读：" + DYNAMIC_COLORS_CLASS + "#" + colorsMethod.name)
            for (i in values.colors.indices) {
                val getter = colorGetters[i]
                values.colors[i] = getter.invoke(saltColors) as? Long
                    ?: throw IllegalStateException(
                        "宿主颜色不可读：" + COLORS_CLASS + "#" + getter.name +
                            "（" + COLOR_NAMES.getOrElse(i) { "第 " + i + " 个" } + "）"
                    )
            }
            for (i in values.textStyles.indices) {
                val getter = textStyleGetters[i]
                values.textStyles[i] = getter.invoke(stylesValue) as? TextStyle
                    ?: throw IllegalStateException(
                        "宿主字体不可读：" + TEXT_STYLES_CLASS + "#" + getter.name +
                            "（" + TEXT_STYLE_NAMES.getOrElse(i) { "第 " + i + " 个" } + "）"
                    )
            }
            for (i in values.shapes.indices) {
                val getter = shapeGetters[i]
                values.shapes[i] = getter.invoke(shapesValue) as? Shape
                    ?: throw IllegalStateException(
                        "宿主圆角不可读：" + SHAPES_CLASS + "#" + getter.name +
                            "（" + SHAPE_NAMES.getOrElse(i) { "第 " + i + " 个" } + "）"
                    )
            }
        }

        /** 用刚读到的值组装 Snapshot（不重新反射）。 */
        fun snapshotOf(values: Measured): Snapshot = Snapshot(
            dark = values.dark,
            palette = Palette(
                highlight = colorFromPacked(values.colors[0]),
                text = colorFromPacked(values.colors[1]),
                subText = colorFromPacked(values.colors[2]),
                background = colorFromPacked(values.colors[3]),
                subBackground = colorFromPacked(values.colors[4]),
                popup = colorFromPacked(values.colors[5]),
                stroke = colorFromPacked(values.colors[6]),
                onHighlight = colorFromPacked(values.colors[7]),
                success = colorFromPacked(values.colors[8]),
                warning = colorFromPacked(values.colors[9]),
                error = colorFromPacked(values.colors[10]),
            ),
            textStyles = TypeSet(
                main = values.textStyles[0]!!,
                sub = values.textStyles[1]!!,
                paragraph = values.textStyles[2]!!,
                largeTitle = values.textStyles[3]!!,
            ),
            shapes = ShapeSet(
                small = values.shapes[0]!!,
                medium = values.shapes[1]!!,
                large = values.shapes[2]!!,
            ),
        )

        /** 无缓存构建路径：读一次完整快照；行为与 0.12.18 一致（UiMountScopeProbe 仍反射调用）。 */
        fun build(configsValue: Any, dynamicValue: Any, stylesValue: Any, shapesValue: Any, dark: Boolean): Snapshot {
            val values = Measured()
            measureInto(values, dynamicValue, stylesValue, shapesValue, dark)
            return snapshotOf(values)
        }

        /**
         * 无缓存构建路径：从当前 Composer 直接读一次完整快照。
         *
         * <p>UiMountScopeProbe 用反射直接调用它核对配色位模式，因此行为保持不变：
         * 任何一项读不到都返回 null、不抛异常。生产组合走 [HostSaltTheme.read]，
         * 快照由每个调用点自己的 remember 槽缓存复用。</p>
         */
        fun read(composer: Composer): Snapshot? {
            // 先 consume 再进 try：consume 只读当前组合值，需要兜底的是后续 23 次反射读取。
            val configsValue = composer.consume(configs)
            val dynamicValue = composer.consume(dynamicColors)
            val stylesValue = composer.consume(textStyles)
            val shapesValue = composer.consume(shapes)
            return try {
                val dark = darkOf(configsValue)
                if (configsValue == null || dynamicValue == null || stylesValue == null ||
                    shapesValue == null || dark == null
                ) {
                    null
                } else {
                    build(configsValue, dynamicValue, stylesValue, shapesValue, dark)
                }
            } catch (t: Throwable) {
                null
            }
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

    /**
     * 每个调用点一份（remember { SnapshotCache() }）：实测主题值没变时返回同一个 Snapshot。
     *
     * <p>键不是 4 个 Local 的实例身份。宿主 AppTheme（com.xuncorp.voxzen.ui.theme.Ԩ#Ϳ）
     * 每次执行都会 new SaltThemeConfigs / SaltDynamicColors / SaltTextStyles / SaltShapes
     * 再交给 SaltTheme 的 staticCompositionLocalOf（javap 见 harness/theme/），身份每次组合
     * 都可能变，但值通常不变；按身份缓存会让宿主每重组一次就重建 Snapshot 并让整棵插件子树
     * 失效。</p>
     *
     * <p>每次 read 都先完整读 23 个 getter（[Reader.measureInto]），再按值比较：颜色比位模式，
     * 字体/圆角比不可变值对象，isDarkTheme 单独比。这样即使宿主将来把 SaltColors / SaltShapes
     * 内部的 MutableState 原位改写（当前字节码没有 setter/setValue，见
     * harness/theme/THEME-IMMUTABILITY.md），组合期读 getter 也会注册快照依赖并读到新值，
     * 不会因为跳过 getter 而漏掉响应式更新。</p>
     *
     * <p>只有成功建好 Snapshot 才写缓存：ABI 不匹配时每次组合都会重试读取，但
     * recordFailure 会去重日志；窗口/页面重新进入会换新的 remember 槽。</p>
     */
    private class SnapshotCache {
        /** 本次实测值；复用缓冲，命中时零分配。 */
        private val measured = Measured()

        /** 上一次成功建 Snapshot 时的值，只在确定重建时复制。 */
        private val key = Measured()
        private var primed = false
        private var snapshot: Snapshot? = null

        fun resolve(
            reader: Reader,
            configs: Any?,
            dynamicColors: Any?,
            textStyles: Any?,
            shapes: Any?,
            dark: Boolean?,
        ): Snapshot? {
            if (configs == null || dynamicColors == null || textStyles == null ||
                shapes == null || dark == null
            ) {
                recordFailure(missingReason(configs, dynamicColors, textStyles, shapes, dark))
                return null
            }
            return try {
                reader.measureInto(measured, dynamicColors, textStyles, shapes, dark)
                if (primed && measured.sameValuesAs(key)) {
                    return snapshot
                }
                val built = reader.snapshotOf(measured)
                key.copyFrom(measured)
                primed = true
                snapshot = built
                built
            } catch (t: Throwable) {
                recordFailure("读取宿主 SaltTheme 失败：" + t)
                null
            }
        }

        private fun missingReason(
            configs: Any?,
            dynamicColors: Any?,
            textStyles: Any?,
            shapes: Any?,
            dark: Boolean?,
        ): String = when {
            configs == null -> "宿主未提供 SaltConfigs（CompositionLocal 为空）"
            dynamicColors == null -> "宿主未提供 SaltDynamicColors（CompositionLocal 为空）"
            textStyles == null -> "宿主未提供 SaltTextStyles（CompositionLocal 为空）"
            shapes == null -> "宿主未提供 SaltShapes（CompositionLocal 为空）"
            dark == null ->
                "宿主 SaltConfigs 的 isDarkTheme 读取失败：" + CONFIGS_CLASS + "#" + CONFIGS_IS_DARK
            else -> "宿主主题读取失败（原因未识别）"
        }
    }

    /** 最近一次读取失败的完整原因；供界面/日志反馈，没有失败过时为 null。 */
    @Volatile
    private var lastFailure: String? = null

    /** 与 [lastFailure] 配合做日志去重：PluginLog 是同步写盘的，绝不能在组合里反复写。 */
    @Volatile
    private var loggedFailure: String? = null

    /**
     * 组合期读取宿主主题；不可用返回 null，不绘制错误主题。
     *
     * <p>每次组合都先 consume 4 个宿主 local（configs / dynamicColors / textStyles /
     * shapes），再完整读 23 个主题 getter；快照只在「读到的值」变化时重建（见
     * [SnapshotCache]）。因此深浅色、字体、圆角、颜色任一真正变化都会重建，而宿主仅仅
     * 重新 new 一遍主题对象（值相同）时返回同一个 Snapshot 实例，LocalSaltSnapshot 不会
     * 让整棵子树失效。缓存挂在每个调用点自己的 remember 槽（本文件调用点：NativeUiPanel、
     * 开发者页、HostGlobalSearch.RootHook 的两处），多窗口、多入口互不影响。</p>
     */
    @Composable
    fun read(): Snapshot? {
        val current = reader
        if (current == null) {
            recordFailure("宿主 SaltTheme ABI 不可用：" + THEME_CLASS + " 的主题 local 缺失")
            return null
        }
        val composer = currentComposer
        val cache = remember { SnapshotCache() }
        // 先 consume 再进 try：四个 local 每次都读（维持失效依赖），try 只包缓存/反射逻辑。
        val configsValue = composer.consume(current.configs)
        val dynamicValue = composer.consume(current.dynamicColors)
        val stylesValue = composer.consume(current.textStyles)
        val shapesValue = composer.consume(current.shapes)
        return try {
            cache.resolve(current, configsValue, dynamicValue, stylesValue, shapesValue, current.darkOf(configsValue))
        } catch (t: Throwable) {
            recordFailure("读取宿主 SaltTheme 失败：" + t)
            null
        }
    }

    /** 最近一次读取失败原因；界面与日志用它说明「为什么没画原生页面」。 */
    fun failureReason(): String? = lastFailure

    private fun recordFailure(reason: String) {
        lastFailure = reason
        if (reason != loggedFailure) {
            loggedFailure = reason
            PluginLog.w(TAG, "[native-ui] " + reason + "（已暂停绘制原生页面）")
        }
    }

    /**
     * 本次组合实际 consume 到的宿主 SaltConfigs。
     *
     * <p>宿主的 Indication 等配置 getter 只有实例才读得到；这里再 consume 一次而不是
     * 缓存「最近一次 configs」，才不会在双窗口/多入口下读到另一个窗口的实例。</p>
     */
    @Composable
    internal fun currentConfigs(): Any? {
        val current = reader ?: return null
        // 只读本次组合真正提供的实例，不缓存跨窗口的旧值；组合期正常不会抛异常。
        return currentComposer.consume(current.configs)
    }

    /** 开发者页使用的主题来源说明（不参与正式页面布局）。 */
    fun describe(snapshot: Snapshot?): String = if (snapshot == null) {
        "不可用：未读到宿主 SaltTheme（混淆名可能已随宿主版本变化，已暂停绘制）" +
            (failureReason()?.let { "；最近失败：" + it } ?: "")
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
        HostLibraryNotices.install()
        HostRowCoverHook.install()
        HostSystemMediaCoverHook.install()
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
            val warnings = listOfNotNull(HostLibraryNotices.failureMessage, HostRowCoverHook.failureMessage, HostSystemMediaCoverHook.failureMessage)
            if (warnings.isNotEmpty()) NativeUiState.status += "；" + warnings.joinToString("；")
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
        HostLibraryNotices.uninstall()
        HostRowCoverHook.uninstall()
        HostSystemMediaCoverHook.uninstall()
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
