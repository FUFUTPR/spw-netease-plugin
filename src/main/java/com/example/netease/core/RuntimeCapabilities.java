package com.example.netease.core;

import com.xuncorp.spw.workshop.api.WorkshopApi;

/**
 * 宿主能力探测（惰性、只读、**不得触发宿主类静态初始化**）。
 *
 * <p>背景：本插件只按 1.18.5 的 **19 个 API 类**编写。dev21 才有的东西
 * （{@code WorkshopApi.Library}、{@code KeyBindingManager}、{@code ActionShortcut}、
 * {@code PluginPermission}、{@code Playback.getCurrentMediaItem}、{@code getLyricsLines}、
 * 扩展点的 {@code onLyricsLinesUpdated}）一律**不能**出现在字段声明、方法签名或类初始化路径里，
 * 否则插件类一加载就 {@code NoClassDefFoundError}。</p>
 *
 * <p>探测方式：{@code Class.forName(name, false, loader)}（false = 不初始化）
 * 与 {@code getMethod}。全部在 {@link HostBridgeWorker} 线程上执行。</p>
 */
public final class RuntimeCapabilities {

    private static volatile String spwVersion = "unknown";
    private static volatile String channel = "unknown";

    private static volatile boolean probed;
    private static volatile boolean hasLibrary;
    private static volatile boolean hasKeyBindingManager;
    private static volatile boolean hasPermissionApi;
    private static volatile boolean hasCurrentMediaItem;
    private static volatile boolean hasNoArgCreateConfigManager;

    private RuntimeCapabilities() {
    }

    /** 由主类在 start() 里用宿主注入的 {@code PluginContext} 填充。 */
    public static void setRuntime(String version, String channelName) {
        if (version != null && !version.isBlank()) {
            spwVersion = version.trim();
        }
        if (channelName != null && !channelName.isBlank()) {
            channel = channelName.trim();
        }
    }

    /** 探测一次并缓存（幂等）。**必须在 HostBridgeWorker 线程上调用。** */
    public static synchronized void probeOnce() {
        if (probed) {
            return;
        }
        ClassLoader loader = RuntimeCapabilities.class.getClassLoader();
        hasLibrary = classExists("com.xuncorp.spw.workshop.api.WorkshopApi$Library", loader);
        hasKeyBindingManager = classExists("com.xuncorp.spw.workshop.api.KeyBindingManager", loader);
        hasPermissionApi = classExists("com.xuncorp.spw.workshop.api.PluginPermission", loader);
        hasCurrentMediaItem = methodExists(WorkshopApi.Playback.class, "getCurrentMediaItem");
        hasNoArgCreateConfigManager = methodExists(WorkshopApi.Manager.class, "createConfigManager");
        probed = true;
        PluginLog.i("caps", report());
    }

    public static boolean isProbed() {
        return probed;
    }

    public static boolean hasLibrary() {
        return hasLibrary;
    }

    public static boolean hasKeyBindingManager() {
        return hasKeyBindingManager;
    }

    public static boolean hasPermissionApi() {
        return hasPermissionApi;
    }

    public static boolean hasCurrentMediaItem() {
        return hasCurrentMediaItem;
    }

    public static boolean hasNoArgCreateConfigManager() {
        return hasNoArgCreateConfigManager;
    }

    public static String spwVersion() {
        return spwVersion;
    }

    public static String channel() {
        return channel;
    }

    /** 能力矩阵（启动时写日志首行）。 */
    public static String report() {
        return "能力矩阵 spw=" + spwVersion + " channel=" + channel
                + " library=" + flag(hasLibrary)
                + " keyBinding=" + flag(hasKeyBindingManager)
                + " permission=" + flag(hasPermissionApi)
                + " currentMediaItem=" + flag(hasCurrentMediaItem)
                + " noArgCreateConfigManager=" + flag(hasNoArgCreateConfigManager);
    }

    /** 人类可读的宿主版本行（用于配置页/日志）。 */
    public static String describe() {
        return "Salt Player " + spwVersion + " (" + channel + ")";
    }

    private static String flag(boolean b) {
        return b ? "有" : "无";
    }

    private static boolean classExists(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean methodExists(Class<?> owner, String name) {
        try {
            owner.getMethod(name);
            return true;
        } catch (Throwable t) {
            try {
                for (java.lang.reflect.Method m : owner.getMethods()) {
                    if (m.getName().equals(name)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return false;
        }
    }
}
