package netease.smoke;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.net.NeteaseException;
import com.example.netease.net.SmokeHooks;

/**
 * net 层离线 + 在线冒烟自测（零第三方测试框架）。
 *
 * <p>用法：{@code java -cp <classes> netease.smoke.SmokeNet [--offline]}</p>
 *
 * <p>退出码：0 = 全部通过；1 = 有断言不满足；2 = 网络不可用（优雅降级，不抛栈）。</p>
 */
public final class SmokeNet {

    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[\\d{2}:\\d{2}(?::\\d{2})?[.]\\d{2,3}\\]");
    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    private SmokeNet() {
    }

    public static void main(String[] args) {
        boolean offline = false;
        for (String a : args) {
            if ("--offline".equals(a)) {
                offline = true;
            }
        }
        System.out.println("=== net 层冒烟测试（offline=" + offline + "）===");
        System.out.println("java " + System.getProperty("java.version")
                + " / " + System.getProperty("os.name"));

        System.out.println();
        System.out.println("--- [离线] weapi 加密自检 ---");
        boolean cryptoOk = report(SmokeHooks.cryptoSelfTest());
        System.out.println();
        System.out.println("--- [离线] cookie jar 自检 ---");
        cryptoOk &= report(SmokeHooks.cookieSelfTest());
        System.out.println();
        System.out.println("--- [离线] 二维码 PNG 自检 ---");
        cryptoOk &= report(SmokeHooks.qrSelfTest());
        System.out.println();
        System.out.println("--- [离线] 账号字段解析（0.11.40：名字取 profile.nickname，不是 account.userName）---");
        cryptoOk &= report(SmokeHooks.accountSelfTest());
        cryptoOk &= offlineP3Checks();

        if (offline) {
            finish(cryptoOk, false);
            return;
        }

        boolean networkDown = false;
        try {
            onlineChecks();
        } catch (NeteaseException e) {
            networkDown = true;
            System.out.println();
            System.out.println("[网络不可用] 网易云接口当前不可达：" + e.getMessage());
            System.out.println("           离线部分" + (cryptoOk ? "全部通过" : "存在失败项")
                    + "；在线部分未执行。属于优雅降级，不是崩溃。");
        } catch (Throwable t) {
            System.out.println();
            System.out.println("[FAIL] 未预期异常（真 bug，需要修）：" + t.getClass().getName()
                    + ": " + t.getMessage());
            t.printStackTrace(System.out);
            failed++;
        }
        finish(cryptoOk, networkDown);
    }

    // ---------------------------------------------------------------- 在线检查

