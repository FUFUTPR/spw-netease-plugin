package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import com.example.netease.core.Timers;
import com.example.netease.net.Device;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 网易云登录状态机（docs/00 §6.7 冻结签名；唯一允许驱动 net 登录 API 的地方）。
 *
 * <p><b>线程模型</b>：内部持有**唯一**的守护单线程 {@code netease-account}（自建 {@link ThreadFactory}，仿
 * {@code core.HostBridgeWorker}）。所有 net 登录 API（{@code qrCreate/qrPoll/accountInfo/exportCookies/
 * importCookies/loginByCaptcha/logout}）**只在这条线程上调用**——因为 {@code net.Http} 的 cookie jar 是进程级
 * 静态单例（docs/00 §11 第 7 条）。其它线程（UI/EDT、宿主回调、HostBridgeWorker）只读 {@link #status()} 的不可变快照。</p>
 *
 * <p><b>立即返回</b>：{@link #startQr()} / {@link #pollNow()} / {@link #logout()} 从 UI 线程调用时只改内存快照 +
 * 投递任务，绝不阻塞 EDT。{@link #loginByCaptcha} 允许阻塞（有 30s 上限），UI 需要异步时自行放到后台线程。</p>
 *
 * <p><b>状态机</b>：二维码轮询码 → 801 等待扫码 / 802 待确认 / 800 过期 / 803 成功（成功立即
 * {@code CookieVault.save(exportCookies())} 并刷新账号信息）。凭据失效（{@code accountInfo()} 抛 301）→
 * {@code EXPIRED} + 清内存 cookie + **保留**本地文件，提示重新登录。</p>
 *
 * <p><b>日志红线</b>：全流程日志不出现 cookie 值；只记长度、状态码、昵称这类非敏感信息（{@code PluginLog} 另有脱敏兜底）。</p>
 */
public final class AccountService {

    /** 登录状态（冻结枚举，顺序即语义分组，不要重排）。 */
    public enum State { NONE, WAITING_SCAN, WAITING_CONFIRM, LOGGED_IN, EXPIRED, ERROR }

    /**
     * 登录状态快照（不可变，可跨线程传递）。
     *
     * @param state       当前状态
     * @param message     人类可读说明（永不为 null）
     * @param nickname    昵称（未知为空串）
     * @param uid         用户 id（未知为 0）
     * @param vip         是否有<b>任一</b>会员权益（黑胶 / 音乐包任一为真即为真；复合量）
     * @param vipName     会员<b>档位名</b>（0.11.41：{@code 黑胶SVIP} / {@code 黑胶VIP} / {@code 音乐包}；
     *                    查不到或认不出 ⇒ 空串，界面退成通用「会员」；{@code vip=false} ⇒ 空串）
     * @param expiresAtMs 扫码会话的过期时刻（毫秒时间戳；非扫码态 / 未知为 0）
     */
    public record LoginStatus(State state, String message, String nickname, long uid, boolean vip, String vipName,
                              long expiresAtMs) {
    }

    private static final String TAG = "account";
    /** 内部唯一线程名（验收 P3-A8 会检查它是否残留）。 */
    public static final String THREAD_NAME = "netease-account";
    /** 二维码有效期（本地估算，仅用于 UI 倒计时/过期提示）。 */
    private static final long QR_TTL_MS = 180_000L;
    /** 手机号登录的最大等待。 */
    private static final long CELLPHONE_TIMEOUT_SEC = 30L;
    /** 发送短信验证码的等待上限（秒）；比登录短，因为用户就等在按钮上。 */
    private static final long SMS_TIMEOUT_SEC = 20L;
    /** 扫码轮询间隔（自驱 + UI 催promote 共用；伺服端对 1.5s 量级无压力）。 */
    private static final long POLL_INTERVAL_MS = 1500L;
    /** 轮询节流下限：两个驱动源（自驱 + UI 定时器）合起来也不会比这个更密。 */
    private static final long POLL_MIN_INTERVAL_MS = 1200L;
    /**
     * 风控待验证期的轮询节流下限（0.11.32 需求①）。
     *
     * <p>风控（{@code -462} / 8821）是**账号/IP 级**的判定，与轮询快慢无关；用户在手机上做行为验证
     * 期间只要「别停下」即可，所以降到 6s 一探 —— 既不空耗流量，也让日志安静下来。</p>
     */
    private static final long RISK_POLL_INTERVAL_MS = 6000L;

    private static final Object WORKER_LOCK = new Object();
    private static final AtomicBoolean POLL_PENDING = new AtomicBoolean(false);
    /** 上一次轮询的投递时刻（节流用；只在任意线程读、账号线程写）。 */
    private static volatile long lastPollAtMs;
    /** 扫码期间的自驱定时任务（离开等待态即取消）。 */
    private static volatile ScheduledFuture<?> pollLoop;
    /** 上一次已记日志的轮询码（只在码变化时写日志，避免 1.5s 刷屏）。 */
    private static volatile int lastLoggedCode = Integer.MIN_VALUE;
    /** 本轮连续失败是否已记过日志（只记第一条，成功后复位）。 */
    private static volatile boolean pollFailureLogged;
    /** 本次会话被风控拦截（8821 / -462）的次数：只用于日志与界面文案，不再据它自动重试。 */
    private static volatile int captchaHits;
    /**
     * 本轮扫码被风控拦下后**冻住**的行为验证地址（0.11.32 需求①；空串 = 当前没有）。
     *
     * <p>为什么要冻住：伺服端每次答复都给一份带新 {@code event_id}/{@code sign} 的地址，
     * 若每 6s 换一次二维码，用户手机刚扫上就失效。首次拿到的地址保留到本轮结束
     * （{@link #retryQrAfterRisk()} 才清），界面上的码因此稳定可扫。</p>
     */
    private static volatile String qrVerifyUrl = "";
    /** 是否处于「等用户完成行为验证」态（0.11.32 需求①）。 */
    private static volatile boolean qrRisk;

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, THREAD_NAME);
        t.setDaemon(true);
        t.setContextClassLoader(AccountService.class.getClassLoader());
        accountThread = t;
        return t;
    };

    private static volatile ExecutorService worker;
    private static volatile Thread accountThread;
    private static volatile Path dataDir;
    /** 配置键 {@code auto_login}：init 时是否用保险柜里的凭据恢复登录态（默认开）。 */
    private static volatile boolean autoLogin = true;

    /**
     * 登录成功后的回调（0.11.7，W1）：登录成功即触发一轮同步（R1/R3 的接线点）。
     *
     * <p>由宿主侧（{@code NeteasePlugin}）注入，形如 {@code () -> spawnNativeSync(...)}。
     * **在 {@code netease-account} 线程上同步调用**：实现必须立即返回（投递任务即可），
     * 不得做网络 / 磁盘 / 锁等待；异常只记日志，绝不影响登录态。</p>
     */
    private static volatile Runnable afterLoginHook;

    /**
     * 「关窗回滚」回调（0.11.41，用户 m00221 ②）：{@link #pauseQrWatch()} 把登录态回滚回扫码前
     * 那一刻后触发，让调用方补一次展示行回写（{@code svc.SmsLogin.refreshAccountRow}）。
     *
     * <p><b>关窗线程上同步调用</b>（真机路径 = EDT 的 windowClosed）：实现必须立即返回
     * （投递任务即可），不得做网络 / 磁盘 / 锁等待；异常只记日志，绝不影响回滚结果。</p>
     */
    private static volatile Runnable afterQrRestore;

    // 0.11.7（W1 / 验收 A4）删除：原 `auto_client` 开关在这里。
    // 连同「接入本机网易云客户端」的全部入口（loginFromClient / takeOverClient / showClientWindow /
    // stopClient / clientStatusLine）与 svc/ClientBridge.java 整文件一起移除 —— 插件自带精简客户端，
    // 登录全过程只在宿主界面内完成，不再拉起 / 隐藏 / 读本机客户端的进程与内存（红线 1）。

    /**
     * 生命周期闸门（0.4.0）：{@link #stop()} 之后禁止再新建 {@code netease-account} 线程。
     *
     * <p>没有这道闸门时，任何「停止之后才到的请求」（宿主桥线程上排队的命令、UI 定时器）
     * 都会经 {@link #submit} → {@link #startWorker} 悄悄再造一个账号线程，而它已经
     * 没人会再 stop 了 —— 真机表现为插件停用后仍有一个 netease-account 常驻。
     * 由 {@link #init(Path, boolean)} 复位（新一轮生命周期）。</p>
     */
    private static volatile boolean stopped;
    /** {@link #stop()} 等账号线程真正退出的上限（宿主停用不该被卡在 socket 读上的线程拖住）。 */
    private static final long STOP_JOIN_MS = 1500L;

    private static volatile LoginStatus status =
            new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L);
    private static volatile Dto.QrSession qr;
    private static volatile String qrKey;
    private static volatile long qrCreatedAtMs;

    /**
     * 二维码本地保鲜期（0.11.44，用户「切一下验证码登录就把码刷新了」「点取消不要直接刷新二维码」）：
     * 这段时间内重开登录窗、来回切档一律**复用同一张码**，一个请求都不发。
     *
     * <p>为什么不照搬伺服端 TTL：真机实测网易云的码约 310s 失效（2026-10-03 21:20:42 建码、
     * 21:26:33 收到 800）。客户端提前到 180s 就不再用 —— 宁可让用户重开窗时多等一次取码，
     * 也不给一张「扫了马上就过期」的码。</p>
     */
    private static final long QR_CLIENT_TTL_MS = 180_000L;

    /** 网易云已明确判过「这张码过期了」（0.11.44，真机证据 = 轮询 800）：重开窗必须重新取码。 */
    private static volatile boolean qrServerExpired;
    /** 已投递、还没回来的取码任务（0.11.44）：同一时刻不重复投递新码（重开窗排在取码途中会走它）。 */
    private static volatile boolean qrCreating;
    /** 在途取码任务属于哪一代：关窗回滚会让上一代的取码作废，重开窗必须重新投递。 */
    private static volatile long qrCreatingGen;
    /** 落过**终局**结论（800/803）的 key（0.11.44）：同一把码的终局只允许落一次，迟到的复述丢弃。 */
    private static volatile String qrTerminalKey;

    /**
     * 本次登录窗打开那一刻的登录态 + 登录成功代数（0.11.44，用户「窗口过段时间自己关了」）。
     *
     * <p>真机 2026-10-03 21:26:33：轮询 800 把「扫码前已登录」的会话**当场**回滚成 LOGGED_IN，
     * 开着的登录窗下一拍读到 LOGGED_IN 就当成「扫码成功」自动 dispose（用户没碰任何按键）。
     * 现在 {@code ui.LoginDialog} 只在这之后**真的**登录成功过（{@link #loginSerial} 变了）时才关窗。</p>
     */
    private static volatile LoginStatus statusAtOpen;
    private static volatile long loginSerialAtOpen;
    /** 登录成功代数：803 与手机号登录各 +1（只用于上面那条「开窗后是否真的登录成功过」判定）。 */
    private static volatile long loginSerial;

    /**
     * 0.3.5：扫码会话开始前的登录态。
     *
     * <p>为「误开登录窗」兜底：本来已登录的用户打开登录窗（或点刷新二维码）后没扫，
     * 二维码过期（800）会把状态压成 EXPIRED——本地凭据明明还在。此时恢复到扫码前的
     * LOGGED_IN，而不是把有效会话降级成「未登录」（真机 0.3.4 就是这样把在线试播挡在门外的）。</p>
     */
    private static volatile LoginStatus beforeQr;

    /**
     * 扫码会话代号（0.11.41，用户 m00221 ②）：{@link #startQr()} 与「关窗回滚」
     * （{@link #pauseQrWatch()}）各自增一次。
     *
     * <p>为什么需要它：{@link #pollNow()} 把一次轮询提交到账号线程后，若用户当场关窗走了回滚，
     * 那条**已经在网线上**的轮询会带着旧会话的结论回来（801/802 会把状态重新压成等待态）——
     * 回滚就被它冲掉了。轮询任务在提交时抄下代号，落码前比一次，不等就丢弃。
     * {@link #startQr()} 的异步建码任务同理（迟到的「等待扫码」也会盖掉回滚）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong QR_GEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long qrGen;

    private AccountService() {
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 初始化（读盘**异步**）：建密钥、起 {@code netease-account} 线程，并在该线程上恢复上次登录态。
     *
     * <p>可在宿主 UI 线程 / {@code HostBridgeWorker} 上安全调用：本方法只做内存赋值 + 投递。</p>
     */
    public static void init(Path dir) {
        init(dir, autoLogin);
    }

    /**
     * 设置配置键 {@code auto_login}（调用方：{@code HostBridgeWorker} 线程，来自 {@code PluginConfig.autoLogin()}）。
     *
     * <p><b>非阻塞</b>：只改一个 volatile 标志。{@code false} = 本进程不再用凭据文件恢复登录态，
     * 但**绝不动** {@code account.json} / {@code account.key}（用户手动登录后照常保存）。</p>
     *
     * <p>可在 {@link #init(Path)} 之前或之后任意时刻调用：恢复任务在账号线程执行时会重新读这个标志，
     * 所以「先 init 后 setAutoLogin(false)」也能拦住尚未开始的恢复。</p>
     */
    public static void setAutoLogin(boolean on) {
        autoLogin = on;
        PluginLog.i(TAG, "auto_login = " + on + "（不影响本地凭据文件与已建立的登录态）");
    }

    /**
     * 注入「登录成功后」回调（0.11.7，W1；生产者：{@code NeteasePlugin.start()}）。
     *
     * <p>覆盖全部登录路径：扫码 803、手机号+短信验证码 —— 它们最终都走
     * {@link #refreshAccountInfo()}。传 {@code null} = 卸载回调（宿主停用插件时）。</p>
     *
     * <p>回调在 {@code netease-account} 线程上同步执行：实现必须立即返回（投递任务即可），
     * 不得在该线程做网络 / 磁盘 / 锁等待（docs/00 §6.10 线程纪律）。</p>
     */
    public static void setAfterLoginHook(Runnable hook) {
        afterLoginHook = hook;
        PluginLog.i(TAG, "登录后回调：" + (hook == null ? "已卸载" : "已注入（登录成功后触发一轮同步）"));
    }

    /**
     * 注入「关窗回滚」回调（0.11.41，用户 m00221 ②；生产者：{@code NeteasePlugin.start()}）。
     *
     * <p>覆盖「已登录 → 点登录 → 直接关窗」这一条真机路径：回滚把状态机复原了，但配置页
     * 「当前账号」行还是扫码时的空文案 —— 这里补一次回写。传 {@code null} = 卸载回调。</p>
     */
    public static void setAfterQrRestoreHook(Runnable hook) {
        afterQrRestore = hook;
        PluginLog.i(TAG, "关窗回滚回调：" + (hook == null ? "已卸载" : "已注入（回滚成功后回写展示行）"));
    }

    // 0.11.7（W1 / 验收 A4）删除：setAutoClient / loginFromClient / takeOverClient / showClientWindow /
    // stopClient / clientStatusLine。手机号登录入口见下方 loginByCaptcha（阻塞、有上限）。

    /**
     * 初始化重载（冻结签名之外的补充）：{@code autoLogin=false} 时只初始化保险柜与线程，
     * **不**读取/注入本地凭据（对应配置键 {@code auto_login}，由调用方决定是否恢复）。
     */
    public static void init(Path dir, boolean autoLoginOn) {
        dataDir = dir;
        autoLogin = autoLoginOn;
        stopped = false;               // 新一轮生命周期：允许重新建账号线程
        try {
            Device.init(dir);          // 设备身份（deviceId/NMTID）：只写内存；读盘推迟到账号线程
        } catch (Throwable t) {
            PluginLog.w(TAG, "设备身份初始化失败（登录仍可进行，设备维度可能被服务端重新分配）", t);
        }
        qr = null;
        qrKey = null;
        POLL_PENDING.set(false);
        qrRisk = false;
        qrVerifyUrl = "";
        try {
            CookieVault.init(dir);
        } catch (Throwable t) {
            PluginLog.e(TAG, "CookieVault 初始化失败（凭据功能降级）", t);
        }
        setStatus(new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L));
        if (!startWorker()) {
            PluginLog.w(TAG, "账号线程未能启动，登录功能不可用");
            return;
        }
        if (!autoLogin) {
            PluginLog.i(TAG, "init：auto_login 关闭，跳过凭据恢复");
            return;
        }
        submit(AccountService::restoreFromVault);
    }

    /** 停内部线程（幂等；{@code NeteasePlugin.stop()} 生命周期调用）。 */
    public static void stop() {
        stopped = true;                // 停止之后绝不再新建账号线程（防「停了又冒一个」）
        qr = null;
        qrKey = null;
        POLL_PENDING.set(false);
        qrRisk = false;
        qrVerifyUrl = "";
        cancelPollLoop();
        ExecutorService w;
        synchronized (WORKER_LOCK) {
            w = worker;
            worker = null;
        }
        Thread t = accountThread;
        accountThread = null;
        if (w != null) {
            w.shutdownNow();
        }
        if (t != null) {
            t.interrupt();
        }
        // 0.11.7（W1）：原来这里调 ClientBridge.abort() 中止「在飞的客户端连接」；去客户端化后
        // 不再需要——net/Http 每次请求自建 HttpURLConnection 并在 finally 关闭，单次调用有
        // CONNECT 5s / READ 8s 上限（net/Http.java），加上下面这步有界 join（STOP_JOIN_MS）。
        joinAccountThread(t);
    }

    /**
     * 等账号线程真正退出（有界，{@link #STOP_JOIN_MS}）。
     *
     * <p>线程是 daemon，但「{@code stop()} 返回即插件自有线程已死」是生命周期承诺：
     * 宿主停用插件后不该再有任何 netease-* 线程（验收 P3-A8 / harness H7.2 / H10.1 都按这个口径查）。
     * 超过上限仍活着就记 WARN + 栈顶若干帧，把「谁把它钉住了」留在日志里（照旧失败，绝不静默）。</p>
     */
    private static void joinAccountThread(Thread t) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        long deadline = System.currentTimeMillis() + STOP_JOIN_MS;
        while (t.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                t.join(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!t.isAlive()) {
            PluginLog.d(TAG, "账号线程已退出（stop() 返回即无残留）");
            return;
        }
        StringBuilder sb = new StringBuilder("账号线程未在 " + STOP_JOIN_MS + "ms 内退出（残留风险，栈顶如下）");
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 8; i++) {
            sb.append("\n    at ").append(st[i]);
        }
        PluginLog.w(TAG, sb.toString());
    }

    // ---------------------------------------------------------------- 冻结 API

    /** 内存快照：任意线程可调，绝不抛。 */
    public static LoginStatus status() {
        LoginStatus s = status;
        return (s == null) ? new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L) : s;
    }

    /** 起二维码登录（能复用就复用，过期 / 强制才重新生成），立即返回。 */
    public static void startQr() {
        startQr(false);
    }

    /**
     * 起二维码登录（0.11.44 起带复用），立即返回；真正的取码在 {@code netease-account} 线程上做。
     *
     * <p>0.11.44（用户）：「切一下验证码登录就把码刷新了」「点取消也不要直接刷新二维码」
     * 「取消退出后只要码没过期，进来还是那张码」—— 这里把「开窗」与「重新取码」拆开：
     * 本地还留着、且未过 {@link #QR_CLIENT_TTL_MS} 的码一律复用（零请求、零状态重置），
     * 只有 ①用户点「刷新二维码」（{@code force=true}）②码超过保鲜期 ③网易云已回 800
     * 才重新向网易云取码。</p>
     *
     * @param force true = 用户手动刷新：无视保鲜期，重新要一张新码（旧码就此作废）
     * @return true = 已投递**新的**取码任务（调用方应把展示复位成「正在获取二维码…」）；
     *         false = 复用了现有码 / 已有一张码正在生成 —— 调用方不要动已有展示，只按快照同步
     */
    public static boolean startQr(boolean force) {
        long now = System.currentTimeMillis();
        LoginStatus before = status();
        // 开窗快照（0.11.44）：状态 + 登录成功代数 —— 界面只在这之后**真的**登录成功过时才自动关窗。
        statusAtOpen = before;
        loginSerialAtOpen = loginSerial;

        String key = qrKey;
        Dto.QrSession have = qr;
        boolean fresh = have != null && key != null && !key.isBlank() && !qrServerExpired
                && now - qrCreatedAtMs < QR_CLIENT_TTL_MS;
        boolean creating = qrCreating && qrCreatingGen == qrGen;
        if (!force && (fresh || creating)) {
            if (fresh) {
                // 0.11.42 规则保留：只有当前确实是 LOGGED_IN 才刷新关窗快照（会话进行中的复用不许冲掉）。
                if (before.state() == State.LOGGED_IN) {
                    beforeQr = before;
                }
                State cur = status().state();
                if (cur != State.WAITING_SCAN && cur != State.WAITING_CONFIRM) {
                    // 上一轮关窗把状态回滚成了 LOGGED_IN（或凭据过期态）⇒ 把扫码乐观态放回来；
                    // 下一次轮询（<=1.5s）会用伺服端真实进度覆盖它。
                    setStatus(new LoginStatus(State.WAITING_SCAN, "等待扫码…", "", 0L, false, "",
                            qrCreatedAtMs + QR_TTL_MS));
                }
                if (pollLoop == null) {
                    startPollLoop();
                }
                PluginLog.i(TAG, "二维码复用（0.11.44）：key 未过期（已存活 "
                        + Math.max(0L, now - qrCreatedAtMs) / 1000 + "s），不向网易云重新取码");
            } else {
                PluginLog.i(TAG, "二维码还在生成中（0.11.44）：不重复投递取码任务");
            }
            return false;
        }

        // 0.11.42（真机 2026-10-03 21:07 复现「点登录→切来切去→取消⇒当前未登录」）：登录窗里
        // 「扫码 → 验证码登录 → 扫码」切回时会**第二次**调 startQr，而那一刻状态已是 WAITING_SCAN ——
        // 旧写法把 beforeQr 冲成 null ⇒ 关窗回滚没有快照可用、登录态被留在等待态。只有当前确实是
        // LOGGED_IN 才刷新快照；会话进行中的重新生成（刷新二维码 / 来回切档）必须**保留第一次**的快照。
        if (before.state() == State.LOGGED_IN) {
            beforeQr = before;
        }
        qrGen = QR_GEN.incrementAndGet();       // 新会话：上一代在途的轮询结果就此作废
        final long gen = qrGen;                 // 本会话代号：关窗回滚后，迟到的生成/轮询结果一律丢弃
        qr = null;
        qrKey = null;
        qrCreatedAtMs = now;
        qrServerExpired = false;
        qrCreating = true;
        qrCreatingGen = gen;
        POLL_PENDING.set(false);
        lastLoggedCode = Integer.MIN_VALUE;   // 新二维码：第一轮轮询必定记一行
        pollFailureLogged = false;
        captchaHits = 0;                      // 新会话重新计数（风控拦截的自动重试预算）
        qrVerifyUrl = "";                     // 新会话解冻行为验证地址（0.11.32 需求①）
        qrRisk = false;
        setStatus(new LoginStatus(State.WAITING_SCAN, "正在生成二维码…", "", 0L, false, "", now + QR_TTL_MS));
        if (!startWorker()) {
            qrCreating = false;
            setStatus(new LoginStatus(State.ERROR, "账号线程未启动，无法登录", "", 0L, false, "", 0L));
            return true;
        }
        submit(() -> {
            long beganAt = System.currentTimeMillis();
            try {
                if (gen != qrGen) {
                    return;                         // 会话已作废（用户没等二维码出来就关窗走了回滚）⇒ 丢弃
                }
                Dto.QrSession s = NeteaseApi.qrCreate();
                if (gen != qrGen) {
                    // 0.11.44：生成期间用户关窗回了滚（或又点了一次刷新）—— 结论**不落状态**
                    //（否则会把回滚压回等待态，0.11.41 的老毛病），但把码悄悄留着：重开窗时只要
                    // 它没过保鲜期就直接复用（用户「取消后进来还是那张码」）。只在「没有更新一代
                    // 的取码在途」时才留，免得盖掉新码。
                    if (s != null && s.key() != null && !s.key().isBlank()
                            && !(qrCreating && qrCreatingGen != gen)) {
                        qr = s;
                        qrKey = s.key();
                        qrCreatedAtMs = System.currentTimeMillis();
                        qrServerExpired = false;
                        qrTerminalKey = null;
                        PluginLog.i(TAG, "二维码已生成但会话已作废（用时 " + (qrCreatedAtMs - beganAt)
                                + "ms）：留作下次开窗复用，不落状态");
                    }
                    return;
                }
                if (s == null) {
                    setStatus(new LoginStatus(State.ERROR, "二维码生成失败，请重试", "", 0L, false, "", 0L));
                    return;
                }
                qr = s;
                qrKey = s.key();
                qrCreatedAtMs = System.currentTimeMillis();
                qrTerminalKey = null;           // 新码：终局结论重新计数（0.11.44）
                PluginLog.i(TAG, "二维码已生成：key 长度 " + len(s.key()) + "，PNG "
                        + (s.pngBytes() == null ? 0 : s.pngBytes().length) + "B，用时 "
                        + (qrCreatedAtMs - beganAt) + "ms");
                setStatus(new LoginStatus(State.WAITING_SCAN, "等待扫码…", "", 0L, false, "", qrCreatedAtMs + QR_TTL_MS));
            } catch (Throwable t) {
                if (gen != qrGen) {
                    return;
                }
                PluginLog.w(TAG, "二维码生成失败：" + describe(t));
                setStatus(new LoginStatus(State.ERROR, "二维码生成失败：" + describe(t), "", 0L, false, "", 0L));
            } finally {
                if (qrCreatingGen == gen) {
                    qrCreating = false;         // 本代取码已回来（成功/失败/作废都算「不在途」）
                }
            }
        });
        startPollLoop();
        return true;
    }

    /**
     * 启动扫码自驱轮询（意在：**登录不依赖登录窗是否开着**）。
     *
     * <p>0.2.1 及以前只有 {@link #pollNow()} 这个入口，而 UI 定时器只读快照、从没调用过它，
     * 于是真机上「扫码确认后一直停在等待扫码」——状态机根本没被喂过（详见 docs/14 §9.6）。
     * 这里用插件自己的调度线程（{@link Timers}，非新增线程）每 {@value #POLL_INTERVAL_MS}ms 催一次。</p>
     */
    private static void startPollLoop() {
        cancelPollLoop();
        try {
            pollLoop = Timers.every(POLL_INTERVAL_MS, POLL_INTERVAL_MS, AccountService::pollNow);
            if (pollLoop == null) {
                PluginLog.w(TAG, "自驱轮询未启动（Timers 未运行？）——将由登录窗定时器驱动");
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "自驱轮询启动失败（UI 定时器仍可驱动）：" + describe(t));
        }
    }

    /** 取消自驱轮询（幂等）。 */
    private static void cancelPollLoop() {
        ScheduledFuture<?> f = pollLoop;
        pollLoop = null;
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
                // 取消失败不影响正确性：任务内部还有状态判断
            }
        }
    }

    /**
     * 暂停扫码自驱轮询（0.11.33）：登录窗一关就调它 —— 真机取证（2026-10-02 16:55:54）显示关窗后
     * 自驱轮询仍在每 6 秒打一行「扫码被风控拦截」，日志刷屏且空耗请求。
     *
     * <p>与 {@link #stop()} 的区别：**只停轮询、不动登录态、不清二维码**（二维码还会过期自行失效）。
     * 再次 {@link #startQr()} 或用户重开登录窗时会重新 {@link #startPollLoop()}。</p>
     */
    public static boolean pauseQrWatch() {
        boolean restored = restoreAbandonedQr();
        if (pollLoop == null) {
            return restored;              // 本来就没在跑：不打日志，避免关窗噪声
        }
        cancelPollLoop();
        PluginLog.i(TAG, "自驱轮询已暂停（登录窗关闭）：状态机 " + status().state());
        return restored;
    }

    /**
     * 关窗 = 放弃本次扫码（0.11.41，用户 m00221 ②「早已登录好，再点登录会把登录清掉」）：
     * 扫码前是已登录、且凭据仍在 jar 里 ⇒ 回滚到那一刻。
     *
     * <p>真机缺陷（2026-10-03 20:38）：已登录的用户点「登录」→ 开窗即 {@link #startQr()} 把状态压成
     * WAITING_SCAN → 关窗后状态**停在那里**（恢复分支只挂在二维码过期码 800 上）⇒「当前账号」显示
     * 「当前未登录」、展示行被清空、在线播放降级 —— 用户以为登录被清了（凭据其实还在 account.json 里，
     * 重启宿主即可恢复）。</p>
     *
     * <p>只在「本次扫码还没成功」时回滚：{@code beforeQr} 由 {@link #startQr()} 记下、803 成功时清空，
     * 这里看到非 null 就等于「用户没登进去」。已经走完 803（状态已 LOGGED_IN）或 jar 里凭据没了
     * （宁可让用户重新登录，也不能假装还登录着）⇒ 都不回滚。</p>
     *
     * @return 是否发生了回滚（调用方据此补一次展示行回写 —— 写配置必须回宿主交互线程）
     */
    private static boolean restoreAbandonedQr() {
        LoginStatus back = beforeQr;
        if (back == null) {
            return false;
        }
        State cur = status().state();
        if (cur != State.WAITING_SCAN && cur != State.WAITING_CONFIRM
                && cur != State.EXPIRED && cur != State.ERROR) {
            return false;                 // 已走到别的状态（例如 803 已登录）⇒ 不回滚
        }
        if (!NeteaseApi.isLoggedIn()) {
            return false;                 // 凭据已不在 jar 里 ⇒ 无可回滚，老实让用户重新登录
        }
        beforeQr = null;
        qrGen = QR_GEN.incrementAndGet();  // 让在途的旧轮询结果作废，别把回滚冲掉
        setStatus(back);
        // 0.11.44：二维码本身**不销毁** —— 用户「取消退出后只要码没过期，进来还是那张码」。
        // 没超保鲜期且网易云没说过期 ⇒ 重开窗直接复用（零请求）；否则 startQr 自己会重新取码。
        PluginLog.i(TAG, "登录窗关闭 = 放弃本次扫码：本地凭据仍在 ⇒ 恢复扫码前登录态（uid=" + back.uid()
                + "，昵称=" + back.nickname() + "，VIP=" + back.vip() + "）"
                + (qrServerExpired ? "；二维码已被网易云判过期，重开窗时重新取码"
                        : "；本地二维码保留，未过期时重开窗直接复用"));
        fireAfterQrRestore();
        return true;
    }

    /** 触发「关窗回滚」回调（关窗线程；实现必须立即返回，异常只记日志）。 */
    private static void fireAfterQrRestore() {
        Runnable hook = afterQrRestore;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Throwable t) {
            PluginLog.w(TAG, "关窗回滚回调失败（登录态已恢复，展示行可能滞后）：" + describe(t));
        }
    }

    /** 当前二维码（未开始 / 已消费时为 null）。 */
    public static Dto.QrSession qrImage() {
        return qr;
    }

    /**
     * 本次登录窗打开时账号是否**已经登录**，且此后没有发生新的登录成功（0.11.44）。
     *
     * <p>界面（{@code ui.LoginDialog.tickQr}）靠它决定「读到 LOGGED_IN 要不要自动关窗」：
     * 真机 2026-10-03 21:26:33 的缺陷是轮询 800 把扫码前的 LOGGED_IN 当场放回来，界面误当成
     * 「本次扫码成功」把窗自己关了（用户没碰任何按键）。只有本方法为 false（开窗时未登录，或
     * 开窗后真的登录成功过）才允许自动关窗。</p>
     */
    public static boolean wasLoggedInAtOpen() {
        LoginStatus s = statusAtOpen;
        return s != null && s.state() == State.LOGGED_IN && loginSerialAtOpen == loginSerial;
    }

    /**
     * 风控待验证期要交给**电脑浏览器**打开的行为验证地址（0.11.32 需求①；0.11.33 改为电脑上完成；
     * 空串 = 没有）。
     *
     * <p>界面（{@code ui.LoginDialog}）只读快照、不问来源 —— 与二维码本身同一套「冻结 API」口径。</p>
     */
    public static String qrVerifyUrl() {
        String v = qrVerifyUrl;
        return v == null ? "" : v;
    }

    /** 是否处于「等用户完成行为验证」态（0.11.32 需求①）。 */
    public static boolean qrRiskRequired() {
        return qrRisk;
    }

    /**
     * 用户在电脑上做完行为验证后点「重试」走这里：解冻地址、清掉节流窗口、立刻再探一次。
     *
     * <p>为什么不重新生成二维码：扫码 key 本身是好的，被打回的是**这台机器这次登录**的风控判定，
     * 换 key 等于让用户白扫一次（0.2.2 那次自动重生就是这么白费的）。</p>
     */
    public static void retryQrAfterRisk() {
        qrVerifyUrl = "";
        qrRisk = false;
        lastPollAtMs = 0L;
        lastLoggedCode = Integer.MIN_VALUE;   // 让下一轮无论什么码都记一行，便于真机取证
        PluginLog.i(TAG, "风控验证重试：清掉冻结地址，立刻重探一次扫码状态");
        setStatus(new LoginStatus(State.WAITING_SCAN, "正在确认验证结果…", "", 0L, false, "",
                qrCreatedAtMs + QR_TTL_MS));
        pollNow();
    }

    /**
     * 把当前登录 cookie 串交给回调（0.11.34：内嵌验证宿主需要与插件同源的设备身份，否则验证页
     * 在网易云眼里是「另一台机器的浏览器」）。
     *
     * <p>cookie jar 是进程级静态单例，**只允许在 {@code netease-account} 线程上读写**（docs/00 §11 第 7 条），
     * 所以这里投递回账号线程取，再回调；回调**可能在账号线程上执行**（拿不到线程池时退化为同步回调），
     * 因此回调里只许做轻活、绝不回调 net 登录 API。取不到 cookie ⇒ 回调空串（调用方按「没有」处理）。</p>
     *
     * <p>日志红线：只记长度，cookie 值不落盘。</p>
     */
    public static void withCookies(Consumer<String> cb) {
        if (cb == null) {
            return;
        }
        boolean posted = submit(() -> {
            String ck = null;
            try {
                ck = NeteaseApi.exportCookies();
            } catch (Throwable t) {
                PluginLog.w(TAG, "取 cookie 失败（照常继续）：" + describe(t));
            }
            String text = ck == null ? "" : ck.trim();
            PluginLog.d(TAG, "交出 cookie 给内嵌验证：" + (text.isEmpty() ? "无" : text.length() + " 字符"));
            cb.accept(text);
        });
        if (!posted) {
            PluginLog.w(TAG, "账号线程不可用 ⇒ 交不出 cookie（内嵌验证将不带登录态）");
            cb.accept("");
        }
    }

    /**
     * 内嵌验证页写下的 cookie 回灌插件 jar（0.11.34）：**按名合并**，插件既有项一律优先，绝不整串覆盖。
     *
     * <p>为什么要回灌：验证过程本身也会写 cookie（网易云用它标记「这台设备已过验证」）；丢掉这几项，
     * 用户刚做完的验证对插件无意义（下一次请求还是从零判定）。合并只取验证页独有的名字，避免把插件
     * 会话里更新的值退回旧值。</p>
     */
    public static void mergeCookies(String fromWebView) {
        if (fromWebView == null || fromWebView.isBlank()) {
            return;
        }
        String theirs = fromWebView.trim();
        boolean posted = submit(() -> {
            try {
                String mine = "";
                try {
                    String ck = NeteaseApi.exportCookies();
                    mine = ck == null ? "" : ck;
                } catch (Throwable t) {
                    PluginLog.w(TAG, "读插件 cookie 失败（按空处理）：" + describe(t));
                }
                String merged = mergeCookieHeader(mine, theirs);
                int before = cookieCount(mine);
                int after = cookieCount(merged);
                if (merged.equals(mine)) {
                    PluginLog.d(TAG, "验证页 cookie 没有新名字 ⇒ 无需回灌（插件 " + before + " 项）");
                    return;
                }
                NeteaseApi.importCookies(merged);
                PluginLog.i(TAG, "验证页 cookie 已按名合并（插件 " + before + " 项 + 验证页独有 "
                        + (after - before) + " 项 ⇒ " + after + " 项；值不进日志）");
            } catch (Throwable t) {
                PluginLog.w(TAG, "回灌验证页 cookie 失败：" + describe(t));
            }
        });
        if (!posted) {
            PluginLog.w(TAG, "账号线程不可用 ⇒ 验证页 cookie 丢弃（只记长度 " + theirs.length() + "）");
        }
    }

    /** 按名合并两个 {@code Cookie} 头（前者的名与值一律优先，后者只补前者没有的名字）。 */
    private static String mergeCookieHeader(String mine, String theirs) {
        String left = mine == null ? "" : mine.trim();
        String right = theirs == null ? "" : theirs.trim();
        if (right.isEmpty()) {
            return left;
        }
        Set<String> have = new HashSet<>();
        for (String part : left.split(";")) {
            String name = cookieName(part.trim());
            if (!name.isEmpty()) {
                have.add(name);
            }
        }
        StringBuilder sb = new StringBuilder(left);
        for (String part : right.split(";")) {
            String item = part.trim();
            String name = cookieName(item);
            if (name.isEmpty() || have.contains(name)) {
                continue;
            }
            have.add(name);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') {
                sb.append("; ");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    private static String cookieName(String item) {
        int eq = item.indexOf('=');
        return eq <= 0 ? "" : item.substring(0, eq).trim();
    }

    private static int cookieCount(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String part : header.split(";")) {
            if (!cookieName(part.trim()).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 催一次轮询（自驱定时器 + 登录窗定时器共用），立即返回；上一次没回来 / 节流窗口内直接丢弃。 */
    public static void pollNow() {
        State st = status().state();
        if (st != State.WAITING_SCAN && st != State.WAITING_CONFIRM) {
            cancelPollLoop();     // 已离开等待态：自驱任务自我了断
            return;
        }
        long now = System.currentTimeMillis();
        // 0.11.32（需求①）：风控待验证期放慢到 6s —— 判定是账号/IP 级的，快轮询没意义
        long window = qrRisk ? RISK_POLL_INTERVAL_MS : POLL_MIN_INTERVAL_MS;
        if (now - lastPollAtMs < window) {
            return;               // 两个驱动源重叠时只发一次
        }
        if (!POLL_PENDING.compareAndSet(false, true)) {
            return;
        }
        lastPollAtMs = now;
        if (!startWorker()) {
            POLL_PENDING.set(false);
            return;
        }
        final long gen = qrGen;               // 这次轮询属于哪一代会话（落码前还要再比一次，见下）
        if (!submit(() -> {
            try {
                String key = qrKey;
                if (key == null || key.isBlank()) {
                    return;   // 二维码还没生成出来，等下一次
                }
                if (gen != qrGen) {
                    return;   // 会话已换代（关窗回滚 / 重新取码）⇒ 这次结论一律丢弃（0.11.41）
                }
                NeteaseApi.QrPoll poll = NeteaseApi.qrPollFull(key);
                if (gen != qrGen) {
                    // 0.11.43（真机 2026-10-03 21:13:04 实证）：网络调用期间用户关窗回了滚 ——
                    // 结论必须**再过一次**代号闸门，否则迟到的 801 会把回滚压回等待态，
                    // 下一次开窗连带失去登录态快照（再关窗就没法回滚）。
                    return;
                }
                if (!feedTerminalOnce(poll)) {
                    return;   // 这把码的终局已经落过一次（迟到的复述）⇒ 丢弃（0.11.44）
                }
                onPollCode(poll);
            } catch (Throwable t) {
                if (gen != qrGen) {
                    return;   // 同上：迟到的失败也不许把回滚好的状态压回等待态
                }
                // 单次网络抖动不该毁掉整次登录：保持等待态，下一次继续。
                // 日志只记一条（连续失败会 1.5s 一条刷屏，够看出问题即可）。
                if (!pollFailureLogged) {
                    pollFailureLogged = true;
                    PluginLog.w(TAG, "二维码轮询异常（保持等待态，下次继续）：" + describe(t));
                }
                State cur = status().state();
                if (cur == State.WAITING_SCAN || cur == State.WAITING_CONFIRM) {
                    setStatus(new LoginStatus(cur, cur == State.WAITING_SCAN
                            ? "等待扫码…（网络重试中）" : "已扫码，请在手机上确认…（网络重试中）",
                            "", 0L, false, "", qrCreatedAtMs + QR_TTL_MS));
                }
            } finally {
                POLL_PENDING.set(false);
            }
        })) {
            POLL_PENDING.set(false);
        }
    }

    /**
     * 发送短信验证码（0.2.4，docs/00 §6.3 修正第 11 条）。**会阻塞**（最长 {@value #SMS_TIMEOUT_SEC} 秒）。
     *
     * <p>真实网络调用在 {@code netease-account} 线程上做（{@code Http} 的 cookie jar 是进程级静态单例，
     * 只有该线程能碰）；调用方（UI）只需保证自己没在 EDT 上干等 —— 见 {@code ui.LoginDialog}
     * 的 offThread（0.11.30 起扫码窗 ui.LoginWindow 已退役，对话框是唯一登录窗）。</p>
     *
     * @param phone 11 位手机号；格式非法时直接返回 {@code code=-2}，不发请求
     * @return 业务码 + 伺服端原话（如 200 已发送 / 503 太频繁 / 8821 风控）；永不返回 null
     */
    public static Dto.SmsResult sendSmsCode(String phone) {
        if (phone == null || !phone.matches("1\\d{10}")) {
            return new Dto.SmsResult(-2, "手机号格式不正确（需 11 位）");
        }
        if (!startWorker()) {
            return new Dto.SmsResult(-1, "账号线程不可用");
        }
        if (isOnAccountThread()) {
            return doSmsSend(phone);
        }
        FutureTask<Dto.SmsResult> task = new FutureTask<>(() -> doSmsSend(phone));
        if (!submit(task)) {
            return new Dto.SmsResult(-1, "账号线程不可用");
        }
        try {
            Dto.SmsResult r = task.get(SMS_TIMEOUT_SEC, TimeUnit.SECONDS);
            return r == null ? new Dto.SmsResult(-1, "发送失败（无响应）") : r;
        } catch (Throwable t) {
            task.cancel(false);
            PluginLog.w(TAG, "短信验证码发送等待超时/中断：" + describe(t));
            return new Dto.SmsResult(-1, "请求超时，请检查网络后重试");
        }
    }

    /**
     * 手机号 + 短信验证码登录（0.11.7：返回业务码）。**会阻塞**（最长 {@value #CELLPHONE_TIMEOUT_SEC} 秒）。
     *
     * <p>验证码只作为参数传给 net 层，绝不写日志、绝不出现在状态文案里，也不落盘。</p>
     *
     * @return 登录结果；参数非法 / 线程不可用 / 超时也返回结果对象（{@code ok()==false}）
     */
    public static NeteaseApi.LoginOutcome loginByCaptcha(String phone, String captcha) {
        if (phone == null || phone.isBlank() || captcha == null || captcha.isBlank()) {
            return new NeteaseApi.LoginOutcome(false, -2, "手机号或验证码为空");
        }
        if (!startWorker()) {
            return new NeteaseApi.LoginOutcome(false, -1, "账号线程不可用（插件可能已停用）");
        }
        if (isOnAccountThread()) {
            return doCaptchaLogin(phone, captcha);
        }
        FutureTask<NeteaseApi.LoginOutcome> task = new FutureTask<>(() -> doCaptchaLogin(phone, captcha));
        if (!submit(task)) {
            return new NeteaseApi.LoginOutcome(false, -1, "账号线程不可用（插件可能已停用）");
        }
        try {
            NeteaseApi.LoginOutcome out = task.get(CELLPHONE_TIMEOUT_SEC, TimeUnit.SECONDS);
            return out == null ? new NeteaseApi.LoginOutcome(false, -1, "登录失败（无响应）") : out;
        } catch (Throwable t) {
            task.cancel(false);
            PluginLog.w(TAG, "验证码登录等待超时/中断：" + describe(t));
            return new NeteaseApi.LoginOutcome(false, -1, "登录等待超时，请检查网络后重试");
        }
    }

    /**
     * 手机号 + 短信验证码登录（**兼容入口**，0.2.4 的布尔语义）。
     *
     * <p>0.11.7 起新代码请用 {@link #loginByCaptcha(String, String)}。本方法保留给既有调用点
     * （{@code ui/LoginDialog}；0.11.30 起扫码窗 ui/LoginWindow 已退役）。</p>
     *
     * @return 是否登录成功
     */
    public static boolean loginCellphoneCaptcha(String phone, String captcha) {
        return loginByCaptcha(phone, captcha).ok();
    }

    /** 退出登录：**立即**清内存快照（UI 立刻变「未登录」），落盘删除在账号线程上做。 */
    public static void logout() {
        qr = null;
        qrKey = null;
        POLL_PENDING.set(false);
        setStatus(new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L));
        Runnable job = () -> {
            try {
                NeteaseApi.logout();      // 清 cookie jar（只在账号线程）
                NativeStreamServer.credentialsChanged();
            } catch (Throwable t) {
                PluginLog.w(TAG, "清空内存 cookie 失败：" + describe(t));
            }
            CookieVault.clear();          // 删 account.json，保留 account.key
            PluginLog.i(TAG, "退出登录完成：内存 cookie 已清空，凭据文件已删除（密钥保留）");
        };
        if (!submit(job)) {
            job.run();                    // 线程不可用时直接做（单个小文件删除，<1ms）
        }
    }

    /** 是否已登录（纯内存判断，绝不抛）。 */
    public static boolean loggedIn() {
        return status().state() == State.LOGGED_IN;
    }

    /** 一行状态文案：给 UI/日志用。 */
    public static String statusLine() {
        LoginStatus s = status();
        String msg = (s.message() == null || s.message().isBlank()) ? "" : s.message();
        switch (s.state()) {
            case WAITING_SCAN:
                return msg.isEmpty() ? "等待扫码…" : msg;
            case WAITING_CONFIRM:
                return msg.isEmpty() ? "已扫码，请在手机上确认…" : msg;
            case LOGGED_IN: {
                String nick = (s.nickname() == null || s.nickname().isBlank()) ? "" : s.nickname();
                if (nick.isEmpty()) {
                    return "已登录：<昵称未知>";
                }
                return "已登录：" + nick + (s.vip() ? "（VIP）" : "");
            }
            case EXPIRED:
                return msg.isEmpty() ? "登录已过期，请重新扫码" : msg;
            case ERROR:
                return msg.isEmpty() ? "登录异常" : "登录异常：" + msg;
            case NONE:
            default:
                return msg.isEmpty() ? "未登录" : msg;
        }
    }

    /** 凭据文件路径 {@code data\account.json}。 */
    public static Path file() {
        return CookieVault.file();
    }

    // ---------------------------------------------------------------- 烟测钩子（仅供 tools/smoke/SmokeAccount.java）

    /**
     * ⚠️ **仅供烟测**（{@code tools/smoke/SmokeAccount.java}）：把一次「二维码轮询结果」直接喂给状态机，
     * 不联网、不碰 net，用来在离线环境驱动 NONE→WAITING_SCAN→WAITING_CONFIRM→LOGGED_IN/EXPIRED 全流程。
     *
     * <p>调用的是生产路径同一个 {@link #feedQrCode(int, String, String, String, long, boolean)}，不是平行实现。
     * 业务代码不得调用本方法。</p>
     *
     * @param code     801/802/800/803
     * @param cookies  803 时写入保险柜的 cookie 串（烟测用假串），其它码传 null
     * @param nickname 803 时预置昵称（null = 走真实 {@code accountInfo()} 路径）
     * @param uid      803 时预置 uid
     * @param vip      803 时预置 VIP 标记
     */
    public static void __testFeedQrCode(int code, String cookies, String nickname, long uid, boolean vip) {
        feedQrCode(code, "", cookies, nickname, uid, vip);
    }

    /** ⚠️ **仅供烟测**：模拟 {@code accountInfo()} 抛 301 的凭据失效路径（只动状态机，不碰 cookie jar）。 */
    public static void __testFeedSessionExpired() {
        markExpired("__test 模拟 301", false);
    }

    /**
     * ⚠️ **仅供烟测**：复位成「全新生命周期」的干净状态（不动磁盘、不碰 net）。
     *
     * <p>0.4.0 起 {@link #stop()} 是终态：{@code stopped} 闸门挡住「停用之后才到的请求」，
     * 否则宿主停用插件后仍会悄悄冒出新的 {@code netease-account} 线程（真机残留的根因）。
     * 生产环境要复活只能走 {@link #init(Path, boolean)}（宿主重新启用插件，见
     * {@code NeteasePlugin.start()}）；烟测没有宿主，所以由这里显式复位闸门，
     * 等价于「重新 init 一次」。</p>
     */
    public static void __testReset() {
        stopped = false;               // 等价于新生命周期（生产路径由 init 复位）
        try {
            Device.reset();            // 清设备身份的内存态（不删 device.json）
        } catch (Throwable ignored) {
            // 复位失败不影响状态机复位
        }
        qr = null;
        qrKey = null;
        qrServerExpired = false;
        qrCreating = false;
        qrTerminalKey = null;
        statusAtOpen = null;
        loginSerialAtOpen = 0L;
        POLL_PENDING.set(false);
        setStatus(new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L));
    }

    /**
     * ⚠️ **仅供烟测**：凭空放一张「本地还留着」的二维码（不联网），用来驱动 0.11.44 的
     * 复用 / 保鲜期 / 重取判定（生产路径的码一律由 {@code startQr} 经网易云生成）。
     *
     * @param key         二维码 key（照真实长度 36 传即可）
     * @param createdAtMs 建码时刻（毫秒；传「现在 - 200000」即可造一张超保鲜期的码）
     */
    public static void __testSeedQr(String key, long createdAtMs) {
        qr = new Dto.QrSession(key, null);
        qrKey = key;
        qrCreatedAtMs = createdAtMs;
        qrServerExpired = false;
        qrCreating = false;
        qrTerminalKey = null;
    }

    /** ⚠️ **仅供烟测**：当前线程是否就是 {@code netease-account} 线程。 */
    public static boolean __testOnAccountThread() {
        return isOnAccountThread();
    }

    /**
     * ⚠️ **仅供烟测**：等账号线程把已投递任务做完（FIFO 屏障），最多 {@code timeoutMs} 毫秒。
     *
     * @return true = 已空闲（或无待办）；false = 超时 / 在账号线程上调用
     */
    public static boolean __testDrain(long timeoutMs) {
        if (isOnAccountThread()) {
            return false;
        }
        ExecutorService w = worker;
        if (w == null || w.isShutdown()) {
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        if (!submit(done::countDown)) {
            return false;
        }
        try {
            return done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * ⚠️ **仅供烟测**：单独跑「登录成功 → 触发登录后回调（一轮同步）」这一步（{@link #fireAfterLogin()}），
     * 不碰网络、不碰凭据、不改状态机。
     *
     * <p>用途：验证回调契约（{@link #setAfterLoginHook}）的两条硬要求 ——
     * ①回调异常**不得逃逸**（登录成功不能被同步失败拖成失败）；②回调失败**必须落日志**可见。
     * 真机上的正对照由「真实登录成功」覆盖，这里只做离线负对照。</p>
     */
    public static void __testFireAfterLogin() {
        fireAfterLogin();
    }

    // ---------------------------------------------------------------- 状态机

    /** 生产轮询路径（账号线程）：net 调用与状态迁移都从这里走。 */
    private static void onPollCode(NeteaseApi.QrPoll poll) {
        int code = poll.code();
        // 只在状态**变化**时记一行：1.5s 一次的轮询若每次都写会刷爆日志，
        // 但「扫码确认了插件却没反应」这类问题必须能从日志一眼看出走到哪一步（0.2.2 之前的诊断痛点）。
        // 0.11.32（需求①）：把伺服端原话一并带上 —— -462 的人话在 data.blockText 里（坑 39）。
        if (code != lastLoggedCode) {
            lastLoggedCode = code;
            String words = poll.blockText();
            PluginLog.i(TAG, "扫码状态：" + code + "（" + qrStateText(code) + "）"
                    + ((words == null || words.isBlank()) ? "" : "（伺服端原话：" + PluginLog.sanitize(words) + "）"));
        }
        pollFailureLogged = false;
        String cookies = null;
        if (code == 803) {
            try {
                cookies = NeteaseApi.exportCookies();      // 只在账号线程
            } catch (Throwable t) {
                PluginLog.w(TAG, "导出 cookie 失败：" + describe(t));
            }
        }
        feedQrCode(code, poll.verifyUrl(), cookies, null, 0L, false);
        if (code == 803 && loggedIn()) {
            refreshAccountInfo();
        }
    }

    /** 轮询码的中文说明（日志与排障用）。 */
    private static String qrStateText(int code) {
        switch (code) {
            case 800:
                return "二维码已过期";
            case 801:
                return "等待扫码";
            case 802:
                return "已扫码，等待手机确认";
            case 803:
                return "确认成功";
            case 8821:
            case -462:
                return "需要行为验证码验证（风控拦截）";
            default:
                return "未知状态";
        }
    }

    /**
     * 同一把码的**终局**（800 过期 / 803 成功）只允许落一次（0.11.44）。
     *
     * <p>801/802 是过程码，可以且必须反复落（等待扫码 → 已扫码待确认）；800/803 一落，这张码的
     * 故事就结束了，迟到的复述（例如关窗回滚后还在网线上的旧结论）只会把状态机再压一次 ——
     * 直接丢弃。非终局码一律放行。</p>
     *
     * @return false = 该丢弃（同一 key 的终局已落过）
     */
    private static boolean feedTerminalOnce(NeteaseApi.QrPoll poll) {
        int code = poll.code();
        if (code != 800 && code != 803) {
            return true;
        }
        String k = qrKey;
        if (java.util.Objects.equals(k, qrTerminalKey)) {
            PluginLog.d(TAG, "轮询终局 " + code + " 重复到达（key 未变）⇒ 丢弃");
            return false;
        }
        qrTerminalKey = k;
        return true;
    }

    /**
     * 状态机核心（生产与烟测共用）：把一次轮询结果落成状态 + 副作用（803 落盘凭据）。
     *
     * @param verifyUrl 风控答复里的行为验证地址（0.11.32 需求①；非风控码传空串/null）
     * @param nickname  非空表示调用方已知道昵称（烟测路径），否则 803 后由 {@link #refreshAccountInfo()} 补
     */
    private static void feedQrCode(int code, String verifyUrl, String cookies, String nickname, long uid,
                                   boolean vip) {
        long created = qrCreatedAtMs;
        if (code == 800 || code == 801 || code == 802 || code == 803) {
            clearQrRisk(code);      // 风控放行：扫码档即可收回验证二维码（0.11.32 需求①）
        }
        switch (code) {
            case 801:
                setStatus(new LoginStatus(State.WAITING_SCAN, "等待扫码…", "", 0L, false, "", created + QR_TTL_MS));
                break;
            case 802:
                setStatus(new LoginStatus(State.WAITING_CONFIRM, "已扫码，请在手机上确认…", "", 0L, false, "", created + QR_TTL_MS));
                break;
            case 800: {
                // 0.11.44（用户：窗口不许自己关、过期要当场看得见）：旧写法把「扫码前已登录」的会话
                // **当场**回滚成 LOGGED_IN —— 开着的登录窗下一拍读到 LOGGED_IN 就当成「扫码成功」
                // 自动 dispose（真机 2026-10-03 21:26:33 实证：用户没碰任何按键，窗口自己没了）。
                // 现在 800 只把这张码判死：界面把码压黑 + 提示手动刷新；回滚只发生在关窗那一刻
                // （pauseQrWatch），且 qrServerExpired 会让重开窗时**重新取码**而不是复用死码。
                qrServerExpired = true;
                cancelPollLoop();
                PluginLog.i(TAG, "二维码已过期（800）：等用户点「刷新二维码」重新取码"
                        + ((beforeQr != null && NeteaseApi.isLoggedIn()) ? "（关窗仍会回滚到扫码前的登录态）" : ""));
                setStatus(new LoginStatus(State.EXPIRED, "二维码已过期，请刷新二维码", "", 0L, false, "", 0L));
                break;
            }
            case 803: {
                if (cookies == null || cookies.isBlank()) {
                    PluginLog.w(TAG, "扫码成功（803）但 cookie 为空，视为失败");
                    setStatus(new LoginStatus(State.ERROR, "登录成功但凭据为空，请重试", "", 0L, false, "", 0L));
                    break;
                }
                CookieVault.save(cookies);
                qr = null;
                qrKey = null;
                beforeQr = null;
                cancelPollLoop();
                loginSerial++;                  // 0.11.44：本次开窗之后「真的」登录成功了（界面据此关窗）
                boolean known = nickname != null && !nickname.isBlank();
                setStatus(new LoginStatus(State.LOGGED_IN,
                        known ? "登录成功" : "登录成功（正在获取账号信息）",
                        known ? nickname : "", uid, vip, "", 0L));
                PluginLog.i(TAG, "扫码登录成功：凭据已加密落盘（明文长度 " + cookies.length()
                        + "，内容不落日志），昵称=" + (known ? nickname : "(待获取)"));
                break;
            }
            case 8821:
            case -462: {
                // 网易云风控：要求先完成「行为验证」才能继续（8821 = 老口径，-462 = 新口径，
                // 真机取证 2026-10-02：匿名与 weapi 两条通道、明文与加密两个 key 全部同码 ⇒ 账号/IP 级）。
                //
                // 0.2.3 ~ 0.11.31 的策略是「作废二维码 + 停轮询 + 指路手机号登录」，但真机上用户手机
                // 明明已经确认过，插件却永远拿不到 803（用户需求①「扫码确认登录后软件没反应」）。
                // 0.11.32 改为：**不看门、递梯子** —— 保持轮询（放慢到 6s），同时把伺服端给的行为验证地址
                // 交给界面开成电脑浏览器里的验证页，用户当场在电脑上完成验证，随后的轮询就会自己走回
                // 801/802/803。0.11.33：验证从「手机扫码」改成「电脑上完成」（用户裁定）。
                boolean firstHit = !qrRisk;       // 跃迁标记：只有第一拍值得告警
                captchaHits++;
                qrRisk = true;
                String url = (verifyUrl == null) ? "" : verifyUrl.trim();
                if (!url.isEmpty() && qrVerifyUrl.isEmpty()) {
                    // 冻住**首份**地址：伺服端每次答复都换 event_id/sign，逐次换码等于让用户白扫
                    qrVerifyUrl = url;
                }
                boolean haveUrl = !qrVerifyUrl.isEmpty();
                String detail = "扫码被风控拦截：" + code + "（本次会话第 " + captchaHits + " 次）"
                        + (haveUrl
                        ? "，已冻住行为验证地址（长度 " + qrVerifyUrl.length() + "，值不进日志）"
                        : "，伺服端未给验证地址")
                        + "；保持轮询，等用户在电脑上完成验证";
                if (firstHit) {
                    PluginLog.w(TAG, detail);
                } else {
                    // 0.11.33：真机 2026-10-02 这里是每 6 秒一行 WARN、连打 23 行把日志刷爆 —— 降噪
                    PluginLog.d(TAG, detail + "（风控未解除，不再重复告警）");
                }
                setStatus(new LoginStatus(State.WAITING_SCAN,
                        haveUrl ? "网易云要求先完成安全验证" : "网易云风控拦截了本次扫码登录",
                        "", 0L, false, "", created + QR_TTL_MS));
                break;
            }
            default:
                PluginLog.w(TAG, "未知轮询码：" + code + "（忽略）");
                break;
        }
    }

    /**
     * 风控门复位（0.11.32 需求①）：只要有**正常**轮询码回来，就说明风控已放行，
     * 界面应当从「安全验证」形态退回扫码形态。
     */
    private static void clearQrRisk(int code) {
        if (!qrRisk && qrVerifyUrl.isEmpty()) {
            return;
        }
        PluginLog.i(TAG, "风控已放行（轮询码 " + code + "）⇒ 清掉冻结的验证地址，界面回扫码档");
        qrRisk = false;
        qrVerifyUrl = "";
    }

    /** 凭据失效：清内存 cookie（可选）+ 状态置 EXPIRED，**不删**本地文件。 */
    private static void markExpired(String why, boolean clearJar) {
        if (clearJar) {
            try {
                NeteaseApi.logout();
            } catch (Throwable ignored) {
                // 清 jar 失败不影响状态判定
            }
        }
        PluginLog.w(TAG, why + "：凭据失效 → EXPIRED（本地文件保留，等用户重新登录）");
        setStatus(new LoginStatus(State.EXPIRED, "登录已过期，请重新扫码", "", 0L, false, "", 0L));
    }

    // ---------------------------------------------------------------- 账号线程任务

    /**
     * init 的异步部分（账号线程）：按本地凭据恢复登录态。
     *
     * <p>0.11.7（W1 / A4）：恢复失败时**不再**去接入本机网易云客户端（原来会拉起/隐藏它、用 CDP
     * 读它内存里的 cookie）。现在不建立登录态就是「未登录」，等用户在配置页/登录框里完成手机号登录；
     * 状态文案保持原来的 <b>「未登录」</b>，以免烟测/UI 的既有契约漂移。</p>
     */
    private static void restoreFromVault() {
        restoreFromVaultOnly();
        LoginStatus s = status();
        if (s == null || s.state() != State.LOGGED_IN) {
            PluginLog.i(TAG, "本地凭据未建立登录态 → 等待用户登录"
                    + "（0.11.7：不再接入本机网易云客户端；当前状态=" + (s == null ? "null" : s.state()) + "）");
        }
    }

    /** 读盘 → 注入 → 校验登录态（只负责「本地凭据」这一条路）。 */
    private static void restoreFromVaultOnly() {
        if (!autoLogin) {
            PluginLog.i(TAG, "init：auto_login 已关闭 → 跳过凭据恢复（文件保留）");
            setStatus(new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L));
            return;
        }
        if (!CookieVault.exists()) {
            PluginLog.i(TAG, "init：无本地凭据 → 未登录");
            setStatus(new LoginStatus(State.NONE, "未登录", "", 0L, false, "", 0L));
            return;
        }
        String cookie = CookieVault.load();
        if (cookie == null || cookie.isBlank()) {
            PluginLog.w(TAG, "init：凭据文件存在但无法解密/为空 → 视为未登录（文件保留，便于排障）");
            setStatus(new LoginStatus(State.NONE, "未登录（本地凭据无法解密，请重新登录）", "", 0L, false, "", 0L));
            return;
        }
        try {
            NeteaseApi.importCookies(cookie);
            PluginLog.i(TAG, "init：已注入本地凭据（明文长度 " + cookie.length() + "，内容不落日志）");
        } catch (Throwable t) {
            PluginLog.e(TAG, "init：凭据注入失败", t);
            setStatus(new LoginStatus(State.ERROR, "登录初始化失败：" + describe(t), "", 0L, false, "", 0L));
            return;
        }
        try {
            if (!NeteaseApi.isLoggedIn()) {
                markExpired("init：凭据中不含 MUSIC_U", true);
                return;
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "init：登录态本地判断异常：" + describe(t));
        }
        try {
            Dto.Account acc = NeteaseApi.accountInfo();
            if (acc == null) {
                markExpired("init：账号信息为空", true);
                return;
            }
            setStatus(new LoginStatus(State.LOGGED_IN, "已登录", acc.nickname(), acc.userId(), acc.vip(),
                    acc.vipName(), 0L));
            PluginLog.i(TAG, "init：恢复登录态成功，昵称=" + acc.nickname() + "，uid=" + acc.userId()
                    + "，VIP=" + acc.vip() + "（" + (acc.vipName().isEmpty() ? "档位未知" : acc.vipName()) + "）");
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("301")) {
                markExpired("init：accountInfo 返回 301", true);
            } else {
                // 网络抖动 ≠ 凭据失效：本地 cookie 仍在，不能误判成未登录（否则用户被反复要求登录）
                PluginLog.w(TAG, "init：账号信息校验失败（" + describe(t) + "）→ 凭据保留，状态按已登录（未校验）");
                setStatus(new LoginStatus(State.LOGGED_IN, "已登录（本次未能校验，可稍后重试）",
                        status().nickname(), status().uid(), status().vip(), status().vipName(), 0L));
            }
        }
    }

    // 0.11.7（W1 / A4）删除：doClientLogin()（客户端接入）整段。
    // 原实现：确保本机客户端在跑（必要时由我们拉起并隐藏窗口）→ 用 CDP 读它内存里的 cookie
    // → 注入 jar → 加密落盘 → 补账号信息。这条路径依赖本机 cloudmusic 进程，已在 0.11.7 移除：
    // 插件自带「精简客户端」，登录只走 weapi（扫码 / 手机号+短信验证码）。

    /** 803 之后补昵称/uid/VIP（账号线程）；失败不改变 LOGGED_IN。 */
    private static void refreshAccountInfo() {
        // 新凭据生效后不能继续复用匿名/旧会员权限下的低音质直链和缓存。
        NativeStreamServer.credentialsChanged();
        try {
            Dto.Account acc = NeteaseApi.accountInfo();
            if (acc == null) {
                PluginLog.w(TAG, "登录后账号信息为空，保持已登录");
                return;
            }
            setStatus(new LoginStatus(State.LOGGED_IN, "登录成功", acc.nickname(), acc.userId(), acc.vip(),
                    acc.vipName(), 0L));
            PluginLog.i(TAG, "账号信息已更新：昵称=" + acc.nickname() + "，uid=" + acc.userId()
                    + "，VIP=" + acc.vip() + "（" + (acc.vipName().isEmpty() ? "档位未知" : acc.vipName()) + "）");
        } catch (Throwable t) {
            PluginLog.w(TAG, "登录后获取账号信息失败（登录态保持）：" + describe(t));
        }
        fireAfterLogin();
    }

    /** 触发登录后回调（账号线程；异常只记日志，绝不影响登录态）。 */
    private static void fireAfterLogin() {
        Runnable hook = afterLoginHook;
        if (hook == null) {
            return;
        }
        try {
            PluginLog.i(TAG, "登录成功 → 触发登录后回调（一轮同步）");
            hook.run();
        } catch (Throwable t) {
            PluginLog.e(TAG, "登录后回调失败（登录态不受影响，可用配置页「立即重新同步」手动重试）", t);
        }
    }

    /**
     * 手机号登录收尾（账号线程）：成功 → 导出 cookie → 加密落盘 → **回读校验** → 刷新账号信息。
     *
     * <p>A5 要求「登录成功即凭据加密落盘」，而 {@link CookieVault#save(String)} 自己吞异常
     * （失败只记日志不抛），所以这里必须回读验证：只有 {@code account.json} 真能解出
     * {@code MUSIC_U=} 才算登录成功；否则状态置 ERROR 并返回失败（禁止静默失败）。</p>
     */
    private static NeteaseApi.LoginOutcome finishPhoneLogin(String via, String phone, NeteaseApi.LoginOutcome out) {
        NeteaseApi.LoginOutcome result = out;
        if (result == null) {
            result = new NeteaseApi.LoginOutcome(false, -1, "登录无响应");
        }
        if (!result.ok()) {
            setStatus(new LoginStatus(State.ERROR, "手机号登录失败：" + result.hint(), "", 0L, false, "", 0L));
            return result;
        }
        if (!persistLogin(via, phone)) {
            return new NeteaseApi.LoginOutcome(false, -3, "凭据加密落盘失败（登录未生效）");
        }
        setStatus(new LoginStatus(State.LOGGED_IN, "登录成功（正在获取账号信息）", "", 0L, false, "", 0L));
        loginSerial++;                      // 0.11.44：同 803，手机号登录也是「真的登录成功过一次」
        refreshAccountInfo();
        return result;
    }

    /**
     * 凭据加密落盘 + **回读校验**（A5）。返回 false 时已记 ERROR 状态与 error 日志。
     *
     * <p>回读走 {@link CookieVault#load()}（同一把 account.key 解密），能解出且含
     * {@code MUSIC_U=} 才算「登录成功即可重启宿主仍登录」。日志只记长度与掩码号码，
     * 凭据内容绝不落日志。</p>
     */
    private static boolean persistLogin(String how, String phone) {
        try {
            String cookie = NeteaseApi.exportCookies();
            if (cookie == null || cookie.isBlank()) {
                PluginLog.e(TAG, how + "登录：登录成功但导出的凭据为空（内存 jar 里没有 cookie）");
                setStatus(new LoginStatus(State.ERROR, "登录成功但凭据为空，请重试", "", 0L, false, "", 0L));
                return false;
            }
            CookieVault.save(cookie);
            String back = CookieVault.load();
            // "MUSIC_U=" 与 net/Http 的 cookie 名一致（NeteaseApi.COOKIE_MUSIC_U 在 net 包内私有）
            if (back == null || back.isBlank() || !back.contains("MUSIC_U=")) {
                PluginLog.e(TAG, how + "登录：凭据落盘后回读校验失败（exists=" + CookieVault.exists()
                        + "，回读长度=" + len(back) + "）→ 登录未生效");
                setStatus(new LoginStatus(State.ERROR,
                        "凭据加密落盘失败：请检查插件数据目录写权限后重试", "", 0L, false, "", 0L));
                return false;
            }
            PluginLog.i(TAG, how + "登录成功：凭据已加密落盘并回读校验通过（明文长度 " + cookie.length()
                    + "，回读长度 " + back.length() + "，内容不落日志，号码=" + maskPhone(phone) + "）");
            return true;
        } catch (Throwable t) {
            PluginLog.e(TAG, how + "登录：凭据落盘异常", t);
            setStatus(new LoginStatus(State.ERROR, "凭据保存失败：" + describe(t), "", 0L, false, "", 0L));
            return false;
        }
    }

    /** 发送短信验证码（账号线程）：只转发 net 层结果，**不改登录状态**（发码成功 ≠ 已登录）。 */
    private static Dto.SmsResult doSmsSend(String phone) {
        try {
            Dto.SmsResult r = NeteaseApi.smsSend(phone);
            // 号码只留掩码：日志要能定位「发到哪个号」，又不留下完整手机号
            PluginLog.i(TAG, "短信验证码发送返回：code=" + r.code() + "，号码=" + maskPhone(phone)
                    + (r.message().isEmpty() ? "" : "，伺服端=" + r.message()));
            return r;
        } catch (Throwable t) {
            PluginLog.w(TAG, "短信验证码发送异常：" + describe(t));
            return new Dto.SmsResult(-1, "发送失败：" + describe(t));
        }
    }

    /** 验证码登录（账号线程）：落盘 → 回读校验 → 刷新账号信息。 */
    private static NeteaseApi.LoginOutcome doCaptchaLogin(String phone, String captcha) {
        return finishPhoneLogin("验证码", phone, NeteaseApi.loginByCaptcha(phone, captcha));
    }

    /** 手机号掩码（{@code 138****0000}）：日志与状态文案里都不出现完整号码（UI 也用这个）。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    // ---------------------------------------------------------------- 线程与状态工具

    private static boolean startWorker() {
        synchronized (WORKER_LOCK) {
            if (stopped) {
                PluginLog.d(TAG, "账号线程已停止（生命周期结束），不再新建：" + THREAD_NAME);
                return false;
            }
            ExecutorService w = worker;
            if (w != null && !w.isShutdown()) {
                return true;
            }
            try {
                ExecutorService fresh = Executors.newSingleThreadExecutor(FACTORY);
                worker = fresh;
                // 单线程池默认**懒建线程**：先投一个空任务把 netease-account 线程真正拉起来
                // （与 core.HostBridgeWorker.start() 的「启动即建线程」语义一致，也让「唯一账号线程」可被观测）
                fresh.execute(() -> {
                });
                PluginLog.d(TAG, "账号线程已启动：" + THREAD_NAME);
                return true;
            } catch (Throwable t) {
                PluginLog.e(TAG, "账号线程启动失败", t);
                worker = null;
                return false;
            }
        }
    }

    private static boolean submit(Runnable task) {
        if (task == null) {
            return false;
        }
        if (!startWorker()) {
            return false;
        }
        ExecutorService w = worker;
        if (w == null) {
            return false;
        }
        try {
            w.execute(task);
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "账号任务投递失败（线程已停止？）：" + describe(t));
            return false;
        }
    }

    private static boolean isOnAccountThread() {
        Thread t = accountThread;
        return t != null && Thread.currentThread() == t;
    }

    private static void setStatus(LoginStatus s) {
        if (s == null) {
            return;
        }
        LoginStatus cur = status;
        // 同一条事实不重复写（0.11.44）：轮询每 1.5s 都在回报同一个 801/802，先前每次都换一个新对象，
        // volatile 写与界面重绘全跟着抖。expiresAtMs 只用于排障展示，不参与「是不是同一条事实」的比较。
        if (cur != null && cur.state() == s.state()
                && java.util.Objects.equals(cur.message(), s.message())
                && java.util.Objects.equals(cur.nickname(), s.nickname())
                && cur.uid() == s.uid()
                && cur.vip() == s.vip()
                && java.util.Objects.equals(cur.vipName(), s.vipName())) {
            return;
        }
        status = s;
    }

    private static void setStatus(State st, String message) {
        LoginStatus cur = status();
        setStatus(new LoginStatus(st, message, cur.nickname(), cur.uid(), cur.vip(), cur.vipName(), 0L));
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 异常 → 一行诊断（只留类型与脱敏后的 message，避免把 URL/凭据甩进日志）。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            return t.getClass().getSimpleName();
        }
        String one = m.replace('\n', ' ').replace('\r', ' ').trim();
        if (one.length() > 200) {
            one = one.substring(0, 200) + "…";
        }
        return t.getClass().getSimpleName() + ": " + one;
    }
}
