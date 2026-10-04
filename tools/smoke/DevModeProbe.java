package com.example.netease.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 开发者模式离线探针（0.11.30 用户需求③：新增「开发者模式」开关，关闭时普通用户看不到开发者功能）。
 *
 * <p>本探针**不碰宿主、不联网、不装包**：在一个临时装机目录上把 {@link DevMode} 的开 / 关跑一遍，
 * 逐字节比对「装机目录里的 classes\preference_config.json 到底被写成了哪一档」，并把
 * {@code NeteasePlugin.devGate(String)}（运行时第二层闸）用反射调一次 —— 需求③ 的两层
 * （配置文件层 + 运行时层）都在这里留证据。</p>
 *
 * <p>判定四件事：</p>
 * <ol>
 *   <li>随包两档资源可读，且开发者档 = 用户档 + 14 行开发者入口（在线播放（实验）4 行 /
 *       三条管线总览 / 封面完成度 / 歌词完成度 / 日志级别 / 音质档位状态 / 打开数据目录 /
 *       重建曲库映射 / 测试网络连通性 / 启动时自动登录 / 启用插件 —— 0.11.45 起「启用插件」、
 *       0.11.46 起「启动时自动登录」也只留开发者档）；</li>
 *   <li>关 ⇒ 装机文件逐字节 = 用户档；开 ⇒ 逐字节 = 开发者档；关回去 ⇒ 又回到用户档（可逆）；</li>
 *   <li>幂等：同值重复 {@code setOn} 不重复写盘（mtime 不动）、不留 {@code *.devtool.tmp} 临时文件；
 *       装机目录拿不到（{@code init(null)}）⇒ 一切调用安全 no-op、已有档位不被破坏；</li>
 *   <li>运行时闸：{@code devGate} 在开关打开时放行、关闭时拒绝（第二层保险，防绕过配置页的调用）；</li>
 *   <li><b>0.11.35 真机根因：自读 ⇒ 「关」方向从不写盘</b>。装机布局下 {@code /preference_config.json}
 *       解析到的就是被改写目标自己；早期实现拿它当用户档源 ⇒ {@code sameContent(源, 目标)} 恒真，
 *       「关」方向永远提前 return（还打「结果=成功」）。本探针在**复现装机布局**的前提下验：
 *       ①两个方向都真的换字节（用户档 → 开发者档、开发者档 → 用户档主本）；
 *       ②{@code classes\preference_config.user.json} 存在且与用户档逐字节相同；
 *       ③{@code DevMode.isSelfRead(...)} 对「源 == 目标」判真、对两个主本判假；
 *       ④把 {@code target} 反射改指到用户档主本本身（复刻坏布局）后走用户那侧关开关 ⇒ {@code apply()}
 *       必须返回 false、日志按「结果=失败」上报、源文件一字未动。这条配了阴性对照：把 {@code RES_USER}
 *       改回 {@code /preference_config.json} 必须变红（否则门禁不算有效）。</li>
 *   <li><b>0.11.35（用户 m00001 问题1）</b>：账号行不再承载登录态 —— 它是 {@code button}
 *       「当前账号」→ {@code NeteasePlugin.currentAccount}，点开由 {@code ui.AccountStatusWindow}
 *       才展示「账号名字 / 账号 ID / 会员信息」（未登录 ⇒ 单行「当前未登录」）。0.11.32–0.11.34 的
 *       {@code {{ACCOUNT}}} 占位机制与 {@code DevMode.setAccountText(String)} 一并退役，所以本探针
 *       断言的是**反向事实**：两档资源与写盘结果里都不许出现 {@code {{ACCOUNT}}} 占位、也不许出现
 *       「当前账号：」这种常驻登录态文本（用户原话「不要常驻显示」）。</li>
 * </ol>
 *
 * <p>用法（仓库根；<b>输出目录必须在沙箱目录之外</b> —— 见下面的坑）：</p>
 * <pre>
 * javac --release 21 -encoding UTF-8 -cp "tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar" ^
 *       -d build\probe-e\cp &lt;全量 src\main\java 的 java 文件&gt; tools\smoke\DevModeProbe.java
 * java -Dstdout.encoding=UTF-8 -cp "build\probe-e\cp;build\probe-e\sandbox\plugin-com.example.netease-0.11.35\classes;tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar;tools\.cache\kotlin-stdlib-host.jar" ^
 *      com.example.netease.core.DevModeProbe build\probe-e\sandbox
 * </pre>
 *
 * <p><b>⚠️ classpath 上挂的是「沙箱 classes\」，不是 {@code src\main\resources}</b>（0.11.35 起）：探针
 * 自己把仓库里的三份档位文件拷进沙箱 {@code classes\}，让**被改写目标文件所在的目录本身就是 classpath 根**
 * —— 与真机完全一致（真机上插件类加载器的 classpath 根就是装机 {@code classes\}）。旧用法把
 * {@code src\main\resources} 挂在 classpath 前，「源资源」与「被改写目标」于是永远不是同一物理文件，
 * 正好把 0.11.35 的自读缺陷（{@code RES_USER} 解析到目标自己 ⇒ 「关」方向空转却报成功）盖住了。
 * 〇节的前几条断言就是钉这条布局的。第二个命令行参数（缺省 {@code src/main/resources}）只当**拷贝来源**
 * 用，不参与 classpath 解析。</p>
 *
 * <p><b>坑：编译产物不能放进沙箱目录里</b>。{@code main} 第一件事就是 {@code deleteTree(root)} 把沙箱
 * （默认 {@code build\devmode-probe}）整个清空 —— 若把 {@code -d} 指到 {@code build\devmode-probe\classes}，
 * 探针会在跑到第一行断言前**把自己刚编出来的 class 删掉**，然后报
 * {@code NoClassDefFoundError: com/example/netease/core/PluginLog}（看着像 classpath 写错，其实是自杀）。
 * javac 那一侧还会 exit 0、目录里也确实有 145 个 class —— 只有运行起来才炸。</p>
 *
 * <p><b>kotlin-stdlib-host.jar 必须挂在运行时 classpath 上</b>：反射调 {@code devGate} 的「拒绝」分支会走
 * {@code Notifier.warn} → {@code WorkshopApi$Ui$ToastType}，而后者是 Kotlin 类，缺 stdlib 会抛
 * {@code NoClassDefFoundError: kotlin/enums/EnumEntriesKt}（宿主运行时自带的库，离线探针得自己补）。
 * 若该 jar 不在，探针仍会把这条判为 SKIP 而不是 FAIL。</p>
 */
