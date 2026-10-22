import com.example.netease.net.Dto;
import com.example.netease.svc.LocalPlaylists;
import com.example.netease.ui.controller.LocalSaveController;
import com.example.netease.ui.controller.PlaylistController;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * NativeUiSession 会话生命周期与本地保存快照联动探针（REVIEW issue 4 + issue 5）。
 *
 * <p>覆盖两组回归：</p>
 * <ul>
 *   <li>issue 4（停用插件没有关闭登录异步通道）：真实调用生产 {@code NativeUiSession.start()/stop()}，
 *       向生产 {@code LoginAsync}/{@code LocalSaveAsync} 投递无网络的 {@code Fetcher}，断言 stop 后
 *       {@code native-ui-login}/{@code native-ui-local-save} 线程归零、LatestTask.closed=true、
 *       再次 start 重建全新通道且旧通道不会被复用；同时验证 {@code closeLogin()} 只关面板、不销毁通道。</li>
 *   <li>issue 5（同名歌单刷新后保存旧曲目）：用真实 {@code NativeUiState.playlist}、真实
 *       {@code NativeUiSession.submitLocalSave/captureLocalSave}，只把最终保存端口换成内存记录，
 *       断言“用户确认保存时拿到的是此刻可见的来源+列表”，并验证提交后切页不影响本次写盘。</li>
 * </ul>
 *
 * <p>全部通过打印 {@code NATIVE_UI_SESSION_RESULT=<n> PASS} 并 exit 0；任一失败打印
 * {@code FAIL <编号> (<用例>): <说明>} 并 exit 1。运行需要 classpath 同时包含插件类和宿主运行时：
 * 宿主安装包的 {@code app/ffmpeg-x64.dll} 扩展名是 dll，内容其实是 ZIP，里面有 NativeUiState
 * 初始化所依赖的 Compose runtime（{@code androidx.compose.runtime.SnapshotStateKt}）。</p>
 *
 * <p>探针只走反射 + 现有 Java 控制器 API，不引用 Compose 编译期 API，不联网、不写用户文件、不连接宿主。</p>
 */
public final class NativeUiSessionProbe {

    private static final String SESSION_CLASS = "com.example.netease.nativeui.NativeUiSession";
    private static final String UI_STATE_CLASS = "com.example.netease.nativeui.NativeUiState";
    private static final String LOGIN_THREAD = "native-ui-login";
    private static final String LOCAL_SAVE_THREAD = "native-ui-local-save";
    private static final String TICK_THREAD = "native-ui-tick";
    private static final String LOGIN_FETCHER = "com.example.netease.ui.controller.LoginController$Fetcher";
    private static final String LOCAL_SAVE_FETCHER = "com.example.netease.ui.controller.LocalSaveController$Fetcher";

    private static int passed;
    private static String currentCase = "";

