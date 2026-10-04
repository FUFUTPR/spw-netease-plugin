package com.example.netease.ui;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.AccountService;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌行为验证窗（0.11.34）—— 用户 m02783 的唯一要求：安全验证必须<b>在本软件里</b>完成。
 *
 * <p>形态：一张与登录窗<b>长得一模一样</b>的窗（同一张宿主静态底图 + 同一层 60% 遮罩 + 同一张 580 宽
 * 的卡，连零件都来自 {@code LoginDialog} 的同一批工厂），<b>原样盖在登录窗上</b>（矩形 = 登录窗矩形，
 * 并且 owner = 登录窗 ⇒ 永远在它上面）。窗内那块「网页区」由 {@code classes\verify-host\VerifyHost.exe}
 * 这个 C# 助手用 {@code SetParent} 把自己变成登录窗的子窗口、在里面渲染 Chromium（WebView2）——
 * 于是用户看不到任何浏览器外壳（没有地址栏、没有标签页、没有浏览器图标）。</p>
 *
 * <p>为什么是「跨进程子窗口」而不是「Java 内嵌浏览器」：本机（开发机=用户机）<b>没有任何 C/C++ 编译器</b>，
 * Java 侧手写 native/COM 桥（JNA/FFM）不可行；而 dotnet SDK 与 WebView2 运行时都在（Chromium 153），
 * 于是走 C# 助手 + 纯 stdout 协议，Java 侧零原生调用。可行性的实证：{@code tools\smoke\VerifyEmbedProbe}
 * （10 断言全绿：认领 Java 窗 → adopted → Chromium 渲染面在窗内抓到洋红像素 → 关窗后助手自杀）。</p>
 *
 * <p>降级链（每级都留可 grep 日志，坑 40 教训）：①内嵌（唯一正常路径）；②组件缺失 / 认领超时 / WebView2
 * 起不来 / 加载失败 ⇒ 状态行明说 + 自动改用外部浏览器（{@link LoginDialog#openExternal(String)}）；
 * ③连浏览器都拉不起来 ⇒ 地址进剪贴板 + 状态行给提示。任何一级都不会静默。</p>
 *
 * <p>cookie 桥：开窗前向账号线程要一份当前 cookie（{@code --cookie}）灌进 WebView —— 让验证页与插件
 * 用同一套设备身份；验证页自己写下的 cookie（{@code cookies <header>}）回灌进插件 jar
 * （{@link AccountService#mergeCookies(String)}）。<b>cookie 值一律不落日志</b>（只记长度/项数）。</p>
 */
public final class VerifyWindow {

    private static final String TAG = "verify-win";
    /** 助手靠这个标题找父窗（{@code FindWindowW("SunAwtDialog", title)}）⇒ 必须唯一且固定。 */
    private static final String TITLE = "网易云安全验证";
    private static final String EXE = "VerifyHost.exe";
    /** 助手每一行输出的前缀（{@code Program.cs} 的 {@code Say()} 加的；解析前必须先剥掉）。 */
    private static final String PREFIX = "verify-host: ";
    private static final String OVERRIDE_PROP = "spw.verifyhost";
    /** 网页区高度（卡片总高 = 458，见 {@link #CARD_MIN_H}）。 */
    private static final int WEB_H = 280;
    /** 卡片最小高度/宽度（按各行累加算出；小于它就把覆盖矩形撑大，保证卡片不被裁）。 */
    private static final int CARD_MIN_H = 458;
    /** 等账号线程给 cookie 的上限；超时就直接起（没有 cookie 也能开）。 */
    private static final int COOKIE_WAIT_MS = 1500;
    /** 助手从启动到 ready 的上限（超时判降级）。 */
    private static final int READY_TIMEOUT_MS = 20000;
    private static final int RECT_MARGIN = 60;

    private static volatile VerifyWindow current;

    private final JDialog dialog;
    private final JPanel webArea;
    private final JLabel placeholder;
    private final JLabel hint;
    private final JLabel status;
    private final JButton cancelBtn;
    private final JButton retryBtn;
    private final String url;
    private final String source;
    private final Rectangle cover;
    private final Runnable onRetry;
    private final AtomicBoolean launched = new AtomicBoolean();
    private final AtomicBoolean cookieAsked = new AtomicBoolean();

    private volatile Process helper;
    private volatile boolean ready;
    private volatile boolean degraded;
    private volatile boolean closed;
    private Runnable statusAction;

    // ---------------------------------------------------------------- 对外 API

    /** 内嵌验证窗是否开着（登录窗风控档据此判断「验证面交出去了没有」）。 */
    public static boolean isOpen() {
        VerifyWindow w = current;
        return w != null && !w.closed && w.dialog.isDisplayable();
    }

    /** 开着、而且开的就是这一份验证地址（伺服端每次答复都换 event_id/sign ⇒ 必须按地址比）。 */
    public static boolean isOpenFor(String url) {
        VerifyWindow w = current;
        return w != null && !w.closed && url != null && url.equals(w.url);
    }

    /** 关掉内嵌验证窗（登录窗关闭 / 登录成功 / 离开风控档时调）。 */
    public static void closeIfOpen() {
        VerifyWindow w = current;
        if (w != null) {
            SwingUtilities.invokeLater(w::dispose);
        }
    }

    /**
     * 打开内嵌验证窗，盖在登录窗上。
     *
     * @param owner    属主（登录窗）—— 决定 z 序：永远压着登录窗
     * @param url      行为验证地址（伺服端给的，{@code -462/8821} 时从 {@code data.url} 取）
     * @param cover    要覆盖的矩形（= 登录窗矩形；太小会按卡片尺寸撑大并保持居中）
     * @param backdrop 登录窗那一刻的宿主静态底图（null ⇒ 纯卡片色根）
     * @param source   来源文案（「扫码」/「验证码登录」，只用于提示行与日志）
     * @param onRetry  用户点「我已完成，重试」时回调（null = 只关窗）
     */
    public static void open(Window owner, String url, Rectangle cover, BufferedImage backdrop,
                            String source, Runnable onRetry) {
        if (url == null || url.isBlank()) {
            PluginLog.w(TAG, "验证地址为空 ⇒ 不开窗");
            return;
        }
        VerifyWindow w = current;
        if (w != null && !w.closed) {
            if (url.equals(w.url)) {
                w.toFront();
                PluginLog.d(TAG, "同一份验证地址已在窗里 ⇒ 只置前，不重开");
                return;
            }
            PluginLog.i(TAG, "换了一份验证地址 ⇒ 关掉旧验证窗再开新的");
            w.dispose();
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> open(owner, url, cover, backdrop, source, onRetry));
            return;
        }
        VerifyWindow nw = new VerifyWindow(owner, url, cover, backdrop,
                source == null ? "" : source, onRetry);
        current = nw;
        nw.show();
    }

    /** 仅测试用：等助手 ready（探针轮询 Pixel / 状态用），最多 {@code timeoutMs}。 */
    public static boolean awaitReady(long timeoutMs) {
        VerifyWindow w = current;
        if (w == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (w.ready) {
                return true;
            }
            if (w.closed) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return w.ready;
    }

    /** 仅测试用：内嵌验证窗现在的矩形（探针抓像素用）。 */
    public static Rectangle boundsForTest() {
        VerifyWindow w = current;
        return w == null ? null : w.dialog.getBounds();
    }

    // ---------------------------------------------------------------- 构造与显示

    private VerifyWindow(Window owner, String url, Rectangle cover, BufferedImage backdrop,
                         String source, Runnable onRetry) {
        this.url = url;
        this.cover = cover == null ? new Rectangle(0, 0, 760, 470) : new Rectangle(cover);
        this.source = source;
        this.onRetry = onRetry;

        dialog = new JDialog(owner, TITLE, Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        dialog.setBackground(backdrop != null ? HostTheme.PAGE_BG : HostTheme.CARD_BG);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setContentPane(LoginDialog.backdropRoot(backdrop));

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
                LoginDialog.text("安全验证", HostTheme.TITLE_PX, HostTheme.TEXT, true),
                Integer.MAX_VALUE, HostTheme.TITLE_PX + 8));

        hint = LoginDialog.text((source.isEmpty() ? "" : source + "触发了") + "网易云安全验证，请在下方窗口内完成",
                HostTheme.SMALL_PX, HostTheme.TEXT_DIM, false);
        LoginDialog.stack(col, gc, row++, 12, LoginDialog.sized(
                hint, Integer.MAX_VALUE, HostTheme.SMALL_PX + 7));

        // 网页区：助手的无边框子窗口就盖在这块矩形上（尺寸在 show() 之后按真实布局量）
        webArea = new JPanel(new BorderLayout());
        webArea.setOpaque(true);
        webArea.setBackground(HostTheme.FIELD_BG);
        placeholder = LoginDialog.text("正在启动内嵌验证…", HostTheme.SMALL_PX, HostTheme.TEXT_DIM, false);
        placeholder.setHorizontalAlignment(SwingConstants.CENTER);
        webArea.add(placeholder, BorderLayout.CENTER);
        LoginDialog.stack(col, gc, row++, 10, LoginDialog.sized(webArea, LoginDialog.CONTENT_W, WEB_H));

        status = LoginDialog.text("正在准备验证窗口…", HostTheme.SMALL_PX, HostTheme.TEXT_DIM, false);
        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (statusAction != null) {
                    statusAction.run();
                }
            }
        });
        LoginDialog.stack(col, gc, row++, 10, LoginDialog.sized(status, LoginDialog.CONTENT_W, HostTheme.LINE_H));

        cancelBtn = LoginDialog.pill("取消", false);
        cancelBtn.addActionListener(e -> dispose());
        retryBtn = LoginDialog.pill("我已完成，重试", true);
        retryBtn.addActionListener(e -> onRetryClick());
        JPanel btns = LoginDialog.xrow(HostTheme.BTN_H);
        btns.add(cancelBtn);
        btns.add(javax.swing.Box.createRigidArea(new Dimension(HostTheme.BTN_GAP, 0)));
        btns.add(retryBtn);
        LoginDialog.stack(col, gc, row, 12, LoginDialog.sized(btns, LoginDialog.CONTENT_W, HostTheme.BTN_H));

        card.add(col, BorderLayout.CENTER);
        dialog.getContentPane().add(card, new GridBagConstraints());
    }

    private void show() {
        Rectangle bounds = fit(cover);
        dialog.setBounds(bounds);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closed = true;
                if (current == VerifyWindow.this) {
                    current = null;
                }
            }
        });
        dialog.setVisible(true);
        PluginLog.i(TAG, "内嵌验证窗已开：来源=" + (source.isEmpty() ? "未知" : source)
                + "，覆盖矩形=" + fmt(bounds) + "（登录窗=" + fmt(cover) + "）"
                + "，底图=" + (dialog.getContentPane() instanceof JPanel ? "有" : "无")
                + "，地址长度=" + url.length() + "（值不进日志）");
        // 等一帧：布局完成后才能量出网页区的真实坐标（子窗口按它摆）
        SwingUtilities.invokeLater(this::start);
    }

    /** 覆盖矩形：至少能装下整卡（撑大时保持以原矩形为中心 ⇒ 仍然完全盖住登录窗）。 */
    private static Rectangle fit(Rectangle cover) {
        int minW = HostTheme.CARD_W + RECT_MARGIN * 2;
        int minH = CARD_MIN_H + RECT_MARGIN;
        Rectangle r = new Rectangle(cover);
        if (r.width < minW) {
            r.x -= (minW - r.width) / 2;
            r.width = minW;
        }
        if (r.height < minH) {
            r.y -= (minH - r.height) / 2;
            r.height = minH;
        }
        Rectangle screen = LoginDialog.screenUnion();
        if (screen != null) {
            Rectangle clip = LoginDialog.clipToScreen(r, screen);
            if (clip != null) {
                return clip;
            }
        }
        return r;
    }

    private void toFront() {
        dialog.toFront();
        dialog.requestFocus();
    }

    // ---------------------------------------------------------------- 启动助手

    private void start() {
        if (closed) {
            return;
        }
        Path dir = helperDir();
        if (dir == null) {
            degrade("没找到内嵌验证组件目录", "-D" + OVERRIDE_PROP + " 与 classes\\verify-host 都不存在");
            return;
        }
        Path exe = dir.resolve(EXE);
        if (!Files.isRegularFile(exe)) {
            degrade("内嵌验证组件缺失", exe.toString());
            return;
        }
        if (!cookieAsked.compareAndSet(false, true)) {
            return;
        }
        // 先问账号线程要一份 cookie（同一套设备身份），最多等 COOKIE_WAIT_MS；超时就直接起。
        try {
            AccountService.withCookies(ck -> {
                launched(ck);
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, "取 cookie 失败（照常起助手）：" + t.getClass().getSimpleName());
        }
        Timer once = new Timer(COOKIE_WAIT_MS, e -> launched(""));
        once.setRepeats(false);
        once.start();
    }

    /** 拿到（或放弃）cookie 后的统一起点：两个来路只有一个能真正起进程（两边都可能在非 EDT 线程上）。 */
    private void launched(String cookies) {
        if (!SwingUtilities.isEventDispatchThread()) {
            String ck = cookies;
            SwingUtilities.invokeLater(() -> launched(ck));
            return;
        }
        if (closed) {
            return;
        }
        if (!launched.compareAndSet(false, true)) {
            return;
        }
        Path dir = helperDir();
        Path exe = dir == null ? null : dir.resolve(EXE);
        if (exe == null || !Files.isRegularFile(exe)) {
            degrade("内嵌验证组件缺失", String.valueOf(exe));
            return;
        }
        Point rel = SwingUtilities.convertPoint(webArea, 0, 0, dialog.getContentPane());
        Rectangle web = new Rectangle(rel.x, rel.y, webArea.getWidth(), webArea.getHeight());
        String cookie = cookies == null ? "" : cookies.trim();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("--title");
        cmd.add(dialog.getTitle());
        cmd.add("--url");
        cmd.add(url);
        cmd.add("--rect");
        cmd.add(web.x + "," + web.y + "," + web.width + "," + web.height);
        cmd.add("--pid");
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        cmd.add("--data-dir");
        cmd.add(dataDir().toString());
        cmd.add("--timeout-ms");
        cmd.add(String.valueOf(READY_TIMEOUT_MS));
        cmd.add("--bg");
        cmd.add("262626");
        if (!cookie.isEmpty()) {
            cmd.add("--cookie");
            cmd.add(cookie);
        }
        PluginLog.i(TAG, "启动内嵌验证宿主：" + exe
                + "，网页区=" + web.x + "," + web.y + " " + web.width + "x" + web.height
                + "，cookie=" + (cookie.isEmpty() ? "无" : cookie.length() + " 字符（值不进日志）")
                + "，超时=" + READY_TIMEOUT_MS + "ms");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        try {
            helper = pb.start();
        } catch (Throwable t) {
            degrade("内嵌验证进程启动失败", t.getClass().getSimpleName() + "：" + t.getMessage());
            return;
        }
        Process p = helper;
        Thread pump = new Thread(() -> pump(p), "verify-host-out");
        pump.setDaemon(true);
        pump.start();
        Timer watchdog = new Timer(READY_TIMEOUT_MS + 4000, e -> {
            if (!ready && !closed) {
                degrade("内嵌验证启动超时", "超过 " + ((READY_TIMEOUT_MS + 4000) / 1000) + " 秒没有 ready");
            }
        });
        watchdog.setRepeats(false);
        watchdog.start();
    }

    /** 读助手 stdout（协议见 tools/verify-host/Program.cs）；URL 与 cookie 只报长度。 */
    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.trim();
                PluginLog.d(TAG, "宿主：" + l);
                onHostLine(l);
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "读内嵌验证宿主输出失败：" + t.getClass().getSimpleName());
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (Throwable ignored) {
            // 进程已被 destroy()：退出码不重要
        }
        int c = code;
        SwingUtilities.invokeLater(() -> {
            if (!closed && !ready) {
                degrade("内嵌验证进程提前退出", "退出码=" + c);
            } else if (!closed) {
                setStatus("验证窗口已关闭");
            }
        });
    }

    /**
     * 解析助手的一行输出。
     *
     * <p><b>注意前缀</b>：助手（{@code tools/verify-host/Program.cs} 的 {@code Say()}）每一行都带
     * {@code "verify-host: "} 前缀，这里是先剥前缀再比关键字；忘了剥就会出现「助手明明报了 ready、
     * 插件却一直等不到」——0.11.34 装机前就是这么漏的（探针第四节当场抓出来：走满 24 秒看门狗降级，
     * 而日志里 {@code 宿主：verify-host: ready} 明明已经打出来了）。</p>
     */
    private void onHostLine(String raw) {
        String l = raw == null ? "" : raw.trim();
        int at = l.indexOf(PREFIX);
        if (at >= 0) {
            l = l.substring(at + PREFIX.length()).trim();
        }
        if (l.startsWith("parent ")) {
            setStatus("已接管登录窗口，正在启动内嵌验证…");
        } else if (l.startsWith("adopted")) {
            setStatus("内嵌验证窗口已就位");
        } else if (l.startsWith("webview2 ")) {
            PluginLog.i(TAG, "WebView2 运行时 = " + l.substring("webview2 ".length()).trim());
        } else if (l.equals("ready")) {
            markReady();
        } else if (l.startsWith("nav-start")) {
            setStatus("正在加载安全验证页面…");
        } else if (l.startsWith("cookies ")) {
            mergeCookies(l.substring("cookies ".length()).trim());
        } else if (l.startsWith("cookie-in ")) {
            PluginLog.i(TAG, "登录 cookie 已灌进内嵌验证页：" + l.substring("cookie-in ".length()).trim() + " 项");
        } else if (l.startsWith("nav-done")) {
            if (l.contains(" False")) {
                degrade("验证页加载失败", l);
            } else {
                setStatus("请在上方窗口完成验证，完成后点「我已完成，重试」");
            }
        } else if (l.equals("window-close-requested")) {
            SwingUtilities.invokeLater(this::dispose);
        } else if (l.equals("parent-timeout")) {
            degrade("没认领到登录窗", "助手在超时前没找到父窗口");
        } else if (l.startsWith("fail ")) {
            degrade("内嵌验证失败", l.substring("fail ".length()));
        }
    }

    private void markReady() {
        ready = true;
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            placeholder.setText("");
            webArea.revalidate();
            webArea.repaint();
        });
        setStatus("请在上方窗口完成验证，完成后点「我已完成，重试」");
    }

    private void mergeCookies(String header) {
        if (header == null || header.isBlank()) {
            return;
        }
        PluginLog.i(TAG, "验证页回灌 cookie：长度 " + header.length() + "（值不进日志）");
        try {
            AccountService.mergeCookies(header);
        } catch (Throwable t) {
            PluginLog.w(TAG, "cookie 回灌失败：" + t.getClass().getSimpleName());
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                status.setText(text);
            }
        });
    }

    /**
     * 内嵌起不来 ⇒ 降级（用户裁定：这是兜底，必须留痕）。
     *
     * <p>①状态行明说原因；②自动改用外部浏览器；③浏览器也拉不起来 ⇒ 地址进剪贴板，状态行变成可点入口。</p>
     */
    private void degrade(String reason, String detail) {
        if (degraded || closed) {
            return;
        }
        degraded = true;
        PluginLog.w(TAG, "内嵌验证不可用 ⇒ 降级外部浏览器：" + reason + "（" + detail + "）");
        boolean ok = false;
        try {
            ok = LoginDialog.openExternal(url);
        } catch (Throwable t) {
            PluginLog.w(TAG, "外部浏览器兜底也失败：" + t.getClass().getSimpleName());
        }
        hint.setText("内嵌验证不可用：" + reason);
        if (ok) {
            setStatus("已改用浏览器打开验证页");
            statusAction = null;
        } else {
            setStatus("点这里复制验证地址，手动打开浏览器");
            statusAction = () -> {
                boolean copied = LoginDialog.openExternal(url);
                PluginLog.w(TAG, "用户点状态行重试兜底：结果=" + (copied ? "地址已复制/已打开" : "仍失败"));
            };
        }
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                hint.repaint();
                status.repaint();
            }
        });
    }

    private void onRetryClick() {
        PluginLog.i(TAG, "用户点「我已完成，重试」（内嵌验证窗）⇒ 关窗并回登录窗重试");
        dispose();
        if (onRetry != null) {
            try {
                onRetry.run();
            } catch (Throwable t) {
                PluginLog.w(TAG, "重试回调失败：" + t.getClass().getSimpleName());
            }
        }
    }

    private void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        Process p = helper;
        if (p != null && p.isAlive()) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
                // 已经退出了
            }
        }
        try {
            if (dialog.isDisplayable()) {
                dialog.dispose();
            }
        } catch (Throwable ignored) {
            // 已经关掉了
        }
        if (current == this) {
            current = null;
        }
        PluginLog.i(TAG, "内嵌验证窗已关（ready=" + ready + "，降级=" + degraded + "）");
    }

    // ---------------------------------------------------------------- 资源定位

    /**
     * 助手目录：优先 {@code -Dspw.verifyhost=<dir>}（探针/开发用），否则按本类的 code source 推 ——
     * 插件从 {@code <pluginPath>\plugin-*.jar} 加载 ⇒ 兄弟目录 {@code <pluginPath>\classes\verify-host}。
     */
    private static Path helperDir() {
        String override = System.getProperty(OVERRIDE_PROP);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                return p;
            }
            PluginLog.w(TAG, "-D" + OVERRIDE_PROP + " 指的不是目录：" + override);
        }
        try {
            URL loc = VerifyWindow.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            Path dir = Files.isDirectory(p) ? p : p.getParent();
            Path[] candidates = Files.isDirectory(p)
                    ? new Path[] {p.resolve("classes").resolve("verify-host"), p.resolve("verify-host")}
                    : new Path[] {dir.resolve("classes").resolve("verify-host"), dir.resolve("verify-host")};
            for (Path c : candidates) {
                if (Files.isDirectory(c)) {
                    return c;
                }
            }
            PluginLog.w(TAG, "按 code source 没找到助手目录（codeSource=" + p + "）");
        } catch (Throwable t) {
            PluginLog.w(TAG, "定位助手目录失败：" + t.getClass().getSimpleName());
        }
        return null;
    }

    /** WebView2 的用户数据目录（放插件数据目录下的 verify-webview；拿不到数据目录就退到临时目录）。 */
    private static Path dataDir() {
        try {
            Path d = DataPaths.data();
            if (d != null) {
                Path target = d.resolve("verify-webview");
                DataPaths.ensure(target);
                return target;
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "取插件数据目录失败：" + t.getClass().getSimpleName());
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir", "."), "spw-verify-webview");
        try {
            Files.createDirectories(fallback);
        } catch (Throwable ignored) {
            // 建不出来就让 WebView2 自己去抱怨（会走 degrade）
        }
        return fallback;
    }

    private static String fmt(Rectangle r) {
        return r == null ? "null" : r.x + "," + r.y + " " + r.width + "x" + r.height;
    }

    /** 仅测试用：当前状态行的文案（探针断言「降级必须明说」用）。 */
    public static String statusTextForTest() {
        VerifyWindow w = current;
        return w == null ? "" : String.valueOf(w.status.getText());
    }
}