    private static void onlineChecks() throws NeteaseException {
        System.out.println();
        System.out.println("--- [在线] 搜索：周杰伦 ---");
        List<Dto.Song> songs = NeteaseApi.search("周杰伦", 10);
        if (songs.isEmpty()) {
            System.out.println("  （返回 0 条）");
            check("搜索返回 ≥5 条", false, true);
            return;
        }
        for (int i = 0; i < songs.size(); i++) {
            Dto.Song s = songs.get(i);
            System.out.printf("  %2d. id=%-12d | %-26s | %-16s | %-24s | %7dms | fee=%d | playable=%s%n",
                    i + 1, s.id(), cut(s.name(), 26), cut(s.artists(), 16),
                    cut(s.album(), 24), s.durationMs(), s.fee(), s.playable());
        }
        check("搜索返回 ≥5 条", songs.size() >= 5, true);
        check("每条都有 id 与歌名",
                songs.stream().allMatch(s -> s.id() > 0 && !s.name().isBlank()), true);
        check("时长字段有值", songs.stream().anyMatch(s -> s.durationMs() > 0), true);

        System.out.println();
        System.out.println("--- [在线] 登录相关接口（当前未登录，验证降级路径）---");
        System.out.println("  isLoggedIn()（本地判断）= " + NeteaseApi.isLoggedIn());
        check("未登录时 isLoggedIn()=false", !NeteaseApi.isLoggedIn(), true);
        NeteaseApi.LoginOutcome guard = NeteaseApi.loginByCaptcha("", "");
        System.out.println("  空凭据 loginByCaptcha = " + guard.ok() + " code=" + guard.code());
        check("空凭据登录返回 false（不发请求）", !guard.ok() && guard.code() == -2, true);
        try {
            Dto.Account acc = NeteaseApi.accountInfo();
            System.out.println("  未登录 accountInfo = " + (acc == null ? "null" : acc.nickname()));
            check("未登录 accountInfo 返回 null", acc == null, true);
        } catch (NeteaseException e) {
            System.out.println("  未登录 accountInfo 降级抛出（已捕获）：" + e.getMessage());
            check("未登录 accountInfo 不崩", true, true);
        }
        try {
            String url = NeteaseApi.songUrl(songs.get(0).id(), "exhigh");
            System.out.println("  未登录 songUrl(exhigh) = "
                    + (url == null ? "null（无直链/需登录，预期）" : safe(url)));
            check("未登录 songUrl 返回 null 或链接（都不崩）", true, true);
        } catch (NeteaseException e) {
            System.out.println("  未登录 songUrl 抛出（已捕获）：" + e.getMessage());
            check("未登录 songUrl 不崩", true, true);
        }
        NeteaseApi.logout();
        check("logout 后 cookie 已清空", !NeteaseApi.isLoggedIn(), true);

        // ---- 未登录下的匿名兜底，打通 search → songUrl → 在线播放（0.5.0 起不再有下载链路）----
        System.out.println();
        System.out.println("--- [在线] songUrl 匿名兜底（未登录，task-5 证据）---");
        Dto.Song free = null;
        for (Dto.Song s : songs) {
            if (s.playable() && s.fee() == 0) {
                free = s;
                break;
            }
        }
        if (free == null) {
            System.out.println("  本次搜索没有 playable==true && fee==0 的歌，跳过匿名兜底用例");
        } else {
            System.out.println("  用例歌曲：id=" + free.id() + "「" + free.name() + "」fee=0 playable=true");
            String[] levels = {"standard", "exhigh", "lossless"};
            String fallbackUrl = null;
            for (String lv : levels) {
                try {
                    long t0 = System.nanoTime();
                    String url = NeteaseApi.songUrl(free.id(), lv);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    System.out.println("  songUrl(" + lv + ") = "
                            + (url == null ? "null" : maskUrl(url)) + "   [" + ms + "ms]");
                    if ("exhigh".equals(lv)) {
                        fallbackUrl = url;
                    }
                } catch (NeteaseException e) {
                    System.out.println("  songUrl(" + lv + ") 抛出（已捕获）：" + e.getMessage());
                }
            }
            check("未登录 + fee==0 的歌曲能拿到非空直链（匿名兜底生效）",
                    fallbackUrl != null && !fallbackUrl.isBlank(), true);
            if (fallbackUrl != null) {
                probe(fallbackUrl);
            } else {
                // 兜底失败时的诊断：端点在空会话下到底给了什么
                System.out.println("  [诊断] 匿名端点裸响应：" + SmokeHooks.anonymousUrlRaw(free.id(), 320000));
                System.out.println("  [诊断] " + SmokeHooks.warmupSession());
                try {
                    String again = NeteaseApi.songUrl(free.id(), "exhigh");
                    System.out.println("  [诊断] 预热会话后重试 songUrl(exhigh) = "
                            + (again == null ? "null" : maskUrl(again)));
                    if (again != null && !again.isBlank()) {
                        probe(again);
                    }
                } catch (NeteaseException e) {
                    System.out.println("  [诊断] 预热后重试抛出：" + e.getMessage());
                }
            }
        }

        onlinePlaylistChecks();
        NeteaseApi.logout();
    }

    // ---------------------------------------------------------------- P3 离线检查

