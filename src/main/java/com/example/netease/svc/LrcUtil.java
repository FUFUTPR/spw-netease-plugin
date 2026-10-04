package com.example.netease.svc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 文本处理：清洗 / 合并翻译与罗马音 / 时间戳平移（**纯函数、零依赖、零宿主交互**）。
 *
 * <p>宿主只认标准 LRC（{@code [mm:ss.xx]文本}），而网易云的应答里混着四种「非标准」：
 * yrc 的逐字标记 {@code [1234,567]}、{@code [offset:500]} 这类元数据、
 * {@code [mm:ss:xx]} 这种用冒号当小数点的写法、以及大量重复的时间戳行。
 * 本类负责把它们统一成宿主能吃的东西。</p>
 *
 * <p><b>时间戳精度保持原样</b>：输入 {@code [00:12.34]}（2 位小数）平移后仍是 2 位小数，
 * 不强行补成 3 位 —— 多轮写入/读取的往返不会让文本「越长越胖」，也便于人眼比对。</p>
 *
 * <p>线程安全：全部静态纯函数，无共享可变状态。</p>
 */
public final class LrcUtil {

    /** yrc 逐字标记：{@code [1234,567]} / {@code [1234,567,0]}。 */
    private static final Pattern WORD_TAG = Pattern.compile("\\[\\d{1,7},\\d{1,7}(?:,\\d{1,3})?\\]");

    /** 时间戳：{@code [mm:ss]}、{@code [mm:ss.xx]}、{@code [mm:ss:xx]}（冒号小数点是真实存在的野写法）。 */
    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** 元数据行：键必须以字母开头，否则 {@code [00:12.34]} 会被误判。 */
    private static final Pattern META = Pattern.compile("^\\[([A-Za-z]+):(.*)\\]$");

    /** 允许保留的元数据键（其余如 {@code [offset:]} 会干扰宿主，一律丢弃）。 */
    private static final Set<String> KEEP_META = Set.of("ti", "ar", "al", "by", "length");

    private LrcUtil() {
    }

    // ------------------------------------------------------------------ 清洗

    /**
     * 清洗成宿主可用的标准 LRC。
     *
     * <ul>
     *   <li>去 BOM、{@code \r\n}/{@code \r} 统一为 {@code \n}；</li>
     *   <li>去掉 yrc 逐字标记 {@code [xxxx,yyyy]}；</li>
     *   <li>{@code [mm:ss:xx]} 归一为 {@code [mm:ss.xx]}；</li>
     *   <li>只保留 {@code [ti:][ar:][al:][by:][length:]} 元数据，丢弃 {@code [offset:]} 等；</li>
     *   <li>丢掉既无时间戳又非元数据的行（宿主渲染不了）；</li>
     *   <li>按「时间戳序列 + 正文」去重；</li>
     *   <li>裁掉首尾/中间空行。</li>
     * </ul>
     *
     * @return 清洗后的文本；**无任何有效行时返回 {@code null}**（纯音乐/纯文本歌词走这里）
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        if (s.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>(64);
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder textOnly = new StringBuilder();
        for (String rawLine : s.split("\n", -1)) {
            String line = WORD_TAG.matcher(rawLine).replaceAll("").trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher meta = META.matcher(line);
            if (meta.matches()) {
                String key = meta.group(1).toLowerCase(Locale.ROOT);
                if (KEEP_META.contains(key) && seen.add("m:" + line)) {
                    out.add(line);
                }
                continue;
            }
            line = normalizeSeparators(line);
            if (!TS.matcher(line).find()) {
                continue;
            }
            if (seen.add("l:" + dedupeKey(line))) {
                out.add(line);
                textOnly.append(lyricText(line));
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        // 「[00:00.00]暂无歌词」这类占位文本不是歌词：按"无歌词"处理（绝不写进缓存文件）
        if (isInstrumentalHint(textOnly.toString())) {
            return null;
        }
        return String.join("\n", out);
    }

    /** 去掉一行里的全部时间戳，只留正文（占位文本判定用）。 */
    private static String lyricText(String line) {
        return TS.matcher(line).replaceAll("").trim();
    }