    private static Object uiState;
    private static Method getPlaylist;
    private static Method setPlaylist;

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        requireHostRuntime();
        try {
            initUiStateHandle();
            run("停止释放登录异步通道", NativeUiSessionProbe::stopReleasesLoginChannel);
            run("重新启动重建全新通道", NativeUiSessionProbe::restartCreatesFreshChannel);
            run("关闭登录面板不销毁通道", NativeUiSessionProbe::closePanelKeepsChannel);
            run("停止释放本地保存通道", NativeUiSessionProbe::stopReleasesLocalSaveChannel);
            run("反复启停不累积线程", NativeUiSessionProbe::repeatedStartStopNoAccumulation);
            run("同名不同歌单保存当前列表", NativeUiSessionProbe::sameSourceDifferentPlaylist);
            run("同一来源刷新保存当前列表", NativeUiSessionProbe::sameSourceContentRefresh);
            run("提交后切页不影响本次写盘", NativeUiSessionProbe::snapshotIsolationAfterSubmit);
            run("捕获默认名回归", NativeUiSessionProbe::captureDefaultNameRegression);
            run("无可见歌单拒绝保存", NativeUiSessionProbe::submitWithoutPlaylistRejected);
        } catch (Throwable t) {
            if (t instanceof AssertionError && t.getMessage() != null) {
                System.out.println(t.getMessage());
            } else {
                System.out.println("FAIL " + (passed + 1) + " (" + currentCase + "): " + t);
                t.printStackTrace(System.out);
            }
            System.exit(1);
        }
        System.out.println("NATIVE_UI_SESSION_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 用例

    /**
     * issue 4 主路径：start → 投递一次真实异步请求（无网络）→ stop。
     * 基线缺陷是 stop 只关面板，LatestTask 的两个线程与 closed=false 会留下；修复后必须全部释放。
     */
    private static void stopReleasesLoginChannel() throws Exception {
        ensureStopped();
        Object session = session();
        call(session, "start");
        check(started(), "start() 后 started=true");
        Object controller = call(session, "login");
        check(controller != null, "start() 后登录控制器存在");
        Object channel = sessionField("loginAsync");
        check(channel != null, "登录异步通道被保存为会话字段（stop 才能显式关闭）");
        Object task = latestTask(channel);
        check(!taskClosed(task), "新通道的 LatestTask 尚未关闭");
        eq(0L, countThreads(LOGIN_THREAD), "未发请求时没有 native-ui-login 线程");

        submitProbeRequest(channel, true);
        waitFor("登录线程出现", 3_000L, () -> countThreads(LOGIN_THREAD) >= 2L);
        eq(2L, countThreads(LOGIN_THREAD), "一次发码请求后有 2 条 native-ui-login 线程（1 工作线程 + 1 超时定时器）");

        call(session, "stop");
        waitFor("登录线程退出", 3_000L, () -> countThreads(LOGIN_THREAD) == 0L);
        eq(0L, countThreads(LOGIN_THREAD), "stop() 后 native-ui-login 线程归零");
        check(taskClosed(task), "stop() 后旧 LatestTask.closed=true");
        check(sessionField("loginAsync") == null, "stop() 后会话不再持有旧通道");
        check(!started(), "stop() 后 started=false");
        check(call(session, "login") == null, "stop() 后登录控制器已摘除");
    }

    /** issue 4：LatestTask 关闭后不能复用（closed 后 run 会静默丢弃），start 必须重建全新通道。 */
    private static void restartCreatesFreshChannel() throws Exception {
        ensureStopped();
        Object session = session();
        call(session, "start");
        Object oldChannel = sessionField("loginAsync");
        Object oldTask = latestTask(oldChannel);
        submitProbeRequest(oldChannel, true);
        waitFor("登录线程出现", 3_000L, () -> countThreads(LOGIN_THREAD) >= 2L);
        call(session, "stop");
        waitFor("登录线程退出", 3_000L, () -> countThreads(LOGIN_THREAD) == 0L);

        call(session, "start");
        Object newChannel = sessionField("loginAsync");
        check(newChannel != null, "再次 start() 后重新持有登录异步通道");
        check(newChannel != oldChannel, "再次 start() 重建了新的 LoginAsync 实例");
        Object newTask = latestTask(newChannel);
        check(newTask != oldTask, "新通道持有全新的 LatestTask");
        check(!taskClosed(newTask), "新 LatestTask 未关闭，可以接受请求");
        check(taskClosed(oldTask), "旧 LatestTask 保持关闭，不会被复用");

        submitProbeRequest(newChannel, true);
        waitFor("重启后线程出现", 3_000L, () -> countThreads(LOGIN_THREAD) >= 2L);
        eq(2L, countThreads(LOGIN_THREAD), "重启后的通道能真正执行后台任务");

        call(session, "stop");
        waitFor("重启后线程退出", 3_000L, () -> countThreads(LOGIN_THREAD) == 0L);
        eq(0L, countThreads(LOGIN_THREAD), "第二次 stop() 后线程同样归零");
        check(taskClosed(newTask), "第二次 stop() 后新 LatestTask 也关闭");
    }

    /** issue 4 边界：closeLogin 只是“关闭登录面板”，用户还能再次打开；通道必须保留，stop 才销毁。 */
    private static void closePanelKeepsChannel() throws Exception {
        ensureStopped();
        Object session = session();
        call(session, "start");
        Object channel = sessionField("loginAsync");
        Object task = latestTask(channel);
        submitProbeRequest(channel, true);
        waitFor("登录线程出现", 3_000L, () -> countThreads(LOGIN_THREAD) >= 2L);

        call(session, "closeLogin");
        check(sessionField("loginAsync") == channel, "closeLogin() 不摘除异步通道字段");
        check(!taskClosed(task), "closeLogin() 不关闭 LatestTask（面板可再次打开）");
        check(call(session, "login") != null, "closeLogin() 保留登录控制器实例");
        call(session, "closeLogin");
        check(sessionField("loginAsync") == channel, "closeLogin() 幂等，重复调用也不销毁通道");
        check(!taskClosed(task), "重复 closeLogin() 后通道仍未关闭");

        call(session, "stop");
        waitFor("登录线程退出", 3_000L, () -> countThreads(LOGIN_THREAD) == 0L);
        eq(0L, countThreads(LOGIN_THREAD), "随后 stop() 才真正释放登录线程");
        check(taskClosed(task), "stop() 后通道关闭（与 closeLogin 语义区分开）");
    }

    /** issue 4：LocalSaveAsync 同类问题一并处理；stop 后本地保存通道也必须线程归零、task 关闭。 */
    private static void stopReleasesLocalSaveChannel() throws Exception {
        ensureStopped();
        Object session = session();
        call(session, "start");
        Object channel = sessionField("localSaveAsync");
        check(channel != null, "本地保存异步通道被保存为会话字段");
        Object task = latestTask(channel);
        check(!taskClosed(task), "新本地保存通道的 LatestTask 尚未关闭");
        submitProbeRequest(channel, false);
        waitFor("本地保存线程出现", 3_000L, () -> countThreads(LOCAL_SAVE_THREAD) >= 2L);
        eq(2L, countThreads(LOCAL_SAVE_THREAD), "一次本地保存请求后有 2 条 native-ui-local-save 线程");

        call(session, "stop");
        waitFor("本地保存线程退出", 3_000L, () -> countThreads(LOCAL_SAVE_THREAD) == 0L);
        eq(0L, countThreads(LOCAL_SAVE_THREAD), "stop() 后 native-ui-local-save 线程归零");
        check(taskClosed(task), "stop() 后本地保存 LatestTask.closed=true");
        check(sessionField("localSaveAsync") == null, "stop() 后会话不再持有旧本地保存通道");
    }

    /** “反复启停会累积”是 issue 4 的原始症状：连续 3 轮启停后线程数必须仍为 0。 */
    private static void repeatedStartStopNoAccumulation() throws Exception {
        ensureStopped();
        Object session = session();
        for (int round = 1; round <= 3; round++) {
            call(session, "start");
            Object channel = sessionField("loginAsync");
            submitProbeRequest(channel, true);
            waitFor("第 " + round + " 轮线程出现", 3_000L, () -> countThreads(LOGIN_THREAD) >= 2L);
            eq(2L, countThreads(LOGIN_THREAD), "第 " + round + " 轮发请求后线程数为 2");
            call(session, "stop");
            waitFor("第 " + round + " 轮线程退出", 3_000L, () -> countThreads(LOGIN_THREAD) == 0L);
            eq(0L, countThreads(LOGIN_THREAD), "第 " + round + " 轮 stop 后线程归零，没有累积");
            check(taskClosed(latestTask(channel)), "第 " + round + " 轮的旧 LatestTask 已关闭");
            check(sessionField("loginAsync") == null, "第 " + round + " 轮 stop 后通道字段为空（下一轮必然重建）");
        }
    }

    /**
     * issue 5 复现点：trace 里“可见 [2,3]，实际保存 [1]”——先捕获旧列表，再把同一来源换成另一张歌单。
     * 修复后 submitLocalSave 必须在确认保存的调用栈内读取当前 playlist，而不是用旧快照。
     */
    private static void sameSourceDifferentPlaylist() throws Exception {
        MemorySavePort m = installMemorySave();
        uiSetPlaylist(playlist("每日推荐", List.of(song(1))));
        call(session(), "captureLocalSave");
        uiSetPlaylist(playlist("每日推荐", List.of(song(2), song(3))));

        call(session(), "submitLocalSave", "我的本地歌单");
        eq(1, m.async.size(), "确认保存后立刻投递且只投递一次写盘任务");
        m.async.drain();
        eq(List.of(2L, 3L), m.savedIds.get(0), "同名不同歌单：保存的是当前可见 [2,3]，不是旧快照 [1]");
        eq("每日推荐", m.controller.state().sourceName(), "来源名与曲目来自同一份当前快照");
        eq(2, m.controller.state().trackCount(), "控制器内固定的曲目数量与可见列表一致");
        check(m.controller.state().saved(), "写盘完成后 saved=true");
        uninstallMemorySave();
    }

    /** issue 5：同一歌单内容刷新（来源名不变）也必须按当前列表保存。 */
    private static void sameSourceContentRefresh() throws Exception {
        MemorySavePort m = installMemorySave();
        uiSetPlaylist(playlist("我喜欢", List.of(song(1))));
        call(session(), "captureLocalSave");
        uiSetPlaylist(playlist("我喜欢", List.of(song(1), song(2))));

        call(session(), "submitLocalSave", "刷新保存");
        m.async.drain();
        eq(List.of(1L, 2L), m.savedIds.get(0), "同一来源内容刷新：保存的是当前可见 [1,2]");
        uninstallMemorySave();
    }

    /** issue 5：提交后用户切页（playlist 变化）不能改变本次已确认的写盘内容。 */
    private static void snapshotIsolationAfterSubmit() throws Exception {
        MemorySavePort m = installMemorySave();
        uiSetPlaylist(playlist("歌单A", List.of(song(2), song(3))));
        call(session(), "captureLocalSave");
        call(session(), "submitLocalSave", "提交后切页");
        eq(1, m.async.size(), "写盘任务已投递，尚未执行");

        // 模拟写盘期间 tick 捕获新页面：当前页面换成另一张歌单（来源名变化会触发 capture）。
        uiSetPlaylist(playlist("歌单B", List.of(song(9))));
        call(session(), "captureLocalSave");
        eq(1, m.async.size(), "切页后没有产生第二次写盘任务");
        m.async.drain();
        eq(List.of(2L, 3L), m.savedIds.get(0), "提交后切页不影响本次写盘：仍保存提交时的 [2,3]");
        eq("提交后切页", m.names.get(0), "写盘用的仍是用户确认时输入的名字");
        eq(1, m.savedIds.size(), "一次确认只写盘一次");
        uninstallMemorySave();
    }

    /** 回归保护：captureLocalSave 仍要负责“默认名取当前来源”，tick 的这条行为不能被本次修复破坏。 */
    private static void captureDefaultNameRegression() throws Exception {
        MemorySavePort m = installMemorySave();
        uiSetPlaylist(playlist("我的收藏", List.of(song(7))));
        uiSetSaveName("");
        call(session(), "captureLocalSave");
        eq("我的收藏", uiGetSaveName(), "captureLocalSave() 仍同步默认名（当前来源）");
        eq(1, m.controller.state().trackCount(), "capture 仍会捕获当前曲目");
        uninstallMemorySave();
    }

    /** 防御用例：页面没有歌单（切走/未加载）时，确认保存不能拿旧快照写盘。 */
    private static void submitWithoutPlaylistRejected() throws Exception {
        MemorySavePort m = installMemorySave();
        uiSetPlaylist(null);
        call(session(), "submitLocalSave", "空页面");
        eq(0, m.async.size(), "没有可见歌单时不投递写盘任务");
        check(m.savedIds.isEmpty(), "没有可见歌单时没有写盘调用");
        uninstallMemorySave();
    }

    // ------------------------------------------------------------------ 会话驱动

    private static Object session() throws Exception {
        return Class.forName(SESSION_CLASS).getField("INSTANCE").get(null);
    }

    private static boolean started() throws Exception {
        return sessionField("started") instanceof Boolean b && b;
    }

    private static Object sessionField(String name) throws Exception {
        Field f = Class.forName(SESSION_CLASS).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setSessionField(String name, Object value) throws Exception {
        Field f = Class.forName(SESSION_CLASS).getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void ensureStopped() throws Exception {
        Object session = session();
        if (started()) {
            call(session, "stop");
        }
        waitFor("tick 线程退出", 2_000L, () -> countThreads(TICK_THREAD) == 0L);
    }

    /** 反射调用生产 object 的 public 方法；参数按个数匹配（探针里没有同名重载歧义）。 */
    private static Object call(Object target, String name, Object... args) throws Exception {
        Method found = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == args.length) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            throw new IllegalStateException("找不到方法 " + name + "（参数 " + args.length + " 个）");
        }
        found.setAccessible(true);
        try {
            return found.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private static Object latestTask(Object channel) throws Exception {
        Field f = channel.getClass().getDeclaredField("task");
        f.setAccessible(true);
        return f.get(channel);
    }

    private static boolean taskClosed(Object task) throws Exception {
        Field f = task.getClass().getDeclaredField("closed");
        f.setAccessible(true);
        return f.getBoolean(task);
    }

    /**
     * 向生产 Async 通道投递一次无网络的探测请求：Fetcher 只 sleep 一小段再返回，
     * 覆盖“工作线程池 + 超时定时器”两条线程，但不触达短信/网络端口。
     */
    private static void submitProbeRequest(Object channel, boolean loginFetcher) throws Exception {
        Method run = null;
        for (Method candidate : channel.getClass().getMethods()) {
            if (candidate.getName().equals("run") && candidate.getParameterCount() == 3) {
                run = candidate;
                break;
            }
        }
        if (run == null) {
            throw new IllegalStateException("找不到 Async.run(Fetcher, Consumer, Consumer)");
        }
        run.setAccessible(true);
        Class<?> fetcherType = Class.forName(loginFetcher ? LOGIN_FETCHER : LOCAL_SAVE_FETCHER);
        Object work = Proxy.newProxyInstance(
                fetcherType.getClassLoader(),
                new Class<?>[]{fetcherType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "fetch":
                            Thread.sleep(150L);
                            return "probe-ok";
                        case "toString":
                            return "NativeUiSessionProbeFetcher";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        Consumer<Object> success = value -> { };
        Consumer<Throwable> failure = error -> { };
        run.invoke(channel, work, success, failure);
    }

    private static long countThreads(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && name.equals(t.getName()))
                .count();
    }

    private static void waitFor(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    // ------------------------------------------------------------------ NativeUiState / 内存保存端口

    private static void initUiStateHandle() throws Exception {
        Class<?> type = Class.forName(UI_STATE_CLASS);
        uiState = type.getField("INSTANCE").get(null);
        getPlaylist = type.getMethod("getPlaylist");
        setPlaylist = type.getMethod("setPlaylist", PlaylistController.State.class);
    }

    private static void uiSetPlaylist(PlaylistController.State state) throws Exception {
        setPlaylist.invoke(uiState, state);
    }

    private static void uiSetSaveName(String value) throws Exception {
        Method setter = Class.forName(UI_STATE_CLASS).getMethod("setSaveName", String.class);
        setter.invoke(uiState, value);
    }

    private static String uiGetSaveName() throws Exception {
        Method getter = Class.forName(UI_STATE_CLASS).getMethod("getSaveName");
        return (String) getter.invoke(uiState);
    }

    private static PlaylistController.State playlist(String sourceName, List<Dto.Song> tracks) {
        // PlaylistController.State 的 13 参构造（briefs, briefsLoaded, tracks, sourceName, message,
        // busy, pendingLabel, retryLabel, canRetry, pendingPlayRequest, playbackText, playing, accountEpoch）。
        return new PlaylistController.State(
                List.of(), false, tracks, sourceName, "", false, "", "", false, 0, "", false, 0L);
    }

    private static Dto.Song song(long id) {
        return new Dto.Song(id, "曲目" + id, "歌手", "专辑", 200_000L, 0, true, "");
    }

    private static MemorySavePort installMemorySave() throws Exception {
        ensureStopped();
        MemorySavePort port = new MemorySavePort();
        setSessionField("localSaveController", port.controller);
        return port;
    }

    private static void uninstallMemorySave() throws Exception {
        setSessionField("localSaveController", null);
    }

    /**
     * 真实 LocalSaveController + 内存 Ports/Async：save() 只记录曲目 id，不写盘；
     * Async.run 只入队，drain() 才执行，用来观察“提交后切页”与“写盘任务仍是提交时快照”。
     */
    private static final class MemorySavePort implements LocalSaveController.Ports {
        final QueuedAsync async = new QueuedAsync();
        final List<String> names = new ArrayList<>();
        final List<List<Long>> savedIds = new ArrayList<>();
        final LocalSaveController controller;

        MemorySavePort() {
            this.controller = new LocalSaveController(this, async, state -> { });
        }

        @Override
        public List<Dto.Song> tracks() {
            PlaylistController.State current = currentPlaylist();
            return current == null ? List.of() : current.tracks();
        }

        @Override
        public String sourceName() {
            PlaylistController.State current = currentPlaylist();
            return current == null ? "" : current.sourceName();
        }

        @Override
        public String save(String name, List<Dto.Song> songs) {
            names.add(name);
            List<Long> ids = new ArrayList<>();
            if (songs != null) {
                for (Dto.Song s : songs) {
                    ids.add(s.id());
                }
            }
            savedIds.add(ids);
            return "probe-key";
        }

        @Override
        public List<LocalPlaylists.Entry> list() {
            return List.of();
        }

        private static PlaylistController.State currentPlaylist() {
            try {
                return (PlaylistController.State) getPlaylist.invoke(uiState);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 排队式 Async：run 只登记，drain 时执行，保证断言发生在写盘之后且不依赖真实线程时序。 */
    private static final class QueuedAsync implements LocalSaveController.Async {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public <T> void run(LocalSaveController.Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            queue.addLast(() -> {
                try {
                    success.accept(work.fetch());
                } catch (Throwable error) {
                    failure.accept(error);
                }
            });
        }

        @Override
        public void cancel() {
            // 探针不取消：保留 queued job，用 drain 验证快照。
        }

        @Override
        public void close() {
            // 无资源可关。
        }

        int size() {
            return queue.size();
        }

        void drain() {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        }
    }

    // ------------------------------------------------------------------ 断言与运行前提

    private static void requireHostRuntime() {
        try {
            Class.forName("androidx.compose.runtime.SnapshotStateKt");
        } catch (Throwable t) {
            System.out.println("FATAL 缺少宿主运行时：NativeUiState 初始化需要 Compose runtime（androidx.compose.runtime.SnapshotStateKt）。");
            System.out.println("请把宿主安装包的 app/ffmpeg-x64.dll 加入运行 classpath（扩展名是 dll，内容其实是 ZIP）：");
            System.out.println("  java -Djava.awt.headless=true -cp \"<编译输出>;<插件 classes>;<宿主 app>/ffmpeg-x64.dll\" NativeUiSessionProbe");
            System.exit(2);
        }
    }

    private static void run(String name, ThrowingBody body) throws Exception {
        currentCase = name;
        body.run();
    }

    private static void check(boolean ok, String label) {
        passed++;
        if (!ok) {
            throw new AssertionError("FAIL " + passed + " (" + currentCase + "): " + label);
        }
        System.out.println("PASS " + passed + " " + label);
    }

    private static void eq(Object expected, Object actual, String label) {
        passed++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL " + passed + " (" + currentCase + "): " + label
                    + "（expected=[" + expected + "] actual=[" + actual + "]）");
        }
        System.out.println("PASS " + passed + " " + label);
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws Exception;
    }
}
