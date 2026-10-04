package com.example.netease.cfg;

import com.example.netease.core.DevMode;
import com.example.netease.core.EventBus;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Levels;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.NativeStreamServer;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件配置（宿主 ConfigManager 的薄封装 + **线程安全快照**）。
 *
 * <p>为什么不读穿：本插件的取值会发生在宿主回调的 IO 线程（扩展点）与事件线程上，
 * 而在这些线程上触碰宿主对象违反死锁铁律（docs/00 §4）。因此：</p>
 * <ul>
 *   <li>只有 {@link HostBridgeWorker} 线程会碰 {@code ConfigManager/ConfigHelper}；</li>
 *   <li>读到的值被拷贝进一个不可变 {@link Snapshot}；</li>
 *   <li>所有 getter 只读 {@code snap} 这个 volatile 引用 —— 任意线程、零宿主交互、绝不抛。</li>
 * </ul>
 *
 * <p><b>0.5.0 瘦身版</b>：配置面只剩三组——基础 {@code config.json}、账号
 * {@code account_cfg.json}、网易云客户端 {@code client_cfg.json}。歌词 / 匹配 / 下载 / 打标
 * 四组配置随功能一并删除（配置页在 0.4.0 就已撤下那五组入口，本版把代码侧也删掉）。</p>
 *
 * <p><b>0.11.30</b>：新增快照分量 {@code dev_mode}（配置页「维护」组那只开关）——
 * 它不改变任何业务行为，只决定插件改写自己装机目录下 {@code preference_config.json} 时
 * 用用户档还是开发者档（{@code core.DevMode}）。</p>
 */
public final class PluginConfig {

    public static final String F_MAIN = "config.json";

    /**
     * P3：账号分组的**宿主托管配置文件**。
     *
     * <p>注意：这里刻意不叫 {@code account.json} —— 那是
     * {@code svc.CookieVault} 的**凭据密文文件**（{@code v1:}+AES-GCM，见 docs/00 §6.7），
     * 两者同名会在同一个插件数据目录里互相覆盖（宿主的 ConfigManager 会写自己的 JSON）。</p>
     */
    public static final String F_ACCOUNT = "account_cfg.json";

    /**
     * 0.11.7：**只读遗留文件** —— 配置页的「网易云客户端」组已按 D1-A 撤下（不再由插件托管），
     * 但老版本用户选的音质档位 {@code audio_level} 还留在里面，必须能读出来做一次性迁移
     * （否则升级会静默把用户选的档位退回默认值）。新值写在 {@link #F_MAIN}。
     *
     * <p>该文件**不再注册写入**、不再出现在 {@code preference_config.json} 里；
     * 迁移完成后（真机数据目录里 {@code config.json} 已有 {@code audio_level}）可整体删除。</p>
     */
    public static final String F_LEGACY_CLIENT = "client_cfg.json";

    private static final String[] ALL_FILES = {F_MAIN, F_ACCOUNT, F_LEGACY_CLIENT};

    /**
     * 账号组键（0.11.23）：**界面上唯一的那一行输入框**（{@code edittext}，标题「登录」）。
     *
     * <p>用户规格要求「登录块只许有一个可交互件」且「当前账号显示在上面那一行」，于是这一行身兼三职，
     * 按**值的形态**分步：11 位手机号 → 自动发码；4~6 位数字 → 自动登录；登录成功后插件把账号串
     * 写回这一行。⚠️ 宿主把它的内容**明文**写进 {@code account_cfg.json}（docs/00 §7），所以它同样
     * 受「短命」纪律约束：读到验证码立刻抹、启动自愈清残留、不进日志/证据。</p>
     */
    private static final String K_LOGIN_INPUT = "login_input";

    /**
     * 账号组键：手机号（**内部键**，0.11.23 起界面上不再单独显示）。
     *
     * <p>「登录」那一行会被登录流程清空、又会被账号串占用，所以号码另存本键：
     * 「手机号原样保留」这条既有约束（docs/00 §6.27）不再依赖界面行的显示内容。</p>
     */
    private static final String K_PHONE = "phone";

