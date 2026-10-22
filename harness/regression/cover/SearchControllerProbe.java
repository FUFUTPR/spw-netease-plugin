import com.example.netease.net.Dto;
import com.example.netease.ui.controller.SearchController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.example.netease.ui.controller.SearchController.Phase.EMPTY;
import static com.example.netease.ui.controller.SearchController.Phase.FAILED;
import static com.example.netease.ui.controller.SearchController.Phase.IDLE;
import static com.example.netease.ui.controller.SearchController.Phase.RESULTS;
import static com.example.netease.ui.controller.SearchController.Phase.SEARCHING;

/**
 * SearchController 离线探针（批次 2，契约 build/_tmp/batch2-contracts.md）。
 *
 * <p>不联网、不碰 Swing：FakeAsync 保存每次 run 的回调并可手动/乱序触发，FakePlayback 记录
 * 投递参数并可指定某个请求编号的宿主反馈。断言风格对齐 harness/regression/AsyncProbe.java。</p>
 *
 * <p>跑通后打印 {@code SEARCH_CONTROLLER_RESULT=<N> PASS} 并以 0 退出。</p>
 */
public final class SearchControllerProbe {

    private static int passed;

    private SearchControllerProbe() {
    }

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
        passed++;
        System.out.println("PASS " + label);
    }

    private static Dto.Song song(long id, String name, boolean playable) {
        return new Dto.Song(id, name, "歌手" + id, "专辑" + id, 200_000L, 0, playable, "");
    }

    public static void main(String[] args) {
        initial();
        emptyKeywordKeepsResults();
        searchingKeepsOldRows();
        successReplacesRows();
        selectionRestoredById();
        emptyResult();
        failures();
        lateResultsDropped();
        playbackFlow();
        playbackTimeout();
        retries();
        accountChanged();
        snapshotAndWiring();
        closeSemantics();
        System.out.println("SEARCH_CONTROLLER_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 初始

    private static void initial() {
        Fixture f = new Fixture();
        SearchController.State s = f.state();
        check(s.phase() == IDLE, "初始 phase=IDLE");
        check("就绪".equals(s.message()), "初始 message=就绪");
        check(s.rows() != null && s.rows().isEmpty(), "初始 rows 为空且不为 null");
        check(s.sourceKeyword().isEmpty() && s.requestedKeyword().isEmpty(), "初始来源与请求关键词都是空串");
        check(s.selectedSongId() == null && s.selectedRow() == -1, "初始无选中");
        check(s.pendingPlayRequest() == 0 && !s.playing()
                && !s.searchRetryable() && !s.playRetryable(), "初始无播放请求、无重试");
        check(f.states.isEmpty(), "构造时不通知（初始状态由 state() 读取）");
    }

    // ------------------------------------------------------------------ 搜索

    private static void emptyKeywordKeepsResults() {
        Fixture f = new Fixture();
        f.controller.search("周杰伦");
        int request = f.lastCall();
        f.async.succeed(request, List.of(song(1001, "晴天", true), song(1002, "稻香", true)));
        SearchController.State before = f.state();
        check(before.phase() == RESULTS && before.rows().size() == 2, "成功搜索进入 RESULTS");

        int runsBefore = f.async.runs;
        f.controller.search("   ");
        SearchController.State after = f.state();
        check("请输入关键词".equals(after.message()), "空关键词提示请输入关键词");
        check(after.phase() == before.phase() && after.rows().equals(before.rows())
                && after.sourceKeyword().equals(before.sourceKeyword()), "空关键词保留旧列表与旧来源");
        check(f.async.runs == runsBefore && f.async.cancels == 1, "空关键词不发请求且取消在途请求");

        f.async.succeed(request, List.of(song(2001, "不该出现", true)));
        check(f.state().rows().equals(before.rows()) && f.state().phase() == RESULTS,
                "空关键词取消后，迟到结果不得改动旧列表");
    }

    private static void searchingKeepsOldRows() {
        Fixture f = new Fixture();
        f.controller.search("甲");
        f.async.succeed(f.lastCall(), List.of(song(1, "A", true)));
        f.controller.search("乙");
        SearchController.State s = f.state();
        check(s.phase() == SEARCHING && "搜索中…".equals(s.message()), "搜索中状态与提示");
        check(s.rows().size() == 1 && s.rows().get(0).id() == 1, "搜索中保留旧列表");
        check("甲".equals(s.sourceKeyword()) && "乙".equals(s.requestedKeyword()),
                "搜索中来源仍是旧关键词、请求关键词已更新");
        check(!s.searchRetryable() && !s.playing(), "搜索中不允许重试且停止旧播放轮询");
    }

    private static void successReplacesRows() {
        Fixture f = new Fixture();
        f.controller.search("  a b  ");
        check("a b".equals(f.state().requestedKeyword()), "关键词 trim 后记录");
        f.async.succeed(f.lastCall(), List.of(song(2, "B", true), song(3, "C", true), song(4, "D", true)));
        SearchController.State s = f.state();
        check(s.rows().size() == 3 && s.phase() == RESULTS, "成功替换列表");
        check("a b".equals(s.sourceKeyword()), "成功同时替换来源关键词");
        check("找到 3 条".equals(s.message()), "成功文案为找到 N 条");
        check(s.searchRetryable(), "成功后允许重试搜索");
    }

    private static void selectionRestoredById() {
        Fixture f = new Fixture();
        f.controller.search("q1");
        f.async.succeed(f.lastCall(), List.of(song(10, "A", true), song(11, "B", true), song(12, "C", true)));
        f.controller.select(11);
        check(f.state().selectedSongId() == 11L && f.state().selectedRow() == 1, "select(songId) 按 ID 选中");

        f.controller.search("q2");
        f.async.succeed(f.lastCall(), List.of(song(13, "D", true), song(12, "C", true), song(11, "B", true)));
        check(f.state().selectedSongId() == 11L && f.state().selectedRow() == 2,
                "新结果按歌曲 ID 恢复选中行");

        f.controller.search("q3");
        f.async.succeed(f.lastCall(), List.of(song(20, "E", true)));
        check(f.state().selectedSongId() == null && f.state().selectedRow() == -1,
                "歌曲不在新结果里时清空选中");

        f.controller.select(999);
        check(f.state().selectedSongId() == null, "select 不存在的 ID 被忽略");
        f.controller.selectRow(5);
        check(f.state().selectedRow() == -1, "selectRow 越界被忽略");
        f.controller.selectRow(0);
        check(f.state().selectedSongId() == 20L && f.state().selectedRow() == 0, "selectRow 选中当前行");
    }

    private static void emptyResult() {
        Fixture f = new Fixture();
        f.controller.search("无结果");
        f.async.succeed(f.lastCall(), List.of());
        SearchController.State s = f.state();
        check(s.phase() == EMPTY, "空结果 phase=EMPTY");
        check("没有找到结果，可换个关键词重试".equals(s.message()), "空结果文案");
        check(s.rows().isEmpty() && "无结果".equals(s.sourceKeyword()), "空结果仍记录来源关键词");

        f.controller.search("null 结果");
        f.async.succeed(f.lastCall(), null);
        check(f.state().phase() == EMPTY && f.state().rows().isEmpty(), "null 结果按空列表处理");
    }

    private static void failures() {
        Fixture f = new Fixture();
        f.controller.search("旧");
        f.async.succeed(f.lastCall(), List.of(song(30, "X", true)));
        List<Dto.Song> old = f.state().rows();
        f.controller.search("新");
        f.async.fail(f.lastCall(), new RuntimeException("网络\r\n超时"));
        SearchController.State s = f.state();
        check(s.phase() == FAILED, "失败 phase=FAILED");
        check(s.rows().equals(old) && "旧".equals(s.sourceKeyword()), "失败保留旧列表与旧来源");
        check("搜索失败：网络超时（保留上次结果）".equals(s.message()), "失败文案清理 CR/LF");
        check(s.searchRetryable(), "失败后允许重试搜索");

        f.controller.search("再新");
        f.async.fail(f.lastCall(), new RuntimeException("   "));
        check("搜索失败：网络或服务暂不可用，请重试（保留上次结果）".equals(f.state().message()),
                "异常信息为空时用兜底文案");
    }

    private static void lateResultsDropped() {
        Fixture f = new Fixture();
        f.controller.search("A");
        int first = f.lastCall();
        f.controller.search("B");
        f.async.succeed(first, List.of(song(40, "迟到A", true)));
        SearchController.State s = f.state();
        check(s.phase() == SEARCHING && s.rows().isEmpty(), "迟到的成功被丢弃（不覆盖新请求）");
        check("B".equals(s.requestedKeyword()) && "搜索中…".equals(s.message()),
                "迟到结果不改变请求关键词与提示");

        f.async.succeed(f.lastCall(), List.of(song(41, "B1", true)));
        check(f.state().phase() == RESULTS && f.state().rows().size() == 1, "最新请求的结果生效");
        f.async.fail(first, new RuntimeException("迟到的失败"));
        check(f.state().phase() == RESULTS && f.state().rows().size() == 1, "迟到的失败被丢弃");
    }

    // ------------------------------------------------------------------ 播放

    private static void playbackFlow() {
        Fixture f = new Fixture();
        f.controller.search("play");
        f.async.succeed(f.lastCall(), List.of(song(50, "可播", true), song(51, "无版权", false)));

        f.controller.playSelected();
        check("请先选择一首歌".equals(f.state().message()) && f.playback.playedSongIds.isEmpty(),
                "无选中时拒绝播放");
        check(!f.state().playRetryable(), "从未播放过时不可重试播放");

        f.controller.selectRow(1);
        f.controller.playSelected();
        check("这首歌当前无版权或不可播放".equals(f.state().message())
                && f.playback.playedSongIds.isEmpty(), "不可播曲目显示原因且不投递");

        f.controller.selectRow(0);
        f.controller.playSelected();
        SearchController.State s = f.state();
        check(f.playback.playedSongIds.size() == 1 && f.playback.playedSongIds.get(0) == 50L,
                "播放投递的是选中歌曲");
        check("lossless".equals(f.playback.levels.get(0)), "音质来自注入的 audioLevel");
        check("播放请求：正在准备「可播」".equals(s.message()) && s.message().equals(s.playbackText()),
                "播放后文案是已投递请求而不是已出声");
        check(!s.message().contains("出声") && !s.playbackText().contains("已实际"),
                "不得把已投递显示成已实际出声");
        check(s.playing() && s.pendingPlayRequest() == 901, "投递后进入 30 秒反馈窗口");
        check(s.playRetryable(), "已有播放记录时窗口内即可重试");

        f.playback.reply(901, "正在解析音源…");
        f.controller.tick();
        SearchController.State first = f.state();
        check(first.playing() && first.pendingPlayRequest() == 901, "首条 report 不提前结束窗口");
        check("播放请求：正在解析音源…".equals(first.playbackText())
                && first.playbackText().equals(first.message()), "首条 report 只回显到文案");
        int marks = f.states.size();
        f.controller.tick();
        check(f.states.size() == marks, "同值 report 不重复通知");

        f.playback.reply(901, "已提交给宿主播放器：可播 - 歌手50");
        f.controller.tick();
        SearchController.State latest = f.state();
        check("播放请求：已提交给宿主播放器：可播 - 歌手50".equals(latest.playbackText()),
                "窗口内随后续 report 持续更新文案");
        check(latest.playing() && latest.pendingPlayRequest() == 901, "30 秒内继续轮询，不因 report 非空结束");

        f.advance(SearchController.PLAYBACK_TIMEOUT_MS);
        f.controller.tick();
        SearchController.State timedOut = f.state();
        check(!timedOut.playing(), "到 30 秒后离开反馈窗口");
        check("播放请求：已提交给宿主播放器：可播 - 歌手50".equals(timedOut.playbackText()),
                "窗口结束保留最后一条 report，不覆盖成超时文案");
        check(timedOut.pendingPlayRequest() == 901, "窗口结束后仍保留请求编号供 UI 显示");
        check(timedOut.playRetryable(), "有播放记录即可重试");
        int afterWindow = f.states.size();
        f.controller.tick();
        check(f.states.size() == afterWindow, "窗口结束后不再轮询该请求");
    }

    private static void playbackTimeout() {
        Fixture f = new Fixture();
        f.controller.search("timeout");
        f.async.succeed(f.lastCall(), List.of(song(60, "慢", true)));
        f.controller.selectRow(0);
        f.controller.playSelected();
        int requestId = f.state().pendingPlayRequest();
        check(requestId > 0, "播放请求编号为正");

        f.advance(SearchController.PLAYBACK_TIMEOUT_MS - 1);
        int notifyBefore = f.states.size();
        f.controller.tick();
        check(f.state().playing() && f.state().pendingPlayRequest() == requestId,
                "未超时且无反馈时继续等待");
        check(f.states.size() == notifyBefore, "无变化时 tick 不通知");

        f.advance(1);
        f.controller.tick();
        SearchController.State s = f.state();
        check("播放请求：等待宿主反馈超时（30 秒），可重试".equals(s.playbackText()), "从未有 report 时超时文案");
        check(s.message().equals(s.playbackText()) && !s.playing(), "超时后离开反馈窗口");
        check(s.pendingPlayRequest() == requestId, "超时后仍保留请求编号");
        check(s.playRetryable(), "超时后允许重试播放");

        int notifyAfter = f.states.size();
        f.controller.tick();
        check(f.states.size() == notifyAfter, "窗口结束后 tick 无副作用");
    }

    private static void retries() {
        Fixture f = new Fixture();
        f.controller.search("第一次");
        f.async.succeed(f.lastCall(), List.of(song(70, "甲", true), song(71, "乙", true)));
        f.controller.selectRow(0);
        f.controller.playSelected();
        int firstRequest = f.state().pendingPlayRequest();
        f.controller.selectRow(1);
        check(f.state().selectedSongId() == 71L, "选中切到另一首");

        f.playback.reply(firstRequest, "已提交");
        f.controller.tick();
        check(f.state().playing(), "窗口内反馈不影响等待");
        f.advance(SearchController.PLAYBACK_TIMEOUT_MS);
        f.controller.tick();
        check(!f.state().playing(), "第一次播放请求窗口已结束");

        f.controller.retryPlayback();
        check(f.playback.playedSongIds.size() == 2 && f.playback.playedSongIds.get(1) == 70L,
                "播放重试仍针对上次播放的那首，而不是当前选中");
        check(f.state().selectedSongId() == 70L && f.state().selectedRow() == 0,
                "播放重试把选中切回该歌");
        check(f.state().playing() && f.state().pendingPlayRequest() == 902,
                "播放重试生成新的请求编号");
        check("播放请求：正在准备「甲」".equals(f.state().message()), "播放重试文案同首次投递");

        f.playback.reply(902, "取直链失败：版权受限");
        f.controller.tick();
        check("播放请求：取直链失败：版权受限".equals(f.state().playbackText()), "重试后的反馈正常回显");
        f.controller.retryPlayback();
        check(f.playback.playedSongIds.size() == 3 && f.state().pendingPlayRequest() == 903,
                "窗口内允许再次重试并生成新编号");
        check("播放请求：正在准备「甲」".equals(f.state().message()) && f.state().playing(),
                "重试重置窗口文案与 lastPlaybackReport");
        int marks = f.states.size();
        f.controller.tick();
        check(f.states.size() == marks && "播放请求：正在准备「甲」".equals(f.state().playbackText()),
                "新窗口不复用上个请求的旧 report");

        f.controller.search("第二次");
        f.async.succeed(f.lastCall(), List.of(song(80, "丙", true)));
        check(f.state().playRetryable(), "列表变化不撤销播放重试资格（按最后播放记录判断）");
        f.controller.retryPlayback();
        check("上次播放的歌曲已不在当前结果中，无法重试".equals(f.state().message()),
                "上次播放的歌不在结果中时拒绝重试");

        f.controller.search("   ");
        f.controller.retrySearch();
        check("第二次".equals(f.state().requestedKeyword()) && f.async.runs == 3,
                "搜索重试用上次提交的关键词");
        check(f.state().phase() == SEARCHING, "搜索重试重新走搜索中");

        Fixture fresh = new Fixture();
        fresh.controller.retrySearch();
        check("没有可重试的搜索".equals(fresh.state().message()), "没有搜索时重试提示");
        fresh.controller.retryPlayback();
        check("没有可重试的播放请求".equals(fresh.state().message()), "没有播放请求时重试提示");
    }

    // ------------------------------------------------------------------ 账号变化

    private static void accountChanged() {
        // 1) 在途搜索结果必须作废，且不能永久停在「搜索中…」
        Fixture f = new Fixture();
        f.controller.search("旧账号");
        int request = f.lastCall();
        f.controller.onAccountChanged();
        SearchController.State s = f.state();
        check(s.phase() == IDLE, "账号变化时无旧列表 → 回到 IDLE，不停留在搜索中");
        check("账号状态已变化，旧的搜索请求与播放反馈已作废".equals(s.message()), "账号变化给出说明文案");
        check(f.async.cancels == 1, "账号变化取消在途搜索请求");
        int marks = f.states.size();
        f.async.succeed(request, List.of(song(500, "迟到", true)));
        check(f.state().rows().isEmpty() && f.state().phase() == IDLE && f.states.size() == marks,
                "账号变化后迟到的搜索结果被丢弃，不覆盖列表");
        int runs = f.async.runs;
        f.controller.search("新账号");
        f.async.succeed(f.lastCall(), List.of(song(501, "新的", true)));
        check(f.async.runs == runs + 1 && f.state().rows().size() == 1, "账号变化后仍可发起新搜索");

        // 2) 有旧列表 + 播放中：列表/选中保留，播放反馈作废
        Fixture g = new Fixture();
        g.controller.search("保留");
        g.async.succeed(g.lastCall(), List.of(song(600, "甲", true), song(601, "乙", true)));
        g.controller.selectRow(1);
        g.controller.playSelected();
        int playRequest = g.state().pendingPlayRequest();
        g.playback.reply(playRequest, "已提交给宿主播放器");
        g.controller.tick();
        check(g.state().playing() && g.state().pendingPlayRequest() == playRequest, "账号变化前窗口内有反馈");

        g.controller.onAccountChanged();
        SearchController.State after = g.state();
        check(after.rows().size() == 2 && after.selectedSongId() == 601L && after.selectedRow() == 1,
                "账号变化保留已拿到的列表与选中");
        check(after.pendingPlayRequest() == 0 && !after.playing() && !after.playRetryable()
                && after.playbackText().isEmpty(), "账号变化清空旧播放请求与反馈");
        check("账号状态已变化，旧的搜索请求与播放反馈已作废".equals(after.message()),
                "有播放记录时也给出账号变化文案");
        int marksAfter = g.states.size();
        g.controller.tick();
        check(g.states.size() == marksAfter, "账号变化后 tick 不再轮询旧播放请求");
        g.controller.retryPlayback();
        check("没有可重试的播放请求".equals(g.state().message()), "账号变化后旧播放重试失效");

        // 3) 搜索中且有旧列表：回退 RESULTS，不丢旧列表
        Fixture h = new Fixture();
        h.controller.search("第一");
        h.async.succeed(h.lastCall(), List.of(song(700, "旧", true)));
        h.controller.search("第二");
        int inflight = h.lastCall();
        check(h.state().phase() == SEARCHING, "第二个搜索在途");
        h.controller.onAccountChanged();
        check(h.state().phase() == RESULTS && h.state().rows().size() == 1,
                "账号变化时有旧列表 → 回退 RESULTS 并保留旧列表");
        check("第一".equals(h.state().sourceKeyword()) && "第二".equals(h.state().requestedKeyword()),
                "回退后来源仍是已成功的关键词");
        check(h.state().searchRetryable(), "回退后允许重试搜索");
        h.async.succeed(inflight, List.of(song(701, "迟到二", true)));
        check(h.state().rows().size() == 1 && h.state().rows().get(0).id() == 700L,
                "第二个搜索的迟到结果同样作废");

        // 4) close 之后 onAccountChanged 无副作用
        Fixture d = new Fixture();
        d.controller.search("关闭");
        d.controller.close();
        SearchController.State atClose = d.state();
        int closes = d.async.closes;
        int cancels = d.async.cancels;
        d.controller.onAccountChanged();
        check(d.state().equals(atClose) && d.async.cancels == cancels && d.async.closes == closes,
                "close 后账号变化不改状态、不再取消");
    }

    // ------------------------------------------------------------------ 快照与装配

    private static void snapshotAndWiring() {
        Fixture f = new Fixture();
        List<Dto.Song> mutable = new ArrayList<>();
        mutable.add(song(1, "A", true));
        f.controller.search("snapshot");
        f.async.succeed(f.lastCall(), mutable);
        mutable.add(song(2, "B", true));
        check(f.state().rows().size() == 1, "结果列表是快照，外部改动不影响状态");

        SearchController wired = SearchController.createDefault(null);
        check(wired != null && wired.state().phase() == IDLE,
                "createDefault 在 headless 下可装配（Runnable::run）");
        if (wired != null) {
            wired.close();
        }
    }

    // ------------------------------------------------------------------ 关闭

    private static void closeSemantics() {
        Fixture f = new Fixture();
        f.controller.search("close");
        int request = f.lastCall();
        f.controller.close();
        check(f.async.closes == 1, "close 关闭请求通道");
        SearchController.State atClose = f.state();
        int marks = f.states.size();
        f.async.succeed(request, List.of(song(90, "迟到", true)));
        check(f.state().equals(atClose) && f.states.size() == marks, "close 后成功回调不通知不改状态");
        f.async.fail(request, new RuntimeException("迟到失败"));
        check(f.state().equals(atClose) && f.states.size() == marks, "close 后失败回调不通知");
        f.controller.tick();
        check(f.state().equals(atClose) && f.states.size() == marks, "close 后 tick 无副作用");
        int runs = f.async.runs;
        f.controller.search("再搜索");
        check(f.async.runs == runs && f.state().equals(atClose), "close 后不再接受搜索");
        f.controller.close();
        check(f.async.closes == 1, "close 幂等");
    }

    // ------------------------------------------------------------------ 假实现

    private static final class Fixture {
        final FakeAsync async = new FakeAsync();
        final FakePlayback playback = new FakePlayback();
        final List<SearchController.State> states = new ArrayList<>();
        final long[] now = {1_000_000L};
        final SearchController controller = new SearchController(states::add, async, playback,
                () -> "lossless", () -> now[0]);

        SearchController.State state() {
            return controller.state();
        }

        int lastCall() {
            return async.runs - 1;
        }

        void advance(long millis) {
            now[0] += millis;
        }
    }

    private static final class FakeAsync implements SearchController.Async {
        final List<Object[]> calls = new ArrayList<>();
        int runs;
        int cancels;
        int closes;

        @Override
        public <T> void run(SearchController.Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            runs++;
            calls.add(new Object[]{work, success, failure});
        }

        void succeed(int index, Object value) {
            accept(calls.get(index)[1], value);
        }

        void fail(int index, Throwable error) {
            accept(calls.get(index)[2], error);
        }

        @SuppressWarnings("unchecked")
        private static <X> void accept(Object callback, X value) {
            ((Consumer<X>) callback).accept(value);
        }

        @Override
        public void cancel() {
            cancels++;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class FakePlayback implements SearchController.Playback {
        final List<Long> playedSongIds = new ArrayList<>();
        final List<String> levels = new ArrayList<>();
        final Map<Integer, String> reports = new HashMap<>();
        int nextId = 900;

        @Override
        public int playOnline(Dto.Song song, String level) {
            playedSongIds.add(song.id());
            levels.add(level);
            return ++nextId;
        }

        @Override
        public String report(int requestId) {
            return reports.get(requestId);
        }

        void reply(int requestId, String text) {
            reports.put(requestId, text);
        }
    }
}
