package spw.harness;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.WorkshopPluginManager;

import org.pf4j.PluginDescriptor;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 离线加载 harness 主体：普通 JVM 里用宿主的 {@link WorkshopPluginManager}
 * （extends org.pf4j.DefaultPluginManager）加载 .spmod，走完整生命周期。
 *
 * <p>链路：注入桩 WorkshopApi → loadPlugins → descriptor(Plugin-Id/Version)
 * → getExtensions(PlaybackExtensionPoint) 非空 → startPlugin → 宿主回调桩
 * → stopPlugin → 线程/异常清点。全部 PASS 时退出码 0。
 *
 * <p>系统属性：
 * <pre>
 * harness.pluginDir     插件目录（.spmod 解压后的 plugin-&lt;id&gt;-&lt;version&gt; 目录）
 * harness.pluginId      期望 Plugin-Id
 * harness.pluginVersion 期望 Plugin-Version
 * harness.workDir       临时工作目录（plugins root / 配置落地）
 * harness.timeoutSec    每阶段超时秒数（缺省 30）
 * harness.spwVersion    伪装的宿主版本（缺省 1.18.5）
 * harness.appData       沙箱 %APPDATA%（run-harness.ps1 会把它设成 JVM 的 APPDATA 环境变量）
 * harness.dataDir       插件数据目录（= &lt;appData&gt;\Salt Player for Windows\workshop\data\&lt;pluginId&gt;）
 * harness.spmodSha256   被测 .spmod 的 sha256（仅用于证据打印）
 * </pre>
 */
public final class HarnessMain {

    // ------------------------------------------------------------- 结果统计
    private static final List<Object[]> RESULTS = new ArrayList<>(); // [id, desc, pass(boolean), detail]
    private static int passed = 0;
    private static int failed = 0;

    private static synchronized void check(String id, String desc, boolean ok, String detail) {
        String tag = ok ? "PASS" : "FAIL";
        String line = String.format("  [%s] %-6s %s", tag, id, desc);
        if (detail != null && !detail.isEmpty()) {
            line += "\n         → " + detail;
        }
        System.out.println(line);
        System.out.flush();
        RESULTS.add(new Object[]{id, desc, ok, detail});
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static void info(String s) {
        System.out.println("  · " + s);
        System.out.flush();
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s + " " + repeat('─', Math.max(0, 60 - s.length())));
        System.out.flush();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 崩溃收集
    /** 任何**非 harness 自己**的线程抛未捕获异常都记下来（插件线程崩了必须被看见）。 */
    private static final List<String> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------ main
    public static void main(String[] args) {
        Thread.currentThread().setName("harness-main");

        Path pluginDir = Paths.get(prop("harness.pluginDir", ""));
        Path workDir = Paths.get(prop("harness.workDir", "harness/work"));
        String expectId = prop("harness.pluginId", "");
        String expectVer = prop("harness.pluginVersion", "");
        String spwVersion = prop("harness.spwVersion", "1.18.5");
        int timeoutSec = Integer.parseInt(prop("harness.timeoutSec", "30"));
        String dataDirProp = prop("harness.dataDir", "");
        Path dataDir = dataDirProp.isEmpty() ? workDir.resolve("data") : Paths.get(dataDirProp);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String tn = (t == null) ? "" : String.valueOf(t.getName());
            if (tn.startsWith("harness-") || tn.startsWith("main")) {
                return; // harness 自己起的探测线程，异常由 runWithTimeout 捕获上报
            }
            String msg = "线程 " + (t == null ? "?" : t.getName()) + " 未捕获异常: " + oneLine(e);
            UNCAUGHT.add(msg);
            System.out.println("  !! " + msg);
            if (e != null) {
                e.printStackTrace(System.out);
            }
        });

        section("环境");
        info("pluginDir      = " + pluginDir);
        info("workDir        = " + workDir);
        info("期望 Plugin-Id  = " + expectId);
        info("期望 Version    = " + expectVer);
        info("伪装 SPW 版本   = " + spwVersion);
        info("阶段超时        = " + timeoutSec + "s");
        info("java.version   = " + System.getProperty("java.version"));
        info("沙箱 APPDATA    = " + prop("harness.appData", "(未设置 —— 插件会写进真机数据目录!)"));
        info("数据目录        = " + dataDir);
        info("被测产物 sha256 = " + prop("harness.spmodSha256", "(未传)"));