    /**
     * 账号组键：短信验证码（**遗留内部键**，0.11.23 起界面上不再有「验证码」行）。
     *
     * <p>⚠️ 这是**一次性凭据**：宿主的 edittext 只能明文写盘，所以 {@code svc.SmsLogin} 的纪律是
     * 「读到即抹」，另外启动时自愈抹残留（老版本升级上来时本键可能还留着值）。任何日志、任何快照、
     * 任何证据采集都不得输出它的值（只允许写位数）。</p>
     */
    private static final String K_SMS_CODE = "sms_code";

    /** 账号组键：当前账号（昵称 + uid）的**内部记录**（0.11.23 起同时写进「登录」那一行给人看）。 */
    private static final String K_CURRENT_ACCOUNT = "current_account";

    /** 配置快照：不可变，跨线程安全发布。 */
    private record Snapshot(
            boolean enabled,
            String logLevel,
            String audioLevel,
            boolean autoLogin,
            String phone,
            boolean audioCache,
            long audioCacheBytes,
            boolean devMode,
            boolean mergePlaylists) {

        String summary() {
            // ⚠️ 手机号是 PII：只报「配没配」，绝不把号码写进日志。
            return "enabled=" + enabled
                    + " log_level=" + logLevel
                    + " audio_level=" + audioLevel
                    + " auto_login=" + autoLogin
                    + " phone=" + (phone == null || phone.isBlank() ? "未填" : "已填(" + phone.length() + "位)")
                    + " native_audio_cache=" + audioCache
                    + " audio_cache_gb=" + (audioCacheBytes / (1024L * 1024L * 1024L))
                    + " dev_mode=" + devMode
                    + " merge_playlists=" + mergePlaylists;
        }
    }

    private static final Snapshot DEFAULTS = new Snapshot(
            true, "info", "lossless", true, "", true, 10L * 1024 * 1024 * 1024, false, true);

    private static final Map<String, ConfigHelper> HELPERS = new ConcurrentHashMap<>();

    /**
     * 「键不存在」哨兵。
     *
     * <p>宿主 {@code ConfigHelper.get(key, default)} 是**按 default 的运行时类型**决定怎么解释 JSON 的
     * （反编译 {@code androidx.compose.ui.hw}：`when (default) { is String -> contentOrNull;
     * is Boolean/Int/Long/Float/Double -> xxxOrNull; else -> default }`）。
     * 所以 <b>传 null 永远只会拿回 null</b>，插件会一直用自己写的默认值 —— 用户改了配置也没用。
     * 这里必须传对应类型的默认值；需要区分「没配过」时用本哨兵（String 类型）再归一成 null。</p>
     */
    private static final String ABSENT = "\u0000absent";

    private static volatile Snapshot snap = DEFAULTS;
    private static volatile String pluginId = "com.example.netease";
    private static volatile ConfigManager manager;

    /** 「账号组变更后」的回调（0.11.22，见 {@link #setAccountChangeHook(Runnable)}）。 */
    private static volatile Runnable accountChangeHook;

    /**
     * 「合并歌单开关变化后」的回调（0.11.46，见 {@link #setMergePlaylistsHook}）。
     * 参数 = 新状态（true 开 / false 关）。
     */
    private static volatile java.util.function.Consumer<Boolean> mergePlaylistsHook;

    private PluginConfig() {
    }

    /** 入队创建 ConfigManager（非阻塞，可在 start() 里安全调用）。 */
    public static void load(String id) {
        if (id != null && !id.isBlank()) {
            pluginId = id.trim();
        }
        HostBridgeWorker.get().submit(PluginConfig::createManager);
    }

