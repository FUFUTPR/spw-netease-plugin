package spw.harness;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P3 验收（账号 / 歌单 / 批量 / 标签回写）的 harness 侧检查。
 *
 * <p>分工：
 * <ul>
 *   <li><b>verify-spmod.ps1 P10.x</b> 做「包内静态事实」比对（字符串级）；</li>
 *   <li><b>本类 H9.x</b> 做「接线真能反射调用」——从 preference_config.json 读出全部
 *       on_click，{@code Class.forName} + {@code getMethod} 断言 public static 无参 void，
 *       失败时打印控件标题（verify-spmod 无法发现「方法名拼错」）；</li>
 *   <li><b>本类 H10.x</b> 做 stop() 之后的线程残留、凭据密文往返/篡改、日志与数据目录
 *       全量扫描（无 cookie 明文）。</li>
 * </ul>
 *
 * <p>所有类都从**插件类加载器**加载（{@code pcl}），否则测的不是包里的字节码。
 */
final class P3Checks {

    /** 结果回调（由 HarnessMain 注入，落到同一张 PASS/FAIL 表）。 */
    interface Reporter {
        void check(String id, String desc, boolean ok, String detail);
    }

    private P3Checks() {
    }

    private static void info(String s) {
        System.out.println("  · " + s);
        System.out.flush();
    }

    private static String oneLine(Throwable t) {
        if (t == null) {
            return "?";
        }
        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        String m = c.getMessage();
        String s = c.getClass().getSimpleName() + (m == null ? "" : (": " + m));
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String join(List<String> l) {
        return String.join(" / ", l);
    }

    /**
     * 跑完 EDT 上已排队的任务。P3 跑在非 EDT 线程上，而 {@code currentAccount()} 这类开窗入口只是把
     * 建窗动作 {@code invokeLater} 投递出去 ⇒ 投递后必须先把 EDT 队列跑干净，窗口「到底建起来没有」
     * 才是可观测事实（否则断言会读到一个还没开始的空窗）。
     */
    private static void flushEdt() {
        try {
            if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
                javax.swing.SwingUtilities.invokeAndWait(() -> { });
            }
        } catch (Throwable ignored) {
            // EDT 起不来（headless / 已死）时不在这里失败：窗口是否真的建起来由 textForTest() 判定
        }
    }

    private static String abbrev(List<String> l, int max) {
        if (l.size() <= max) {
            return join(l);
        }
        return join(l.subList(0, max)) + " …（共 " + l.size() + " 项）";
    }

    /**
     * H9.15 用：把 DevMode 的资源名解析成**真实文件路径**。
     *
     * <p>生产代码走 {@code DevMode.class.getResource(...)}（见 {@code readResource} / {@code isSelfRead}）；
     * 类加载器直接取带前导斜杠的名字会拿到 null，故这里以 {@code Class.getResource} 为准、
     * 类加载器（去掉前导斜杠）兜底。解析失败把原因追加进 {@code bad} 并返回 null。
     */
    private static Path resourceFile(Class<?> owner, String res, String label, List<String> bad) {
        String name = res == null ? "" : res;
        java.net.URL u = name.isEmpty() ? null : owner.getResource(name);
        if (u == null && !name.isEmpty()) {
            ClassLoader cl = owner.getClassLoader();
            if (cl != null) {
                u = cl.getResource(name.startsWith("/") ? name.substring(1) : name);
            }
        }
        if (u == null) {
            bad.add(label + "=\"" + res + "\" 在插件类加载器里取不到资源（getResource 返回 null）："
                    + "切档源资源必须随包分发且能从装机 classes\\ 读到，否则 apply() 只会失败或读错档");
            return null;
        }
        if (!"file".equalsIgnoreCase(u.getProtocol())) {
            bad.add(label + "=\"" + res + "\" 解析成 " + u.getProtocol() + ":（" + u + "）而不是 file:："
                    + "装机形态下 classpath 根就是 classes\\，资源得落在真实文件上，才谈得上与改写目标做同一性比对");
            return null;
        }
        try {
            return Path.of(u.toURI());
        } catch (Throwable t) {
            bad.add(label + "=\"" + res + "\" 的 file: URL 转 Path 失败：" + oneLine(t));
            return null;
        }
    }

    /** H9.15 用：同一物理文件？路径没取到／文件不存在时返回「?」，不抛异常（异常本身由上游报）。 */
    private static String sameFileQuiet(Path a, Path b) {
        if (a == null || b == null) {
            return "?(路径未取到)";
        }
        try {
            return Files.isSameFile(a, b) ? "true（同一物理文件！）" : "false";
        } catch (Throwable t) {
            return "?(比对失败 " + t.getClass().getSimpleName() + ")";
        }
    }

