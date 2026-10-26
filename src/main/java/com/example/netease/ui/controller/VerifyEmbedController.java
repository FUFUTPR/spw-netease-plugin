package com.example.netease.ui.controller;

import com.example.netease.core.PluginLog;
import com.example.netease.svc.AccountService;
import com.example.netease.ui.VerifyHostPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 内嵌验证控制器（批次 4，契约 {@code build/_tmp/batch4-contracts.md}，需求 docs/PLAN.md §9）。
 *
 * <p>本类把旧的 JDialog 承载实现（0.12.0 已删除）里「起助手 / 等 ready / 摆 rect / 显隐 /
 * 收 cookie / 有界关闭」那套跨进程逻辑抽成<b>无 UI 依赖</b>的状态机：不 import Swing / AWT / Kotlin /
 * Compose，也不自己起界面；只把 {@link State} 不可变快照交给 {@link Listener}，由 Compose 页面按快照
 * 渲染。助手仍然是 {@code classes\verify-host\VerifyHost.exe}（C# + WebView2），协议见契约 §2。</p>
 *
 * <p><b>线程规矩</b>：所有公共方法可被任意线程调用；所有状态字段由同一把内部锁保护，监听器通知在锁内
 * 同步完成（保证「先改状态、再通知」且顺序与变更一致）。<b>监听器回调里不得调用本控制器的方法</b>
 * （open/close/retry/setBounds/setVisible/state 都不要调）—— 回调只应把快照交给 UI 线程渲染；
 * 回调抛出的任何异常都会被吞掉并记一条 warn，不影响状态机。</p>
 *
 * <p><b>启动时序</b>：{@link #open} 先发布 {@link Phase#STARTING}，再向 {@code Host#requestCookies} 要
 * 一份 cookie，<b>最多等 {@value #COOKIE_WAIT_MS}ms</b>；拿到 cookie 或超时后启动助手（启动动作只在
 * open/retry 自己的调用栈里发生，迟到的回调永远不会重新拉起进程）。每次 open/retry 生成新的 8 位十六
 * 进制 session id；旧进程/旧会话的输出按 session 代次直接丢弃。</p>
 *
 * <p><b>日志红线</b>（契约 §3.10）：日志里不出现验证地址原文、cookie 值、助手 stdout 整行原文；只记
 * 类型 / 长度 / 退出码 / 会话 id 这类非敏感信息。验证页回传的 cookie 头只经 {@code Host#mergeCookies}
 * 交给账号侧，句柄本身不落字段、不进日志。</p>
 *
 * <p><b>关闭</b>：{@link #close()} 有界（发 {@code close} → 等 600ms → destroy → 等 400ms →
 * destroyForcibly）、幂等、绝不抛异常；关闭后 {@link Phase#CLOSED} 只通知一次，之后 setBounds /
 * setVisible 不再写进程，也不会再产生状态通知。</p>
 *
 * <p>构造器分两种：生产用 {@link #VerifyEmbedController(Listener)}（真实 {@link AccountService} 与
 * {@link VerifyHostPaths}）；包私有注入版供 {@code harness/regression/cover} 探针替换进程启动器与
 * cookie/epoch/日志来源（契约 §1）。</p>
 */
public final class VerifyEmbedController {

    /** 助手协议前缀（C# 侧 {@code Say()} 加的；解析前必须先剥掉）。 */
    private static final String PREFIX = "verify-host: ";
    /** 助手可执行文件名（目录由 {@link VerifyHostPaths#helperDir()} 解析）。 */
    private static final String EXE = "VerifyHost.exe";
    /** 助手按这个窗口类名认领宿主主窗口（Compose 主窗口是 SunAwtFrame）。 */
    private static final String PARENT_CLASS = "SunAwtFrame";
    /** 助手从启动到 ready 的上限（秒参数，契约固定 15000）。 */
    private static final String READY_TIMEOUT_MS = "15000";
    /** 网页区背景色（助手 --bg 参数，契约固定 262626）。 */
    private static final String BG = "262626";
    /** 等账号线程给 cookie 的上限；超时就用空 cookie 起（契约 §3.3）。 */
    static final long COOKIE_WAIT_MS = 1_500L;
    /** 关闭：先等助手优雅退出。 */
    private static final long CLOSE_WAIT_MS = 600L;
    /** 关闭：destroy 后再等多久才 destroyForcibly。 */
    private static final long DESTROY_WAIT_MS = 400L;
    /** 控制器的日志 tag（生产实现走 PluginLog）。 */
    private static final String TAG = "verify-embed";

    // ---- 契约文案（UI 直接显示、探针逐字断言，一个标点都不能改） ----

    static final String TEXT_URL_EMPTY = "验证地址为空，无法启动内嵌验证";
    static final String TEXT_STARTING = "正在启动内嵌验证…";
    static final String TEXT_LOADING = "正在加载安全验证页面…";
    static final String TEXT_READY = "验证页已就绪：请在上方验证区域内完成验证，完成后点「我已完成，重试」";
    static final String TEXT_CLOSED = "验证区域已关闭";
    static final String TEXT_PARENT_GONE = "宿主主窗口已消失，验证区域已关闭";
    static final String TEXT_PARENT_TIMEOUT = "没能在超时前唯一定位宿主主窗口，可重试（请关闭其它插件窗口后重试）";
    static final String TEXT_BROKEN = "验证组件进程已失去连接，可重试";

    /** 阶段（冻结枚举，顺序即语义分组，不要重排）。 */
    public enum Phase { IDLE, STARTING, READY, FAILED, CLOSED }

    /**
     * 一次性状态快照（不可变；{@code message}/{@code sessionId} 永不为 null，宽高非负）。
     *
     * @param phase     当前阶段
     * @param message   面向用户的一行反馈（IDLE 时为空串）
     * @param sessionId 本次验证会话 id（8 位十六进制小写；还没开过为空串）
     * @param embedded  助手是否已回报 adopted（网页区域已接管）
     * @param retryable FAILED 时是否值得重试
     * @param width     最近一次 setBounds 的逻辑宽（0 表示还没有）
     * @param height    最近一次 setBounds 的逻辑高（0 表示还没有）
     */
    public record State(Phase phase, String message, String sessionId, boolean embedded, boolean retryable,
                        int width, int height) {
        public State {
            phase = phase == null ? Phase.IDLE : phase;
            message = message == null ? "" : message;
            sessionId = sessionId == null ? "" : sessionId;
            width = Math.max(0, width);
            height = Math.max(0, height);
        }
    }

    /** 状态监听器（回调在控制器锁内同步执行；实现里不要调用控制器方法）。 */
    public interface Listener {
        void onStateChanged(State state);
        default void onDismissRequested() { }
    }

    /** 探针注入点：怎么起助手进程（生产实现用 ProcessBuilder；探针换成 FakeVerifyHost）。 */
    public interface Launcher {
        /**
         * 起一个进程；实现方负责 {@code redirectErrorStream(true)}。
         *
         * @param exePath 解析出的助手 exe（可能为 null，例如组件缺失；探针实现忽略它）
         * @param args    命令行参数（不含 exePath 本身）
         */
        Process start(String exePath, List<String> args) throws IOException;
    }

    /** 探针注入点：cookie / 账号纪元 / 日志的来路（生产实现接 {@link AccountService} 与 PluginLog）。 */
    public interface Host {
        /** 异步取一份 cookie 头；回调可能永不发生，也可能迟迟才发生。 */
        void requestCookies(Consumer<String> callback);

        /** 验证页回传的 cookie 头（按名合并回插件 jar）。 */
        void mergeCookies(String header);
        default void mergeCookies(String header, long expectedEpoch) { mergeCookies(header); }
        default void mergeCookies(String header, long expectedEpoch, java.util.function.BooleanSupplier valid) {
            if (valid.getAsBoolean()) mergeCookies(header, expectedEpoch);
        }

        /** 账号纪元：变化即表示登录态换代，旧输出一律丢弃。 */
        long accountEpoch();

        /** 日志：level 取 i / w / d。 */
        void log(String level, String message);
    }

    // ---- 生产实现（探针不碰这里） ----

    private static final Launcher DEFAULT_LAUNCHER = (exePath, args) -> {
        if (exePath == null) {
            throw new IOException("未找到 " + EXE);
        }
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add(exePath);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Path parent = Path.of(exePath).getParent();
        if (parent != null) {
            pb.directory(parent.toFile());
        }
        return pb.start();
    };

    private static final Host DEFAULT_HOST = new Host() {
        @Override
        public void requestCookies(Consumer<String> callback) {
            try {
                AccountService.withCookies(callback);
            } catch (Throwable t) {
                PluginLog.w(TAG, "取 cookie 失败（照常启动）：" + t.getClass().getSimpleName());
                callback.accept("");
            }
        }

        @Override
        public void mergeCookies(String header) {
            try {
                AccountService.mergeCookies(header, AccountService.sessionEpoch());
            } catch (Throwable t) {
                PluginLog.w(TAG, "回灌验证页 cookie 失败：" + t.getClass().getSimpleName());
            }
        }

        @Override
        public long accountEpoch() {
            try {
                return AccountService.sessionEpoch();
            } catch (Throwable t) {
                return 0L;
            }
        }

        @Override public void mergeCookies(String header, long expectedEpoch) {
            AccountService.mergeCookies(header, expectedEpoch);
        }
        @Override public void mergeCookies(String header, long expectedEpoch, java.util.function.BooleanSupplier valid) {
            AccountService.mergeCookies(header, expectedEpoch, valid);
        }

        @Override
        public void log(String level, String message) {
            String lv = level == null ? "i" : level;
            switch (lv) {
                case "w" -> PluginLog.w(TAG, message);
                case "d" -> PluginLog.d(TAG, message);
                case "e" -> PluginLog.e(TAG, message);
                default -> PluginLog.i(TAG, message);
            }
        }
    };

    // ---- 状态（全部由 lock 保护） ----

    private final Object lock = new Object();
    private final Listener listener;
    private final Launcher launcher;
    private final Host host;

    private State state = new State(Phase.IDLE, "", "", false, false, 0, 0);

    /** 会话代次：每次关闭 / 每次 open 递增；旧进程的输出按它丢弃。 */
    private volatile long generation;
    /** 当前（或最近一次）会话 id。 */
    private String sessionId = "";
    /** 当前验证地址 / 来源 / 父窗口标题 / 内容区逻辑尺寸 / 缩放（retry 复用）。 */
    private String url = "";
    private String source = "";
    private String parentTitle = "";
    private int contentWidth;
    private int contentHeight;
    private double scale = 1.0;
    /** 是否已经被 client 行的实测值校正过 scale（校正后 setBounds 一律用校正值）。 */
    private boolean scaleCorrected;

    /** open 时记录的账号纪元；每行解析前比对，变化即丢弃该行（不改状态）。 */
    private long epoch;

    private Process process;
    private volatile long parentXid;
    private BufferedWriter stdin;
    private boolean embedded;

    /** 最近一次 setBounds 的逻辑矩形（client 校正后重算 pending 用）。 */
    private int lastLogicalX;
    private int lastLogicalY;
    private int lastLogicalW;
    private int lastLogicalH;

    /** 尚未发送的物理矩形（READY/adopted 之前缓存）。 */
    private int[] pendingRect;
    /** 最近一次真正写进 stdin 的物理矩形。 */
    private int[] sentRect;
    /** 最近一次真正写进 stdin 的显隐期望（null = 还没发过）。 */
    private Boolean sentVisible;
    /** Compose 侧期望的显隐（adopted 后按它补发）。 */
    private boolean visibleDesired = true;

    // ---------------------------------------------------------------- 构造

    /** 生产构造器：用真实 {@link AccountService} 取/并 cookie，日志走 PluginLog。 */
    public VerifyEmbedController(Listener listener) {
        this(listener, DEFAULT_LAUNCHER, DEFAULT_HOST);
    }

    /** The bundled WebView2 child-window helper requires a Windows runtime. */
    public static boolean nativeSupported() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return true;
            try { return com.example.netease.ui.LinuxVerifySurface.supported(); }
        catch (Throwable unsupported) { return false; }
    }

    /**
     * 探针构造器（package-private，供 harness/regression/cover 使用）：
     * launcher 决定怎么起助手进程，host 提供 cookie/epoch/日志。
     */
    VerifyEmbedController(Listener listener, Launcher launcher, Host host) {
        this.listener = listener;
        this.launcher = launcher == null ? DEFAULT_LAUNCHER : launcher;
        this.host = host == null ? DEFAULT_HOST : host;
    }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 起助手。url 为空 ⇒ FAILED「验证地址为空，无法启动内嵌验证」。
     *
     * @param url           验证地址（值不进日志）
     * @param source        来源文案（"扫码" / "验证码登录"，只进日志）
     * @param parentTitle   给助手做窗口身份核对（空串 ⇒ 不按标题过滤）
     * @param contentWidth  宿主窗口内容区逻辑宽
     * @param contentHeight 宿主窗口内容区逻辑高
     * @param scale         AWT defaultTransform 的缩放（如 1.25）
     */
    public void open(String url, String source, String parentTitle,
                     int contentWidth, int contentHeight, double scale) {
        String u = url == null ? "" : url;
        if (u.isBlank()) {
            shutdownCurrentSilently();
            synchronized (lock) {
                publish(Phase.FAILED, TEXT_URL_EMPTY, false);
            }
            return;
        }
        synchronized (lock) {
            Phase ph = state.phase();
            if ((ph == Phase.STARTING || ph == Phase.READY) && u.equals(this.url)) {
                // 契约 §3.1：同一份地址已在开 ⇒ 不重开、不新建会话、不重复 notify。
                return;
            }
        }
        // 换了地址 / 已失败 / 已关闭：先静默清掉旧的，再按新参数起。
        shutdownCurrentSilently();
        startSession(u, source, parentTitle, contentWidth, contentHeight, scale);
    }

    /**
     * Compose 布局变化时调用：逻辑坐标（window 内）→ 物理像素只在这里换算一次（{@link Math#round}）。
     *
     * <p>与上次发送值相同 ⇒ 不发；adopted 之前只缓存，adopted 后补发最新值；client 行校正过 scale
     * 之后一律用校正值换算。</p>
     */
    public void setBounds(int logicalX, int logicalY, int logicalW, int logicalH, double scale) {
        Process terminate = null;
        synchronized (lock) {
            if (state.phase() == Phase.CLOSED) {
                return;
            }
            int w = Math.max(0, logicalW);
            int h = Math.max(0, logicalH);
            lastLogicalX = logicalX;
            lastLogicalY = logicalY;
            lastLogicalW = w;
            lastLogicalH = h;
            double s = effectiveScaleLocked(scale);
            int[] phys = physicalRect(logicalX, logicalY, w, h, s);
            pendingRect = phys;
            publishSize(w, h);
            Phase ph = state.phase();
            if (ph != Phase.STARTING && ph != Phase.READY) {
                return;
            }
            if (!embedded && ph != Phase.READY) {
                return; // 契约 §3.5：adopted 之前只缓存
            }
            if (sentRect != null && Arrays.equals(sentRect, phys)) {
                return;
            }
            if (sendLocked(rectCommand(phys))) {
                sentRect = phys.clone();
            } else {
                terminate = failWriteLocked();
            }
        }
        if (terminate != null) {
            boundedShutdown(terminate);
        }
    }

    /**
     * 显示/隐藏网页区域；adopted 之前（或进程还没就绪）只记期望值，adopted 后按期望补发。
     * 与上次发送值相同 ⇒ 不发。
     */
    public void setVisible(boolean visible) {
        Process terminate = null;
        synchronized (lock) {
            if (state.phase() == Phase.CLOSED) {
                return;
            }
            visibleDesired = visible;
            Phase ph = state.phase();
            if (ph != Phase.STARTING && ph != Phase.READY) {
                return;
            }
            if (!embedded && ph != Phase.READY) {
                return;
            }
            if (sentVisible != null && sentVisible == visible) {
                return;
            }
            if (sendLocked(visible ? "show" : "hide")) {
                sentVisible = visible;
            } else {
                terminate = failWriteLocked();
            }
        }
        if (terminate != null) {
            boundedShutdown(terminate);
        }
    }

    public void focus() {
        synchronized (lock) { if (state.phase() == Phase.READY) sendLocked("focus"); }
    }

    /** 关闭后用同一 url/尺寸重开（新 session id）；未打开时等价于 open 的参数校验。 */
    public void retry() {
        String u;
        String src;
        String title;
        int cw;
        int ch;
        double sc;
        synchronized (lock) {
            u = url;
            src = source;
            title = parentTitle;
            cw = contentWidth;
            ch = contentHeight;
            sc = scale;
        }
        if (u == null || u.isBlank()) {
            shutdownCurrentSilently();
            synchronized (lock) {
                publish(Phase.FAILED, TEXT_URL_EMPTY, false);
            }
            return;
        }
        close();
        startSession(u, src, title, cw, ch, sc);
    }

    /**
     * 有界关闭：发 close → waitFor(600ms) → destroy → waitFor(400ms) → destroyForcibly；
     * 绝不抛异常；幂等。关闭后 state=CLOSED、message=「验证区域已关闭」、isOpen()=false。
     */
    public void close() {
        Process p;
        synchronized (lock) {
            if (state.phase() == Phase.CLOSED) {
                return;
            }
            BufferedWriter out = stdin;
            p = detachProcessLocked();
            publish(Phase.CLOSED, TEXT_CLOSED, false);
            if (out != null) {
                writeQuietly(out, "close");
            }
        }
        boundedShutdown(p);
    }

    /** 当前状态快照（不可变；任意线程可调）。 */
    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** STARTING / READY 时为 true。 */
    public boolean isOpen() {
        synchronized (lock) {
            Phase ph = state.phase();
            return ph == Phase.STARTING || ph == Phase.READY;
        }
    }

    // ---------------------------------------------------------------- 启动

    /** 新会话：发布 STARTING → 等 cookie（≤1500ms）→ 起进程。 */
    private void startSession(String url, String source, String parentTitle,
                              int contentWidth, int contentHeight, double scale) {
        if (launcher == DEFAULT_LAUNCHER && !nativeSupported()) {
            synchronized (lock) {
                this.url = url;
                publish(Phase.FAILED, "当前系统暂不支持此内嵌验证后端；需要 Windows WebView2，或 Linux X11/XWayland 与系统 WebKitGTK 4.1。", false);
            }
            return;
        }
        final long gen;
        synchronized (lock) {
            gen = ++generation;
            this.sessionId = newSessionId();
            this.url = url;
            this.source = source == null ? "" : source;
            this.parentTitle = parentTitle == null ? "" : parentTitle;
            this.contentWidth = Math.max(0, contentWidth);
            this.contentHeight = Math.max(0, contentHeight);
            this.scale = saneScale(scale);
            this.scaleCorrected = false;
            this.epoch = safeEpoch();
            this.embedded = false;
            this.sentRect = null;
            this.sentVisible = null;
            publish(Phase.STARTING, TEXT_STARTING, false);
            host.log("i", "准备启动内嵌验证：来源=" + this.source
                    + "，地址长度=" + url.length() + "（值不进日志）"
                    + "，内容区=" + this.contentWidth + "x" + this.contentHeight
                    + "，缩放=" + this.scale);
        }
        String cookie = awaitCookie(gen);
        boolean go;
        synchronized (lock) {
            go = gen == generation && state.phase() == Phase.STARTING;
        }
        if (go) {
            if (launcher == DEFAULT_LAUNCHER && com.example.netease.core.DataPaths.nativeLinux()) {
                Thread startup = new Thread(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    try {
                        while ((parentXid = com.example.netease.ui.LinuxVerifySurface.handle()) == 0) {
                            synchronized (lock) { if (gen != generation) return; }
                            if (System.nanoTime() >= deadline) { fail(gen, "Linux 验证承载区域未就绪，可重试"); return; }
                            Thread.sleep(50);
                        }
                        launch(gen, cookie);
                    } catch (Throwable error) { fail(gen, "Linux 验证组件缺失或不可用，请检查 GTK3 / WebKitGTK 4.1 依赖"); }
                }, "verify-embed-start");
                startup.setDaemon(true); startup.start();
            } else launch(gen, cookie);
        }
    }

    /** 问 host 要 cookie；最多等 {@link #COOKIE_WAIT_MS}。迟到的回调只记录，不触发启动。 */
    private String awaitCookie(long gen) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] box = new String[1];
        try {
            host.requestCookies(ck -> {
                boolean accept;
                synchronized (lock) {
                    accept = gen == generation && state.phase() == Phase.STARTING && box[0] == null;
                    if (accept) {
                        box[0] = ck == null ? "" : ck.trim();
                    }
                }
                if (accept) {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            host.log("w", "取 cookie 失败（照常启动）：" + t.getClass().getSimpleName());
        }
        try {
            latch.await(COOKIE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return box[0] == null ? "" : box[0];
        }
    }

    /** 组装命令行（顺序由契约 §2 固定，探针逐项断言）并起进程。 */
    private void launch(long gen, String cookie) {
        String exePath = resolveExePath();
        List<String> args;
        String sid;
        synchronized (lock) {
            if (gen != generation || state.phase() != Phase.STARTING) {
                return;
            }
            sid = sessionId;
            args = buildArgs(launcher == DEFAULT_LAUNCHER ? "" : cookie);
        }
        Process p;
        try {
            p = launcher.start(exePath, args);
        } catch (Throwable t) {
            fail(gen, "验证组件启动失败：" + t.getClass().getSimpleName());
            return;
        }
        if (p == null) {
            fail(gen, "验证组件启动失败：启动器返回 null");
            return;
        }
        boolean keep;
        synchronized (lock) {
            keep = gen == generation && state.phase() == Phase.STARTING;
            if (keep) {
                process = p;
                stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
                if (launcher == DEFAULT_LAUNCHER) {
                    try {
                        String payload = com.example.netease.core.Json.stringify(java.util.Map.of(
                                "url", url, "cookies", cookie == null ? "" : cookie, "session", sid, "scale", scale));
                        stdin.write("init " + java.util.Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
                        stdin.newLine(); stdin.flush();
                    } catch (IOException error) { destroyQuietly(p); fail(gen, "验证组件初始化管道已关闭"); return; }
                }
            }
        }
        if (!keep) {
            destroyQuietly(p);
            return;
        }
        Thread pump = new Thread(() -> pump(gen, p), "verify-embed-out");
        pump.setDaemon(true);
        pump.start();
    }

    /** 组装参数（不含 exePath）；契约固定顺序。调用方需持有 lock。 */
    private List<String> buildArgs(String cookie) {
        List<String> args = new ArrayList<>(32);
        if (launcher == DEFAULT_LAUNCHER) args.add("--init-stdin");
        else { args.add("--url"); args.add(url); }
        args.add("--pid");
        args.add(String.valueOf(ProcessHandle.current().pid()));
        args.add("--session");
        args.add(sessionId);
        args.add("--parent-class");
        args.add(PARENT_CLASS);
        if (launcher == DEFAULT_LAUNCHER && com.example.netease.core.DataPaths.nativeLinux()) {
            args.add("--parent-xid"); args.add(Long.toUnsignedString(parentXid));
        }
        if (!parentTitle.isEmpty()) {
            args.add("--parent-title");
            args.add(parentTitle);
        }
        int expectW = (int) Math.round(contentWidth * scale);
        int expectH = (int) Math.round(contentHeight * scale);
        args.add("--expect-client");
        args.add(expectW + "x" + expectH);
        String ck = cookie == null ? "" : cookie.trim();
        if (!ck.isEmpty()) {
            args.add("--cookie");
            args.add(ck);
        }
        args.add("--data-dir");
        args.add(String.valueOf(VerifyHostPaths.dataDir()));
        args.add("--timeout-ms");
        args.add(READY_TIMEOUT_MS);
        args.add("--bg");
        args.add(BG);
        return args;
    }

    /** 助手 exe 路径（可能为 null：目录或文件缺失）。 */
    private static String resolveExePath() {
        try {
            if (com.example.netease.core.DataPaths.nativeLinux()) return VerifyHostPaths.linuxFile("VerifyHost").toString();
            Path dir = VerifyHostPaths.helperDir();
            if (dir == null) {
                return null;
            }
            Path exe = dir.resolve(EXE);
            return Files.isRegularFile(exe) ? exe.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String newSessionId() {
        return String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());
    }

    // ---------------------------------------------------------------- 输出泵

    private void pump(long gen, Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                onLine(gen, line);
            }
        } catch (Throwable t) {
            host.log("w", "读验证组件输出失败：" + t.getClass().getSimpleName());
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (Throwable ignored) {
            // 已被 destroy：退出码不重要
        }
        onExit(gen, code);
    }

    /** 解析一行助手输出（先剥前缀再比关键字；载荷值不进日志）。 */
    private void onLine(long gen, String raw) {
        boolean dismiss = false;
        String line = stripPrefix(raw);
        if (line.isEmpty()) {
            return;
        }
        boolean doClose = false;
        Process terminate = null;
        synchronized (lock) {
            Phase ph = state.phase();
            if (gen != generation || ph == Phase.CLOSED || ph == Phase.FAILED || ph == Phase.IDLE) {
                return;
            }
            if (epochChangedLocked()) {
                host.log("d", "账号纪元已变化 ⇒ 丢弃一行助手输出（长度 " + line.length() + "）");
                return;
            }
            // 顺序敏感：parent-* / nav-* / cookie-* 必须排在通用前缀之前。
            if (line.startsWith("parent-timeout")) {
                publish(Phase.FAILED, TEXT_PARENT_TIMEOUT, true);
            } else if (line.startsWith("parent-ambiguous ")) {
                String n = tokenValue(line, "count=");
                publish(Phase.FAILED, "宿主主窗口匹配到 " + (n == null ? "?" : n) + " 个（要求唯一），已拒绝挂载", true);
            } else if (line.startsWith("parent-invalid ")) {
                publish(Phase.FAILED, "宿主主窗口身份核对失败：" + line.substring("parent-invalid ".length()) + "，已拒绝挂载", true);
            } else if (line.equals("parent-gone exit") || line.startsWith("parent-gone exit")) {
                terminate = detachProcessLocked();
                publish(Phase.FAILED, TEXT_PARENT_GONE, false);
            } else if (line.startsWith("parent-client-mismatch ")) {
                String got = tokenValue(line, "got=");
                String expect = tokenValue(line, "expect=");
                publish(Phase.FAILED, "宿主窗口尺寸核对失败（got " + (got == null ? "?" : got)
                        + "，expect " + (expect == null ? "?" : expect) + "），已拒绝挂载", true);
            } else if (line.startsWith("parent ")) {
                host.log("i", "助手已找到并认领宿主主窗口");
            } else if (line.startsWith("start ")) {
                String sid = tokenValue(line, "session=");
                if (sid != null && !sid.equals(sessionId)) {
                    host.log("w", "助手 start 行的会话 id 不符（期望 " + sessionId + "，收到 " + sid + "）⇒ 丢弃该行");
                    return;
                }
                host.log("d", "助手已确认本次会话");
            } else if (line.startsWith("adopted")) {
                embedded = true;
                publish(state.phase(), state.message(), state.retryable());
                if (!embedded) {
                    // 已被并发关闭/失败：什么都不写
                } else {
                    terminate = flushQueuedLocked();
                }
            } else if (line.startsWith("client ")) {
                onClientLocked(line);
            } else if (line.startsWith("webview2 ")) {
                String ver = line.substring("webview2 ".length()).trim();
                host.log("i", "WebView2 运行时长度=" + ver.length());
            } else if (line.equals("ready")) {
                publish(Phase.READY, TEXT_READY, false);
            } else if (line.startsWith("nav-start")) {
                publish(state.phase(), TEXT_LOADING, state.retryable());
            } else if (line.startsWith("nav-done ")) {
                String[] tk = line.split("\\s+");
                boolean ok = tk.length > 1 && "True".equalsIgnoreCase(tk[1]);
                if (ok) {
                    publish(state.phase(), TEXT_READY, false);
                } else {
                    String code = tk.length > 2 ? tk[2] : "?";
                    publish(Phase.FAILED, "验证页加载失败（错误码 " + code + "），可在页面内重试", true);
                }
            } else if (line.startsWith("cookie-in ")) {
                String n = line.substring("cookie-in ".length()).trim();
                host.log("i", "登录 cookie 已灌进验证页：" + n + " 项");
            } else if (line.startsWith("cookies ") || line.startsWith("cookies-session ")) {
                String header;
                if (line.startsWith("cookies-session ")) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length != 3 || !sessionId.equals(parts[1])) return;
                    header = parts[2].trim();
                } else header = line.substring("cookies ".length()).trim();
                if (!header.isEmpty()) {
                    host.log("d", "验证页回传 cookie 头（长度 " + header.length() + "，值不进日志）");
                    host.mergeCookies(header, epoch, () -> generation == gen);
                }
            } else if (line.startsWith("cookie-dump-failed ")) {
                String cls = line.substring("cookie-dump-failed ".length()).trim();
                host.log("w", "验证页 cookie 导出失败：" + cls);
            } else if (line.equals("escape-requested")) {
                dismiss = true;
                doClose = true;
            } else if (line.equals("window-close-requested")) {
                doClose = true;
            } else if (line.startsWith("fail ")) {
                publish(Phase.FAILED, "验证组件失败：" + line.substring("fail ".length()), true);
            } else {
                host.log("d", "未识别的助手输出（长度 " + line.length() + "）");
            }
        }
        if (dismiss && listener != null) {
            try { listener.onDismissRequested(); } catch (Throwable ignored) { }
        }
        if (doClose) {
            close();
        }
        if (terminate != null) {
            boundedShutdown(terminate);
        }
    }

    /** adopted 后补发缓存的 rect / 显隐；失败 ⇒ FAILED 并返回要清掉的进程。 */
    private Process flushQueuedLocked() {
        if (pendingRect != null) {
            if (!sendLocked(rectCommand(pendingRect))) {
                return failWriteLocked();
            }
            sentRect = pendingRect.clone();
        }
        if (sentVisible == null) {
            if (!sendLocked(visibleDesired ? "show" : "hide")) {
                return failWriteLocked();
            }
            sentVisible = visibleDesired;
        }
        return null;
    }

    /** client w=&lt;w&gt; h=&lt;h&gt;：用实测值校正 scale（契约 §2）。 */
    private void onClientLocked(String line) {
        int w = intValue(tokenValue(line, "w="));
        int h = intValue(tokenValue(line, "h="));
        if (w <= 0 || contentWidth <= 0) {
            return;
        }
        int expected = (int) Math.round(contentWidth * scale);
        if (Math.abs(expected - w) <= 2) {
            return;
        }
        double corrected = (double) w / contentWidth;
        scale = corrected;
        scaleCorrected = true;
        host.log("w", "助手实测客户区宽度 " + w + " 与预期 " + expected + " 相差超过 2 ⇒ 缩放校正为 " + corrected);
        if (!embedded && lastLogicalW > 0) {
            pendingRect = physicalRect(lastLogicalX, lastLogicalY, lastLogicalW, lastLogicalH, scale);
        }
        if (h > 0 && contentHeight > 0) {
            host.log("d", "助手实测客户区高度=" + h + "（高度只记录，不参与缩放校正）");
        }
    }

    /** 进程退出（EOF）：READY 前 ⇒ 提前退出；READY 后 ⇒ 已退出；已 FAILED/CLOSED 不改写。 */
    private void onExit(long gen, int code) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            Phase ph = state.phase();
            if (ph != Phase.STARTING && ph != Phase.READY) {
                return;
            }
            process = null;
            stdin = null;
            embedded = false;
            sentRect = null;
            sentVisible = null;
            if (ph == Phase.STARTING) {
                publish(Phase.FAILED, com.example.netease.core.DataPaths.nativeLinux() && code == 127
                        ? "缺少 Linux 验证依赖：GTK3、WebKitGTK 4.1 或 JSON-GLib，请安装后重试"
                        : "验证组件提前退出（退出码 " + code + "）", true);
            } else {
                publish(Phase.FAILED, "验证组件已退出（退出码 " + code + "），可重试", true);
            }
        }
    }

    // ---------------------------------------------------------------- 写进程 / 清理

    private boolean sendLocked(String command) {
        BufferedWriter out = stdin;
        if (out == null) {
            return false;
        }
        try {
            out.write(command);
            out.newLine();
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写失败 ⇒ FAILED「验证组件进程已失去连接，可重试」并摘掉进程（由调用方在锁外 bounded 关闭）。 */
    private Process failWriteLocked() {
        Process p = detachProcessLocked();
        publish(Phase.FAILED, TEXT_BROKEN, true);
        return p;
    }

    /** 摘掉当前进程/管道，代次自增（旧输出全部作废）；不改变状态快照。 */
    private Process detachProcessLocked() {
        Process p = process;
        process = null;
        stdin = null;
        embedded = false;
        sentRect = null;
        sentVisible = null;
        generation++;
        return p;
    }

    /** 静默清掉旧进程（不发 CLOSED），open/retry 换会话前用。 */
    private void shutdownCurrentSilently() {
        Process p;
        synchronized (lock) {
            BufferedWriter out = stdin;
            p = detachProcessLocked();
            if (out != null) {
                writeQuietly(out, "close");
            }
        }
        boundedShutdown(p);
    }

    /** 有界关闭：等 600ms → destroy → 等 400ms → destroyForcibly；绝不抛异常。 */
    private void boundedShutdown(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 已被销毁 / 中断
        }
        if (alive(p)) {
            destroyQuietly(p);
        }
        try {
            p.waitFor(DESTROY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 同上
        }
        if (alive(p)) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
                // 已经退出
            }
        }
    }

    private static boolean alive(Process p) {
        try {
            return p.isAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void destroyQuietly(Process p) {
        try {
            p.destroy();
        } catch (Throwable ignored) {
            // 已经退出
        }
    }

    private static void writeQuietly(BufferedWriter out, String command) {
        try {
            out.write(command);
            out.newLine();
            out.flush();
        } catch (Throwable ignored) {
            // 管道已断：关闭流程照常继续
        }
    }

    /** 启动失败 / 启动器异常：只有代次仍匹配时才落 FAILED。 */
    private void fail(long gen, String message) {
        synchronized (lock) {
            if (gen != generation || state.phase() != Phase.STARTING) {
                return;
            }
            publish(Phase.FAILED, message, true);
        }
    }

    // ---------------------------------------------------------------- 状态发布 / 工具

    private void publish(Phase phase, String message, boolean retryable) {
        applyState(new State(phase, message, sessionId, embedded, retryable, state.width(), state.height()));
    }

    private void publishSize(int width, int height) {
        State s = state;
        if (s.width() == width && s.height() == height) {
            return;
        }
        applyState(new State(s.phase(), s.message(), s.sessionId(), s.embedded(), s.retryable(), width, height));
    }

    private void applyState(State next) {
        if (next.equals(state)) {
            return;
        }
        state = next;
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onStateChanged(next);
        } catch (Throwable t) {
            host.log("w", "状态监听器回调异常（已吞掉，不影响状态机）：" + t.getClass().getSimpleName());
        }
    }

    private boolean epochChangedLocked() {
        long now;
        try {
            now = host.accountEpoch();
        } catch (Throwable t) {
            return false;
        }
        return now != epoch;
    }

    private long safeEpoch() {
        try {
            return host.accountEpoch();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private double effectiveScaleLocked(double arg) {
        if (scaleCorrected) {
            return scale > 0 ? scale : 1.0;
        }
        if (saneScale(arg) > 0) {
            scale = saneScale(arg);
        }
        return scale > 0 ? scale : 1.0;
    }

    private static double saneScale(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) {
            return 1.0;
        }
        return v;
    }

    private static int[] physicalRect(int x, int y, int w, int h, double s) {
        return new int[] {
                (int) Math.round(x * s),
                (int) Math.round(y * s),
                (int) Math.round(w * s),
                (int) Math.round(h * s)
        };
    }

    private String rectCommand(int[] rect) {
        return "rect " + rect[0] + " " + rect[1] + " " + rect[2] + " " + rect[3]
                + (launcher == DEFAULT_LAUNCHER && com.example.netease.core.DataPaths.nativeLinux() ? " " + scale : "");
    }

    private static String stripPrefix(String raw) {
        String line = raw == null ? "" : raw.trim();
        int at = line.indexOf(PREFIX);
        if (at >= 0) {
            line = line.substring(at + PREFIX.length()).trim();
        }
        return line;
    }

    private static String tokenValue(String line, String key) {
        for (String token : line.split("\\s+")) {
            if (token.startsWith(key)) {
                return token.substring(key.length());
            }
        }
        return null;
    }

    private static int intValue(String text) {
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