    /** ⚠️ 只能在 HostBridgeWorker 线程上执行。 */
    private static void createManager() {
        Throwable first = null;
        try {
            manager = WorkshopApi.manager().createConfigManager(pluginId);
        } catch (Throwable t) {
            first = t;
        }
        if (manager == null) {
            try {
                manager = WorkshopApi.manager().createConfigManager();
            } catch (Throwable t) {
                PluginLog.w("cfg", "创建 ConfigManager 失败，全部使用默认配置",
                        first == null ? t : first);
                return;
            }
        }
        for (String file : ALL_FILES) {
            try {
                ConfigHelper helper = manager.getConfig(file);
                if (helper != null) {
                    HELPERS.put(file, helper);
                }
            } catch (Throwable t) {
                PluginLog.d("cfg", "预建配置分组失败：" + file + " :: " + t);
            }
            try {
                manager.addConfigChangeListener(file, helper ->
                        // ⚠️ 宿主回调线程：只允许投递事件，绝不做重活
                        EventBus.get().offer(new EventBus.ConfigChanged(file)));
            } catch (Throwable t) {
                PluginLog.d("cfg", "注册配置监听失败：" + file + " :: " + t);
            }
        }
        // 0.11.45 补丁（用户需求：开发者模式默认关闭）：先把上次会话残留的 dev_mode=true 复位，
        // 再 refresh —— 顺序不能反，否则 refresh 会先把装机档位改成开发者档、页面又冒开发者行。
        resetDevModeOnStartup();
        refresh();
        PluginLog.i("cfg", "配置已加载 " + snap.summary());
    }

    /**
     * 0.11.45 补丁（用户需求：**开发者模式默认关闭**）：启动时把上次会话留在
     * {@code config.json} 里的 {@code dev_mode=true} 复位为 {@code false}。
     *
     * <p>为什么需要它：宿主只在键**缺失**时才用 schema 的 {@code default_value}；开关一旦被打开过，
     * 宿主就把 {@code true} 落盘，之后每次启动 {@link #refresh()} 都会照实把开发者档写回装机目录
     * —— 用户看到的现象是「重启后开发者行还在」（0.11.45 之前的真机取证：21:55 / 22:01 两次重启
     * 都直接进开发者档）。复位后「开发者模式」变成**会话级开关**：本次会话里打开、重进配置页即可用；
     * 下次插件启动一律回到关闭。schema 的 {@code default_value=false} 仍保持（键缺失时的兜底）。</p>
     *
     * <p>⚠️ 必须在 {@link #refresh()} **之前**调用，且只能在 {@code HostBridgeWorker} 线程上执行
     * （写宿主配置）。装机档位本身由 {@code DevMode.init(...)} 按「关」落用户档，与本方法互补。</p>
     */
    private static void resetDevModeOnStartup() {
        if (!bool(F_MAIN, "dev_mode", false)) {
            return;
        }
        ConfigHelper h = helper(F_MAIN);
        if (h == null) {
            PluginLog.w("cfg", "开发者模式默认关闭：配置组不可用，无法复位（本次可能仍按上次的「开」生效）");
            return;
        }
        try {
            h.set("dev_mode", false);
            h.save();
            PluginLog.i("cfg", "开发者模式默认关闭：已把上次会话留下的「开」复位为「关」"
                    + "（会话级开关；重进插件后不会自动出现开发者行）");
        } catch (Throwable t) {
            PluginLog.w("cfg", "开发者模式复位写盘失败（忽略）：" + t);
        }
    }