    /** P3 新增（纯离线）：cookie 导出/注入往返 + 冻结签名与返回契约断言。 */
    private static boolean offlineP3Checks() {
        System.out.println();
        System.out.println("--- [离线] cookie 导出/注入往返 + P3 契约（task-7）---");
        boolean all = true;

        NeteaseApi.importCookies(null);
        String empty = NeteaseApi.exportCookies();
        System.out.println("  清空后 exportCookies()：len=" + (empty == null ? -1 : empty.length())
                + "，是 null=" + (empty == null));
        all &= ok("无 cookie 时 exportCookies() 返回空串（绝不为 null）", empty != null && empty.isEmpty());
        all &= ok("无 cookie 时 isLoggedIn()=false", !NeteaseApi.isLoggedIn());

        String header = "MUSIC_U=PLACEHOLDER_A; __csrf=PLACEHOLDER_B";
        NeteaseApi.importCookies(header);
        String exported = NeteaseApi.exportCookies();
        System.out.println("  注入 2 条（值不打印）→ exportCookies()：len="
                + (exported == null ? -1 : exported.length()) + "，项数=" + countEntries(exported)
                + "，含 MUSIC_U=" + (exported != null && exported.contains("MUSIC_U=")));
        all &= ok("注入后 exportCookies() 非 null 非空", exported != null && !exported.isEmpty());
        all &= ok("导出含 MUSIC_U 与 __csrf", exported != null
                && exported.contains("MUSIC_U=") && exported.contains("__csrf="));
        all &= ok("导出格式 \"k=v; k2=v2\" 且无尾分号",
                exported != null && !exported.endsWith(";") && countEntries(exported) == 2);
        all &= ok("往返一致（注入串原样导出，值不被改写）", header.equals(exported));
        all &= ok("注入 MUSIC_U 后 isLoggedIn()=true", NeteaseApi.isLoggedIn());

        boolean threwOnJunk = false;
        try {
            NeteaseApi.importCookies("garbage; ; =bad; MUSIC_U=PLACEHOLDER_C");
        } catch (Throwable t) {
            threwOnJunk = true;
            System.out.println("  importCookies 非法片段抛出（违规）：" + t.getClass().getSimpleName());
        }
        String after = NeteaseApi.exportCookies();
        all &= ok("非法片段（无 '=' / 以 '=' 开头）被忽略且不抛", !threwOnJunk);
        all &= ok("非法片段不进 jar（只剩 1 条）", countEntries(after) == 1);

        for (String blank : new String[]{null, "", "   "}) {
            NeteaseApi.importCookies(blank);
        }
        String cleared = NeteaseApi.exportCookies();
        all &= ok("importCookies(null/\"\"/空白) 一律等于清空", cleared != null && cleared.isEmpty()
                && !NeteaseApi.isLoggedIn());

        try {
            List<Dto.PlaylistBrief> z1 = NeteaseApi.userPlaylists(0L, 100);
            List<Dto.PlaylistBrief> z2 = NeteaseApi.userPlaylists(12345L, 10);
            all &= ok("未登录 userPlaylists(0,100) 返回空列表（不抛）", z1 != null && z1.isEmpty());
            all &= ok("未登录 userPlaylists(12345,10) 返回空列表（不抛）", z2 != null && z2.isEmpty());
        } catch (Throwable t) {
            all &= ok("未登录 userPlaylists 返回空列表（不抛）", false);
            System.out.println("  （userPlaylists 未登录路径抛了 " + t.getClass().getSimpleName()
                    + "：" + t.getMessage() + "）");
        }

        all &= ok("未登录 dailySongs() 抛 NeteaseException 且 message 含「未登录」", notLoggedIn(true));
        all &= ok("未登录 likedSongs(0) 抛 NeteaseException 且 message 含「未登录」", notLoggedIn(false));
        all &= ok("playlist(0) 抛可读 NeteaseException（id 非法）", badIdThrows(0L));
        all &= ok("playlist(-5) 抛可读 NeteaseException（id 非法）", badIdThrows(-5L));

        Dto.Playlist pl = new Dto.Playlist(7L, "测试名单", "测试创建者", 3,
                new java.util.ArrayList<Dto.Song>());
        all &= ok("Playlist(id/name/creator/trackCount) 访问器可用",
                pl.id() == 7L && "测试名单".equals(pl.name())
                        && "测试创建者".equals(pl.creator()) && pl.trackCount() == 3);
        all &= ok("Playlist.tracks() 绝不为 null", pl.tracks() != null && pl.tracks().isEmpty());
        Dto.PlaylistBrief pb = new Dto.PlaylistBrief(9L, "测试名单2", "测试创建者2", 12, 345L);
        all &= ok("PlaylistBrief(id/name/creator/trackCount/playCount) 访问器可用",
                pb.id() == 9L && "测试名单2".equals(pb.name()) && "测试创建者2".equals(pb.creator())
                        && pb.trackCount() == 12 && pb.playCount() == 345L);

        return all;
    }

    /** 未登录时 dailySongs/likedSongs 必须抛 NeteaseException 且 message 含「未登录」。 */
    private static boolean notLoggedIn(boolean daily) {
        String what = daily ? "dailySongs()" : "likedSongs(0)";
        try {
            if (daily) {
                NeteaseApi.dailySongs();
            } else {
                NeteaseApi.likedSongs(0L);
            }
            System.out.println("  （" + what + " 未登录时没抛异常 → 违反契约）");
            return false;
        } catch (NeteaseException e) {
            String m = String.valueOf(e.getMessage());
            System.out.println("  " + what + " 未登录抛出：" + m);
            return m.contains("未登录");
        } catch (Throwable t) {
            System.out.println("  " + what + " 抛出了非 NeteaseException：" + t.getClass().getName());
            return false;
        }
    }