public final class DevModeProbe {

    private static final String DEV_DEGRADED = "开发者档";

    /** 被改写的那个资源名（装机 {@code classes\preference_config.json} 本身）——探针只用来做「自读」取证。 */
    private static final String TARGET_RES = "/preference_config.json";
    /** 用户档主本（0.11.35 起 DevMode.RES_USER 指的就是它）——它与目标必须是**不同**的物理文件。 */
    private static final String USER_MASTER = "/preference_config.user.json";
    /** 开发者档（DevMode.RES_DEV）——同样必须与目标物理分离。 */
    private static final String DEV_MASTER = "/preference_config.dev.json";
    /** 真机上该随开发者档一起出现的那一行（关掉开关后它必须消失）。 */
    private static final String DEV_ROW_TEXT = "在线模式自检";

    /** 0.11.35：账号行的标题（固定就是这四个字，点开才展示账号信息）。 */
    private static final String ACCOUNT_TITLE = "当前账号";
    /** 0.11.35：账号行的接线（点开 ui.AccountStatusWindow）。 */
    private static final String ACCOUNT_CLICK = "com.example.netease.NeteasePlugin.currentAccount";
    /** 0.11.32–0.11.34 的常驻登录态前缀 —— 用户 m00001 要求页面**不得**再出现它。 */
    private static final String ACCOUNT_INLINE_PREFIX = "当前账号：";

    /** 用户裁定的开发者入口（m00568；0.11.35 起 12 行，0.11.45 加「启用插件」、0.11.46 加「启动时自动登录」= 14 行）——按标题核对，不看顺序。 */
    private static final String[] DEV_ROWS = {
            "在线模式自检", "试播一首", "试播本地文件", "装拦截器",
            "三条管线总览", "封面完成度", "歌词完成度",
            "日志级别", "音质档位状态", "打开数据目录", "重建曲库映射", "测试网络连通性",
            "启用插件", "启动时自动登录"
    };

    private static int pass;
    private static int fail;
    private static int skip;
    private static final List<String> failures = new ArrayList<>();
    /** 反射调 devGate 时的失败原因（缺宿主 Kotlin 运行时等）——只降级为 SKIP，不判 FAIL。 */
    private static String invokeGateError;

    private DevModeProbe() {
    }