        Set<String> threadsBefore = threadNames();

        // ------------------------------------------------------ H1 桩注入
        section("H1 宿主 API 桩注入");
        StubWorkshopApi.StubManager mgr = new StubWorkshopApi.StubManager(workDir.resolve("conf"));
        StubWorkshopApi stub = new StubWorkshopApi(mgr);
        boolean injected = HostStubs.inject(stub);
        check("H1.1", "WorkshopApi$Companion.instance 反射注入桩成功", injected,
                injected ? "字段: WorkshopApi$Companion.instance (public static)" : "无法写入静态字段");
        boolean accessors = injected && HostStubs.verifyAccessors();
        check("H1.2", "静态访问器 WorkshopApi.ui()/playback()/manager() 返回桩", accessors,
                accessors ? "三项均非 null" : "任一为 null 或抛异常");

        // ------------------------------------------------------ H2 加载插件
        section("H2 插件加载（WorkshopPluginManager / PF4J）");
        if (!java.nio.file.Files.isDirectory(pluginDir)) {
            check("H2.0", "插件目录存在", false, "不是目录：" + pluginDir);
            finish();
            return;
        }

        PluginContext ctx = HostStubs.context(expectId, expectVer, pluginDir, spwVersion);

        WorkshopPluginManager pm = null;
        try {
            // PF4J 3.12 没有公开的 setPluginFactory/setExtensionFactory（javap 实测），
            // 只能在子类里 override createPluginFactory()/createExtensionFactory()。
            HarnessPluginManager hpm = new HarnessPluginManager(pluginDir.getParent());
            hpm.setHarnessContext(ctx);
            pm = hpm;
            pm.setSystemVersion(spwVersion);
            info("PluginManager = " + pm.getClass().getName());
            info("pluginsRoot   = " + pm.getPluginsRoot());
        } catch (Throwable t) {
            check("H2.1", "构造 WorkshopPluginManager 成功", false, oneLine(t));
            t.printStackTrace(System.out);
            finish();
            return;
        }

        boolean factoriesOk = false;
        try {
            factoriesOk = pm.getExtensionFactory() != null;
        } catch (Throwable t) {
            info("getExtensionFactory() 探测失败: " + oneLine(t));
        }
        check("H2.1", "自定义 ExtensionFactory 已在构造期装配（pluginFactory 由子类 override 提供）",
                factoriesOk,
                "理由：SpwPlugin 只有 (PluginContext) 构造器，PF4J 默认工厂会返回 null，插件起不来");

        long t0 = System.currentTimeMillis();
        try {
            pm.loadPlugins();
        } catch (Throwable t) {
            check("H2.2", "loadPlugins() 不抛异常", false, oneLine(t));
            t.printStackTrace(System.out);
            finish();
            return;
        }
        long loadMs = System.currentTimeMillis() - t0;
        check("H2.2", "loadPlugins() 不抛异常", true, "耗时 " + loadMs + " ms");

        List<PluginWrapper> plugins = pm.getPlugins();
        check("H2.3", "至少加载到 1 个插件", plugins != null && !plugins.isEmpty(),
                plugins == null ? "getPlugins() 返回 null" : "加载到 " + plugins.size() + " 个");

        if (plugins == null || plugins.isEmpty()) {
            info("PF4J 未能从目录读出插件描述符。zip 分支只读 classes/META-INF/MANIFEST.MF，目录分支递归找 MANIFEST.MF。");
            info("目录内容：");
            listTree(pluginDir, "    ");
            finish();
            return;
        }

        PluginWrapper wrapper = plugins.get(0);
        info("发现插件: " + wrapper.getPluginId() + " @ " + wrapper.getPluginPath());

        // ------------------------------------------------------ H3 描述符
        section("H3 插件描述符（descriptor）");
        PluginDescriptor d = wrapper.getDescriptor();
        check("H3.1", "descriptor 非 null", d != null, null);
        if (d == null) {
            finish();
            return;
        }

