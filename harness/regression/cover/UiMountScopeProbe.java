package com.example.netease.nativeui;

import com.xuncorp.spw.workshop.api.hook.UiHookCall;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * REVIEW issue2（挂载范围）验收探针，附带 issue6（宿主主题桥接常量）冒烟检查。
 *
 * <p>全部通过反射或字节码常量池完成，不引用任何 Compose 编译期 API；可只用
 * javac + 仓库既有 classpath 编译（见文件末尾“运行方法”）。探针不创建窗口、
 * 不写网络、不启动宿主，只验证纯函数与静态约束。</p>
 *
 * <p>验证点：</p>
 * <ol>
 *   <li>HostUiMountScope：本插件 pluginId 精确匹配 → true；其它插件 / 缺失 / 空 /
 *       首尾空格 / 大小写不同 / 非 ModConfigContent 路由 → false；</li>
 *   <li>shouldMount(UiHookCall)：正常参数、空参数、null、getArguments 抛异常 → 过滤正确；</li>
 *   <li>install() 不得无条件可见：HostUiAdapter.class 常量池不得含 setVisible；</li>
 *   <li>PanelHook 必须按 shouldMountRoute 过滤且匹配时才画 NativeUiPanel（常量池断言）；</li>
 *   <li>NativeUiState.visible 初值 false（需 Compose runtime，缺失则 SKIP）；</li>
 *   <li>NativePage 含 DEVELOPER 开发者入口，HOME 仍保留（需插件类，缺失则 SKIP）；</li>
 *   <li>宿主 SaltTheme 混淆名桥接常量存在（需宿主 jar + Compose runtime，缺失则 SKIP）。</li>
 * </ol>
 *
 * <p>推荐接入方式：仓库统一回归脚本已把本文件编入第 9 批（Batch 9），
 * 由 Lead 在构建出 .spmod 后执行 <code>harness/run-regression.ps1 -Batch 9</code>；
 * 该批次的运行 classpath 会自动追加宿主运行时（ffmpeg-x64.dll），因此
 * NativeUiState 初值与宿主 SaltTheme 检查不会被 SKIP。</p>
 *
 * <p>手动运行方法（仓库根目录；JDK 25）：</p>
 * <pre>
 *   javac -encoding UTF-8 -cp "build/plugin-classes;tools/.cache/spw-workshop-api-host.jar;tools/.cache/pf4j-3.12.0.jar" \
 *       -d build/_tmp/probe-classes harness/regression/cover/UiMountScopeProbe.java
 *   java -Djava.awt.headless=true -cp "build/_tmp/probe-classes;build/plugin-classes;tools/.cache/spw-workshop-api-host.jar;tools/.cache/pf4j-3.12.0.jar;tools/.cache/kotlin-stdlib-host.jar;C:/Program Files (x86)/Steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll" \
 *       com.example.netease.nativeui.UiMountScopeProbe
 * </pre>
 *
 * <p>若要把 NativeUiState 初值与主题桥接检查也跑起来，额外把 Compose runtime
 * desktop jar 加到运行 classpath（例：androidx.compose.runtime:runtime-desktop:1.12.1），
 * 缺失时对应检查输出 SKIP，不计失败。</p>
 */
public final class UiMountScopeProbe {

    private static final String SCOPE_CLASS = "com.example.netease.nativeui.HostUiMountScope";
    private static final String BRIDGE_CLASS = "com.example.netease.nativeui.HostSaltTheme";
    private static final String ADAPTER_CLASS = "com.example.netease.nativeui.HostUiAdapter";
    private static final String PANEL_HOOK_CLASS = "com.example.netease.nativeui.HostUiAdapter$PanelHook";
    private static final String STATE_CLASS = "com.example.netease.nativeui.NativeUiState";
    private static final String PAGE_CLASS = "com.example.netease.nativeui.NativePage";
    private static final String ROUTE_CLASS =
        "com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent";

    private static int pass;
    private static int fail;
    private static int skip;
    private static final List<String> failures = new ArrayList<String>();

    private UiMountScopeProbe() {
    }