    private static Boolean invokeGate(Method gate) {
        try {
            return (Boolean) gate.invoke(null, "探针");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            invokeGateError = cause.toString();
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : "build/devmode-probe").toAbsolutePath();
        Path repoRes = Paths.get(args.length > 1 ? args[1] : "src/main/resources").toAbsolutePath();
        guardSandbox(root);
        deleteTree(root);
        Path pluginDir = root.resolve("plugin-com.example.netease-0.11.35");
        Path classes = pluginDir.resolve("classes");
        Files.createDirectories(classes);
        PluginLog.init(root.resolve("logs"));

        System.out.println("── 开发者模式离线探针（0.11.30 需求③ + 0.11.35 账号行按钮 + 自读缺陷） " + root + " ──");

        Path target = classes.resolve("preference_config.json");
        Path userMaster = classes.resolve("preference_config.user.json");
        Path devMaster = classes.resolve("preference_config.dev.json");

        // ---------- 〇、装机布局复现（本缺陷的舞台）
        // 真机布局：插件类加载器的 classpath 根**就是** classes\ —— 被改写的 preference_config.json
        // 与随包资源在同一个目录、同一个物理文件。这里把仓库源目录（缺省 src\main\resources，正是
        // tools\build.py 里「资源整目录平铺进 classes\」的来源）的三份档位文件拷进沙箱 classes\，再由
        // 调用方把该目录挂到探针 classpath 上（见文件头用法）。
        // ⚠️ 旧版探针把 src\main\resources 直接挂在 classpath 前 ⇒「源资源」解析到仓库里**另一份**文件，
        //    源与目标永远不是同一物理文件 —— 正好把 0.11.35 的自读缺陷盖住。本节第三、四条断言就是钉这个。
        section("〇、装机布局复现（目标文件所在目录即 classpath 根 —— 真机布局，本缺陷的舞台）");
        byte[] repoUser = readFile(repoRes.resolve("preference_config.json"));
        byte[] repoMaster = readFile(repoRes.resolve("preference_config.user.json"));
        byte[] repoDev = readFile(repoRes.resolve("preference_config.dev.json"));
        check("仓库源目录三份档位文件都可读（用户档 / 用户档主本 / 开发者档）",
                repoUser != null && repoMaster != null && repoDev != null,
                (repoUser == null ? -1 : repoUser.length) + " / " + (repoMaster == null ? -1 : repoMaster.length)
                        + " / " + (repoDev == null ? -1 : repoDev.length) + " 字节（" + repoRes + "）");
        if (repoUser == null || repoMaster == null || repoDev == null) {
            summary();
            return;
        }
        check("用户档主本与装箱用户档逐字节相同（单一事实来源 src/main/resources 未漂移）",
                Arrays.equals(repoMaster, repoUser), sha12(repoMaster) + " == " + sha12(repoUser));
        Files.write(target, repoUser);
        Files.write(userMaster, repoMaster);
        Files.write(devMaster, repoDev);
        check("沙箱 classes\\ 已复现装机三件套（目标 + 用户档主本 + 开发者档）",
                Files.isRegularFile(target) && Files.isRegularFile(userMaster) && Files.isRegularFile(devMaster),
                "目标 " + Files.size(target) + " / 主本 " + Files.size(userMaster) + " / 开发者档 "
                        + Files.size(devMaster) + " 字节");
        check("classpath 根 = 目标文件所在目录：/preference_config.json 解析到的物理文件 == 被改写目标",
                sameFile(resourcePath(TARGET_RES), target),
                "资源 " + resourceOrigin(TARGET_RES) + " ／ 目标 " + target);
        check("修复点实证：/preference_config.user.json 与目标**不是**同一物理文件（源≠目标）",
                resourcePath(USER_MASTER) != null && !sameFile(resourcePath(USER_MASTER), target),
                "资源 " + resourceOrigin(USER_MASTER) + " ／ 目标 " + target);
        check("修复点实证：/preference_config.dev.json 与目标**不是**同一物理文件（源≠目标）",
                resourcePath(DEV_MASTER) != null && !sameFile(resourcePath(DEV_MASTER), target),
                "资源 " + resourceOrigin(DEV_MASTER) + " ／ 目标 " + target);

        // ---------- 一、两档资源 + 用户档主本
        section("一、随包两档资源 + 用户档主本");
        byte[] packedUser = resource(TARGET_RES);   // 装箱的那份 = 装机初值（切档后它就会被改写）
        byte[] user = resource(USER_MASTER);        // 用户档主本：本探针永不改写它 —— 它就是「源」
        byte[] dev = resource(DEV_MASTER);          // 开发者档：同理
        // 0.11.35：写盘就是「资源原样落盘」——不再有任何占位替换，所以期望字节 = 资源字节。
        byte[] userDisk = null;
        byte[] devDisk = null;
        check("装机初值 /preference_config.json 可读", packedUser != null && packedUser.length > 0,
                packedUser == null ? "读不到" : packedUser.length + " 字节");
        check("用户档主本 /preference_config.user.json 可读（0.11.35 起「关」方向读的就是它）",
                user != null && user.length > 0, user == null ? "读不到" : user.length + " 字节");
        check("开发者档 /preference_config.dev.json 可读", dev != null && dev.length > 0,
                dev == null ? "读不到" : dev.length + " 字节");
        if (user == null || dev == null) {
            summary();
            return;
        }
        check("装机初值（宿主首次渲染看到的那份）与用户档主本逐字节相同",
                Arrays.equals(packedUser, user), sha12(packedUser) + " == " + sha12(user));
        userDisk = user;
        devDisk = dev;
        String userText = new String(user, StandardCharsets.UTF_8);
        String devText = new String(dev, StandardCharsets.UTF_8);

        check("用户档含「开发者模式」开关（否则开不回来）",
                userText.contains("\"key\": \"dev_mode\"") || userText.contains("\"key\":\"dev_mode\""),
                "dev_mode 在用户档里");
        check("用户档里没有任何开发者行", countDevRows(userText) == 0,
                "命中 " + countDevRows(userText) + " 行：" + hitDevRows(userText));
        check("开发者档含全部 14 行开发者入口", countDevRows(devText) == DEV_ROWS.length,
                "命中 " + countDevRows(devText) + "/" + DEV_ROWS.length + "：" + hitDevRows(devText));
        check("开发者档 = 用户档 + 14 行（体积更大且包含用户档全部用户可见行）",
                dev.length > user.length && devText.contains(ACCOUNT_TITLE) && devText.contains("退出登录"),
                "用户档 " + user.length + " 字节 / 开发者档 " + dev.length + " 字节");
        check("两档资源里都已无 {{ACCOUNT}} 占位（0.11.35：账号信息不常驻，占位机制退役）",
                !userText.contains("{{ACCOUNT}}") && !devText.contains("{{ACCOUNT}}"),
                "用户档=" + userText.contains("{{ACCOUNT}}") + " 开发者档=" + devText.contains("{{ACCOUNT}}"));
        check("两档账号行都是 button「" + ACCOUNT_TITLE + "」→ currentAccount（不是编辑框、标题无登录态尾巴）",
                isAccountButton(userText) && isAccountButton(devText),
                "用户档 " + accountRowSummary(userText) + " / 开发者档 " + accountRowSummary(devText));
        check("两档资源里都没有「" + ACCOUNT_INLINE_PREFIX + "」常驻登录态文本",
                !userText.contains(ACCOUNT_INLINE_PREFIX) && !devText.contains(ACCOUNT_INLINE_PREFIX),
                "用户档=" + userText.contains(ACCOUNT_INLINE_PREFIX) + " 开发者档=" + devText.contains(ACCOUNT_INLINE_PREFIX));

        // ---------- 二、关 ⇒ 用户档
        section("二、开关关闭 ⇒ 装机文件 = 用户档（账号信息不常驻）");
        DevMode.init(pluginDir);
        check("init 后装机文件已生成", Files.isRegularFile(target), target.toString());
        check("init 落的是用户档（逐字节 = 用户档资源）", fileEquals(target, userDisk),
                onDiskSummary(target, userDisk, devDisk));
        String diskText = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        check("装机文件里没有残留 {{ACCOUNT}} 占位", !diskText.contains("{{ACCOUNT}}"), "无残留");
        check("装机文件里没有「" + ACCOUNT_INLINE_PREFIX + "」常驻登录态文本（点开才展示）",
                !diskText.contains(ACCOUNT_INLINE_PREFIX), "标题里不含登录态");
        Boolean jsonOk = jsonValid(diskText);
        if (jsonOk == null) {
            skipped("装机文件仍是合法 JSON（用插件自己的 net.Json 验）", "取不到 net.Json 解析器");
        } else {
            check("装机文件仍是合法 JSON（用插件自己的 net.Json 验）", jsonOk, "解析结果非空");
        }
        check("结构层：账号行 type = button（点开才展示，不是宿主自绘输入框）",
                "button".equals(parsedAccountField(diskText, "type")),
                "解析得到：" + parsedAccountField(diskText, "type"));
        check("结构层：账号行 title 恰好「" + ACCOUNT_TITLE + "」",
                ACCOUNT_TITLE.equals(parsedAccountField(diskText, "title")),
                "解析得到：" + parsedAccountField(diskText, "title"));
        check("结构层：账号行 on_click = " + ACCOUNT_CLICK,
                ACCOUNT_CLICK.equals(parsedAccountField(diskText, "on_click")),
                "解析得到：" + parsedAccountField(diskText, "on_click"));
        check("结构层：账号组恰好 3 行（当前账号 / 登录 / 退出登录；0.11.46 起「启动时自动登录」搬进维护组）",
                accountGroupRows(diskText) == 3, "账号组 " + accountGroupRows(diskText) + " 行");

        // ---------- 三、开 ⇒ 开发者档；关回去 ⇒ 用户档
        section("三、开关打开 ⇒ 开发者档；再关 ⇒ 回用户档（可逆）");
        DevMode.setOn(true);
        check("开开关后 DevMode.on()=true", DevMode.on(), "on()=" + DevMode.on());
        check("开开关后装机文件 = 开发者档（逐字节）", fileEquals(target, devDisk),
                onDiskSummary(target, userDisk, devDisk));
        check("开开关后装机文件里出现「在线模式自检」",
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains("在线模式自检"), "");
        check("开发者档里账号行仍是 button「" + ACCOUNT_TITLE + "」→ currentAccount（切档不改接线）",
                isAccountButton(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)), "");
        check("开发者档里也没有「" + ACCOUNT_INLINE_PREFIX + "」常驻登录态文本",
                !new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains(ACCOUNT_INLINE_PREFIX), "");
        long mtime = Files.getLastModifiedTime(target).toMillis();
        Thread.sleep(1100);
        DevMode.setOn(true);
        check("同值重复 setOn 不重复写盘（mtime 未变）",
                Files.getLastModifiedTime(target).toMillis() == mtime,
                "mtime=" + Files.getLastModifiedTime(target).toMillis() + "（前次 " + mtime + "）");
        check("目录里没有写的临时文件残留（*.devtool.tmp）", countTmp(classes) == 0, "");
        DevMode.setOn(false);
        check("关开关后 DevMode.on()=false", !DevMode.on(), "on()=" + DevMode.on());
        check("关开关后装机文件 = 用户档（逐字节）", fileEquals(target, userDisk),
                onDiskSummary(target, userDisk, devDisk));
        check("关开关后装机文件里不再有「在线模式自检」",
                !new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains("在线模式自检"), "");