        String id = d.getPluginId();
        String ver = d.getVersion() == null ? "" : d.getVersion().toString();
        check("H3.2", "Plugin-Id 与 project.json 一致", expectId.equals(id),
                "期望 " + expectId + " / 实际 " + id);
        check("H3.3", "Plugin-Version 与 project.json 一致", expectVer.equals(ver),
                "期望 " + expectVer + " / 实际 " + ver);

        String desc = d.getPluginDescription();
        check("H3.4", "Plugin-Description 读得到（UTF-8 折行未破坏）",
                desc != null && !desc.trim().isEmpty(),
                "值 = " + desc);

        // javap 实测：PluginDescriptor.getPluginClass() 返回 **String**（类名），不是 Class
        String mainClassName = d.getPluginClass();
        Class<?> mainClass = null;
        String mainClassErr = null;
        try {
            if (mainClassName == null || mainClassName.trim().isEmpty()) {
                mainClassErr = "Plugin-Class 为空";
            } else {
                mainClass = wrapper.getPluginClassLoader().loadClass(mainClassName.trim());
            }
        } catch (Throwable t) {
            mainClassErr = oneLine(t);
        }
        check("H3.5", "Plugin-Class 可解析为 org.pf4j.Plugin 子类",
                mainClass != null && org.pf4j.Plugin.class.isAssignableFrom(mainClass),
                "Plugin-Class = " + mainClassName
                        + (mainClassErr != null ? "（解析失败: " + mainClassErr + "）"
                                                : " → " + (mainClass == null ? "null" : mainClass.getName())));

        // 插件实例（PF4J 加载期就用我们的工厂构造好了）
        Object plugin = wrapper.getPlugin();
        check("H3.6", "插件实例已由自定义工厂构造（非 null）", plugin != null,
                "工厂路径 = " + HarnessFactories.lastPluginFactoryPath);

        if (plugin != null) {
            boolean isSpwPlugin = plugin instanceof com.xuncorp.spw.workshop.api.SpwPlugin;
            check("H3.7", "插件实例是 com.xuncorp.spw.workshop.api.SpwPlugin 子类", isSpwPlugin,
                    plugin.getClass().getName());
            if (plugin instanceof com.xuncorp.spw.workshop.api.SpwPlugin) {
                com.xuncorp.spw.workshop.api.SpwPlugin sp = (com.xuncorp.spw.workshop.api.SpwPlugin) plugin;
                PluginContext got = sp.getPluginContext();
                boolean ctxOk = got != null
                        && expectId.equals(got.getPluginId())
                        && spwVersion.equals(got.getSpwVersion());
                check("H3.8", "getPluginContext() 与 harness 注入的上下文一致", ctxOk,
                        got == null ? "null"
                                : "id=" + got.getPluginId() + " ver=" + got.getPluginVersion()
                                  + " spw=" + got.getSpwVersion() + " channel=" + got.getSpwChannel()
                                  + " path=" + got.getPluginPath());
            }
        }

        // ------------------------------------------------------ H4 扩展点
        section("H4 扩展点发现");
        ClassLoader pcl = null;
        try {
            pcl = pm.getPluginClassLoader(id);
        } catch (Throwable t) {
            info("getPluginClassLoader 失败: " + oneLine(t));
        }
        info("pluginClassLoader = " + pcl);

        check("H4.1", "插件状态为 RESOLVED（扩展已解析）",
                wrapper.getPluginState() == PluginState.RESOLVED || wrapper.getPluginState() == PluginState.STARTED,
                "state = " + wrapper.getPluginState());

        Set<String> extIdx = new java.util.LinkedHashSet<>();
        try {
            Set<String> got = pm.getExtensionClassNames(id);
            if (got != null) {
                extIdx.addAll(got);
            }
        } catch (Throwable t) {
            info("getExtensionClassNames 失败: " + oneLine(t));
        }
        check("H4.2", "extensions.idx 里的扩展类被 PF4J 读到", !extIdx.isEmpty(),
                "getExtensionClassNames = " + extIdx);