    /** 重新读取全部配置项并发布新快照。⚠️ 只能在 HostBridgeWorker 线程上执行。 */
    private static void refresh() {
        if (manager == null) {
            return;
        }
        Snapshot before = snap;
        // 用哨兵默认值区分「没配过」（0.9.0 只有布尔开关 native_audio_cache 时，读不到数值键）
        String configuredCacheGb = rawString(F_MAIN, "audio_cache_gb", ABSENT);
        if (ABSENT.equals(configuredCacheGb)) {
            configuredCacheGb = null;
        }
        long cacheBytes = configuredCacheGb == null
                // 兼容 0.9.0 的布尔开关：旧用户关闭过缓存时，升级后仍为 0 GB。
                ? (bool(F_MAIN, "native_audio_cache", true) ? cacheBytes(null, 10L) : 0L)
                : cacheBytes(configuredCacheGb, 10L);
        Snapshot s = new Snapshot(
                bool(F_MAIN, "enabled", true),
                str(F_MAIN, "log_level", "info"),
                // 音质档位 0.11.7 起归「维护」组（F_MAIN）；老用户的取值还在 client_cfg.json 里，
                // 读不到新键时回退读遗留键，做到「升级不丢用户选择」。白名单见 core.Levels
                level(migratedAudioLevel()),
                // 0.11.46（用户需求：这行搬进「维护」组）⇒ 开关的新写盘落点是 config.json；
                // 老用户的取值还在账号组（account_cfg.json），读不到新键时回退读旧键 ——
                // 「升级不丢用户选择」（与 audio_level 的遗留迁移同一手法）。
                bool(F_MAIN, "auto_login", bool(F_ACCOUNT, "auto_login", true)),
                str(F_ACCOUNT, K_PHONE, ""),
                cacheBytes > 0L,
                cacheBytes,
                // 0.11.30（开发者模式）：配置页「维护」组的那只开关。⚠️ 改这个键会走到下面
                // DevMode.setOn ⇒ 一次小文件改写（两份随包资源互覆），别在这里做重活。
                bool(F_MAIN, "dev_mode", false),
                // 0.11.46（合并歌单，用户需求）：默认开 —— 网易云与本地音乐合并显示在「歌曲」里；
                // 关 ⇒ 同步链路整体停写 + 撤销已写入的 netease-* 行（落地动作走 mergePlaylistsHook）。
                bool(F_MAIN, "merge_playlists", true));
        snap = s;
        PluginLog.setLevel(s.logLevel());
        if (!before.equals(s)) {
            // 配置面唯一可观测落点：改档位/容量/开关后，日志必须能看见快照真的变了
            PluginLog.i("cfg", "配置快照已更新 " + s.summary());
        }
        if (before.devMode() != s.devMode()) {
            // 开关一动就改写装机目录里的 preference_config.json，配置页的行显隐随之切换。
            // ⚠️ 本方法跑在 HostBridgeWorker 线程上：setOn 内部只做一次「先写临时文件再原子覆盖」，
            // 失败也绝不抛（DevMode 自己吞掉并记日志）。
            DevMode.setOn(s.devMode());
        }
        if (before.mergePlaylists() != s.mergePlaylists()) {
            // 0.11.46（合并歌单）：参数就是开/关方向；回调方负责把重活（撤库 / 重跑同步）丢到
            // 非宿主线程 —— 这里仍在 HostBridgeWorker 上，绝不允许阻塞（与 NativeStreamServer 同一纪律）。
            java.util.function.Consumer<Boolean> hook = mergePlaylistsHook;
            if (hook != null) {
                try {
                    hook.accept(s.mergePlaylists());
                } catch (Throwable t) {
                    PluginLog.w("cfg", "合并歌单开关回调抛异常（已兜住）：" + t);
                }
            }
        }
        if (!before.audioLevel().equals(s.audioLevel())
                || before.audioCacheBytes() != s.audioCacheBytes()) {
            // 直链必须随音质立即换代；缓存容量的清理放到后台线程。
            NativeStreamServer.onConfigChanged();
        }
    }

    public static void onConfigChanged(String fileName) {
        PluginLog.i("cfg", "配置已变更：" + fileName);
        HostBridgeWorker.get().submit(() -> {
            refresh();
            // 0.11.22：账号组变了（用户点了某个输入框的「确定」）→ 交给登录流程看一眼。
            // 只有配置页那一排控件的写盘会走到这里；插件自己写「当前账号」也会走到，
            // 此时验证码键是空的 ⇒ SmsLogin 立刻返回，不会自激。
            if (F_ACCOUNT.equals(fileName)) {
                Runnable hook = accountChangeHook;
                if (hook != null) {
                    try {
                        hook.run();
                    } catch (Throwable t) {
                        PluginLog.w("cfg", "账号组变更回调抛异常（已兜住）：" + t);
                    }
                }
            }
        });
    }