        // ---------- 三·二、两个方向都真的换字节（复刻真机 22:03:02 起始态：装机文件 = 开发者档）
        section("三·二、两个方向真的换字节（复刻真机起始态：装机文件 = 开发者档）");
        Files.write(target, userDisk);          // 装机初值 = 用户档（宿主首次渲染的那份）
        long sizeUserBefore = Files.size(target);
        DevMode.setOn(true);
        long sizeDevAfter = Files.size(target);
        check("方向A（用户档 → 开发者档）：setOn(true) 后装机文件逐字节 = 开发者档资源",
                fileEquals(target, devDisk), onDiskSummary(target, userDisk, devDisk));
        check("方向A：确实换了字节（" + sizeUserBefore + " → " + sizeDevAfter + " 字节）",
                sizeUserBefore != sizeDevAfter && sizeDevAfter == devDisk.length,
                sizeUserBefore + " → " + sizeDevAfter + " 字节（开发者档资源 " + devDisk.length + " 字节）");
        Files.write(target, devDisk);           // 复刻真机故障现场：装机文件 = 开发者档
        long sizeDevBefore = Files.size(target);
        DevMode.setOn(false);
        long sizeUserAfter = Files.size(target);
        check("方向B（开发者档 → 用户档）：setOn(false) 后装机文件逐字节 = 用户档主本",
                fileEquals(target, userDisk), onDiskSummary(target, userDisk, devDisk));
        check("方向B：确实换了字节（" + sizeDevBefore + " → " + sizeUserAfter + " 字节）—— 本轮修的正是这一条",
                sizeDevBefore != sizeUserAfter && sizeUserAfter == userDisk.length,
                sizeDevBefore + " → " + sizeUserAfter + " 字节（用户档 " + userDisk.length + " 字节）");
        String switchedBack = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        check("方向B：装机文件里不再含「" + DEV_ROW_TEXT + "」（用户报障的 14 行入口全没了）",
                !switchedBack.contains(DEV_ROW_TEXT), "14 行命中：" + hitDevRows(switchedBack));
        check("方向B：账号组恰好 3 行、账号行仍是 button「" + ACCOUNT_TITLE + "」",
                accountGroupRows(switchedBack) == 3 && isAccountButton(switchedBack),
                "账号组 " + accountGroupRows(switchedBack) + " 行 / " + accountRowSummary(switchedBack));

