package com.example.netease.ui;

import com.example.netease.core.PluginLog;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.SmsLogin;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * 「当前账号」明细窗（0.11.35）—— 用户 m00001 问题1 的落点。
 *
 * <p><b>要解决的问题</b>：配置页那一行原来把登录态写进<b>行标题</b>（{@code 当前账号：UID 123456789
 * （ID 123456789） · 会员}），账号 id / 会员信息就那样常驻在界面上；而那一行的类型是 {@code edittext}，
 * 点开是宿主自绘的<b>单输入框对话框</b>（{@code docs/00} 坑 35）——既不是「点一下就能看」的形态，
 * 也不适合承载账号信息。</p>
 *
 * <p><b>本类怎么做</b>：账号行改成 {@code button}，点一下打开本窗，窗里直接给出三件事——</p>
 * <ul>
 *   <li><b>账号名字</b>：昵称；昵称取不到、或本身就是号码形态（含 {@code 138****0000} 这种打码号）时，
 *       与配置页各处的既有约定一致，退成 {@code UID <uid>}，绝不把手机号形态的东西当名字显示；
 *       <b>uid 未知（{@code uid <= 0}）时不拿「UID 0」冒充名字</b> —— 一律给 {@code 未知}，
 *       与同行「账号 ID：未知 / 会员信息：未知」自洽（0.11.36；来源：
 *       {@code svc/AccountService.java} 的 {@code new LoginStatus(State.LOGGED_IN, "登录成功（正在获取账号信息）", "", 0L, false, "", 0L)}，
 *       它会长期停在 LOGGED_IN 而 uid 仍为 0）；</li>
 *   <li><b>账号 id</b>：{@code uid}（未知 ⇒ {@code 未知}）；</li>
 *   <li><b>会员信息</b>：档位名（{@code 黑胶SVIP} / {@code 黑胶VIP} / {@code 音乐包}）/
 *       通用 {@code 会员} / {@code 无会员}（登录态未知 ⇒ {@code 未知}）。
 *       <b>0.11.41 起显示档位名</b>（用户 m00221 ①）：档位取自
 *       {@code net.NeteaseApi.vipNameOf(...)} 问的 {@code music-vip-membership/client/vip/info}；
 *       接口失败 / 认不出的档位退成中性「会员」（{@code LoginStatus.vip()} 是复合量，
 *       只买音乐包也会是 true，绝不猜名字）；没会员写「无会员」。</li>
 * </ul>
 *
 * <p><b>单一真源（0.11.36）</b>：窗内显示的每一行都取自 {@link #rows(AccountService.LoginStatus)}，
 * {@link #render(AccountService.LoginStatus)} 只是把它用换行拼起来。此前构造器与 {@code render()} 各算一套文案，
 * 探针只走 {@code render()} ⇒ 完全可能「探针全绿而窗口文案漂移」。</p>
 *
 * <p><b>窗口构造（0.11.37）</b>：{@code dialog.setContentPane(不透明根面板)}，卡片铺在根面板里
 * （{@code JDialog} 默认 contentPane 是
 * {@code BorderLayout}，0.11.35 把 {@code new GridBagConstraints()} 当布局约束传进去 ⇒ 真机
 * {@code IllegalArgumentException: cannot add to layout: constraint must be a string (or null)}，
 * 且被 {@code open()} 的 {@code catch (Throwable)} 吞掉 ⇒ 点「当前账号」什么也不出现）。根面板不透明、
 * 必须铺满整窗：卡片自绘只到 {@code (w-1, h-1)}，右/下各差 1 px，加上四角圆角外那块 —— 没有组件上色时
 * 真机就是黑的，所以根必须铺满、必须不透明。</p>
 *
 * <p><b>未登录</b>：整窗只给一行「当前未登录」（用户 m00001 问题1 的第 5 条原话），不给任何半截字段。</p>
 *
 * <p><b>只读</b>：窗里没有任何可编辑控件（需求 4「直接呈现，不是用户还能自己更改的输入框」），
 * 只有一颗「关闭」。</p>
 *
 * <p><b>外观（0.11.37；0.11.39 换成与登录窗逐像素同源的遮罩底）</b>：与登录对话框 / 内嵌验证窗同一套
 * 宿主令牌（{@link HostTheme} + {@code LoginDialog} 的静态工厂）。窗口用<b>宿主窗口做 owner</b>、
 * <b>{@code APPLICATION_MODAL} 模态窗</b>（取不到宿主时降级 {@code MODELESS}）—— 与登录窗同口径
 * ⇒ 弹出期间宿主收不到输入、拖不动（用户 m00221 ①「让账号窗的行为与登录窗保持一致」）。
 * <b>0.11.39 起铺满宿主窗口矩形</b>：抓一张宿主底图（{@code captureBackdrop}）+ 60% 遮罩打底
 * （{@code backdropRoot}）、卡片居中 —— 与点「登录」弹出的那扇窗背景逐像素同源（用户 m00221 ①：
 * 「当前账号窗口的背景也要和登录弹出的窗口背景颜色一致」；0.11.38 只改窗口底色，用户看不到变化）。
 * 拿不到底图时退回「按内容 pack + 居中到宿主 + 按卡片圆角切形」（0.11.37 的老样子：
 * 0.11.35 用 {@code screenUnion()} 居中，双屏时并集中心落在两屏接缝附近 —— 用户 m00001
 *「并不是固定在软件中心」的像素来源）。「关闭」按钮与登录窗「取消」同款：
 * {@code sized(pill("关闭", false), BTN_W, BTN_H)} = 260×32 药丸；{@code ESC} 与登录窗「ESC = 取消」
 * 同义，直接关窗（用户 m00221 ②）。
 * （0.11.38 一度把底色改成 {@link HostTheme#PAGE_BG} 就算「与登录窗同色」—— 那只是本窗自己那 1~2 px
 * 边角的颜色，用户看不到任何变化，0.11.39 改成真正的同源底图 + 遮罩。）</p>
 *
 * <p><b>线程与生命周期</b>：一律切 EDT；窗口登记进 {@link UiRegistry}，插件停用时由
 * {@code NeteasePlugin.stop()} 的 {@code disposeAll()} 一并销毁（验收 A9）。</p>
 */
public final class AccountStatusWindow {

    private static final String TAG = "ui.account";

    /** 行高（宿主正文行距 19，留一点余量给中文字形）。 */
    private static final int ROW_H = 22;

    /** 标签列宽（「账号名字 / 账号 ID / 会员信息」三个标签里最宽的那个）。 */
    private static final int LABEL_W = 76;

    private static volatile AccountStatusWindow current;

    private final JDialog dialog;
    private final AccountService.LoginStatus status;

    // ---------------------------------------------------------------- 对外 API

    /**
     * 打开（或前置）「当前账号」明细窗。任意线程可调，内部切 EDT。
     *
     * <p>内容取调用那一刻的 {@link AccountService#status()} —— 每次点都重取，不做缓存。</p>
     */
    public static void open() {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                doOpen();
            } else {
                SwingUtilities.invokeLater(AccountStatusWindow::doOpen);
            }
        } catch (Throwable t) {
            PluginLog.e(TAG, "打开当前账号窗口失败", t);
        }
    }

    /** 仅测试用：开着返回 true。 */
    public static boolean isOpen() {
        AccountStatusWindow w = current;
        return w != null && w.dialog.isDisplayable();
    }

    /** 仅测试用：把任一登录态渲染成窗里那段文案（纯函数，不需要真窗口）。 */
    public static String lineForTest(AccountService.LoginStatus st) {
        return render(st);
    }

    /** 仅测试用：当前窗里显示的文案（没开窗返回空串）。 */
    public static String textForTest() {
        AccountStatusWindow w = current;
        return w == null ? "" : render(w.status);
    }

    // ---------------------------------------------------------------- 文案（纯函数）

    /** 未登录时的唯一一行（窗与探针共用；不要再在别处写字面量）。 */
    static final String NOT_LOGGED_IN = "当前未登录";

    /** 三行明细的标签（顺序 = {@link #rows} 里已登录三行的顺序）。 */
    private static final String[] FACT_LABELS = {"账号名字", "账号 ID", "会员信息"};

    /**
     * <b>唯一真源（0.11.36）</b>：把登录态渲染成窗里要显示的行。
     *
     * <p>未登录 / 非 LOGGED_IN ⇒ <b>恰好一行</b> {@link #NOT_LOGGED_IN}；
     * 已登录 ⇒ <b>恰好三行</b>「账号名字：… / 账号 ID：… / 会员信息：…」。
     * 构造器逐行消费本方法，{@link #render} 只是用换行把它拼起来 ⇒ 窗口文案与探针文案
     * 不可能各算一套（0.11.35 的隐患：探针只走 {@code render()}，窗口另算一套 ⇒ 探针全绿而窗口漂移）。</p>
     */
    static List<String> rows(AccountService.LoginStatus st) {
        if (st == null || st.state() != AccountService.State.LOGGED_IN) {
            return List.of(NOT_LOGGED_IN);
        }
        List<String> vals = values(st);
        return List.of(
                FACT_LABELS[0] + "：" + vals.get(0),
                FACT_LABELS[1] + "：" + vals.get(1),
                FACT_LABELS[2] + "：" + vals.get(2));
    }

    /** 三行的<b>值</b>（下标与 {@link #FACT_LABELS} 一一对应）——窗内的「标签 + 值」两列也从这里取。 */
    private static List<String> values(AccountService.LoginStatus st) {
        long uid = st.uid();
        String nick = st.nickname() == null ? "" : st.nickname().trim();
        String name = SmsLogin.accountName(st);
        // uid 未知时不拿「UID 0」冒充名字（0.11.36）：昵称空白、或昵称是号码形态而被 accountName 退成
        // 「UID <uid>」时，与同行的「账号 ID：未知」保持一致给「未知」。
        if (uid <= 0L && (name.isEmpty() || name.equals("UID " + uid))) {
            name = "未知";
        } else if (name.isEmpty()) {
            name = "未知";
        } else if (nick.isEmpty() && uid <= 0L) {
            name = "未知";
        }
        return List.of(
                name,
                uid > 0L ? String.valueOf(uid) : "未知",
                vipText(st));
    }

    /**
     * 「会员信息」那一行的值（0.11.41，用户 m00221 ①：「要显示对应的会员名称，不是光写『会员』；
     * 没会员就直接写无会员」）。
     *
     * <ul>
     *   <li>有档位名（{@code 黑胶SVIP} / {@code 黑胶VIP} / {@code 音乐包}，取自
     *       {@code net.NeteaseApi.vipNameOf(...)} 问的 {@code music-vip-membership/client/vip/info}）⇒ 原样显示；</li>
     *   <li>有会员权益但档位说不清（接口失败 / 认不出的档位）⇒ 通用「会员」——**不猜名字**；</li>
     *   <li>没有会员 ⇒ 「无会员」（0.11.40 及以前是「非会员」，用户明确要「无会员」）；</li>
     *   <li>uid 未知（{@code uid <= 0}）⇒ 「未知」（与同行「账号 ID：未知」自洽，不拿「无会员」冒充）。</li>
     * </ul>
     */
    private static String vipText(AccountService.LoginStatus st) {
        if (st.uid() <= 0L) {
            return "未知";
        }
        if (!st.vip()) {
            return "无会员";
        }
        String tier = st.vipName() == null ? "" : st.vipName().trim();
        return tier.isEmpty() ? "会员" : tier;
    }

    /**
     * 把登录态渲染成给人看的一段文本（窗里逐行显示，探针按行断言）。
     *
     * <p>分隔用换行：未登录只有一行；已登录三行（账号名字 / 账号 ID / 会员信息）。
     * <b>行文案随 0.11.41 更新</b>（会员信息：档位名 / 通用「会员」/「无会员」/「未知」；
     * {@code tools\smoke\DevModeProbe.java} / {@code harness\src\spw\harness\P3Checks.java} 依赖它），
     * 且由 {@link #rows} 作为唯一真源拼装。</p>
     */
    static String render(AccountService.LoginStatus st) {
        return String.join("\n", rows(st));
    }

    // ---------------------------------------------------------------- 窗口

    private static void doOpen() {
        try {
            AccountStatusWindow w = current;
            if (w != null && w.dialog.isDisplayable()) {
                w.dialog.toFront();
                w.dialog.requestFocus();
                PluginLog.d(TAG, "明细窗已开着 ⇒ 只置前，不重开");
                return;
            }
            AccountStatusWindow created = new AccountStatusWindow();
            current = created;
            UiRegistry.track(created.dialog);
            // 0.11.37：本窗已是 APPLICATION_MODAL（见构造器），setVisible(true) 会**阻塞到窗口关闭**才返回
            // ——与登录窗同款行为。所以「已打开」必须在 setVisible 之前记，否则这行日志要等窗关了才打出来。
            PluginLog.i(TAG, "当前账号明细窗已打开：" + oneLine(created.status));
            created.dialog.setVisible(true);
            PluginLog.i(TAG, "当前账号明细窗已关闭：" + oneLine(created.status));
        } catch (Throwable t) {
            PluginLog.e(TAG, "创建当前账号窗口失败", t);
        }
    }

    private AccountStatusWindow() {
        this.status = AccountService.status();

        // 0.11.36（用户 m00001）：拿宿主窗口做 owner 再居中 —— 无 owner 的 JDialog 是独立顶层窗，会被宿主
        Window host = LoginDialog.findHostWindow();
        // 0.11.37（用户 m00221 ①「账号窗弹出后我还能拖拽软件主窗口，请让账号窗的行为与登录窗保持一致」）：
        // 改成**模态窗**。登录窗的模态口径就是「取到宿主 ⇒ APPLICATION_MODAL，取不到 ⇒ MODELESS」（见
        // ui/LoginDialog.java 的 showOnEdt），本窗照抄 ⇒ 弹出期间宿主收不到输入，自然拖不动。
        boolean modal = host != null;
        // 0.11.39（用户 m00221 ①「当前账号窗口弹出后背景也要和登录弹出的窗口背景颜色一致」）：
        // 「背景」= 弹出时**整块宿主窗口被压暗 60%** 的那层遮罩（登录窗最显眼的就是它：点「登录」整屏
        // 变暗、卡片浮在上面）。0.11.38 只把底色从 CARD_BG 改成 PAGE_BG，那是本窗自己那 1~2 px 边角的
        // 颜色 —— 用户看不到任何变化（「背景还是没变」）。现在改成与登录窗**同一套底**：开窗瞬间抓一张
        // 宿主窗口区域的静态底图（{@link LoginDialog#captureBackdrop}），窗口铺满宿主矩形，底图 + 60% 遮罩
        // 打底、卡片居中（{@link LoginDialog#backdropRoot}），于是两扇窗的背景逐像素同源。
        Rectangle hostRect = modal ? LoginDialog.hostBounds(host) : null;
        boolean pixels = modal && LoginDialog.supportsTranslucency();
        java.awt.image.BufferedImage base = LoginDialog.captureBackdrop(TAG, host, hostRect);
        dialog = new JDialog(host, "当前账号",
                modal ? Dialog.ModalityType.APPLICATION_MODAL : Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        // 与登录窗同款窗口挂法：①铺满宿主矩形时做**逐像素透明窗**（形状由 alpha 决定 ⇒ DWM 不会再给
        // 它切一圈 8 px 硬圆角，四角留的是底图里宿主自己的软边圆角）；②拿不到底图的降级档用不透明底 +
        // {@link LoginDialog#roundCorners} 按卡片圆角切形（窗小、又没底图，切了才不露底色边角）。
        dialog.setBackground(pixels ? new java.awt.Color(0, 0, 0, 0) : HostTheme.PAGE_BG);
        // 0.11.35 真机（2026-10-03 01:5x）：这里曾是 DO_NOTHING_ON_CLOSE ⇒ OS 级关闭（Alt+F4 /
        // 系统菜单关闭 / WM_CLOSE）是空操作，唯一出口是窗内那颗「关闭」按钮；自动化改过窗尺寸后按钮
        // 落到可交互区外 ⇒ 该窗彻底锁死（725×181 的 SunAwtDialog「当前账号」跨在副屏右缘，只能重启宿主清掉）。
        // 本窗是只读信息窗 ⇒ 照既有约定 ui/LoginDialog.java:294 的 DISPOSE_ON_CLOSE 改（可被常规方式关掉）。
        // 对照：ui/VerifyWindow.java:202 的 DO_NOTHING_ON_CLOSE 是**故意**的（验证窗有自己的流程约束），
        // 那是验证窗的样板，不是本窗的样板，不许照抄过来。
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);

        JPanel card = LoginDialog.cardPane();
        card.setLayout(new BorderLayout());
        card.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                HostTheme.CARD_PAD_TOP, HostTheme.CARD_PAD_X, HostTheme.CARD_PAD_BOTTOM, HostTheme.CARD_PAD_X));

        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        LoginDialog.stack(col, gc, row++, 0, LoginDialog.sized(
                LoginDialog.text("当前账号", HostTheme.TITLE_PX, HostTheme.TEXT, true),
                Integer.MAX_VALUE, HostTheme.TITLE_PX + 8));

        // 窗内文案<b>逐行消费 rows()</b>（唯一真源）：探针断言的文本与窗口显示的文本是同一份数据，
        // 不再各算一套（0.11.35 隐患：探针只走 render() ⇒ 可能「探针全绿而窗口文案漂移」）。
        List<String> lines = rows(status);
        if (lines.size() == 1) {
            LoginDialog.stack(col, gc, row++, 12, LoginDialog.sized(
                    LoginDialog.text(lines.get(0), HostTheme.BODY_PX, HostTheme.TEXT, false),
                    Integer.MAX_VALUE, ROW_H));
        } else {
            for (int i = 0; i < lines.size() && i < FACT_LABELS.length; i++) {
                String line = lines.get(i);
                String prefix = FACT_LABELS[i] + "：";
                String value = line.startsWith(prefix) ? line.substring(prefix.length()) : line;
                row = addFact(col, gc, row, i == 0 ? 12 : 6, FACT_LABELS[i] + "：", value);
            }
        }

        // 0.11.37（用户 m00221 ②「关闭按钮样式改成与软件现有样式一致」）：pill() 造出来的按钮**尺寸是它自己的**
        // （构造器收了 w/h 但没用），必须再套一层 sized(..., BTN_W, BTN_H) 才与登录窗 / 内嵌验证窗的「取消」
        // 同款 —— 260×32 药丸。0.11.36 少了这一步 ⇒ 本窗那颗只有文字宽，看起来是颗小胶囊，跟既有按钮不是一个东西。
        JButton closeBtn = LoginDialog.sized(LoginDialog.pill("关闭", false),
                HostTheme.BTN_W, HostTheme.BTN_H);
        closeBtn.addActionListener(e -> dispose());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        btns.setOpaque(false);
        btns.add(closeBtn);
        LoginDialog.stack(col, gc, row, 16, LoginDialog.sized(btns, LoginDialog.CONTENT_W, HostTheme.BTN_H));

        card.add(col, BorderLayout.CENTER);
        // 绝不能写成 getContentPane().add(card, new GridBagConstraints())：JDialog 默认 contentPane 是
        // BorderLayout，第二个参数必须是 String 常量或 null，否则构造途中抛
        // IllegalArgumentException: cannot add to layout: constraint must be a string (or null)
        // —— 0.11.35 真机 22:40:47「创建当前账号窗口失败」的根因（doOpen 的 catch(Throwable) 会把它吞掉）。
        // 根面板 = 登录窗同款（{@link LoginDialog#backdropRoot}）：不透明根（清底者，坑 38 铁律）铺满整窗，
        // 有底图就「底图 + 60% 遮罩」，没有就退回纯 PAGE_BG；卡片交给它的 GridBagLayout 居中（与登录窗
        // 的 buildRoot 一个写法 —— 卡片保持自己的 580 宽，四周留出遮罩区）。
        // 注意：绝不能写成 getContentPane().add(card, new GridBagConstraints())（JDialog 默认 contentPane
        // 是 BorderLayout，第二个参数必须是 String 常量或 null，否则构造途中抛 IllegalArgumentException）。
        JPanel root = LoginDialog.backdropRoot(base);
        root.add(card, new GridBagConstraints());
        dialog.setContentPane(root);

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                UiRegistry.forget(dialog); // 别让已 dispose 的窗留在 UiRegistry.WINDOWS（openCount() 会虚高）
                if (current == AccountStatusWindow.this) {
                    current = null;
                }
                PluginLog.d(TAG, "当前账号明细窗已关");
            }
        });
        if (base == null) {
            // 降级档（取不到宿主 / 抓不到底图）：窗按内容 pack，居中到宿主窗口（0.11.35 用的是屏幕并集
            // screenUnion()：双屏时并集中心落在两屏接缝附近，这正是用户 m00001「并不是固定在软件中心」；
            // 宿主为 null 时 setLocationRelativeTo(null) 等价于居中到屏幕），并按卡片圆角切形。
            dialog.pack();
            dialog.setLocationRelativeTo(host);
            LoginDialog.roundCorners(dialog, HostTheme.CARD_RADIUS);
        } else {
            // 与登录窗同形：先 pack（建对等体），再铺满宿主窗口矩形 —— 底图 + 遮罩 + 居中卡片。
            dialog.pack();
            dialog.setBounds(hostRect);
        }
        // ESC = 关闭（与登录窗「ESC = 取消」同一口径，见 ui/LoginDialog.java 的 registerKeyboardAction）
        dialog.getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** 一行「标签 + 值」：标签固定宽度（三行对齐），值只读。 */
    private static int addFact(JPanel col, GridBagConstraints gc, int row, int gapTop,
                               String label, String value) {
        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel key = LoginDialog.text(label, HostTheme.BODY_PX, HostTheme.TEXT_DIM, false);
        key.setPreferredSize(new Dimension(LABEL_W, ROW_H));
        key.setHorizontalAlignment(SwingConstants.LEFT);
        JLabel val = LoginDialog.text(value, HostTheme.BODY_PX, HostTheme.TEXT, false);
        line.add(key);
        line.add(val);
        LoginDialog.stack(col, gc, row, gapTop, LoginDialog.sized(line, LoginDialog.CONTENT_W, ROW_H));
        return row + 1;
    }

    private void dispose() {
        try {
            if (dialog.isDisplayable()) {
                dialog.dispose();
            }
        } catch (Throwable ignored) {
            // 已经关掉了
        }
        // 立刻退登记（别等 windowClosed 事件）：否则已 dispose 的窗还留在 UiRegistry.WINDOWS，openCount() 虚高。
        UiRegistry.forget(dialog);
        if (current == this) {
            current = null;
        }
    }

    /**
     * 0.11.36：本方法已删除 —— 原实现取 {@code LoginDialog.screenUnion()}（所有屏幕的<b>并集</b>）居中，
     * 双屏时并集中心落在两屏接缝附近，用户看到的就是「弹窗不在软件中心」。现在由
     * {@code dialog.setLocationRelativeTo(host)} 居中到宿主窗口（见构造器）。
     */

    /** 单行化（日志用）。 */
    private static String oneLine(AccountService.LoginStatus st) {
        return render(st).replace("\n", " ｜ ");
    }
}