    // --------------------------------------------------------------- getters
    // 以下全部只读快照：任意线程可调用，无宿主交互，绝不抛。

    public static boolean enabled() {
        return snap.enabled();
    }

    public static String logLevel() {
        return snap.logLevel();
    }

    /** standard | higher | exhigh | lossless | hires */
    public static String audioLevel() {
        return snap.audioLevel();
    }

    /** P3：启动时是否用本地加密凭据自动恢复登录态（只影响恢复，不影响手动登录后的保存）。 */
    public static boolean autoLogin() {
        return snap.autoLogin();
    }

    /**
     * 0.11.30：开发者模式开关（配置页行显隐）。
     *
     * <p>开 ⇒ 装机目录下的 {@code classes\preference_config.json} 被换成「开发者档」（多出 13 行排查/自检入口）；
     * 关 ⇒ 换回「用户档」。同理只读快照，任意线程可调用，绝不抛；真正的文件改写发生在
     * {@code refresh()} 里（见 {@link DevMode}）。</p>
     *
     * <p><b>0.11.46 追加</b>：关 ⇒ {@code core.Notifier} 的<b>全部用户提示（toast）静默</b> ——
     * 「只要是提示的，开发者模式关闭就完全不会弹」；打开才恢复弹出。</p>
     */
    public static boolean devMode() {
        return snap.devMode();
    }

    /**
     * 0.11.46：合并歌单开关（配置页「维护」组，默认开）。
     *
     * <p>开 ⇒ 同步链路把网易云歌单写进宿主曲库（网易云与本地音乐合并显示在「歌曲」里）；
     * 关 ⇒ 一切同步入口停写，且「歌曲」总列表经 {@code host.HostTrackListGate} 的展示层过滤
     * 只显示本地音乐 —— 网易云歌单 / 曲目 / 关联<b>一行不删</b>（歌单页照常可听）。
     * 状态变化的重活由 {@link #setMergePlaylistsHook} 注册的回调负责（本类只发布快照）。</p>
     */
    public static boolean mergePlaylists() {
        return snap.mergePlaylists();
    }

    /**
     * 手机号（登录用；配置页 {@code edittext}，宿主会把它**明文**写进 {@code account_cfg.json}）。
     *
     * <p>0.11.22 起只用于日志与状态文案；真正的登录取值走 {@link #freshPhone()}（新鲜读）。</p>
     */
    public static String phone() {
        return snap.phone();
    }

    // ------------------------------------------------- 账号组的两行（0.11.23 单行 → 0.11.26 对话框 + 展示行）
    // 0.11.7 的 ui.LoginPrompt 与 0.11.22 的四件套（登录 / 获取验证码 / 验证码 / 确定登录）都已退役，
    // 0.11.23 的单行输入也在 0.11.26 让位给用户明确要的对话框：账号组 = 按钮「登录」（弹出
    // ui.LoginDialog：手机号框 + 验证码框 + 右侧「获取验证码」+ 底部「取消」「登录」）+ 展示行
    // 「当前账号」（current_account，插件写入的登录态真相）+ 开关 + 两个按钮（docs/00 §6.27）。
    // ⚠️ 下面几个方法都要在 HostBridgeWorker 线程上调用（ConfigHelper 的线程规矩，docs/01 §C7）。

    /** 遗留键 {@code login_input}（0.11.23–0.11.25 的输入行，控件已退役）的**新鲜读**：只用于启动自愈清理。 */
    public static String freshLoginInput() {
        return str(F_ACCOUNT, K_LOGIN_INPUT, "");
    }

    /**
     * 写入遗留键 {@code login_input}（0.11.26 起不再有控件指向它；只在清理残留与兼容旧版本时写）。
     *
     * <p>写盘会触发一轮「配置已变更」回调，所以 {@code svc.SmsLogin} 记住「最近一次自己写的值」，
     * 读到它直接返回，不自激。</p>
     */
    public static void writeLoginInput(String text) {
        ConfigHelper h = helper(F_ACCOUNT);
        if (h == null) {
            return;
        }
        try {
            h.set(K_LOGIN_INPUT, text == null ? "" : text);
            h.save();
        } catch (Throwable t) {
            PluginLog.w("cfg", "写「登录」行失败：" + t);
        }
    }

