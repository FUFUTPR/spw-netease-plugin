package com.example.netease.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 插件自有日志（同步写盘，落 {@code workshop\data\<pluginId>\logs\plugin-YYYYMMDD.log}）。
 *
 * <p>铁律：</p>
 * <ul>
 *   <li>只写插件数据目录，**绝不**写宿主 {@code logs.txt}。</li>
 *   <li>单文件超过 {@value #MAX_BYTES} 字节轮转为 {@code plugin-YYYYMMDD.1.log}；启动时清理 7 天前的日志。</li>
 *   <li>写日志本身**绝不能抛**（A2：任何异常都不能影响宿主）。</li>
 *   <li>所有输出先过 {@link #sanitize(String)}：cookie / password / encSecKey 一律 ****。</li>
 * </ul>
 */
public final class PluginLog {

    /** 日志级别。 */
    public enum Level { ERROR, WARN, INFO, DEBUG }

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int KEEP_DAYS = 7;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final Pattern[] SECRETS = {
            Pattern.compile("MUSIC_U=[^;\\s\"']*"),
            Pattern.compile("MUSIC_A=[^;\\s\"']*"),
            Pattern.compile("__csrf=[^;\\s\"']*"),
            Pattern.compile("encSecKey=[^;\\s\"']*"),
            // 网易云的非凭据类 cookie（设备 id / 登录记忆标记）也一律脱敏：
            // 契约是「日志里不出现任何 cookie 值」，不是「只对 MUSIC_U 脱敏」。
            Pattern.compile("NMTID=[^;\\s\"']*"),
            Pattern.compile("_ntes_nnid=[^;\\s\"']*"),
            Pattern.compile("_ntes_nuid=[^;\\s\"']*"),
            Pattern.compile("__remember_me=[^;\\s\"']*"),
            Pattern.compile("\"password\"\\s*:\\s*\"[^\"]*\""),
            Pattern.compile("\"encSecKey\"\\s*:\\s*\"[^\"]*\""),
            Pattern.compile("(?i)(pass(word)?|pwd|token|cookie)\\s*[=:]\\s*[^;\\s\"']{4,}"),
    };

    private static final Object LOCK = new Object();

    private static volatile Level level = Level.INFO;
    private static volatile Path logsDir;
    private static volatile Path currentFile;
    private static BufferedWriter writer;
    private static long written;

    private PluginLog() {
    }

    /** 初始化日志目录（由 {@code NeteasePlugin.start()} 最先调用，此时还不会碰任何宿主类）。 */
    public static void init(Path dir) {
        synchronized (LOCK) {
            closeQuietly();
            logsDir = dir;
            currentFile = null;
            written = 0L;
            try {
                if (dir != null) {
                    Files.createDirectories(dir);
                    openWriter();
                    cleanupOldFiles(dir);
                }
            } catch (Throwable t) {
                // 日志起不来也只能算了，绝不能影响插件启动
                writer = null;
            }
        }
        i("log", "日志初始化完成：level=" + level + " file=" + currentFile);
    }

    /** 设置级别：error / warn / info / debug（非法值按 info）。 */
    public static void setLevel(String name) {
        if (name == null) {
            return;
        }
        try {
            level = Level.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
        }
    }

    public static Level currentLevel() {
        return level;
    }

    /** 当前日志文件（可能为 null，例如初始化失败）。 */
    public static Path file() {
        return currentFile;
    }

    public static boolean enabled(Level lv) {
        return lv.ordinal() <= level.ordinal();
    }

    public static void i(String tag, String msg) {
        write(Level.INFO, tag, msg);
    }

    public static void w(String tag, String msg) {
        write(Level.WARN, tag, msg);
    }

    public static void e(String tag, String msg) {
        write(Level.ERROR, tag, msg);
    }

    public static void d(String tag, String msg) {
        write(Level.DEBUG, tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        writeThrowable(Level.ERROR, tag, msg, t);
    }

    /** 与 {@link #e(String, String, Throwable)} 对称的三参警告。 */
    public static void w(String tag, String msg, Throwable t) {
        writeThrowable(Level.WARN, tag, msg, t);
    }

    private static void writeThrowable(Level lv, String tag, String msg, Throwable t) {
        if (t == null) {
            write(lv, tag, msg);
            return;
        }
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        sb.append(" :: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\n    at ").append(el);
            if (sb.length() > 8000) {
                sb.append("\n    ...(栈已截断)");
                break;
            }
        }
        write(lv, tag, sb.toString());
    }

    /** 脱敏：cookie / 密码 / encSecKey 一律替换为 ****。 */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Pattern p : SECRETS) {
            out = p.matcher(out).replaceAll(mr ->
                    mr.group().contains("=") && !mr.group().startsWith("\"")
                            ? mr.group().substring(0, mr.group().indexOf('=') + 1) + "****"
                            : "\"" + (mr.group().startsWith("\"password") || mr.group().startsWith("\"encSecKey")
                                        ? mr.group().substring(1, mr.group().indexOf('"', 1) + 1) : "token") + "\":\"****\"");
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static void write(Level lv, String tag, String msg) {
        if (!enabled(lv)) {
            return;
        }
        String line = String.format("%s [%-5s] [%s] %s",
                java.time.LocalTime.now().withNano(0), lv, tag == null ? "-" : tag, sanitize(msg));
        synchronized (LOCK) {
            try {
                if (writer == null) {
                    return;
                }
                rotateIfNeeded();
                writer.write(line);
                writer.newLine();
                writer.flush();
                written += line.length();
            } catch (Throwable ignored) {
                // 日志失败绝不影响业务
            }
        }
    }

    private static void openWriter() throws IOException {
        LocalDate today = LocalDate.now();
        currentFile = logsDir.resolve("plugin-" + today.format(DATE) + ".log");
        writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            written = Files.size(currentFile);
        } catch (IOException e) {
            written = 0L;
        }
    }

    private static void rotateIfNeeded() throws IOException {
        if (written < MAX_BYTES || currentFile == null) {
            return;
        }
        writer.close();
        Path rotated = currentFile.resolveSibling(currentFile.getFileName().toString().replace(".log", ".1.log"));
        try {
            Files.move(rotated, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable ignored) {
            // 目标不存在是正常情况
        }
        try {
            Files.deleteIfExists(rotated);
        } catch (Throwable ignored) {
            // 删不掉就算了，后面直接覆盖写
        }
        try {
            Files.move(currentFile, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // 移动失败就继续往原文件追加
        }
        writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        written = 0L;
    }

    private static void cleanupOldFiles(Path dir) {
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("plugin-") && n.endsWith(".log");
            }).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                    }
                } catch (Throwable ignored) {
                    // 单个文件删不掉不影响其它
                }
            });
        } catch (Throwable ignored) {
            // 目录不可读就算了
        }
    }

    private static void closeQuietly() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        } finally {
            writer = null;
        }
    }
}