        // start 之前先探一次，但【不作判定】——真实宿主的时序是「先 startPlugins() 再 getExtensions(...)」，
        // 所以扩展点的正式判定放在 H5 之后（经验：PF4J 的扩展解析与插件状态相关，时序过早会得到 0 个）。
        List<PlaybackExtensionPoint> exts = new ArrayList<>();
        int preStartCount = -1;
        try {
            List<PlaybackExtensionPoint> pre = pm.getExtensions(PlaybackExtensionPoint.class);
            if (pre != null) {
                preStartCount = pre.size();
            }
        } catch (Throwable t) {
            info("start 之前查询 getExtensions 异常: " + oneLine(t));
        }
        info("（信息，不作判定）start 之前 getExtensions(PlaybackExtensionPoint.class) = " + preStartCount + " 个");

        // ------------------------------------------------------ H5 start
        section("H5 生命周期 start()");
        String startErr = null;
        try {
            startWithTimeout(pm, id, timeoutSec);
        } catch (Throwable t) {
            startErr = oneLine(t);
        }
        PluginState afterStart = wrapper.getPluginState();
        check("H5.1", "startPlugin(\"" + id + "\") 返回 STARTED",
                startErr == null && afterStart == PluginState.STARTED,
                startErr != null ? "异常: " + startErr : "state = " + afterStart);
        check("H5.2", "start() 期间无非 harness 线程未捕获异常",
                UNCAUGHT.isEmpty(), UNCAUGHT.isEmpty() ? null : join(UNCAUGHT));

        // ---------------------------------------------- H4.3（宿主时序：start 之后）
        try {
            List<PlaybackExtensionPoint> got = pm.getExtensions(PlaybackExtensionPoint.class);
            if (got != null) {
                exts.addAll(got);
            }
        } catch (Throwable t) {
            check("H4.3", "start() 之后 getExtensions(PlaybackExtensionPoint.class) 非空", false, oneLine(t));
            t.printStackTrace(System.out);
        }
        if (!exts.isEmpty()) {
            check("H4.3", "start() 之后 getExtensions(PlaybackExtensionPoint.class) 非空（宿主时序）", true,
                    "拿到 " + exts.size() + " 个扩展：" + classNames(exts)
                            + (preStartCount == 0 ? "（start 之前为 0 ⇒ 扩展解析依赖插件已 start，H4 处查询过早）" : ""));
        } else {
            check("H4.3", "start() 之后 getExtensions(PlaybackExtensionPoint.class) 非空（宿主时序）", false,
                    "start 之后仍为空 ⇒ 与插件状态无关，是扩展发现机制本身的问题（见下方 D1/D2 诊断）");
            diagnoseEmptyExtensions(pm, extIdx, pcl);
        }

        if (afterStart != PluginState.STARTED) {
            Throwable fe = wrapper.getFailedException();
            if (fe != null) {
                info("PF4J 记录失败异常：" + oneLine(fe));
                fe.printStackTrace(System.out);
            }
        }

        // ------------------------------------------------------ H6 宿主回调
        section("H6 宿主回调桩（PlaybackExtensionPoint）");
        int uncaughtBase = UNCAUGHT.size();
        if (!exts.isEmpty()) {
            PlaybackExtensionPoint raw = exts.get(0);
            info("扩展实例: " + raw.getClass().getName());

            // 包装：记录每个回调的方法名 + 参数，并捕获 Throwable（契约要求必须自己兜住）
            RecordingHandler handler = new RecordingHandler(raw, false);
            PlaybackExtensionPoint ext = (PlaybackExtensionPoint) Proxy.newProxyInstance(
                    HarnessMain.class.getClassLoader(),
                    new Class<?>[]{PlaybackExtensionPoint.class},
                    handler);

            PlaybackExtensionPoint.MediaItem item = new PlaybackExtensionPoint.MediaItem(
                    "测试曲目 · 标题", "测试歌手", "测试专辑", "专辑艺人",
                    "C:\\Music\\测试 目录\\测试曲目.flac");

            callback("H6.1", "onBeforeLoadLyrics(MediaItem) 不抛异常", ext,
                    handler, "onBeforeLoadLyrics", timeoutSec, item);
            callback("H6.2", "onAfterLoadLyrics(MediaItem) 不抛异常", ext,
                    handler, "onAfterLoadLyrics", timeoutSec, item);
            callback("H6.3", "onStateChanged(State.Ready) 不抛异常", ext,
                    handler, "onStateChanged", timeoutSec, PlaybackExtensionPoint.State.Ready);
            callback("H6.4", "onPositionUpdated(1000L) 不抛异常", ext,
                    handler, "onPositionUpdated", timeoutSec, 1000L);
            callback("H6.5", "onSeekTo(5000L) 不抛异常", ext,
                    handler, "onSeekTo", timeoutSec, 5000L);
            callback("H6.6", "onIsPlayingChanged(true) 不抛异常", ext,
                    handler, "onIsPlayingChanged", timeoutSec, Boolean.TRUE);

            info("代理记录到的回调序列 = " + handler.calls);
        } else {
            check("H6.1", "宿主回调桩可执行（需要扩展实例）", false, "无扩展实例，跳过");
        }