    /** H9.16 用：按「先父后子」递归收集组件树（顺序 = 构造顺序，与窗内视觉顺序一致）。 */
    private static void collectComponents(java.awt.Component c, List<java.awt.Component> out) {
        if (c == null) {
            return;
        }
        out.add(c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                collectComponents(k, out);
            }
        }
    }

    /**
     * H9.16 用：这个组件是不是「可编辑控件」（只读信息窗里必须一个都没有）。
     *
     * <p>{@code JButton} 不算（它是本窗唯一的交互件，且不改任何值）；{@code JToggleButton} 系
     * （含 {@code JCheckBox} / {@code JRadioButton}）算。
     */
    private static boolean isEditableControl(java.awt.Component c) {
        return c instanceof javax.swing.text.JTextComponent
                || c instanceof javax.swing.JComboBox
                || c instanceof javax.swing.JSpinner
                || c instanceof javax.swing.JSlider
                || c instanceof javax.swing.JToggleButton
                || c instanceof javax.swing.JFormattedTextField
                || c instanceof java.awt.TextComponent;
    }

    // ------------------------------------------------------------ JSON 取值

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object o) {
        return (o instanceof List) ? (List<Object>) o : Collections.emptyList();
    }

    private static String asStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 展平 preference_config.json：每项 = (组 config 文件名, 组标题, 控件标题, on_click 目标)。 */
    private static final class Click {
        final String cfg;
        final String group;
        final String title;
        final String target;

        Click(String cfg, String group, String title, String target) {
            this.cfg = cfg;
            this.group = group;
            this.title = title;
            this.target = target;
        }
    }

    private static List<Click> clicks(Map<String, Object> root) {
        List<Click> out = new ArrayList<>();
        for (Object g : asArr(root.get("configs"))) {
            Map<String, Object> gm = asObj(g);
            String cfg = asStr(gm.get("config"));
            String gt = asStr(gm.get("title"));
            for (Object p : asArr(gm.get("preferences"))) {
                Map<String, Object> pm = asObj(p);
                String t = asStr(pm.get("on_click"));
                if (t == null || t.trim().isEmpty()) {
                    continue;
                }
                out.add(new Click(cfg, gt, asStr(pm.get("title")), t.trim()));
            }
        }
        return out;
    }

    private static Map<String, Object> groupByConfig(Map<String, Object> root, String cfg) {
        for (Object g : asArr(root.get("configs"))) {
            Map<String, Object> gm = asObj(g);
            if (cfg.equals(asStr(gm.get("config")))) {
                return gm;
            }
        }
        return null;
    }

    private static Map<String, Object> prefByTitle(Map<String, Object> group, String title) {
        if (group == null) {
            return null;
        }
        for (Object p : asArr(group.get("preferences"))) {
            Map<String, Object> pm = asObj(p);
            if (title.equals(asStr(pm.get("title")))) {
                return pm;
            }
        }
        return null;
    }

    // ============================================================ H9（start 之后）

    /**
     * P3 入口方法（docs/00 §6.9 / §6.6）：public static void 无参。
     *
     * <p>0.5.0 瘦身版：{@code retagCurrent / clearCache / fixMatch} 随歌词·匹配·打标功能一并删除，
     * 在线播放（实验）组的四个入口补进来（它们同样是配置页 on_click 的落点）。</p>
     */
    private static final String[] ENTRY_METHODS = {
            "showLoginDialog", "openPlaylist", "logout", "testConnection",
            "openDataDir", "openSearch",
            // 0.11.7：账号层去客户端化 —— 原生登录（弹框内输入验证码）+ 账号状态；
            // 客户端接入（clientLogin/clientShow/clientTakeOver/clientStop/clientStatus）已整组删除。
            // 0.11.9：手机号 + 密码登录已按用户决定退役（loginPassword 同时移除）。
            // 0.11.22：登录整组搬到配置页原生控件（用户规格）——独立模态窗 ui.LoginPrompt 退役。
            // 0.11.23：登录块收成「一行」（值形态分步）——「获取验证码 smsSend」「确定登录 smsLoginNow」
            // 两个入口随之删除（登录侧零入口）。
            // 0.11.26：用户规格「点开登录弹出一个框，里面能分别填手机号和验证码」⇒ 单行输入让位给
            // **自绘对话框** ui.LoginDialog，入口 = 配置页「登录」按钮 → openLoginDialog（见 docs/00 §6.27）。
            "openLoginDialog",
            // 0.11.35（用户 m00001 问题1）：账号行从 edittext 改成**按钮**，点开自绘只读窗
            // ui.AccountStatusWindow 才展示名字 / id / 会员；开发者档那个「账号状态」按钮与
            // NeteasePlugin.accountStatus() 一并删除，职责由本入口接管。
            "currentAccount",
            // 0.11.7：状态与缓存组（三条管线总览 + 三态 + 两个独立清理按钮）
            "pipelineStatus", "coverStats", "lyricStats", "clearCoverCache", "clearLyricCache",
            // 0.11.7：维护组新增档位状态（D7 界面标注的落点）
            "audioLevelStatus",
            // 0.4.0：在线播放（实验）组
            "onlineSelfCheck", "onlineTryOne", "onlineTryLocal", "onlineInjectInterceptor"
    };

    private static final String PLUGIN_CLASS = "com.example.netease.NeteasePlugin";

    /**
     * 0.11.30 需求③：收进「开发者模式」的入口（关着时两档里都不该可见，且运行时闸会拒绝）。
     *
     * <p>0.11.35（用户 m00001 问题1）：「账号状态」按钮已被删除（职责并进账号行按钮「当前账号」，
     * 那是普通用户入口、不设开发者闸）⇒ 本清单从 12 条变 11 条。</p>
     */
    private static final List<String> DEV_TARGETS = List.of(
            PLUGIN_CLASS + ".pipelineStatus", PLUGIN_CLASS + ".coverStats", PLUGIN_CLASS + ".lyricStats",
            PLUGIN_CLASS + ".audioLevelStatus", PLUGIN_CLASS + ".openDataDir",
            PLUGIN_CLASS + ".rebuildMapping", PLUGIN_CLASS + ".testConnection",
            PLUGIN_CLASS + ".onlineSelfCheck", PLUGIN_CLASS + ".onlineTryOne",
            PLUGIN_CLASS + ".onlineTryLocal", PLUGIN_CLASS + ".onlineInjectInterceptor");

    /** 0.11.30：用户档（开发者模式关闭时的那一档）里应有的按钮接线。 */
    private static final List<String> USER_TARGETS = List.of(
            PLUGIN_CLASS + ".currentAccount",
            PLUGIN_CLASS + ".openLoginDialog", PLUGIN_CLASS + ".logout",
            PLUGIN_CLASS + ".clearCoverCache", PLUGIN_CLASS + ".clearLyricCache",
            PLUGIN_CLASS + ".clearPlaybackCache", PLUGIN_CLASS + ".syncNow");

    /** 一档 schema 里所有行的标题（用于「用户档 ⊆ 开发者档」的子集判定）。 */
    private static Set<String> allTitles(Map<String, Object> root) {
        Set<String> out = new LinkedHashSet<>();
        for (Object g : asArr(root.get("configs"))) {
            for (Object p : asArr(asObj(g).get("preferences"))) {
                String t = asStr(asObj(p).get("title"));
                if (t != null && !t.trim().isEmpty()) {
                    out.add(normAccountTitle(t.trim()));
                }
            }
        }
        return out;
    }

    /**
     * 账号行标题归一。
     *
     * <p>0.11.32–0.11.34 时账号行 title 里带动态尾巴（{@code 当前账号{{ACCOUNT}}} → 「：未登录 /
     * ：昵称（ID uid）」），两档比对必须把这段归一掉。</p>
     *
     * <p><b>0.11.35（用户 m00001 问题1）</b>：账号行改成按钮、title 固定就是「当前账号」，已经没有任何
     * 动态尾巴。这里保留归一，是为了同时容忍<b>老装机目录</b>里可能残留的 0.11.32–0.11.34 形态
     * （升级换包那一瞬间，宿主装的还是上一版的那一份文件）—— 否则「用户档 ⊆ 开发者档」会假红一次。
     * 真正的把关移到 H9.13 的账号行断言（必须恰好是「当前账号」四个字、不带冒号、不带 {{ACCOUNT}}）。</p>
     */
    private static String normAccountTitle(String title) {
        return title.startsWith("当前账号") ? "当前账号（动态登录态）" : title;
    }

    /** 0.4.0：在线播放音质档位白名单（与 preference_config.json 的 entry_values 对齐）。 */
    private static final Set<String> LEVELS =
            new LinkedHashSet<>(List.of("standard", "higher", "exhigh", "lossless", "hires",
                    "sky", "jymaster", "jyeffect", "dolby"));

    /** H9：接线反射校验 + P3 入口 + 启动期配置快照 + 账号线程正证据。 */
    static void phaseWiring(Reporter rep, Path pluginDir, Path dataDir, ClassLoader pcl, String pluginId) {
        System.out.println();
        System.out.println("── H9 P3 接线反射校验（preference_config.json → 插件真实字节码） "
                + "──────────");

        // ---------- H9.1 preference_config.json 可解析
        Path pref = pluginDir.resolve("classes").resolve("preference_config.json");
        String text = null;
        String readErr = null;
        Map<String, Object> root = null;
        try {
            text = new String(Files.readAllBytes(pref), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
        } catch (Throwable t) {
            readErr = oneLine(t);
        }
        if (text != null) {
            try {
                root = StubConfigHelper.MiniJson.parseObject(text);
            } catch (Throwable t) {
                readErr = oneLine(t);
            }
        }
        rep.check("H9.1", "preference_config.json 可解析（harness 侧独立解析，与 verify-spmod 互为交叉验证）",
                root != null,
                root != null ? ("顶层键 = " + root.keySet()) : ("读取/解析失败：" + readErr));
        if (root == null) {
            return;
        }

        // ---------- 开发者档（0.11.30 需求③）：装机目录里同时躺着两档 schema
        //  关着开发者模式时 DevMode 把 classes\preference_config.json 写成用户档（H9.1 读的就是它），
        //  打开时写成开发者档；开发者档本体常驻为 classes\preference_config.dev.json，随包分发。
        Path prefDev = pluginDir.resolve("classes").resolve("preference_config.dev.json");
        String devErr = null;
        Map<String, Object> rootDev = null;
        try {
            String devText = new String(Files.readAllBytes(prefDev), StandardCharsets.UTF_8);
            if (devText.startsWith("\uFEFF")) {
                devText = devText.substring(1);
            }
            rootDev = StubConfigHelper.MiniJson.parseObject(devText);
        } catch (Throwable t) {
            devErr = oneLine(t);
        }

        // ---------- H9.2 账号组 config = account_cfg.json（不与凭据密文 account.json 撞名；0.11.7 起无遗留客户端组）
        Map<String, Object> acct = groupByConfig(root, "account_cfg.json");
        Set<String> cfgNames = new LinkedHashSet<>();
        for (Object g : asArr(root.get("configs"))) {
            cfgNames.add(asStr(asObj(g).get("config")));
        }
        boolean clash = cfgNames.contains("account.json");
        boolean legacyClient = cfgNames.contains("client_cfg.json");
        rep.check("H9.2", "账号组 config=account_cfg.json，无任何组 config=account.json（避免盖掉凭据密文），且无遗留客户端组 client_cfg.json",
                acct != null && !clash && !legacyClient,
                "config 文件名 = " + cfgNames + (clash ? "  ⚠ 出现 account.json" : "")
                        + (legacyClient ? "  ⚠ 出现遗留 client_cfg.json（0.11.7 应已删除）" : ""));

        // ---------- H9.3 用户档接线清单（0.11.30 需求③：开发者行整组搬进 preference_config.dev.json）
        List<Click> all = clicks(root);
        List<String> targets = all.stream().map(c -> c.target).collect(Collectors.toList());
        List<String> missingUser = USER_TARGETS.stream().filter(t -> !targets.contains(t)).collect(Collectors.toList());
        List<String> devLeak = DEV_TARGETS.stream().filter(targets::contains).collect(Collectors.toList());
        rep.check("H9.3", "用户档 preference_config.json 的 on_click 覆盖用户可见入口（登录/退出登录/封面·歌词·播放缓存三清 + 同步，"
                        + "共 " + all.size() + " 个接线），且不含任何开发者入口",
                missingUser.isEmpty() && devLeak.isEmpty(),
                "缺失：" + join(missingUser) + "；开发者入口泄漏进用户档：" + join(devLeak));

        // ---------- H9.4 全部 on_click 都是 public static 无参 void（两档一起查；方法名拼错 / 签名漂移都在这暴露）
        //  开发者档的接线同样要能反射到包内真实类——否则打开开发者模式后按钮是死的。
        List<Click> allWire = new ArrayList<>(all);
        List<String> devClicks = new ArrayList<>();
        if (rootDev != null) {
            for (Click c : clicks(rootDev)) {
                allWire.add(c);
                devClicks.add(c.title + "→" + c.target);
            }
        }
        List<String> wireOk = new ArrayList<>();
        List<String> wireBad = new ArrayList<>();
        for (Click c : allWire) {
            int dot = c.target.lastIndexOf('.');
            if (dot <= 0) {
                wireBad.add(c.title + " → " + c.target + "（不是 包名.类名.方法名）");
                continue;
            }
            String cn = c.target.substring(0, dot);
            String mn = c.target.substring(dot + 1);
            Class<?> clz;
            try {
                clz = Class.forName(cn, false, pcl);
            } catch (Throwable t) {
                wireBad.add(c.title + " → " + c.target + "（类加载失败：" + oneLine(t) + "）");
                continue;
            }
            try {
                Method m = clz.getMethod(mn); // 无参：多参数方法在这里就会 NoSuchMethod
                int mod = m.getModifiers();
                boolean ok = Modifier.isPublic(mod) && Modifier.isStatic(mod)
                        && m.getParameterCount() == 0 && m.getReturnType() == void.class;
                if (ok) {
                    wireOk.add(c.title + "→" + mn);
                } else {
                    wireBad.add(c.title + " → " + c.target + "（签名不符：public=" + Modifier.isPublic(mod)
                            + " static=" + Modifier.isStatic(mod) + " 参数=" + m.getParameterCount()
                            + " 返回=" + m.getReturnType().getSimpleName() + "，要求 public static 无参 void）");
                }
            } catch (NoSuchMethodException e) {
                wireBad.add(c.title + " → " + c.target + "（方法不存在：无 public 无参方法 " + mn + "）");
            } catch (Throwable t) {
                wireBad.add(c.title + " → " + c.target + "（反射失败：" + oneLine(t) + "）");
            }
        }
        rep.check("H9.4", "两档 on_click 都能反射到 public static 无参 void 方法（用户档 " + all.size()
                        + " 个 + 开发者档 " + devClicks.size() + " 个接线）",
                wireBad.isEmpty(),
                wireBad.isEmpty() ? ("全部可调用：" + join(wireOk)) : ("不合格：" + abbrev(wireBad, 6)));

        // ---------- H9.13 开发者档本体 + 两档分离（0.11.30 需求③：开关关闭时开发者功能不可见）
        //  ①开发者档随包分发且可解析；②11 个开发者入口接线齐全（0.11.35 起账号状态已删除）；
        //  ③用户档每一行在开发者档里都有同名行（开发者档 = 用户档 + 开发者行，不许少）；
        //  ④用户档里点名的那几行（日志级别 / 测试网络连通性）不许出现；
        //  ⑤账号行（0.11.35 用户 m00001 问题1）：标题恰好「当前账号」、是 button、
        //    接线 currentAccount、summary 为固定短句 —— **不许**带「：<登录态>」这类常驻账号信息。
        List<String> devBad = new ArrayList<>();
        String devDetail;
        if (rootDev == null) {
            devBad.add("开发者档 classes\\preference_config.dev.json 读不到或解析失败：" + devErr);
        } else {
            List<String> devTargetList = clicks(rootDev).stream().map(c -> c.target).collect(Collectors.toList());
            for (String t : DEV_TARGETS) {
                if (!devTargetList.contains(t)) {
                    devBad.add("开发者档缺接线：" + t);
                }
            }
            if (devTargetList.contains(PLUGIN_CLASS + ".accountStatus")) {
                devBad.add("开发者档里仍有已删除的接线：" + PLUGIN_CLASS + ".accountStatus（0.11.35 起账号状态按钮已删除）");
            }
            Set<String> userTitles = allTitles(root);
            Set<String> devTitles = allTitles(rootDev);
            for (String t : userTitles) {
                if (!devTitles.contains(t)) {
                    devBad.add("开发者档少了用户可见行：「" + t + "」");
                }
            }
            for (String t : new String[]{"账号状态", "日志级别", "测试网络连通性", "启动时自动登录"}) {
                if (userTitles.contains(t)) {
                    devBad.add("用户档里仍能看见开发者行：「" + t + "」");
                }
            }
            if (!devTitles.contains("开发者模式")) {
                devBad.add("开发者档里没有「开发者模式」开关行");
            }
            // 0.11.35（用户 m00001 问题1）：账号行必须是「点开才看账号信息」的按钮，
            // 且页面上**不许**常驻任何账号文本（旧形态是 edittext 标题里写「当前账号：UID … · 会员」）。
            if (text != null) {
                if (text.contains("{{ACCOUNT}}")) {
                    devBad.add("装机用户档里还留着 {{ACCOUNT}} 占位（0.11.32 的账号行占位机制应已退役）");
                }
                if (text.contains("\"当前账号：")) {
                    devBad.add("装机用户档的账号行仍常驻登录态文本（用户 m00001 要求「不要常驻显示」）");
                }
            }
            Map<String, Object> acctRow = prefByTitle(acct, "当前账号");
            if (acctRow == null) {
                devBad.add("用户档账号组里找不到「当前账号」行");
            } else {
                Map<String, Object> pm = asObj(acctRow);
                String t = asStr(pm.get("title"));
                String ty = asStr(pm.get("type"));
                String oc = asStr(pm.get("on_click"));
                if (!"当前账号".equals(t)) {
                    devBad.add("账号行标题不是「当前账号」而是「" + t + "」");
                }
                if (!"button".equalsIgnoreCase(ty)) {
                    devBad.add("账号行不是 button（实际 " + ty + "）—— edittext 点开是宿主可编辑的输入框，不满足用户要求");
                }
                if (!(PLUGIN_CLASS + ".currentAccount").equals(oc)) {
                    devBad.add("账号行 on_click 不是 " + PLUGIN_CLASS + ".currentAccount（实际 " + oc + "）");
                }
            }
        }
        devDetail = rootDev == null ? devBad.toString()
                : ("开发者档行标题 " + allTitles(rootDev).size() + " 个 / 接线 " + clicks(rootDev).size()
                + " 个（开发者入口 " + DEV_TARGETS.size() + " 条全在：" + (devBad.isEmpty() ? "是" : "否") + "）"
                + (devBad.isEmpty() ? "" : "；" + join(devBad)));
        rep.check("H9.13", "开发者档随包分发且 = 用户档 + " + DEV_TARGETS.size()
                        + " 个开发者按钮（用户档里看不见任何开发者行；两档都有「开发者模式」开关；"
                        + "账号行是 button「当前账号」→ currentAccount，页面上不常驻任何账号信息）",
                devBad.isEmpty(), devDetail);

        // ---------- H9.5 P3 入口签名
        List<String> entryBad = new ArrayList<>();
        Class<?> pluginClz = null;
        try {
            pluginClz = Class.forName(PLUGIN_CLASS, false, pcl);
        } catch (Throwable t) {
            entryBad.add(PLUGIN_CLASS + " 类加载失败：" + oneLine(t));
        }
        if (pluginClz != null) {
            for (String name : ENTRY_METHODS) {
                try {
                    Method m = pluginClz.getMethod(name);
                    int mod = m.getModifiers();
                    if (!(Modifier.isPublic(mod) && Modifier.isStatic(mod)
                            && m.getParameterCount() == 0 && m.getReturnType() == void.class)) {
                        entryBad.add(name + " 签名不符（要求 public static void 无参）");
                    }
                } catch (Throwable t) {
                    entryBad.add(name + " 不存在");
                }
            }
        }
        rep.check("H9.5", "P3 入口方法齐全且是 public static void 无参（" + ENTRY_METHODS.length + " 个）",
                entryBad.isEmpty(), entryBad.isEmpty() ? "全部符合" : join(entryBad));

        // ---------- H9.6 start() 期配置日志（PluginConfig 用 [cfg] 打印快照）
        String cfgLine = null;
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && cfgLine == null) {
            String log = readAllLogs(dataDir);
            if (log != null) {
                for (String line : log.split("\\R")) {
                    if (line.contains("配置已加载")) {
                        cfgLine = line.trim();
                        break;
                    }
                }
            }
            if (cfgLine == null) {
                sleep(200);
            }
        }
        boolean cfgOk = cfgLine != null && cfgLine.contains("auto_login=") && cfgLine.contains("phone=");
        rep.check("H9.6", "插件日志出现「配置已加载 … auto_login=… phone=…」（0.11.7 账号层开关与手机号真的进了快照，手机号只报「已填/未填」不报号码）",
                cfgOk, cfgLine == null ? "4s 内未在日志里找到「配置已加载」" : cfgLine);

        // ---------- H9.7 运行期快照 ↔ JSON 默认值交叉验证（0.11.7：auto_login / audio_level）
        String detail97;
        boolean ok97 = false;
        try {
            Class<?> pc = Class.forName("com.example.netease.cfg.PluginConfig", false, pcl);
            Object autoLogin = pc.getMethod("autoLogin").invoke(null);
            Object audioLevel = pc.getMethod("audioLevel").invoke(null);
            Map<String, Object> mainGrp = groupByConfig(root, "config.json");
            // 0.11.46：auto_login 从账号组搬进「维护」组，且只在开发者档渲染 —— 先在开发者档的
            // 「维护」组找，再回退用户档（兼容旧包 / 旧位置）。
            Map<String, Object> devGrp = rootDev == null ? null : groupByConfig(rootDev, "config.json");
            Map<String, Object> acPref = prefByTitle(devGrp, "启动时自动登录");
            if (acPref == null) {
                acPref = prefByTitle(mainGrp, "启动时自动登录");
            }
            Map<String, Object> alPref = prefByTitle(mainGrp, "在线播放音质");
            String acJson = asStr(acPref == null ? null : acPref.get("default_value"));
            String alJson = asStr(alPref == null ? null : alPref.get("default_value"));
            List<String> p = new ArrayList<>();
            if (!"true".equalsIgnoreCase(acJson) && !"false".equalsIgnoreCase(acJson)) {
                p.add("JSON 里 auto_login 默认值不是布尔：" + acJson);
            } else if (!acJson.equalsIgnoreCase(String.valueOf(autoLogin))) {
                p.add("autoLogin()=" + autoLogin + " 与 JSON 默认值 " + acJson + " 不一致");
            }
            if (!LEVELS.contains(alJson)) {
                p.add("JSON 里 audio_level 默认值不在白名单：" + alJson);
            } else if (!alJson.equals(String.valueOf(audioLevel))) {
                p.add("audioLevel()=" + audioLevel + " 与 JSON 默认值 " + alJson + " 不一致");
            }
            ok97 = p.isEmpty();
            detail97 = "autoLogin()=" + autoLogin + "（JSON " + acJson + "）, audioLevel()=" + audioLevel
                    + "（JSON " + alJson + "）" + (p.isEmpty() ? "" : "；" + join(p));
        } catch (Throwable t) {
            detail97 = "反射读取 PluginConfig 失败：" + oneLine(t);
        }
        rep.check("H9.7", "运行期配置快照 autoLogin()/audioLevel() 与 preference_config.json 默认值一致且在白名单内",
                ok97, detail97);

        // ---------- H9.11 配置「改值跟随」：宿主 get 的 default 决定类型（0.10.1 回归断言）
        // 背景：宿主 ConfigHelper.get(key, default) 按 default 的运行时类型分派，传 null 永远回默认值。
        // 这里模拟配置页改值（桩 set → listener → 插件 refresh），断言运行期快照真的跟着变。
        String detail911;
        boolean ok911 = false;
        try {
            Class<?> pc = Class.forName("com.example.netease.cfg.PluginConfig", false, pcl);
            Object beforeLevel = pc.getMethod("audioLevel").invoke(null);
            Object beforeBytes = pc.getMethod("audioCacheBytes").invoke(null);
            ConfigManager cm = WorkshopApi.manager().createConfigManager("com.example.netease");
            ConfigHelper main = cm.getConfig("config.json");
            String target = "standard".equals(String.valueOf(beforeLevel)) ? "exhigh" : "standard";
            main.set("audio_level", target);
            main.set("audio_cache_gb", "0");
            long cfgDeadline = System.currentTimeMillis() + 5000L;
            String seen = "（未观察到变化）";
            while (System.currentTimeMillis() < cfgDeadline) {
                Object al = pc.getMethod("audioLevel").invoke(null);
                Object cb = pc.getMethod("audioCacheBytes").invoke(null);
                seen = "audioLevel()=" + al + " / audioCacheBytes()=" + cb;
                if (target.equals(String.valueOf(al)) && cb instanceof Number n && n.longValue() == 0L) {
                    ok911 = true;
                    break;
                }
                sleep(200);
            }
            // 还原：后面的断言不许被这次改值污染
            main.set("audio_level", String.valueOf(beforeLevel));
            long beforeGb = beforeBytes instanceof Number n ? n.longValue() / (1024L * 1024L * 1024L) : 10L;
            main.set("audio_cache_gb", String.valueOf(beforeGb));
            detail911 = "改 audio_level=" + target + " + audio_cache_gb=0 → " + seen
                    + "（已还原 " + beforeLevel + " / " + beforeGb + " GB）";
        } catch (Throwable t) {
            detail911 = "配置改值跟随断言异常：" + oneLine(t);
        }
        rep.check("H9.11", "配置页改值真的进了运行期快照（宿主 get 按 default 类型解析，传 null 会永远回默认值）",
                ok911, detail911);

        // ---------- H9.14 展示行回写必须幂等（0.11.31 / 坑 41：自激写盘死循环）
        //  背景：写 current_account 会惊动宿主配置变更回调 → PluginConfig.onConfigChanged →
        //  账号组钩子 SmsLogin.onAccountConfigChanged() → refreshAccountRow() → 又写 current_account。
        //  0.11.30 把需求⑤ 的展示行改成「无条件回写」、拆掉了 0.11.26 时代「验证码键为空就返回」的老闸，
        //  于是这条路在真机上打成了死循环：日志每秒上千行 `[cfg] 配置已变更：account_cfg.json` +
        //  `[sms] 展示行已清空（未登录）`，宿主 CPU 累计 283.53 s，只能 Stop-Process 强停。
        //  判据：先埋一个陈旧行值，再连调钩子 200 次 —— 写盘次数必须有界（≤ 2：写一次真相、之后同值不写），
        //  且行值必须收敛到真相（未登录 = 空串）。没有这两处 early-return 时，200 次调用 = 200 次写。
        String detail914;
        boolean ok914 = false;
        try {
            ConfigManager cm914 = WorkshopApi.manager().createConfigManager("com.example.netease");
            StubConfigHelper acctH914 = (StubConfigHelper) cm914.getConfig("account_cfg.json");
            acctH914.set("current_account", "陈旧值（探针埋的）");
            acctH914.resetSetCount();
            Method hook914 = Class.forName("com.example.netease.svc.SmsLogin", false, pcl)
                    .getMethod("onAccountConfigChanged");
            for (int i = 0; i < 200; i++) {
                hook914.invoke(null);
            }
            int writes914 = acctH914.setCount();
            String now914 = String.valueOf(acctH914.get("current_account", ""));
            ok914 = writes914 <= 2 && now914.isEmpty();
            detail914 = "连调 onAccountConfigChanged() 200 次 ⇒ 写盘 " + writes914 + " 次（上限 2）、行值「" + now914
                    + "」" + (now914.isEmpty() ? "（未登录真相，正确）" : "（应为空串：未登录）");
        } catch (Throwable t) {
            detail914 = "展示行幂等断言异常：" + oneLine(t);
        }
        rep.check("H9.14", "展示行回写幂等：连调账号组变更钩子 200 次，写盘 ≤ 2 次且收敛到未登录真相（0.11.31 / 坑 41 的自激写盘循环不许回潮）",
                ok914, detail914);

        // ---------- H9.12 退役不留痕（0.11.10）：临时探针组（0.11.7 的 P-2..P-5）不得回潮
        //  探针是**临时验收设施**——docs/00 §7 表行与 §8 布局行都写明「验收通过后本组与这四个静态入口
        //  一起删除」；四条管线的验收证据齐备后，0.11.10 整组退役
        //  （登记见 docs\51-W1c探针组退役-0.11.10.md）。与 H9.2 同构的**反向**断言：
        //  配置树 / 接线 / 控件 / 插件类方法 / 两个被删的 svc 类，五处都要干净。
        List<String> probeBack = new ArrayList<>();
        if (cfgNames.contains("probe.json")) {
            probeBack.add("配置树里仍有 config=probe.json 的组");
        }
        for (Click c : all) {
            if (c.target != null && c.target.matches(".*\\.probeP[2-5]$")) {
                probeBack.add("接线回潮：" + c.cfg + "/" + c.target);
            }
            if (c.title != null && c.title.matches("^P-[2-5].*")) {
                probeBack.add("控件回潮：" + c.cfg + "/「" + c.title + "」");
            }
            if (c.group != null && c.group.contains("探针") && !probeBack.contains("分组回潮：" + c.cfg + "/「" + c.group + "」")) {
                probeBack.add("分组回潮：" + c.cfg + "/「" + c.group + "」");
            }
        }
        if (pluginClz != null) {
            for (String m : new String[]{"probeP2", "probeP3", "probeP4", "probeP5"}) {
                if (declaredMethodExists(pluginClz, m)) {
                    probeBack.add("NeteasePlugin 仍有方法 " + m + "()");
                }
            }
        }
        for (String cls : new String[]{"com.example.netease.svc.ProbeWindow", "com.example.netease.svc.ProbeCover"}) {
            try {
                Class.forName(cls, false, pcl);
                probeBack.add("已退役的类仍可加载：" + cls);
            } catch (ClassNotFoundException expected) {
                // 期望路径：类应随探针组一起消失
            } catch (Throwable t) {
                probeBack.add("加载 " + cls + " 时异常：" + oneLine(t));
            }
        }
        rep.check("H9.12", "退役不留痕：无 probe.json 组 / 无 probeP2..P5 接线与 P-2..P-5 控件 / 插件类无这四个方法 / svc.ProbeWindow|ProbeCover 不可加载（0.11.10 起不该有）",
                probeBack.isEmpty(),
                probeBack.isEmpty()
                        ? ("五处检查 0 命中（配置 " + cfgNames.size() + " 组 / 接线 " + all.size() + " 个）")
                        : join(probeBack));

        // ---------- H9.8 / H9.9 幂等安全入口真调用（不联网、不开窗、不改外部文件）
        if (pluginClz != null) {
            String d8;
            boolean ok8;
            try {
                pluginClz.getMethod("logout").invoke(null);
                Class<?> as = Class.forName("com.example.netease.svc.AccountService", false, pcl);
                Object loggedIn = as.getMethod("loggedIn").invoke(null);
                Object line = as.getMethod("statusLine").invoke(null);
                boolean okLine = line != null && !String.valueOf(line).trim().isEmpty();
                ok8 = Boolean.FALSE.equals(loggedIn) && okLine;
                d8 = "logout() 无异常；loggedIn()=" + loggedIn + "，statusLine()=\"" + line + "\""
                        + (okLine ? "" : "（状态文案为空）");
            } catch (Throwable t) {
                ok8 = false;
                d8 = "logout() 抛异常：" + oneLine(t);
            }
            rep.check("H9.8", "未登录态调用 logout() 不抛异常且状态回到「未登录」（UI 线程可直接调的承诺）",
                    ok8, d8);

            List<String> callBad = new ArrayList<>();
            // 0.5.0 瘦身版：clearCache / retagCurrent 已随歌词·匹配·打标功能删除；
            // 0.11.7 起 clientStatus() 随客户端接入一起删除；
            // 0.11.35（用户 m00001 问题1）：accountStatus() 删除，只读入口换成 currentAccount()
            // （它只**开一个只读窗**：窗口内容取瞬时登录态，不发起登录、不联网、不碰本机客户端；
            // 窗体登记在 UiRegistry 里，由 stop() 的 disposeAll() 收尾）。
            for (String name : new String[]{"currentAccount"}) {
                try {
                    pluginClz.getMethod(name).invoke(null);
                } catch (Throwable t) {
                    callBad.add(name + "() 抛异常：" + oneLine(t));
                }
            }
            // 「不抛异常」本身不构成证据：open() 里 catch (Throwable) 会把构造途中的异常吞掉，
            // 窗口一个都没建起来也照样返回（0.11.35 真机就是如此）。这里补「窗口真被建起来」的正面证据。
            flushEdt();
            String winText = "";
            try {
                Class<?> winClz = Class.forName("com.example.netease.ui.AccountStatusWindow", false, pcl);
                Object txt = winClz.getMethod("textForTest").invoke(null);
                winText = txt == null ? "" : String.valueOf(txt);
            } catch (Throwable t) {
                callBad.add("AccountStatusWindow.textForTest() 调用失败：" + oneLine(t));
            }
            if (winText.trim().isEmpty()) {
                callBad.add("账号明细窗没被建起来（0.11.35 真机 IAE 复现点）：currentAccount() 返回后 "
                        + "AccountStatusWindow.textForTest() 仍为空串"
                        + (java.awt.GraphicsEnvironment.isHeadless() ? "（JVM headless=true，窗口无法创建）" : ""));
            }
            rep.check("H9.9", "幂等只读入口 currentAccount() 可调用且不抛异常，且账号明细窗真被建起来"
                            + "（0.11.35：accountStatus 已删除；textForTest() 非空）",
                    callBad.isEmpty(),
                    callBad.isEmpty()
                            ? "入口正常返回；账号明细窗已建立，文案=" + winText.replace('\n', '｜')
                            : join(callBad));
        } else {
            rep.check("H9.8", "未登录态调用 logout() 不抛异常", false, PLUGIN_CLASS + " 未加载，无法调用");
            rep.check("H9.9", "幂等只读入口 currentAccount() 可调用且账号明细窗真被建起来",
                    false, PLUGIN_CLASS + " 未加载");
        }

        // ---------- H9.15 切档源资源不得与「被改写的主本」是同一个物理文件
        // 真机根因：DevMode 用自身类加载器读包内资源来切档，而 classpath 根就是装机 classes\ ⇒ 若把
        // RES_USER 写成 "/preference_config.json"，源资源与被 apply() 改写的主本（classes\preference_config.json）
        // 就是**同一个物理文件** ⇒ sameContent(源,目标) 恒真 ⇒ apply() 在「内容相同」分支提前返回、报成功
        // 却从不写盘（关掉开发者模式开关后按钮不隐藏）。
        // 为什么必须有这条：verify-spmod 的 P10.24 只比包内两份资源的字节（把常量改回同名照样全绿），
        // 只有运行期把「资源名 → 真实文件」与「改写目标路径」做同一性比对才钉得住这个根因。
        {
            final String wantUser = "/preference_config.user.json";
            final String wantDev = "/preference_config.dev.json";
            List<String> bad = new ArrayList<>();
            String evi = "(无)";
            Path target = pref;  // = <pluginDir>\classes\preference_config.json：apply() 真正改写的那个文件
            try {
                Class<?> dm = Class.forName("com.example.netease.core.DevMode", false, pcl);
                java.lang.reflect.Field fUser = dm.getDeclaredField("RES_USER");
                fUser.setAccessible(true);
                java.lang.reflect.Field fDev = dm.getDeclaredField("RES_DEV");
                fDev.setAccessible(true);
                String resUser = String.valueOf(fUser.get(null));
                String resDev = String.valueOf(fDev.get(null));

                if (!wantUser.equals(resUser)) {
                    bad.add("RES_USER=\"" + resUser + "\" ≠ \"" + wantUser + "\"：把它改回同名（\"/preference_config.json\"）"
                            + "即复现真机根因 —— 源资源与改写目标是同一个物理文件，sameContent(源,目标) 恒真，"
                            + "关开发者模式开关时 apply() 提前返回、从不写盘（复现：改 DevMode.java 该常量后跑本门禁）");
                }
                if (!wantDev.equals(resDev)) {
                    bad.add("RES_DEV=\"" + resDev + "\" ≠ \"" + wantDev + "\"：同上，源==目标即复现真机根因（切档写盘被跳过）");
                }

                Path pUser = resourceFile(dm, resUser, "RES_USER", bad);
                Path pDev = resourceFile(dm, resDev, "RES_DEV", bad);

                if (pUser != null && pDev != null && Files.isSameFile(pUser, pDev)) {
                    bad.add("两档资源指向同一个物理文件：" + pUser + " 与 " + pDev + "（isSameFile=true）—— "
                            + "档位源必须分档；用户档与开发者档内容不同，同文件即意味着切档读到的永远是同一份");
                }
                if (pUser != null && !Files.exists(pUser)) {
                    bad.add("用户档资源文件不存在：" + pUser + "（复现：包内漏发 preference_config.user.json）");
                }
                if (pDev != null && !Files.exists(pDev)) {
                    bad.add("开发者档资源文件不存在：" + pDev + "（复现：包内漏发 preference_config.dev.json）");
                }
                if (!Files.exists(target)) {
                    bad.add("改写目标不存在：" + target + "（harness 现场缺 classes\\preference_config.json，"
                            + "无法做同一性比对；apply() 的目标就是它）");
                } else {
                    if (pUser != null && Files.exists(pUser) && Files.isSameFile(pUser, target)) {
                        bad.add("真机根因复现：用户档资源 " + pUser + " 与改写目标 " + target + " 是同一个物理文件"
                                + "（isSameFile=true）⇒ sameContent(源,目标) 恒真 ⇒ apply() 提前返回、"
                                + "关开发者模式开关时从不写盘（UI 永远停在开发者档）");
                    }
                    if (pDev != null && Files.exists(pDev) && Files.isSameFile(pDev, target)) {
                        bad.add("开发者档资源 " + pDev + " 与改写目标 " + target + " 是同一个物理文件"
                                + "（isSameFile=true）：要写进去的与要写的同源 ⇒ 内容比对恒真、写盘被跳过");
                    }
                }
                evi = "RES_USER=\"" + resUser + "\" → " + pUser + "；RES_DEV=\"" + resDev + "\" → " + pDev
                        + "；改写目标 " + target
                        + "；isSameFile(用户档,目标)=" + sameFileQuiet(pUser, target)
                        + "；isSameFile(开发者档,目标)=" + sameFileQuiet(pDev, target)
                        + "；isSameFile(用户档,开发者档)=" + sameFileQuiet(pUser, pDev);
            } catch (Throwable t) {
                bad.add("反射读 DevMode.RES_USER/RES_DEV 失败：" + oneLine(t)
                        + "（复现：类名/字段名被改名，或 DevMode 未随包分发）");
                evi = oneLine(t);
            }
            rep.check("H9.15", "切档源资源与改写目标不是同一物理文件：DevMode.RES_USER=\"/preference_config.user.json\"、"
                            + "RES_DEV=\"/preference_config.dev.json\"，且两者都不是 classes\\preference_config.json"
                            + "（真机根因：源==目标 ⇒ sameContent 恒真 ⇒ 关开关从不写盘）",
                    bad.isEmpty(),
                    bad.isEmpty() ? evi : join(bad) + " ｜现场：" + evi);
        }

        // ---------- H9.16 账号明细窗「真身」的只读性（读真实组件树，不看 render()）
        // 为什么必须有这条：H9.9 只断 AccountStatusWindow.textForTest() 非空，而 textForTest() 走 render()
        // = join("\n", rows()) ⇒ 它**不读窗里的真实 JLabel**。产品承诺是「窗内只有只读标签 + 一颗关闭按钮，
        // 未登录只给一行『当前未登录』」，这条原本在自动门禁里零覆盖（0.11.35 真机正是「入口不抛异常、
        // 构造途中出岔子被 open() 的 catch(Throwable) 吞掉」那种失败形态：探针全绿而窗没建起来）。
        // headless 处置与 H9.9 保持一致：给提示并判 FAIL（窗口无法创建时这条本就无法验证，不许静默通过）。
        if (pluginClz != null) {
            final String winTitle = "当前账号";
            List<String> bad = new ArrayList<>();
            String evi = "(无)";
            try {
                flushEdt();
                // 幂等：H9.9 已开过 ⇒ 只置前；若已被关掉则这里重开（不依赖既有断言的副作用）
                pluginClz.getMethod("currentAccount").invoke(null);
                flushEdt();

                List<java.awt.Dialog> hit = new ArrayList<>();
                List<String> allWin = new ArrayList<>();
                for (java.awt.Window w : java.awt.Window.getWindows()) {
                    boolean disp;
                    try {
                        disp = w.isDisplayable();
                    } catch (Throwable ignored) {
                        disp = false;
                    }
                    if (w instanceof java.awt.Dialog) {
                        String title = ((java.awt.Dialog) w).getTitle();
                        allWin.add("\"" + title + "\"(displayable=" + disp + ")");
                        if (winTitle.equals(title) && disp) {
                            hit.add((java.awt.Dialog) w);
                        }
                    }
                }

                if (hit.isEmpty()) {
                    bad.add("「" + winTitle + "」明细窗没建起来：currentAccount() 返回后，Window.getWindows() 里没有"
                            + "标题=\"" + winTitle + "\"且 isDisplayable() 的 Dialog"
                            + (java.awt.GraphicsEnvironment.isHeadless()
                                    ? "（JVM headless=true，窗口无法创建）"
                                    : "（复现：AccountStatusWindow 构造途中出岔子会被 open() 的 catch(Throwable) 吞掉，"
                                      + "点「当前账号」该出现的东西不出现）")
                            + "；现场窗口=" + (allWin.isEmpty() ? "(无 Dialog)" : join(allWin)));
                } else if (hit.size() > 1) {
                    bad.add("「" + winTitle + "」明细窗重复建窗：" + hit.size() + " 个可显示 Dialog —— open() 必须幂等只置前"
                            + "（复现：连点两次「当前账号」按钮）；现场窗口=" + join(allWin));
                } else {
                    java.awt.Dialog d = hit.get(0);
                    List<java.awt.Component> comps = new ArrayList<>();
                    collectComponents(d, comps);
                    List<String> labels = new ArrayList<>();
                    List<String> buttons = new ArrayList<>();
                    List<String> editable = new ArrayList<>();
                    for (java.awt.Component c : comps) {
                        if (isEditableControl(c)) {
                            editable.add(c.getClass().getName());
                        } else if (c instanceof javax.swing.JLabel) {
                            String s = ((javax.swing.JLabel) c).getText();
                            if (s != null && !s.trim().isEmpty()) {
                                labels.add(s);
                            }
                        } else if (c instanceof javax.swing.AbstractButton) {
                            String s = ((javax.swing.AbstractButton) c).getText();
                            buttons.add(s == null ? "(null)" : s);
                        }
                    }

                    // ① 零可编辑控件（只读信息窗：不许出现可改字段）
                    if (!editable.isEmpty()) {
                        bad.add("窗内出现可编辑控件 " + editable.size() + " 个：" + join(editable)
                                + "（承诺：只读信息窗、零输入控件；复现：给 AccountStatusWindow 加任何文本框/下拉/勾选框）");
                    }
                    // ② 恰一颗「关闭」
                    if (buttons.size() != 1) {
                        bad.add("按钮数量=" + buttons.size() + "（必须恰一颗「关闭」）：" + buttons);
                    } else if (!"关闭".equals(buttons.get(0).trim())) {
                        bad.add("唯一按钮文本=\"" + buttons.get(0) + "\"（必须恰为「关闭」）");
                    }
                    // ③ 模板占位符不许漏进 UI
                    for (String s : labels) {
                        if (s.contains("{{ACCOUNT}}")) {
                            bad.add("标签里残留模板占位符 {{ACCOUNT}}：\"" + s + "\"（配置页的动态尾巴不许进窗）");
                        }
                    }
                    // ④ 标签与登录态一致（以真实组件树里的 JLabel 文本为准）
                    List<String> facts = new ArrayList<>(labels);
                    if (!facts.remove(winTitle)) {
                        bad.add("窗内没有标题标签「" + winTitle + "」：实际标签=" + labels);
                    } else if (facts.contains(winTitle)) {
                        bad.add("标题标签「" + winTitle + "」出现多次：" + labels);
                    }
                    Object loggedIn = null;
                    try {
                        Class<?> as = Class.forName("com.example.netease.svc.AccountService", false, pcl);
                        loggedIn = as.getMethod("loggedIn").invoke(null);
                    } catch (Throwable t) {
                        bad.add("读 AccountService.loggedIn() 失败，无法判定该给几行：" + oneLine(t));
                    }
                    if (Boolean.TRUE.equals(loggedIn)) {
                        String[] keys = {"账号名字：", "账号 ID：", "会员信息："};
                        if (facts.size() != 6) {
                            bad.add("已登录态：除标题外应恰为 6 个标签（3 键 + 3 值，键值紧邻成对），实际 "
                                    + facts.size() + " 个：" + facts);
                        } else {
                            for (int i = 0; i < 3; i++) {
                                if (!keys[i].equals(facts.get(i * 2))) {
                                    bad.add("已登录态：第 " + (i + 1) + " 组键标签=\"" + facts.get(i * 2)
                                            + "\"，应为 \"" + keys[i] + "\"（实际标签=" + facts + "）");
                                } else if (facts.get(i * 2 + 1).trim().isEmpty()) {
                                    bad.add("已登录态：\"" + keys[i] + "\" 后没有值标签（标签=" + facts + "）");
                                }
                            }
                        }
                    } else if (Boolean.FALSE.equals(loggedIn)) {
                        if (!facts.equals(List.of("当前未登录"))) {
                            bad.add("未登录态：除标题外应恰为一行「当前未登录」，实际 " + facts.size() + " 个标签：" + facts
                                    + "（未登录时不许给「账号名字：/账号 ID：/会员信息：」半截字段）");
                        }
                        for (String s : labels) {
                            for (String k : new String[]{"账号名字：", "账号 ID：", "会员信息："}) {
                                if (s.contains(k)) {
                                    bad.add("未登录态却出现账号明细标签 \"" + s + "\"（不许给半截字段）");
                                    break;
                                }
                            }
                        }
                    }
                    evi = "窗=JDialog(\"" + d.getTitle() + "\"，displayable=" + d.isDisplayable() + ")，组件 " + comps.size()
                            + " 个；标签=" + labels + "；按钮=" + buttons + "；可编辑控件=" + editable.size()
                            + "；loggedIn()=" + loggedIn;

                    // ⑤ 收尾必须把窗关掉（harness JVM 里不留窗）
                    try {
                        d.dispatchEvent(new java.awt.event.WindowEvent(d, java.awt.event.WindowEvent.WINDOW_CLOSING));
                        flushEdt();
                    } catch (Throwable t) {
                        bad.add("关窗 dispatchEvent(WINDOW_CLOSING) 抛异常：" + oneLine(t));
                    }
                    boolean still;
                    try {
                        still = d.isDisplayable();
                    } catch (Throwable ignored) {
                        still = true;
                    }
                    if (still) {
                        try {
                            d.dispose();   // 兜底：DISPOSE_ON_CLOSE 之外的路径
                            flushEdt();
                        } catch (Throwable t) {
                            bad.add("dispose() 兜底也失败：" + oneLine(t));
                        }
                        try {
                            still = d.isDisplayable();
                        } catch (Throwable ignored) {
                            still = false;
                        }
                    }
                    if (still) {
                        bad.add("关窗失败：dispatchEvent(WINDOW_CLOSING) + dispose() 之后 isDisplayable() 仍为 true"
                                + "（本窗是 DISPOSE_ON_CLOSE，必须关得掉；harness JVM 里不许留窗）");
                    }
                    evi = evi + "；关窗后 isDisplayable=" + still;
                }
            } catch (Throwable t) {
                bad.add("H9.16 组件树检查抛异常：" + oneLine(t));
            }
            rep.check("H9.16", "「当前账号」明细窗真身只读：恰一个可显示窗、零可编辑控件、恰一颗「关闭」、标签与登录态一致"
                            + "（未登录恰一行「当前未登录」/已登录三键各带值）、无 {{ACCOUNT}} 残留，且收尾能关掉",
                    bad.isEmpty(),
                    bad.isEmpty() ? evi : join(bad) + " ｜现场：" + evi);
        } else {
            rep.check("H9.16", "「当前账号」明细窗真身只读性（读真实组件树而非 render()）", false,
                    PLUGIN_CLASS + " 未加载，无法调用 currentAccount()");
        }

        // ---------- H9.10 start() 后插件自有线程正证据（H10.1 的阳性对照；空集永远「通过」是假证据）
        List<String> p3Threads = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (n != null && n.startsWith("netease-")) {
                p3Threads.add(n + (t.isDaemon() ? "(daemon)" : "(非 daemon!)"));
            }
        }
        boolean accountUp = p3Threads.stream().anyMatch(s -> s.startsWith("netease-account"));
        rep.check("H9.10", "start() 后观测到插件自有账号线程 netease-account（线程残留检查的阳性对照）",
                accountUp, "观测到的插件线程：" + (p3Threads.isEmpty() ? "(无)" : join(p3Threads)));
        info("插件 ID = " + pluginId + "；数据目录 = " + dataDir);
    }

    // ============================================================ H10（stop 之后）

    /** H10：线程残留 / RETAG_POOL shutdown / 凭据密文往返与篡改 / 明文扫描。 */
    static void phaseCredentials(Reporter rep, Path dataDir, ClassLoader pcl, Set<String> threadsBefore) {
        System.out.println();
        System.out.println("── H10 P3 生命周期与凭据（stop 之后） ─────────────────────────");

        // ---------- H10.1 插件线程残留（stop 后 netease-account / netease-retag 必须消失）
        List<String> leaked = new ArrayList<>();
        List<String> infra = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = String.valueOf(t.getName());
            if (n.startsWith("harness-") || threadsBefore.contains(n)) {
                continue;
            }
            boolean byName = n.startsWith("netease-");
            boolean byCl = ownedByPlugin(t, pcl);
            if (byName || byCl) {
                leaked.add(threadDump(t) + (byName ? "[名字 netease-*]" : "[contextClassLoader 属于插件]"));
            } else {
                infra.add(n);
            }
        }
        rep.check("H10.1", "stop() 后无插件自有线程残留（netease-* 与插件类加载器归属线程均为 0）",
                leaked.isEmpty(),
                leaked.isEmpty()
                        ? ("插件自有线程 0" + (infra.isEmpty() ? "" : "（另有非插件线程，不计入：" + join(infra) + "）"))
                        : ("残留：" + join(leaked) + (infra.isEmpty() ? "" : "；另有非插件线程 " + join(infra))));

        // ---------- H10.2 stop() 是终态：停之后再调「登录入口」，不得又冒出一个账号线程
        // 0.5.0 瘦身版：原 RETAG_POOL（打标线程池）随功能删除，这里改为对终态本身的回归门 ——
        // 0.4.0 修的病正是「停了又冒一个」（AccountService.stopped 门禁 + 有界 join，见 docs/00 §5）。
        // 0.11.7：客户端接入入口已删，改用原生登录入口（stop 后必须被门禁挡下、
        // 立即返回「账号线程不可用」而不建线程；本调用在 harness 里不发网络请求）。
        // 0.11.9：手机号 + 密码退役，同一门禁改由验证码入口 loginByCaptcha 承证（门禁在
        // AccountService 内部，与登录方式无关，故覆盖等价）。
        String d2;
        boolean ok2;
        int before = countPluginThreads("netease-");
        String callErr = null;
        String callRet = null;
        try {
            Class<?> as = Class.forName("com.example.netease.svc.AccountService", false, pcl);
            Object out = as.getMethod("loginByCaptcha", String.class, String.class)
                    .invoke(null, "10000000000", "000000");
            callRet = String.valueOf(out);
        } catch (Throwable t) {
            callErr = oneLine(t);
        }
        sleep(1500);
        int after = countPluginThreads("netease-");
        ok2 = (callErr == null) && after == 0 && before == 0;
        d2 = "stop() 后 loginByCaptcha()：" + (callErr == null ? "正常返回 " + callRet : "抛异常 " + callErr)
                + "；netease-* 线程数 " + before + " → " + after + "（1.5s 观察窗）";
        rep.check("H10.2", "stop() 后调用原生登录入口不再新建插件线程（stop() 是终态）", ok2, d2);

        // ---------- H10.9 退役不留痕：手机号 + 密码路径（0.11.9 删除）不得回潮
        // 编号避开 H10.3（「凭据密文往返」已占用，同一次运行里两处同名会被报表算重）。
        // 注意用 declaredMethodExists（getDeclaredMethod + 走父类）而不是 Class#getMethod：
        // 后者只看得见 public，包内可见的 passwordPayload 即使复活也会漏判（假阴性）。
        List<String> revived = new ArrayList<>();
        try {
            Class<?> as = Class.forName("com.example.netease.svc.AccountService", false, pcl);
            for (String m : new String[]{"loginByPassword", "loginCellphone"}) {
                if (declaredMethodExists(as, m, String.class, String.class)) {
                    revived.add("svc.AccountService#" + m);
                }
            }
            Class<?> api = Class.forName("com.example.netease.net.NeteaseApi", false, pcl);
            for (String m : new String[]{"loginByPassword", "loginCellphone", "passwordPayload"}) {
                if (declaredMethodExists(api, m, String.class, String.class)) {
                    revived.add("net.NeteaseApi#" + m);
                }
            }
            // 0.11.22：ui.LoginPrompt 整类已退役 ⇒ Class.forName 抛 ClassNotFoundException 是**期望态**
            // （该退役本身由 H10.10 正向判定），这里只在「类被复活」时才继续查 promptPassword。
            Class<?> lp = null;
            try {
                lp = Class.forName("com.example.netease.ui.LoginPrompt", false, pcl);
            } catch (ClassNotFoundException expected) {
                // 期望态：0.11.22 起该类不存在
            }
            if (lp != null && declaredMethodExists(lp, "promptPassword", String.class)) {
                revived.add("ui.LoginPrompt#promptPassword");
            }
            Class<?> np = Class.forName(PLUGIN_CLASS, false, pcl);
            if (declaredMethodExists(np, "loginPassword")) {
                revived.add(PLUGIN_CLASS + "#loginPassword");
            }
        } catch (Throwable t) {
            revived.add("反射失败：" + oneLine(t));
        }
        rep.check("H10.9", "0.11.9 已退役的「手机号 + 密码」入口在任何公开面上都不存在（不得回潮）",
                revived.isEmpty(),
                revived.isEmpty()
                        ? "七个入口（net×3 / svc×2 / ui×1 / plugin×1，含包内可见）逐一 getDeclaredMethod 查证：全部未找到"
                        : "发现回潮：" + join(revived));

        // ---------- H10.10 退役不留痕：ui.LoginPrompt（独立模态登录框）整类已删除（0.11.22）
        // 用户规格（m00161）：「登录不能弹新窗口」+「弹框要和手机号那行一样是宿主原生控件」⇒
        // 登录四步全落在 preference_config.json 账号组的原生控件上，该类连同 promptSms 一起退役。
        // 判定用正向存在性：Class.forName 抛 ClassNotFoundException = 通过（与 H10.9 的反向查证互补）。
        boolean lpGone;
        String lpDetail;
        try {
            Class.forName("com.example.netease.ui.LoginPrompt", false, pcl);
            lpGone = false;
            lpDetail = "类仍在（0.11.22 起应为不存在）";
        } catch (ClassNotFoundException expected) {
            lpGone = true;
            lpDetail = "Class.forName 抛 ClassNotFoundException：整类已删除";
        } catch (Throwable t) {
            lpGone = false;
            lpDetail = "反射异常（不算通过）：" + oneLine(t);
        }
        rep.check("H10.10", "0.11.22 退役的 ui.LoginPrompt（宿主窗口之上的独立模态登录框）整类不存在（不得回潮）",
                lpGone, lpDetail);

        // ---------- 凭据密文往返 / 篡改 / 清除
        final String canaryUid = "CANARY_P3_MUSICU_9f3a2b7c";
        final String canaryCsrf = "CANARY_P3_CSRF_5d1e4a";
        final String cookie = "MUSIC_U=" + canaryUid + "; __csrf=" + canaryCsrf;
        Path vaultFile = null;
        try {
            Class<?> cv = Class.forName("com.example.netease.svc.CookieVault", true, pcl);
            Method init = cv.getMethod("init", Path.class);
            Method save = cv.getMethod("save", String.class);
            Method load = cv.getMethod("load");
            Method clear = cv.getMethod("clear");
            Method exists = cv.getMethod("exists");
            Method file = cv.getMethod("file");
            init.invoke(null, dataDir);

            // ---------- H10.3 往返 + 落盘是 v1: 密文
            save.invoke(null, cookie);
            vaultFile = (Path) file.invoke(null);
            String fileText = (vaultFile != null && Files.isRegularFile(vaultFile))
                    ? new String(Files.readAllBytes(vaultFile), StandardCharsets.ISO_8859_1) : "";
            Object back = load.invoke(null);
            List<String> p3 = new ArrayList<>();
            if (vaultFile == null || !Files.isRegularFile(vaultFile)) {
                p3.add("account.json 未落盘");
            } else {
                if (vaultFile.getFileName().toString().equals("account.json") == false) {
                    p3.add("凭据文件名不是 account.json：" + vaultFile.getFileName());
                }
                if (!fileText.startsWith("v1:")) {
                    p3.add("落盘内容不以 v1: 开头（明文？）：" + fileText.substring(0, Math.min(24, fileText.length())));
                }
                if (fileText.contains(canaryUid) || fileText.contains(canaryCsrf)) {
                    p3.add("落盘内容含 canary 明文");
                }
            }
            if (!cookie.equals(back)) {
                p3.add("load() 与 save() 的值不一致：back=" + (back == null ? "null" : "长度 " + String.valueOf(back).length()));
            }
            rep.check("H10.3", "CookieVault save→load 往返一致；落盘为 v1: 密文且不含明文 cookie",
                    p3.isEmpty(),
                    p3.isEmpty() ? ("round-trip OK；account.json = " + Files.size(vaultFile) + " B，首 3 字符 v1:") : join(p3));

            // ---------- H10.4 篡改一位 → load()==null（AEAD 完整性），且文件不被删
            List<String> p4 = new ArrayList<>();
            if (vaultFile == null || !Files.isRegularFile(vaultFile)) {
                p4.add("无 account.json 可篡改");
            } else {
                byte[] b = Files.readAllBytes(vaultFile);
                int idx = -1;
                String b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
                for (int i = 8; i < b.length - 8; i++) {
                    if (b64.indexOf((char) b[i]) >= 0) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    p4.add("密文里找不到可篡改的 Base64 字符位");
                } else {
                    byte old = b[idx];
                    b[idx] = (old == 'A') ? (byte) 'B' : (byte) 'A';
                    Files.write(vaultFile, b);
                    Object bad = load.invoke(null);
                    boolean stillThere = (Boolean) exists.invoke(null);
                    p4.add("篡改位置 = 第 " + idx + " 字节（'" + (char) old + "' → '" + (char) b[idx] + "'）");
                    if (bad != null) {
                        p4.add("⚠ 篡改后 load() 仍返回非 null（完整性校验失效）");
                    }
                    if (!stillThere) {
                        p4.add("⚠ load() 把损坏的 account.json 删掉了（契约：只返回 null，不改不删）");
                    }
                }
            }
            boolean ok4 = !p4.isEmpty() && !p4.get(0).startsWith("⚠") && p4.stream().noneMatch(s -> s.startsWith("⚠"));
            rep.check("H10.4", "篡改 account.json 一个 Base64 字符位 → load() 返回 null 且不删文件（AES-GCM 完整性真的生效）",
                    ok4, join(p4));

            // ---------- H10.5 重新 save 后恢复（损坏文件不该让插件永久失能）
            List<String> p5 = new ArrayList<>();
            save.invoke(null, cookie);
            Object again = load.invoke(null);
            if (!cookie.equals(again)) {
                p5.add("重新 save() 后 load() 仍失败");
            }
            rep.check("H10.5", "篡改后重新 save() 能恢复（load() 再次得到原值）", p5.isEmpty(),
                    p5.isEmpty() ? "恢复 OK" : join(p5));

            // ---------- H10.6 clear() → account.json 删除、account.key 保留、load()==null
            List<String> p6 = new ArrayList<>();
            clear.invoke(null);
            boolean existsAfter = (Boolean) exists.invoke(null);
            Object afterClear = load.invoke(null);
            Path keyFile = dataDir.resolve("account.key");
            if (existsAfter || (vaultFile != null && Files.exists(vaultFile))) {
                p6.add("clear() 后 account.json 仍存在");
            }
            if (afterClear != null) {
                p6.add("clear() 后 load() 仍返回非 null");
            }
            if (!Files.isRegularFile(keyFile)) {
                p6.add("clear() 把 account.key 也删了（密钥应保留，否则重登后旧密文无法解）");
            }
            rep.check("H10.6", "clear() 删除 account.json、保留 account.key，load() 返回 null", p6.isEmpty(),
                    p6.isEmpty() ? ("account.json 已删；account.key " + Files.size(keyFile) + " B 仍在") : join(p6));

            // ---------- H10.7 日志脱敏（注入 canary，断言日志文件里找得到脱敏标记、找不到明文）
            Class<?> pl = Class.forName("com.example.netease.core.PluginLog", true, pcl);
            Object sanitized = pl.getMethod("sanitize", String.class).invoke(null, cookie);
            pl.getMethod("i", String.class, String.class).invoke(null, "harness-canary", "cookie=" + cookie);
            sleep(400);
            String logText = readAllLogs(dataDir);
            List<String> p7 = new ArrayList<>();
            String s = String.valueOf(sanitized);
            if (s.contains(canaryUid) || s.contains(canaryCsrf)) {
                p7.add("sanitize() 没打码：\"" + s + "\"");
            }
            if (!s.contains("****")) {
                p7.add("sanitize() 结果没有 **** 占位：\"" + s + "\"");
            }
            if (logText == null || !logText.contains("harness-canary")) {
                p7.add("注入的日志行没写进日志文件（无法验证写盘路径）");
            } else if (logText.contains(canaryUid) || logText.contains(canaryCsrf)) {
                p7.add("⚠ 日志文件里出现 cookie canary 明文");
            }
            rep.check("H10.7", "PluginLog 脱敏：sanitize() 打码，且注入的 cookie 明文不出现在日志文件里",
                    p7.isEmpty(), p7.isEmpty() ? ("sanitize(\"" + cookie + "\") = \"" + s + "\"") : join(p7));

            // ---------- H10.8 数据目录全量扫描（不只是日志：配置/缓存/报表都算）
            Scan scan = scanNoPlaintext(dataDir, canaryUid, canaryCsrf);
            rep.check("H10.8", "插件数据目录全量扫描：无 MUSIC_U=<非*>、无 canary 明文（" + scan.files + " 个文件）",
                    scan.hits.isEmpty(),
                    scan.hits.isEmpty() ? ("扫描 " + scan.files + " 个文件，命中 0")
                            : abbrev(scan.hits, 6));
        } catch (Throwable t) {
            rep.check("H10.3", "CookieVault save→load 往返一致", false, "反射链路失败：" + oneLine(t));
            rep.check("H10.4", "篡改密文 → load() 返回 null", false, "反射链路失败：" + oneLine(t));
            rep.check("H10.5", "篡改后重新 save() 可恢复", false, "反射链路失败：" + oneLine(t));
            rep.check("H10.6", "clear() 删 account.json 保留 account.key", false, "反射链路失败：" + oneLine(t));
            rep.check("H10.7", "PluginLog 脱敏", false, "反射链路失败：" + oneLine(t));
            rep.check("H10.8", "数据目录无明文 cookie", false, "反射链路失败：" + oneLine(t));
        }
    }

    // ------------------------------------------------------------ 扫描工具

    private static final class Scan {
        int files;
        final List<String> hits = new ArrayList<>();
    }

    /** 递归扫描数据目录：MUSIC_U=<非*>、canary 明文、以及「名叫 account.json 却不是 v1: 密文」。 */
    private static Scan scanNoPlaintext(Path root, String canaryUid, String canaryCsrf) {
        Scan sc = new Scan();
        if (root == null || !Files.isDirectory(root)) {
            sc.hits.add("数据目录不存在：" + root);
            return sc;
        }
        Pattern plain = Pattern.compile("MUSIC_U=[^*\\s]");
        List<Path> all = new ArrayList<>();
        try (Stream<Path> st = Files.walk(root)) {
            st.filter(Files::isRegularFile).forEach(all::add);
        } catch (Throwable t) {
            sc.hits.add("遍历失败：" + oneLine(t));
            return sc;
        }
        for (Path p : all) {
            sc.files++;
            String rel = root.relativize(p).toString();
            String body;
            try {
                body = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            } catch (Throwable t) {
                sc.hits.add(rel + "（读失败：" + oneLine(t) + "）");
                continue;
            }
            Matcher m = plain.matcher(body);
            if (m.find()) {
                sc.hits.add(rel + " 命中明文 cookie 形态：" + body.substring(Math.max(0, m.start() - 8),
                        Math.min(body.length(), m.end() + 24)).replaceAll("\\R", " "));
            }
            if (body.contains(canaryUid)) {
                sc.hits.add(rel + " 含 canary MUSIC_U 明文值");
            }
            if (body.contains(canaryCsrf)) {
                sc.hits.add(rel + " 含 canary __csrf 明文值");
            }
            if (p.getFileName().toString().equals("account.json") && !body.startsWith("v1:")) {
                sc.hits.add(rel + " 名叫 account.json 但不是 v1: 密文（配置/凭据撞名？）");
            }
        }
        return sc;
    }

    /** 把数据目录下 logs\*.log 全读出来（按文件名排序拼接）。 */
    private static String readAllLogs(Path dataDir) {
        if (dataDir == null) {
            return null;
        }
        Path logs = dataDir.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> st = Files.list(logs)) {
            st.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log"))
                    .forEach(files::add);
        } catch (Throwable t) {
            return null;
        }
        files.sort(Collections.reverseOrder());
        for (Path p : files) {
            try {
                sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
            } catch (Throwable t) {
                // 忽略：日志可能正在轮转
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 线程残留诊断（P3 线程泄漏检查的失败证据）：名字 + 状态 + 栈顶 5 帧。
     *
     * <p>只用于把失败说清楚（「谁把它钉住了」），不参与判定：线程是否残留仍由名字 / 类加载器归属决定。</p>
     */
    static String threadDump(Thread t) {
        if (t == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(t.getName());
        sb.append('[').append(t.getState()).append(t.isDaemon() ? ",daemon" : ",非 daemon");
        sb.append(",int=").append(t.isInterrupted() ? "已置位" : "未置位").append(']');
        StackTraceElement[] st = t.getStackTrace();
        int n = Math.min(st.length, 14);
        for (int i = 0; i < n; i++) {
            sb.append("\n               at ").append(st[i]);
        }
        if (st.length > n) {
            sb.append("\n               … 共 ").append(st.length).append(" 帧");
        }
        return sb.toString();
    }

    /** 数一下当前存活的插件自有线程（名字前缀匹配）——「停了又冒一个」回归门的观测量。 */
    private static int countPluginThreads(String prefix) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name != null && name.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** 线程是否由插件类加载器派生（contextClassLoader 的祖先链里有 pcl）。 */
    private static boolean ownedByPlugin(Thread t, ClassLoader pcl) {
        if (t == null || pcl == null) {
            return false;
        }
        ClassLoader c = t.getContextClassLoader();
        for (ClassLoader x = c; x != null; x = x.getParent()) {
            if (x == pcl) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 类（含父类）里是否存在<b>声明</b>的该方法。
     *
     * <p>用 {@code getDeclaredMethod} 而不是 {@code Class#getMethod}：后者只返回 public，
     * 于是「包内可见的方法复活」这种回潮会被漏判 —— 退役断言宁可严，不可松。</p>
     */
    private static boolean declaredMethodExists(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    /** 供 HarnessMain 打印：本次 H9/H10 用到的期望映射（排错时一眼看出接线表）。 */
    static Map<String, String> expectedEntryMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : ENTRY_METHODS) {
            m.put(s, PLUGIN_CLASS + "." + s);
        }
        return m;
    }
}
