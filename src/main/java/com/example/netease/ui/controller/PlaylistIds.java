package com.example.netease.ui.controller;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌单 ID / 链接解析（从 旧 PlaylistWindow（0.12.0 已删除） 抽出的纯逻辑，无 UI 依赖）。
 *
 * <p>语义与 旧 PlaylistWindow.parseId（0.12.0 已删除） 的旧实现逐字等价：</p>
 * <ul>
 *   <li>{@code null} / 空白 → -1；</li>
 *   <li>命中 {@code (?&amp;...)} 形式的三段式正则且能解析成 long → 该 id；</li>
 *   <li>无匹配 / 数字溢出 → -1。</li>
 * </ul>
 */
public final class PlaylistIds {

    /** 与 旧 PlaylistWindow.ID_PATTERN（0.12.0 已删除） 完全一致的正则。 */
    private static final Pattern ID_PATTERN =
            Pattern.compile("(?:[?&]id=|/playlist/|^)(\\d{3,})");

    private PlaylistIds() {
    }

    /**
     * 从用户输入里解析歌单 id（支持纯数字、带 id= 的链接、#/playlist/ 链接）。
     *
     * @param text 用户输入；{@code null} 视为无效
     * @return 解析出的歌单 id；无法解析时 {@code -1}
     */
    public static long parse(String text) {
        if (text == null) {
            return -1L;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return -1L;
        }
        Matcher m = ID_PATTERN.matcher(s);
        if (!m.find()) {
            return -1L;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
