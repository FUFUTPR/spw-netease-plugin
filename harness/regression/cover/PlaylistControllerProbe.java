import com.example.netease.net.Dto;
import com.example.netease.ui.controller.PlaylistController;
import com.example.netease.ui.controller.PlaylistIds;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 批次 2 · 歌单控制器回归探针（headless，无 Swing/AWT）。
 *
 * <p>覆盖契约 §11：A→B 迟到丢弃、B 失败保留旧内容、retry 原参数、账号 epoch、
 * 播放反馈 30 秒窗口（持续回显 report、同值不 notify、超时保留最后文案）、
 * 窗口内 retryPlayback、close 后不发布。成功打印 PLAYLIST_CONTROLLER_RESULT=N PASS。</p>
 */
public final class PlaylistControllerProbe {

    private static int passed;

    public static void main(String[] args) {
        // 统一以 UTF-8 输出，避免 Windows 控制台代码页（GBK）导致中文标签乱码。
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        try {
            idTests();
            initialState();
            silentEpochAtStartup();
            loginGuards();
            dailyFlow();
            lateDiscard();
            failureKeepsOldAndRetry();
            briefsDoNotClearTracks();
            textLoading();
            nameFallback();
            playGuards();
            playClampAndText();
            retryPlaybackLocates();
            feedbackWindow();
            timeoutWithoutReport();
            retryWithinWindow();
            epochChange();
            closeStopsPublish();
        } catch (Throwable t) {
            System.out.println("PLAYLIST_CONTROLLER_FAIL " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println("PLAYLIST_CONTROLLER_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 用例

    private static void idTests() {
        eq(123L, PlaylistIds.parse("123"), "纯数字");
        eq(456L, PlaylistIds.parse(" 456 "), "trim 后纯数字");
        eq(789L, PlaylistIds.parse("https://music.163.com/#/playlist?id=789"), "链接 ?id=");
        eq(321L, PlaylistIds.parse("?id=321"), "查询串 ?id=");
        eq(555L, PlaylistIds.parse("/playlist/555"), "路径 /playlist/");
        eq(-1L, PlaylistIds.parse(null), "null → -1");
        eq(-1L, PlaylistIds.parse("   "), "空白 → -1");
        eq(-1L, PlaylistIds.parse("abc"), "非数字 → -1");
        eq(-1L, PlaylistIds.parse("99999999999999999999"), "溢出 → -1");
        eq(-1L, PlaylistIds.parse("id=777"), "缺少 ?/& 前缀 → -1");
    }

    private static void initialState() {
        Fixture f = new Fixture();
        PlaylistController.State st = f.ctl.state();
        eq("就绪", st.message(), "初始文案");
        check(!st.busy(), "初始非 busy");
        check(st.briefs().isEmpty(), "初始歌单清单为空");
        check(st.tracks().isEmpty(), "初始曲目为空");
        eq("", st.sourceName(), "初始来源为空");
        eq(0L, st.accountEpoch(), "初始 epoch");
        check(!st.playing(), "初始不在播放窗口");
        eq(0, st.pendingPlayRequest(), "无待反馈播放请求");
        check(!st.canRetry(), "初始不可重试");
    }

    /** 插件启动早期账号 epoch 抖动：页面还没载入任何内容时只静默同步快照，不打扰用户。 */
    private static void silentEpochAtStartup() {
        Fixture f = new Fixture();
        int n = f.notes();
        f.api.epoch = 9L;
        f.ctl.tick();
        eq(9L, f.ctl.state().accountEpoch(), "未载入时 epoch 抖动只同步快照");
        eq("就绪", f.ctl.state().message(), "未载入时 epoch 抖动不改文案");
        check(!f.ctl.state().canRetry(), "未载入时 epoch 抖动不启用重试");
        eq(n, f.notes(), "未载入时 epoch 抖动不通知");
    }

    private static void loginGuards() {
        Fixture f = new Fixture();
        f.api.loggedIn = false;
        f.ctl.loadMine();
        eq(0, f.async.count(), "未登录 loadMine 不发请求");
        contains(f.ctl.state().message(), "请先登录");
        f.ctl.loadLiked();
        eq(0, f.async.count(), "未登录 loadLiked 不发请求");
        contains(f.ctl.state().message(), "请先登录");
        check(!f.ctl.state().busy(), "拒绝后不 busy");
    }

    private static void dailyFlow() {
        Fixture f = new Fixture();
        f.api.daily = List.of(song(1, "日推一", true), song(2, "日推二", true));
        f.ctl.loadDaily();
        eq(1, f.async.count(), "一次载入一个任务");
        check(f.ctl.state().busy(), "载入中 busy");
        eq("每日推荐", f.ctl.state().pendingLabel(), "pendingLabel 为每日推荐");
        contains(f.ctl.state().message(), "正在载入");
        f.async.succeed(0);
        PlaylistController.State st = f.ctl.state();
        eq(2, st.tracks().size(), "每日推荐载入曲目");
        eq("每日推荐", st.sourceName(), "来源名");
        eq("每日推荐 2 首（今日）", st.message(), "成功文案");
        check(!st.busy(), "成功后非 busy");
        eq("", st.pendingLabel(), "pendingLabel 清空");
        check(st.canRetry(), "成功后仍可重试");
        contains(st.retryLabel(), "每日推荐");
        eq(1, f.api.dailyCalls);
    }

    private static void lateDiscard() {
        Fixture f = new Fixture();
        f.api.playlists.put(111L, playlist(111, "旧歌单", List.of(song(1, "旧歌", true))));
        f.api.playlists.put(222L, playlist(222, "新歌单", List.of(song(2, "新一", true), song(3, "新二", true))));
        f.ctl.loadPlaylist(111L, "A");
        f.ctl.loadPlaylist(222L, "B");
        eq(2, f.async.count(), "A→B 产生两个任务");
        f.async.succeed(0);
        PlaylistController.State st = f.ctl.state();
        check(st.tracks().isEmpty(), "迟到的 A 结果被丢弃");
        eq("", st.sourceName());
        check(st.busy(), "B 仍在载入");
        f.async.succeed(1);
        eq(2, f.ctl.state().tracks().size());
        eq("歌单「新歌单」", f.ctl.state().sourceName());
    }

    private static void failureKeepsOldAndRetry() throws Exception {
        Fixture f = new Fixture();
        f.api.playlists.put(111L, playlist(111, "旧歌单", List.of(song(1, "旧歌", true))));
        f.api.playlists.put(222L, playlist(222, "新歌单", List.of(song(2, "新一", true), song(3, "新二", true))));
        f.ctl.loadPlaylist(111L, "A");
        f.async.succeed(0);
        eq(1, f.ctl.state().tracks().size());
        f.ctl.loadPlaylist(222L, "B");
        f.async.fail(1, new TimeoutException("timeout"));
        PlaylistController.State st = f.ctl.state();
        eq(1, st.tracks().size(), "B 失败保留旧曲目");
        eq("歌单「旧歌单」", st.sourceName(), "B 失败保留旧来源");
        contains(st.message(), "歌单「B」");
        contains(st.message(), "请求超时");
        contains(st.message(), "保留上次列表");
        check(st.canRetry(), "失败可重试");
        contains(st.retryLabel(), "歌单「B」");
        check(!st.busy(), "失败后非 busy");
        f.ctl.retry();
        eq(3, f.async.count(), "retry 复用原参数发起新任务");
        f.async.succeed(2);
        eq(2, f.api.playlistCalls, "retry 只调用一次 playlist");
        eq(2, f.api.playlistIds.size());
        eq(222L, f.api.playlistIds.get(f.api.playlistIds.size() - 1), "retry 使用原 playlistId");
        eq(2, f.ctl.state().tracks().size());
        eq("歌单「新歌单」声明 2 首，已载入 2 首", f.ctl.state().message());
    }

    private static void briefsDoNotClearTracks() {
        Fixture f = new Fixture();
        f.api.playlists.put(111L, playlist(111, "旧歌单", List.of(song(1, "旧歌", true))));
        f.ctl.loadPlaylist(111L, "A");
        f.async.succeed(0);
        f.api.briefs = List.of(
                new Dto.PlaylistBrief(10L, "我的创建", "me", 3, 0L, true),
                new Dto.PlaylistBrief(11L, "收藏的", "x", 5, 0L, false));
        f.ctl.loadMine();
        eq(2, f.async.count());
        f.async.succeed(1);
        PlaylistController.State st = f.ctl.state();
        eq(2, st.briefs().size(), "歌单清单载入");
        check(st.briefsLoaded(), "briefsLoaded");
        eq(1, st.tracks().size(), "载入歌单清单不清空曲目");
        eq("已载入 2 个歌单（创建 1 / 收藏 1），点左侧列表切换", st.message());
        eq(1, f.api.userPlaylistsCalls);
        eq(200, f.api.limitSeen, "BRIEF_LIMIT=200");
        eq(42L, f.api.uidSeen);
    }

    private static void textLoading() {
        Fixture f = new Fixture();
        f.api.playlists.put(333L, playlist(333, "夜曲", List.of(song(9, "夜", true))));
        f.ctl.loadPlaylistText("https://music.163.com/#/playlist?id=333");
        eq(1, f.async.count());
        f.async.succeed(0);
        eq(333L, f.api.playlistIds.get(0));
        eq("歌单「夜曲」", f.ctl.state().sourceName());
        int before = f.async.count();
        f.ctl.loadPlaylistText("abc");
        eq(before, f.async.count(), "非法输入不发请求");
        eq("请填写有效歌单 ID 或链接", f.ctl.state().message());
        f.ctl.loadPlaylistText(null);
        eq(before, f.async.count(), "null 输入不发请求");
        contains(f.ctl.state().message(), "请填写有效歌单");
    }

    private static void nameFallback() {
        Fixture f = new Fixture();
        f.api.playlists.put(444L, playlist(444, "", List.of(song(1, "a", true))));
        f.ctl.loadPlaylist(444L, "歌单 #444");
        f.async.succeed(0);
        eq("歌单「歌单 #444」", f.ctl.state().sourceName(), "空名回退 label");
        f.api.playlists.put(445L, playlist(445, null, List.of()));
        f.ctl.loadPlaylist(445L, "歌单 #445");
        f.async.succeed(1);
        eq("歌单「歌单 #445」", f.ctl.state().sourceName(), "null 名回退 label");
        eq("歌单「歌单 #445」声明 0 首，已载入 0 首", f.ctl.state().message());
    }

    private static void playGuards() {
        Fixture f = new Fixture();
        f.ctl.playFrom(0);
        eq(0, f.playback.playCount, "空表不发播放请求");
        contains(f.ctl.state().message(), "曲目表是空的");
        f.api.playlists.put(111L, playlist(111, "X", List.of(song(1, "可播", true), song(2, "不可播", false))));
        f.ctl.loadPlaylist(111L, "X");
        f.async.succeed(0);
        f.ctl.playFrom(1);
        eq(0, f.playback.playCount, "不可播不发播放请求");
        contains(f.ctl.state().message(), "无版权");
        f.ctl.retryPlayback();
        contains(f.ctl.state().message(), "没有可重试的播放请求");
        eq(0, f.playback.playCount);
    }

    private static void playClampAndText() {
        Fixture f = new Fixture();
        List<Dto.Song> tracks = List.of(song(1, "甲", true), song(2, "乙", true), song(3, "丙", true));
        f.api.playlists.put(111L, playlist(111, "测试歌单", tracks));
        f.ctl.loadPlaylist(111L, "测试歌单");
        f.async.succeed(0);
        f.ctl.playFrom(-5);
        eq(0, f.playback.startRow, "负行号夹到 0");
        eq(1, f.playback.playCount);
        eq(3, f.playback.songs.size(), "整单播放传入完整列表");
        eq("极高", f.playback.level);
        eq("歌单「测试歌单」", f.playback.source);
        eq(f.playback.lastRequestId, f.ctl.state().pendingPlayRequest(), "pendingPlayRequest 为宿主编号");
        eq("播放请求：歌单「测试歌单」 自第 1 首「甲」起（音质 极高；失败原因见日志 bridge）",
                f.ctl.state().playbackText(), "播放文案精确");
        eq(f.ctl.state().playbackText(), f.ctl.state().message());
        check(f.ctl.state().playing(), "播放后进入反馈窗口");
        f.ctl.playFrom(99);
        eq(2, f.playback.startRow, "越界行号夹到末行");
        eq(2, f.playback.playCount);
        contains(f.ctl.state().playbackText(), "自第 3 首「丙」起");
        f.ctl.loadDaily();
        check(!f.ctl.state().playing(), "新载入清空播放窗口");
        eq(0, f.ctl.state().pendingPlayRequest());
    }

    private static void retryPlaybackLocates() {
        Fixture f = new Fixture();
        f.api.playlists.put(111L, playlist(111, "A",
                List.of(song(1, "一", true), song(2, "二", true), song(3, "三", true))));
        f.ctl.loadPlaylist(111L, "A");
        f.async.succeed(0);
        f.ctl.playFrom(0);
        int lastRequest = f.ctl.state().pendingPlayRequest();
        f.api.playlists.put(222L, playlist(222, "B",
                List.of(song(10, "十", true), song(1, "一", true), song(30, "三十", true))));
        f.ctl.loadPlaylist(222L, "B");
        f.async.succeed(1);
        f.ctl.retryPlayback();
        eq(2, f.playback.playCount, "窗口内可重试播放");
        eq(1, f.playback.startRow, "retry 按 lastPlaySongId 重新定位行");
        eq("歌单「B」", f.playback.source);
        check(f.playback.lastRequestId > lastRequest, "新请求编号替换 pendingPlayRequest");
        f.api.playlists.put(333L, playlist(333, "C", List.of(song(10, "十", true))));
        f.ctl.loadPlaylist(333L, "C");
        f.async.succeed(2);
        int plays = f.playback.playCount;
        f.ctl.retryPlayback();
        eq(plays, f.playback.playCount, "找不到上次歌曲不发请求");
        eq("上次播放的歌曲已不在当前列表中，无法重试", f.ctl.state().message());
    }

    private static void feedbackWindow() {
        Fixture f = new Fixture();
        f.api.daily = List.of(song(1, "日推一", true), song(2, "日推二", true));
        f.ctl.loadDaily();
        f.async.succeed(0);
        f.now[0] = 1_000L;
        f.ctl.playFrom(0);
        int rid = f.ctl.state().pendingPlayRequest();
        int n = f.notes();
        f.ctl.tick();
        check(f.ctl.state().playing(), "无 report 时窗口保持");
        eq(n, f.notes(), "无变化不 notify");
        f.playback.reports.put(rid, "正在解析音源…");
        f.ctl.tick();
        check(f.ctl.state().playing(), "首条 report 不提前结束窗口");
        eq("播放请求：正在解析音源…", f.ctl.state().playbackText());
        eq(n + 1, f.notes(), "首条 report 更新并 notify");
        f.playback.reports.put(rid, "已提交给宿主播放器…");
        f.ctl.tick();
        eq("播放请求：已提交给宿主播放器…", f.ctl.state().playbackText());
        eq(n + 2, f.notes(), "第二条不同 report 更新并 notify");
        f.ctl.tick();
        eq(n + 2, f.notes(), "同值 report 不 notify");
        f.now[0] = 1_000L + PlaylistController.PLAYBACK_TIMEOUT_MS;
        f.ctl.tick();
        check(!f.ctl.state().playing(), "到 30 秒结束窗口");
        eq("播放请求：已提交给宿主播放器…", f.ctl.state().playbackText(), "超时保留最后一条 report 文案");
        eq(0, f.ctl.state().pendingPlayRequest());
        eq(n + 3, f.notes(), "超时 notify 一次");
        f.ctl.tick();
        eq(n + 3, f.notes(), "窗口结束后不再轮询");
    }

    private static void timeoutWithoutReport() {
        Fixture f = new Fixture();
        f.api.daily = List.of(song(1, "日推一", true));
        f.ctl.loadDaily();
        f.async.succeed(0);
        f.now[0] = 0L;
        f.ctl.playFrom(0);
        f.now[0] = PlaylistController.PLAYBACK_TIMEOUT_MS - 1;
        f.ctl.tick();
        check(f.ctl.state().playing(), "未到 30 秒仍等待");
        f.now[0] = PlaylistController.PLAYBACK_TIMEOUT_MS;
        f.ctl.tick();
        check(!f.ctl.state().playing(), "从未有 report 到 30 秒结束");
        eq("播放请求：等待宿主反馈超时（30 秒），可重试", f.ctl.state().playbackText());
        eq(0, f.ctl.state().pendingPlayRequest());
    }

    private static void retryWithinWindow() {
        Fixture f = new Fixture();
        f.api.daily = List.of(song(1, "日推一", true), song(2, "日推二", true));
        f.ctl.loadDaily();
        f.async.succeed(0);
        f.now[0] = 0L;
        f.ctl.playFrom(0);
        int first = f.ctl.state().pendingPlayRequest();
        f.playback.reports.put(first, "正在解析音源…");
        f.ctl.tick();
        eq("播放请求：正在解析音源…", f.ctl.state().playbackText());
        check(f.ctl.state().playing());
        f.now[0] = 5_000L;
        f.ctl.retryPlayback();
        eq(2, f.playback.playCount, "窗口内重试播放");
        int second = f.ctl.state().pendingPlayRequest();
        check(second != first, "新请求替换 pendingPlayRequest");
        check(f.ctl.state().playing(), "重试后仍在窗口内");
        f.playback.reports.put(second, "已提交给宿主播放器…");
        f.now[0] = 34_999L;
        f.ctl.tick();
        eq("播放请求：已提交给宿主播放器…", f.ctl.state().playbackText());
        check(f.ctl.state().playing(), "retry 后按新 playStart 重新计时");
        f.now[0] = 35_000L;
        f.ctl.tick();
        check(!f.ctl.state().playing());
        eq("播放请求：已提交给宿主播放器…", f.ctl.state().playbackText());
    }

    private static void epochChange() {
        Fixture f = new Fixture();
        f.api.playlists.put(111L, playlist(111, "旧", List.of(song(1, "一", true))));
        f.ctl.loadPlaylist(111L, "A");
        f.async.succeed(0);
        f.api.briefs = List.of(new Dto.PlaylistBrief(1L, "创建", "me", 1, 0L, true));
        f.ctl.loadMine();
        f.async.succeed(1);
        eq(1, f.ctl.state().briefs().size());
        f.api.epoch = 7L;
        int n = f.notes();
        f.ctl.tick();
        PlaylistController.State st = f.ctl.state();
        check(st.briefs().isEmpty(), "epoch 变化清空歌单清单");
        check(!st.briefsLoaded(), "epoch 变化重置 briefsLoaded");
        eq(1, st.tracks().size(), "epoch 变化保留曲目");
        eq(7L, st.accountEpoch(), "epoch 快照更新");
        contains(st.message(), "账号状态变化");
        check(st.canRetry(), "epoch 变化可重试");
        eq("重新载入我的歌单", st.retryLabel());
        eq(1, f.async.cancelCount, "epoch 变化取消在途请求");
        eq(n + 1, f.notes(), "epoch 变化 notify 一次");
    }

    private static void closeStopsPublish() {
        Fixture f = new Fixture();
        f.api.daily = List.of(song(1, "日推一", true));
        f.ctl.loadDaily();
        int n = f.notes();
        f.ctl.close();
        eq(1, f.async.closeCount);
        f.async.succeed(0);
        eq(n, f.notes(), "close 后迟到回调不 notify");
        check(f.ctl.state().tracks().isEmpty(), "close 后不发布结果");
        int count = f.async.count();
        f.ctl.loadDaily();
        eq(count, f.async.count(), "close 后不再接受请求");
        f.ctl.close();
        eq(1, f.async.closeCount, "close 幂等");
    }

    // ------------------------------------------------------------------ 断言

    private static void check(boolean ok, String label) {
        if (!ok) {
            throw new AssertionError("FAIL: " + label);
        }
        passed++;
        System.out.println("PASS " + label);
    }

    private static void eq(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL: " + label + " (expected=" + expected + ", actual=" + actual + ")");
        }
        passed++;
        System.out.println("PASS " + label);
    }

    private static void contains(String haystack, String needle, String label) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("FAIL: " + label + " :: haystack=" + haystack);
        }
        passed++;
        System.out.println("PASS " + label);
    }

    /** 省略 label 时用调用点的 方法名:行号 作为标签。 */
    private static String autoLabel() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StackTraceElement caller = frames[2];
        return caller.getMethodName() + ":" + caller.getLineNumber();
    }