    /** 把手机号写进**内部键**（界面那一行不显示它；理由见 {@link #K_PHONE}）。 */
    public static void writePhone(String phoneText) {
        ConfigHelper h = helper(F_ACCOUNT);
        if (h == null) {
            return;
        }
        try {
            h.set(K_PHONE, phoneText == null ? "" : phoneText);
            h.save();
        } catch (Throwable t) {
            PluginLog.w("cfg", "写手机号失败：" + t);
        }
    }

    /**
     * 手机号的**新鲜读**：不经过快照，直接读宿主内存里的配置值。
     *
     * <p>为什么不用 {@link #phone()}：快照只在「配置变更」事件后才刷新，而用户完全可能刚填完号
     * 就触发下一步——那一刻快照可能还是空的（真机上首次填号尤其常见）。</p>
     */
    public static String freshPhone() {
        return str(F_ACCOUNT, K_PHONE, "");
    }

    /** 验证码的**新鲜读**。⚠️ 返回值是一次性凭据：不得进日志、不得进快照与任何证据报告。 */
    public static String freshSmsCode() {
        return str(F_ACCOUNT, K_SMS_CODE, "");
    }

    /**
     * 抹掉配置页里残留的验证码（读后即抹 / 启动自愈 / 格式不符）。
     *
     * <p>空值不写：既省一次无谓的落盘，也避免我们自己触发一轮「配置已变更」回调。</p>
     */
    public static void wipeSmsCode(String why) {
        ConfigHelper h = helper(F_ACCOUNT);
        if (h == null) {
            return;
        }
        try {
            Object cur = h.get(K_SMS_CODE, "");
            if (cur != null && !String.valueOf(cur).isBlank()) {
                h.set(K_SMS_CODE, "");
                h.save();
                PluginLog.i("cfg", "已抹掉配置页里的验证码（" + why + "）");
            }
        } catch (Throwable t) {
            PluginLog.w("cfg", "抹掉验证码失败（" + why + "）：" + t);
        }
    }

    /**
     * 回填配置页「当前账号」行（只读展示；宿主会把它连同其它键一起写盘）。
     *
     * <p><b>值没变就不写盘（0.11.31 / 坑 41）</b>：本键写盘会触发宿主配置变更回调 →
     * {@code onConfigChanged} → 账号组钩子 → 又回到回写这一行。若无条件 {@code set + save}，
     * 就是「写 → 变更 → 再写」的自激死循环：真机上实测每秒上千行日志、宿主进程烧掉 283 s CPU
     * （0.11.30 装机后 3 分钟内发生，本键 + 钩子的组合才成环）。同值直接返回是这道闸的第一层，
     * 第二层在 {@code svc.SmsLogin#refreshAccountRow()}（连日志也不打，免得刷屏）。</p>
     */
    public static void writeCurrentAccount(String text) {
        ConfigHelper h = helper(F_ACCOUNT);
        if (h == null) {
            return;
        }
        String want = text == null ? "" : text;
        if (want.equals(freshCurrentAccount())) {
            return;
        }
        try {
            h.set(K_CURRENT_ACCOUNT, want);
            h.save();
        } catch (Throwable t) {
            PluginLog.w("cfg", "写「当前账号」行失败：" + t);
        }
    }

    /** 「当前账号」键的**新鲜读**（内部记录；0.11.23 起它同时出现在「登录」那一行给人看）。 */
    public static String freshCurrentAccount() {
        return str(F_ACCOUNT, K_CURRENT_ACCOUNT, "");
    }

