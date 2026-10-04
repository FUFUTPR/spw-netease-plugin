package com.example.netease.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 插件数据目录约定。
 *
 * <p>唯一可写根：{@code %APPDATA%\Salt Player for Windows\workshop\data\<pluginId>\}。</p>
 * <p>绝不写 {@code spw.db}，绝不写宿主 {@code logs.txt}，绝不主动删宿主 {@code cache\}。</p>
 *
 * <p>注意：这里只依赖环境变量与 {@code user.home}，**不触碰任何宿主类**，
 * 所以可以在 {@code start()} 的最前面安全调用（死锁铁律第 1 条）。</p>
 */
public final class DataPaths {

    private static final String APP_DIR_NAME = "Salt Player for Windows";
    private static volatile Path dataDir;

    private DataPaths() {
    }

    /** 由 {@code NeteasePlugin.start()} 早期调用一次。 */
    public static synchronized void init(String pluginId) {
        String id = (pluginId == null || pluginId.isBlank()) ? "com.example.netease" : pluginId.trim();
        Path workshop = workshopRoot();
        dataDir = workshop.resolve("data").resolve(id);
        ensure(dataDir);
    }

    /** 宿主 workshop 根目录。 */
    public static Path workshopRoot() {
        String appdata = System.getenv("APPDATA");
        Path roaming;
        if (appdata != null && !appdata.isBlank()) {
            roaming = Path.of(appdata);
        } else {
            String home = System.getProperty("user.home", ".");
            roaming = Path.of(home, "AppData", "Roaming");
        }
        return roaming.resolve(APP_DIR_NAME).resolve("workshop");
    }

    /** 插件数据根目录。init 之前调用会按默认 pluginId 兜底推导。 */
    public static Path data() {
        Path dir = dataDir;
        if (dir == null) {
            dir = workshopRoot().resolve("data").resolve("com.example.netease");
            dataDir = dir;
        }
        return dir;
    }

    public static Path logs() {
        return ensure(data().resolve("logs"));
    }

    /** 数据目录下的一个文件。 */
    public static Path file(String name) {
        return data().resolve(name);
    }

    /** mkdirs 并原样返回（失败也不抛，交由调用方后续 IOException 处理）。 */
    public static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            PluginLog.w("paths", "创建目录失败：" + dir + " :: " + t);
        }
        return dir;
    }

    /** 人类可读的数据目录（配置页/日志用）。 */
    public static String display() {
        return String.valueOf(data());
    }
}