    private static void check(boolean ok) {
        check(ok, autoLabel());
    }

    private static void eq(Object expected, Object actual) {
        eq(expected, actual, autoLabel());
    }

    private static void contains(String haystack, String needle) {
        contains(haystack, needle, autoLabel());
    }

    // ------------------------------------------------------------------ 夹具

    private static Dto.Song song(long id, String name, boolean playable) {
        return new Dto.Song(id, name, "artist", "album", 200_000L, 0, playable, "");
    }

    private static Dto.Playlist playlist(long id, String name, List<Dto.Song> tracks) {
        return new Dto.Playlist(id, name, "creator", tracks.size(), tracks, "");
    }

    private static final class Fixture {
        final FakeApi api = new FakeApi();
        final FakeAsync async = new FakeAsync();
        final FakePlayback playback = new FakePlayback();
        final List<PlaylistController.State> states = new ArrayList<>();
        final long[] now = {0L};
        final PlaylistController ctl;

        Fixture() {
            ctl = new PlaylistController(states::add, async, api, playback, () -> "极高", () -> now[0]);
        }

        int notes() {
            return states.size();
        }
    }

    private static final class FakeAsync implements PlaylistController.Async {
        private final List<Object[]> jobs = new ArrayList<>();
        int cancelCount;
        int closeCount;