    /** playlist(id<=0) 必须抛可读 NeteaseException。 */
    private static boolean badIdThrows(long id) {
        try {
            NeteaseApi.playlist(id);
            System.out.println("  （playlist(" + id + ") 没抛异常 → 违反契约）");
            return false;
        } catch (NeteaseException e) {
            System.out.println("  playlist(" + id + ") 抛出：" + e.getMessage());
            return true;
        } catch (Throwable t) {
            System.out.println("  playlist(" + id + ") 抛出非 NeteaseException：" + t.getClass().getName());
            return false;
        }
    }

    // ---------------------------------------------------------------- P3 在线检查（需登录）

    /**
     * 歌单 / 每日推荐 / 我喜欢的音乐（P3）。
     *
     * <p>需登录态：可用环境变量 {@code NETEASE_SMOKE_COOKIE}（原始 Cookie 头）或
     * {@code NETEASE_SMOKE_COOKIE_FILE}（文件内容为 Cookie 头）注入，注入值与已导出的
     * cookie 一律不打印。未登录 / 接口不可用时一律 SKIP 并说明原因，计入 SKIP 而不是 FAIL。</p>
     */
    private static void onlinePlaylistChecks() {
        System.out.println();
        System.out.println("--- [在线] 歌单 / 每日推荐 / 我喜欢的音乐（P3，需登录）---");
        String envCookie = envCookie();
        boolean injected = envCookie != null;
        if (injected) {
            NeteaseApi.importCookies(envCookie);
            System.out.println("  已注入登录态（来源环境变量，值不打印）：项数="
                    + countEntries(NeteaseApi.exportCookies())
                    + "，isLoggedIn()=" + NeteaseApi.isLoggedIn());
        }
        boolean loggedIn = NeteaseApi.isLoggedIn();
        long playlistId = 0L;
        if (!loggedIn) {
            skip("userPlaylists", "未登录（jar 里没有 MUSIC_U）；设 NETEASE_SMOKE_COOKIE 后可跑真实断言");
            // 歌单详情不强依赖登录：未登录时用公开歌单搜索拿一个真实 id，再走 playlist(id)
            try {
                List<Dto.PlaylistBrief> pub = SmokeHooks.searchPlaylists("周杰伦", 5);
                System.out.println("  未登录 · 公开歌单搜索 → " + pub.size() + " 条");
                for (int i = 0; i < Math.min(3, pub.size()); i++) {
                    Dto.PlaylistBrief b = pub.get(i);
                    System.out.println("    id=" + b.id() + " | " + cut(b.name(), 24)
                            + " | 曲目=" + b.trackCount() + " | 播放=" + b.playCount());
                }
                ok("未登录也能搜到公开歌单（≥1 条）", !pub.isEmpty());
                ok("公开歌单条目 id>0 且名称非空",
                        pub.stream().allMatch(b -> b.id() > 0L && !b.name().isBlank()));
                if (!pub.isEmpty()) {
                    playlistId = pub.get(0).id();
                }
            } catch (NeteaseException e) {
                skip("公开歌单搜索", "接口不可用（" + e.getMessage() + "）");
            }
        } else {
            try {
                List<Dto.PlaylistBrief> briefs = NeteaseApi.userPlaylists(0L, 100);
                System.out.println("  userPlaylists → " + briefs.size() + " 个歌单");
                for (int i = 0; i < Math.min(5, briefs.size()); i++) {
                    Dto.PlaylistBrief b = briefs.get(i);
                    System.out.println("    id=" + b.id() + " | " + cut(b.name(), 24) + " | 创建者="
                            + cut(b.creator(), 12) + " | 曲目=" + b.trackCount() + " | 播放=" + b.playCount());
                }
                ok("userPlaylists 不返回 null", briefs != null);
                ok("歌单条目 id>0 且名称非空", briefs != null
                        && briefs.stream().allMatch(b -> b.id() > 0L && !b.name().isBlank()));
                ok("歌单条目 trackCount/playCount 有值", briefs != null
                        && briefs.stream().allMatch(b -> b.trackCount() >= 0 && b.playCount() >= 0L));
                if (briefs != null && !briefs.isEmpty()) {
                    playlistId = briefs.get(0).id();
                }
            } catch (NeteaseException e) {
                skip("userPlaylists", "接口不可用（" + e.getMessage() + "）——外部原因，不判 FAIL");
            }
        }

        if (playlistId > 0L) {
            try {
                long t0 = System.nanoTime();
                Dto.Playlist pl = NeteaseApi.playlist(playlistId);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                System.out.println("  playlist(id=" + pl.id() + ")「" + cut(pl.name(), 24) + "」声明 "
                        + pl.trackCount() + " 首 → 取回 " + pl.tracks().size() + " 首 [" + ms + "ms]");
                for (int i = 0; i < Math.min(3, pl.tracks().size()); i++) {
                    Dto.Song s = pl.tracks().get(i);
                    System.out.println("    " + (i + 1) + ". id=" + s.id() + " | " + cut(s.name(), 24)
                            + " | " + cut(s.artists(), 16) + " | " + s.durationMs() + "ms | playable="
                            + s.playable());
                }
                ok("Playlist.tracks() 绝不为 null", pl.tracks() != null);
                ok("单次取回不超过 1000 首（分页上限）", pl.tracks().size() <= 1000);
                ok("曲目 id>0 且歌名非空", pl.tracks() != null
                        && pl.tracks().stream().allMatch(s -> s.id() > 0L && !s.name().isBlank()));
                ok("Playlist.id 与请求 id 一致", pl.id() == playlistId);
                ok("Playlist.name 非空", !pl.name().isBlank());
            } catch (NeteaseException e) {
                skip("playlist(id=" + playlistId + ")", "接口不可用（" + e.getMessage() + "）");
            }
        } else {
            skip("playlist", "本次没拿到可用歌单 id（账号歌单为空或列表接口不可用）");
        }

        if (!loggedIn) {
            skip("dailySongs", "未登录：每日推荐必须登录态（设 NETEASE_SMOKE_COOKIE 后可跑）");
        } else {
            try {
                List<Dto.Song> daily = NeteaseApi.dailySongs();
                System.out.println("  dailySongs → " + daily.size() + " 首"
                        + (daily.isEmpty() ? "（当日推荐为空也属正常）"
                                : "，首条=" + cut(daily.get(0).name(), 24)));
                ok("dailySongs 不返回 null", daily != null);
                ok("dailySongs 曲目字段完整", daily != null
                        && daily.stream().allMatch(s -> s.id() > 0L && !s.name().isBlank()));
            } catch (NeteaseException e) {
                skip("dailySongs", "接口不可用（" + e.getMessage() + "）——常见于无每日推荐权限/风控");
            }
        }

        if (!loggedIn) {
            skip("likedSongs", "未登录：我喜欢的音乐必须登录态（设 NETEASE_SMOKE_COOKIE 后可跑）");
        } else {
            try {
                List<Dto.Song> liked = NeteaseApi.likedSongs(0L);
                System.out.println("  likedSongs → " + liked.size() + " 首"
                        + (liked.isEmpty() ? "（未收藏也属正常）" : "，首条=" + cut(liked.get(0).name(), 24)));
                ok("likedSongs 不返回 null", liked != null);
                ok("likedSongs 曲目字段完整", liked != null
                        && liked.stream().allMatch(s -> s.id() > 0L && !s.name().isBlank()));
            } catch (NeteaseException e) {
                skip("likedSongs", "接口不可用（" + e.getMessage() + "）");
            }
        }

        if (injected) {
            NeteaseApi.logout();
            System.out.println("  已清空注入的登录态：isLoggedIn()=" + NeteaseApi.isLoggedIn());
        }
    }