    /**
     * 注入「账号组变更后的回调」（0.11.22）。
     *
     * <p>用注入而不是直接调用：cfg 包不必反向依赖 svc 包，与
     * {@code svc.AccountService.setAfterLoginHook} 同一手法。注册方 =
     * {@code NeteasePlugin.start()} → {@code svc.SmsLogin::afterAccountConfigChanged}。</p>
     */
    public static void setAccountChangeHook(Runnable hook) {
        accountChangeHook = hook;
    }

    /**
     * 注入「合并歌单开关变化后的回调」（0.11.46）。
     *
     * <p>用注入而不是直接调用：cfg 包不必反向依赖插件主类，与 {@link #setAccountChangeHook} 同一手法。
     * 注册方 = {@code NeteasePlugin.start()} → {@code NeteasePlugin::onMergePlaylistsChanged}；
     * ⚠️ 回调发生在 {@code refresh()} 里（HostBridgeWorker 线程）——实现方只许**立即返回**，
     * 重活（撤库 / 重跑同步）必须另起非宿主线程。</p>
     */
    public static void setMergePlaylistsHook(java.util.function.Consumer<Boolean> hook) {
        mergePlaylistsHook = hook;
    }

    /** 是否持久保留边听边下的音频；0 GB 模式为 false。 */
    public static boolean audioCacheEnabled() {
        return snap.audioCache();
    }

    /** 播放缓存上限；0 表示只保留当前曲目的临时数据，切歌即删除。 */
    public static long audioCacheBytes() {
        return snap.audioCacheBytes();
    }

    // ----------------------------------------------------------------- 内部

    private static ConfigHelper helper(String fileName) {
        return HELPERS.get(fileName);
    }

    private static Object raw(String file, String key, Object typedDefault) {
        ConfigHelper h = helper(file);
        if (h == null) {
            return null;
        }
        try {
            return h.get(key, typedDefault);
        } catch (Throwable t) {
            PluginLog.d("cfg", "读取配置项失败 " + file + "/" + key + " :: " + t);
            return null;
        }
    }

    /** 读字符串项：必须用 String 默认值，宿主才会返回 JSON 里的字符串内容。 */
    private static String rawString(String file, String key, String def) {
        Object v = raw(file, key, def);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(String file, String key, boolean def) {
        Object v = raw(file, key, Boolean.valueOf(def));
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return def;
    }

    private static String str(String file, String key, String def) {
        String v = rawString(file, key, def);
        if (v == null) {
            return def;
        }
        return v.isBlank() ? def : v;
    }

    private static String level(String v) {
        String canonical = Levels.canonical(v);
        return canonical == null ? Levels.DEFAULT : canonical;
    }

    /**
     * 音质档位的一次性迁移（0.11.7）：新键在 {@code config.json}，老键在 {@code client_cfg.json}。
     *
     * <p>新键有效值优先；新键缺失时回退读老键。两者都没有 ⇒ 返回值会让上层落到 {@link Levels#DEFAULT}。
     * 读到老键时会打一行 INFO，便于在真机上确认「用户的选择没被升级吞掉」。</p>
     */
    private static String migratedAudioLevel() {
        String fresh = rawString(F_MAIN, "audio_level", ABSENT);
        if (fresh != null && !ABSENT.equals(fresh) && !fresh.isBlank()) {
            return fresh;
        }
        String legacy = rawString(F_LEGACY_CLIENT, "audio_level", ABSENT);
        if (legacy != null && !ABSENT.equals(legacy) && !legacy.isBlank()) {
            PluginLog.i("cfg", "音质档位迁移：从遗留文件 " + F_LEGACY_CLIENT + " 读到 audio_level=" + legacy
                    + "（新键 " + F_MAIN + "/audio_level 生效后即可删该文件）");
            return legacy;
        }
        return null;
    }

    private static long cacheBytes(Object value, long defGb) {
        try {
            long gb = value == null ? defGb
                    : value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
            gb = Math.max(0L, Math.min(1000L, gb));
            return Math.multiplyExact(gb, 1024L * 1024L * 1024L);
        } catch (Throwable ignored) {
            return defGb * 1024L * 1024L * 1024L;
        }
    }
}
