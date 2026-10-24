package com.example.netease.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Locale;

/**
 * 插件数据目录约定。
 *
 * <p>数据写入宿主上下文指定的 {@code workshop/data/<pluginId>}；Windows 与原生 Linux
 * 的数据根分别回退到 APPDATA 和 XDG_DATA_HOME。</p>
 * <p>绝不写 {@code spw.db}，绝不写宿主 {@code logs.txt}，绝不主动删宿主 {@code cache\}。</p>
 *
 * <p>注意：这里只解析上下文给出的路径、环境变量与 {@code user.home}，**不触碰任何宿主类**，
 * 所以可以在 {@code start()} 的最前面安全调用（死锁铁律第 1 条）。</p>
 */
public final class DataPaths {

    private static final String APP_DIR_NAME = "Salt Player for Windows";
    private static volatile Path dataDir;
    private static volatile Path hostDir;
    private static volatile String storageNote = "";

    private DataPaths() {
    }

    /** 由 {@code NeteasePlugin.start()} 早期调用一次。 */
    public static synchronized void init(String pluginId) {
        init(pluginId, null);
    }

    /** The host context identifies the actual workshop on native Linux and Wine alike. */
    public static synchronized void init(String pluginId, String pluginPath) {
        String id = (pluginId == null || pluginId.isBlank()) ? "com.example.netease" : pluginId.trim();
        hostDir = contextHostDirectory(pluginPath);
        storageNote = "";
        Path workshop = workshopRoot();
        Path preferred = workshop.resolve("data").resolve(id);
        dataDir = existingDataDirectory(id, preferred);
        if (!dataDir.equals(preferred)) storageNote = "保留 Linux 旧版凭据与缓存目录：" + dataDir;
        ensure(dataDir);
    }

    private static Path existingDataDirectory(String id, Path preferred) {
        if (!nativeLinux()) return preferred;
        Path legacy = Path.of(System.getProperty("user.home", "."), "AppData", "Roaming", APP_DIR_NAME,
                "workshop", "data", id).toAbsolutePath().normalize();
        // Cache services may resolve data() before start(). Make the same choice there.
        // Keep old credentials in place; host-created empty folders must not reset login.
        // An established destination remains authoritative, and neither tree is merged.
        if (Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(preferred.resolve("account.key"), LinkOption.NOFOLLOW_LINKS)) return legacy;
        return preferred;
    }

    static Path contextHostDirectory(String pluginPath) {
        if (pluginPath == null || pluginPath.isBlank()) return null;
        try {
            Path path = Path.of(pluginPath);
            if (!path.isAbsolute()) return null;
            Path plugins = path.normalize().getParent();
            Path workshop = plugins == null ? null : plugins.getParent();
            if (plugins != null && workshop != null && workshop.getParent() != null
                    && "plugins".equalsIgnoreCase(String.valueOf(plugins.getFileName()))
                    && "workshop".equalsIgnoreCase(String.valueOf(workshop.getFileName()))) {
                return workshop.getParent();
            }
        } catch (RuntimeException invalidPath) { /* keep the platform fallback */ }
        return null;
    }

    public static boolean nativeLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    public static String storageNote() { return storageNote; }

    public static Path hostDataDirectory() {
        Path bound = hostDir;
        if (bound != null) return bound;
        if (nativeLinux()) {
            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg != null && !xdg.isBlank()) {
                try { if (Path.of(xdg).isAbsolute()) return Path.of(xdg, "Salt Player"); }
                catch (RuntimeException ignored) { }
            }
            return Path.of(System.getProperty("user.home", "."), ".local", "share", "Salt Player");
        }
        String appdata = System.getenv("APPDATA");
        Path roaming = appdata != null && !appdata.isBlank() ? Path.of(appdata)
                : Path.of(System.getProperty("user.home", "."), "AppData", "Roaming");
        return roaming.resolve(APP_DIR_NAME);
    }

    /** 宿主 workshop 根目录。 */
    public static Path workshopRoot() {
        return hostDataDirectory().resolve("workshop");
    }

    /** 插件数据根目录。init 之前调用会按默认 pluginId 兜底推导。 */
    public static Path data() {
        Path dir = dataDir;
        if (dir == null) {
            dir = existingDataDirectory("com.example.netease", workshopRoot().resolve("data").resolve("com.example.netease"));
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