    /** 环境变量里的 Cookie 头（NETEASE_SMOKE_COOKIE 优先，其次 NETEASE_SMOKE_COOKIE_FILE）；无则 null。 */
    private static String envCookie() {
        String v = System.getenv("NETEASE_SMOKE_COOKIE");
        if (v != null && !v.isBlank()) {
            return v;
        }
        String f = System.getenv("NETEASE_SMOKE_COOKIE_FILE");
        if (f != null && !f.isBlank()) {
            try {
                String body = java.nio.file.Files.readString(java.nio.file.Path.of(f.trim()),
                        StandardCharsets.UTF_8).trim();
                return body.isEmpty() ? null : body;
            } catch (Exception e) {
                System.out.println("  读 NETEASE_SMOKE_COOKIE_FILE 失败（忽略）：" + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 直链探针 / 输出工具

    /**
     * 只读探针：只取首字节验证直链可达（真正实现走 {@link SmokeHooks#httpProbe}，因为 {@code Http} 是包内可见）。
     *
     * <p>先带 {@code Range: bytes=0-0} 请求一次，拿到 200 直接通过；若是 302 则由 {@code Http}
     * 侧跟着 Location 再探一次（Http 本身刻意不跟随 302）。</p>
     */
    private static void probe(String url) {
        String diag = SmokeHooks.httpProbe(url);
        System.out.println("  直链探针（Range 1 字节）：" + diag);
        Matcher m = Pattern.compile("HTTP (\\d{3})").matcher(diag);
        int last = 0;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));   // 取最后一个状态码 = 最终状态
        }
        check("直链可达（200/206）", last == 200 || last == 206, true);
    }

    /** 直链/封面 URL 脱敏显示：隐藏查询串与签名参数，只留 scheme://host/path 片段。 */
    private static String maskUrl(String url) {
        if (url == null) {
            return "null";
        }
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String tail = q < 0 ? "" : "?（含签名参数 " + (url.length() - q - 1) + " 字符，已隐藏）";
        if (base.length() > 96) {
            base = base.substring(0, 96) + "…";
        }
        return base + tail;
    }

    private static boolean report(List<SmokeHooks.Check> checks) {
        boolean all = true;
        for (SmokeHooks.Check c : checks) {
            if (c.ok()) {
                passed++;
                System.out.println("  [PASS] " + c.what() + "  → " + c.detail());
            } else {
                failed++;
                all = false;
                System.out.println("  [FAIL] " + c.what() + "  → " + c.detail());
            }
        }
        return all;
    }

    private static void check(String what, boolean actual, boolean expected) {
        if (actual == expected) {
            passed++;
            System.out.println("  [PASS] " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    /** 断言 + 返回该断言结果（便于 {@code all &= ok(...)} 累积）。 */
    private static boolean ok(String what, boolean condition) {
        check(what, condition, true);
        return condition;
    }

    /**
     * 跳过一项（SKIP ≠ FAIL）：用于「需登录 / 需外部服务」的用例。
     *
     * <p>SKIP 只计数与打印，不影响退出码——外部账号状态不可控，不能因此判失败。</p>
     */
    private static void skip(String what, String reason) {
        skipped++;
        System.out.println("  [SKIP] " + what + " → " + reason);
    }

    /** 数 Cookie 头里的条目数（只看 '=' 位置与条数，不解析也不打印值）。 */
    private static int countEntries(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String part : header.split(";")) {
            if (part.indexOf('=') > 0) {
                n++;
            }
        }
        return n;
    }

    private static void finish(boolean offlineOk, boolean networkDown) {
        System.out.println();
        System.out.println("=== 汇总：PASS " + passed + " / FAIL " + failed + " / SKIP " + skipped
                + "，离线自检 " + (offlineOk ? "全部通过" : "存在失败") + " ===");
        // ASCII 哨兵行：不受控制台编码影响，便于脚本 / grep 取计数（SKIP 不算失败）
        System.out.println("[SMOKE] SUMMARY pass=" + passed + " fail=" + failed + " skip=" + skipped
                + " offlineOk=" + offlineOk + " networkDown=" + networkDown);
        if (skipped > 0) {
            System.out.println("（SKIP " + skipped + " 项：需登录 / 需外部服务，未执行断言，不计入失败）");
        }
        if (failed > 0 || !offlineOk) {
            System.out.println("=== 退出码 1（存在失败）===");
            System.exit(1);
        }
        if (networkDown) {
            System.out.println("=== 退出码 2（网络不可用，离线部分通过）===");
            System.exit(2);
        }
        System.out.println("=== 退出码 0（全部通过）===");
        System.exit(0);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String present(String s) {
        return s != null && !s.isBlank() ? "是" : "否";
    }

    private static int size(String s) {
        return s == null ? 0 : s.length();
    }

    /** 直链只打印 scheme+host+path，避免把带 token 的查询串刷进屏幕。 */
    private static String safe(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        return base + (q < 0 ? "" : "?…(" + (url.length() - q) + " 字节查询串已隐藏)");
    }

    /** 备用：把二维码 PNG 落盘，供人工验收时肉眼扫码（仅自测使用）。 */
    static void dump(java.nio.file.Path path, byte[] bytes) {
        try {
            java.nio.file.Files.write(path, new String(bytes, StandardCharsets.ISO_8859_1)
                    .getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            System.out.println("  写文件失败：" + e.getMessage());
        }
    }
}
