package com.example.netease.core;

import java.util.List;
import java.util.Locale;

/**
 * 在线播放音质档位 —— **单一事实来源**（0.10.2）。
 *
 * <p>顺序 = 从低到高。宿主配置页的 {@code entries/entry_values}、{@link
 * com.example.netease.net.NeteaseApi} 的档位规范化、流服务的 level 参数解析、
 * 播放桥的「跟随配置」解析、歌单窗口的档位下拉，全部从这里取 —— 避免“加一档要改五份白名单、
 * 漏一份就静默漂移”的老问题。</p>
 *
 * <p>无损以上四档（0.10.2 新增）：{@code sky} 高清臻音、{@code jymaster} 超清母带、
 * {@code jyeffect} 沉浸声、{@code dolby} 杜比全景声。**有没有这一档由曲源与账号权限决定**：
 * 服务端没有时会自己回退（插件日志与 {@code NeteaseApi.lastUrlMeta()} 记录的是**实际**档位，
 * 不伪报请求档位，见 docs/00 §6.16.3 铁律 1）。</p>
 */
public final class Levels {

    /** 从低到高，全部可选档位。 */
    public static final List<String> ALL = List.of(
            "standard", "higher", "exhigh", "lossless", "hires",
            "sky", "jymaster", "jyeffect", "dolby");

    /**
     * 需要 {@code encodeType=flac} 的档位。
     *
     * <p>⚠ 这份必须跟着档位走：无损以上的档位若按 {@code encodeType=mp3} 请求，服务端会把它们
     * 一口气回退成 320k MP3（2026-09-30 实测），表现就是“选了母带还是 320k”。</p>
     */
    public static final List<String> FLAC = List.of(
            "lossless", "hires", "sky", "jymaster", "jyeffect");

    /** 缺省档位（配置缺省 / 无法识别时的回退）。 */
    public static final String DEFAULT = "lossless";

    private Levels() {
    }

    /** 规范化：已知档位返回小写规范名；未知返回 {@code null}（由调用方决定回退）。 */
    public static String canonical(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String v = level.trim().toLowerCase(Locale.ROOT);
        return ALL.contains(v) ? v : null;
    }

    /** 该档位是否需要 flac 容器。 */
    public static boolean isFlac(String level) {
        return level != null && FLAC.contains(level);
    }

    /**
     * 档位序号（越大约高）；未知档位返回 -1。
     *
     * <p>用于「逐级降档」：请求档位拿到的**实际档位**比请求低时，按序号往下一级一级试，
     * 保留拿到的最高档（见 {@link com.example.netease.net.NeteaseApi#songUrl}）。</p>
     */
    public static int index(String level) {
        return level == null ? -1 : ALL.indexOf(level.trim().toLowerCase(Locale.ROOT));
    }

    /** 下一档（低一级）；已经在最低档或未知返回 {@code null}。 */
    public static String nextBelow(String level) {
        int i = index(level);
        return i > 0 ? ALL.get(i - 1) : null;
    }

    /** a 是否严格高于 b（用于在降级链里挑最好的结果）。 */
    public static boolean higher(String a, String b) {
        return index(a) > index(b);
    }

    /**
     * 降了几档（请求档序号 − 实得档序号）：{@code 0} = 请求档与实得档一致，{@code >0} = 降了这么多档。
     *
     * <p>任一档位无法识别时返回 {@code -1} —— <b>不知道就说不知道</b>，不要返回 0 让上层把
     * 「没协商出来」当成「没降档」（A15 的日志与界面标注靠这个数）。</p>
     */
    public static int stepsDown(String requested, String actual) {
        int a = index(requested);
        int b = index(actual);
        return a < 0 || b < 0 ? -1 : Math.max(0, a - b);
    }
}