        // ---------- 三·五、账号行契约（0.11.35 用户 m00001 问题1：点开才展示）
        section("三·五、账号行契约（0.11.35：页面上不常驻账号信息，点开才由明细窗展示）");
        String after = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        check("切档往返后账号行仍是 button（用户在页面上看到的是可点的行）",
                "button".equals(parsedAccountField(after, "type")),
                "解析得到：" + parsedAccountField(after, "type"));
        check("切档往返后账号行 title 仍恰好「" + ACCOUNT_TITLE + "」（不带冒号、不带占位）",
                ACCOUNT_TITLE.equals(parsedAccountField(after, "title")),
                "解析得到：" + parsedAccountField(after, "title"));
        check("切档往返后账号行 on_click 仍 = " + ACCOUNT_CLICK,
                ACCOUNT_CLICK.equals(parsedAccountField(after, "on_click")),
                "解析得到：" + parsedAccountField(after, "on_click"));
        check("账号行不是 edittext（0.11.32–0.11.34 的宿主自绘输入框形态不许回潮）",
                !"edittext".equals(parsedAccountField(after, "type")),
                "解析得到：" + parsedAccountField(after, "type"));
        check("切档往返后页面上仍无「" + ACCOUNT_INLINE_PREFIX + "」与 {{ACCOUNT}}（不常驻是可逆的）",
                !after.contains(ACCOUNT_INLINE_PREFIX) && !after.contains("{{ACCOUNT}}"), "无残留");
        check("写盘后的行数没有被改写（\"type\" 个数 = 用户档资源）",
                countOccurrences(after, "\"type\"") == countOccurrences(userText, "\"type\""),
                "type 数=" + countOccurrences(after, "\"type\"") + "（用户档资源 " + countOccurrences(userText, "\"type\"") + "）");
        check("点开账号行要打开的明细窗类存在：ui.AccountStatusWindow",
                accountWindowOk(), accountWindowDetail());
        check("目录里仍然没有临时文件残留", countTmp(classes) == 0, "");

        // ---------- 四、装机目录拿不到 ⇒ 安全 no-op
        section("四、装机目录拿不到 ⇒ 安全 no-op（开关仍可切，但不动磁盘、不抛）");
        boolean threw = false;
        try {
            DevMode.init(null);
            DevMode.setOn(true);
        } catch (Throwable t) {
            threw = true;
        }
        check("init(null) + setOn(true) 不抛异常", !threw, threw ? "抛了异常" : "安全返回");
        check("不可写时已有档位不被破坏（仍是用户档）", fileEquals(target, userDisk),
                onDiskSummary(target, userDisk, devDisk));
        check("不可写时开关值仍被记住（on()=true）", DevMode.on(), "on()=" + DevMode.on());

        // ---------- 五、重新 init ⇒ 按开关值自愈
        section("五、重新 init ⇒ 按当前开关值自愈重写");
        DevMode.init(pluginDir);
        check("重新 init 后（dev=true）自愈重写为开发者档", fileEquals(target, devDisk),
                onDiskSummary(target, userDisk, devDisk));
        DevMode.setOn(false);
        check("再关一次 ⇒ 回到用户档", fileEquals(target, userDisk),
                onDiskSummary(target, userDisk, devDisk));