        @Override
        public <T> void run(PlaylistController.Fetcher<T> work, Consumer<T> success,
                            Consumer<Throwable> failure) {
            jobs.add(new Object[]{work, success, failure});
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }

        int count() {
            return jobs.size();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void succeed(int index) {
            Object[] job = jobs.get(index);
            Object value;
            try {
                value = ((PlaylistController.Fetcher) job[0]).fetch();
            } catch (Exception e) {
                throw new RuntimeException("fake work failed", e);
            }
            ((Consumer) job[1]).accept(value);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void succeed(int index, Object value) {
            ((Consumer) jobs.get(index)[1]).accept(value);
        }

        @SuppressWarnings("unchecked")
        void fail(int index, Throwable error) {
            ((Consumer<Throwable>) jobs.get(index)[2]).accept(error);
        }
    }

    private static final class FakeApi implements PlaylistController.Api {
        boolean loggedIn = true;
        long uid = 42L;
        long epoch;
        long uidSeen = -1L;
        int limitSeen = -1;
        List<Dto.PlaylistBrief> briefs = List.of();
        List<Dto.Song> liked = List.of();
        List<Dto.Song> daily = List.of();
        final Map<Long, Dto.Playlist> playlists = new HashMap<>();
        final Map<String, List<Dto.Song>> locals = new HashMap<>();
        final List<Long> playlistIds = new ArrayList<>();
        final List<String> localKeys = new ArrayList<>();
        int userPlaylistsCalls;
        int likedCalls;
        int dailyCalls;
        int playlistCalls;
        int localCalls;

        @Override
        public List<Dto.PlaylistBrief> userPlaylists(long uid, int limit) {
            userPlaylistsCalls++;
            uidSeen = uid;
            limitSeen = limit;
            return briefs;
        }

        @Override
        public List<Dto.Song> likedSongs(long uid) {
            likedCalls++;
            return liked;
        }

        @Override
        public List<Dto.Song> dailySongs() {
            dailyCalls++;
            return daily;
        }

        @Override
        public Dto.Playlist playlist(long id) {
            playlistCalls++;
            playlistIds.add(id);
            return playlists.get(id);
        }

        @Override
        public List<Dto.Song> localPlaylist(String key) {
            localCalls++;
            localKeys.add(key);
            return locals.get(key);
        }

        @Override
        public boolean loggedIn() {
            return loggedIn;
        }

        @Override
        public long uid() {
            return loggedIn ? uid : -1L;
        }

        @Override
        public long accountEpoch() {
            return epoch;
        }
    }

    private static final class FakePlayback implements PlaylistController.Playback {
        final Map<Integer, String> reports = new HashMap<>();
        List<Dto.Song> songs = List.of();
        int startRow = -1;
        String level = "";
        String source = "";
        int playCount;
        int lastRequestId = -1;
        private int nextRequestId = 1_000;

        @Override
        public int playPlaylist(List<Dto.Song> songs, int startRow, String level, String source) {
            this.songs = songs;
            this.startRow = startRow;
            this.level = level;
            this.source = source;
            this.playCount++;
            this.lastRequestId = nextRequestId++;
            return lastRequestId;
        }

        @Override
        public String report(int requestId) {
            return reports.get(requestId);
        }
    }
}