        // 回调里插件通常「入队 + 立刻 return」，给后台线程一点时间暴露异常
        sleep(1500);
        List<String> newUncaught = snapshotUncaught(uncaughtBase);
        check("H6.7", "回调后 1.5s 内无插件线程未捕获异常", newUncaught.isEmpty(),
                newUncaught.isEmpty() ? null : join(newUncaught));

        // ------------------------------------------- H6.8 延迟就绪 toast
        // NeteasePlugin 在 start() 之后约 3 秒才提示「已就绪」（刻意避开宿主启动高峰，
        // 见 NeteasePlugin.java:101-104），所以必须在 stop() 之前留观察窗，否则永远看不到
        // —— H8.2 此前报 toast=0 就是这个原因，不代表插件没提示。
        String readyToast = null;
        long toastDeadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < toastDeadline && readyToast == null) {
            for (String t : new ArrayList<>(stub.toasts)) {
                if (t != null && t.contains("已就绪")) { readyToast = t; break; }
            }
            if (readyToast == null) { sleep(200); }
        }
        if (readyToast != null) {
            check("H6.8", "start() 后 4s 内观察到延迟就绪 toast（对应 docs/10 的 P0-2 验收项）",
                    true, "toast = " + readyToast);
        } else {
            info("[H6.8] 4s 内未观察到延迟就绪 toast（桩 toast 共 " + stub.toasts.size() + " 条）"
                    + " —— 不作为失败判据：宿主真实弹窗仍需真人按 docs/10 P0-2 确认");
        }

        // ------------------------------------------------------ H9 P3 接线反射校验
        // preference_config.json 里的每个 on_click 都真的反射一遍：verify-spmod 只能比对字符串，
        // 「方法名拼错 / 签名漂移」只有真反射才能发现。
        P3Checks.Reporter rep = (cid, cdesc, cok, cdet) -> check(cid, cdesc, cok, cdet);
        P3Checks.phaseWiring(rep, pluginDir, dataDir, pcl, id);

        // ------------------------------------------------------ H7 stop
        section("H7 生命周期 stop()");
        String stopErr = null;
        try {
            stopWithTimeout(pm, id, timeoutSec);
        } catch (Throwable t) {
            stopErr = oneLine(t);
        }
        PluginState afterStop = wrapper.getPluginState();
        check("H7.1", "stopPlugin(\"" + id + "\") 返回 STOPPED",
                stopErr == null && afterStop == PluginState.STOPPED,
                stopErr != null ? "异常: " + stopErr : "state = " + afterStop);

        sleep(1000);
        List<String> leaked = new ArrayList<>();
        List<String> infraNew = new ArrayList<>();
        Set<String> threadsAfter = threadNames();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (threadsBefore.contains(name) || name.startsWith("harness-")) {
                continue;
            }
            if (isPluginOwned(t, pcl)) {
                leaked.add(P3Checks.threadDump(t));     // 插件自有线程 → 真泄漏（致命；带状态与栈便于定位）
            } else {
                infraNew.add(name);                     // JDK/宿主基础设施线程 → 仅记录
            }
        }
        check("H7.2", "stop() 后无插件自有线程残留（1s 观察窗）", leaked.isEmpty(),
                leaked.isEmpty()
                        ? ("插件自有线程 0" + (infraNew.isEmpty() ? ""
                                : "（另有非插件线程，不计入：" + join(infraNew) + "）"))
                        : "插件自有线程残留：" + leaked
                                + (infraNew.isEmpty() ? "" : "；另有非插件线程 " + join(infraNew)));

        check("H7.3", "整个生命周期无非 harness 线程未捕获异常", UNCAUGHT.isEmpty(),
                UNCAUGHT.isEmpty() ? null : join(UNCAUGHT));

        // ------------------------------------------------------ H10 P3 生命周期与凭据
        // 全部放在 stop() 之后：线程残留、RETAG_POOL shutdown、凭据密文往返/篡改、明文扫描。
        P3Checks.phaseCredentials(rep, dataDir, pcl, threadsBefore);

        // ------------------------------------------------------ H8 收尾
        section("H8 收尾");
        try {
            pm.unloadPlugins();
            check("H8.1", "unloadPlugins() 不抛异常", true, null);
        } catch (Throwable t) {
            check("H8.1", "unloadPlugins() 不抛异常", false, oneLine(t));
        }

        int toastCount = stub.toasts.size();
        int playbackCalls = stub.playback.calls.size();
        Map<String, Object> wrote = new LinkedHashMap<>();
        for (Map.Entry<String, StubConfigHelper> e : mgr.configs.entrySet()) {
            wrote.put(e.getKey(), e.getValue().snapshot());
        }
        info("桩 toast 调用 " + toastCount + " 次；播放控制 " + playbackCalls + " 次");
        info("桩配置表 = " + wrote);
        check("H8.2", "插件未在回调里抛异常（配置/toast 调用是加分项，不作为失败条件）", true,
                "toast=" + toastCount + ", playback=" + playbackCalls + ", config 配置名=" + wrote.size());

        finish();
    }

    /** 取 UNCAUGHT 中第 from 条之后的快照（synchronizedList 必须整表加锁复制）。 */
    private static List<String> snapshotUncaught(int from) {
        List<String> all;
        synchronized (UNCAUGHT) {
            all = new ArrayList<>(UNCAUGHT);
        }
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(Math.max(0, from), all.size()));
    }

    /**
     * H4.3 失败时的根因诊断 —— 避免只输出「为空」这种无信息量的结论。
     *
     * D1 换超类型 org.pf4j.ExtensionPoint 再查一次：
     *    若超类型查得到、而 PlaybackExtensionPoint 查不到 → 扩展类实现的是【另一个】
     *    PlaybackExtensionPoint 类实例（同一个类名被两个 classloader 各载入一次），
     *    属打包/类路径缺陷；两者都查不到 → 扩展发现机制（extensions.idx / META-INF/services）没被采纳。
     * D2 用插件的 PluginClassLoader 直接 loadClass 扩展类（initialize=false），打印它实际实现的接口
     *    及其 classloader 身份，用 System.identityHashCode 与 harness 侧那个 Class 对象逐一比对。
     */
    private static void diagnoseEmptyExtensions(org.pf4j.PluginManager pm, Set<String> extClassNames, ClassLoader pcl) {
        info("根因诊断 D1：换超类型 org.pf4j.ExtensionPoint 再查一次");
        try {
            List<org.pf4j.ExtensionPoint> all = pm.getExtensions(org.pf4j.ExtensionPoint.class);
            int n = (all == null ? -1 : all.size());
            info("  getExtensions(org.pf4j.ExtensionPoint.class).size = " + n);
            if (all != null) {
                for (org.pf4j.ExtensionPoint o : all) {
                    info("    ← " + o.getClass().getName());
                }
            }
            info(n > 0
                    ? "  ⇒ 超类型查得到 ⇒ 扩展类实现的 PlaybackExtensionPoint 与 harness 侧不是同一个 Class（classloader 重复载入）"
                    : "  ⇒ 超类型也查不到 ⇒ 扩展发现机制没生效（idx/services 未被采纳，或扩展类加载失败）");
        } catch (Throwable t) {
            info("  D1 失败: " + oneLine(t));
        }

        Class<?> pep = PlaybackExtensionPoint.class;
        info("根因诊断 D2：扩展类的类身份");
        info("  harness 侧 " + pep.getName() + " : identity=" + System.identityHashCode(pep) + " loader=" + pep.getClassLoader());
        for (String cn : extClassNames) {
            try {
                Class<?> c = Class.forName(cn, false, pcl);
                info("  插件类 " + cn);
                info("    identity=" + System.identityHashCode(c) + " loader=" + c.getClassLoader());
                info("    @org.pf4j.Extension 存在 = " + c.isAnnotationPresent(org.pf4j.Extension.class));
                info("    pep.isAssignableFrom(插件类) = " + pep.isAssignableFrom(c));
                Class<?>[] itfs = c.getInterfaces();
                if (itfs.length == 0) {
                    info("    implements: 无直接接口");
                }
                for (Class<?> itf : itfs) {
                    info("    implements " + itf.getName()
                            + " : identity=" + System.identityHashCode(itf)
                            + " loader=" + itf.getClassLoader()
                            + " 与 harness 同一个 Class = " + (itf == pep));
                }
            } catch (Throwable t) {
                info("    加载失败 " + cn + " : " + oneLine(t));
                t.printStackTrace(System.out);
            }
        }
    }

    /**
     * 判定一个新增线程是否「插件自有」——判据是上下文类加载器，不是线程名。
     *
     * 理由（实测教训）：插件源码 NeteasePlugin.java:20-21 引了 javax.swing.SwingUtilities / java.awt.Desktop，
     * 一旦碰过 Swing/AWT，JVM 会自己拉起 AWT-Windows + Java2D Disposer 两个 JDK 基础设施线程。
     * 只用名字白名单会把这种情况误报成「插件线程泄漏」；而这两个线程是 JDK 的、一直在 idle，与插件无关。
     * PF4J 里插件代码运行时的 contextClassLoader 就是该插件的 PluginClassLoader，
     * 插件自己 new Thread(...) 会继承它 → 由此可把「插件自有线程」与「JDK 基础设施线程」分开。
     */
    private static boolean isPluginOwned(Thread t, ClassLoader pluginCl) {
        if (pluginCl == null) {
            return false;
        }
        ClassLoader ctx;
        try {
            ctx = t.getContextClassLoader();
        } catch (Throwable ignore) {
            return false;
        }
        for (ClassLoader cl = ctx; cl != null; cl = cl.getParent()) {
            if (cl == pluginCl) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkThread(String name) {
        return name.startsWith("Common-Cleaner") || name.startsWith("Reference Handler")
                || name.startsWith("Finalizer") || name.startsWith("Signal Dispatcher")
                || name.startsWith("Attach Listener") || name.startsWith("Notification Thread")
                || name.startsWith("process reaper") || name.startsWith("DestroyJavaVM")
                || name.startsWith("Monitor Ctrl-Break") || name.startsWith("main")
                || name.startsWith("ForkJoinPool.commonPool") || name.startsWith("Cleaner-");
    }

    private static Set<String> threadNames() {
        Set<String> s = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            s.add(t.getName());
        }
        return s;
    }

    /**
     * 调用一个回调：经 JDK 动态代理进入插件的真实实现，超时保护 + 异常可见。
     * 代理同时也记录「插件真的被调到了」（见 {@link RecordingHandler#calls}）。
     */
    private static void callback(String id, String desc, PlaybackExtensionPoint proxy,
                                 RecordingHandler handler,
                                 String methodName, int timeoutSec, Object... args) {
        String detail;
        try {
            Object r = runWithTimeout(methodName + "()", timeoutSec, new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    Method m = findMethod(PlaybackExtensionPoint.class, methodName, args.length);
                    return m.invoke(proxy, args);
                }
            });
            String ret = (r instanceof String) ? (" 返回 \"" + abbrev((String) r, 90) + "\"") : (r == null ? "" : " 返回 " + r);
            detail = methodName + "(" + argTypes(args) + ") 正常返回" + ret;
            check(id, desc, true, detail);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            detail = methodName + " 抛异常: " + oneLine(cause);
            check(id, desc, false, detail);
            cause.printStackTrace(System.out);
        }
    }

    private static String argTypes(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        throw new IllegalStateException("找不到接口方法 " + name + "/" + argc);
    }

    // --------------------------------------------------------- 超时执行工具
    /** 在 harness 线程里限时执行；超时抛 IllegalStateException（不是挂死）。 */
    private static Object runWithTimeout(String what, int timeoutSec, Callable<Object> body) throws Exception {
        FutureTask<Object> task = new FutureTask<>(body);
        Thread th = new Thread(task, "harness-timeout-" + what);
        th.setDaemon(true);
        th.start();
        try {
            return task.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            task.cancel(true);
            throw new IllegalStateException(what + " 超过 " + timeoutSec + "s 未返回（疑似卡死，宿主铁律要求回调只入队立刻 return）");
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            throw new IllegalStateException(c);
        }
    }

    private static void startWithTimeout(final WorkshopPluginManager pm, final String id, int sec) throws Exception {
        runWithTimeout("startPlugin(" + id + ")", sec, new Callable<Object>() {
            @Override
            public Object call() {
                return pm.startPlugin(id);
            }
        });
    }

    private static void stopWithTimeout(final WorkshopPluginManager pm, final String id, int sec) throws Exception {
        runWithTimeout("stopPlugin(" + id + ")", sec, new Callable<Object>() {
            @Override
            public Object call() {
                return pm.stopPlugin(id);
            }
        });
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    // ------------------------------------------------------------- 工具
    private static String prop(String k, String dflt) {
        String v = System.getProperty(k);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    private static String oneLine(Throwable t) {
        if (t == null) {
            return "null";
        }
        String m = t.getMessage();
        return t.getClass().getName() + (m == null ? "" : ": " + m.replace('\n', ' ').replace('\r', ' '));
    }

    private static String abbrev(String s, int n) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', '⏎').replace('\r', '⏎');
        return one.length() <= n ? one : one.substring(0, n) + "…(" + one.length() + " chars)";
    }

    private static String classNames(Collection<?> l) {
        StringBuilder sb = new StringBuilder();
        for (Object o : l) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o.getClass().getName());
        }
        return sb.toString();
    }

    private static String join(List<String> l) {
        return String.join(" | ", l);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void listTree(Path root, String indent) {
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.walk(root, 4)) {
            s.sorted().limit(80).forEach(p -> {
                if (!p.equals(root)) {
                    System.out.println(indent + root.relativize(p));
                }
            });
        } catch (Throwable t) {
            System.out.println(indent + "(列目录失败: " + oneLine(t) + ")");
        }
    }

    // ------------------------------------------------------------------ 收尾
    private static void finish() {
        section("汇总");
        System.out.println("  PASS = " + passed + "   FAIL = " + failed);
        System.out.println();
        if (failed == 0) {
            System.out.println("HARNESS_RESULT=ALL_PASS");
        } else {
            System.out.println("HARNESS_RESULT=FAILED");
            System.out.println("失败项：");
            for (Object[] r : RESULTS) {
                if (!((Boolean) r[2])) {
                    System.out.println("  - [" + r[0] + "] " + r[1] + (r[3] == null ? "" : " → " + r[3]));
                }
            }
        }
        System.out.flush();
        System.exit(failed == 0 ? 0 : 1);
    }

    // ----------------------------------------------------- 回调调用记录代理
    /**
     * 只做两件事：① 记录被调用的方法 + 参数（证明宿主回调真的走到插件）
     * ② 把底层 Throwable 抛出去让 harness 看得见（插件契约要求自己 catch(Throwable)）。
     */
    static final class RecordingHandler implements InvocationHandler {
        final PlaybackExtensionPoint target;
        final boolean useProxyTarget;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        RecordingHandler(PlaybackExtensionPoint target, boolean useProxyTarget) {
            this.target = target;
            this.useProxyTarget = useProxyTarget;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) {
                return "HarnessProxy(" + target.getClass().getName() + ") (calls=" + calls.size() + ")";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == ((args == null || args.length == 0) ? null : args[0]);
            }
            calls.add(name + "/" + (args == null ? 0 : args.length));
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ite) {
                throw ite.getCause();
            }
        }
    }

    private HarnessMain() {
    }
}
