package com.example.netease.net;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.netease.core.Json;
import com.example.netease.core.Levels;
import com.example.netease.core.PluginLog;
import com.example.netease.core.QrEncoder;

/**
 * 网易云音乐接口封装（免登录主路径优先）。
 *
 * <p>主路径：{@code GET /api/search/get/web}（无需 cookie、最稳）；取音频直链走 weapi + 匿名兜底。
 * 需要登录态/高音质的接口走 weapi（{@code POST /weapi/&lt;path&gt;/?csrf_token=}）。</p>
 *
 * <p>线程安全：全部静态方法无共享可变状态；cookie jar 与节流在 {@link Http} 内部同步。</p>
 *
 * <p>可见性说明：本类与 {@link Dto} / {@link NeteaseException} 是 net 包对外的公开面
 * （svc 层在另一个包里，必须 public 才能调用）；{@link WeapiCrypto} / {@link Http}
 * 是包内实现细节，刻意保持 package-private。二维码编码器走 core 包的公开工具类
 * {@link QrEncoder}（版本 1–20 / 纠错等级 M；0.11.30 起 net.QrCode 退役，编码器只有这一处实现）。</p>
 */
public final class NeteaseApi {

    /** 登录态 cookie 名。 */
    private static final String COOKIE_MUSIC_U = "MUSIC_U";
    /** 歌单曲目硬上限（0.2.5 起由 1000 提到 5000：真实歌单可达 2600+ 首，docs/00 §6.6）。 */
    private static final int MAX_PLAYLIST_TRACKS = 5000;
    /**
     * {@code v6/playlist/detail} 单次能返回的 trackIds 条数。
     *
     * <p>⚠️ 这是该端点的**单页**上限，不是歌单全量：0.2.4 及以前把 {@code n} 直接当曲目上限用，
     * 于是 2641 首的歌单只拿到 1000 首（见 docs/08 §19 缺陷 D1）。要全量必须走
     * {@link #playlistTrackPage} 分页。</p>
     */
    private static final int PLAYLIST_DETAIL_N = 1000;
    /** 歌单曲目分页单页条数（{@code track/all} 端点）。 */
    private static final int PLAYLIST_PAGE_SIZE = 500;
    /** 歌单曲目分页最多轮数（防御式上限：500 × 12 = 6000 ≥ {@link #MAX_PLAYLIST_TRACKS}）。 */
    private static final int MAX_TRACK_PAGES = 12;
    /** 歌单列表单次可请求的条数上限。 */
    private static final int MAX_PLAYLIST_LIMIT = 1000;
    /** 歌单列表自动翻页的最多轮数（防御式上限）。 */
    private static final int MAX_PLAYLIST_PAGES = 5;
    /** 曲目详情每批条数（v3/song/detail 的 c 参数不宜过大）。 */
    private static final int SONG_DETAIL_CHUNK = 500;
    /**
     * 批量直链每批首数（0.6.0「整单入队」）。
     *
     * <p>{@code song/enhance/player/url/v1} 的 {@code ids} 本是 JSON 数组，一次多首是网关支持的用法；
     * 100 是保守值（实测 30 首一次请求即可），既压掉 N 次握手，又不至于让单次响应过大。</p>
     */
    private static final int BATCH_URL_SIZE = 100;
    /** 「我喜欢的音乐」特制歌单的 specialType 取值。 */
    private static final int SPECIAL_TYPE_LIKED = 5;
    /**
     * 歌词正文可能出现容器字段（0.6.0 恢复，原样搬回旧版）。
     *
     * <p>按顺序取第一个非空：{@code lrc} 是标准 LRC，{@code klyric} 是逐字卡拉OK版，
     * {@code yrc} 是逐字 JSON 版。只要有一个能当正文就不必再往下试——所以在
     * {@link #lyric(long)} 里这个顺序同时也是「优先要标准 LRC」的策略。</p>
     */
    private static final String[] LYRIC_CONTAINERS = {"lrc", "klyric", "yrc"};
    /** 匿名会话是否已预热（拉过一次首页、cookie jar 里有匿名 cookie）。 */
    private static volatile boolean warmedUp;

    private NeteaseApi() {
    }

    // ---------------------------------------------------------------- 搜索（免登录）