    /** {@code [mm:ss:xx]} → {@code [mm:ss.xx]}（不动无小数点的 {@code [mm:ss]}）。 */
    private static String normalizeSeparators(String line) {
        Matcher m = TS.matcher(line);
        StringBuilder sb = new StringBuilder(line.length());
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start()).append('[')
              .append(m.group(1)).append(':').append(m.group(2));
            if (m.group(3) != null) {
                sb.append('.').append(m.group(3));
            }
            sb.append(']');
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /** 去重键：全部时间戳 + 时间戳之后的正文。 */
    private static String dedupeKey(String line) {
        Matcher m = TS.matcher(line);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(m.group()).append('|');
            last = m.end();
        }
        return sb.append(line.substring(last).trim()).toString();
    }

    // ------------------------------------------------------------------ 判定

    /** 是否是「宿主能显示」的 LRC（至少一行带时间戳）。 */
    public static boolean looksLikeLrc(String text) {
        return clean(text) != null;
    }

    /** 带时间戳的行数。 */
    public static int timedLineCount(String lrc) {
        String c = clean(lrc);
        if (c == null) {
            return 0;
        }
        int n = 0;
        Matcher m = TS.matcher(c);
        for (String line : c.split("\n")) {
            if (TS.matcher(line).find()) {
                n++;
            }
        }
        return n;
    }

    /** 网易云对纯音乐 / 无歌词的「说明性」文本（"纯音乐，请欣赏"、"暂无歌词" 等）。 */
    public static boolean isInstrumentalHint(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return t.contains("纯音乐") || t.contains("没有填词") || t.contains("无歌词")
                || t.contains("暂无歌词") || t.contains("歌词暂无") || t.contains("暂无LRC")
                || t.contains("请欣赏") || t.contains("此歌曲为没有填词的纯音乐")
                || lower.contains("instrumental") || lower.contains("no lyric")
                || lower.contains("lyric not") || lower.contains("lyrics not");
    }

    /** 前若干行预览（写日志/自检用，绝不用于业务判断）。 */
    public static String preview(String text, int maxLines) {
        if (text == null) {
            return "<null>";
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(maxLines, lines.length); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 合并

    /**
     * 原文 + 翻译 + 罗马音合并。
     *
     * <p>译文/罗马音按**时间戳对齐**插到对应原文行的下一行（同时间戳重复的翻译行只取第一条）；
     * 与原文完全相同、或与译文相同的行不再重复输出；对不上主歌词的翻译行直接丢弃
     * （宁可不显示，也不让两句歌词错位）。</p>
     *
     * @return 合并后的标准 LRC；主歌词为空/纯音乐时返回 {@code null}
     */
    public static String merge(String lrc, String translatedLrc, String romajiLrc,
                               boolean withTranslation, boolean withRomaji) {
        String main = clean(lrc);
        if (main == null) {
            return null;
        }
        if (!withTranslation && !withRomaji) {
            return main;
        }
        Map<Long, String> trans = withTranslation ? parseTimedMap(translatedLrc) : Map.of();
        Map<Long, String> roma = withRomaji ? parseTimedMap(romajiLrc) : Map.of();
        if (trans.isEmpty() && roma.isEmpty()) {
            return main;
        }
        List<String> out = new ArrayList<>(64);
        for (String line : main.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            out.add(line);
            if (META.matcher(line).matches()) {
                continue;
            }
            List<String> prefixes = new ArrayList<>(1);
            Matcher m = TS.matcher(line);
            long firstMs = -1L;
            int textFrom = 0;
            while (m.find()) {
                prefixes.add(m.group());
                if (firstMs < 0L) {
                    firstMs = toMillis(m);
                }
                textFrom = m.end();
            }
            if (firstMs < 0L) {
                continue;
            }
            String body = line.substring(textFrom).trim();
            String t = trans.get(firstMs);
            if (t != null && !t.equals(body)) {
                for (String p : prefixes) {
                    out.add(p + t);
                }
            }
            String r = roma.get(firstMs);
            if (r != null && !r.equals(body) && !r.equals(t)) {
                for (String p : prefixes) {
                    out.add(p + r);
                }
            }
        }
        return out.isEmpty() ? null : String.join("\n", out);
    }

    /** 解析「时间戳 → 正文」映射（时间戳取每行的第一个）。 */
    private static Map<Long, String> parseTimedMap(String text) {
        Map<Long, String> map = new LinkedHashMap<>();
        String c = clean(text);
        if (c == null) {
            return map;
        }
        for (String line : c.split("\n")) {
            if (META.matcher(line).matches()) {
                continue;
            }
            Matcher m = TS.matcher(line);
            if (!m.find()) {
                continue;
            }
            String body = line.substring(m.end()).trim();
            if (!body.isEmpty()) {
                map.putIfAbsent(toMillis(m), body);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------ 平移

    /**
     * 时间戳整体平移（**写缓存时一次性完成**，缓存里存的就是最终文本）。
     *
     * <p>方向约定：{@code offsetMs > 0} = 歌词提前显示 = 时间戳**减小**
     * （用户觉得歌词比人声慢，就调大 offset）。</p>
     *
     * <p>精度保持：输入 2 位小数就输出 2 位小数，并做四舍五入 + 进位/借位
     * （{@code [00:59.97] - 50ms → [01:00.02]}、{@code [00:12.34] + 500ms → [00:11.84]}）；
     * 减到负数一律夹到 {@code [00:00.00]}。</p>
     */
    public static String shift(String lrc, int offsetMs) {
        String cleaned = clean(lrc);
        if (cleaned == null) {
            return null;
        }
        if (offsetMs == 0) {
            return cleaned;
        }
        String[] lines = cleaned.split("\n", -1);
        StringBuilder sb = new StringBuilder(cleaned.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(shiftLine(lines[i], offsetMs));
        }
        return sb.toString();
    }

    private static String shiftLine(String line, int offsetMs) {
        Matcher m = TS.matcher(line);
        StringBuilder sb = new StringBuilder(line.length() + 16);
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            int digits = m.group(3) == null ? 0 : m.group(3).length();
            long shifted = toMillis(m) - (long) offsetMs;
            if (shifted < 0L) {
                shifted = 0L;
            }
            sb.append(format(shifted, digits));
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /** 毫秒 → {@code [mm:ss(.f)]}，小数位数按 {@code digits} 补齐（先按该精度四舍五入）。 */
    public static String format(long ms, int digits) {
        long unit = switch (digits) {
            case 0 -> 1000L;
            case 1 -> 100L;
            case 2 -> 10L;
            default -> 1L;
        };
        long total = Math.max(0L, unit == 1L ? ms : ((ms + unit / 2L) / unit) * unit);
        long min = total / 60000L;
        long sec = (total / 1000L) % 60L;
        long frac = total % 1000L;
        StringBuilder sb = new StringBuilder(12);
        if (min < 10L) {
            sb.append('0');
        }
        sb.append(min).append(':');
        if (sec < 10L) {
            sb.append('0');
        }
        sb.append(sec);
        if (digits > 0) {
            long scaled = digits == 1 ? frac / 100L : digits == 2 ? frac / 10L : frac;
            String s = String.valueOf(scaled);
            sb.append('.');
            for (int i = s.length(); i < digits; i++) {
                sb.append('0');
            }
            sb.append(s);
        }
        return "[" + sb + "]";
    }

    /** 时间戳 → 毫秒（1/2/3 位小数分别按 ×100/×10/×1 解释）。 */
    private static long toMillis(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        long frac = 0L;
        if (m.group(3) != null) {
            String f = m.group(3);
            String use = f.length() > 3 ? f.substring(0, 3) : f;
            frac = Long.parseLong(use);
            if (use.length() == 1) {
                frac *= 100L;
            } else if (use.length() == 2) {
                frac *= 10L;
            }
        }
        return min * 60000L + sec * 1000L + frac;
    }
}
