package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 「手机号 + 短信验证码」登录的收单与记账。界面入口 = 配置页账号组的「登录」按钮
 * （{@code ui.LoginDialog}，0.11.30 起它同时承载扫码档）；本类只做校验、单飞、抹码与展示行回写。
 *
 * <p>本类管四件事：<b>校验</b>（11 位号 / 4~6 位码）、<b>单飞与冷却</b>（发码 60 秒 / 同一条号码）、
 * <b>抹码</b>（读后即抹 + 启动自愈）、<b>登录态回写</b>（账号串写进配置页展示行）：</p>
 * <ul>
 *   <li>{@link #sendCodeNow(String, String)} / {@link #loginNow(String, String, String)} —— <b>阻塞</b>
 *       API（最长 20 s / 30 s），调用方自己放到后台线程上；</li>
 *   <li>{@link #refreshAccountRow()} —— 展示行的<b>唯一真相来源</b>，两条登录路径（验证码 / 扫码 +
 *       凭据自动恢复）都收敛到这里。</li>
 * </ul>
 *
 * <p><b>展示位纪律（0.11.30，0.11.35 收口）</b>：{@code current_account} 这个键<b>只写不读</b> ——
 * 它是「登录态真相」的盘上落点（{@code PluginConfig.writeCurrentAccount}），<b>永不作为输入</b>。
 * 往它里面粘 11 位手机号或 4~6 位验证码不会触发任何动作（{@link #onAccountConfigChanged()} 只按
 * 登录态回写，不解析键值）。<b>0.11.35 起配置页上已经没有承载它的行</b>：用户 m00001 要求账号信息
 * 不得常驻显示，账号行改成按钮「当前账号」，点开 {@code ui.AccountStatusWindow} 才展示名字 / id / 会员。</p>
 *
 * <p><b>凭据纪律</b>：验证码只在本机内存（不落盘 / 不进日志 / 不进证据，读完即抹）；日志只写位数与
 * 掩码号码（{@code AccountService.maskPhone}）。{@code harness/b3-collect.ps1} 与
 * {@code tools/smoke/collect-evidence.py} 采集配置页文件时对
 * {@code current_account} / {@code login_input} / {@code phone} / {@code sms_code} 做屏蔽。</p>
 *
 * <p><b>线程规矩</b>：{@link #refreshAccountRow()} 等写配置的动作必须回 {@code HostBridgeWorker}
 * 线程；弹窗路径由 {@code ui.LoginDialog} 自己的守护线程调用 {@link #sendCodeNow} /
 * {@link #loginNow}，本类内部把「写配置」这一段回宿主线程。</p>
 */
public final class SmsLogin {

    private static final String TAG = "sms";

    /** 「登录」行的值 = 11 位手机号。 */
    private static final Pattern PHONE_RE = Pattern.compile("^1\\d{10}$");

    /** 行 = 4~6 位验证码（真机短信就是 4~6 位；宽进严出，最终以伺服端判定为准）。 */
    private static final Pattern CODE_RE = Pattern.compile("^\\d{4,6}$");

    /** 脱敏昵称形态（如 {@code 138****0000} 或 {@code 138_********0000}）：号码打码后仍是号码，不拿来当名字。 */
    private static final Pattern MASKED_NICK_RE = Pattern.compile(".*\\d[_*·]{3,}.*");

    /**
     * 昵称 = 本机手机号（含打码形态）：此时展示行不用昵称，改用 {@code UID N}
     * —— 否则「不要把手机号写进这一行」这条纪律会被昵称绕过。
     */
    private static final Pattern NICK_IS_NUMBER_RE = Pattern.compile("\\d{7,}");

    /** 重发冷却，与 {@code ui.LoginDialog} 的 60 秒对齐。 */
    private static final long RESEND_COOLDOWN_MS = 60_000L;

    /** 发码单飞闸：一次只允许一个发码请求在飞。 */
    private static final AtomicBoolean SEND_IN_FLIGHT = new AtomicBoolean(false);

    /** 登录单飞闸：一次只允许一个登录请求在飞（与发码互不阻塞 —— 用户常常在发码还没回来时就已收到短信）。 */
    private static final AtomicBoolean LOGIN_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile long lastSentAtMs;

    /** 最近一次自动发码的号码：同号在冷却期内不重复发（用户改一位再打回来就是换号，照发）。 */
    private static volatile String lastSentPhone = "";

    /**
     * 按当前登录态回写登录态落点（{@code current_account}）—— 已登录 ⇒「昵称（ID uid）」；
     * 未登录 ⇒ 清空。
     *
     * <p>⚠️ 写配置要回宿主交互线程（{@code HostBridgeWorker}）。本方法只按登录态写，不读取、
     * 不解析这个键的内容 —— 它<b>永不作为输入</b>，也不再进配置页 schema（0.11.35）。</p>
     *
     * <p><b>值没变就直接返回（0.11.31 / 坑 41）</b>：本方法被账号组变更钩子调用
     * （{@link #onAccountConfigChanged()}），而它自己又会写盘 ⇒ 无条件写就是自激死循环
     * （真机实测：每秒上千行「配置已变更 → 展示行已清空」、宿主 CPU 283 s）。所以这里先比一次，
     * 相同就既不写也不打日志；真值变化时才写一次，那一次触发的回调会在下一轮被本判据吃掉。</p>
     */
    public static void refreshAccountRow() {
        AccountService.LoginStatus st = AccountService.status();
        String text = rowText(st);
        if (!text.equals(PluginConfig.freshCurrentAccount())) {
            PluginConfig.writeCurrentAccount(text);
            PluginLog.i(TAG, "展示行已" + (text.isEmpty() ? "清空（未登录）" : "写入当前账号：" + text));
        }
        // 0.11.35（用户 m00001 问题1）：账号行改成**按钮**（点开 ui.AccountStatusWindow 才展示
        // 名字 / id / 会员），配置页上再没有任何「常驻显示账号信息」的位置 ⇒ 不再需要把这段文本推进
        // 配置页 schema（0.11.32–0.11.34 的 DevMode.setAccountText 通路随之退役）。
    }

    /** 退出登录后清空展示行，并抹掉遗留的内部键（{@code login_input} 残留 + 可能残留的验证码）。 */
    public static void clearAccountRow() {
        PluginConfig.writeCurrentAccount("");
        PluginConfig.writeLoginInput("");
        PluginConfig.wipeSmsCode("退出登录");
        PluginLog.i(TAG, "展示行已清空（退出登录）");
    }

    // ============================================================ 登录动作

    /**
     * 发短信验证码 —— <b>阻塞</b>（最长 20 秒），调用方负责「不要在 EDT / HostBridgeWorker 上直接调」。
     *
     * <p>本方法<b>不弹任何提示</b>：提示由调用方决定（对话框写自己的状态行）。冷却按<b>号码</b>算：
     * 同一条号码 60 秒内只发一次（防手抖连点）；换一条号码（哪怕只改一位）照发。</p>
     *
     * @return 伺服端结果；本地拦下时返回合成的结果（{@code 429} 冷却中 / {@code -1} 单飞或网络异常 /
     *         {@code -2} 号码格式不对），文案已在 {@link Dto.SmsResult#message()} 里
     */
    public static Dto.SmsResult sendCodeNow(String phone, String source) {
        final String p = phone == null ? "" : phone.trim();
        if (!PHONE_RE.matcher(p).matches()) {
            PluginLog.w(TAG, "发码被拒：手机号格式不符（长度 " + p.length() + "，来源=" + source + "）");
            return new Dto.SmsResult(-2, "手机号要 11 位数字");
        }
        final String masked = AccountService.maskPhone(p);
        long since = System.currentTimeMillis() - lastSentAtMs;
        if (p.equals(lastSentPhone) && lastSentAtMs > 0L && since < RESEND_COOLDOWN_MS) {
            long wait = (RESEND_COOLDOWN_MS - since + 999L) / 1000L;
            PluginLog.i(TAG, "发码被冷却挡下（来源=" + source + "）：" + masked + " 还要等 " + wait + " 秒");
            return new Dto.SmsResult(429, "同一号码 60 秒内只能发一次，还要等 " + wait + " 秒");
        }
        if (!SEND_IN_FLIGHT.compareAndSet(false, true)) {
            PluginLog.d(TAG, "发码已在飞，忽略这一次（来源=" + source + "，" + masked + "）");
            return new Dto.SmsResult(-1, "上一个发码请求还在进行");
        }
        try {
            PluginLog.i(TAG, "发送验证码：" + masked + "（来源=" + source + "）");
            Dto.SmsResult r = AccountService.sendSmsCode(p);
            if (r != null && r.sent()) {
                lastSentAtMs = System.currentTimeMillis();
                lastSentPhone = p;
                rememberPhone(p);
                PluginLog.i(TAG, "验证码已发送（" + masked + "，服务端 code=" + r.code() + "）");
            } else {
                PluginLog.w(TAG, "验证码发送失败：code=" + (r == null ? "-" : r.code())
                        + " 判读=" + smsHint(r));
            }
            return r;
        } catch (Throwable t) {
            PluginLog.e(TAG, "验证码发送异常（已兜住）", t);
            return new Dto.SmsResult(-1, "发送异常：" + t.getClass().getSimpleName());
        } finally {
            SEND_IN_FLIGHT.set(false);
        }
    }

    /**
     * 验证码登录 —— <b>阻塞</b>（最长 30 秒），调用方负责放到后台线程上。
     *
     * <p>成功 / 失败都会走一次 {@link #onLoginDone} 记账（回写展示行），所以调用方只需要把返回结果
     * 写在自己的界面上；本地校验失败（{@code -2}）不记账，由调用方提示。</p>
     */
    public static NeteaseApi.LoginOutcome loginNow(String phone, String code, String source) {
        final String p = phone == null ? "" : phone.trim();
        final String c = code == null ? "" : code.trim();
        if (!PHONE_RE.matcher(p).matches()) {
            PluginLog.w(TAG, "登录被拒：手机号未填或格式不符（长度 " + p.length() + "，来源=" + source + "）");
            return new NeteaseApi.LoginOutcome(false, -2, "先填 11 位手机号");
        }
        if (!CODE_RE.matcher(c).matches()) {
            PluginLog.w(TAG, "登录被拒：验证码位数不符（" + c.length() + " 位，来源=" + source + "）");
            return new NeteaseApi.LoginOutcome(false, -2, "验证码要 4~6 位数字");
        }
        if (!LOGIN_IN_FLIGHT.compareAndSet(false, true)) {
            PluginLog.d(TAG, "登录已在飞，忽略这一次（来源=" + source + "）");
            return new NeteaseApi.LoginOutcome(false, -1, "上一次登录还在进行");
        }
        PluginConfig.wipeSmsCode("登录已开始");     // ★ 读后即抹：这一步之后盘上不再有验证码
        PluginLog.i(TAG, "开始验证码登录（来源=" + source + "，号码=" + AccountService.maskPhone(p)
                + "，验证码 " + c.length() + " 位，值不进日志）");
        NeteaseApi.LoginOutcome out;
        try {
            out = AccountService.loginByCaptcha(p, c);
        } catch (Throwable t) {
            PluginLog.e(TAG, "登录请求异常（已兜住）", t);
            out = new NeteaseApi.LoginOutcome(false, -1, "登录异常：" + t.getClass().getSimpleName());
        } finally {
            LOGIN_IN_FLIGHT.set(false);
        }
        final NeteaseApi.LoginOutcome r = out;
        if (r != null && r.ok()) {
            rememberPhone(p);
        }
        bookkeeping(r, source);
        return r;
    }

    /** 登录收尾（回写展示行）—— 必须回宿主交互线程做，写配置才合法。 */
    private static void bookkeeping(NeteaseApi.LoginOutcome out, String source) {
        try {
            HostBridgeWorker.get().runNow(() -> onLoginDone(out, source));
        } catch (Throwable t) {
            PluginLog.w(TAG, "登录收尾回宿主线程失败（已兜住）：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 登录收尾（⚠️ 必须在宿主交互线程上跑）：成功 ⇒ 回写展示行（昵称 + ID）；失败 ⇒ 同样<b>回写真相</b>
     * ＋按业务码给提示。
     */
    private static void onLoginDone(NeteaseApi.LoginOutcome out, String source) {
        if (out != null && out.ok()) {
            PluginLog.i(TAG, "验证码登录成功（来源=" + source + "）：" + AccountService.statusLine());
            refreshAccountRow();
            Notifier.success("登录成功：" + AccountService.statusLine());
            return;
        }
        refreshAccountRow();
        String hint = out == null ? "登录无响应（详见日志）" : out.hint();
        int code = out == null ? -1 : out.code();
        PluginLog.w(TAG, "验证码登录失败（来源=" + source + "）：code=" + code
                + " 判读=" + hint + "（验证码不进日志）");
        if (code == -3) {
            Notifier.error("登录已通过，但凭据加密落盘失败：" + hint);
        } else if (code == -2) {
            Notifier.error(hint);
        } else if (AccountService.status() != null
                && AccountService.status().state() == AccountService.State.ERROR) {
            Notifier.error("登录失败：" + hint);
        } else {
            Notifier.warn("登录失败：" + hint);
        }
    }

    // ============================================================ 展示行（只展示，永不作为输入）

    /**
     * 账号组配置变更回调（{@code NeteasePlugin.start()} 里注入）。
     *
     * <p>落盘会触发本回调，但本方法<b>不解析键值、不分派任何登录动作</b>：只按当前登录态把
     * {@code current_account} 回写成真相（未登录时回写成空）。因此往它里面粘手机号 / 验证码，
     * 除了被下一次回写抹掉以外不会发生任何事（0.11.35 起配置页上也没有承载它的行了）。</p>
     */
    public static void onAccountConfigChanged() {
        refreshAccountRow();
    }

    /**
     * 登录态落点文案：已登录 ⇒ {@code 昵称（ID uid）}（VIP 追加 {@code · 黑胶SVIP} / {@code · 会员}
     * 这类后缀，档位名见 {@link #vipSuffix}），未登录 ⇒ 空串。
     *
     * <p>昵称取不到（空）或本身就是号码时用 {@code UID uid} —— 手机号绝不出现在这里。</p>
     *
     * <p><b>0.11.35</b>：这段文本只落进配置键（{@code current_account}），<b>不再写进配置页</b> ——
     * 账号信息一律由 {@code ui.AccountStatusWindow} 在用户点击「当前账号」后逐项呈现；页面上常驻的
     * 只有「当前账号」四个字（用户 m00001 问题1）。</p>
     */
    private static String rowText(AccountService.LoginStatus st) {
        if (st == null || st.state() != AccountService.State.LOGGED_IN) {
            return "";
        }
        return accountName(st) + "（ID " + st.uid() + "）" + vipSuffix(st);
    }

    /**
     * 会员后缀（0.11.41，用户 m00221 ①）：有档位名就写档位名（{@code  · 黑胶SVIP} 这种），
     * 有权益但档位说不清 ⇒ 通用 {@code " · 会员"}，没会员 ⇒ 空串。
     */
    private static String vipSuffix(AccountService.LoginStatus st) {
        if (!st.vip()) {
            return "";
        }
        String tier = st.vipName() == null ? "" : st.vipName().trim();
        return " · " + (tier.isEmpty() ? "会员" : tier);
    }

    /**
     * 账号名字（0.11.35）：昵称；昵称取不到、或本身就是号码形态（含 {@code 138****0000} 这种打码号）
     * 时退成 {@code UID <uid>} —— 这是账号展示位的<b>既有约定</b>（0.11.32 起），此处只是把它抽成一个
     * 公共入口，供「当前账号」明细窗（{@code ui.AccountStatusWindow}）与 {@link #rowText} 共用，
     * 免得两处各写一份判断而走偏。手机号形态的东西永不作为名字显示。
     */
    public static String accountName(AccountService.LoginStatus st) {
        if (st == null || st.state() != AccountService.State.LOGGED_IN) {
            return "";
        }
        long uid = st.uid();
        String nick = st.nickname() == null ? "" : st.nickname().trim();
        return nick.isEmpty() || looksLikeNumber(nick) ? "UID " + uid : nick;
    }

    /** 昵称是不是号码（含打码号 {@code 138****0000} / {@code 138_********0000}）—— 是就不用它。 */
    private static boolean looksLikeNumber(String nick) {
        return MASKED_NICK_RE.matcher(nick).matches() || NICK_IS_NUMBER_RE.matcher(nick).find();
    }

    // ============================================================ 启动自愈

    /**
     * 启动自愈（只抹不解释）：抹掉遗留的验证码键与 0.11.23–0.11.25 遗留输入键
     * （{@code login_input}）里的残留值 —— <b>绝不据此登录</b>，也不自动发码。
     *
     * <p>展示行由 {@link #refreshAccountRow()} 按真实登录态回写，启动时不做延迟回填：自动恢复登录是
     * 异步的，成功后由 {@code AccountService.setAfterLoginHook} 的回调与 {@code NeteasePlugin}
     * 的主动对齐统一回填；若在此起一个 sleep 线程，{@code stop()} 时可能仍未结束 ⇒ harness
     * H10.1「无插件自有线程残留」会红。</p>
     */
    public static void startupHygiene() {
        // ① 老版本（0.11.22 及以前）的遗留验证码键
        String legacyCode = PluginConfig.freshSmsCode().trim();
        if (!legacyCode.isEmpty()) {
            PluginConfig.wipeSmsCode("启动自愈");
            PluginLog.w(TAG, "启动时抹掉遗留验证码键（" + legacyCode.length() + " 位），不据此登录");
        }
        // ② 0.11.23–0.11.25 的输入行（login_input）：控件已退役，只剩盘上的残留
        if (!PluginConfig.freshLoginInput().trim().isEmpty()) {
            PluginConfig.writeLoginInput("");
            PluginLog.w(TAG, "启动时抹掉遗留输入行的残留（0.11.23–0.11.25 的 login_input）");
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 把手机号写进**内部键**（界面不显示它；让「号码原样保留」不受展示行影响，也是弹窗预填的来源）。 */
    private static void rememberPhone(String phone) {
        if (PHONE_RE.matcher(phone).matches()) {
            PluginConfig.writePhone(phone);
        }
    }

    /** 发码失败的读得懂的说法：优先用伺服端文案，没有就按业务码给。 */
    private static String smsHint(Dto.SmsResult r) {
        if (r == null) {
            return "发送失败（详见日志）";
        }
        String msg = r.message() == null ? "" : r.message().trim();
        if (!msg.isEmpty()) {
            return msg;
        }
        return switch (r.code()) {
            case -2 -> "手机号格式不正确（需 11 位）";
            case -1 -> "网络或账号线程不可用，请稍后重试";
            default -> "服务端返回 code=" + r.code();
        };
    }

    private SmsLogin() {
    }
}
