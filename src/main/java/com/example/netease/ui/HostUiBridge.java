package com.example.netease.ui;

import com.example.netease.core.PluginLog;

import java.lang.reflect.Method;

/**
 * 原生 UI 适配层的 Java 入口（0.11.60 批次 1）。
 *
 * <p>为什么是反射：{@code harness/run-harness.ps1} 只编译 {@code src/main/java}（对着
 * {@code StubWorkshopApi}），Java 源码不能直接 import Kotlin/Compose 类，否则编译失败。
 * 所以这里只出现一个字符串类名，真正的实现由 Gradle 编进 {@code com.example.netease.nativeui}。</p>
 *
 * <p>铁律：任何异常只记插件日志，绝不影响播放与插件启动；注册/卸载都幂等。</p>
 */
public final class HostUiBridge {

    private static final String TAG = "native-ui";
    private static final String IMPL = "com.example.netease.nativeui.NativeUiHost";

    private HostUiBridge() {
    }

    /** 由 {@code NeteasePlugin.start()} 调用；失败只记日志。 */
    public static void install(String hostVersion, String pluginVersion) {
        invoke("install", hostVersion, pluginVersion);
    }

    /** 由 {@code NeteasePlugin.stop()} 调用；失败只记日志。 */
    public static void uninstall() {
        invoke("uninstall");
    }

    /**
     * 原生页面路由（批次 3）：宿主配置页的「搜索歌曲 / 歌单与同步状态 / 登录 / 当前账号」先尝试
     * 打开主窗口内的原生页；返回 false 表示原生层未就绪（Hook 未装、Kotlin 类缺失等），调用方回退旧窗口。
     */
    public static boolean openNativePage(String page) {
        try {
            Class<?> impl = Class.forName(IMPL);
            Method method = impl.getMethod("openPage", String.class);
            Object result = method.invoke(null, nz(page));
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            PluginLog.d(TAG, "原生页面路由不可用（回退旧窗口）：" + t);
            return false;
        }
    }

    private static void invoke(String name, String... args) {
        try {
            Class<?> impl = Class.forName(IMPL);
            Method method;
            Object[] actual;
            if (args.length == 2) {
                method = impl.getMethod(name, String.class, String.class);
                actual = new Object[]{nz(args[0]), nz(args[1])};
            } else {
                method = impl.getMethod(name);
                actual = new Object[0];
            }
            method.invoke(null, actual);
        } catch (Throwable t) {
            // 隔离：宿主升级导致适配层失效时，插件其余功能必须照常工作
            if ("uninstall".equals(name)) {
                PluginLog.w(TAG, "原生 UI 卸下失败（已忽略）：" + t);
            } else {
                PluginLog.w(TAG, "原生 UI 挂载失败（已隔离，不影响播放）：" + t);
            }
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "unknown" : s;
    }
}