        // ---------- 五·五、自读守卫（源资源 == 被改写目标 ⇒ 必须拒绝，绝不许假成功）
        section("五·五、自读守卫（随包资源解析到被改写目标本身 ⇒ 拒绝写盘、按失败上报）");
        check("守卫正向：装机布局下 /preference_config.json 就是被改写目标本身 ⇒ isSelfRead=true",
                DevMode.isSelfRead(TARGET_RES), "指向 " + resourceOrigin(TARGET_RES));
        check("守卫反向：用户档主本 /preference_config.user.json 与目标物理分离 ⇒ isSelfRead=false",
                !DevMode.isSelfRead(USER_MASTER), "指向 " + resourceOrigin(USER_MASTER));
        check("守卫反向：开发者档 /preference_config.dev.json 与目标物理分离 ⇒ isSelfRead=false",
                !DevMode.isSelfRead(DEV_MASTER), "指向 " + resourceOrigin(DEV_MASTER));
        // 复刻 0.11.35 真机的坏布局：把被改写目标改指到「用户档主本」自身（源与目标同一物理文件），
        // 再走用户那一侧的关开关路径 —— 修复前 apply() 会在这里「跳过写盘」并报成功（真机 22:49:31 那句）。
        DevMode.setOn(true);
        boolean hooked = setTargetField(userMaster);
        check("反射把被改写目标改指到用户档主本自身（复刻「源 == 目标」的坏布局）", hooked,
                hooked ? "target := " + userMaster : "反射失败：DevMode.target");
        byte[] masterBefore = readFile(userMaster);
        boolean guardThrew = false;
        boolean applied = true;
        try {
            DevMode.setOn(false);
            applied = DevMode.apply();
        } catch (Throwable t) {
            guardThrew = true;
        }
        check("自读守卫：源 == 目标时 apply() 返回 false（绝不把空转报成成功）",
                !applied && !guardThrew, "apply()=" + applied + (guardThrew ? "，且抛了异常" : ""));
        check("自读守卫：setOn 日志按「结果=失败」上报（真机那句「结果=成功」不许回潮）",
                logText().contains("配置页已改写（用户档，结果=失败）"), lastLine(logText()));
        check("自读守卫：日志留下 ERROR 级拒绝记录（真机取证能抓到）",
                logText().contains("[ERROR]") && logText().contains("拒绝改写"), grepLog("拒绝改写"));
        check("自读守卫：拒绝后源文件（用户档主本）字节一字未动",
                Arrays.equals(masterBefore, readFile(userMaster)),
                (masterBefore == null ? "读不到" : masterBefore.length + " 字节，") + sha12(readFile(userMaster)));
        DevMode.init(pluginDir);   // target 复位回装机文件，并按当前档位（关）重生
        check("自读实验后 target 已复位：装机文件仍是用户档、无临时文件残留",
                fileEquals(target, userDisk) && countTmp(classes) == 0,
                onDiskSummary(target, userDisk, devDisk));

        // ---------- 六、运行时闸（第二层保险）
        section("六、运行时闸 devGate（开关关闭时拒绝、打开时放行）");
        Method gate = null;
        try {
            Class<?> np = Class.forName("com.example.netease.NeteasePlugin");
            gate = np.getDeclaredMethod("devGate", String.class);
            gate.setAccessible(true);
            check("反射找到 NeteasePlugin.devGate(String)（private static）", true,
                    gate.toGenericString());
        } catch (Throwable t) {
            skipped("反射 devGate（需要全量插件类 + 宿主 jar 在 classpath）", t.toString());
        }
        if (gate != null) {
            DevMode.setOn(false);
            Boolean denied = invokeGate(gate);
            if (denied == null) {
                skipped("开关关闭 ⇒ devGate 拒绝执行", "调用不可用：" + invokeGateError);
            } else {
                check("开关关闭 ⇒ devGate 拒绝执行", !denied, "返回 false");
            }
            DevMode.setOn(true);
            Boolean allowed = invokeGate(gate);
            if (allowed == null) {
                skipped("开关打开 ⇒ devGate 放行", "调用不可用：" + invokeGateError);
            } else {
                check("开关打开 ⇒ devGate 放行", allowed, "返回 true");
            }
            DevMode.setOn(false);
        }