    /**
     * 搜索歌曲（免登录路径）。
     *
     * @param keyword 关键词
     * @param limit   期望条数（会被裁剪到 1..100）
     * @return 结果列表，绝不返回 null
     */
    public static List<Dto.Song> search(String keyword, int limit) throws NeteaseException {
        List<Dto.Song> out = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return out;
        }
        int n = Math.max(1, Math.min(limit, 100));
        String url = Http.BASE + "/api/search/get/web?csrf_token=&s="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&type=1&offset=0&total=true&limit=" + n;
        String body = call("搜索", url, null);
        PluginLog.i("netease", "搜索 \"" + keyword + "\" → HTTP " + Http.lastStatus()
                + "，" + body.length() + " 字节");
        Object root = parse(Json.parse(body));
        List<Object> songs = Json.list(Json.get(root, "result.songs"), "");
        for (Object node : songs) {
            if (!(node instanceof Map)) {
                continue;
            }
            long id = Json.lng(node, "id", 0L);
            if (id <= 0L) {
                continue;
            }
            String name = Json.str(node, "name", "");
            String album = Json.str(node, "album.name", "");
            long durationMs = Json.lng(node, "duration", 0L);
            int fee = Json.integer(node, "fee", 0);
            StringBuilder artists = new StringBuilder();
            for (Object a : Json.list(Json.get(node, "artists"), "")) {
                String artistName = Json.str(a, "name", "");
                if (artistName.isEmpty()) {
                    continue;
                }
                if (artists.length() > 0) {
                    artists.append('/');
                }
                artists.append(artistName);
            }
            // privilege 缺失时按"有版权"乐观处理：真正的可用性以 songUrl 为准
            boolean playable = Json.bool(node, "privilege.st", true);
            // 封面原始 URL（0.6.0）：搜索命中用 al.picUrl，老式应答用 album.picUrl；缺失 = ""
            String picUrl = Json.str(node, "al.picUrl", "");
            if (picUrl.isEmpty()) {
                picUrl = Json.str(node, "album.picUrl", "");
            }
            out.add(new Dto.Song(id, name, artists.toString(), album, durationMs, fee, playable, picUrl));
        }
        return out;
    }

    // ---------------------------------------------------------------- 歌词（免登录）

    /**
     * 歌词；纯音乐/无歌词时返回空壳（三个字段均为 null）。
     *
     * <p><b>0.6.0 原样恢复旧版语义</b>（0.5.0 瘦身时连代码删除，见 docs/00 §6.3 修正 2）：</p>
     * <ol>
     *   <li>主路径必须用<b>老</b>接口 {@code /api/song/lyric}——实测 {@code /api/song/lyric/v1}
     *       的 {@code lrc.lyric} 返回的是 yrc 的 JSON 文本（{@code {"t":0,"c":[{"tx":"作词: "}]}}），
     *       直接落盘就是坏缓存；</li>
     *   <li>主路径失败 / {@code lrc.lyric} trim 后为空 → 试 v1 端点补翻译与罗马音；</li>
     *   <li>翻译/罗马音仍缺 → 剥一层 {@code data} 信封 + 补取一次带 {@code rv=-1} 的老接口；</li>
     *   <li>{@code lrc} 仍为 null → 兜底 {@code GET /lyric}，返回值可直接当正文。</li>
     * </ol>
     *
     * <p>线程：网络方法，绝不能在 EDT / 宿主回调线程里调（见 docs/00 §6.3 修正 4）。</p>
     *
     * @param songId 网易云歌曲 id
     * @return 歌词三件套；参数非法或纯音乐时三个字段均为 null，绝不返回 null
     */
    public static Dto.Lyric lyric(long songId) throws NeteaseException {
        if (songId <= 0L) {
            return new Dto.Lyric(null, null, null);
        }
        // 主路径用老接口：它的 lrc.lyric 是标准 [mm:ss.xx] 文本（缓存文件要的就是这个）。
        // 实测：/api/song/lyric/v1 的 lrc.lyric 返回的是 yrc 的 JSON（{"t":..,"c":[..]}），不能当正文。
        Object node = null;
        try {
            node = fetchLyric("/api/song/lyric?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1");
        } catch (NeteaseException e) {
            PluginLog.w("netease", "老歌词接口失败：" + safeMessage(e));
        }
        if (node == null || trimToNull(Json.str(node, "lrc.lyric", null)) == null) {
            try {
                Object v1 = fetchLyric("/api/song/lyric/v1?os=pc&id=" + songId
                        + "&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0");
                if (v1 != null) {
                    node = v1;
                }
            } catch (NeteaseException e) {
                // 只有「拿到过老接口应答」才允许吞掉 v1 的异常——否则整条链路一起断
                if (node == null) {
                    throw e;
                }
            }
        }
        if (node == null) {
            return new Dto.Lyric(null, null, null);
        }
        String lrc = firstText(node, LYRIC_CONTAINERS);
        String translated = joinText(node, "tlyric.lyric");
        String romaji = joinText(node, "romalrc.lyric");
        if (translated == null || romaji == null) {
            // 翻译/罗马音可能包在 data 信封里，补一次派生
            Object inner = Json.get(node, "data");
            if (inner instanceof Map) {
                if (translated == null) {
                    translated = joinText(inner, "tlyric.lyric");
                }
                if (romaji == null) {
                    romaji = joinText(inner, "romalrc.lyric");
                }
            }
        }
        if (translated == null || romaji == null) {
            // 老接口常把翻译藏在 /api/song/lyric 的同级 tlyric；v1 才有 romalrc
            try {
                Object extra = fetchLyric("/api/song/lyric?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1&rv=-1");
                if (translated == null) {
                    translated = joinText(extra, "tlyric.lyric");
                }
                if (romaji == null) {
                    romaji = joinText(extra, "romalrc.lyric");
                }
            } catch (NeteaseException e) {
                PluginLog.d("netease", "歌词补取失败（忽略）：" + safeMessage(e));
            }
        }
        if (lrc == null) {
            // 最后兜底：老 /lyric 接口（返回值可直接当正文）
            try {
                lrc = trimToNull(call("歌词(兜底)", Http.BASE + "/lyric?id=" + songId
                        + "&lv=-1&kv=-1&tv=-1", null));
            } catch (NeteaseException e) {
                PluginLog.d("netease", "兜底歌词接口也不可用：" + safeMessage(e));
            }
        }
        return new Dto.Lyric(lrc, translated, romaji);
    }

    /** 请求一个歌词端点并把 {@code data} 信封剥掉（现役 {@code parse} 返回 Object，不是旧版的 String）。 */
    private static Object fetchLyric(String pathAndQuery) throws NeteaseException {
        String body = call("歌词", Http.BASE + pathAndQuery, null);
        Object root = parse(Json.parse(body));
        // 有些应答是 code/data 信封，多剥一层
        Object inner = Json.get(root, "data");
        return inner instanceof Map ? inner : root;
    }

    // ---------------------------------------------------------------- 音频直链

    /**
     * 直链；无版权 / 需会员时返回 null。
     *
     * <p><b>匿名兜底</b>（见 docs/00 §6.3 修正 6）：未登录会话下 weapi
     * {@code song/enhance/player/url/v1} 对任何歌（含 fee==0）都返回空直链，
     * 所以 weapi 无果时回退公开匿名端点 {@code /api/song/enhance/player/url}。
     * 全链路不抛异常（除参数/协议级错误走 NeteaseException），拿不到就返回 null。</p>
     *
     * <p><b>逐级降档（0.10.3）</b>：用户选「超清母带」时，没有母带的曲子服务端**可能直接甩到 320k MP3**
     * （2026-09-30 实测），而不是退到「高清臻音」。所以这里在客户端补一条降级链：请求档位拿到的
     * **实际档位**低于请求时，按 {@link Levels#nextBelow} 一级一级往下试，保留拿到的最高档，
     * 直到「请求档位 == 实际档位」或链底。档位可用的曲子只发一次请求（首次即命中），成本可控。</p>
     */
    public static String songUrl(long songId, String level) throws NeteaseException {
        if (songId <= 0L) {
            return null;
        }
        String want = normalizeLevel(level);
        Link best = null;
        int tries = 0;
        for (String lv = want; lv != null; lv = Levels.nextBelow(lv)) {
            Link link = linkOnce(songId, lv);
            tries++;
            if (link != null && link.url() != null) {
                if (best == null || Levels.higher(link.actual(), best.actual())) {
                    best = link;
                }
                if (lv.equals(link.actual())) {
                    break;   // 请求档位 == 实际档位：命中，收工
                }
                PluginLog.d("netease", "降档链：请求=" + want + " → 试 " + lv + " 实际=" + link.actual()
                        + "（继续往下一档试）");
            }
            if (best != null && Levels.index(best.actual()) >= Levels.index(lv)) {
                // 已经拿到不低于当前请求档位的东西（服务端自有回退），再往下试不会更好
                break;
            }
        }
        if (best == null) {
            return null;
        }
        if (!want.equals(best.actual())) {
            // 只在「没拿到请求档位」时留一行 INFO：用户/排障都能看见实际给的是哪一档
            PluginLog.i("netease", "逐级降档：#" + songId + " 请求=" + want + " 实得=" + best.actual()
                    + "（试了 " + tries + " 档，" + best.format() + " " + best.br() + "bps）");
        }
        recordPicked(want, best);
        return best.url();
    }

    /**
     * 一次取链（weapi 主路径 + 匿名兜底），**不做降级**：返回直链与服务端自报的实际档位等元数据。
     *
     * <p>降级链由 {@link #songUrl} / {@link #songUrls} 负责，这里只负责“要这一档，服务端给什么”。</p>
     */
    private static Link linkOnce(long songId, String lv) {
        // 主路径：weapi（登录态下才能拿到高音质直链）
        try {
            Map<String, Object> payload = Json.newObject();
            payload.put("ids", "[" + songId + "]");
            payload.put("level", lv);
            // ⚠ encodeType 必须跟着档位走（Levels.isFlac）：无损以上若按 mp3 请求，
            //   服务端会把母带/臻音/沉浸声一口气回退成 320k MP3（2026-09-30 实测）。
            payload.put("encodeType", Levels.isFlac(lv) ? "flac" : "mp3");
            // 注意：core.Json.get 是点号路径，下标写 "data.0" 而不是 "data[0]"（后者会被当字面键，静默返回 null）
            Object data0 = Json.get(weapi("song/enhance/player/url/v1", payload), "data.0");
            String url = trimToNull(Json.str(data0, "url", null));
            if (url != null) {
                return link("weapi", data0, url);
            }
            PluginLog.d("netease", "weapi 直链为空（未登录/需会员），走匿名兜底：id=" + songId + " level=" + lv);
        } catch (NeteaseException e) {
            // 未登录时 weapi 本来就可能失败；不是致命错误，继续兜底
            PluginLog.d("netease", "weapi 直链失败（走匿名兜底）：" + e.getMessage());
        }
        // 兜底：公开匿名端点（免登录也能拿 128k/320k 直链）
        return anonymousLinkOnce(songId, lv);
    }

    /**
     * 批量直链（0.6.0「整单入队」用）：一次请求拿一组歌的直链。
     *
     * <p>返回**与入参等长、同序**的列表，拿不到直链的位置为 {@code null}——调用方据此跳过，
     * 绝不把空位置塞给宿主（历史教训：位置为空/不存在时宿主会去「开一个文件」，直接退回 Idle）。</p>
     *
     * <p>为什么要批量：整单播放要在一次点击内把窗口内每首都灌进宿主队列，逐首往返会把一次点击
     * 放大成 N 次握手；{@code song/enhance/player/url/v1} 的 {@code ids} 本就是 JSON 数组，
     * 网关支持一次多首（每 {@link #BATCH_URL_SIZE} 首一批）。批量里缺的那几首逐首走
     * {@link #songUrl}（含匿名端点兜底），仍拿不到就保持 {@code null}。</p>
     *
     * <p><b>逐级降档（0.10.3）</b>：每一批里「实际档位 ≠ 请求档位」的曲目，用 {@link Levels#nextBelow}
     * 的下一档**再整批问一次**（同一批一起问，$\le$ 链长次数），保留每首歌拿到的最高档 ——
     * 避免“选了母带，没母带的曲子被服务端甩到 320k”而错过它其实有的无损/臻音。</p>
     */
    public static List<String> songUrls(List<Long> songIds, String level) throws NeteaseException {
        List<String> out = new ArrayList<>();
        if (songIds == null || songIds.isEmpty()) {
            return out;
        }
        String lv = normalizeLevel(level);
        List<Long> uniq = new ArrayList<>();
        for (Long id : songIds) {
            if (id != null && id > 0L && !uniq.contains(id)) {
                uniq.add(id);
            }
        }
        Map<Long, Link> resolved = new LinkedHashMap<>();
        for (int from = 0; from < uniq.size(); from += BATCH_URL_SIZE) {
            List<Long> chunk = new ArrayList<>(uniq.subList(from, Math.min(uniq.size(), from + BATCH_URL_SIZE)));
            // 逐级降档：本批里「实际档位 ≠ 请求档位」的曲目，用下一档再批量问一次（同一批一起问，省往返）
            List<Long> remaining = new ArrayList<>(chunk);
            for (String lv2 = lv; lv2 != null && !remaining.isEmpty(); lv2 = Levels.nextBelow(lv2)) {
                Map<Long, Link> got;
                try {
                    got = batchUrls(remaining, lv2);
                } catch (NeteaseException e) {
                    // 批量失败不是致命错误：交给下面的逐首兜底
                    PluginLog.d("netease", "weapi 批量直链失败（转逐首兜底）：" + e.getMessage());
                    break;
                }
                List<Long> next = new ArrayList<>();
                for (Long id : remaining) {
                    Link link = got.get(id);
                    if (link == null) {
                        next.add(id);
                        continue;
                    }
                    Link prev = resolved.get(id);
                    if (prev == null || Levels.higher(link.actual(), prev.actual())) {
                        resolved.put(id, link);
                    }
                    if (!lv2.equals(link.actual())) {
                        next.add(id);   // 没命中这一档 → 下一轮用更低的档位再问
                    }
                }
                remaining = next;
            }
        }
        PluginLog.i("netease", "批量直链：请求=" + uniq.size() + " 首 首发命中=" + resolved.size()
                + " 档位=" + lv);
        int fallback = 0;
        for (Long id : uniq) {
            if (resolved.containsKey(id)) {
                continue;
            }
            String one = songUrl(id, level);
            if (one != null && !one.isEmpty()) {
                resolved.put(id, new Link(one, "?", 0L, 0L, urlExt(one), "weapi逐首"));
                fallback++;
            }
        }
        if (fallback > 0) {
            PluginLog.i("netease", "批量直链：逐首兜底补到 " + fallback + " 首（共 " + resolved.size() + " 首可用）");
        }
        for (Long id : songIds) {
            Link link = id == null ? null : resolved.get(id);
            out.add(link == null ? null : link.url());
        }
        return out;
    }

    /** weapi 批量直链（一批 ≤ {@link #BATCH_URL_SIZE} 首）：返回 id → {@link Link}（含实际档位）。 */
    private static Map<Long, Link> batchUrls(List<Long> ids, String lv) throws NeteaseException {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(ids.get(i));
        }
        arr.append(']');
        Map<String, Object> payload = Json.newObject();
        payload.put("ids", arr.toString());
        payload.put("level", lv);
        payload.put("encodeType", Levels.isFlac(lv) ? "flac" : "mp3");
        Object body = weapi("song/enhance/player/url/v1", payload);
        Map<Long, Link> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            // 注意：core.Json.get 是点号路径，下标写 "data.0" 而不是 "data[0]"
            Object d = Json.get(body, "data." + i);
            if (d == null) {
                continue;
            }
            String url = trimToNull(Json.str(d, "url", null));
            if (url == null) {
                continue;
            }
            long id = Json.lng(d, "id", 0L);
            out.put(id > 0L ? id : ids.get(i), link("weapi批量", d, url));
        }
        PluginLog.d("netease", "批量直链单批：" + ids.size() + " 首 请求=" + lv + " → 命中 " + out.size() + " 首");
        return out;
    }

    /** 匿名公开端点的 br 档位；未知档位按 128000。 */
    private static int anonymousBr(String level) {
        return switch (level) {
            case "higher" -> 192000;
            case "exhigh" -> 320000;
            // 无损以上的档位匿名端点一律拿不到（会员档），按合同降到无损
            case "lossless", "hires", "sky", "jymaster", "jyeffect", "dolby" -> 999000;
            default -> 128000;
        };
    }

    /** 匿名端点；失败/无直链返回 null（不抛）。 */
    private static Link anonymousLinkOnce(long songId, String requestedLevel) {
        int br = anonymousBr(requestedLevel);
        String body;
        try {
            body = call("匿名直链", anonymousUrl(songId, br), null);
        } catch (NeteaseException e) {
            PluginLog.w("netease", "匿名直链请求失败：id=" + songId + " :: " + e.getMessage());
            return null;
        }
        Link direct = parseAnonymousLink(body);
        if (direct != null) {
            logAnonymousHit(songId, br);
            return direct;
        }
        // 空会话（cookie jar 为空）时公开端点常返回空 data；拉一次首页拿匿名 cookie 再试一次
        PluginLog.d("netease", "匿名直链首次为空（可能是空会话）：id=" + songId + " 请求 " + br);
        if (!warmupSession()) {
            return null;
        }
        try {
            body = call("匿名直链(预热后)", anonymousUrl(songId, br), null);
        } catch (NeteaseException e) {
            PluginLog.w("netease", "匿名直链重试失败：id=" + songId + " :: " + e.getMessage());
            return null;
        }
        Link retry = parseAnonymousLink(body);
        if (retry == null) {
            PluginLog.w("netease", "匿名直链不可用：id=" + songId + " 请求 " + br + "（无版权或已下架）");
            return null;
        }
        logAnonymousHit(songId, br);
        return retry;
    }

    private static String anonymousUrl(long songId, int br) {
        return Http.BASE + "/api/song/enhance/player/url?id=" + songId
                + "&ids=%5B" + songId + "%5D&br=" + br;
    }

    /** 从匿名端点响应里取 data[0]；没有直链就返回 null。 */
    private static Link parseAnonymousLink(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            // 注意：core.Json.get 是点号路径，下标写 "data.0" 而不是 "data[0]"
            Object data0 = Json.get(parse(Json.parse(body)), "data.0");
            String url = trimToNull(Json.str(data0, "url", null));
            return url == null ? null : link("匿名", data0, url);
        } catch (Throwable t) {
            PluginLog.d("netease", "匿名直链响应解析失败：" + safeMessage(t));
            return null;
        }
    }

    /** 一次取链的结果：直链 + 服务端自报的实际档位/码率/字节/格式 + 来源。 */
    private record Link(String url, String actual, long br, long size, String format, String source) {
    }

    /** 把一次 weapi/匿名响应变成 {@link Link}（actual 取服务端自报的 {@code level}）。 */
    private static Link link(String source, Object data0, String url) {
        String actual = null;
        long br = 0L;
        long size = 0L;
        String format = null;
        try {
            actual = Json.str(data0, "level", null);
            br = Json.integer(data0, "br", 0);
            size = Json.lng(data0, "size", 0L);
            format = Json.str(data0, "type", urlExt(url));
        } catch (Throwable t) {
            PluginLog.d("netease", "直链元数据解析失败：" + safeMessage(t));
        }
        return new Link(url, actual == null ? "?" : actual.trim().toLowerCase(java.util.Locale.ROOT),
                br, size, format == null ? urlExt(url) : format, source);
    }

    /** 最近一次直链解析的**实际**档位元数据（诊断/自证用的非契约面，永不含完整 URL）。 */
    private static volatile String lastUrlMeta = "";

    /** 诊断面（非契约）：最近一次 {@link #songUrl} 实际拿到的档位/码率/大小，如 {@code 来源=weapi 请求=hires 实际档位=hires 码率=1900000 字节=48211323 格式=flac}。 */
    public static String lastUrlMeta() {
        return lastUrlMeta;
    }

    /**
     * 记录**最终选中**的那条直链的档位信息（客服问题的关键证据：请求 lossless 实际给 320k 必须能看见）。
     *
     * <p>只记档位/码率/字节/格式与扩展名，<b>不记 URL 本身</b>（直链含临时凭据）。</p>
     */
    private static void recordPicked(String requested, Link link) {
        lastUrlMeta = "来源=" + link.source()
                + (requested == null || requested.isBlank() ? "" : " 请求=" + requested)
                + " 实际档位=" + link.actual()
                + " 码率=" + link.br()
                + " 字节=" + link.size()
                + " 格式=" + link.format();
    }

    /** 直链扩展名（取不到 → mp3）。 */
    private static String urlExt(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            String path = u.getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot > 0 && dot < path.length() - 1) {
                    String e = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                    if (e.length() <= 5) {
                        return e;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 落到 mp3
        }
        return "mp3";
    }

    private static void logAnonymousHit(long songId, int br) {
        PluginLog.i("netease", "匿名直链命中：id=" + songId + " 请求 " + br);
    }

    /** 拉一次首页，让 cookie jar 收下匿名会话 cookie；成功（或已有会话）返回 true。 */
    private static boolean warmupSession() {
        if (warmedUp) {
            return true;
        }
        try {
            Http.get(Http.BASE + "/", null);
            warmedUp = true;
            PluginLog.d("netease", "已预热匿名会话（cookie jar 非空）");
            return true;
        } catch (Throwable t) {
            PluginLog.d("netease", "匿名会话预热失败（忽略）：" + safeMessage(t));
            return false;
        }
    }

    /** 档位别名 / 白名单 → weapi 的 level 取值（规范名由 {@link Levels} 单点维护）。 */
    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "exhigh";
        }
        String v = level.trim().toLowerCase(java.util.Locale.ROOT);
        String alias = switch (v) {
            case "128000", "128k", "low" -> "standard";
            case "192000", "192k" -> "higher";
            case "320000", "320k", "high" -> "exhigh";
            case "999000", "flac" -> "lossless";
            case "1900000", "hi-res" -> "hires";
            case "master" -> "jymaster";       // 超清母带
            case "immersive" -> "jyeffect";    // 沉浸声
            case "atmos" -> "dolby";           // 杜比全景声
            default -> v;
        };
        String canonical = Levels.canonical(alias);
        return canonical == null ? "exhigh" : canonical;
    }

    // ---------------------------------------------------------------- 二维码登录

    /**
     * 二维码里要编码的内容：登录页链接（扫码后由 App 打开）。
     *
     * <p>依据 `docs/03-网易云音乐接口调研.md` §4.1：`https://music.163.com/login?codekey=&lt;unikey&gt;`。
     * 包内可见，供 `tools\smoke-qr.ps1` 的自证探针断言「图里编的到底是什么」。</p>
     */
    static String qrContent(String key) {
        return Http.BASE + "/login?codekey=" + key;
    }

    /** 生成二维码会话（key 必给；PNG 离线生成，失败则为 null）。 */
    public static Dto.QrSession qrCreate() throws NeteaseException {
        // 0.11.32（需求①）：扫码路径以前从不带设备身份，unikey 与后续轮询是「两台设备」在说话。
        // 与手机号登录对齐（cellphoneLogin 早就这么做）：只补缺项、不覆盖服务端回写的值。
        Device.ensureOnJar();
        Map<String, Object> payload = Json.newObject();
        payload.put("type", 1);
        Object root = weapi("login/qrcode/unikey", payload);
        String key = trimToNull(Json.str(root, "unikey", null));
        if (key == null) {
            key = trimToNull(Json.str(root, "data.unikey", null));
        }
        if (key == null) {
            throw new NeteaseException("二维码 key 获取失败（响应缺少 unikey）");
        }
        // 二维码内容必须是登录页链接（docs/03 §4.1：把 "https://music.163.com/login?codekey=<unikey>"
        // 编码为二维码图片）。0.2.0 误用了接口自身的 /login/qrcode/<key>?type=1，扫码器认不出。
        // 0.11.30：编码器统一到 core.QrEncoder（M 等级；内容 = BASE(21) + "/login?codekey="(15) + 36 字符
        // unikey = 72 字节 ⇒ v5，模块边长 37，输出 PNG 约 225px）。
        String qrContent = qrContent(key);
        byte[] png = QrEncoder.png(qrContent, 232);
        return new Dto.QrSession(key, png);
    }

    /** 上一次已告警过的非标准轮询码（同类只告警一次，防日志被 1.5s 一条刷爆）。 */
    private static volatile int lastNotedCode = -1;

    /**
     * 只取状态码的老口径（0.2.x 起沿用）。新代码请用 {@link #qrPollFull(String)} —— 风控答复里
     * 带的验证地址只有那条路径能拿到（0.11.32 需求①）。
     */
    public static int qrPoll(String key) throws NeteaseException {
        return qrPollFull(key).code();
    }

    /**
     * 一次扫码轮询的完整结果（0.11.32 需求①）：状态码 + 行为验证地址 + 伺服端原话。
     *
     * <p>为什么需要它：真机取证（{@code build\diag\QrRiskProbe}，2026-10-02）发现匿名轮询接口
     * 在风控时会返回 <b>HTTP 200 + {@code code:-462}</b>，而 {@code data.url} 里就是完整的行为验证页
     * （{@code https://st.music.163.com/encrypt-pages?params=…&verifyType=40&verifyId=…&verifyToken=…}，
     * {@code data.blockText} = 「验证成功后，可进行下一步操作哦~」）。旧的 {@code qrPoll} 只返回 int，
     * 这个地址被就地丢弃 ⇒ 界面上只剩一句「未知状态」，用户只能干瞪眼。</p>
     *
     * @param code      800 过期 / 801 待扫 / 802 待确认 / 803 成功 / 8821 与 -462 风控要求行为验证
     * @param verifyUrl 行为验证页直链（没有 ⇒ 空串）
     * @param blockText 伺服端原话（没有 ⇒ 空串；**只含公开文案，不含凭据**）
     */
    public record QrPoll(int code, String verifyUrl, String blockText) {
        public QrPoll {
            verifyUrl = verifyUrl == null ? "" : verifyUrl;
            blockText = blockText == null ? "" : blockText;
        }

        /** 风控要求先完成行为验证（-462 与 8821 同族，只是通道不同）。 */
        public boolean risk() {
            return code == -462 || code == 8821;
        }
    }

    /**
     * 轮询扫码状态：800 过期 / 801 待扫 / 802 待确认 / 803 成功 / 8821·-462 风控要求行为验证码。
     *
     * <p><b>通道顺序（0.2.3 实测后定稿）：匿名 {@code /api/} 优先，weapi 兜底</b>（docs/00 §6.3 修正第 9 条）。
     * 0.2.2 曾假设「换 weapi 通道就能拿到 803」，0.2.3 用双通道对照探针
     * （{@code tools\smoke-qrlogin.ps1}，见 docs/14 §9.7）实测**证伪**：两条通道同码同步
     * —— 801 → 802 → 8821，8821 与通道无关，是伺服端对本次登录的风控拒绝
     * （原话「请切换其他登录方式或升级新版本再试」）。因此通道只按可用性排序，不再猜语义。
     * 0.11.32 再补一条对照：{@code -462} 同样与时序无关（四条轮询、两条通道、两个 key 全部同码），
     * 属于**账号/IP 级**风控。</p>
     *
     * <p>兜底路径 weapi 把「code != 200」当失败抛异常，而 800/801/802/8821 都是本接口的**正常状态**，
     * 所以那里用 {@link #codeFromMessage(String)} 把码从异常消息里取回来，不当作失败
     * （验证地址则由 {@link #takeVerifyUrl()} 从 {@code weapi} 的旁路取回）。</p>
     */
    public static QrPoll qrPollFull(String key) throws NeteaseException {
        if (key == null || key.isBlank()) {
            return new QrPoll(800, "", "");
        }
        Throwable anonymousFailure = null;
        // 主路径：匿名公开接口（docs/03 §4.1）—— 一个 GET 拿 JSON，不把状态码当异常
        try {
            String url = Http.BASE + "/api/login/qrcode/client/login?key="
                    + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&type=1";
            Object root = Json.parse(Http.get(url, null));
            int code = Json.integer(root, "code", Json.integer(root, "data.code", 0));
            if (code != 0) {
                String words = wordsOf(root);
                noteCode(code, words);
                return new QrPoll(code, verifyUrlOf(root), words);
            }
            PluginLog.w("netease", "二维码轮询响应缺少 code 字段（按 801 继续）");
            return new QrPoll(801, "", "");
        } catch (Throwable t) {
            anonymousFailure = t;
            PluginLog.d("netease", "二维码轮询走匿名接口失败，回退 weapi：" + safeMessage(t));
        }
        // 兜底：weapi 通道（与官网扫码页同一路状态机）
        Map<String, Object> payload = Json.newObject();
        payload.put("key", key);
        payload.put("type", 1);
        try {
            Object root = weapi("login/qrcode/client/login", payload);
            int code = Json.integer(root, "data.code", Json.integer(root, "code", 0));
            if (code != 0) {
                noteCode(code, "");
                return new QrPoll(code, takeVerifyUrl(), "");
            }
            PluginLog.w("netease", "二维码轮询响应缺少 code 字段（按 801 继续）");
            return new QrPoll(801, "", "");
        } catch (NeteaseException e) {
            Integer parsed = codeFromMessage(e.getMessage());
            if (parsed != null) {
                noteCode(parsed, "");
                // weapi 在抛异常前已把验证地址存进旁路（见 #weapi），这里取回来交给界面
                return new QrPoll(parsed, takeVerifyUrl(), "");
            }
            throw e;
        } catch (Throwable t) {
            PluginLog.d("netease", "二维码轮询 weapi 也失败：" + safeMessage(t));
            if (anonymousFailure != null) {
                throw new NeteaseException("二维码轮询失败：" + safeMessage(anonymousFailure));
            }
            throw new NeteaseException("二维码轮询失败：" + safeMessage(t));
        }
    }

    /**
     * 从响应体里捞行为验证地址（{@code data.url} → {@code data.verifyUrl} → {@code url}）。
     *
     * <p>0.11.32 从 {@link #weapi} 里抽出来共用：扫码轮询（{@link #qrPollFull}）与手机号登录走的是
     * 同一个风控答复结构，两边必须用同一个提取顺序，否则「登录能弹验证、扫码不弹」这种偏门 bug 又会回来。
     * 返回值一定非 null（不合格 ⇒ 空串）。</p>
     */
    private static String verifyUrlOf(Object root) {
        String v = sanitizeVerifyUrl(Json.str(root, "data.url", ""));
        if (v.isEmpty()) {
            v = sanitizeVerifyUrl(Json.str(root, "data.verifyUrl", ""));
        }
        if (v.isEmpty()) {
            v = sanitizeVerifyUrl(Json.str(root, "url", ""));
        }
        return v;
    }

    /** 伺服端人话：{@code message} → {@code data.blockText}（都空 ⇒ 空串）。 */
    private static String wordsOf(Object root) {
        String words = Json.str(root, "message", "");
        if (words == null || words.isBlank()) {
            words = Json.str(root, "data.blockText", "");
        }
        return words == null ? "" : words;
    }

    /**
     * 非标准轮询码只在**变化**时告警一次（标准码不记，状态机自己会记状态迁移）。
     *
     * @param message 伺服端原话（匿名通道才有；为空则省略）。**只含公开文案，不含凭据**。
     */
    private static void noteCode(int code, String message) {
        if (code == 800 || code == 801 || code == 802 || code == 803) {
            return;
        }
        if (code != lastNotedCode) {
            lastNotedCode = code;
            String extra = "";
            if (message != null && !message.isBlank()) {
                extra = "（伺服端原话：" + message.replace('\n', ' ').replace('\r', ' ') + "）";
            }
            PluginLog.w("netease", "二维码轮询返回非标准状态：" + code
                    + (code == 8821 || code == -462 ? "（网易云风控：需要行为验证码验证）" : "（未知码）") + extra);
        }
    }

    /**
     * 从 weapi 异常消息（形如 {@code … 失败：code=801 msg=…}）里取回轮询码；取不到返回 null。
     *
     * <p><b>包内可见</b>（0.11.29 起），只为了让离线探针 {@code tools/smoke/LoginCodeParseProbe} 能断言
     * 负数业务码（-462 风控）解析正确 —— 真机排障就是被这条规则坑了（坑 39）。</p>
     */
    static Integer codeFromMessage(String message) {
        if (message == null) {
            return null;
        }
        int at = message.indexOf("code=");
        if (at < 0) {
            return null;
        }
        int i = at + 5;
        // 0.11.29（坑 39）：风控码是负数（-462 = 需先完成行为验证）。0.11.28 及以前这里只吃连续数字，
        // 负号被跳过 ⇒ 一个数字都取不到 ⇒ 返回 null ⇒ LoginOutcome 把「风控拦截」误显成「网络请求失败」。
        boolean negative = i < message.length() && message.charAt(i) == '-';
        if (negative) {
            i++;
        }
        StringBuilder sb = new StringBuilder();
        while (i < message.length() && Character.isDigit(message.charAt(i))) {
            sb.append(message.charAt(i));
            i++;
        }
        if (sb.length() == 0) {
            return null;
        }
        try {
            int value = Integer.parseInt(sb.toString());
            return negative ? -value : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 从 weapi 异常消息（形如 {@code … 失败：code=503 msg=发送太频繁}）里取回伺服端原话；取不到返回空串。
     *
     * <p><b>包内可见</b>（0.11.29 起），供离线探针 {@code tools/smoke/LoginCodeParseProbe} 断言。</p>
     */
    static String msgFromMessage(String message) {
        if (message == null) {
            return "";
        }
        int at = message.indexOf(" msg=");
        if (at < 0) {
            return "";
        }
        return message.substring(at + 5);
    }

    /** 给 UI 的伺服端文案：脱敏 + 去换行 + 截断（绝不把原始 JSON / 凭据甩进界面）。 */
    private static String headless(String message) {
        String s = PluginLog.sanitize(message);
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    // ---------------------------------------------------------------- 账号

    /** 当前账号信息；未登录返回 null。 */
    public static Dto.Account accountInfo() throws NeteaseException {
        if (!isLoggedIn()) {
            return null;
        }
        Dto.Account base = parseAccount(weapi("nuser/account/get", Json.newObject()));
        if (base == null) {
            return null;
        }
        return new Dto.Account(base.nickname(), base.userId(), base.vip(), vipNameOf(base));
    }

    /**
     * 会员<b>档位名</b>（0.11.41，用户 m00221 ①：「会员那一行要显示对应的会员名称，不是光写『会员』」）。
     *
     * <p>{@code account.vipType} / {@code vipRights} 只是复合量（{@code vipType>0 || redVip || musicPackage}），
     * 判得出「有没有会员」但说不出「哪种会员」。档位名只有专属接口给得准：
     * {@code music-vip-membership/client/vip/info}（真机响应见 {@code tools/smoke/AccountNickProbe}：
     * {@code data.redVipLevel=7}、{@code data.redplus.vipLevel=7(vipCode 300)}、
     * {@code data.musicPackage.vipLevel=7(vipCode 220)}）。</p>
     *
     * <p>失败 / 认不出档位 ⇒ 空串（界面退成通用「会员」）。<b>绝不影响登录态</b>：档位是展示信息，
     * 不是鉴权信息，所以这里连异常都不往上抛。</p>
     */
    private static String vipNameOf(Dto.Account base) {
        if (!base.vip() || base.userId() <= 0L) {
            return "";
        }
        try {
            Map<String, Object> payload = Json.newObject();
            payload.put("userId", String.valueOf(base.userId()));
            return vipTierName(Json.get(weapi("music-vip-membership/client/vip/info", payload), "data"));
        } catch (Throwable t) {
            PluginLog.w("netease", "会员档位查询失败（登录态不受影响，档位退成通用「会员」）：" + safeMessage(t));
            return "";
        }
    }

    /**
     * {@code vip/info} 的 {@code data} → 档位名（纯函数，{@code SmokeHooks.accountSelfTest()} 离线自测）。
     *
     * <p>优先级 = 权益从高到低：<b>黑胶SVIP</b>（{@code redplus}，vipCode 300）→ <b>黑胶VIP</b>
     * （{@code redVipLevel}）→ <b>音乐包</b>（{@code musicPackage}，vipCode 220）；都没有 ⇒ 空串
     * （调用方退成通用「会员」）。</p>
     *
     * <p><b>只认这三个拿真机响应核对过的键</b>：{@code associator}（联合会员）/ {@code voiceBookVip}
     * （有声书）/ {@code albumVip}（专辑）的官方名字没有出处可查，宁可不猜 —— 认不出就显示通用「会员」，
     * 绝不把猜的名字写进界面。</p>
     */
    static String vipTierName(Object data) {
        long now = System.currentTimeMillis();
        if (tierActive(data, "redplus", now)) {
            return "黑胶SVIP";
        }
        if (Json.integer(data, "redVipLevel", 0) > 0) {
            return "黑胶VIP";
        }
        if (tierActive(data, "musicPackage", now)) {
            return "音乐包";
        }
        return "";
    }

    /** 子档位节点是否生效：{@code vipLevel > 0} 且未过期（{@code expireTime} 缺失/为 0 ⇒ 只看等级）。 */
    private static boolean tierActive(Object data, String path, long now) {
        Object node = Json.get(data, path);
        if (!(node instanceof Map)) {
            return false;
        }
        if (Json.integer(node, "vipLevel", 0) <= 0) {
            return false;
        }
        long expire = Json.lng(node, "expireTime", 0L);
        return expire <= 0L || expire > now;
    }

    /**
     * 把 {@code nuser/account/get} 的响应解析成 {@link Dto.Account}（0.11.40 从 {@link #accountInfo()} 抽出，
     * 纯函数、可离线自测 —— 见 {@code SmokeHooks.accountSelfTest()}）。
     *
     * <p><b>0.11.40（用户 m00221）：「账号名字」取 {@code profile.nickname}，不再先取 {@code account.userName}。</b>
     * 真机实测（2026-10-03，探针 {@code tools/smoke/AccountNickProbe}）同一份响应里两段字段并不相同：
     * {@code account.userName = 138_********0000}（登录名 = 打码手机号）、
     * {@code profile.nickname = TPR-A}（展示昵称）。旧实现先读 {@code account.userName} ⇒ 昵称被
     * {@code svc.SmsLogin.accountName} 判成「号码形态」而退成 {@code UID 123456789}，与「账号 ID」那行
     * 一模一样（用户原话：「账号名字给错误识别成 uid 了」）。取值顺序现为：
     * {@code profile.nickname} → {@code account.userName} → {@code account.nickname} → {@code profile.userName}
     * （后三个是旧口径的兜底，老信封行为不变）。</p>
     *
     * <p>信封兼容同旧版：account 依次试 {@code data.account} / {@code account} / {@code data}；
     * profile 依次试 {@code data.profile} / {@code profile}。两段都取不到 ⇒ 返回 {@code null}。</p>
     */
    static Dto.Account parseAccount(Object root) {
        Object account = firstMap(root, "data.account", "account", "data");
        Object profile = firstMap(root, "data.profile", "profile");
        if (account == null && profile == null) {
            return null;
        }
        long userId = Json.lng(account, "id", Json.lng(profile, "userId", 0L));
        String nickname = trimmed(Json.str(profile, "nickname", ""));
        if (nickname.isEmpty()) {
            nickname = trimmed(Json.str(account, "userName", ""));
        }
        if (nickname.isEmpty()) {
            nickname = trimmed(Json.str(account, "nickname", ""));
        }
        if (nickname.isEmpty()) {
            nickname = trimmed(Json.str(profile, "userName", ""));
        }
        // VIP 判据同旧版，只是两个节点都看：真实响应里 vipType 两段都有（account=11 / profile=110），
        // vipRights 只在其中一个节点出现 ⇒ 任一命中即为真。
        boolean vip = Math.max(Json.integer(account, "vipType", 0), Json.integer(profile, "vipType", 0)) > 0
                || Json.bool(account, "vipRights.redVip", false)
                || Json.bool(profile, "vipRights.redVip", false)
                || Json.bool(account, "vipRights.musicPackage", false)
                || Json.bool(profile, "vipRights.musicPackage", false)
                || Json.lng(account, "createTime", 0L) > 0L && "黑胶VIP".equals(nickname);
        return new Dto.Account(nickname, userId, vip, "");   // 档位名要另问一次 vip/info，见 vipNameOf(...)
    }

    /** 按点号路径依次取第一个 Map 节点；一个都不是 Map ⇒ null。 */
    private static Object firstMap(Object root, String... paths) {
        for (String path : paths) {
            Object node = Json.get(root, path);
            if (node instanceof Map) {
                return node;
            }
        }
        return null;
    }

    /** 去首尾空白；null ⇒ 空串（昵称是给人看的，前后空白一律不算内容）。 */
    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    /** 国家码（手机号登录固定 86）。 */
    private static final String COUNTRY_CODE = "86";

    /**
     * 登录结果（0.11.7，W1）：把「成不成」与**伺服端业务码**一起带出来。
     *
     * <p>为什么要带码：0.2.x 的布尔语义把 501（账号不存在）、502（验证码错）、
     * 503（太频繁）、8821（风控要行为验证码）全压成 {@code false}，UI 只能说「登录失败」——
     * 用户不知道该重发验证码、等一会、还是换登录方式（0.2.3 真机排障的痛点）。</p>
     *
     * @param ok      是否登录成功（{@code code == 200}）
     * @param code    伺服端业务码；网络异常 {@code -1}，本地参数非法 {@code -2}
     * @param message 伺服端原话（已脱敏），可能为空串；**不含任何凭据**
     * @param verifyUrl 风控答复里带的行为验证跳转地址（0.11.30）；没有时为空串，**不是凭据**
     */
    public record LoginOutcome(boolean ok, int code, String message, String verifyUrl) {

        /** 三参兼容构造（0.11.29 及以前的调用点）：默认没有验证链接。 */
        public LoginOutcome(boolean ok, int code, String message) {
            this(ok, code, message, "");
        }

        /**
         * 风控答复是否**真的带上了**行为验证地址（0.11.30 需求①）。
         *
         * <p>Solved 的重点：以前风控只给一句「稍后重试或改用扫码登录」，用户无从下手。带上了地址，
         * 对话框就能把它画成二维码让用户当场在手机上完成验证，再点「我已完成，重试」。</p>
         */
        public boolean hasVerify() {
            return verifyUrl != null && !verifyUrl.isBlank();
        }

        /** 风控要求行为验证（插件内无法完成：换登录方式或稍后再试）。8821 = 行为验证码；-462 = 需先完成行为验证。 */
        public boolean riskBlocked() {
            return code == 8821 || code == -462;
        }

        /** 账号不存在 / 验证码不对（501 账号不存在，502 验证码错）。 */
        public boolean badSecret() {
            return code == 501 || code == 502;
        }

        /** 请求太频繁（503 / 429）。 */
        public boolean tooFrequent() {
            return code == 503 || code == 429;
        }

        /** 一行中文说明（UI 文案直接可用；**不含任何凭据**）。0.11.30 起按需求④压到最短。 */
        public String hint() {
            if (ok) {
                return "登录成功";
            }
            switch (code) {
                case 501:
                    return "该手机号还没注册";
                case 502:
                    return "验证码不正确";
                case 400:
                    return "手机号格式被拒绝";
                case 503:
                case 429:
                    return "操作太频繁，请稍后再试";
                case 8821:
                    // 0.11.32：有验证地址 ⇒ 对话框窗内出二维码，一句话就够；没地址 ⇒ 指到扫码档（唯一的出路）。
                    return hasVerify() ? "网易云要求先完成安全验证（8821）" : "网易云要求先完成安全验证，请用扫码登录（8821）";
                case -462:
                    // 0.11.29（坑 39）：真机实测的短信登录失败码。伺服端 message 为 null、人话在 data.blockText，
                    // 以前因负数码解析缺陷被压成 code=-1 ⇒ 显示成「网络请求失败」，把用户引到查网络上。
                    // 0.11.30（需求①）：伺服端同时给了 data.url（行为验证页）⇒ 交给对话框当场完成验证。
                    return hasVerify() ? "网易云要求先完成安全验证（-462）" : "网易云要求先完成安全验证，请用扫码登录（-462）";
                case -3:
                    return "登录已通过，但凭据没能落盘（检查插件数据目录写权限）";
                case -2:
                    return "手机号或验证码为空";
                case -1:
                    return "网络请求失败";
                default:
                    return (message == null || message.isBlank()) ? ("登录失败（code=" + code + "）") : message;
            }
        }
    }

    /**
     * 手机号 + 短信验证码登录（0.11.7 起返回 {@link LoginOutcome}）。
     *
     * <p>与扫码登录同构：都打 {@code login/cellphone}，只是带 {@code captcha}。
     * 验证码只在内存中停留，绝不落日志、绝不落盘。登录前会把设备身份
     * （{@link Device}）补进 cookie jar ——「精简客户端」用自己的设备指纹，不再依赖本机客户端。</p>
     */
    public static LoginOutcome loginByCaptcha(String phone, String captcha) {
        if (phone == null || phone.isBlank() || captcha == null || captcha.isBlank()) {
            return new LoginOutcome(false, -2, "手机号或验证码为空");
        }
        return cellphoneLogin("验证码", captchaPayload(phone, captcha));
    }

    /**
     * {@code login/cellphone} 的载荷（验证码方式）：{@code phone} / {@code countrycode} / {@code captcha} /
     * {@code rememberLogin}。
     *
     * <p><b>包内可见</b>，只为了让离线探针 {@code tools/smoke/LoginShapeProbe} 能断言「请求构造正确」
     * （R2 要求：登录路径都要有真机或离线证据，验证码路径另有真机成功实证）。
     * 业务代码请走 {@link #loginByCaptcha(String, String)}——它才是唯一的生产路径。</p>
     */
    static Map<String, Object> captchaPayload(String phone, String captcha) {
        Map<String, Object> payload = Json.newObject();
        payload.put("phone", phone);
        payload.put("countrycode", COUNTRY_CODE);
        payload.put("captcha", captcha);                          // 验证码是凭据：只进内存与本次请求体
        payload.put("rememberLogin", "true");
        return payload;
    }

    /** {@code login/cellphone} 的生产路径（0.11.9 起只服务手机号 + 短信验证码；业务码一律经 {@link LoginOutcome} 返回）。 */
    private static LoginOutcome cellphoneLogin(String via, Map<String, Object> payload) {
        try {
            Device.ensureOnJar();          // 精简客户端设备身份（只补缺项，不覆盖服务端回写值）
            Object root = weapi("login/cellphone", payload);
            int code = Json.integer(root, "code", 200);
            String msg = headless(Json.str(root, "message", ""));
            // 0.11.30：200 也把验证地址取掉（顺手清理本线程的 ThreadLocal，不留残值）
            takeVerifyUrl();
            LoginOutcome out = new LoginOutcome(code == 200, code, msg);
            if (out.ok()) {
                PluginLog.i("netease", "手机号登录成功（" + via + "）：设备身份 " + Device.describe());
            } else {
                PluginLog.w("netease", "手机号登录失败（" + via + "）：code=" + code
                        + " msg=" + (msg.isEmpty() ? "(无)" : msg) + "；判读=" + out.hint());
            }
            return out;
        } catch (NeteaseException e) {
            // weapi 把非 200 抛成异常：从消息里把业务码捞回来，语义与上面完全一致
            Integer parsed = codeFromMessage(e.getMessage());
            int code = parsed == null ? -1 : parsed;
            String msg = headless(msgFromMessage(e.getMessage()));
            // 0.11.30（需求①）：风控答复带出的行为验证地址（没有则空串）
            String verify = takeVerifyUrl();
            LoginOutcome out = new LoginOutcome(false, code, msg, verify);
            PluginLog.w("netease", "手机号登录失败（" + via + "）：code=" + code
                    + " msg=" + (msg.isEmpty() ? "(无)" : msg)
                    + (out.hasVerify() ? " 行为验证=已带出地址" : "")
                    + "；判读=" + out.hint());
            return out;
        } catch (Throwable t) {
            PluginLog.w("netease", "手机号登录异常（" + via + "）：" + safeMessage(t));
            return new LoginOutcome(false, -1, safeMessage(t));
        }
    }

    /**
     * 发送短信验证码（0.2.4，docs/00 §6.3 修正第 11 条）。**会阻塞一次网络请求**，调用方负责线程。
     *
     * <p>返回业务码而不是 boolean：503（发送太频繁）、8821（风控：需要行为验证码验证）
     * 都必须能被上层区分并给出不同文案。手机号只用于请求，绝不落日志（日志只记掩码）与落盘。</p>
     *
     * @param phone 11 位中国大陆手机号
     * @return 业务码 + 伺服端原话（脱敏）；网络异常时为 {@code code=-1}
     */
    public static Dto.SmsResult smsSend(String phone) {
        if (phone == null || phone.isBlank()) {
            return new Dto.SmsResult(-2, "手机号为空");
        }
        Map<String, Object> payload = smsSendPayload(phone);
        try {
            Object root = weapi("sms/captcha/sent", payload);
            int code = Json.integer(root, "code", 200);
            String msg = headless(Json.str(root, "message", ""));
            PluginLog.i("netease", "短信验证码发送返回：code=" + code + (msg.isEmpty() ? "" : " msg=" + msg));
            return new Dto.SmsResult(code, msg);
        } catch (NeteaseException e) {
            Integer parsed = codeFromMessage(e.getMessage());
            int code = parsed == null ? -1 : parsed;
            String msg = headless(msgFromMessage(e.getMessage()));
            PluginLog.w("netease", "短信验证码发送失败：code=" + code + (msg.isEmpty() ? "" : " msg=" + msg)
                    + (code == 8821 ? "（网易云风控：需要行为验证码验证）" : ""));
            return new Dto.SmsResult(code, msg);
        } catch (Throwable t) {
            PluginLog.w("netease", "短信验证码发送异常：" + safeMessage(t));
            return new Dto.SmsResult(-1, safeMessage(t));
        }
    }

    /**
     * {@code sms/captcha/sent} 的载荷：{@code cellphone} / {@code ctcode}（国家码）。
     *
     * <p><b>包内可见</b>，只为了让离线探针 {@code tools/smoke/LoginShapeProbe} 断言
     * 发码请求构造正确（R2 的另一条路径）。生产路径仍是 {@link #smsSend(String)}。</p>
     */
    static Map<String, Object> smsSendPayload(String phone) {
        Map<String, Object> payload = Json.newObject();
        payload.put("cellphone", phone);
        payload.put("ctcode", COUNTRY_CODE);
        return payload;
    }

    /**
     * 手机号 + 短信验证码登录（**兼容入口**，0.2.4 的布尔语义）。**会阻塞一次网络请求**，调用方负责线程。
     *
     * <p>0.11.7 起新代码请用 {@link #loginByCaptcha(String, String)}（带业务码，UI 才区分得出
     * 502 验证码错 / 503 太频繁 / 8821 风控）。本方法保留给旧调用点与冒烟探针，语义等价于
     * {@code loginByCaptcha(...).ok()}；成功时 cookie 已在 {@code Http} 的 jar 里，由调用方落盘。</p>
     *
     * @param phone   11 位手机号
     * @param captcha 短信验证码（4~6 位数字）
     * @return 是否登录成功
     */
    public static boolean loginCellphoneCaptcha(String phone, String captcha) throws NeteaseException {
        return loginByCaptcha(phone, captcha).ok();
    }

    /** 只清 cookie，不额外发请求。 */
    public static void logout() {
        Http.setCookies(null);
    }

    /** 是否有登录态（本地判断 MUSIC_U，不发请求）。 */
    public static boolean isLoggedIn() {
        return Http.cookie(COOKIE_MUSIC_U) != null;
    }

    // ---------------------------------------------------------------- cookie 进出（P3，docs/00 §6.6）

    /**
     * 导出当前 cookie 串（{@code "k=v; k2=v2"}）；无 cookie 返回空串，绝不为 null。
     *
     * <p>⚠️ 只允许在 {@code netease-account} 线程调用（{@code Http} 的 cookie jar 是进程级静态单例）。</p>
     *
     * @return cookie 头样式字符串；无 cookie 时为空串（绝不返回 null）
     */
    public static String exportCookies() {
        String jar = Http.cookies();
        return jar == null ? "" : jar;
    }

    /**
     * 注入 cookie（{@code "k=v; k2=v2"}）；{@code null} / 空串 = 清空，非法片段忽略。
     *
     * <p>⚠️ 只允许在 {@code netease-account} 线程调用。日志只记「项数」，绝不出现 cookie 值。</p>
     *
     * @param cookieHeader cookie 头样式字符串；null/空白 = 清空
     */
    public static void importCookies(String cookieHeader) {
        Http.setCookies(cookieHeader);
        int count = countCookieEntries(Http.cookies());
        if (count == 0) {
            PluginLog.i("netease", "cookie 已清空（无登录态）");
        } else {
            // 只记名称与项数，值一律不落日志
            PluginLog.i("netease", "cookie 已注入：" + count + " 项（" + cookieNames(Http.cookies()) + "）");
        }
    }

    /** 统计 cookie 串里合法片段数（只看名字，不碰值）。 */
    private static int countCookieEntries(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String part : cookieHeader.split(";")) {
            int eq = part.trim().indexOf('=');
            if (eq > 0) {
                n++;
            }
        }
        return n;
    }

    /** cookie 名清单（形如 {@code MUSIC_U=****; __csrf=****}），供日志使用——**永不打印值**。 */
    static String cookieNames(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : cookieHeader.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(item, 0, eq).append("=****");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 歌单 / 每日推荐 / 我喜欢的音乐（P3，docs/00 §6.6）

    /**
     * 当前用户的歌单列表（含「我喜欢的音乐」）；<b>未登录返回空列表</b>，网络/解析失败抛 {@link NeteaseException}。
     *
     * <p>{@code uid <= 0} 时自动用当前登录账号的 uid（多一次 {@code accountInfo} 调用）。</p>
     *
     * @param uid   用户 id（{@code <=0} = 取当前登录账号）
     * @param limit 期望条数（{@code <=0} = 1000；内部夹到 1..1000，必要时自动翻页）
     * @return 歌单摘要列表；未登录/无数据 = 空列表，绝不返回 null
     */
    public static List<Dto.PlaylistBrief> userPlaylists(long uid, int limit) throws NeteaseException {
        List<Dto.PlaylistBrief> out = new ArrayList<>();
        if (!isLoggedIn()) {
            PluginLog.d("netease", "userPlaylists：未登录 → 返回空列表");
            return out;
        }
        long who = resolveUid(uid);
        if (who <= 0L) {
            PluginLog.w("netease", "userPlaylists：拿不到 uid（未登录或账号信息缺失）→ 空列表");
            return out;
        }
        int want = limit <= 0 ? MAX_PLAYLIST_LIMIT : Math.max(1, Math.min(limit, MAX_PLAYLIST_LIMIT));
        int offset = 0;
        for (int page = 0; page < MAX_PLAYLIST_PAGES; page++) {
            Map<String, Object> payload = Json.newObject();
            payload.put("uid", who);
            payload.put("limit", want);
            payload.put("offset", offset);
            payload.put("includeVideo", true);
            Object root = weapiFirst(payload, "user/playlist", "v1/user/playlist");
            List<Object> items = playlistArray(root);
            if (items.isEmpty()) {
                break;
            }
            for (Object node : items) {
                Dto.PlaylistBrief brief = toPlaylistBrief(node, who);
                if (brief != null) {
                    out.add(brief);
                }
            }
            boolean more = Json.bool(root, "more", false) || Json.bool(root, "data.more", false);
            if (!more || out.size() >= want) {
                break;
            }
            offset += items.size();
        }
        PluginLog.i("netease", "userPlaylists uid=" + who + " → " + out.size() + " 个歌单（HTTP "
                + Http.lastStatus() + "）");
        return out;
    }

    /**
     * 歌单完整曲目（内部自动分页，单次上限 {@link #MAX_PLAYLIST_TRACKS} 首）；私有歌单需登录。
     *
     * <p><b>取数策略</b>（修 0.2.4 的「2641 首只给 1000 首」缺陷）：</p>
     * <ol>
     *   <li><b>主路径</b>：{@code track/all} 分页（每页 {@link #PLAYLIST_PAGE_SIZE} 首）拉全量曲目 ——
     *       {@code v6/playlist/detail} 的 {@code trackIds} 只会给前 {@link #PLAYLIST_DETAIL_N} 个，
     *       <b>不能</b>当全量用；</li>
     *   <li>兜底 1：{@code trackIds} + {@code v3/song/detail} 分批（每批 500 首）；</li>
     *   <li>兜底 2：{@code detail} 响应里内嵌的 {@code tracks}。</li>
     * </ol>
     *
     * <p><b>P2 失败语义</b>（0.11.7+）：分页失败**绝不静默返回半页** —— 已经拿到部分页时抛
     * {@link NeteaseException} 中止本轮；只有「一首都还没拿到」才允许走兜底，且回退必须留下日志
     * （WARN）与来源说明（{@link #lastTrackNote()}，同步摘要应当引用它）。取回数与声明数不一致时
     * 同样 ERROR + 抛错：写库侧会先删该歌单旧关联行再重建，半份数据落到库里 = 用户歌单被截断。</p>
     *
     * @param playlistId 歌单 id（只吃纯 id；链接解析由 ui 层做）
     * @return 歌单（{@code tracks} 绝不为 null）
     * @throws NeteaseException id 非法 / 网络失败 / 响应缺 playlist / **曲目未取全**
     */
    public static Dto.Playlist playlist(long playlistId) throws NeteaseException {
        if (playlistId <= 0L) {
            throw new NeteaseException("歌单 id 非法：" + playlistId);
        }
        lastTrackNote = null;
        Map<String, Object> payload = Json.newObject();
        payload.put("id", playlistId);
        payload.put("n", PLAYLIST_DETAIL_N);
        payload.put("s", 8);
        DetailFetch df = detailSeam;
        Object root = df != null ? df.fetch(playlistId, payload)
                : weapiLoggedIn(payload, "v6/playlist/detail", "v3/playlist/detail");
        Object node = Json.get(root, "playlist");
        if (!(node instanceof Map)) {
            node = Json.get(root, "data.playlist");
        }
        if (!(node instanceof Map)) {
            throw new NeteaseException("歌单详情解析失败：响应缺少 playlist（id=" + playlistId + "）");
        }
        long id = Json.lng(node, "id", playlistId);
        String name = Json.str(node, "name", "");
        String creator = Json.str(node, "creator.nickname", "");
        int trackCount = Json.integer(node, "trackCount", 0);
        int want = trackCount > 0 ? Math.min(trackCount, MAX_PLAYLIST_TRACKS) : MAX_PLAYLIST_TRACKS;

        // ---- 主路径：track/all 分页 -------------------------------------------------
        // P2（0.11.7+）铁律：分页失败**绝不静默返回半页**。
        //   一首都还没拿到 ⇒ 允许走下面的兜底，但「回退」必须写进日志与结果摘要（lastTrackNote）；
        //   已经拿到部分页 ⇒ ERROR + 抛错中止本轮（写库侧会先删旧关联行再重建，半份 = 用户歌单被截断）。
        List<Dto.Song> tracks = new ArrayList<>();
        String source = null;
        int pages = 0;
        String firstPageError = null;
        if (trackAllDead) {
            PluginLog.i("netease", "曲目分页本会话已判定不可用（先前歌单第 1 页 offset=0 就给空页）"
                    + " ⇒ 本歌单直接走 trackIds/song/detail 兜底，不再白花一次分页请求"
                    + "（本轮曲目来源仍会在结果摘要里写明）");
            firstPageError = "本会话分页已判定不可用";
        }
        for (int page = 0; !trackAllDead && page < MAX_TRACK_PAGES && tracks.size() < want; page++) {
            int offset = tracks.size();
            int limit = Math.min(PLAYLIST_PAGE_SIZE, want - offset);
            List<Dto.Song> got;
            PageFetch pf = pageSeam;
            try {
                got = pf != null ? pf.fetch(id, limit, offset) : playlistTrackPage(id, limit, offset);
            } catch (NeteaseException e) {
                if (tracks.isEmpty()) {
                    PluginLog.w("netease", "曲目分页不可用（第 1 页就失败：offset=" + offset + " limit=" + limit
                            + "）：" + e.getMessage() + " ⇒ 回退 trackIds/song/detail 兜底"
                            + "（本轮曲目来源会变成兜底通道，结果摘要里会写明）");
                    firstPageError = e.getMessage();
                    trackAllDead = true;
                    break;
                }
                String msg = "曲目分页中断（第 " + (pages + 1) + " 页失败：offset=" + offset + " limit=" + limit
                        + "；已取回 " + tracks.size() + " 首 / 声明 " + trackCount + " 首）：" + e.getMessage()
                        + " ⇒ 拒绝静默返回半页数据，本轮中止（下次同步会重试）";
                PluginLog.e("netease", msg);
                throw new NeteaseException(msg);
            }
            if (got.isEmpty()) {
                if (tracks.size() < want) {
                    PluginLog.w("netease", "曲目分页提前给空页（第 " + (pages + 1) + " 页：offset=" + offset
                            + " limit=" + limit + "，已取回 " + tracks.size() + " 首 / 声明 " + trackCount
                            + " 首）—— 交给下面的完整性硬门判定");
                }
                if (tracks.isEmpty() && trackCount > 0) {
                    trackAllDead = true;
                    PluginLog.w("netease", "曲目分页本会话判定为不可用：第 1 页 offset=0 就给空页（歌单声明 "
                            + trackCount + " 首）⇒ 本会话后续歌单直接走 trackIds/song/detail 兜底"
                            + "（真机实证 track/all 从来没通；取回曲目与兜底通道逐条一致）");
                }
                break;
            }
            tracks.addAll(got);
            pages++;
            source = lastTrackPath;
            PluginLog.i("netease", "曲目分页第 " + pages + " 页：offset=" + offset + " limit=" + limit
                    + " → 取回 " + got.size() + " 首（通道 " + lastTrackPath + "，累计 " + tracks.size()
                    + " / " + want + "）");
            if (got.size() < limit) {
                break;                                  // 服务端已给到底
            }
        }

        // ---- 兜底 1：detail 的 trackIds（≤ PLAYLIST_DETAIL_N 首）--------------------
        if (tracks.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Object t : Json.list(Json.get(node, "trackIds"), "")) {
                long tid = Json.lng(t, "id", 0L);
                if (tid > 0L) {
                    ids.add(tid);
                }
                if (ids.size() >= want) {
                    break;
                }
            }
            if (!ids.isEmpty()) {
                PluginLog.w("netease", "曲目分页不可用 → 回退 trackIds（" + ids.size() + " 首；"
                        + (firstPageError == null ? "分页未给出数据" : "首因：" + firstPageError) + "）");
                tracks = songsByIds(ids);
                source = "trackIds 兜底（回退）";
                lastTrackPath = source;
                lastTrackNote = source + "：" + tracks.size() + " 首（detail trackIds 上限 "
                        + PLAYLIST_DETAIL_N + "）";
            }
        }

        // ---- 兜底 2：detail 里内嵌的 tracks ----------------------------------------
        if (tracks.isEmpty()) {
            Map<Long, Integer> priv = privilegeSt(root);
            for (Object t : Json.list(Json.get(node, "tracks"), "")) {
                Dto.Song s = toSong(t, priv);
                if (s != null) {
                    tracks.add(s);
                }
                if (tracks.size() >= want) {
                    break;
                }
            }
            if (!tracks.isEmpty()) {
                source = "detail.tracks 兜底（回退）";
                lastTrackPath = source;
                lastTrackNote = source + "：" + tracks.size() + " 首（分页与 trackIds 都不可用）";
            }
        }

        // ---- 完整性硬门（P2）：取回数必须与声明数一致，否则 ERROR + 抛错 ------------
        // 写库侧（NativeLibrary.writeOn）会先删该歌单的旧关联行再重建，所以「半份歌单」落到库里
        // = 用户歌单被截断。宁可中止本轮、下次同步重试，也不写半份。
        int floor = trackCount > 0 ? Math.min(trackCount, MAX_PLAYLIST_TRACKS) : 0;
        if (floor > 0 && tracks.size() < floor) {
            String msg = "歌单曲目未取全：声明 " + trackCount + " 首（单轮上限 " + MAX_PLAYLIST_TRACKS
                    + "，本应取回 " + floor + " 首），实际只有 " + tracks.size() + " 首（来源 "
                    + (source == null ? "无" : source) + "）⇒ 拒绝静默写半份歌单，本轮中止（下次同步会重试）";
            PluginLog.e("netease", msg);
            lastTrackNote = "曲目未取全：" + tracks.size() + " / " + floor + " 首（来源 "
                    + (source == null ? "无" : source) + "）";
            throw new NeteaseException(msg);
        }
        if (trackCount > MAX_PLAYLIST_TRACKS) {
            PluginLog.w("netease", "歌单声明 " + trackCount + " 首，超过单轮上限 " + MAX_PLAYLIST_TRACKS
                    + " 首 ⇒ 本轮只同步前 " + MAX_PLAYLIST_TRACKS + " 首（显式上限，不算半份）");
        }
        if (trackCount <= 0) {
            trackCount = tracks.size();
        }
        if (lastTrackNote == null) {
            lastTrackNote = (source == null ? "无曲目来源" : "曲目来源 " + source) + "：" + tracks.size() + " 首"
                    + (pages > 0 ? " / " + pages + " 页 × " + PLAYLIST_PAGE_SIZE : "");
        }
        PluginLog.i("netease", "歌单详情 id=" + id + "「" + name + "」声明 " + trackCount
                + " 首，实际取回 " + tracks.size() + " 首（来源 " + (source == null ? "无" : source)
                + "；分页 " + pages + " 页 / 页大小 " + PLAYLIST_PAGE_SIZE
                + "；" + lastTrackNote + "；HTTP " + Http.lastStatus() + "）");
        // 0.10.0：歌单封面 URL —— 宿主写库侧用它落「歌单封面文件」（见 svc/PlaylistCover）。
        // 缺失 = ""（绝不为 null）；是否加尺寸参数由写库侧决定，这里保持原始 URL。
        String coverUrl = Json.str(node, "coverImgUrl", "");
        return new Dto.Playlist(id, name, creator, trackCount, tracks, coverUrl);
    }

    /** 歌单曲目分页最近一次命中的通道（自证/排障用的非契约面）。 */
    private static volatile String lastTrackPath = "";

    /**
     * 0.11.8（A11）：本会话「{@code playlist/track/all} 分页已判定不可用」的粘性标记。
     *
     * <p>真机实证（2026-10-01 冷轮 {@code plugin-20261001.log}）：4 个歌单**每一个**都在第 1 页
     * {@code offset=0} 就拿到空页 ⇒ 每次都先白花一次分页请求（含 300 ms 全局节流 + 一次往返）、
     * 再落到 {@code trackIds} 兜底。4 次白花串起来就是 8–12 s 的纯浪费，而这段正好落在 A11
     * 「封面首轮全量 ≤ 60 s」的关键路径上（清单到得越晚，封面池空转越久）。</p>
     *
     * <p><b>用户可见影响为零</b>：兜底通道取回的是同一批曲目，且 {@code lastTrackNote} 照旧写明
     * 「trackIds 兜底（回退）」—— 只是不再重复踩同一个坑。判定条件很窄：仅当「分页第 1 页
     * offset=0 什么都没给、而歌单声明了曲目（trackCount > 0）」时才置位。</p>
     */
    private static volatile boolean trackAllDead;

    // ---- 测试缝（package-private、非契约；生产恒为 null）---------------------------
    // 用途：让离线回归（tools\smoke\PagingProbe.java）在**没有网络**的情况下也能复现
    // 「分页中断」「三通道全失败 → 回退」两类故障 —— P2 铁律「绝不静默返回半页」必须能被
    // 离线回归覆盖，否则线上只能靠运气验证。生产路径（两个 seam 均为 null）行为完全不变。
    interface DetailFetch {
        Object fetch(long playlistId, Map<String, Object> payload) throws NeteaseException;
    }

    interface PageFetch {
        List<Dto.Song> fetch(long playlistId, int limit, int offset) throws NeteaseException;
    }

    static volatile DetailFetch detailSeam;
    static volatile PageFetch pageSeam;

    /** 歌单曲目分页最近一次的来源说明（含回退通道；非契约诊断面，同步摘要可直接引用）。 */
    private static volatile String lastTrackNote = null;

    /** 诊断面（非契约）：最近一次曲目分页命中的通道名，如 {@code weapi track/all}。 */
    public static String lastTrackPath() {
        return lastTrackPath;
    }

    /**
     * 诊断面（非契约）：最近一次取歌单的**曲目来源说明**，例如
     * {@code 曲目来源 weapi track/all：3261 首 / 7 页 × 500} 或
     * {@code trackIds 兜底（回退）：1000 首（detail trackIds 上限 1000）}。
     *
     * <p>P2：回退通道必须能被用户看到 —— 同步摘要（{@code native-lib} 的结果行）应当引用本串。</p>
     */
    public static String lastTrackNote() {
        return lastTrackNote;
    }

    /**
     * 歌单曲目分页（{@code track/all}）。
     *
     * <p>通道依次尝试：weapi {@code v6/playlist/track/all} → weapi {@code playlist/track/all}
     * → 公开 {@code GET /api/v6/playlist/track/all}；全失败抛 {@link NeteaseException}，
     * 由 {@link #playlist} 兜底 —— 这里**绝不**静默返回半页数据。</p>
     */
    private static List<Dto.Song> playlistTrackPage(long playlistId, int limit, int offset) throws NeteaseException {
        Object root = null;
        String via = null;
        try {
            Map<String, Object> p = Json.newObject();
            p.put("id", playlistId);
            p.put("limit", limit);
            p.put("offset", offset);
            p.put("n", limit);
            p.put("total", true);
            root = weapiLoggedIn(p, "v6/playlist/track/all", "playlist/track/all");
            via = "weapi track/all";
        } catch (NeteaseException e) {
            PluginLog.d("netease", "weapi 曲目分页不可用（走公开端点）：" + e.getMessage());
        }
        if (root == null) {
            String body = call("曲目分页", Http.BASE + "/api/v6/playlist/track/all?id=" + playlistId
                    + "&limit=" + limit + "&offset=" + offset + "&n=" + limit + "&total=true", null);
            root = parse(Json.parse(body));
            via = "公开 track/all";
        }
        Map<Long, Integer> priv = privilegeSt(root);
        List<Dto.Song> out = new ArrayList<>();
        for (Object t : Json.list(Json.get(root, "songs"), "")) {
            Dto.Song s = toSong(t, priv);
            if (s != null) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            for (Object t : Json.list(Json.get(root, "playlist.tracks"), "")) {
                Dto.Song s = toSong(t, priv);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        if (!out.isEmpty()) {
            lastTrackPath = via;
        }
        return out;
    }

    /**
     * 每日推荐曲目（需登录）。
     *
     * @return 曲目列表（无数据 = 空列表，绝不 null）
     * @throws NeteaseException 未登录（message 含「未登录」）/ 网络失败
     */
    public static List<Dto.Song> dailySongs() throws NeteaseException {
        if (!isLoggedIn()) {
            throw new NeteaseException("未登录：每日推荐需要登录态");
        }
        Map<String, Object> payload = Json.newObject();
        payload.put("afresh", false);
        Object root = weapiLoggedIn(payload, "v1/discovery/recommend/songs", "v2/discovery/recommend/songs");
        List<Object> items = Json.list(Json.get(root, "data.dailySongs"), "");
        if (items.isEmpty()) {
            items = Json.list(Json.get(root, "recommend"), "");
        }
        if (items.isEmpty()) {
            items = Json.list(Json.get(root, "data.recommend"), "");
        }
        Map<Long, Integer> priv = privilegeSt(root);
        List<Dto.Song> out = new ArrayList<>();
        for (Object node : items) {
            Dto.Song s = toSong(node, priv);
            if (s != null) {
                out.add(s);
            }
        }
        PluginLog.i("netease", "每日推荐 → " + out.size() + " 首（HTTP " + Http.lastStatus() + "）");
        return out;
    }

    /**
     * 我喜欢的音乐（需登录）：内部解析特制歌单后返回完整曲目。
     *
     * <p>主路径 = 从 {@code user/playlist} 里找 {@code specialType==5}（或名字含「喜欢的音乐」）
     * 的特制歌单再取全曲；回退 = 公开 {@code /api/song/likelist} 拿 id 列表后分批取详情。</p>
     *
     * @param uid 用户 id（{@code <=0} = 取当前登录账号）
     * @return 曲目列表（无数据 = 空列表，绝不 null）
     * @throws NeteaseException 未登录（message 含「未登录」）/ 网络失败
     */
    public static List<Dto.Song> likedSongs(long uid) throws NeteaseException {
        if (!isLoggedIn()) {
            throw new NeteaseException("未登录：我喜欢的音乐需要登录态");
        }
        long who = resolveUid(uid);
        long lovedId = specialLikedPlaylistId(who);
        if (lovedId > 0L) {
            try {
                Dto.Playlist pl = playlist(lovedId);
                if (!pl.tracks().isEmpty()) {
                    PluginLog.i("netease", "我喜欢的音乐：特制歌单 id=" + lovedId + " → "
                            + pl.tracks().size() + " 首");
                    return pl.tracks();
                }
                PluginLog.d("netease", "特制歌单 id=" + lovedId + " 取回 0 首，走 likelist 回退");
            } catch (NeteaseException e) {
                PluginLog.d("netease", "特制歌单取曲失败（走 likelist 回退）：" + e.getMessage());
            }
        }
        List<Long> ids = likedTrackIds(who);
        List<Dto.Song> out = songsByIds(ids);
        PluginLog.i("netease", "我喜欢的音乐（likelist 回退）：uid=" + who + " → " + out.size() + " 首");
        return out;
    }

    // ---- 歌单系列内部工具 ----

    /** {@code uid <= 0} 时取当前登录账号的 uid；拿不到返回 0（绝不抛）。 */
    private static long resolveUid(long uid) {
        if (uid > 0L) {
            return uid;
        }
        try {
            Dto.Account acc = accountInfo();
            return acc == null ? 0L : acc.userId();
        } catch (Throwable t) {
            PluginLog.d("netease", "uid 解析失败（按 0 处理）：" + safeMessage(t));
            return 0L;
        }
    }

    /** 依次尝试多个 weapi 路径，返回首个成功（code==200）的响应根；全失败抛最后一次异常。 */
    private static Object weapiFirst(Map<String, Object> payload, String... paths) throws NeteaseException {
        NeteaseException last = null;
        for (String p : paths) {
            try {
                return weapi(p, payload);
            } catch (NeteaseException e) {
                last = e;
                PluginLog.d("netease", "weapi/" + p + " 失败，尝试下一个路径");
            }
        }
        throw last != null ? last : new NeteaseException("weapi 请求失败（无可用路径）");
    }

    /** 与 {@link #weapiFirst} 同，但把「登录态失效」归一成 message 含「未登录」的可读异常。 */
    private static Object weapiLoggedIn(Map<String, Object> payload, String... paths) throws NeteaseException {
        try {
            return weapiFirst(payload, paths);
        } catch (NeteaseException e) {
            throw relogin(e);
        }
    }

    /** 把 weapi 的 code=301/250（未登录 / 登录态失效）归一成 message 含「未登录」的可读异常。 */
    private static NeteaseException relogin(NeteaseException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("code=301") || m.contains("code=250")) {
            return new NeteaseException("未登录：登录态已失效（" + PluginLog.sanitize(m) + "）", e);
        }
        return e;
    }

    /** 兼容 {@code playlist} / {@code data.playlist} 两种信封。 */
    private static List<Object> playlistArray(Object root) {
        List<Object> items = Json.list(Json.get(root, "playlist"), "");
        if (items.isEmpty()) {
            items = Json.list(Json.get(root, "data.playlist"), "");
        }
        return items;
    }

    /**
     * 歌单节点 → {@link Dto.PlaylistBrief}。
     *
     * <p>0.6.0 起多带一个 {@code uid}，用来判定 {@code mine}：判据是
     * 「{@code creator.userId == 当前 uid}」或「响应里 {@code subscribed == false}」——
     * 后者是「没订阅 = 自己建的」。两个判据都要，因为「我收藏的歌单」在
     * {@code user/playlist} 里 {@code creator.userId} 是原主的 id，
     * 光看 creator 会把收藏全判成 mine；而 {@code subscribed} 在某些老响应里缺失，
     * 缺省按 {@code true}（订阅）处理才不会把别人的歌单当成自己的。</p>
     *
     * @param node 歌单元数据节点
     * @param uid  当前登录用户 id（{@code <=0} 表示未知 → mine 恒为 false）
     */
    private static Dto.PlaylistBrief toPlaylistBrief(Object node, long uid) {
        if (!(node instanceof Map)) {
            return null;
        }
        long id = Json.lng(node, "id", 0L);
        if (id <= 0L) {
            return null;
        }
        long creatorId = Json.lng(node, "creator.userId", 0L);
        boolean mine = uid > 0L && (creatorId == uid || !Json.bool(node, "subscribed", true));
        return new Dto.PlaylistBrief(id,
                Json.str(node, "name", ""),
                Json.str(node, "creator.nickname", ""),
                Json.integer(node, "trackCount", 0),
                Json.lng(node, "playCount", 0L),
                mine);
    }

    /** 「我喜欢的音乐」特制歌单 id；找不到返回 0（绝不抛）。 */
    private static long specialLikedPlaylistId(long uid) {
        if (uid <= 0L) {
            return 0L;
        }
        try {
            Map<String, Object> payload = Json.newObject();
            payload.put("uid", uid);
            payload.put("limit", MAX_PLAYLIST_LIMIT);
            payload.put("offset", 0);
            payload.put("includeVideo", true);
            List<Object> items = playlistArray(weapiFirst(payload, "user/playlist", "v1/user/playlist"));
            for (Object node : items) {
                if (Json.integer(node, "specialType", 0) == SPECIAL_TYPE_LIKED) {
                    return Json.lng(node, "id", 0L);
                }
            }
            for (Object node : items) {
                if (Json.str(node, "name", "").contains("喜欢的音乐")) {
                    return Json.lng(node, "id", 0L);
                }
            }
        } catch (Throwable t) {
            PluginLog.d("netease", "特制歌单解析失败（忽略）：" + safeMessage(t));
        }
        return 0L;
    }

    /** 公开 likelist 端点的 id 列表；失败返回空列表（绝不抛）。 */
    private static List<Long> likedTrackIds(long uid) {
        List<Long> out = new ArrayList<>();
        if (uid <= 0L) {
            return out;
        }
        String body;
        try {
            body = call("喜欢列表", Http.BASE + "/api/song/likelist?uid=" + uid + "&csrf_token="
                    + java.util.Objects.toString(Http.cookie("__csrf"), ""), null);
        } catch (NeteaseException e) {
            PluginLog.d("netease", "likelist 请求失败（按空处理）：" + e.getMessage());
            return out;
        }
        try {
            Object root = parse(Json.parse(body));
            for (Object v : Json.list(Json.get(root, "ids"), "")) {
                long id = asLong(v);
                if (id > 0L) {
                    out.add(id);
                }
                if (out.size() >= MAX_PLAYLIST_TRACKS) {
                    break;
                }
            }
            if (out.isEmpty()) {
                for (Object t : Json.list(Json.get(root, "playlist.trackIds"), "")) {
                    long id = Json.lng(t, "id", 0L);
                    if (id > 0L) {
                        out.add(id);
                    }
                }
            }
        } catch (Throwable t) {
            PluginLog.d("netease", "likelist 响应解析失败：" + safeMessage(t));
        }
        return out;
    }

    /** 按 id 批量取曲目详情（每批 500 首，内部上限 1000 首）。 */
    private static List<Dto.Song> songsByIds(List<Long> ids) throws NeteaseException {
        List<Dto.Song> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        int cap = Math.min(ids.size(), MAX_PLAYLIST_TRACKS);
        for (int from = 0; from < cap; from += SONG_DETAIL_CHUNK) {
            int to = Math.min(from + SONG_DETAIL_CHUNK, cap);
            StringBuilder c = new StringBuilder("[");
            for (int i = from; i < to; i++) {
                if (i > from) {
                    c.append(',');
                }
                c.append("{\"id\":").append(ids.get(i)).append('}');
            }
            c.append(']');
            Map<String, Object> payload = Json.newObject();
            payload.put("c", c.toString());
            Object root = weapi("v3/song/detail", payload);
            Map<Long, Integer> priv = privilegeSt(root);
            for (Object node : Json.list(Json.get(root, "songs"), "")) {
                Dto.Song s = toSong(node, priv);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 响应里 {@code privileges[]} / {@code data.privileges[]} 的 id → st 映射（st<0 = 无版权）。 */
    private static Map<Long, Integer> privilegeSt(Object root) {
        Map<Long, Integer> out = new java.util.LinkedHashMap<>();
        collectPrivileges(Json.list(Json.get(root, "privileges"), ""), out);
        collectPrivileges(Json.list(Json.get(root, "data.privileges"), ""), out);
        return out;
    }

    private static void collectPrivileges(List<Object> nodes, Map<Long, Integer> out) {
        for (Object node : nodes) {
            long id = Json.lng(node, "id", 0L);
            if (id > 0L) {
                out.put(id, Json.integer(node, "st", 0));
            }
        }
    }

    /** 歌单/推荐节点 → {@link Dto.Song}（兼容新版 ar/al/dt 与老版 artists/album/duration）。 */
    private static Dto.Song toSong(Object node, Map<Long, Integer> privileges) {
        if (!(node instanceof Map)) {
            return null;
        }
        long id = Json.lng(node, "id", 0L);
        if (id <= 0L) {
            return null;
        }
        String artists = joinArtists(Json.get(node, "ar"));
        if (artists.isEmpty()) {
            artists = joinArtists(Json.get(node, "artists"));
        }
        String album = Json.str(node, "al.name", "");
        if (album.isEmpty()) {
            album = Json.str(node, "album.name", "");
        }
        // 封面原始 URL（0.6.0）：优先新版 al.picUrl，回退老版 album.picUrl；缺失 = ""（绝不为 null）
        String picUrl = Json.str(node, "al.picUrl", "");
        if (picUrl.isEmpty()) {
            picUrl = Json.str(node, "album.picUrl", "");
        }
        long durationMs = Json.lng(node, "dt", 0L);
        if (durationMs <= 0L) {
            durationMs = Json.lng(node, "duration", 0L);
        }
        Integer st = privileges == null ? null : privileges.get(id);
        if (st == null) {
            Object own = Json.get(node, "privilege.st");
            if (own instanceof Number n) {
                st = n.intValue();
            }
        }
        if (st == null) {
            Object own = Json.get(node, "st");
            if (own instanceof Number n) {
                st = n.intValue();
            }
        }
        // privilege 缺失时按「有版权」乐观处理（与 search 一致，真正可用性以 songUrl 为准）
        boolean playable = st == null || st >= 0;
        return new Dto.Song(id, Json.str(node, "name", ""), artists, album,
                durationMs, Json.integer(node, "fee", 0), playable, picUrl);
    }

    /** 歌手数组 → {@code "A/B"}（空数组返回空串）。 */
    private static String joinArtists(Object arrayNode) {
        StringBuilder sb = new StringBuilder();
        for (Object a : Json.list(arrayNode, "")) {
            String name = Json.str(a, "name", "");
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** 原始 JSON 数字（可能被解析成 Double）→ long。 */
    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 最近一次 weapi 失败时伺服端给的行为验证跳转地址（**同线程**；每次请求前清空）。
     *
     * <p>0.11.30（需求①）：风控答复 {@code -462} 里带 {@code data.url} = 行为验证页。它以前在
     * {@link #weapi} 里被直接丢掉，上层只能显示一句「稍后重试」——用户无从下手。这里把它捞出来，
     * 由 {@code cellphoneLogin} 装进 {@link LoginOutcome#verifyUrl()}，登录对话框就能把它画成二维码，
     * 让用户当场在手机上完成验证。</p>
     */
    private static final ThreadLocal<String> LAST_VERIFY_URL = new ThreadLocal<>();

    /** 取走并清掉本线程最后一次的验证地址（取走即清，防止串到下一次登录）。 */
    private static String takeVerifyUrl() {
        String v = LAST_VERIFY_URL.get();
        LAST_VERIFY_URL.remove();
        return (v == null || v.isBlank()) ? "" : v;
    }

    /**
     * 只接受 http(s) 直链，且长度受限（防呆：伺服端可能给空值、也可能是别的东西）。
     * 返回值一定是非 null 字符串（不合格 ⇒ 空串）。
     */
    private static String sanitizeVerifyUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 2048) {
            return "";
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "";
        }
        // 控制字符会让二维码与日志都出问题，直接拒
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 0x20) {
                return "";
            }
        }
        return s;
    }

    private static Object weapi(String path, Map<String, Object> payload) throws NeteaseException {
        LAST_VERIFY_URL.remove();     // 0.11.30：先清，避免把上一次的验证链接串到这一条
        WeapiCrypto.Payload enc = WeapiCrypto.encrypt(payload);
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("params", enc.params());
        form.put("encSecKey", enc.encSecKey());
        String url = Http.BASE + "/weapi/" + path + "/?csrf_token="
                + java.util.Objects.toString(Http.cookie("__csrf"), "");
        String body = call("weapi/" + path, url, form);
        Object root = parse(Json.parse(body));
        int code = Json.integer(root, "code", 200);
        if (code != 200) {
            // 0.11.29（坑 39）：风控答复（-462）没有 message，人话在 data.blockText
            //（「验证成功后，可进行下一步操作哦~」）；两者都空才写空串，免得上层只能显示 "(无)"。
            String words = wordsOf(root);
            // 0.11.30（需求①）：把行为验证跳转地址带出（三个候选键都试，只认 http(s) 的直链）
            // 0.11.32：提取逻辑抽成 verifyUrlOf，扫码轮询与手机号登录共用同一顺序
            String verify = verifyUrlOf(root);
            if (!verify.isEmpty()) {
                LAST_VERIFY_URL.set(verify);
                PluginLog.i("netease", "weapi/" + path + " 要求行为验证：已带出验证地址（长度 "
                        + verify.length() + "，值不进日志）");
            }
            throw new NeteaseException("weapi/" + path + " 失败：code=" + code
                    + " msg=" + PluginLog.sanitize(words));
        }
        return root;
    }

    /** 发一次请求并把 IOException 归一成 NeteaseException。 */
    private static String call(String what, String url, Map<String, String> form) throws NeteaseException {
        try {
            return form == null
                    ? Http.get(url, null)
                    : Http.postForm(url, form, null);
        } catch (IOException e) {
            throw new NeteaseException(what + " 请求失败：" + safeMessage(e));
        }
    }

    /** 异常消息脱敏 + 截断，避免把带参 URL / 凭据甩进 UI。 */
    private static String safeMessage(Throwable t) {
        String msg = PluginLog.sanitize(String.valueOf(t.getMessage()));
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /** 解析失败不抛：返回 null，让上层走"空结果"降级。 */
    private static Object parse(Object parsed) {
        return parsed == null ? new java.util.LinkedHashMap<String, Object>() : parsed;
    }

    /** 依次尝试多个容器字段，取第一个非空文本（0.6.0 随歌词恢复，原样搬回旧版）。 */
    private static String firstText(Object node, String[] fields) {
        for (String f : fields) {
            String v = trimToNull(Json.str(node, f + ".lyric", null));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 取翻译/罗马音字段的纯文本（trim 后为空视作没有）。 */
    private static String joinText(Object node, String path) {
        return trimToNull(Json.str(node, path, null));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
