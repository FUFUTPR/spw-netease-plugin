package com.example.netease.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;

/**
 * 插件版本读取：只认打包时写进 MANIFEST 的 {@code Plugin-Version}，代码里不硬编码版本号。
 *
 * <p>打包脚本会把同一份 manifest 同时放到 {@code META-INF/MANIFEST.MF}（根，PF4J 读）与
 * {@code classes/META-INF/MANIFEST.MF}（插件类加载器可见，这里读的就是后者）。</p>
 */
public final class PluginVersions {

    private static volatile String cached;

    private PluginVersions() {
    }

    /** 读插件自身版本；失败返回 {@code "unknown"}（绝不抛）。 */
    public static String read() {
        String v = cached;
        if (v != null) {
            return v;
        }
        v = "unknown";
        try (InputStream in = PluginVersions.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in != null) {
                Manifest mf = new Manifest(in);
                String value = mf.getMainAttributes().getValue("Plugin-Version");
                if (value != null && !value.isBlank()) {
                    v = value.trim();
                }
            }
        } catch (Throwable t) {
            PluginLog.w("version", "读取 MANIFEST 失败，按 unknown 处理 :: " + t);
        }
        cached = v;
        return v;
    }

    /**
     * 对外 User-Agent 的固定前缀：{@code SaltPlayerNeteasePlugin/<版本>}。
     *
     * <p>版本号一律由这里的 {@link #read()} 从 MANIFEST 取，**各处不得再硬编码**——
     * 0.9.0 之前 CoverArt / NativeStreamServer / CoverCache 各自写死了 0.7.0 / 0.8.0，
     * 一次版本升级要改四处、漏一处就长期漂移。</p>
     */
    private static final String UA_PREFIX = "SaltPlayerNeteasePlugin/";

    /** 统一 UA：{@code SaltPlayerNeteasePlugin/<版本>}（版本取 MANIFEST；读不到时为 {@code unknown}）。 */
    public static String userAgent() {
        return UA_PREFIX + read();
    }

    /** 浏览器形态的 UA：个别 CDN 只认 {@code Mozilla} 前缀，所以套一层再拼插件标识。 */
    public static String browserUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " + userAgent();
    }

    /** 低层兜底：直接按字节找 Plugin-Version（有些打包方式会让 Manifest 解析失败）。 */
    public static String readRaw() {
        try (InputStream in = PluginVersions.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return "unknown";
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                if (line.startsWith("Plugin-Version:")) {
                    return line.substring("Plugin-Version:".length()).trim();
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        return "unknown";
    }
}
