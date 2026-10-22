package com.example.netease.ui.controller;

import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 登录面板业务控制器（批次 3；契约 {@code build/_tmp/batch3-contracts.md}「队友 1」，需求 docs/PLAN.md
 * §8.1/§8.2）。
 *
 * <p>本类把 旧 {@code LoginDialog}（0.12.0 已删除） 里「取码 / 刷新 / 切短信 / 发码 / 登录 / 风控 / 关闭」那套行为抽成
 * <b>无 UI 依赖</b>的状态机：不 import 任何 Swing / AWT / Kotlin / Compose 类型，不自己起线程，也不碰
 * cookie / 密码，只把 {@link State} 快照交给 UI 渲染。所有网络与账号事实都由 {@link Ports} 提供，
 * {@code AccountService} 仍是账号状态的唯一事实来源。</p>
 *
 * <p><b>行为契约要点</b>（探针逐条断言）：</p>
 * <ul>
 *   <li>{@link #open()} 幂等：已打开再调只把当前快照再通知一次，不重复取码、不重置已显示的二维码；</li>
 *   <li>{@link #tick()} 按 {@link Ports#nowMs()} 节流：{@value #QR_TICK_MS} ms 内最多读一次
 *       {@link Ports#status()}，同一毫秒重复 tick 不重复读；短信档不再打二维码接口；</li>
 *   <li>扫码被风控时自动迁移到风控档并打开一次验证页（同一份验证地址不重开）；</li>
 *   <li>手动刷新去重：距上次 {@code startQr} 调用不足 {@link #QR_MIN_GAP_MS} ms 只提示不取码；</li>
 *   <li>发码冷却 {@value #COOLDOWN_SEC} 秒按 tick 递减，切换档位不清零；</li>
 *   <li>{@link #close()} 幂等，只调 {@code pauseQrWatch} / {@code closeVerify} 各一次，<b>不是退出账号</b>
 *       （{@code loggedIn} 保持不变）。</li>
 * </ul>
 *
 * <p><b>线程规矩</b>：所有公共方法可被任意线程调用；状态变更与 listener 通知在同一把锁内完成，保证
 * 「先真正改状态、再通知」且通知顺序与变更一致。{@link Async#run} <b>在锁外</b>调用 —— 生产实现
 * {@code LatestTask} 会在自己的监视器里执行回调，持锁调它就有反向锁序死锁的风险。{@link Ports} 的调用
 * 都在锁内（它们都是读快照 / 投递任务的短操作）。listener 里不要阻塞等另一个线程调本类。</p>
 *
 * <p><b>迟到结果</b>：每次 {@link #open()} / {@link #close()} / 登录成功都会递增内部会话号；异步回调先比对
 * 会话号，过期结果直接丢弃 —— 关掉面板（或已经登录成功）之后到达的回调不会把界面重新点亮，也不会把
 * {@code busy} 卡在 true。</p>
 *
 * <p><b>生命周期</b>：{@link Async} 由调用方（UI 适配层）创建并持有，本类只使用它的 {@code run}；
 * 不用 {@code cancel}/{@code close} —— 契约的关闭语义只要求停二维码轮询与验证窗，在途网络请求由适配层
 * 自己收尾，本类用会话号保证结果不会落地。</p>
 *
 * <p><b>凭据纪律</b>：本类没有任何密码 / cookie / token 字段；验证码只作为 {@link #submitCode} 的入参
 * 传给 {@link Ports#loginSms}，不落字段、不进日志、不进 {@link State}。</p>
 */
public final class LoginController {

    /** 扫码状态轮询间隔：每秒最多读一次 {@link Ports#status()}。 */
    public static final int QR_TICK_MS = 1_000;

    /** 两次取码的最小间隔（手动刷新去重；契约常量 2000 ms）。 */
    public static final long QR_MIN_GAP_MS = 2_000L;

    /** 「获取验证码」冷却秒数。 */
    public static final int COOLDOWN_SEC = 60;

    /** 手机号：11 位，13~19 开头（与 {@code SmsLogin} / {@code LoginDialog} 同一判据）。 */
    private static final Pattern PHONE_RE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 短信验证码：4~6 位数字。 */
    private static final Pattern CODE_RE = Pattern.compile("^\\d{4,6}$");

    /** 登录来源标记：契约固定的文案，直接进伺服端登录请求。 */
    private static final String SOURCE = "原生登录页";

    // ---- 契约文案（UI 直接显示、探针逐字断言，一个标点都不能改） ----

    private static final String TEXT_QR_WAIT = "正在获取二维码…";
    private static final String TEXT_QR_FAIL = "二维码获取失败，请重试";
    private static final String TEXT_QR_SCAN = "用网易云音乐 App 扫一下这个码";
    private static final String TEXT_QR_SCANNED = "已扫码，请在手机上确认";
    private static final String TEXT_QR_EXPIRED = "二维码已过期，点「刷新二维码」";
    private static final String TEXT_ALREADY_LOGGED_IN = "当前已经登录，无需重复扫码；如需换号请先退出登录";
    private static final String TEXT_LOGIN_OK = "登录成功：";
    private static final String TEXT_CURRENT_ACCOUNT = "当前账号";
    private static final String TEXT_LOGIN_VIA_QR = "扫码";
    private static final String TEXT_LOGIN_VIA_SMS = "验证码";
    private static final String TEXT_RISK_NEED_VERIFY = "登录需要先完成行为验证";
    private static final String TEXT_RISK_VERIFY_OPENED = "已打开验证页面，完成后点「我已完成，重试」";
    private static final String TEXT_REFRESH_TOO_SOON = "刚刷新过，请稍候再试";
    private static final String TEXT_PHONE_INVALID = "请输入 11 位手机号";
    private static final String TEXT_CODE_INVALID = "请输入 4-6 位验证码";
    private static final String TEXT_SENDING = "正在发送验证码…";
    private static final String TEXT_SEND_SENT = "验证码已发送到 ";
    private static final String TEXT_SEND_FAIL = "发送失败：";
    private static final String TEXT_SEND_ERROR = "发送验证码失败，请重试";
    private static final String TEXT_LOGGING_IN = "正在登录…";
    private static final String TEXT_SMS_RISK = "需要完成行为验证";
    private static final String TEXT_BAD_SECRET = "手机号或验证码不对";
    private static final String TEXT_LOGIN_ERROR = "登录失败，请重试";
    private static final String TEXT_VERIFY_URL_EMPTY = "验证地址为空，请返回重新获取";
    private static final String TEXT_RETRYING = "正在重新获取登录状态…";

    /** 主体形态：扫码（默认）/ 验证码 / 风控验证。 */
    public enum Mode { QR, SMS, RISK }

    /**
     * 阶段（冻结枚举，顺序即语义分组，不要重排）。
     *
     * <p>{@code CLOSED} 只表示面板已关闭；它<b>不代表退出账号</b> —— 关窗回滚由 {@code pauseQrWatch}
     * 自己负责，控制器的 {@code loggedIn} 不会被关闭动作改写。</p>
     */
    public enum Phase { IDLE, WAITING_QR, QR_READY, SCANNED, EXPIRED, ERROR, SENDING, CODE_SENT, LOGGING_IN,
        RISK, LOGGED_IN, CLOSED }

    /**
     * 一次性状态快照（不可变；{@code message}/{@code qrKey}/{@code riskUrl}/{@code loginVia} 永不为 null，
     * {@code qrPng} 可为 null，{@code cooldownLeft} 非负）。
     *
     * @param mode              当前主体形态
     * @param phase             当前阶段
     * @param message           面向用户的一行反馈
     * @param qrKey             当前展示的二维码 key；没展示过为空串
     * @param qrPng             当前展示的二维码 PNG 字节；没有为 null（数组按引用传递，不拷贝）
     * @param qrExpired         二维码是否已过期（展开「变黑」态）
     * @param busy              是否有在途请求（发码 / 登录）
     * @param cooldownLeft      发码冷却剩余秒数（0 = 可用）
     * @param riskUrl           风控行为验证地址；没有为空串
     * @param riskFromQr        本次风控是否由扫码引来（决定主按钮语义）
     * @param verifyOpened      验证页是否已经交给用户打开过
     * @param loggedIn          当前账号是否已登录
     * @param wasLoggedInAtOpen 打开面板前就已经登录（扫码成功判定要用它区分「恢复的旧登录态」）
     * @param loginVia          本次登录方式（"扫码" / "验证码"）；未登录为空串
     */
    public record State(Mode mode, Phase phase, String message, String qrKey, byte[] qrPng, boolean qrExpired,
                        boolean busy, int cooldownLeft, String riskUrl, boolean riskFromQr, boolean verifyOpened,
                        boolean loggedIn, boolean wasLoggedInAtOpen, String loginVia) {

        public State {
            mode = mode == null ? Mode.QR : mode;
            phase = phase == null ? Phase.IDLE : phase;
            message = message == null ? "" : message;
            qrKey = qrKey == null ? "" : qrKey;
            riskUrl = riskUrl == null ? "" : riskUrl;
            loginVia = loginVia == null ? "" : loginVia;
            cooldownLeft = Math.max(0, cooldownLeft);
        }
    }

    /** 账号 / 网络端口。实现方可以阻塞，但会被在锁内调用 ⇒ 只做短操作（读快照、投递任务）。 */
    public interface Ports {
        boolean startQr(boolean force);

        AccountService.LoginStatus status();

        Dto.QrSession qrImage();

        boolean qrRiskRequired();

        String qrVerifyUrl();

        void retryQrAfterRisk();

        boolean pauseQrWatch();

        boolean wasLoggedInAtOpen();

        Dto.SmsResult sendCode(String phone, String source);

        NeteaseApi.LoginOutcome loginSms(String phone, String code, String source);

        void openVerify(String url, boolean fromQr, Runnable onRetry);

        void closeVerify();

        String maskPhone(String phone);

        long nowMs();
    }

    /** 回到 UI 线程前的工作单元（{@code fetch()} 可抛受检异常）。 */
    public interface Fetcher<T> {
        T fetch() throws Exception;
    }

    /** 异步通道；生产实现是 {@code ui.LatestTask}，探针用可手动 flush 的假实现。 */
    public interface Async {
        <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure);

        void cancel();

        void close();
    }

    /** 状态观察者；每次状态真正变化后调用（{@link #open()} 幂等要求重发一次，是唯一例外）。 */
    public interface Listener {
        void onState(State state);
    }

    private final Ports ports;
    private final Async async;
    private final Listener listener;
    private final Object lock = new Object();

    /** 打开面板前是否已登录（构造时冻结；扫码成功判定全程用它）。 */
    private final boolean wasLoggedInAtOpen;

    // ---- 受 lock 保护的字段 ----
    private Mode mode = Mode.QR;
    private Phase phase = Phase.IDLE;
    private String message = "";
    private String qrKey = "";
    private byte[] qrPng;
    private boolean qrExpired;
    private boolean busy;
    private int cooldownLeft;
    private String riskUrl = "";
    private boolean riskFromQr;
    private boolean verifyOpened;
    private boolean loggedIn;
    private String loginVia = "";
    private boolean open;
    /** 本次会话是否已经收尾（close 或登录成功）；收尾后 close 幂等、在途回调作废。 */
    private boolean closed;
    /** 会话号：open / close / 登录成功各递增一次，异步回调靠它丢弃迟到结果。 */
    private int session;
    /** 已展示过的二维码 key（null = 还没展示过，允许首次 key 为空串）。 */
    private String shownQrKey;
    /** 上次 startQr 调用时刻 + 是否调用过（刷新 2 秒去重，含 open 的那次）。 */
    private long lastStartQrAtMs;
    private boolean startQrCalled;
    /** status 轮询节流：上次读数时刻 + 是否读过（未读过 ⇒ 下一次 tick 立刻读）。 */
    private long lastStatusAtMs;
    private boolean statusRead;
    /** 发码冷却的计时锚点（毫秒）。 */
    private long cooldownAnchorMs;
    /** 上一次已发布（或已构造）的快照，用于「没变就不通知」。 */
    private State lastPublished;

    /**
     * 构造：只读两件事实（当前是否已登录、打开前是否已登录），不取码、不通知。
     *
     * @param ports    账号 / 网络端口（非 null）
     * @param async    异步通道（非 null）
     * @param listener 状态观察者（可为 null，探针可不挂）
     */
    public LoginController(Ports ports, Async async, Listener listener) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.async = Objects.requireNonNull(async, "async");
        this.listener = listener;
        AccountService.LoginStatus st = readStatus();
        this.loggedIn = st != null && st.state() == AccountService.State.LOGGED_IN;
        boolean before = false;
        try {
            before = ports.wasLoggedInAtOpen();
        } catch (Throwable ignored) {
            // 读不到就当成「打开前没登录」：宁可不把扫码结果当旧登录态
        }
        this.wasLoggedInAtOpen = before;
        this.lastPublished = snapshotLocked();
    }

    /** 当前状态快照（不可变，任意线程可读）。 */
    public State state() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    /** 登录面板是否打开。 */
    public boolean isOpen() {
        synchronized (lock) {
            return open;
        }
    }

    /**
     * 打开面板（幂等）。
     *
     * <p>首次：进扫码档、提示取码、调用 {@code startQr(false)} 恰好一次；取码抛异常只落到
     * {@link Phase#ERROR} + 固定文案，异常绝不穿出。已打开再调：只把当前快照再通知一次
     * （契约要求 Listener 收到 1 次），不重复取码、不重置已显示的二维码。</p>
     */
    public void open() {
        synchronized (lock) {
            if (open) {
                publishLocked();
                return;
            }
            open = true;
            closed = false;
            session++;
            mode = Mode.QR;
            phase = Phase.WAITING_QR;
            message = TEXT_QR_WAIT;
            qrPng = null;
            qrExpired = false;
            busy = false;
            loginVia = "";
            shownQrKey = null;
            long now = now();
            lastStartQrAtMs = now;
            startQrCalled = true;
            try {
                ports.startQr(false);
            } catch (Throwable t) {
                phase = Phase.ERROR;
                message = TEXT_QR_FAIL;
            }
            publishLocked();
        }
    }

    /**
     * 关闭面板（幂等，<b>不是退出账号</b>）。
     *
     * <p>第一次：{@code open=false}、{@link Phase#CLOSED}、清 busy 与二维码、冷却与最后一句文案保留，
     * 调 {@code pauseQrWatch} 与 {@code closeVerify} 各一次（返回值忽略），{@code loggedIn} 不变。
     * 第二次起什么都不做（不再调端口、不再通知）。</p>
     */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            open = false;
            phase = Phase.CLOSED;
            busy = false;
            qrPng = null;
            session++;
            try {
                ports.pauseQrWatch();
            } catch (Throwable ignored) {
                // 关窗清理失败不该挡住关闭
            }
            try {
                ports.closeVerify();
            } catch (Throwable ignored) {
                // 同上
            }
            publishLocked();
        }
    }

    /**
     * 每秒一拍（UI 定时器驱动）。
     *
     * <p>未打开直接返回（不读 status / qrImage）。扫码档按 {@code nowMs} 节流读一次账号快照：
     * 先看风控迁移，再做状态映射；短信档只走冷却。风控档不轮询（验证结果由
     * {@link #retryAfterVerify()} 接回）。</p>
     */
    public void tick() {
        synchronized (lock) {
            if (!open) {
                return;
            }
            long now = now();
            tickCooldownLocked(now);
            if (mode != Mode.QR) {
                publishIfChangedLocked();
                return;
            }
            if (statusRead && now - lastStatusAtMs < QR_TICK_MS) {
                publishIfChangedLocked();
                return;
            }
            lastStatusAtMs = now;
            statusRead = true;

            // ① 风控迁移：必须在状态映射之前 —— 被风控拦住时伺服端不会给 LOGGED_IN。
            boolean risk = false;
            String url = "";
            try {
                risk = ports.qrRiskRequired();
                String v = ports.qrVerifyUrl();
                url = v == null ? "" : v;
            } catch (Throwable ignored) {
                // 读快照失败 ⇒ 当成没有风控
            }
            if (risk && !url.isEmpty()) {
                mode = Mode.RISK;
                riskFromQr = true;
                riskUrl = url;
                phase = Phase.RISK;
                busy = false;
                message = TEXT_RISK_NEED_VERIFY;
                if (!verifyOpened) {
                    openVerifyLocked();
                }
                publishIfChangedLocked();
                return;
            }

            // ② 扫码状态映射。
            AccountService.LoginStatus st = readStatus();
            if (st == null) {
                publishIfChangedLocked();
                return;
            }
            AccountService.State state = st.state() == null ? AccountService.State.NONE : st.state();
            switch (state) {
                case NONE:
                case WAITING_SCAN: {
                    Dto.QrSession qr = null;
                    try {
                        qr = ports.qrImage();
                    } catch (Throwable ignored) {
                        // 取不到图不影响状态轮询
                    }
                    byte[] png = qr == null ? null : qr.pngBytes();
                    String key = qr == null || qr.key() == null ? "" : qr.key();
                    if (png != null && png.length > 0) {
                        if (shownQrKey == null || !key.equals(shownQrKey)) {
                            shownQrKey = key;
                            qrKey = key;
                            qrPng = png;
                            qrExpired = false;
                        }
                        phase = Phase.QR_READY;
                        message = TEXT_QR_SCAN;
                    } else {
                        phase = Phase.WAITING_QR;
                        message = TEXT_QR_WAIT;
                    }
                    break;
                }
                case WAITING_CONFIRM:
                    phase = Phase.SCANNED;
                    message = TEXT_QR_SCANNED;
                    break;
                case EXPIRED:
                    phase = Phase.EXPIRED;
                    qrExpired = true;
                    message = TEXT_QR_EXPIRED;
                    break;
                case ERROR: {
                    String m = st.message() == null ? "" : st.message().trim();
                    phase = Phase.ERROR;
                    message = m.isEmpty() ? TEXT_QR_FAIL : m;
                    break;
                }
                case LOGGED_IN:
                    if (wasLoggedInAtOpen) {
                        // 扫码前本来就登录着：这是恢复出来的旧登录态，不是本次扫码的结果 —— 绝不判成功、绝不关窗。
                        phase = Phase.QR_READY;
                        qrExpired = false;
                        message = TEXT_ALREADY_LOGGED_IN;
                    } else {
                        String nick = st.nickname() == null ? "" : st.nickname().trim();
                        qrPng = null;
                        finishLoginLocked(TEXT_LOGIN_VIA_QR, nick);
                    }
                    break;
                default:
                    break;
            }
            publishIfChangedLocked();
        }
    }

    /**
     * 切档：扫码 / 验证码 / 风控。
     *
     * <p>扫码档顺手把码取回来（{@code startQr(false)} 复用，不强制新码）；验证码档停掉二维码显示、
     * 清空状态行，<b>不发任何网络请求</b>；重复切同一模式什么都不做（不重复取码）。冷却不因切档清零。</p>
     */
    public void switchTo(Mode want) {
        if (want == null) {
            return;
        }
        synchronized (lock) {
            if (mode == want) {
                return;
            }
            switch (want) {
                case QR: {
                    if (!open) {
                        return;
                    }
                    mode = Mode.QR;
                    phase = Phase.WAITING_QR;
                    message = TEXT_QR_WAIT;
                    qrExpired = false;
                    long now = now();
                    lastStartQrAtMs = now;
                    startQrCalled = true;
                    try {
                        ports.startQr(false);
                    } catch (Throwable t) {
                        phase = Phase.ERROR;
                        message = TEXT_QR_FAIL;
                    }
                    publishIfChangedLocked();
                    break;
                }
                case SMS:
                    mode = Mode.SMS;
                    qrPng = null;
                    qrExpired = false;
                    qrKey = "";
                    shownQrKey = null;
                    message = "";
                    phase = Phase.IDLE;
                    publishIfChangedLocked();
                    break;
                case RISK:
                    mode = Mode.RISK;
                    phase = Phase.RISK;
                    qrPng = null;
                    publishIfChangedLocked();
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 手动刷新二维码（主按钮「刷新二维码」）。
     *
     * <p>距上次 {@code startQr} 调用（含 {@link #open()} 的那次）不足 {@value #QR_MIN_GAP_MS} ms 时只提示
     * 「刚刷新过，请稍候再试」，不投递请求。真正刷新后重置轮询节流：下一次 tick 立刻可读 status。</p>
     */
    public void refreshQr() {
        synchronized (lock) {
            long now = now();
            if (startQrCalled && now - lastStartQrAtMs < QR_MIN_GAP_MS) {
                message = TEXT_REFRESH_TOO_SOON;
                publishIfChangedLocked();
                return;
            }
            lastStartQrAtMs = now;
            startQrCalled = true;
            boolean startedNew;
            try {
                startedNew = ports.startQr(true);
            } catch (Throwable t) {
                phase = Phase.ERROR;
                message = TEXT_QR_FAIL;
                publishIfChangedLocked();
                return;
            }
            if (startedNew) {
                qrPng = null;
                qrExpired = false;
                phase = Phase.WAITING_QR;
                message = TEXT_QR_WAIT;
                shownQrKey = null;
            } else {
                // 复用本地未过期二维码：不擦已显示的图，只把阶段落回等待（下一拍 tick 会同步出 QR_READY）。
                phase = Phase.WAITING_QR;
            }
            statusRead = false;
            publishIfChangedLocked();
        }
    }

    /**
     * 发送短信验证码。
     *
     * <p>三重拦截：手机号格式 → 冷却剩余 → 在途请求（busy 时静默忽略）。正常路径把
     * {@code ports.sendCode(phone, "原生登录页")} 交给异步通道；成功后冷却置
     * {@value #COOLDOWN_SEC} 秒并回显掩码号码。</p>
     */
    public void sendCode(String phone) {
        final String target = phone == null ? "" : phone.trim();
        final int requestSession;
        synchronized (lock) {
            if (!PHONE_RE.matcher(target).matches()) {
                message = TEXT_PHONE_INVALID;
                publishIfChangedLocked();
                return;
            }
            if (cooldownLeft > 0) {
                message = "请等待 " + cooldownLeft + " 秒后再获取验证码";
                publishIfChangedLocked();
                return;
            }
            if (busy) {
                return;
            }
            busy = true;
            phase = Phase.SENDING;
            message = TEXT_SENDING;
            requestSession = session;
            publishIfChangedLocked();
        }
        async.run(() -> ports.sendCode(target, SOURCE),
                result -> onCodeSent(requestSession, target, result),
                error -> onCodeFailed(requestSession));
    }

    /**
     * 提交短信验证码登录。
     *
     * <p>格式检查（手机号 → 验证码）→ busy 拦截 → 异步 {@code loginSms(phone, code, "原生登录页")}。
     * 成功即收尾（{@code open=false}，调 {@code pauseQrWatch}/{@code closeVerify} 各一次）；
     * 风控答复进风控档并自动打开一次验证页；账号/验证码错与其它错误各归各的文案。</p>
     */
    public void submitCode(String phone, String code) {
        final String target = phone == null ? "" : phone.trim();
        final String secret = code == null ? "" : code.trim();
        final int requestSession;
        synchronized (lock) {
            if (!PHONE_RE.matcher(target).matches()) {
                message = TEXT_PHONE_INVALID;
                publishIfChangedLocked();
                return;
            }
            if (!CODE_RE.matcher(secret).matches()) {
                message = TEXT_CODE_INVALID;
                publishIfChangedLocked();
                return;
            }
            if (busy) {
                return;
            }
            busy = true;
            phase = Phase.LOGGING_IN;
            message = TEXT_LOGGING_IN;
            requestSession = session;
            publishIfChangedLocked();
        }
        async.run(() -> ports.loginSms(target, secret, SOURCE),
                outcome -> onLoginResult(requestSession, outcome),
                error -> onLoginFailed(requestSession));
    }

    /**
     * 打开风控验证页（幂等只对「同一份地址」而言；重复调用允许 —— 用户可能已经把验证窗关掉了）。
     *
     * <p>地址为空只提示，不调端口。</p>
     */
    public void openVerify() {
        synchronized (lock) {
            openVerifyLocked();
            publishIfChangedLocked();
        }
    }

    /**
     * 风控后重试：解冻验证地址、立刻重探一次扫码状态，并重置轮询节流（下一次 tick 立刻读）。
     */
    public void retryAfterVerify() {
        synchronized (lock) {
            try {
                ports.retryQrAfterRisk();
            } catch (Throwable ignored) {
                // 重试失败也不挡着用户继续操作
            }
            mode = Mode.QR;
            riskFromQr = false;
            riskUrl = "";
            verifyOpened = false;
            phase = Phase.WAITING_QR;
            message = TEXT_RETRYING;
            statusRead = false;
            shownQrKey = null;
            publishIfChangedLocked();
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /** 风控验证页：地址非空才交出去；调用方负责随后 publish。 */
    private void openVerifyLocked() {
        if (riskUrl == null || riskUrl.isEmpty()) {
            message = TEXT_VERIFY_URL_EMPTY;
            return;
        }
        try {
            ports.openVerify(riskUrl, riskFromQr, this::retryAfterVerify);
        } catch (Throwable ignored) {
            // 端口失败仍标记「已交出去」：主按钮语义切到「我已完成，重试」，用户可再点开
        }
        verifyOpened = true;
        message = TEXT_RISK_VERIFY_OPENED;
    }

    /** 发码成功 / 业务失败（在异步回调线程上执行）。 */
    private void onCodeSent(int requestSession, String phone, Dto.SmsResult result) {
        synchronized (lock) {
            if (requestSession != session || closed) {
                return;
            }
            busy = false;
            if (result != null && result.sent()) {
                phase = Phase.CODE_SENT;
                cooldownLeft = COOLDOWN_SEC;
                cooldownAnchorMs = now();
                message = TEXT_SEND_SENT + maskPhone(phone);
            } else {
                phase = Phase.ERROR;
                String detail = result == null || result.message() == null ? "" : result.message().trim();
                message = TEXT_SEND_FAIL
                        + (detail.isEmpty() ? "code=" + (result == null ? -1 : result.code()) : detail);
            }
            publishIfChangedLocked();
        }
    }

    /** 发码异常（网络 / 超时）。 */
    private void onCodeFailed(int requestSession) {
        synchronized (lock) {
            if (requestSession != session || closed) {
                return;
            }
            busy = false;
            phase = Phase.ERROR;
            message = TEXT_SEND_ERROR;
            publishIfChangedLocked();
        }
    }

    /** 登录答复分流：成功 → 收尾；风控 → 风控档；账号/验证码错 → 固定文案；其它 → 伺服端原话。 */
    private void onLoginResult(int requestSession, NeteaseApi.LoginOutcome outcome) {
        synchronized (lock) {
            if (requestSession != session || closed) {
                return;
            }
            busy = false;
            if (outcome != null && outcome.ok()) {
                finishLoginLocked(TEXT_LOGIN_VIA_SMS, null);
                publishLocked();
                return;
            }
            if (outcome != null && outcome.riskBlocked()) {
                mode = Mode.RISK;
                riskFromQr = false;
                riskUrl = outcome.verifyUrl() == null ? "" : outcome.verifyUrl();
                phase = Phase.RISK;
                message = TEXT_SMS_RISK;
                if (!riskUrl.isEmpty() && !verifyOpened) {
                    openVerifyLocked();
                }
                publishIfChangedLocked();
                return;
            }
            if (outcome != null && outcome.badSecret()) {
                phase = Phase.ERROR;
                message = TEXT_BAD_SECRET;
                publishIfChangedLocked();
                return;
            }
            String detail = outcome == null || outcome.message() == null ? "" : outcome.message().trim();
            phase = Phase.ERROR;
            message = detail.isEmpty() ? TEXT_LOGIN_ERROR : detail;
            publishIfChangedLocked();
        }
    }

    /** 登录异常（网络 / 超时）。 */
    private void onLoginFailed(int requestSession) {
        synchronized (lock) {
            if (requestSession != session || closed) {
                return;
            }
            busy = false;
            phase = Phase.ERROR;
            message = TEXT_LOGIN_ERROR;
            publishIfChangedLocked();
        }
    }

    /**
     * 登录成功收尾（扫码 / 验证码共用）：置登录态、关面板、停轮询、收验证窗。
     *
     * <p>{@code closed=true} 是刻意的：真正登录成功后关闭面板<b>不应再恢复旧账号</b>（PLAN §8.2），
     * 这里替 {@code pauseQrWatch} 只调一次；之后 UI 再调 {@link #close()} 也不会重复回滚。</p>
     *
     * @param via           登录方式（"扫码" / "验证码"）
     * @param knownNickname 已知昵称；null = 现场问 {@link Ports#status()}（验证码登录路径）
     */
    private void finishLoginLocked(String via, String knownNickname) {
        loggedIn = true;
        phase = Phase.LOGGED_IN;
        loginVia = via == null ? "" : via;
        String nick = knownNickname;
        if (nick == null) {
            AccountService.LoginStatus st = readStatus();
            nick = st == null || st.nickname() == null ? "" : st.nickname().trim();
        }
        nick = nick.trim();
        message = TEXT_LOGIN_OK + (nick.isEmpty() ? TEXT_CURRENT_ACCOUNT : nick);
        open = false;
        busy = false;
        closed = true;
        session++;
        try {
            ports.pauseQrWatch();
        } catch (Throwable ignored) {
            // 同上：清理失败不改变「已登录」这个事实
        }
        try {
            ports.closeVerify();
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** 冷却递减：按 tick 的每秒 -1（一次 tick 落后几秒就补几拍），到 0 为止。 */
    private void tickCooldownLocked(long now) {
        if (cooldownLeft <= 0) {
            return;
        }
        long elapsed = now - cooldownAnchorMs;
        if (elapsed < QR_TICK_MS) {
            return;
        }
        int steps = (int) Math.min((long) cooldownLeft, elapsed / QR_TICK_MS);
        if (steps <= 0) {
            return;
        }
        cooldownLeft -= steps;
        cooldownAnchorMs += (long) steps * QR_TICK_MS;
    }

    /** 读账号快照；任何异常都当成「读不到」，绝不让异常穿出控制器。 */
    private AccountService.LoginStatus readStatus() {
        try {
            return ports.status();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 时钟（端口异常时退回系统时钟，避免节流逻辑整个失效）。 */
    private long now() {
        try {
            return ports.nowMs();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    /** 掩码号码：端口给不出就打原号码（绝不给 null）。 */
    private String maskPhone(String phone) {
        try {
            String masked = ports.maskPhone(phone);
            return masked == null ? phone : masked;
        } catch (Throwable t) {
            return phone;
        }
    }

    private State snapshotLocked() {
        return new State(mode, phase, message, qrKey, qrPng, qrExpired, busy, cooldownLeft, riskUrl,
                riskFromQr, verifyOpened, loggedIn, wasLoggedInAtOpen, loginVia);
    }

    /** 状态变了才通知（避免每秒一次的 tick 把 UI 刷成无意义重绘）。 */
    private void publishIfChangedLocked() {
        State next = snapshotLocked();
        if (sameState(next, lastPublished)) {
            return;
        }
        lastPublished = next;
        if (listener != null) {
            listener.onState(next);
        }
    }

    /** 无条件通知（{@link #open()} 幂等要求把当前快照再发一次）。 */
    private void publishLocked() {
        State next = snapshotLocked();
        lastPublished = next;
        if (listener != null) {
            listener.onState(next);
        }
    }

    /** 快照比较：数组按内容比（record 默认 == 会把同一张码的克隆当成变化）。 */
    private static boolean sameState(State a, State b) {
        if (b == null) {
            return false;
        }
        return a.mode() == b.mode()
                && a.phase() == b.phase()
                && Objects.equals(a.message(), b.message())
                && Objects.equals(a.qrKey(), b.qrKey())
                && java.util.Arrays.equals(a.qrPng(), b.qrPng())
                && a.qrExpired() == b.qrExpired()
                && a.busy() == b.busy()
                && a.cooldownLeft() == b.cooldownLeft()
                && Objects.equals(a.riskUrl(), b.riskUrl())
                && a.riskFromQr() == b.riskFromQr()
                && a.verifyOpened() == b.verifyOpened()
                && a.loggedIn() == b.loggedIn()
                && a.wasLoggedInAtOpen() == b.wasLoggedInAtOpen()
                && Objects.equals(a.loginVia(), b.loginVia());
    }
}
