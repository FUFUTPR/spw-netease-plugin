package com.example.netease.ui;

import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.core.QrEncoder;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.SmsLogin;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.IllegalComponentStateException;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 登录对话框 —— <b>0.11.26 用户规格</b>的原话实现 + <b>0.11.27 皮肤与宿主同款</b>：
 *
 * <blockquote>「点开登录只弹出一个输入框，我无法分别填写手机号和验证码……打开登录后弹出对话框，
 * 框内自上而下为：① 第一个输入框 —— 填写手机号；② 第二个输入框 —— 填写验证码；
 * ③ 验证码输入框的右侧 —— 一个「获取验证码」按钮；④ 最下方 —— 「取消」和「登录」两个按钮。」</blockquote>
 *
 * <blockquote>「这登录打开后的样式要和软件的样式一样，或者说直接调用软件内的ui层就行。」</blockquote>
 *
 * <p><b>为什么必须自绘</b>：宿主配置页只会渲染四种控件（{@code edittext} / {@code button} /
 * {@code switch} / {@code list}），其中 {@code edittext} 点开是<b>宿主自己的</b>模态窗 ——
 * 一个输入框 + 宿主生成的「取消 / 确认」，插件既改不了它的结构也拿不到第二次输入。
 * 所以「同一屏里两个输入框 + 一个按钮 + 两个动作按钮」只能由插件自绘（宿主不提供任何对话框 API，
 * docs/00 §6.27）。配置页那一侧的入口是一个普通 {@code button}（{@code NeteasePlugin.openLoginDialog}）。</p>
 *
 * <p><b>为什么不能直接用宿主的 UI 层</b>：宿主是 Compose 应用（jpackage app-image，JDK 25 + AOT 缓存），
 * 工坊 API 的 19 个类里 {@code WorkshopApi$Ui} 只有 toast，且本项目构建链只有 javac（无 Kotlin/Compose
 * 编译器）⇒ 既没有可调用的宿主 UI 入口，也无法生成 {@code @Composable}。故 0.11.27 改成
 * <b>把宿主自己的模态窗逐像素量成令牌</b>（{@link HostTheme}），再用 Swing 复刻。</p>
 *
 * <p><b>窗口挂法（0.11.28 起改版；0.11.38 的圆角裁剪已在 0.11.39 整条撤掉）</b>：宿主自己的模态窗是
 * 「整窗铺一层纯黑 60% 遮罩 + 居中卡片」（实测 ×0.40）。本类照做：打开瞬间用 {@link Robot} 抓一张
 * <b>宿主窗口区域的静态底图</b>，<b>铺满整块矩形（四角也铺）</b>后先画底图、再画遮罩、再画卡片 ——
 * 四角画的就是底图里宿主自己那个 DWM 软边圆角，与不弹窗时同形，只是整体压暗 60%。
 * 0.11.27 的真机缺陷是逐像素透明窗里整条组件链都「不透明=false」，Swing 找不到不透明祖先来清底，
 * 任何局部重绘（焦点变化、HTML 视图延迟布局）都把新画面直接叠在旧画面上 ⇒ 文字重影 / 焦点残影（坑 38）；
 * 0.11.38 的修法是「窗本体逐像素透明 + 根面板硬裁剪到圆角矩形」，那条路把宿主的 AA 软边换成硬边 ⇒
 * 四角各留一道硬黑边（用户 m00221 ③/④ 报的正是它），0.11.39 撤掉裁剪（{@link RootPane} 只铺不裁）。
 * 抓底图失败 ⇒ 回退只铺遮罩的透明档；再取不到宿主窗口 ⇒ 回退带标题栏的小窗（功能不变）。
 * 模态沿用 {@code APPLICATION_MODAL}：模态 setVisible 进的是嵌套事件循环，
 * EDT 仍在跑，宿主不会冻结（{@link #HEARTBEAT_MS} 心跳就是为了让真机日志能证明这一点）。</p>
 *
 * <p><b>三种主体（0.11.30 需求①②）</b>：卡片内一行<b>分段切换</b>「扫码 | 验证码」，<b>默认扫码</b>；
 * 扫码态 = 窗内 260×260 二维码 + 一行状态 + 主按钮变「刷新二维码」，验证码态 = 手机号框 + （验证码框 +
 * 「获取验证码」），两者<b>共用同一张卡片、同一套按钮</b> —— 0.11.22 起那个独立的扫码窗
 * （{@code ui.LoginWindow}）整类退役，用户不再看到第二个窗口。第三种「风控验证」态：验证码登录被
 * 网易云要求行为验证（{@code 8821} / {@code -462}）时，把服务端答复里带出的验证地址
 * （{@link NeteaseApi.LoginOutcome#verifyUrl()}）就地画成二维码让用户用手机扫，扫完点
 * 「我已完成，重试」；地址取不到时状态行退化成「点这行改用扫码登录」的出口。</p>
 *
 * <p><b>凭据纪律</b>：验证码只在本机内存里用 —— 不写配置文件、不进日志、不进证据（日志只写位数与
 * 掩码号码），关窗 / 取消 / 登录成功都立刻抹掉输入框内容。手机号可预填（内部键 {@code phone}），
 * 日志同样只写掩码。</p>
 *
 * <p><b>线程规矩</b>：本类所有界面动作都在 EDT；两个阻塞网络调用（发码最长 20 s / 登录最长 30 s）
 * 丢给一次性守护线程（{@code netease-login-dialog-*}），跑完再回 EDT 改界面。</p>
 */
public final class LoginDialog {

    private static final String TAG = "ui.login-dialog";

    /** 手机号（11 位，13~19 开头）—— 与 {@code SmsLogin} 同一判据。 */
    private static final Pattern PHONE_RE = Pattern.compile("1[3-9]\\d{9}");

    /** 短信验证码：4~6 位数字（真机短信就是 4~6 位，最终以伺服端判定为准）。 */
    private static final Pattern CODE_RE = Pattern.compile("\\d{4,6}");

    /** 插件自有窗口的类名前缀：挑宿主窗口时必须排除自己（跨类加载器比 Class 会炸，只比名字）。 */
    private static final String OURS_PREFIX = "com.example.netease.";

    private static final int HOST_MIN_W = 200;
    private static final int HOST_MIN_H = 150;

    /** EDT 心跳：每 5 秒一行日志，证明模态对话框没有冻住宿主的事件循环。 */
    private static final int HEARTBEAT_MS = 5000;

    /** 「获取验证码」冷却秒数，与 {@code SmsLogin.RESEND_COOLDOWN_MS} 对齐。 */
    private static final int COOLDOWN_SEC = 60;

    /** 卡片内容宽度 = 宿主模态窗实测 580 − 左右内边距 20×2。 */
    static final int CONTENT_W = HostTheme.CARD_W - HostTheme.CARD_PAD_X * 2;   // 540；ui\VerifyWindow 也用它对齐

    /*
     * <b>宿主窗口那圈圆角（整数：8 px）—— 0.11.39 起<b>不照着它切形</b>，理由都在这段里</b>
     *
     * <p>宿主窗（{@code ComposeWindow}）是无边框窗，四个角是 Windows 11 给窗口默认切出来的（96 DPI 下
     * 半径 8 px）。真机两张（{@code build\diag\login2-1003-194258.png} 有遮罩 /
     * {@code now-1003-193915.png} 无遮罩）在同一像素列上量到的角部斜坡完全一致：
     * {@code 0,0,0,0,7,24,33,43,47…} —— 那是 <b>DWM 用抗锯齿</b>描出来的软边（43 是宿主半覆盖像素），
     * 不是我们能画出来的东西。</p>
     *
     * <p><b>0.11.37 / 0.11.38 的两条「照它切形」的路都撤了</b>：①给窗口 {@code setShape(半径 8)}；
     * ②把根面板硬 {@code clip} 到半径 8 的圆角矩形。两者都是<b>硬边</b>（SetWindowRgn / 硬 clip 都不做
     * AA）：实机量下来切边内侧 = 「底图 × 0.40」的暗值（19），外侧 = 宿主自己的 AA 亮值（43），
     * 相邻两像素差一档 ⇒ 四个角各留一道<b>硬黑边</b>，正是用户 m00221 反复报的「四角还是有黑色的角」。</p>
     *
     * <p><b>0.11.39 的正解是「根本不切」</b>：底图抓的是宿主窗口那一瞬的<b>原像素</b>（含它自己的 AA
     * 圆角与角外的桌面），整块铺满后叠 60% 遮罩 ⇒ 四角自然就是「不弹窗时那个角，被压暗 60%」，
     * 形状 / 软边 / 位置三者都与不弹窗时一模一样，且没有本窗新画的任何一条边。窗口本体仍做逐像素透明
     * （{@code d.setBackground(new Color(0,0,0,0))}）：逐像素透明窗在 Win32 上走 UpdateLayeredWindow，
     * 形状由 alpha 决定 ⇒ DWM 不会再加一圈 8 px 圆角把我们刚铺好的角切掉（不透明窗就会被 DWM 切，
     * 角上又变回硬边）。</p>
     */

    /** 验证码框宽度 = 内容宽度 − 「获取验证码」按钮 − 两者间距。 */
    private static final int CODE_W = CONTENT_W - HostTheme.SEND_W - HostTheme.SEND_GAP;

    /** 二维码边长（0.11.30 需求②：扫码 / 验证码 / 风控共用同一张卡片，只有中间那块换内容）。 */
    private static final int QR_EDGE = 260;

    /** 主体高度（扫码 / 风控档）= 二维码边长。 */
    private static final int BODY_QR_H = QR_EDGE;

    /** 主体高度（验证码档）= 手机号框 + 8 + 验证码行。 */
    private static final int BODY_SMS_H = HostTheme.FIELD_H * 2 + 8;

    /**
     * 主体高度（风控档，0.11.33）= 两行文字。
     *
     * <p>0.11.32 这里是二维码（手机扫码验证）；0.11.33 用户裁定「安全验证要在电脑上完成、不许手机扫码」
     * ⇒ 风控档只剩文字与一个「打开验证页」入口，卡片自然回落到手机号档那一档高度。</p>
     */
    private static final int BODY_RISK_H = 118;

    /** 二维码刷新间隔：每秒看一眼扫码状态（读快照，不联网）。 */
    private static final int QR_TICK_MS = 1000;

    /**
     * 两次取码的最小间隔（0.11.33）：真机日志 2026-10-02 16:52:25 同一秒两行「取二维码」
     * （{@code onShown()} 与 {@code switchTo(QR,true)} 各起了一次），伺服端收到两个 unikey
     * —— 对一个正在判风控的账号，这看起来更像异常行为。
     */
    private static final long QR_MIN_GAP_MS = 2000L;

    /**
     * 主体形态（0.11.30）：{@code QR} = 扫码（默认档）；{@code SMS} = 验证码；
     * {@code RISK} = 被风控拦住 ⇒ 窗内给出**电脑上**完成行为验证的入口（0.11.33 用户裁定）。
     */
    private enum Mode { QR, SMS, RISK }

    /**
     * 外观档（0.11.28）：<b>默认 SNAPSHOT</b> —— 静态底图铺满整块宿主窗口矩形（四角含宿主的软边圆角）
     * + 不透明根面板 + 60% 遮罩（看得见宿主，且每层绘制都落在不透明表面上 ⇒ 重绘能清底，不会出重影），
     * 窗本体逐像素透明只为「别让 DWM 再切一圈圆角」；{@code TRANSLUCENT} = 0.11.27 的逐像素透明窗
     * （抓不到底图时的回退）；{@code PLAIN} = 取不到宿主窗口时的带标题栏小窗。
     */
    private enum Look { SNAPSHOT, TRANSLUCENT, PLAIN }

    /** 同一时刻只允许一个登录框（重复点「登录」按钮 ⇒ 置前，不叠窗）。 */
    private static volatile Session current;

    private LoginDialog() {
    }

    /**
     * 打开登录对话框（立即返回；真正的建框在 EDT 上做）。
     *
     * @param phonePrefill 预填手机号（内部键里存着的那条；空串表示不预填）。调用方必须在宿主交互线程上
     *                     读配置，本方法只接收值。
     */
    public static void open(String phonePrefill) {
        final String prefill = phonePrefill == null ? "" : phonePrefill.trim();
        SwingUtilities.invokeLater(() -> {
            try {
                showOnEdt(prefill);
            } catch (Throwable t) {
                PluginLog.e(TAG, "登录窗口打不开（已兜住）", t);
                Notifier.error("登录窗口打不开：" + t.getClass().getSimpleName() + "（详见日志 tag: " + TAG + "）");
            }
        });
    }

    /** 真机自检用：当前是否有登录框在显示。 */
    public static boolean isOpen() {
        Session b = current;
        return b != null && b.alive;
    }

    /**
     * <b>离屏自检用</b>（{@code tools/smoke/DialogSkinProbe}）：构建与真窗<b>完全同一份</b>组件树，
     * 但不显示、不联网、不碰宿主 API —— 供探针渲染成图片量像素，证明皮肤令牌真的落在界面上。
     *
     * <p>不带底图 ⇒ 逐像素透明档（回退外观，探针量的是「遮罩 alpha = 153」那条路径）。
     * 要量真机默认档（静态底图 + 不透明窗）请用 {@link #buildPreview(BufferedImage)}。</p>
     */
    static JPanel buildPreview() {
        return buildPreview(null);
    }

    /**
     * <b>离屏自检用（0.11.28）</b>：{@code base != null} ⇒ 快照档（不透明窗 + 静态底图 + 遮罩），
     * 这正是真机默认外观；传 {@code null} 等价于 {@link #buildPreview()}。
     */
    static JPanel buildPreview(BufferedImage base) {
        return buildPreview(base, "sms");
    }

    /**
     * <b>离屏自检用（0.11.30 需求①②）</b>：点名渲染某一档 —— 真窗打开时固定进扫码档
     * （{@link Session#onShown()}），验证码档与风控档只能显式点名；探针要逐档量几何与文案，
     * 只量默认那一档就会漏掉另外两档的回归。
     *
     * @param mode {@code "qr"} = 扫码档 / {@code "risk"} = 风控档（验证码登录引来）/ {@code "qrrisk"} =
     *             风控档（扫码引来）/ 其他（含 {@code "sms"}）= 验证码档
     */
    static JPanel buildPreview(BufferedImage base, String mode) {
        boolean qr = mode != null && mode.equalsIgnoreCase("qr");
        boolean risk = mode != null && mode.equalsIgnoreCase("risk");
        boolean qrRisk = mode != null && mode.equalsIgnoreCase("qrrisk");
        Session box = new Session(null, "");
        box.mode = qr ? Mode.QR : (risk || qrRisk) ? Mode.RISK : Mode.SMS;
        JPanel root = buildRoot(box, base == null ? Look.TRANSLUCENT : Look.SNAPSHOT, base);
        if (qr) {
            // 真机上扫码档由 onShown() → openQr() 驱起来（会联网取码）；离屏只能点一份假码，
            // 否则主体区是空的、探针就量不到「二维码确实画在卡片里」这件事。
            box.qrLabel.setIcon(new ImageIcon(QrEncoder.png(
                    "https://music.163.com/login?codekey=probe-only-0123456789abcdef", 232)));
            box.msg("用网易云音乐 App 扫一下这个码", null);
        }
        if (risk) {
            // 探针专用假地址：只为让风控档真的摆出「电脑验证」那一态（不联网、不碰宿主、地址不进日志）。
            // dialog == null ⇒ autoOpenVerifyPage() 的守卫会拦住 —— 离屏预览绝不会弹浏览器。
            box.showRisk(new NeteaseApi.LoginOutcome(false, -462, "网易云要求先完成安全验证",
                    "https://music.163.com/encrypt-pages?s=probe-only"));
        }
        if (qrRisk) {
            // 同一个风控档的**另一条来路**：扫码被风控（0.11.33 用户报障①的原现场 —— 验证态必须
            // 仍站在「扫码」那一枚上，不能串到「验证码登录」）。这条不联网：只调 showRiskFromQr。
            box.showRiskFromQr("https://music.163.com/encrypt-pages?s=probe-only");
        }
        Dimension d = new Dimension(760, 470);
        root.setPreferredSize(d);
        root.setSize(d);
        root.doLayout();
        return root;
    }

    // ------------------------------------------------------------------ 建框

    private static void showOnEdt(String prefill) {
        Session open = current;
        if (open != null && open.alive) {
            open.dialog.toFront();
            open.dialog.requestFocus();
            PluginLog.i(TAG, "登录窗口已在显示中 ⇒ 置前，不重复开");
            return;
        }
        Window host = findHostWindow();
        boolean modal = host != null;
        // 0.11.37 口径（0.11.38 的「贴宿主绘制面」已按用户要求整块回退）：贴宿主**窗口矩形** hostBounds。
        Rectangle hostRect = modal ? hostBounds(host) : null;
        // 平台是否支持逐像素透明窗（0.11.39 起含义已变）：只为「别让 DWM 再替我们切一圈圆角」——
        // 逐像素透明窗的形状由 alpha 决定，DWM 不加角；不透明窗会被 DWM 切 8 px 硬圆角 ⇒ 角上又出现
        // 用户报的那种硬黑边。本窗自己**不切角**（四角由底图里宿主自己的软边圆角负责），见上面
        // 「宿主窗口那圈圆角」那段注释。
        boolean pixels = supportsTranslucency();
        // 0.11.28 默认档：打开瞬间抓一张宿主窗口区域的静态底图，装进窗里画（铺满宿主窗口，
        // 先底图、再 60% 遮罩、再卡片）。0.11.27 的逐像素透明窗清不了底 ⇒ 重影/位移（坑 38）；
        // 0.11.38 起底图的清底者仍在 RootPane（不透明根，铁律），只是把窗本体也做成透明来留出圆角。
        BufferedImage base = captureBackdrop(TAG, host, hostRect);
        Look look = base != null ? Look.SNAPSHOT
                : (modal && pixels ? Look.TRANSLUCENT : Look.PLAIN);
        JDialog d = new JDialog(host, "登录网易云音乐",
                modal ? Dialog.ModalityType.APPLICATION_MODAL : Dialog.ModalityType.MODELESS);
        if (look == Look.PLAIN) {
            // 降级：取不到宿主窗口或平台不支持透明 ⇒ 带标题栏的小窗（可拖动、可 Alt+F4），底色 = 卡片色
            d.setBackground(HostTheme.CARD_BG);
            if (!modal) {
                d.setAlwaysOnTop(true);                  // 没有 owner 的模态窗会自己变成根窗口，只能置顶
            }
        } else {
            d.setUndecorated(true);
            // 0.11.39（修用户 m00221 ③/④「登录窗 / 账号窗四角还是有黑色的角」）：非降级档做**逐像素
            // 透明窗**，但**不切圆角** —— 根面板（见 RootPane）铺满整块矩形，四角画的就是底图里宿主
            // 那个 DWM 软边圆角，形状 / 软边 / 位置与不弹窗时一模一样，只是整块压暗 60%。切形
            // （setShape / 硬 clip）会把软边换成硬边 ⇒ 已整条撤掉。
            // 平台不支持透明时退回不透明底：这时角会被 DWM 切，最坏差一条硬边（降级路径，可接受）。
            d.setBackground(pixels ? new Color(0, 0, 0, 0) : HostTheme.PAGE_BG);
        }
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.setResizable(false);
        Session box = new Session(d, prefill);
        box.backdrop = base;               // 0.11.34：内嵌验证窗复用这张底图（抓屏会抓到登录窗自己）
        d.setContentPane(buildRoot(box, look, base));
        d.getRootPane().setDefaultButton(box.loginBtn);
        // ESC = 取消（与宿主自己的模态窗一致）
        d.getRootPane().registerKeyboardAction(e -> box.cancelBtn.doClick(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                box.wipe();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                box.cleanup();
            }
        });
        if (hostRect == null || look == Look.PLAIN) {
            d.pack();
            d.setLocationRelativeTo(host);
        } else {
            // 铺满宿主窗口（与宿主自己的模态窗同形，四角含宿主的软边圆角）；底图与这块矩形 1:1
            d.pack();            // 先建对等体（addNotify），之后 setBounds 才落到真窗口上
            d.setBounds(hostRect);
        }
        UiRegistry.track(d);
        box.heartbeat = new Timer(HEARTBEAT_MS, e -> box.onHeartbeat());
        box.heartbeat.start();
        current = box;
        PluginLog.i(TAG, "登录窗口打开（宿主窗口="
                + (host == null ? "未取到 ⇒ 降级为不阻塞 + 置顶" : host.getClass().getName())
                + "，模态=" + modal
                + "，外观=" + lookNote(look, hostRect, base)
                + "，预填号码=" + (prefill.isEmpty() ? "无" : AccountService.maskPhone(prefill)) + "）");
        // 0.11.30 需求②：默认进**扫码**档（分段切换的第二档默认状态由 onShown 设定并立刻取码；
        // 放在 setVisible 之前 ⇒ 用户看到的第一帧就是扫码档，不会先闪一下验证码档）。
        box.onShown();
        d.setVisible(true);       // 模态时在此进嵌套事件循环（EDT 仍 pump，见心跳日志）
        PluginLog.i(TAG, "登录窗口已关闭（返回宿主交互）");
    }

    /** 宿主窗口在屏幕上的矩形（副屏负坐标也要正确）；0.11.39 起同包共用（明细窗要贴同一块矩形）。 */
    static Rectangle hostBounds(Window host) {
        try {
            return new Rectangle(host.getLocationOnScreen(), host.getSize());
        } catch (Throwable ignored) {
            return host.getBounds();
        }
    }

    /** 平台是否支持逐像素透明窗（不支持就不画遮罩，免得弹出一个黑块）。 */
    /** 平台是否支持逐像素透明窗；0.11.39 起同包共用（明细窗要按同一判据决定窗口底色）。 */
    static boolean supportsTranslucency() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            boolean ok = gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
            PluginLog.d(TAG, "逐像素透明支持=" + ok);
            return ok;
        } catch (Throwable t) {
            PluginLog.w(TAG, "查询透明支持失败 ⇒ 走降级小窗：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 把宿主窗口矩形裁到屏幕并集里（<b>0.11.29，坑 40</b>）；完全不相交返回 {@code null}。
     *
     * <p><b>纯函数，可离屏断言</b>（{@code tools/smoke/DialogSkinProbe} 量这条规则）：宿主 ComposeWindow 的
     * {@code getLocationOnScreen()+getSize()} 含 Windows 那圈<b>不可见的 8 px 边框/阴影</b> —— 真机实测
     * 窗={@code (-1928,-8,1936,1048)}、屏并集={@code (-1920,0,3968,1152)}。0.11.28 用的判据是
     * 「必须完整落在屏内」，真机上 100% 拒绝 ⇒ 静态底图档从未生效，一直走 0.11.27 的逐像素透明窗回退。</p>
     */
    static Rectangle clipToScreen(Rectangle rect, Rectangle screen) {
        if (rect == null || screen == null) {
            return null;
        }
        Rectangle clip = rect.intersection(screen);
        return (clip.width <= 0 || clip.height <= 0) ? null : clip;
    }

    /**
     * 抓宿主窗口区域的<b>静态底图</b>（0.11.28 外观的基石）；任一前置判据不满足就返回 {@code null}，
     * 由调用方回退（透明窗 → 带标题栏小窗）。
     *
     * <p>三条判据，缺一不可：①这块矩形不低于 {@link #HOST_MIN_W}×{@link #HOST_MIN_H}；
     * ②它完整落在<b>所有屏幕的并集</b>里（副屏是负坐标，按主屏判会误拒）；③宿主窗口此刻是
     * <b>活动窗</b>（否则可能抓到压在它上面的别的窗口 —— 那底图就是错的）。</p>
     *
     * <p>抓到的底图只用于这一次开窗的绘制，不进任何文件、不留存。</p>
     *
     * <p><b>0.11.39 起对同包开放</b>（原来是 {@code private captureHost}）：{@code ui/AccountStatusWindow}
     * 要铺一层<b>与登录窗同款</b>的遮罩底（用户 m00221 ①「当前账号窗口的背景要和登录弹窗一致」），
     * 底图必须抓得一模一样（同一块宿主矩形、同一份越界补底色逻辑）。{@code tag} = 日志归属
     * （登录窗传自己的 TAG，明细窗传 {@code ui.account}），免得两边的日志分不清是谁抓的。</p>
     */
    static BufferedImage captureBackdrop(String tag, Window host, Rectangle rect) {
        if (host == null || rect == null) {
            PluginLog.w(tag, "没有宿主窗口 / 宿主矩形 ⇒ 不抓底图");
            return null;
        }
        if (rect.width < HOST_MIN_W || rect.height < HOST_MIN_H) {
            PluginLog.w(tag, "宿主窗口太小（" + rect.width + "x" + rect.height + "）⇒ 不抓底图");
            return null;
        }
        Rectangle screen = virtualBounds();
        if (screen == null) {
            PluginLog.w(tag, "读不到虚拟屏范围 ⇒ 不抓底图");
            return null;
        }
        // 0.11.29（坑 40）：宿主 ComposeWindow 的 getLocationOnScreen+getSize 含 Windows 那圈
        // 不可见的 8 px 边框/阴影（真机实测窗=(-1928,-8,1936,1048)，屏并集=(-1920,0,3968,1152)）
        // ⇒ 「必须完整落在屏内」这条判据 100% 拒绝，0.11.28 的静态底图档在真机上从未生效过。
        // 正解 = 与屏幕并集求交后抓交集，越界部分按窗口坐标补底色，底图尺寸仍是窗口原尺寸（1:1 贴回）。
        Rectangle clip = clipToScreen(rect, screen);
        if (clip == null || clip.width < HOST_MIN_W || clip.height < HOST_MIN_H) {
            PluginLog.w(tag, "宿主窗口与屏幕并集几乎不相交（窗=" + rect + "，屏=" + screen
                    + "，交=" + clip + "）⇒ 不抓底图");
            return null;
        }
        try {
            if (!host.isActive()) {
                PluginLog.w(tag, "宿主窗口此刻不是活动窗（可能被别的窗口压住）⇒ 底图仍抓，但记录一笔");
            }
            BufferedImage grab = new Robot().createScreenCapture(clip);
            BufferedImage img = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(HostTheme.PAGE_BG);
            g.fillRect(0, 0, rect.width, rect.height);
            g.drawImage(grab, clip.x - rect.x, clip.y - rect.y, null);
            g.dispose();
            PluginLog.i(tag, "已抓到底图 " + img.getWidth() + "x" + img.getHeight()
                    + " @ " + rect.x + "," + rect.y + "（屏内 " + clip.width + "x" + clip.height
                    + (rect.width == clip.width && rect.height == clip.height ? ""
                    : "，越界补底色 " + (rect.width - clip.width) + "x" + (rect.height - clip.height))
                    + "）（静态底图档就绪）");
            return img;
        } catch (Throwable t) {
            PluginLog.w(tag, "抓底图失败 ⇒ 回退：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
            return null;
        }
    }

    /** 所有屏幕的并集（副屏在主屏左侧时是负坐标；Robot 的抓图矩形必须落在这个并集内）。 */
    private static Rectangle virtualBounds() {
        try {
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (devices == null || devices.length == 0) {
                return null;
            }
            Rectangle all = new Rectangle();
            for (GraphicsDevice gd : devices) {
                all = all.union(gd.getDefaultConfiguration().getBounds());
            }
            return all.width <= 0 || all.height <= 0 ? null : all;
        } catch (Throwable t) {
            PluginLog.w(TAG, "读虚拟屏范围失败：" + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 外观档的中文说明（真机日志与证据里读这一行就知道走的哪条路，别只写枚举名）。 */
    private static String lookNote(Look look, Rectangle rect, BufferedImage base) {
        switch (look) {
            case SNAPSHOT:
                // 0.11.39：窗本体逐像素透明、**不切角**（四角由底图里宿主自己的软边圆角负责），清底者
                // 仍是逐像素不透明的根面板。别再把这一档写成「圆角窗」——读日志的人会以为本窗自己画了角。
                return "静态底图 + 整块铺满（含宿主软边圆角，不切形；根面板不透明；宿主同款令牌 "
                        + HostTheme.family() + "；底图 "
                        + base.getWidth() + "x" + base.getHeight() + " @ " + rect.x + "," + rect.y + "）";
            case TRANSLUCENT:
                return "回退：逐像素透明窗（抓底图失败；宿主同款令牌 " + HostTheme.family() + "）";
            default:
                return "回退：带标题栏小窗（未取到宿主窗口或不支持逐像素透明 ⇒ 无遮罩）";
        }
    }

    /**
     * 根面板：快照档 = 不透明底 + 静态底图 + 60% 遮罩；透明档 = 只铺遮罩（0.11.27 回退）；
     * 降级档 = 就是窗口底色（无遮罩、带标题栏）。
     */
    private static JPanel buildRoot(Session box, Look look, BufferedImage base) {
        JPanel root = new RootPane(look, base);
        root.add(buildCard(box), new GridBagConstraints());
        return root;
    }

    /** 卡片：宿主模态窗的骨架（580 宽、圆角 16、1 px 边线、内边距 20/19/16）。 */
    private static JPanel buildCard(Session box) {
        JPanel card = new CardPane();
        card.setLayout(new java.awt.BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(
                HostTheme.CARD_PAD_TOP, HostTheme.CARD_PAD_X, HostTheme.CARD_PAD_BOTTOM, HostTheme.CARD_PAD_X));

        // 0.11.27 修正：列用 GridBagLayout + HORIZONTAL 填充。
        // 之前用 Y_AXIS BoxLayout，交叉轴按「最大尺寸」分配，实测把 540 宽的输入框、整行按钮压成 270 宽
        // ⇒ 行内第二个控件被裁掉（离线探针 DialogSkinProbe 抓到：量到 270 宽、整图找不到 #4CC2FF）。
        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        addRow(col, gc, row++, 0, fixed(label("登录网易云音乐", HostTheme.bold(HostTheme.TITLE_PX), HostTheme.TEXT),
                Integer.MAX_VALUE, HostTheme.TITLE_PX + 8));

        // 0.11.30 需求②：扫码 / 验证码两种登录方式做成**卡片内的分段切换**（默认扫码）—— 用户明确要求
        // 「扫码登录与验证码登录使用完全相同的样式，不允许再弹出目前这种独立窗口」。选中态 = 主色 + 白字。
        JPanel segs = row(HostTheme.BTN_H);
        segs.add(fixed(box.segQr, HostTheme.BTN_W, HostTheme.BTN_H));
        segs.add(Box.createRigidArea(new Dimension(HostTheme.BTN_GAP, 0)));
        segs.add(fixed(box.segSms, HostTheme.BTN_W, HostTheme.BTN_H));
        addRow(col, gc, row++, 14, fixed(segs, CONTENT_W, HostTheme.BTN_H));

        // 主体：三选一（扫码码 / 验证码两个框 / 风控验证码），高度随形态变（扫码/风控 260、验证码 78）。
        // 0.11.28：这里原来还有一段多行 HTML 说明（「填手机号→点获取验证码→…」）。用户报障后要求删掉
        // —— 两个框的占位文字已经说明该填什么。删它的副作用也是正面的：HTML 视图延迟布局会触发局部
        // 重绘，正是 0.11.27 双曝的放大器（坑 38）。
        addRow(col, gc, row++, 14, fixed(box.body, CONTENT_W, BODY_SMS_H));

        addRow(col, gc, row++, 10, fixed(box.msgLabel, CONTENT_W, HostTheme.LINE_H));

        JPanel btns = row(HostTheme.BTN_H);
        btns.add(fixed(box.cancelBtn, HostTheme.BTN_W, HostTheme.BTN_H));
        btns.add(Box.createRigidArea(new Dimension(HostTheme.BTN_GAP, 0)));
        btns.add(fixed(box.loginBtn, HostTheme.BTN_W, HostTheme.BTN_H));
        addRow(col, gc, row, 12, fixed(btns, CONTENT_W, HostTheme.BTN_H));

        card.add(col, java.awt.BorderLayout.CENTER);
        // 先把主体按当前档铺一次（离屏预览与真窗首帧都要有内容；真窗随后由 onShown() 切到扫码档）。
        box.showMode(box.mode);
        box.installHandlers(card);
        return card;
    }

    /** 自绘标签：画字之前先显式开灰度抗锯齿（0.11.27 的透明表面会让字形发虚）。 */
    private static final class SkinLabel extends JLabel {

        SkinLabel(String text) {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            HostTheme.textHints(g2);
            super.paintComponent(g);
        }
    }

    private static JLabel label(String text, java.awt.Font font, Color color) {
        JLabel l = new SkinLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    /** PNG 字节 → BufferedImage（解不出来返回 null：过期压黑需要留一张原图，没有就只显示文字状态行）。 */
    private static BufferedImage decodeImage(byte[] png) {
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 「已过期」的二维码画面（0.11.44，用户裁定：「到时间了就将二维码变黑并附上一层文字说当前
     * 二维码已过期请刷新二维码」）。
     *
     * <p>在**原图**上压一层近 80% 的黑（码必须真的扫不动，不能只是变暗）再写两行居中白字。
     * 只在窗口还开着、用户还停在扫码档时画一次（同一张码不重复压）；换新码即复位。</p>
     */
    private static BufferedImage expiredQrImage(BufferedImage base) {
        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(base, 0, 0, null);
            g.setColor(new Color(0, 0, 0, 200));
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.setFont(HostTheme.bold(11));
            FontMetrics fm = g.getFontMetrics();
            String l1 = "当前二维码已过期";
            String l2 = "请刷新二维码";
            int y = h / 2 - 2;
            g.drawString(l1, (w - fm.stringWidth(l1)) / 2, y);
            g.drawString(l2, (w - fm.stringWidth(l2)) / 2, y + fm.getHeight());
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * 横排行容器：X 轴 BoxLayout，宽度钉死 {@code CONTENT_W}。
     *
     * <p>行内控件各自钉死宽度，总和正好 = {@code CONTENT_W}（{@code 410+10+120} / {@code 260+20+260}），
     * 于是 BoxLayout 没有多余空间可分 ⇒ 每个控件拿到自己的精确宽度。</p>
     */
    private static JPanel row(int height) {
        Dimension size = new Dimension(CONTENT_W, height);
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setPreferredSize(size);
        p.setMinimumSize(size);
        p.setMaximumSize(size);
        return p;
    }

    /**
     * 风控档正文（0.11.33）：两行居中文字。
     *
     * <p>列容器一律 GridBagLayout（坑 36：{@code BoxLayout(Y_AXIS)} 的交叉轴只给半宽）。</p>
     */
    private static JPanel riskBody(SkinLabel title, SkinLabel hint) {
        title.setHorizontalAlignment(SwingConstants.CENTER);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        java.awt.Font titleFont = HostTheme.font(HostTheme.BODY_PX);
        java.awt.Font hintFont = HostTheme.font(HostTheme.SMALL_PX);
        title.setFont(titleFont);
        hint.setFont(hintFont);
        title.setForeground(HostTheme.TEXT);
        hint.setForeground(HostTheme.TEXT_DIM);
        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.CENTER;
        addRow(col, gc, 0, 0, fixed(title, CONTENT_W, HostTheme.BODY_PX + 9));
        addRow(col, gc, 1, 8, fixed(hint, CONTENT_W, HostTheme.SMALL_PX + 7));
        return col;
    }

    /** 往列里加一行：{@code gapTop} = 这一行<b>上方</b>的间距（宿主模态窗的行距就是这样量的）。 */
    private static void addRow(JPanel col, GridBagConstraints proto, int y, int gapTop, Component comp) {
        GridBagConstraints c = (GridBagConstraints) proto.clone();
        c.gridy = y;
        c.insets = new Insets(gapTop, 0, 0, 0);
        col.add(comp, c);
    }

    /** 交出的组件都钉死尺寸：BoxLayout 只按我们量出来的宿主尺寸摆（不随窗口变化）。 */
    private static <T extends Component> T fixed(T c, int w, int h) {
        Dimension size = new Dimension(w, h);
        c.setPreferredSize(size);
        c.setMinimumSize(size);
        c.setMaximumSize(size);
        return c;
    }

    // ---------------------------------------------------------------- 皮肤工厂（0.11.34，给 ui\VerifyWindow）

    /*
     * 内嵌验证窗必须与登录窗**长得一模一样**（用户 m02783 的硬要求），所以它不自己重画一套皮肤，
     * 而是复用这里的同一批零件。工厂方法只交出 JComponent，LoginDialog 里的私有嵌套类
     * （RootPane / CardPane / PillButton / SkinLabel）继续私有 —— 免得两个文件互相看见内部细节。
     */

    /** 与登录窗同形的根：有底图 ⇒ 快照档（底图 + 60% 遮罩）；没有 ⇒ 纯卡片色（降级小窗同款）。 */
    static JPanel backdropRoot(BufferedImage base) {
        return new RootPane(base != null ? Look.SNAPSHOT : Look.PLAIN, base);
    }

    /**
     * <b>把窗口切成圆角矩形</b>（{@code radius} = 圆角半径 px；底层 = Win32 的窗口区域）。
     *
     * <p><b>0.11.39 起只剩一个调用点</b>：{@code ui/AccountStatusWindow} 的<b>降级档</b>（取不到宿主窗口 ⇒
     * 小窗按内容 pack，外面没有底图可铺）。那一档必须切：窗小、卡片圆角（{@link HostTheme#CARD_RADIUS}）
     * 又比窗角大，不切就会在四角露出根面板底色。</p>
     *
     * <p><b>登录遮罩窗绝不调它</b>（0.11.37/0.11.38 的前车之鉴）：那种窗是「铺满宿主窗口的整块遮罩」，
     * 切形等于在宿主的 DWM 软边圆角上盖一道硬边 —— 用户报的「四角黑色的角」正是它。整段理由见本类
     * 「宿主窗口那圈圆角」注释。</p>
     *
     * <p>对等体（peer）没建起来时 {@code setShape} 会抛 {@code IllegalComponentStateException}：调用方
     * 都放在 {@code pack()} / {@code setBounds()} 之后，这里仍兜一层 —— 抛了就改在 {@code windowOpened}
     * 时补切（那一瞬窗口已可见，最坏差一帧）。</p>
     */
    static void roundCorners(Window w, int radius) {
        if (w == null) {
            return;
        }
        try {
            applyRoundShape(w, radius);
        } catch (IllegalComponentStateException notDisplayableYet) {
            w.addWindowListener(new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    try {
                        applyRoundShape(w, radius);
                    } catch (Throwable t) {
                        PluginLog.w(TAG, "窗口圆角补切失败（保持方形）：" + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, "窗口圆角切形失败（保持方形）：" + t.getClass().getSimpleName());
        }
    }

    private static void applyRoundShape(Window w, int radius) {
        int d = Math.max(2, radius) * 2;                        // RoundRectangle2D 的 arc 参数是「直径」
        w.setShape(new RoundRectangle2D.Double(0, 0, w.getWidth(), w.getHeight(), d, d));
    }

    /** 卡片本体（580 宽、圆角 16、1 px 边线；<b>没有投影</b>，见 {@link CardPane} 的说明）。 */
    static JPanel cardPane() {
        return new CardPane();
    }

    /** 自绘文字（画字前先显式开灰度抗锯齿，见 {@link SkinLabel}）。 */
    static JLabel text(String s, int px, Color color, boolean bold) {
        return label(s, bold ? HostTheme.bold(px) : HostTheme.font(px), color);
    }

    /** 药丸按钮（260×32；primary = 主色 + 白字）。 */
    static JButton pill(String s, boolean primary) {
        return new PillButton(s, primary, HostTheme.BTN_W, HostTheme.BTN_H);
    }

    /** 横排行容器（宽钉死 540，见 {@link #row(int)}）。 */
    static JPanel xrow(int height) {
        return row(height);
    }

    /** 钉死尺寸（见 {@link #fixed(Component, int, int)}）。 */
    static <T extends Component> T sized(T c, int w, int h) {
        return fixed(c, w, h);
    }

    /** 往列里加一行（见 {@link #addRow(JPanel, GridBagConstraints, int, int, Component)}）。 */
    static void stack(JPanel col, GridBagConstraints proto, int y, int gapTop, Component comp) {
        addRow(col, proto, y, gapTop, comp);
    }

    /** 所有屏幕的并集（副屏是负坐标；见 {@link #virtualBounds()}）。 */
    static Rectangle screenUnion() {
        return virtualBounds();
    }

    // ---------------------------------------------------------------- 内嵌验证窗的锚点与兜底（0.11.34）

    /** 登录窗现在的矩形（内嵌验证窗要**原样盖上去**）；登录窗不在 ⇒ 给一个 760×470 的兜底矩形。 */
    static Rectangle loginCover() {
        Session s = current;
        if (s != null && s.dialog != null && s.dialog.isDisplayable()) {
            return s.dialog.getBounds();
        }
        return new Rectangle(0, 0, 760, 470);
    }

    /** 登录窗那一刻的静态底图（内嵌验证窗复用；null = 平台抓不到 ⇒ 纯卡片色）。 */
    static BufferedImage loginBackdrop() {
        Session s = current;
        return s == null ? null : s.backdrop;
    }

    /**
     * 外部浏览器兜底（0.11.33 的三级链；0.11.34 起<b>只在 {@code ui.VerifyWindow} 内嵌起不来时</b>走）。
     *
     * <p>①本机 Edge / Chrome 的 {@code --app} 小窗（无地址栏、位置按登录窗居中后夹进屏幕）；
     * ②系统默认浏览器；③地址进剪贴板 + 只给文案。每级都留可 grep 日志（坑 40 教训）。</p>
     *
     * @return 真的把页面交出去了（或至少把地址塞进了剪贴板）⇒ true
     */
    static boolean openExternal(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        boolean opened = false;
        String exe = appBrowser();
        if (exe != null) {
            Rectangle win = verifyWindowBounds();
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.add("--app=" + url);
            cmd.add("--window-size=" + win.width + "," + win.height);
            cmd.add("--window-position=" + win.x + "," + win.y);
            opened = spawnBrowser(cmd);
            if (!opened) {
                PluginLog.w(TAG, "外部浏览器：app 小窗拉起失败，降级到系统默认浏览器");
            }
        } else {
            PluginLog.w(TAG, "外部浏览器：本机没找到 Edge/Chrome，直接用系统默认浏览器");
        }
        if (!opened) {
            opened = browseDefault(url);
        }
        if (opened) {
            PluginLog.i(TAG, "外部浏览器已打开验证页（降级路径；地址长度 " + url.length() + "，值不进日志）");
            return true;
        }
        boolean copied = copyToClipboard(url);
        PluginLog.w(TAG, "外部浏览器也打不开（剪贴板兜底=" + (copied ? "成功" : "失败") + "）");
        return copied;
    }

    /**
     * 宿主主窗口 = 当前显示着的、不是插件自己的、面积最大的那个窗口。
     *
     * <p>刻意<b>不做</b> {@code instanceof ComposeWindow} 判定：宿主是 Compose 应用，
     * 跨类加载器比较 Class 会炸；{@code isInstance} 只用来打一行日志说明是不是认得的那类宿主窗口。</p>
     */
    static Window findHostWindow() {
        Window best = null;
        long bestArea = 0L;
        for (Window w : Window.getWindows()) {
            if (w == null || !w.isShowing() || !w.isVisible() || !w.isDisplayable()) {
                continue;
            }
            if (w.getClass().getName().startsWith(OURS_PREFIX)) {
                continue;                       // 插件自己的窗（含本对话框）
            }
            int ww = Math.max(0, w.getWidth());
            int hh = Math.max(0, w.getHeight());
            if (ww < HOST_MIN_W || hh < HOST_MIN_H) {
                continue;
            }
            long area = (long) ww * (long) hh;
            if (area > bestArea) {
                bestArea = area;
                best = w;
            }
        }
        if (best != null) {
            boolean compose = false;
            try {
                compose = Class.forName("androidx.compose.ui.awt.ComposeWindow").isInstance(best);
            } catch (Throwable ignored) {
                // 宿主换渲染栈时不认它，只影响这行日志
            }
            PluginLog.d(TAG, "选中宿主窗口：" + best.getClass().getName()
                    + "（" + best.getWidth() + "x" + best.getHeight() + "，ComposeWindow=" + compose + "）");
        } else {
            PluginLog.w(TAG, "取不到宿主窗口（宿主可能全屏 / 换渲染栈）⇒ 登录框降级为不阻塞 + 置顶");
        }
        return best;
    }

    /** 一次性守护线程（跑完即消失；名字带 netease- 前缀，真机日志与线程残留检查都看得见）。 */
    private static void offThread(String name, Runnable job) {
        Thread t = new Thread(job, name);
        t.setDaemon(true);
        t.setContextClassLoader(LoginDialog.class.getClassLoader());
        t.start();
    }

    // ------------------------------------------------------------------ 电脑验证页（0.11.33）

    /**
     * 找一个能开 {@code --app} 小窗的浏览器（Edge 优先，其次 Chrome）。
     *
     * <p>只探标准安装位，不查注册表：查不到就降级用系统默认浏览器（{@link #browseDefault(String)}），
     * 两条路都失败再退到剪贴板 —— 每一级都留可 grep 日志（坑 40 教训）。</p>
     */
    private static String appBrowser() {
        String[] roots = {
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramFiles"),
                System.getenv("LOCALAPPDATA"),
        };
        String[] rels = {
                "Microsoft\\Edge\\Application\\msedge.exe",
                "Google\\Chrome\\Application\\chrome.exe",
        };
        for (String rel : rels) {
            for (String root : roots) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                File f = new File(root, rel);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** 拉起浏览器进程；输出全丢弃（不抢宿主控制台），失败只记一行日志并返回 false。 */
    private static boolean spawnBrowser(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "拉起浏览器失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    /** 系统默认浏览器兜底（{@code Desktop} 在无 AWT 桌面的环境不可用，故整段包在 try 里）。 */
    private static boolean browseDefault(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                PluginLog.w(TAG, "系统不支持 Desktop ⇒ 打不开默认浏览器");
                return false;
            }
            Desktop d = Desktop.getDesktop();
            if (!d.isSupported(Desktop.Action.BROWSE)) {
                PluginLog.w(TAG, "系统不支持 BROWSE 动作 ⇒ 打不开默认浏览器");
                return false;
            }
            d.browse(new URI(url));
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "默认浏览器打开失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    /** 最末一级兜底：地址进剪贴板让用户自己粘（日志里只记长度，不记地址本身）。 */
    private static boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "复制到剪贴板失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 验证小窗该放哪：以登录窗中心为锚、夹进屏幕（用户裁定「验证的窗口要覆盖到这个登录窗口上面」）。
     *
     * <p>结果直接喂给浏览器 {@code --window-size} / {@code --window-position}。</p>
     */
    private static Rectangle verifyWindowBounds() {
        Rectangle screen = virtualBounds();
        if (screen == null || screen.width <= 0 || screen.height <= 0) {
            screen = new Rectangle(0, 0, 1280, 900);
        }
        int w = Math.min(880, Math.max(480, screen.width - 80));
        int h = Math.min(720, Math.max(420, screen.height - 80));
        Session box = current;
        Rectangle anchor = null;
        if (box != null && box.dialog != null && box.dialog.isShowing()) {
            anchor = box.dialog.getBounds();
        }
        int x = anchor != null ? anchor.x + (anchor.width - w) / 2 : screen.x + (screen.width - w) / 2;
        int y = anchor != null ? anchor.y + (anchor.height - h) / 2 : screen.y + (screen.height - h) / 2;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - w));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - h));
        return new Rectangle(x, y, w, h);
    }

    // ------------------------------------------------------------------ 皮肤零件（宿主同款）

    /**
     * 根面板：快照档（默认）= 静态底图 + 60% 遮罩；透明档 = 只铺遮罩（0.11.27 回退档）；
     * 降级档 = 窗口底色（无遮罩）。
     *
     * <p><b>「铺满整块矩形、四角不切」（0.11.39，修用户 m00221 ③/④「四角还是有黑色的角」）</b>：
     * 本面板从 {@code (0,0)} 一直画到 {@code (w,h)}，包括四个角 —— 角上画的就是底图里<b>宿主自己那个
     * DWM 软边圆角</b>（外加角外桌面的原像素）压 60% 遮罩后的样子。0.11.38 曾是「硬 clip 到半径 8 的
     * 圆角矩形、角上一个像素都不上色」：那条路把 DWM 的软边换成了硬边，实机量下来切边两侧差一档
     * （外侧 43 / 内侧 19）⇒ 四角各留一道硬黑边，正是用户报的那个。整段量测理由见本类
     * 「宿主窗口那圈圆角」注释。</p>
     *
     * <p><b>铁律（坑 38 的正解，别改回去）</b>：非透明档必须 {@code setOpaque(true)}，并且
     * 清底要<b>在画底图之前</b>做 —— 不透明根是整条组件链上唯一的「清底者」（Swing 从任意子组件往上
     * 找到的第一个不透明祖先就是它）。少了这一步，任何局部重绘都会把新画面叠在旧画面上：文字重影、
     * 焦点残影、点两个框出现渲染位移。清底 = 用底色填满整块矩形（0.11.39 起不再有任何裁剪，
     * 覆盖范围就是整窗 ⇒ 这条铁律只会更稳）。</p>
     */
    private static final class RootPane extends JPanel {

        private final Look look;
        private final BufferedImage base;            // 快照档才有

        RootPane(Look look, BufferedImage base) {
            super(new GridBagLayout());
            this.look = look;
            this.base = base;
            setOpaque(look != Look.TRANSLUCENT);
            setBackground(HostTheme.PAGE_BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);                 // 先清底（不透明根；铁律，见类注释）—— 整块矩形，不裁剪
            if (look == Look.PLAIN) {
                return;                              // 降级档：窗口底色就是全部（卡片压在它上面）
            }
            if (look == Look.SNAPSHOT && base != null) {
                g.drawImage(base, 0, 0, null);       // 静态底图 = 宿主窗口的定格（含宿主自己的四角软边）
            }
            g.setColor(HostTheme.scrim());           // 纯黑 60% 遮罩（宿主模态窗实测 ×0.40）
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /** 卡片本体（宿主模态窗实测：580 宽 / 圆角 16 / 1 px 边线 #2F3030 / <b>没有投影</b>）。 */
    private static final class CardPane extends JPanel {

        CardPane() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width = HostTheme.CARD_W;                 // 宽度钉死 580（宿主实测），高度随内容
            return d;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = HostTheme.CARD_RADIUS * 2;
            // 0.11.39：这里原来有 6 层「黑 4.5%」的近似高斯投影。用户 m00221 ④ 报的「四角有黑色的角」
            // 一半来自它 —— 宿主自己的模态卡片**没有投影**（真机逐像素量取：{@code build\diag\p323-config.png}
            // 里卡片左边缘外是干净的遮罩 17/18，紧挨 1 px 边线 47 就是卡片填充，中间没有任何暗环），
            // 而叠加出来的投影恰好在四个角上最厚（6 层都是 (-i, -i+2) 的圆角矩形，角部层层堆叠）⇒
            // 卡片四角各挂一团比遮罩还暗的（18 → 13）。删掉即与宿主同形：遮罩 → 1 px 边线 → 卡片色。
            g2.setColor(HostTheme.CARD_BG);
            g2.fillRoundRect(0, 0, w - 1, h - 1, r, r);
            g2.setColor(HostTheme.CARD_EDGE);
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 药丸按钮：宿主按钮实测 260×32、圆角 = 半高；主要按钮 #4CC2FF + 纯白，次要 #232323 + #B2B5B9。 */
    private static final class PillButton extends JButton {

        private final boolean primary;
        /** 分段按钮的选中态（0.11.30）：选中 = 主色 + 白字。 */
        private boolean selected;

        PillButton(String text, boolean primary, int w, int h) {
            super(text);
            this.primary = primary;
            setFont(HostTheme.button());
            setForeground(primary ? HostTheme.BTN1_TEXT : HostTheme.TEXT_DIM);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setMargin(new Insets(0, 0, 0, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        /** 分段切换的选中态（0.11.30 需求②）：选中 = 主色底 + 白字，未选中 = 次色底 + 次字色。 */
        void setSelectedState(boolean on) {
            if (selected == on) {
                return;
            }
            selected = on;
            setForeground((primary || on) ? HostTheme.BTN1_TEXT : HostTheme.TEXT_DIM);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel m = getModel();
            Color bg = (primary || selected) ? HostTheme.ACCENT : HostTheme.BTN2_BG;
            if (!isEnabled()) {
                bg = HostTheme.mix(bg, HostTheme.CARD_BG, 0.62f);
            } else if (m.isPressed()) {
                bg = HostTheme.shade(bg, primary ? -0.14f : 0.16f);
            } else if (m.isRollover()) {
                bg = HostTheme.shade(bg, primary ? 0.10f : 0.22f);
            }
            int r = Math.min(getHeight(), Math.min(getWidth(), getHeight()));
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
            if (isFocusOwner() && isEnabled()) {
                g2.setColor(new Color(0xFF, 0xFF, 0xFF, 0x44));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, r, r);
            }
            g2.dispose();
            HostTheme.textHints((Graphics2D) g);        // 文字由 UI 代理在 g 上画 ⇒ 提示要设给 g
            super.paintComponent(g);
        }
    }

    /** 输入框：宿主实测 35 高、底色 #2A2A2A、未聚焦边线 #3A3A3A、聚焦描边 #4CC2FF、圆角 10。 */
    private static final class FlatField extends JTextField {

        private final String placeholder;

        FlatField(String placeholder, int w, int h) {
            this.placeholder = placeholder == null ? "" : placeholder;
            setOpaque(false);
            setFont(HostTheme.font(HostTheme.BODY_PX));
            setForeground(HostTheme.TEXT);
            setCaretColor(HostTheme.ACCENT);
            setSelectionColor(new Color(0x4C, 0xC2, 0xFF, 0x66));
            setSelectedTextColor(HostTheme.TEXT);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = HostTheme.FIELD_RADIUS * 2;
            g2.setColor(isEnabled() ? HostTheme.FIELD_BG : HostTheme.mix(HostTheme.FIELD_BG, HostTheme.CARD_BG, 0.5f));
            g2.fillRoundRect(0, 0, w - 1, h - 1, r, r);
            g2.setColor(isFocusOwner() && isEnabled() ? HostTheme.ACCENT : HostTheme.FIELD_EDGE);
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);
            g2.dispose();
            HostTheme.textHints((Graphics2D) g);        // 已输入的号码/验证码也走灰度抗锯齿
            super.paintComponent(g);
            if (getText() == null || getText().isEmpty()) {
                Graphics2D g3 = (Graphics2D) g.create();
                HostTheme.textHints(g3);
                g3.setColor(HostTheme.PLACEHOLDER);
                g3.setFont(getFont());
                FontMetrics fm = g3.getFontMetrics();
                Insets in = getInsets();
                g3.drawString(placeholder, in.left, (h - fm.getHeight()) / 2 + fm.getAscent());
                g3.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ 一次开窗的生命周期

    private static final class Session {

        final JDialog dialog;                                   // 离屏预览时为 null
        /**
         * 开窗那一刻抓到的宿主静态底图（0.11.34）。
         *
         * <p>内嵌验证窗要铺**同一张**底图 + 同一层遮罩，才能做到「与软件样式一致」；重新抓屏会抓到
         * 已经压在上面的登录窗自己。</p>
         */
        BufferedImage backdrop;
        final FlatField phoneField = new FlatField("手机号", CONTENT_W, HostTheme.FIELD_H);
        final FlatField codeField = new FlatField("验证码", CODE_W, HostTheme.FIELD_H);
        final PillButton sendBtn = new PillButton("获取验证码", false, HostTheme.SEND_W, HostTheme.FIELD_H);
        final PillButton cancelBtn = new PillButton("取消", false, HostTheme.BTN_W, HostTheme.BTN_H);
        final PillButton loginBtn = new PillButton("登录", true, HostTheme.BTN_W, HostTheme.BTN_H);
        final SkinLabel msgLabel = new SkinLabel(" ");
        /** 分段切换（0.11.30 需求②）：扫码 / 验证码登录，默认扫码。 */
        final PillButton segQr = new PillButton("扫码", false, HostTheme.BTN_W, HostTheme.BTN_H);
        final PillButton segSms = new PillButton("验证码登录", false, HostTheme.BTN_W, HostTheme.BTN_H);
        /** 主体容器：三选一（扫码码 / 验证码两框 / 风控码）。 */
        final JPanel body = new JPanel(new GridBagLayout());
        /** 二维码画布（只有字/图标，没有交互）。 */
        final SkinLabel qrLabel = new SkinLabel("");
        /** 风控档正文首行（0.11.33：验证要在电脑上完成，窗内不再出现手机扫码的码）。 */
        final SkinLabel riskTitle = new SkinLabel("请在验证窗口完成安全验证");
        /** 风控档正文次行（灰、小字，只说明验证页在哪打开）。 */
        final SkinLabel riskHint = new SkinLabel("验证窗口会盖在本窗上");
        /** 风控档正文容器（两行文字居中；见坑 36：列容器一律 GridBagLayout）。 */
        final JPanel riskPane = riskBody(riskTitle, riskHint);

        Timer heartbeat;
        Timer cooldown;
        /** 扫码状态轮询（只有扫码档在跑）。 */
        Timer qrTicker;
        int heartbeatTicks;
        int cooldownLeft;
        boolean alive = true;
        boolean busy;
        /** 当前主体形态（0.11.30）。 */
        Mode mode = Mode.SMS;
        /** 已显示的二维码 key（变了才换图，避免每秒重设图标）。 */
        String qrKey = "";
        /** 展示中的**原图**（0.11.44：过期时要在它上面压一层黑 + 文案，原图必须留着）。 */
        BufferedImage qrBaseImage;
        /** 当前图标是不是「已过期」变体（换一张新码时复位；同一张码只压一次黑）。 */
        boolean qrExpiredShown;
        /** 风控档要交给电脑完成的行为验证地址（0.11.33；空 = 伺服端没给地址）。 */
        String riskUrl = "";
        /**
         * 这个风控档是**扫码**引来的（0.11.32 需求①）。
         *
         * <p>两种风控来源的主按钮语义不同：验证码登录引来的要「重发/重登」，扫码引来的则是
         * 「用户已在手机上完成验证 ⇒ 解冻地址、立刻重探」。用这个标志区分。</p>
         */
        boolean riskFromQr;
        /**
         * 已经为哪一份验证地址开过浏览器（0.11.33）。
         *
         * <p>地址为空 / 与当前 {@link #riskUrl} 不同 ⇒ 主按钮是「打开验证页」，且自动开窗一次；
         * 相同 ⇒ 主按钮变成「我已完成，重试」。伺服端每次答复都换 event_id/sign，所以只对
         * <b>同一份</b>地址去重 —— 用户点了「重试」拿到新地址时该再开一次新页。</p>
         */
        String openedForUrl = "";
        /** 上次**强制**取码时刻（{@link #QR_MIN_GAP_MS} 去重：手动刷新双击不重复投递；复用路径不需要它）。 */
        long lastQrAtMs;
        /** 卡片根（形态切换后要 revalidate/repaint 的那一层）。 */
        JPanel cardRoot;
        /** 状态行上的可点入口（0.11.29；null = 普通文字）。 */
        private Runnable msgAction;

        Session(JDialog dialog, String prefill) {
            this.dialog = dialog;
            msgLabel.setFont(HostTheme.font(HostTheme.SMALL_PX));
            msgLabel.setForeground(HostTheme.TEXT_DIM);
            body.setOpaque(false);
            qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
            qrLabel.setVerticalAlignment(SwingConstants.CENTER);
            // 0.11.29：状态行可以被「武装」成一个入口（风控拦住验证码登录时 → 打个出口）；
            // 平时鼠标是箭头、点了不做事。
            msgLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (msgAction != null) {
                        msgAction.run();
                    }
                }
            });
            if (prefill != null && !prefill.isEmpty()) {
                phoneField.setText(prefill);
                phoneField.setCaretPosition(prefill.length());
            }
        }

        void msg(String text, Color color) {
            msgLabel.setText(text == null || text.isEmpty() ? " " : text);
            msgLabel.setForeground(color);
            armMsg(null);            // 换文案就解除上一枚入口，免得旧入口挂在新文案上
        }

        /**
         * 把状态行武装成一个可点入口（{@code null} = 解除）。只给「有明确下一步」的场景用 ——
         * 例如验证码登录被风控拦住又没取到验证地址时，点这行**窗内**改用扫码（0.11.30）。
         */
        void armMsg(Runnable action) {
            armMsg(action, "点这里改用扫码登录");
        }

        /** 同上，但自定义悬停提示（0.11.33：风控档的状态行是「重新打开验证页」）。 */
        void armMsg(Runnable action, String tip) {
            msgAction = action;
            msgLabel.setCursor(action == null
                    ? Cursor.getDefaultCursor()
                    : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            msgLabel.setToolTipText(action == null ? null : tip);
        }

        // ------------------------------------------------------- 0.11.30：分段切换与三种主体

        /** 接线（建树时在 EDT 上调用一次）。 */
        void installHandlers(JPanel card) {
            cardRoot = card;
            sendBtn.addActionListener(e -> onSendCode());
            loginBtn.addActionListener(e -> onPrimary());
            segQr.addActionListener(e -> switchTo(Mode.QR));
            segSms.addActionListener(e -> switchTo(Mode.SMS));
            cancelBtn.addActionListener(e -> {
                wipe();
                if (dialog != null) {
                    dialog.dispose();
                }
            });
            // 号码框里按 Enter ⇒ 跳到验证码框（不要直接登录：那时码还没填）
            phoneField.addActionListener(e -> codeField.requestFocusInWindow());
            cancelBtn.setFocusable(false);      // 宿主的次要按钮不吃焦点（避免默认按钮没有视觉焦点）
            sendBtn.setFocusable(false);
            segQr.setFocusable(false);
            segSms.setFocusable(false);
            // 0.11.28：焦点变化 ⇒ 整张卡片重绘一次。输入框换焦点只改自己那一小块（聚焦描边变 #4CC2FF、
            // 占位文字显隐），Swing 默认就只重绘那一小块；整卡重绘把「焦点切换」变成一次干净的覆盖，
            // 用户报的「点第一个框再点第二个框出现渲染位移」就是这么消掉的。
            FocusListener wholeCard = new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    if (cardRoot != null) {
                        cardRoot.repaint();
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    if (cardRoot != null) {
                        cardRoot.repaint();
                    }
                }
            };
            phoneField.addFocusListener(wholeCard);
            codeField.addFocusListener(wholeCard);
        }

        /** 窗口显示之后走这里：默认进扫码档（0.11.30 需求②：默认扫码；离屏预览不调它 ⇒ 不碰网络）。 */
        void onShown() {
            // 0.11.33：这里不再补一次 openQr() —— switchTo(QR, true) 内部已经取过一次码，
            // 真机日志里 16:52:25 同一秒两行「取二维码」（两个 unikey）就是这么来的。
            switchTo(Mode.QR, true);
        }

        /**
         * 主按钮按档分工：扫码档 = 刷新二维码；风控档 = 打开验证窗 / 我已完成重试；其它 = 登录。
         *
         * <p>风控档两段式（0.11.33 定形，0.11.34 沿用）：验证面还没交出去 ⇒ 「打开验证窗」；交出去了 ⇒
         * 扫码来源重新探状态（{@link #riskRetry()}），验证码来源重提一次登录（{@link #onLogin()}）。</p>
         */
        void onPrimary() {
            if (mode == Mode.QR) {
                openQr(true);           // 「刷新二维码」= 用户要一张新码（0.11.44：只有这里强制重取）
            } else if (mode == Mode.RISK) {
                if (!verifyOpened()) {
                    openVerifyPage();
                } else if (riskFromQr) {
                    riskRetry();
                } else {
                    onLogin();
                }
            } else {
                onLogin();
            }
        }

        String primaryLabel() {
            switch (mode) {
                case QR:
                    return "刷新二维码";
                case RISK:
                    return verifyOpened() ? "我已完成，重试" : "打开验证窗";
                default:
                    return "登录";
            }
        }

        /**
         * 这份验证地址是否已经把验证面交出去了（0.11.33 起算「开过窗」；0.11.34 起内嵌窗也算）。
         *
         * <p>内嵌验证窗还开着 ⇒ 一定算；窗被用户关掉后靠 {@link #openedForUrl} 记住「交过了」，
         * 免得主按钮又退回「打开验证窗」。</p>
         */
        boolean verifyOpened() {
            return !riskUrl.isEmpty() && (VerifyWindow.isOpenFor(riskUrl) || riskUrl.equals(openedForUrl));
        }

        /** 切档（用户点分段）：扫码档顺手把码取回来，验证码档停掉轮询。 */
        void switchTo(Mode want) {
            switchTo(want, false);
        }

        private void switchTo(Mode want, boolean force) {
            if (mode == want && !force) {
                return;
            }
            PluginLog.i(TAG, "对话框：切到" + (want == Mode.SMS ? "验证码" : want == Mode.RISK ? "风控验证" : "扫码") + "档");
            showMode(want);
            if (want == Mode.QR) {
                openQr();
            } else {
                if (qrTicker != null) {
                    qrTicker.stop();
                }
                // 0.11.44：离开扫码档时把「已显示的是哪张码」一并清掉。复用了同一把 key 之后
                // tickQr 的「key 变了才换图」不会再触发，不清这里就再也补不回图标（图标刚被抹掉）。
                qrKey = "";
                qrBaseImage = null;
                qrExpiredShown = false;
                qrLabel.setText("");
                qrLabel.setIcon(null);
                qrLabel.repaint();
                if (want == Mode.SMS) {
                    // 0.11.32（用户②）：切到验证码档必须把状态行**清干净**。以前只换主体，
                    // 扫码档留下的「用网易云音乐 App 扫一下这个码」会跟着跑到验证码档里。
                    msg(" ", null);
                    armMsg(null);
                    riskFromQr = false;
                    phoneField.requestFocusInWindow();
                }
            }
        }

        /** 把主体换成指定形态，并把整卡重排（验证码 78 / 扫码 260 / 风控 118）。 */
        void showMode(Mode want) {
            mode = want;
            if (want != Mode.RISK) {
                VerifyWindow.closeIfOpen();     // 0.11.34：离开风控档 ⇒ 内嵌验证窗跟着收
            }
            body.removeAll();
            if (want == Mode.SMS) {
                JPanel col = new JPanel(new GridBagLayout());
                col.setOpaque(false);
                GridBagConstraints gc = new GridBagConstraints();
                gc.gridx = 0;
                gc.weightx = 1.0;
                gc.fill = GridBagConstraints.HORIZONTAL;
                gc.anchor = GridBagConstraints.NORTHWEST;
                addRow(col, gc, 0, 0, fixed(phoneField, CONTENT_W, HostTheme.FIELD_H));
                JPanel codeRow = row(HostTheme.FIELD_H);
                codeRow.add(fixed(codeField, CODE_W, HostTheme.FIELD_H));
                codeRow.add(Box.createRigidArea(new Dimension(HostTheme.SEND_GAP, 0)));
                codeRow.add(fixed(sendBtn, HostTheme.SEND_W, HostTheme.FIELD_H));
                addRow(col, gc, 1, 8, fixed(codeRow, CONTENT_W, HostTheme.FIELD_H));
                body.add(col);
            } else if (want == Mode.RISK) {
                // 0.11.33：风控档只剩两行文字 + 卡片底部那个「打开验证页」——
                // 安全验证在**电脑上**做（用户裁定），窗内不再画手机扫的码。
                body.add(fixed(riskPane, CONTENT_W, BODY_RISK_H));
            } else {
                // 0.11.44：复用路径重排时不许把「正在获取二维码…」重新糊到已有的码上
                //（JLabel 同时有图标与文字时两者会挤在一行，码会被推走）。
                if (qrLabel.getIcon() == null) {
                    qrLabel.setText("正在获取二维码…");
                }
                body.add(fixed(qrLabel, QR_EDGE, QR_EDGE));
            }
            fixed(body, CONTENT_W, want == Mode.SMS ? BODY_SMS_H
                    : want == Mode.RISK ? BODY_RISK_H : BODY_QR_H);
            // 0.11.33：分段枚按**风控来源**点亮。以前 RISK 一律点亮「验证码登录」，于是扫码引来的
            // 风控看上去像「自己跳到了验证码登录」（用户真机报障 2026-10-02 的那张图）。
            boolean qrSide = (want == Mode.QR) || (want == Mode.RISK && riskFromQr);
            segQr.setSelectedState(qrSide);
            segSms.setSelectedState(!qrSide);
            loginBtn.setText(primaryLabel());
            body.revalidate();
            body.repaint();
            if (cardRoot != null) {
                cardRoot.revalidate();
                cardRoot.repaint();
            }
        }

        /** 取码 + 开轮询（开窗 / 切回扫码档）：能复用就复用，只有过期或用户手动刷新才重新取码（0.11.44）。 */
        void openQr() {
            openQr(false);
        }

        /**
         * 取码 + 开轮询（0.11.44）。
         *
         * <p>用户裁定：「我切换一下验证码登录不要刷新二维码」「点取消登录也不要直接刷新二维码」，
         * 只有网易云说码过期了、或用户点「刷新二维码」才换新码。所以这里先问
         * {@link AccountService#startQr(boolean)}：复用 ⇒ 一个请求都不发、不擦已有展示；
         * 只有它真的投递了新取码任务，才把展示复位成「正在获取二维码…」。</p>
         *
         * @param force true = 用户点「刷新二维码」：强制向网易云重新取码
         */
        void openQr(boolean force) {
            long nowMs = System.currentTimeMillis();
            if (force && nowMs - lastQrAtMs < QR_MIN_GAP_MS) {
                // 0.11.33：同一秒内的重复取码只认第一次（真机 16:52:25 一次开窗建了两个 unikey）。
                PluginLog.d(TAG, "对话框：忽略重复的强制取码（距上次 " + (nowMs - lastQrAtMs) + "ms）");
                return;
            }
            if (force) {
                lastQrAtMs = nowMs;
            }
            boolean startedNew;
            try {
                startedNew = AccountService.startQr(force);
            } catch (Throwable t) {
                PluginLog.w(TAG, "取二维码失败：" + t.getClass().getSimpleName());
                msg("二维码取不到，点「刷新二维码」重试", HostTheme.ERR);
                return;
            }
            if (startedNew) {
                openedForUrl = "";          // 新一轮扫码会话：验证页入口复位
                riskUrl = "";
                PluginLog.i(TAG, "对话框：向网易云取新二维码（旧码作废，来源="
                        + (force ? "手动刷新" : "本地码已过期/不存在") + "）");
                qrKey = "";
                qrBaseImage = null;
                qrExpiredShown = false;
                qrLabel.setIcon(null);
                qrLabel.setText("正在获取二维码…");
                qrLabel.repaint();
                msg("正在获取二维码…", HostTheme.TEXT_DIM);
            } else {
                PluginLog.i(TAG, "对话框：复用本地未过期二维码（不重新取码）");
            }
            if (qrTicker == null) {
                qrTicker = new Timer(QR_TICK_MS, e -> tickQr());
            }
            qrTicker.restart();
            if (!startedNew) {
                tickQr();                   // 复用：立刻按快照同步一次，别等 1 秒后的第一拍
            }
        }

        /** 每秒看一眼扫码状态（读 AccountService 的快照：不联网、不阻塞、绝不抛）。 */
        void tickQr() {
            if (!alive) {
                return;
            }
            AccountService.LoginStatus st;
            try {
                st = AccountService.status();
            } catch (Throwable t) {
                return;
            }
            if (st == null) {
                return;
            }
            // ① 风控迁移（0.11.32 需求①）：先看有没有「被要求完成行为验证」。
            //    这一段必须排在「只服务扫码档」的早退之前 —— 否则一旦切进风控形态就再也出不来。
            boolean risk = false;
            String riskAddr = "";
            try {
                risk = AccountService.qrRiskRequired();
                riskAddr = AccountService.qrVerifyUrl();
            } catch (Throwable ignored) {
                // 读快照失败当成没有风控
            }
            if (risk && !riskAddr.isBlank()) {
                if (mode == Mode.QR || riskFromQr) {
                    showRiskFromQr(riskAddr);
                    return;                  // 风控期窗内那张码就是「当前该扫的码」，不再画扫码码
                }
            } else if (riskFromQr) {
                backToQrFromRisk();          // 风控已放行 ⇒ 收回验证码、回扫码档
            }
            if (mode != Mode.QR) {
                return;
            }
            try {
                Dto.QrSession qr = AccountService.qrImage();
                if (qr != null && qr.pngBytes() != null && qr.pngBytes().length > 0) {
                    String key = qr.key() == null ? "" : qr.key();
                    if (!key.equals(qrKey)) {
                        qrKey = key;
                        qrBaseImage = decodeImage(qr.pngBytes());
                        qrExpiredShown = false;
                        qrLabel.setText("");
                        qrLabel.setIcon(new ImageIcon(qr.pngBytes()));
                        qrLabel.repaint();
                    }
                }
            } catch (Throwable ignored) {
                // 取不到图不影响状态轮询
            }
            switch (st.state()) {
                case LOGGED_IN:
                    if (AccountService.wasLoggedInAtOpen()) {
                        // 0.11.44（真机 21:26:33：用户没碰任何按键，窗自己关了）：这是「扫码前本来就
                        // 已登录」的状态被恢复回来，不是本次扫码的结果 —— 绝不自动关窗。
                        msg("当前账号已登录；要换号请点「刷新二维码」重新扫码", HostTheme.OK);
                    } else {
                        onLoggedIn("扫码");
                    }
                    break;
                case WAITING_CONFIRM:
                    msg("已扫码，请在手机上确认", HostTheme.TEXT_DIM);
                    break;
                case EXPIRED:
                    msg("二维码已过期，点「刷新二维码」", HostTheme.ERR);
                    // 0.11.44（用户：「到时间了就将二维码变黑并附上一层文字说当前二维码已过期请刷新二维码」）：
                    // 只在窗口还开着、用户还停在这一屏时压黑；关窗重开会走「重新取码」，不会看到这一态。
                    if (qrBaseImage != null && !qrExpiredShown) {
                        qrExpiredShown = true;
                        qrLabel.setText("");
                        qrLabel.setIcon(new ImageIcon(expiredQrImage(qrBaseImage)));
                        qrLabel.repaint();
                    }
                    break;
                case ERROR:
                    msg(st.message() == null || st.message().isBlank() ? "扫码异常，点「刷新二维码」重试" : st.message(),
                            HostTheme.ERR);
                    break;
                case WAITING_SCAN:
                    if (risk) {
                        // 风控态但伺服端没给地址：如实说，别让用户对着旧码干等（0.11.32 需求①）
                        msg(st.message() == null || st.message().isBlank()
                                ? "网易云风控拦截了本次扫码登录" : st.message(), HostTheme.ERR);
                    } else if (qrBaseImage == null) {
                        msg("正在获取二维码…", HostTheme.TEXT_DIM);   // 码还没到：别催用户去扫空气
                    } else {
                        msg("用网易云音乐 App 扫一下这个码", HostTheme.TEXT_DIM);
                    }
                    break;
                default:
                    break;      // NONE：还在等伺服端回包，维持「正在获取二维码…」
            }
        }

        /**
         * 扫码被风控拦下（0.11.32 需求①；0.11.33 改成在电脑上完成；0.11.34 改成**内嵌**在本软件里）。
         *
         * <p>窗内只给两行说明 + 一个兜底入口：真正的验证在一张与登录窗同形的**内嵌验证窗**里完成
         * （{@code ui.VerifyWindow}，整窗盖在本对话框上），完成后再点「我已完成，重试」，由每秒 tick
         * 接回轮询结果。手机扫码验证、外部浏览器验证都已被用户否掉（后者只作为内嵌起不来时的兜底）。</p>
         *
         * <p>幂等：{@code AccountService} 侧地址是冻结的，同一份地址重复进来只刷文案、不重开窗。</p>
         */
        void showRiskFromQr(String url) {
            if (mode != Mode.RISK || !riskFromQr) {
                riskFromQr = true;
                showMode(Mode.RISK);
                PluginLog.i(TAG, "对话框：扫码被风控 ⇒ 风控档（内嵌验证；地址长度 " + url.length()
                        + "，值不进日志）");
            }
            if (!url.equals(riskUrl)) {
                riskUrl = url;
                PluginLog.i(TAG, "对话框：换了一份行为验证地址（长度 " + url.length() + "，值不进日志）");
            }
            riskTitle.setText("请在验证窗口完成安全验证");
            riskHint.setText(verifyOpened() ? "验证窗口已盖在本窗上" : "验证窗口会盖在本窗上");
            loginBtn.setText(primaryLabel());
            msg("完成后回本窗点「重试」", HostTheme.ERR);
            armMsg(this::openVerifyPage, "重新打开验证窗");
            autoOpenVerifyPage();
        }

        /** 进风控档时自动把内嵌验证窗调出来（用户裁定：验证窗口要盖在登录窗之上）；离屏预览不弹。 */
        private void autoOpenVerifyPage() {
            if (verifyOpened() || riskUrl.isEmpty()) {
                return;
            }
            if (dialog == null || !dialog.isShowing()) {
                return;                 // 探针 / 离屏预览绝不能弹窗
            }
            PluginLog.i(TAG, "对话框：风控档首次出现 ⇒ 自动打开内嵌验证窗");
            openVerifyPage();
        }

        /**
         * 打开验证面（0.11.34：**内嵌**在本软件里的一张卡上，整窗覆盖本对话框）。
         *
         * <p>用户 m02783 的裁定：不允许再把验证丢给外部网站。内嵌由 {@code ui.VerifyWindow} 负责
         * （WebView2 子窗口 + 与登录窗同一套皮肤：同一张静态底图、同一层遮罩、同一张 580 宽的卡），
         * 它自己接管「内嵌起不来 ⇒ 外部浏览器 ⇒ 地址进剪贴板」的降级链，每级都留可 grep 日志。</p>
         *
         * <p>离屏预览（{@code dialog == null} 或没显示）绝不弹窗 —— 探针与 {@code buildPreview} 会走这里。</p>
         */
        void openVerifyPage() {
            if (riskUrl.isEmpty()) {
                msg("没拿到验证地址，稍后重试", HostTheme.ERR);
                return;
            }
            if (dialog == null || !dialog.isShowing()) {
                PluginLog.d(TAG, "对话框：离屏预览 ⇒ 不打开验证窗");
                return;
            }
            openedForUrl = riskUrl;
            loginBtn.setText(primaryLabel());
            PluginLog.i(TAG, "对话框：把行为验证交给内嵌验证窗（来源=" + (riskFromQr ? "扫码" : "验证码登录")
                    + "，地址长度 " + riskUrl.length() + "，值不进日志）");
            VerifyWindow.open(dialog, riskUrl, dialog.getBounds(), backdrop,
                    riskFromQr ? "扫码" : "验证码登录", this::riskRetry);
        }

        /** 风控档「我已完成，重试」（扫码来源）：解冻地址 + 立刻重探一次，由每秒 tick 接回结果。 */
        void riskRetry() {
            PluginLog.i(TAG, "对话框：用户点「我已完成，重试」（扫码风控）⇒ 立刻重探扫码状态");
            msg("正在确认验证结果…", HostTheme.TEXT_DIM);
            try {
                AccountService.retryQrAfterRisk();
            } catch (Throwable t) {
                PluginLog.w(TAG, "风控重试失败：" + t.getClass().getSimpleName());
            }
            if (qrTicker == null) {
                qrTicker = new Timer(QR_TICK_MS, e -> tickQr());
            }
            qrTicker.restart();
        }

        /** 风控放行：收回窗内的验证码，回到扫码档（图标强制重设，免得 {@code qrKey} 没变而留着空图）。 */
        private void backToQrFromRisk() {
            riskFromQr = false;
            riskUrl = "";
            qrKey = "";
            qrBaseImage = null;
            qrExpiredShown = false;
            PluginLog.i(TAG, "对话框：风控已放行 ⇒ 回扫码档，重新显示当前二维码");
            showMode(Mode.QR);
            qrLabel.setIcon(null);
            qrLabel.repaint();
        }

        /**
         * 风控态（0.11.30 需求①；0.11.33 改成在电脑上完成；0.11.34 改成**内嵌**在本软件里）。
         *
         * <p>验证码登录被要求行为验证时：有地址就把验证交给内嵌验证窗（整窗盖在本对话框上）；
         * 没有地址就只给文案 + 扫码兜底（伺服端不给地址时谁都开不出验证面）。</p>
         */
        void showRisk(NeteaseApi.LoginOutcome out) {
            riskFromQr = false;             // 这条是验证码登录引来的风控：主按钮语义 = 重新登录
            riskUrl = (out != null && out.verifyUrl() != null) ? out.verifyUrl().trim() : "";
            String hint = (out == null || out.hint() == null || out.hint().isBlank())
                    ? "网易云要求先完成安全验证" : out.hint();
            if (qrTicker != null) {
                qrTicker.stop();
            }
            showMode(Mode.RISK);
            PluginLog.i(TAG, "对话框：验证码登录被风控 ⇒ 风控档（内嵌验证；地址="
                    + (riskUrl.isEmpty() ? "无" : "有(" + riskUrl.length() + " 字符，值不进日志)") + "）");
            if (riskUrl.isEmpty()) {
                riskTitle.setText("网易云要求先完成安全验证");
                riskHint.setText("伺服端这次没给验证地址");
                msg(hint + "：点这行改用扫码登录", HostTheme.ERR);
                armMsg(this::switchToQrNow, "点这里改用扫码登录");
                return;
            }
            riskTitle.setText("请在验证窗口完成安全验证");
            riskHint.setText(verifyOpened() ? "验证窗口已盖在本窗上" : "验证窗口会盖在本窗上");
            msg("完成后回本窗点「重试」", HostTheme.ERR);
            armMsg(this::openVerifyPage, "重新打开验证窗");
            autoOpenVerifyPage();
        }

        /** 风控兜底出口：**窗内**切到扫码档（0.11.30 起不再关窗换窗 —— 需求②）。 */
        private void switchToQrNow() {
            PluginLog.i(TAG, "对话框：风控兜底 ⇒ 窗内切到扫码档");
            switchTo(Mode.QR, true);
            openQr();
        }

        /** 登录成功（验证码/扫码两条路径共用）：抹掉验证码、提示、关窗。 */
        void onLoggedIn(String via) {
            wipe();
            AccountService.LoginStatus st = AccountService.status();
            String who = (st == null || st.nickname() == null) ? "" : st.nickname().trim();
            msg(who.isEmpty() ? "登录成功" : "登录成功：" + who, HostTheme.OK);
            PluginLog.i(TAG, "对话框：" + via + "登录成功 ⇒ 关窗（" + AccountService.statusLine() + "）");
            if (qrTicker != null) {
                qrTicker.stop();
            }
            if (dialog != null) {
                dialog.dispose();
            }
        }

        void busyOn(String text) {
            busy = true;
            phoneField.setEnabled(false);
            codeField.setEnabled(false);
            sendBtn.setEnabled(false);
            loginBtn.setEnabled(false);
            segQr.setEnabled(false);
            segSms.setEnabled(false);
            msg(text, HostTheme.TEXT_DIM);
        }

        void busyOff() {
            busy = false;
            if (!alive) {
                return;
            }
            phoneField.setEnabled(true);
            codeField.setEnabled(true);
            loginBtn.setEnabled(true);
            segQr.setEnabled(true);
            segSms.setEnabled(true);
            sendBtn.setEnabled(cooldown == null || !cooldown.isRunning());
        }

        /** 读后即抹：验证码不留在内存里（关窗 / 取消 / 登录成功都调它）。 */
        void wipe() {
            codeField.setText("");
        }

        void cleanup() {
            alive = false;
            if (cooldown != null) {
                cooldown.stop();
            }
            if (heartbeat != null) {
                heartbeat.stop();
            }
            if (qrTicker != null) {
                qrTicker.stop();
            }
            if (dialog != null) {
                UiRegistry.forget(dialog);
            }
            try {
                AccountService.pauseQrWatch();      // 0.11.33：关窗就停掉自驱轮询，别在后台刷风控行
            } catch (Throwable t) {
                PluginLog.w(TAG, "暂停扫码轮询失败：" + t.getClass().getSimpleName());
            }
            VerifyWindow.closeIfOpen();             // 0.11.34：内嵌验证窗跟着登录窗一起收
            if (current == this) {
                current = null;
            }
            PluginLog.i(TAG, "登录窗口资源已释放（心跳 " + heartbeatTicks + " 拍）");
        }

        void onHeartbeat() {
            heartbeatTicks++;
            if (heartbeatTicks <= 3 || heartbeatTicks % 6 == 0) {
                PluginLog.d(TAG, "心跳 #" + heartbeatTicks + "：EDT 仍在跑（模态窗没冻住宿主事件循环）");
            }
        }

        void startCooldown() {
            if (cooldown == null) {
                cooldown = new Timer(1000, e -> {
                    cooldownLeft--;
                    if (cooldownLeft <= 0) {
                        cooldown.stop();
                        sendBtn.setText("获取验证码");
                        if (!busy) {
                            sendBtn.setEnabled(true);
                        }
                        return;
                    }
                    sendBtn.setText("重新获取(" + cooldownLeft + ")");
                });
                cooldown.setInitialDelay(1000);
            }
            cooldownLeft = COOLDOWN_SEC;
            sendBtn.setText("重新获取(" + cooldownLeft + ")");
            sendBtn.setEnabled(false);
            cooldown.restart();
        }

        void onSendCode() {
            String phone = phoneField.getText() == null ? "" : phoneField.getText().trim();
            if (!PHONE_RE.matcher(phone).matches()) {
                msg("手机号要 11 位数字", HostTheme.ERR);
                phoneField.requestFocusInWindow();
                return;
            }
            busyOn("正在发送…");
            PluginLog.i(TAG, "对话框：请求发码（" + AccountService.maskPhone(phone) + "）");
            offThread("netease-login-dialog-send", () -> {
                final Dto.SmsResult res = SmsLogin.sendCodeNow(phone, "登录对话框");
                final boolean sent = res != null && res.sent();
                SwingUtilities.invokeLater(() -> {
                    if (!alive) {
                        return;
                    }
                    if (sent) {
                        startCooldown();
                        msg("验证码已发到 " + AccountService.maskPhone(phone), HostTheme.OK);
                        codeField.requestFocusInWindow();
                    } else {
                        msg(res == null ? "发送失败"
                                : (res.message() == null || res.message().isBlank()
                                ? "发送失败：code=" + res.code() : res.message()), HostTheme.ERR);
                    }
                    busyOff();
                });
            });
        }

        void onLogin() {
            String phone = phoneField.getText() == null ? "" : phoneField.getText().trim();
            String code = codeField.getText() == null ? "" : codeField.getText().trim();
            if (!PHONE_RE.matcher(phone).matches()) {
                msg("手机号要 11 位数字", HostTheme.ERR);
                phoneField.requestFocusInWindow();
                return;
            }
            if (!CODE_RE.matcher(code).matches()) {
                msg("验证码要 4~6 位数字", HostTheme.ERR);
                codeField.requestFocusInWindow();
                return;
            }
            busyOn("正在登录…");
            PluginLog.i(TAG, "对话框：请求验证码登录（" + AccountService.maskPhone(phone)
                    + "，验证码 " + code.length() + " 位，值不进日志）");
            offThread("netease-login-dialog-login", () -> {
                final NeteaseApi.LoginOutcome out = SmsLogin.loginNow(phone, code, "登录对话框");
                SwingUtilities.invokeLater(() -> {
                    if (!alive) {
                        return;
                    }
                    if (out != null && out.ok()) {
                        onLoggedIn("验证码");
                        return;
                    }
                    if (out != null && out.riskBlocked()) {
                        // 0.11.30 需求①：风控要求行为验证 ⇒ **当场在窗内**给出验证入口（二维码在窗里让手机扫），
                        // 而不是只留一句提示。取不到验证地址时，状态行退化成「点这行改用扫码登录」的出口。
                        busyOff();
                        showRisk(out);
                        return;
                    }
                    String hint = out == null ? "登录失败"
                            : (out.hint() == null || out.hint().isBlank() ? "登录失败：code=" + out.code() : out.hint());
                    msg(hint, HostTheme.ERR);
                    codeField.requestFocusInWindow();
                    busyOff();
                });
            });
        }
    }
}
