package com.example.netease.ui;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;

/**
 * 内嵌验证组件（VerifyHost）的资源定位：可执行目录与 WebView2 用户数据目录。
 *
 * <p>批次 4 起由 {@link VerifyWindow}（旧 JDialog 承载）与
 * {@code com.example.netease.ui.controller.VerifyEmbedController}（原生页面内嵌）共用，
 * 避免两套定位逻辑漂移。</p>
 */
public final class VerifyHostPaths {

    private static final String OVERRIDE_PROP = "spw.verifyhost";

    private VerifyHostPaths() {
    }

    /**
     * 助手目录：优先 {@code -Dspw.verifyhost=<dir>}（探针/开发用），否则按本类的 code source 推 ——
     * 插件从 {@code <pluginPath>\plugin-*.jar} 加载 ⇒ 兄弟目录 {@code <pluginPath>\classes\verify-host}。
     */
    public static Path helperDir() {
        String override = System.getProperty(OVERRIDE_PROP);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                return p;
            }
            PluginLog.w("verify-host", "-D" + OVERRIDE_PROP + " 指的不是目录：" + override);
        }
        try {
            URL loc = VerifyHostPaths.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            Path dir = Files.isDirectory(p) ? p : p.getParent();
            Path[] candidates = Files.isDirectory(p)
                    ? new Path[] {p.resolve("classes").resolve("verify-host"), p.resolve("verify-host")}
                    : new Path[] {dir.resolve("classes").resolve("verify-host"), dir.resolve("verify-host")};
            for (Path c : candidates) {
                if (Files.isDirectory(c)) {
                    return c;
                }
            }
            PluginLog.w("verify-host", "按 code source 没找到助手目录（codeSource=" + p + "）");
        } catch (Throwable t) {
            PluginLog.w("verify-host", "定位助手目录失败：" + t.getClass().getSimpleName());
        }
        return null;
    }

    /** WebView2 的用户数据目录（放插件数据目录下的 verify-webview；拿不到数据目录就退到临时目录）。 */
    public static Path dataDir() {
        try {
            Path d = DataPaths.data();
            if (d != null) {
                Path target = d.resolve("verify-webview");
                DataPaths.ensure(target);
                return target;
            }
        } catch (Throwable t) {
            PluginLog.w("verify-host", "取插件数据目录失败：" + t.getClass().getSimpleName());
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir", "."), "spw-verify-webview");
        try {
            Files.createDirectories(fallback);
        } catch (Throwable ignored) {
            // 建不出来就让 WebView2 自己去抱怨（会走失败态）
        }
        return fallback;
    }
}