        summary();
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] resource(String name) {
        try (InputStream in = DevModeProbe.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            byte[] raw = in.readAllBytes();
            if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
                return Arrays.copyOfRange(raw, 3, raw.length);
            }
            return raw;
        } catch (IOException e) {
            return null;
        }
    }

    /** 读文件全部字节（失败 ⇒ null，绝不抛）。 */
    private static byte[] readFile(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把资源名解析成**物理文件路径**（只有 {@code file:} 协议才可能拿到）。
     *
     * @return 文件路径；解析不到（null URL、jar 内、非法 URI）⇒ null
     */
    private static Path resourcePath(String name) {
        try {
            java.net.URL url = DevModeProbe.class.getResource(name);
            if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
                return null;
            }
            return Paths.get(url.toURI());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 资源解析到的原始 URL 文本（取证用；解析不到的异常一律吞掉）。 */
    private static String resourceOrigin(String name) {
        try {
            java.net.URL url = DevModeProbe.class.getResource(name);
            return url == null ? "读不到" : url.toString();
        } catch (Throwable t) {
            return "读不到（" + t + "）";
        }
    }

    /** 两个路径是否指同一个物理文件（任一为 null / 不存在 ⇒ false，绝不抛）。 */
    private static boolean sameFile(Path a, Path b) {
        try {
            return a != null && b != null && Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 反射把 {@code DevMode.target} 改指到别处 —— 用来复刻真机的坏布局（源 == 目标）。
     *
     * <p>只为探针取证；{@code DevMode} 里该字段是 {@code private static Path}。</p>
     *
     * @return true=改成功
     */
    private static boolean setTargetField(Path p) {
        try {
            java.lang.reflect.Field f = DevMode.class.getDeclaredField("target");
            f.setAccessible(true);
            f.set(null, p);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前探针日志全文（读不到 ⇒ 空串，绝不抛）。 */
    private static String logText() {
        try {
            Path f = PluginLog.file();
            if (f == null || !Files.isRegularFile(f)) {
                return "";
            }
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 日志里最后一条非空行（失败时贴现场）。 */
    private static String lastLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return "（日志为空）";
    }

    /** 日志里第一条含关键字的行（失败时贴现场）。 */
    private static String grepLog(String needle) {
        for (String line : logText().split("\\R")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "（日志里没有含「" + needle + "」的行）";
    }

    /** 字节数组的 sha256 前 8 字节十六进制（仅用于细节行里的人类可读指纹）。 */
    private static String sha12(byte[] bytes) {
        if (bytes == null) {
            return "sha256:（读不到）";
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02X", d[i]));
            }
            return sb.append("…（").append(bytes.length).append(" 字节）").toString();
        } catch (Throwable t) {
            return "sha256:?";
        }
    }

    /**
     * 「账号」那一组里有多少行（用插件自己的 {@code Json} 解析器数；按「含 title=当前账号 的那一组」定位，
     * 组名改了也不会误判）。
     *
     * @return 行数；解析失败 / 找不到账号组 ⇒ -1
     */
    private static int accountGroupRows(String text) {
        try {
            Map<String, Object> root = Json.object(text);
            if (!(root.get("configs") instanceof List<?> groups)) {
                return -1;
            }
            for (Object group : groups) {
                if (!(group instanceof Map<?, ?> gm) || !(gm.get("preferences") instanceof List<?> rows)) {
                    continue;
                }
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> rm && ACCOUNT_TITLE.equals(String.valueOf(rm.get("title")))) {
                        return rows.size();
                    }
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 用插件自己的解析器验一遍 JSON 是否合法（{@code com.example.netease.core.Json.parse(String)}，
     * 与运行时写盘用的是同一份实现 —— 探针不再自带第二个解析器）。
     *
     * @return true=解析成功；false=解析失败（内容坏了）；null=不该出现（保留给未来降级）
     */
    private static Boolean jsonValid(String text) {
        try {
            return Json.parse(text) != null;
        } catch (Throwable t) {
            return Boolean.FALSE;
        }
    }

    /**
     * 用插件自己的解析器把装机文件读成**结构**，再取出账号行的某个字段。
     *
     * <p>这一步比「文本里有没有那串字」强：它证明写盘的仍是合法 JSON、层级还是
     * {@code configs[…].preferences[…]}、且账号行的**形态**（type / title / on_click）真的落对了
     * —— 0.11.35 起账号行是 {@code button}「当前账号」→ {@code currentAccount}，不再是带登录态
     * 尾巴的 {@code edittext}。</p>
     *
     * <p>行定位：优先按 title（0.11.35 起账号行没有 {@code key}），退回按 {@code key=current_account}
     * （0.11.32–0.11.34 的老形态，留着只为把「老包写盘」的失败信息说清楚）。</p>
     *
     * @return 该字段的字符串值；取不到（解析失败 / 结构变了 / 没有这一行 / 没有这个字段）⇒ {@code null}
     */
    private static String parsedAccountField(String text, String field) {
        try {
            Map<String, Object> root = Json.object(text);
            Object configs = root.get("configs");
            if (!(configs instanceof List<?> groups)) {
                return null;
            }
            Map<?, ?> fallback = null;
            for (Object group : groups) {
                if (!(group instanceof Map<?, ?> gm)) {
                    continue;
                }
                if (!(gm.get("preferences") instanceof List<?> rows)) {
                    continue;
                }
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> rm)) {
                        continue;
                    }
                    if (ACCOUNT_TITLE.equals(String.valueOf(rm.get("title")))) {
                        return rm.get(field) == null ? null : String.valueOf(rm.get(field));
                    }
                    if (fallback == null && "current_account".equals(rm.get("key"))) {
                        fallback = rm;
                    }
                }
            }
            return fallback == null || fallback.get(field) == null ? null : String.valueOf(fallback.get(field));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 账号行是否就是 0.11.35 的形态：button + title 恰好「当前账号」+ on_click=currentAccount。 */
    private static boolean isAccountButton(String text) {
        return "button".equals(parsedAccountField(text, "type"))
                && ACCOUNT_TITLE.equals(parsedAccountField(text, "title"))
                && ACCOUNT_CLICK.equals(parsedAccountField(text, "on_click"));
    }

    private static String accountRowSummary(String text) {
        return "type=" + parsedAccountField(text, "type")
                + " title=" + parsedAccountField(text, "title")
                + " on_click=" + parsedAccountField(text, "on_click");
    }

    /**
     * 点开账号行后要出现的那个窗（0.11.35 的 {@code ui.AccountStatusWindow}）是否真在包内，
     * 且入口是 {@code public static void open()}。
     *
     * <p>类加载失败（探针 classpath 不全）⇒ 走 {@link #skipped}，不算契约违约。</p>
     */
    private static boolean accountWindowOk() {
        return windowProbeOk;
    }

    private static String accountWindowDetail() {
        return windowProbeDetail;
    }

    private static boolean windowProbeOk;
    private static String windowProbeDetail = "未探测";

    static {
        try {
            Class<?> w = Class.forName("com.example.netease.ui.AccountStatusWindow", false,
                    DevModeProbe.class.getClassLoader());
            Method m = w.getDeclaredMethod("open");
            int mod = m.getModifiers();
            windowProbeOk = Modifier.isPublic(mod) && Modifier.isStatic(mod)
                    && m.getParameterCount() == 0 && m.getReturnType() == void.class;
            windowProbeDetail = "ui.AccountStatusWindow.open() = " + m.toGenericString();
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            windowProbeOk = false;
            windowProbeDetail = "未找到：" + e;
        } catch (Throwable t) {
            windowProbeOk = false;
            windowProbeDetail = "探测失败：" + t;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    private static int countDevRows(String text) {
        int n = 0;
        for (String row : DEV_ROWS) {
            if (text.contains(row)) {
                n++;
            }
        }
        return n;
    }

    private static String hitDevRows(String text) {
        StringBuilder sb = new StringBuilder();
        for (String row : DEV_ROWS) {
            if (text.contains(row)) {
                sb.append(sb.length() == 0 ? "" : "、").append(row);
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private static boolean fileEquals(Path file, byte[] expect) {
        try {
            return Arrays.equals(Files.readAllBytes(file), expect);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String onDiskSummary(Path file, byte[] user, byte[] dev) {
        try {
            byte[] now = Files.readAllBytes(file);
            String which = Arrays.equals(now, user) ? "用户档" : Arrays.equals(now, dev) ? DEV_DEGRADED : "未知内容";
            return now.length + " 字节（" + which + "）";
        } catch (Throwable t) {
            return "读不到：" + t;
        }
    }

    private static int countTmp(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".devtool.tmp")).count();
        }
    }

    /**
     * 销毁式探针的安全闸：**绝不许拿真实工作区（或任何含 {@code .git} 的目录）当沙箱**。
     *
     * <p>2026-10-02 真事：在仓库根目录跑本探针 ⇒ {@code deleteTree(root)} 按目录序把 {@code .git}
     * 里的 {@code HEAD} / {@code config} / {@code index} / {@code logs} 先删掉，直到撞上一个只读的
     * git 对象文件抛 {@link java.nio.file.AccessDeniedException} 才停下——仓库因此变成「not a git
     * repository」，靠 {@code git init -b main} 重建脚手架 + {@code git reset} 重建索引才救回来
     * （工作树与 pack 都没事）。教训：销毁式探针要么自带临时沙箱，要么先把护栏写进代码。</p>
     */
    private static void guardSandbox(Path root) {
        if (Files.exists(root.resolve(".git"))) {
            throw new IllegalArgumentException("拒绝把含 .git 的目录当销毁式沙箱：" + root
                    + "（请用 build\\devmode-probe 或临时目录）");
        }
        if (Files.exists(root.resolve("project.json")) && Files.isDirectory(root.resolve("src"))) {
            throw new IllegalArgumentException("拒绝把插件工作区当销毁式沙箱：" + root);
        }
        Path build = Paths.get("build").toAbsolutePath();
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir", "build")).toAbsolutePath();
        if (!root.startsWith(build) && !root.startsWith(tmp)) {
            throw new IllegalArgumentException("销毁式沙箱只允许放在 " + build + " 或临时目录下：" + root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + (detail == null || detail.isBlank() ? "" : " ｜ " + detail));
        } else {
            fail++;
            failures.add(name);
            System.out.println("[FAIL] " + name + (detail == null || detail.isBlank() ? "" : " ｜ " + detail));
        }
    }

    private static void skipped(String name, String why) {
        skip++;
        System.out.println("[SKIP] " + name + "（" + why + "）");
    }

    private static void summary() {
        System.out.println();
        System.out.println("SUMMARY pass=" + pass + " fail=" + fail + " skip=" + skip);
        if (fail == 0) {
            System.out.println("DEV_MODE_RESULT=ALL_PASS");
            System.exit(0);
        }
        // 结尾 token：ALL_PASS | HAS_FAIL（供门禁脚本 grep；失败时把每条 FAIL 原文接在后面）。
        System.out.println("DEV_MODE_RESULT=HAS_FAIL：" + String.join("；", failures));
        System.exit(1);
    }
}