    public static void main(String[] args) {
        System.out.println("== UiMountScopeProbe（REVIEW issue2/6）==");
        Class<?> scope = load(SCOPE_CLASS);
        if (scope == null) {
            System.out.println("FAIL 无法加载 " + SCOPE_CLASS + "：请先让 Lead 构建出 build/plugin-classes，或把本地编译输出放到 classpath 最前。");
            System.exit(1);
        }

        checkConstants(scope);
        checkPluginIdFilter(scope);
        checkShouldMount(scope);
        checkNoUnconditionalVisible();
        checkPanelHookGated();
        checkStateInitialVisible();
        checkDeveloperPageEnum();
        checkHostSaltThemeBridge();
        summarize();
    }

    // ------------------------------------------------------------------ 断言框架

    private static void ok(String name) {
        pass++;
        System.out.println("PASS " + name);
    }

    private static void bad(String name, String detail) {
        fail++;
        String line = name + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）");
        failures.add(line);
        System.out.println("FAIL " + line);
    }

    private static void skip(String name, String why) {
        skip++;
        System.out.println("SKIP " + name + "：" + why);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            ok(name);
        } else {
            bad(name, null);
        }
    }

    private static void summarize() {
        System.out.println("--------------------------------------------------");
        System.out.println("结果：PASS=" + pass + " FAIL=" + fail + " SKIP=" + skip);
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : failures) {
                System.out.println(" - " + s);
            }
            System.exit(1);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ 常量

    private static void checkConstants(Class<?> scope) {
        String pluginId = (String) staticField(scope, "PLUGIN_ID");
        String routeClass = (String) staticField(scope, "MOD_CONFIG_ROUTE_CLASS");
        check("PLUGIN_ID 为 com.example.netease", "com.example.netease".equals(pluginId));
        check("MOD_CONFIG_ROUTE_CLASS 精确指向宿主路由类", ROUTE_CLASS.equals(routeClass));
    }

    // ------------------------------------------------------------------ pluginId 过滤

    private static void checkPluginIdFilter(Class<?> scope) {
        Method pluginIdOf = method(scope, "pluginIdOf", Object.class);
        Method shouldMountRoute = method(scope, "shouldMountRoute", Object.class);
        if (pluginIdOf == null || shouldMountRoute == null) {
            bad("HostUiMountScope 缺少 pluginIdOf/shouldMountRoute", null);
            return;
        }

        Object own = newHostRoute("com.example.netease");
        if (own == null) {
            skip("本插件路由 → true", "宿主路由类不可用：" + ROUTE_CLASS + "（缺宿主 jar 或宿主版本不含该类）");
            skip("其它插件路由 → false", "同上");
            skip("缺失 / 空 / 首尾空格 / 大小写 / 异类路由 → false", "同上");
        } else {
            check("本插件 pluginId → pluginIdOf 返回原值",
                "com.example.netease".equals(call(pluginIdOf, own)));
            check("本插件 pluginId → shouldMountRoute=true", Boolean.TRUE.equals(call(shouldMountRoute, own)));
            check("其它插件 pluginId → shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, newHostRoute("com.example.other"))));
            check("缺失路由（null）→ shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, new Object[] {null})));
            check("空 pluginId → shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, newHostRoute(""))));
            check("空白 pluginId → shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, newHostRoute("   "))));
            check("首尾空格（近似但不精确）→ shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, newHostRoute(" com.example.netease"))));
            check("大小写不同 → shouldMountRoute=false",
                Boolean.FALSE.equals(call(shouldMountRoute, newHostRoute("COM.EXAMPLE.NETEASE"))));
            check("非 ModConfigContent 路由（String）→ false",
                Boolean.FALSE.equals(call(shouldMountRoute, "com.example.netease")));
            check("非 ModConfigContent 路由（任意 Object）→ false",
                Boolean.FALSE.equals(call(shouldMountRoute, new Object())));
        }
    }

    // ------------------------------------------------------------------ UiHookCall 过滤

    private static void checkShouldMount(Class<?> scope) {
        Method shouldMount = method(scope, "shouldMount", UiHookCall.class);
        Method routeOf = method(scope, "routeOf", UiHookCall.class);
        if (shouldMount == null || routeOf == null) {
            bad("HostUiMountScope 缺少 shouldMount/routeOf", null);
            return;
        }

        Object own = newHostRoute("com.example.netease");
        if (own == null) {
            skip("shouldMount 正常/异常分支", "宿主路由类不可用（缺宿主 jar）");
            return;
        }

        FakeCall ownCall = new FakeCall(own);
        check("shouldMount(本插件调用) → true", Boolean.TRUE.equals(call(shouldMount, ownCall)));
        check("routeOf 取第 0 个参数", own == call(routeOf, ownCall));
        check("shouldMount(无参数调用) → false",
            Boolean.FALSE.equals(call(shouldMount, new FakeCall())));
        check("shouldMount(getArguments=null) → false",
            Boolean.FALSE.equals(call(shouldMount, new FakeCall(null))));
        check("shouldMount(null) → false",
            Boolean.FALSE.equals(call(shouldMount, new Object[] {null})));
        check("shouldMount(getArguments 抛异常) → false（异常被吞掉）",
            Boolean.FALSE.equals(call(shouldMount, new ThrowingCall())));
        check("shouldMount(其它插件调用) → false",
            Boolean.FALSE.equals(call(shouldMount, new FakeCall(newHostRoute("com.example.other")))));
    }

    // ------------------------------------------------------------------ 静态约束

    private static void checkNoUnconditionalVisible() {
        byte[] adapter = readClassBytes(ADAPTER_CLASS);
        if (adapter == null) {
            skip("install 不无条件 visible=true", "读不到 HostUiAdapter.class");
            return;
        }
        check("HostUiAdapter.class 常量池不含 setVisible（install 不再强制置可见）",
            !containsAscii(adapter, "setVisible"));
        check("HostUiAdapter.class 仍引用 NativeUiState（状态诊断保留）",
            containsAscii(adapter, "NativeUiState"));
    }

    private static void checkPanelHookGated() {
        byte[] hook = readClassBytes(PANEL_HOOK_CLASS);
        if (hook == null) {
            skip("PanelHook 按路由过滤", "读不到 HostUiAdapter$PanelHook.class");
            return;
        }
        check("PanelHook 调用 HostUiMountScope.shouldMountRoute",
            containsAscii(hook, "shouldMountRoute"));
        check("PanelHook 仅在匹配后绘制 NativeUiPanel",
            containsAscii(hook, "NativeUiPanel"));
        check("PanelHook 不再无条件绘制（常量池不含 setVisible）",
            !containsAscii(hook, "setVisible"));
    }

    // ------------------------------------------------------------------ 页面状态

    private static void checkStateInitialVisible() {
        Class<?> state = load(STATE_CLASS);
        if (state == null) {
            skip("NativeUiState.visible 初值", "插件类不可用");
            return;
        }
        try {
            Field instance = state.getField("INSTANCE");
            Object obj = instance.get(null);
            Method getVisible = state.getMethod("getVisible");
            Object value = getVisible.invoke(obj);
            check("NativeUiState.visible 初值为 false（入口先以收起态出现）",
                Boolean.FALSE.equals(value));
        } catch (Throwable t) {
            skip("NativeUiState.visible 初值",
                "需要 Compose runtime（" + t.getClass().getSimpleName() + "：" + t.getMessage() + "）");
        }
    }

    private static void checkDeveloperPageEnum() {
        Class<?> page = load(PAGE_CLASS);
        if (page == null) {
            skip("NativePage 开发者入口", "插件类不可用");
            return;
        }
        try {
            Object[] values = (Object[]) page.getMethod("values").invoke(null);
            boolean hasDeveloper = false;
            boolean hasHome = false;
            for (Object value : values) {
                String name = ((Enum<?>) value).name();
                if ("DEVELOPER".equals(name)) {
                    hasDeveloper = true;
                }
                if ("HOME".equals(name)) {
                    hasHome = true;
                }
            }
            check("NativePage 含 DEVELOPER（开发者信息页）", hasDeveloper);
            check("NativePage 仍保留 HOME（普通用户首页）", hasHome);
        } catch (Throwable t) {
            skip("NativePage 开发者入口", "无法枚举：" + t);
        }
    }

    // ------------------------------------------------------------------ 宿主主题桥接常量

    private static void checkHostSaltThemeBridge() {
        try {
            Class<?> theme = Class.forName("androidx.compose.ui.\u02B6");
            requireMethods(theme, "\u037F", "\u0528", "\u0529", "\u052B");
            requireMethods(Class.forName("androidx.compose.ui.\u0374"),
                "\u037F", "\u0528", "\u0529", "\u052A", "\u052B", "\u052C",
                "\u052D", "\u052E", "\u052F", "\u0560", "\u0588");
            requireMethods(Class.forName("androidx.compose.ui.\u02B4"), "\u037F", "\u0528");
            requireMethods(Class.forName("androidx.compose.ui.\u02E4"), "\u037F");
            requireMethods(Class.forName("androidx.compose.ui.\u02B2"), "\u037F", "\u0528", "\u0529", "\u052A");
            requireMethods(Class.forName("androidx.compose.ui.\u02EE"), "\u037F", "\u0528", "\u0529");
            ok("宿主 SaltTheme 桥接常量齐全（6 类 + 主题 local / 配色 / 字体 / 形状 getter）");
        } catch (Throwable t) {
            skip("宿主 SaltTheme 桥接常量",
                "需要宿主 jar 与 Compose runtime（" + t.getClass().getSimpleName() + "：" + t.getMessage() + "）");
            return;
        }
        try {
            Class<?> compositionLocal = Class.forName("androidx.compose.runtime.CompositionLocal");
            Object configsLocal = Class.forName("androidx.compose.ui.\u02B6")
                .getMethod("\u037F").invoke(null);
            check("宿主主题 local \u037F 是 CompositionLocal 实例（可从 Composer.consume 读取）",
                compositionLocal.isInstance(configsLocal));

            Class<?> composerClass = Class.forName("androidx.compose.runtime.Composer");
            Class<?> bridge = Class.forName(BRIDGE_CLASS);
            Method read = bridge.getDeclaredMethod("read", composerClass, int.class);
            String snapshotName = read.getReturnType().getName();
            check("HostSaltTheme.read(Composer,int) 返回 Snapshot 读取入口",
                snapshotName.endsWith("HostSaltTheme$Snapshot"));
            bridge.getDeclaredMethod("describe", read.getReturnType());
            ok("HostSaltTheme.describe(Snapshot) 诊断入口存在");
        } catch (Throwable t) {
            skip("HostSaltTheme 读取/诊断入口", "需插件类与 Compose runtime：" + t);
        }
    }

    private static void requireMethods(Class<?> owner, String... names) throws Exception {
        for (String name : names) {
            owner.getMethod(name);
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("  ! 反射调用 " + method.getName() + " 抛出：" + cause);
            return null;
        }
    }

    private static Object newHostRoute(String pluginId) {
        try {
            Class<?> route = Class.forName(ROUTE_CLASS);
            Constructor<?> ctor = route.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(pluginId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readClassBytes(String binaryName) {
        String resource = binaryName.replace('.', '/') + ".class";
        InputStream in = UiMountScopeProbe.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 关闭失败不影响断言
            }
        }
    }

    /** 在常量池字节里按 ASCII 搜索；这里只搜 ASCII 名字，不涉及中文常量。 */
    private static boolean containsAscii(byte[] bytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 测试替身

    /** 极简 UiHookCall 替身：只实现 Hook 引擎会调用的三个抽象方法。 */
    private static class FakeCall extends UiHookCall {
        private final List<Object> arguments;

        FakeCall(Object... args) {
            this.arguments = args == null ? null : Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List<Object> getArguments() {
            return arguments;
        }

        @Override
        public Object getArgument(String name) {
            return null;
        }
    }

    /** getArguments 抛异常的调用：验证适配层把异常吞掉并返回 false。 */
    private static final class ThrowingCall extends UiHookCall {
        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List<Object> getArguments() {
            throw new IllegalStateException("模拟 Hook 参数不可读");
        }

        @Override
        public Object getArgument(String name) {
            return null;
        }
    }
}
