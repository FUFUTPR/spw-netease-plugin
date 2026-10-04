package com.example.netease.host;

import com.example.netease.NeteasePlugin;
import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.NativeLibrary;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「合并歌单」的展示门（0.11.46 真机修正；用户口径：关掉开关后宿主<b>「歌曲」总列表只显示本地
 * 音乐</b>，网易云接入的<b>歌单不会消失</b>）。
 *
 * <p><b>为什么不是撤库</b>：0.11.46 首版关闭时调 {@link NativeLibrary#purgeAll()} 撤掉全部
 * {@code netease-*} 行（真机日志「已撤销插件写入：歌单 4 / 曲目 3269 / 关联 5939」），用户看到的
 * 却是<b>歌单也一起没了</b> —— 不可接受。javap 逐层核对宿主数据链路：「歌曲」页（{@code fH}
 * TrackScreen）→ {@code fQ} TrackViewModel → 静态方法 {@code \u071b.\u0528()}（TrackRepo.getAllFlow）
 * → 接口 {@code \u0d10.\u0528()}（TrackDao.getAllFlow），内嵌 SQL 就是 {@code SELECT * FROM Track}，
 * <b>无任何过滤条件</b>；而「歌单」页走 {@code PlaylistDao} 的 INNER JOIN 查同一张 Track 表 ——
 * 删行两边都空，这是首版的根本错误。</p>
 *
 * <p><b>本版做法（数据一行不动，只换「歌曲」列表看到的视图）</b>：{@code \u071b}（TrackRepo，
 * Kotlin object）的私有静态字段 {@code \u0528} 持有 {@code \u0d10}（TrackDao）实现；
 * {@code \u0528()}（getAllFlow）是<b>全宿主唯一</b>被「歌曲」页消费的入口（javap 全引用面核对：
 * 30 个引用 {@code \u071b} 的类里只有 TrackViewModel 调它）。本类在宿主进程内把该字段换成
 * 动态代理：只拦截那一个方法，把返回的 Flow 用 {@code kotlinx.coroutines.flow.FlowKt.map} 包一层
 * <b>逐列表变换</b> —— 这条流的元素是 {@code List<Track>}（DAO 返回 {@code Flow<List<Track>>}），
 * 开关关闭时把列表里 {@code Track.id} 以 {@code netease-} 开头的插件行剔除，其余全部保留；其它
 * DAO 方法一律原样转发给真实现。歌单页的 PlaylistDao 是另一个对象，完全不受影响。</p>
 *
 * <p><b>为什么是 map 不是 filter（0.11.46 真机二次修正）</b>：首版把谓词挂在 {@code FlowKt.filter}
 * 上，但流的元素是<b>整份列表</b>不是单首歌 —— 谓词拿到 {@code ArrayList} 后反射 {@code getId()}
 * 抛 {@code NoSuchMethodException}，异常被兜底吞掉 ⇒ 每次整份列表原样放行，开关关闭后列表毫无
 * 变化（真机症状：谓词日志有、过滤效果无）。改成 {@code map} 后在<b>列表内部</b>逐首判定，才真正
 * 实现「关 ⇒ 歌曲总列表只剩本地」。</p>
 *
 * <p><b>0.11.47 两项追加（数据同样一行不动）</b>：① <b>列表去重</b> —— 变换里对「歌名 + 标签
 * （专辑）+ 作者」三要素完全一样的曲目只显示一行，保留<b>占用空间最大</b>的那一个（大小并列时
 * 本地行优先，再并列时长者优先，再并列保持原顺序）。插件流曲目的 size 通常为 0 ⇒ 与本地行重复
 * 时被隐藏的必然是插件行（用户口径：「两个歌名、标签、作者完全一样 … 占用空间最小的那一个」）。
 * ② <b>扫库屏蔽</b> —— 宿主「刷新音乐库」会把全部已有曲目与本地扫描结果 union 后逐首
 * {@code TagParser.readAudioTag}；插件曲目是 {@code http://127.0.0.1:17788/...} 读不到，便走
 * {@code \u069e}（TrackRepoKt）的 updateByUnreadableVris 把整批插件行标成 readable=false
 * （UI 上全变感叹号）。本类在安装时把 {@code \u069e} 自己的 DAO 静态字段 {@code \u0528} 也换成
 * 同款代理，拦截「元素全是 {@code \u0d37}（只含 id，构造即 readable=false）的 List 写入」，
 * 把 id 以 {@code netease-} 开头的元素从批次里剔除（整批全是插件行就整批不落库）⇒
 * 扫库只管本地文件，插件曲目不再被标不可读。两处 DAO 字段存的是<b>同一个 Room 实现对象</b>，
 * 但放在两个不同的静态字段里，必须各装一次。</p>
 *
 * <p><b>0.11.48 追加（刷新音乐库联动）</b>：宿主点「刷新音乐库」（按钮 / 热键 / 菜单，入口全是
 * {@code er.\u0528()}）时，除扫本地曲库外<b>顺带 force 一轮网易云同步</b> —— 用户在网易云侧新加
 * 的歌，点一下就能刷出来。实现 = 把 {@code er} 的「扫库中」状态（{@code \u052c}，MutableStateFlow）
 * 换成动态代理：扫库协程开头那次 {@code emit(true)} 即触发 {@code NeteasePlugin.syncFromRefreshLibrary()}
 * （与配置页「立即重新同步」同通路，静默不弹窗）。详见 {@link #installRefreshHook()}。</p>
 *
 * <p><b>即时生效</b>：开关切换时 {@link #refresh(String)} 在宿主自己的 Room 连接上执行一条同值
 * {@code UPDATE}（点着 TEMP 失效触发器 ⇒ UI 的 Flow 重新发射 ⇒ 谓词重新求值）。写库通路见
 * {@link HostSqlBridge}（同连接才会点着宿主那份 TEMP 触发器，这是 0.7.0 起验证过的机制）。</p>
 *
 * <p><b>安装时机</b>：必须在宿主 UI 首次调用 {@code \u071b.\u0528()}（创建歌曲页 ViewModel）之前
 * 替换掉静态字段，所以 {@code NeteasePlugin.start()} 一起来就投递（{@link #installAsync}）。
 * 字段是 {@code private static final}（普通反射写不进、{@code VarHandle} 对 final 也不给写）⇒ 与
 * {@code VoxzenBridge} 的声源切换器同一手法：{@code sun.misc.Unsafe}（宿主 JRE 含
 * {@code jdk.unsupported}，0.3.10 起已实证）。</p>
 *
 * <p><b>降级</b>：宿主结构不符 / Unsafe 被拒 / 找不到 {@code FlowKt.map} ⇒ 只记日志、<b>不装门</b>，行为退回
 * 「关掉开关也不隐藏」（与首版之前的可见性一致）—— 绝不影响插件其它功能与宿主稳定性。</p>
 *
 * <p><b>线程纪律</b>：安装与刷新经 {@link HostBridgeWorker}（安装经它投递，刷新经
 * {@link HostSqlBridge#onHostConnection} 投递，两者都保证碰宿主类只在唯一宿主交互线程上）；
 * 谓词由宿主协程线程回调，只做「读插件配置 + 反射读一个已加载类的方法」，不加载任何新宿主类。</p>
 */
public final class HostTrackListGate {

    private static final String TAG = "track-gate";

    /** 宿主 TrackRepo（Kotlin object）：{@code androidx.compose.ui.\u071b}（U+071B）。 */
    private static final String CLS_REPO = "androidx.compose.ui.\u071b";

    /**
     * 宿主 TrackDao 接口：{@code androidx.compose.ui.ஐ}（**U+0B90**，泰米尔字母 AI）。
     *
     * <p>⚠️ 别写成看起来同形的 U+0D10（马拉雅拉姆 {@code ഐ}）—— 那是个完全无关的 Compose 类
     * （Brush 相关），字节级核对：TrackRepo 字段描述符里的类型名是 {@code e0 ae 90}（U+0B90）。</p>
     */
    private static final String CLS_DAO = "androidx.compose.ui.\u0b90";

    /** TrackRepo 里持有 DAO 的静态字段名：{@code \u0528}（U+0528）。 */
    private static final String NAME_FIELD_DAO = "\u0528";

    /** 要过滤的方法名：{@code \u0528()}（无参、返回 {@code Flow<List<Track>>} = getAllFlow）。 */
    private static final String NAME_GET_ALL_FLOW = "\u0528";

    private static final String CLS_FLOW = "kotlinx.coroutines.flow.Flow";

    /** 宿主 TrackRepoKt（顶层函数所在的 Kotlin object）：{@code androidx.compose.ui.\u069e}（U+069E）。 */
    private static final String CLS_TRACK_REPO_KT = "androidx.compose.ui.\u069e";

    /**
     * 扫库写「不可读」用的数据类：{@code androidx.compose.ui.\u0d37}（**U+0D37**）。只含 id 与
     * readable 两个字段，构造参数只有 id 且 readable 恒置 false —— 所以「元素是它」的 List 写入
     * 等价于「把这些曲目标成不可读（感叹号）」。
     *
     * <p>⚠️ 别写成看起来同形的手抄字符 —— 字节级核对：updateByUnreadableVris 里
     * {@code new \u0d37(track.getId())} 的类名首字符 UTF-8 是 {@code e0 b4 b7}（U+0D37）。</p>
     */
    private static final String CLS_TRACK_READABLE_MARK = "androidx.compose.ui.\u0d37";

    /** TrackDao 里写「可读状态」的方法名：{@code \u037f(List<\u0d37>, Continuation)}（U+037F）。 */
    private static final String NAME_UPDATE_READABLE = "\u037f";

    /** 宿主「刷新音乐库」控制器（Kotlin object）：{@code androidx.compose.ui.er}（0.11.48 联动用）。 */
    private static final String CLS_REFRESH_CTRL = "androidx.compose.ui.er";

    /**
     * {@code er} 里「扫库中」状态字段名：{@code \u052c}（U+052C，{@code MutableStateFlow<Boolean>}）。
     * 宿主扫库协程 {@code er$\u037f} 一开头就 {@code er.\u052a().emit(true)}，且全宿主只有它访问该流 ⇒
     * emit(true) == 用户点了「刷新音乐库」（收尾的 emit(false) 不触发）。
     */
    private static final String NAME_FIELD_SCAN_STATE = "\u052c";

    /** 代理「扫库中」状态用的接口：{@code kotlinx.coroutines.flow.MutableStateFlow}。 */
    private static final String CLS_MUTABLE_STATE_FLOW = "kotlinx.coroutines.flow.MutableStateFlow";

    /**
     * 「歌曲」列表刷新的同值 UPDATE：命中一行即触发 Room 的 TEMP UPDATE 触发器（值不变也会触发）；
     * 表为空时 WHERE 不命中，什么也不发生（此时也没有 UI 内容需要刷新）。
     */
    private static final String SQL_TOUCH_TRACK =
            "UPDATE Track SET readable = readable WHERE rowid = (SELECT MIN(rowid) FROM Track)";

    private static volatile boolean installed;

    private static volatile String lastNote = "尚未安装（宿主 UI 若已建流，需重进歌曲页或重启宿主）";

    /** {@code FlowKt.map(Flow, Function2)} —— 逐列表变换的入口（安装时解析并缓存）。 */
    private static volatile Method mapMethod;

    /** 传给 {@code FlowKt.map} 的变换器（{@code Function2} 的动态代理，宿主类加载器版本）。 */
    private static volatile Object mapTransformer;

    /** 缓存 {@code Track.getId()}（变换器在宿主协程线程上按曲目逐条调用，避免每次查方法表）。 */
    private static volatile Method idGetter;

    /** 去重用：{@code Track} 的公开 getter 读取器（三要素 + size/duration，按类缓存方法对象）。 */
    private static final Getter TITLE = new Getter("getTitle");
    private static final Getter ARTIST = new Getter("getArtist");
    private static final Getter ALBUM = new Getter("getAlbum");
    private static final Getter SIZE = new Getter("getSize");
    private static final Getter DURATION = new Getter("getDuration");

    /** 扫库屏蔽用：{@code \u0d37.\u037f()}（读「不可读」批次里每首的 id）。 */
    private static final Getter MARK_ID = new Getter("\u037f");

    /** 扫库屏蔽（第二处 DAO 静态字段，{@code \u069e.\u0528}）是否装好；失败只降级，不影响歌曲列表门。 */
    private static volatile boolean scanGateInstalled;

    /** 「刷新音乐库」联动（{@code er.\u052c} 状态门）是否装好；失败只降级，不影响其它两道门。 */
    private static volatile boolean refreshGateInstalled;

    /** 取证：扫库开始信号累计命中次数（宿主每点一次「刷新音乐库」+1）。 */
    private static final AtomicInteger REFRESH_TRIGGERS = new AtomicInteger();

    /** 去抖：同一次点击可能连发两次信号（取消旧任务再启新任务），5 秒内只认第一次。 */
    private static final AtomicLong LAST_REFRESH_TRIGGER_MS = new AtomicLong();
    private static final long REFRESH_DEBOUNCE_MS = 5_000L;

    /** 安装期自检（dry-run 门）用：是否命中「扫库开始」信号。 */
    private static volatile boolean refreshSelfTestHit;

    /** {@code kotlin.Unit.INSTANCE} 缓存（整批插件行被拦下时，作为 suspend 调用的完成返回值）。 */
    private static volatile Object unitInstance;

    /** 取证：扫库「不可读」写批次里被拦下的插件曲目累计数。 */
    private static final AtomicInteger UNREADABLE_WRITES_BLOCKED = new AtomicInteger();

    /** 取证：含插件行的「不可读」写批次次数（采样打日志用）。 */
    private static final AtomicInteger UNREADABLE_BLOCK_CALLS = new AtomicInteger();

    /** 取证：扫库过滤内部异常只记一次（异常 = 该条按本地放行，宁多写不误拦）。 */
    private static final AtomicBoolean UNREADABLE_FAIL_LOGGED = new AtomicBoolean();

    /** 取证：累计被去重隐藏的曲目数（三要素完全相同，占用空间更小者）。 */
    private static final AtomicInteger DEDUP_DROPPED_TOTAL = new AtomicInteger();

    /** 取证：{@code \u0528()}（getAllFlow）被调用的次数（宿主 UI 每次建流一次，频率极低）。 */
    private static final AtomicInteger FLOW_CALLS = new AtomicInteger();

    /** 取证：列表变换被调用的次数（每次 Flow 发射一次，频率低；只在采样点打日志）。 */
    private static final AtomicInteger TRANSFORMS = new AtomicInteger();

    /** 取证：累计隐藏的插件曲目数（开关关闭期间）。 */
    private static final AtomicInteger DROPPED_TOTAL = new AtomicInteger();

    /** 取证：变换器「开始工作」只记一次。 */
    private static final AtomicBoolean TRANSFORM_LOGGED = new AtomicBoolean();

    /** 取证：变换内部异常只记一次（异常 = 判错就整份放行，绝不误藏本地曲目）。 */
    private static final AtomicBoolean TRANSFORM_FAIL_LOGGED = new AtomicBoolean();

    /** 取证：开关切换后是否观察到一次宿主重查（决定「UI 用的到底是不是带变换的流」）。 */
    private static final AtomicBoolean AWAIT_REQUERY = new AtomicBoolean();

    private HostTrackListGate() {
    }

    /** 门是否已装好（装好后「歌曲」列表才随开关过滤）。 */
    public static boolean installed() {
        return installed;
    }

    /** 最近一次安装/降级的单行中文摘要（配置页或日志取证用）。 */
    public static String lastNote() {
        return lastNote;
    }

    /**
     * 启动即装（幂等）。**越早越好**：必须抢在宿主 UI 首次访问 TrackRepo 之前。
     * 走 {@link HostBridgeWorker}（唯一宿主交互线程），失败只记日志、绝不抛。
     */
    public static void installAsync() {
        HostBridgeWorker.get().submit(HostTrackListGate::installOnWorker);
    }

    // ------------------------------------------------------------ 安装（宿主交互线程内）

    private static void installOnWorker() {
        installRefreshHook();   // 0.11.48：独立于列表门的「刷新音乐库」联动，先装（幂等，失败只记日志）
        if (installed) {
            return;
        }
        try {
            Class<?> repo = Class.forName(CLS_REPO);
            Field field = repo.getDeclaredField(NAME_FIELD_DAO);
            field.setAccessible(true);
            Object current = field.get(null);
            if (current == null) {
                note("失败：TrackRepo." + NAME_FIELD_DAO + " 为空（宿主结构不符）", false, null);
                return;
            }
            if (isGate(current)) {
                // 幂等复核：本进程早前已装过列表门；扫库屏蔽字段也复核一遍（它可能是老版本漏装的）
                installed = true;
                installScanGate(loadDaoInterface());
                note("已安装（幂等复核；扫库屏蔽=" + (scanGateInstalled ? "已装" : "未装")
                        + "；刷新音乐库联动=" + (refreshGateInstalled ? "已装" : "未装") + "）", true, null);
                return;
            }
            if (!prepareMap()) {
                note("失败：找不到 FlowKt.map / Function2（宿主协程库结构不符），为稳妥不装门", false, null);
                return;
            }
            Class<?> daoItf = Class.forName(CLS_DAO);
            if (!daoItf.isInterface() || !daoItf.isInstance(current)) {
                note("失败：" + CLS_DAO + " 不是 TrackRepo." + NAME_FIELD_DAO
                        + " 实现的接口（宿主结构变化），不装门", false, null);
                return;
            }
            Object gate = Proxy.newProxyInstance(
                    current.getClass().getClassLoader(),
                    new Class<?>[]{daoItf},
                    new DaoGate(current));
            String why = putStaticFinal(field, gate);
            if (why != null) {
                note("失败：Unsafe 写回静态字段被拒（" + why + "）", false, null);
                return;
            }
            Object back = field.get(null);
            boolean ok = isGate(back);
            installed = ok;
            if (ok) {
                installScanGate(daoItf);
                note("已安装：宿主「歌曲」列表门（关「合并歌单」只显示本地 + 同名三要素去重保大）"
                        + "；扫库屏蔽=" + (scanGateInstalled ? "已装（扫库不再把插件曲目标不可读）"
                                                              : "未装（降级：只保证列表门）")
                        + "；刷新音乐库联动=" + (refreshGateInstalled ? "已装（点刷新音乐库会顺带同步网易云）"
                                                                     : "未装（降级：刷新音乐库不触发同步）"), true, null);
            } else {
                note("失败：写回后读回不是门对象（宿主结构不符）", false, null);
            }
        } catch (Throwable t) {
            note("失败：" + brief(t), false, t);
        }
    }

    /** 解析 TrackDao 接口（幂等复核路径用）；失败返回 null，调用方自行降级。 */
    private static Class<?> loadDaoInterface() {
        try {
            Class<?> daoItf = Class.forName(CLS_DAO);
            return daoItf.isInterface() ? daoItf : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 第二处 DAO 静态字段的安装（扫库屏蔽，0.11.47）：把 {@code \u069e}（TrackRepoKt）自己的
     * {@code \u0528} 字段也换成同款动态代理。
     *
     * <p><b>为什么必须单独装</b>：宿主「刷新音乐库」读不到插件曲目（http 流）后，走 {@code \u069e}
     * 的私有 updateByUnreadableVris，其中调的是<b>它自己的静态字段</b> {@code \u069e.\u0528}
     * （与 {@code \u071b.\u0528} 指向同一个 Room 实现对象，但字段分离）—— 只换 {@code \u071b}
     * 的字段拦不到这条写。失败只记日志（降级 = 扫库仍会把插件曲目标不可读），绝不影响列表门与
     * 宿主稳定性。</p>
     */
    private static void installScanGate(Class<?> daoItf) {
        try {
            if (daoItf == null) {
                PluginLog.w(TAG, "扫库屏蔽：TrackDao 接口不可用，跳过安装（降级）");
                return;
            }
            Class<?> kt = Class.forName(CLS_TRACK_REPO_KT);
            Field field = kt.getDeclaredField(NAME_FIELD_DAO);
            field.setAccessible(true);
            Object current = field.get(null);
            if (current == null) {
                PluginLog.w(TAG, "扫库屏蔽：TrackRepoKt." + NAME_FIELD_DAO + " 为空（宿主结构不符），跳过安装（降级）");
                return;
            }
            if (isGate(current)) {
                scanGateInstalled = true;
                PluginLog.i(TAG, "扫库屏蔽：已安装（幂等复核）");
                return;
            }
            if (!daoItf.isInstance(current)) {
                PluginLog.w(TAG, "扫库屏蔽：" + CLS_TRACK_REPO_KT + "." + NAME_FIELD_DAO
                        + " 不是 " + CLS_DAO + " 实例（宿主结构不符），跳过安装（降级）");
                return;
            }
            Object gate = Proxy.newProxyInstance(
                    current.getClass().getClassLoader(),
                    new Class<?>[]{daoItf},
                    new DaoGate(current));
            String why = putStaticFinal(field, gate);
            if (why != null) {
                PluginLog.w(TAG, "扫库屏蔽：Unsafe 写回静态字段被拒（" + why + "），降级为不屏蔽");
                return;
            }
            scanGateInstalled = isGate(field.get(null));
            if (scanGateInstalled) {
                PluginLog.i(TAG, "扫库屏蔽：已安装 —— 扫库给插件曲目标「不可读」的写会先被过滤（netease-* 一个都不落库）");
                PluginLog.i(TAG, "扫库屏蔽自检：" + scanGateSelfTest(daoItf, field));
            } else {
                PluginLog.w(TAG, "扫库屏蔽：写回后读回不是门对象（宿主结构不符），降级为不屏蔽");
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "扫库屏蔽安装失败（忽略，不影响歌曲列表门）：" + brief(t));
        }
    }

    /**
     * 安装期自检（0.11.47）：在宿主进程里构造一个 {@code \u0d37("netease-SELFTEST-NOEXIST")}，
     * 经门调用「标不可读」接口 —— 正常情况会被代理拦下（计数器 +1、<b>不触库</b>）；若拦截分支
     * 没命中，这个 id 也不存在于曲库，UPDATE 命中 0 行，依然安全。
     *
     * <p>返回一行中文结论（已装门为前提）；任何异常只记日志，绝不影响宿主。</p>
     */
    private static String scanGateSelfTest(Class<?> daoItf, Field field) {
        try {
            Object gate = field.get(null);
            if (!isGate(gate)) {
                return "跳过（字段不是门对象）";
            }
            Class<?> markCls = Class.forName(CLS_TRACK_READABLE_MARK);
            Object mark = markCls.getConstructor(String.class)
                    .newInstance(NativeLibrary.ID_PREFIX + "SELFTEST-NOEXIST");
            Method target = null;
            for (Method m : daoItf.getMethods()) {
                if (NAME_UPDATE_READABLE.equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == List.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                return "跳过（接口里找不到 \u037f(List, Continuation)）";
            }
            List<Object> probe = new ArrayList<>(1);
            probe.add(mark);
            int before = UNREADABLE_WRITES_BLOCKED.get();
            target.invoke(gate, probe, null);
            int blocked = UNREADABLE_WRITES_BLOCKED.get() - before;
            return blocked >= 1 ? "通过（探针曲目被拦下，未触库）"
                                : "未拦下（拦截分支没命中，请上报）";
        } catch (Throwable t) {
            return "异常（忽略）：" + brief(t);
        }
    }

    // ------------------------------------------------------------ 「刷新音乐库」联动（0.11.48）

    /**
     * 安装「刷新音乐库」联动（0.11.48）：把宿主 {@code androidx.compose.ui.er}（「刷新音乐库」控制器，
     * Kotlin object）的「扫库中」状态字段 {@code \u052c}（{@code MutableStateFlow<Boolean>}）换成动态代理。
     *
     * <p><b>为什么钉这个字段</b>：宿主扫库入口 {@code er.\u0528()} 每次都新起一个 {@code er$\u037f}
     * 协程；协程一开头（锁到互斥量后）就调 {@code er.\u052a().emit(true)}。全引用面核对：该
     * MutableStateFlow 只有 {@code er$\u037f} 一个访问方、{@code emit(true)} 只此一处 ⇒「收到
     * emit(true)」==「用户点了刷新音乐库（按钮 / 热键 / 菜单）」。扫库收尾的 emit(false) 不触发。</p>
     *
     * <p><b>联动动作</b>：{@link NeteasePlugin#syncFromRefreshLibrary()} —— 与配置页「立即重新同步」
     * 同一条 force 通路（绕 30 分钟间隔闸、不绕在飞闸），静默：未登录 / 关闭合并歌单 / 已有同步在飞
     * 时只写日志。信号在宿主协程线程上，联动只起一条非宿主线程（spawnNativeSync 内部），不占扫库线程。</p>
     *
     * <p>降级：结构不符 / Unsafe 被拒 ⇒ 只记日志，不影响列表门与扫库屏蔽。</p>
     */
    private static void installRefreshHook() {
        try {
            Class<?> ctrl = Class.forName(CLS_REFRESH_CTRL);
            Field field = ctrl.getDeclaredField(NAME_FIELD_SCAN_STATE);
            field.setAccessible(true);
            Object current = field.get(null);
            if (current == null) {
                PluginLog.w(TAG, "刷新音乐库联动：" + CLS_REFRESH_CTRL + "." + NAME_FIELD_SCAN_STATE
                        + " 为空（宿主结构不符），跳过安装（降级）");
                return;
            }
            if (isRefreshGate(current)) {
                refreshGateInstalled = true;
                PluginLog.i(TAG, "刷新音乐库联动：已安装（幂等复核）");
                return;
            }
            Class<?> msf = Class.forName(CLS_MUTABLE_STATE_FLOW);
            if (!msf.isInterface() || !msf.isInstance(current)) {
                PluginLog.w(TAG, "刷新音乐库联动：" + CLS_REFRESH_CTRL + "." + NAME_FIELD_SCAN_STATE
                        + " 不是 " + CLS_MUTABLE_STATE_FLOW + " 实例（宿主结构不符），跳过安装（降级）");
                return;
            }
            Object gate = Proxy.newProxyInstance(
                    current.getClass().getClassLoader(),
                    new Class<?>[]{msf},
                    new RefreshGate(current));
            String why = putStaticFinal(field, gate);
            if (why != null) {
                PluginLog.w(TAG, "刷新音乐库联动：Unsafe 写回静态字段被拒（" + why + "），降级为不联动");
                return;
            }
            refreshGateInstalled = isRefreshGate(field.get(null));
            if (refreshGateInstalled) {
                PluginLog.i(TAG, "刷新音乐库联动：已安装 —— 宿主「刷新音乐库」开始时顺带触发一轮网易云同步（force）");
                PluginLog.i(TAG, "刷新音乐库联动自检：" + refreshHookSelfTest(msf));
            } else {
                PluginLog.w(TAG, "刷新音乐库联动：写回后读回不是门对象（宿主结构不符），降级为不联动");
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "刷新音乐库联动安装失败（忽略，不影响其它两道门）：" + brief(t));
        }
    }

    /**
     * 安装期自检（0.11.48）：拿真实的 {@code MutableStateFlow.emit} 方法对象，在 dry-run 门下走一遍 ——
     * {@code emit(true)} 必须被识别为扫库开始、{@code emit(false)} 必须不识别。dry-run 不碰真流、
     * 也不触发同步（只置一个探针标记）。
     */
    private static String refreshHookSelfTest(Class<?> msfItf) {
        try {
            Method emit = msfItf.getMethod("emit", Object.class, Class.forName("kotlin.coroutines.Continuation"));
            RefreshGate probe = new RefreshGate(Boolean.FALSE, true);
            refreshSelfTestHit = false;
            probe.invoke(null, emit, new Object[]{Boolean.TRUE, null});
            boolean hitTrue = refreshSelfTestHit;
            refreshSelfTestHit = false;
            probe.invoke(null, emit, new Object[]{Boolean.FALSE, null});
            boolean hitFalse = refreshSelfTestHit;
            refreshSelfTestHit = false;
            return hitTrue && !hitFalse
                    ? "通过（emit(true) 识别为扫库开始，emit(false) 不触发）"
                    : "未通过（信号识别异常，联动可能不生效：" + (hitTrue ? "true 已识别" : "true 未识别")
                            + "、" + (hitFalse ? "false 误触发" : "false 未触发") + "）";
        } catch (Throwable t) {
            return "异常（忽略）：" + brief(t);
        }
    }

    /** 字段/对象是不是本类装的「刷新音乐库」状态门（幂等复核用）。 */
    private static boolean isRefreshGate(Object o) {
        if (o == null || !Proxy.isProxyClass(o.getClass())) {
            return false;
        }
        return Proxy.getInvocationHandler(o) instanceof RefreshGate;
    }

    /**
     * 纯判定（离线探针可测）：这次调用是不是「扫库开始」信号 ——
     * {@code MutableStateFlow.emit(value, continuation)} 且 {@code value == Boolean.TRUE}。
     */
    public static boolean isScanStartCall(Method method, Object[] args) {
        return method != null && "emit".equals(method.getName())
                && args != null && args.length == 2 && Boolean.TRUE.equals(args[0]);
    }

    /** 扫库开始时的联动动作：静默触发一轮 force 网易云同步（任何异常只记日志，绝不回抛给宿主）。 */
    private static void onScanStart() {
        try {
            int n = REFRESH_TRIGGERS.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = LAST_REFRESH_TRIGGER_MS.get();
            if (now - last < REFRESH_DEBOUNCE_MS) {
                PluginLog.d(TAG, "刷新音乐库联动：5 秒内的重复信号（第 " + n + " 次）已忽略");
                return;
            }
            LAST_REFRESH_TRIGGER_MS.set(now);
            PluginLog.i(TAG, "刷新音乐库联动：检测到宿主扫库开始（第 " + n + " 次），触发网易云同步（force）");
            NeteasePlugin.syncFromRefreshLibrary();
        } catch (Throwable t) {
            PluginLog.w(TAG, "刷新音乐库联动：触发失败（忽略）：" + brief(t));
        }
    }

    /**
     * 「刷新音乐库」状态门（0.11.48）：拦住 {@code MutableStateFlow.emit} 里的「真值」那一次（= 扫库
     * 开始），其余调用（读 value / 订阅 collect / 发射 false…）一律原样转发给真流。
     */
    private static final class RefreshGate implements InvocationHandler {

        private final Object delegate;
        private final boolean dryRun;

        RefreshGate(Object delegate) {
            this(delegate, false);
        }

        RefreshGate(Object delegate, boolean dryRun) {
            this.delegate = delegate;
            this.dryRun = dryRun;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isScanStartCall(method, args)) {
                if (dryRun) {
                    refreshSelfTestHit = true;
                    return null;
                }
                onScanStart();
            } else if (dryRun) {
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause == null ? e : cause;
            }
        }
    }

    /** 解析 {@code FlowKt.map} 并造好变换器代理；失败返回 false（调用方据此放弃装门）。 */
    private static boolean prepareMap() {
        if (mapMethod != null && mapTransformer != null) {
            return true;
        }
        try {
            Method found = null;
            for (String clsName : new String[]{
                    "kotlinx.coroutines.flow.FlowKt",
                    "kotlinx.coroutines.flow.FlowKt__TransformKt"}) {
                Class<?> c;
                try {
                    c = Class.forName(clsName);
                } catch (Throwable ignored) {
                    continue;
                }
                for (Method m : c.getMethods()) {
                    if (!"map".equals(m.getName()) || m.getParameterCount() != 2) {
                        continue;
                    }
                    if (!CLS_FLOW.equals(m.getParameterTypes()[0].getName())) {
                        continue;
                    }
                    found = m;
                    break;
                }
                if (found != null) {
                    break;
                }
            }
            if (found == null) {
                PluginLog.w(TAG, "宿主里找不到 FlowKt.map(Flow, Function2)（已试 FlowKt 与 FlowKt__TransformKt）");
                return false;
            }
            // ⚠️ Function2 必须用**宿主加载的那一份**（map 的参数类型就是它），代理类也由
            // 同一个加载器定义 —— 否则类别身份分裂，宿主会在 invoke 时抛 IllegalArgumentException。
            Class<?> function2 = found.getParameterTypes()[1];
            Object transformer = Proxy.newProxyInstance(
                    function2.getClassLoader(),
                    new Class<?>[]{function2},
                    new ListMapHandler());
            mapMethod = found;
            mapTransformer = transformer;
            PluginLog.i(TAG, "列表变换器就绪：map=" + found.getDeclaringClass().getName() + "#" + found.getName()
                    + "，Function2=" + function2.getName());
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "列表变换器准备失败（忽略）：" + brief(t));
            return false;
        }
    }

    private static boolean isGate(Object o) {
        if (o == null || !Proxy.isProxyClass(o.getClass())) {
            return false;
        }
        return Proxy.getInvocationHandler(o) instanceof DaoGate;
    }

    /**
     * 用 {@code sun.misc.Unsafe} 写 {@code private static final} 字段（普通反射写不进去）。
     * 与 {@code VoxzenBridge.putStaticFinal} 同一手法（0.3.10 起实证可用）。
     *
     * @return null 表示成功，否则返回失败摘要
     */
    private static String putStaticFinal(Field f, Object value) {
        try {
            f.setAccessible(true);
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object u = theUnsafe.get(null);
            Object base = uc.getMethod("staticFieldBase", Field.class).invoke(u, f);
            Object off = uc.getMethod("staticFieldOffset", Field.class).invoke(u, f);
            uc.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(u, base, off, value);
            return null;
        } catch (Throwable t) {
            return brief(t);
        }
    }

    // ------------------------------------------------------------ 刷新（开关切换时）

    /** 异步刷新入口（自起守护线程）。 */
    public static void refreshAsync(String why) {
        Thread t = new Thread(() -> refresh(why), "netease-track-gate-refresh");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 触发宿主「歌曲」列表重新查询（可在任意非宿主线程调用；{@link HostSqlBridge#onHostConnection}
     * 负责投递到宿主交互线程并等待）。
     *
     * <p>为什么一条同值 UPDATE 能刷新 UI：宿主的 Room 失效追踪是 TEMP 触发器 + 失效日志（见
     * {@code host/HostSqlBridge} 类注释），同连接上的任何 UPDATE 都会点着它 ⇒ 观察 Track 表的
     * Flow 重新发射 ⇒ 「歌曲」页重查并经过本门过滤。</p>
     */
    public static void refresh(String why) {
        try {
            AWAIT_REQUERY.set(true);
            String dbPath = NativeLibrary.dbPath() == null ? null : NativeLibrary.dbPath().toString();
            Integer rows = HostSqlBridge.onHostConnection(dbPath, sql -> {
                sql.exec(SQL_TOUCH_TRACK);
                return 1;
            });
            if (rows == null) {
                PluginLog.w(TAG, "刷新「歌曲」列表：宿主同连接不可用（" + why + "）⇒ 退回重建曲库快照");
                VoxzenBridge.refreshHostLibrary();
                return;
            }
            PluginLog.i(TAG, "已刷新「歌曲」列表（" + why + "；门=" + (installed ? "已装" : "未装")
                    + "）：宿主 UI 将重新查询" + (installed ? "并按开关过滤" : "（门未装，本次无过滤效果）"));
        } catch (Throwable t) {
            PluginLog.w(TAG, "刷新「歌曲」列表异常（忽略）：" + brief(t));
        }
    }

    // ------------------------------------------------------------ 代理与谓词

    /** TrackDao 门：只拦 {@code \u0528()}（getAllFlow），其余方法原样转发。 */
    private static final class DaoGate implements InvocationHandler {

        private final Object delegate;

        DaoGate(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name) && (args == null || args.length == 0)) {
                return "netease-track-list-gate";
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if (NAME_GET_ALL_FLOW.equals(name) && method.getParameterCount() == 0
                    && CLS_FLOW.equals(method.getReturnType().getName())) {
                int n = FLOW_CALLS.incrementAndGet();
                if (n <= 5 || n % 50 == 0) {
                    PluginLog.i(TAG, "宿主获取「歌曲」列表流：第 " + n + " 次（此流已套「合并歌单」列表变换）");
                }
                return gateFlow(call(method, args));
            }
            if (NAME_UPDATE_READABLE.equals(name) && method.getParameterCount() == 2
                    && args != null && args.length == 2 && args[0] instanceof List
                    && isReadableMarkList((List<?>) args[0])) {
                return writeReadableMarks(method, args);
            }
            return call(method, args);
        }

        /**
         * 扫库屏蔽核心（0.11.47）：把「不可读」写批次里的插件行（{@code netease-*}）剔掉再交给
         * 真实现；全被剔掉 ⇒ 整批不落库（返回 {@code kotlin.Unit.INSTANCE}，宿主协程照常收口）。
         */
        private Object writeReadableMarks(Method method, Object[] args) throws Throwable {
            List<?> list = (List<?>) args[0];
            List<Object> kept = new ArrayList<>(list.size());
            int blocked = 0;
            for (Object item : list) {
                String id = null;
                try {
                    id = item == null ? null : MARK_ID.getString(item);
                } catch (Throwable t) {
                    if (UNREADABLE_FAIL_LOGGED.compareAndSet(false, true)) {
                        PluginLog.w(TAG, "读「不可读」标记的曲目 ID 失败（该条按本地放行）：" + brief(t));
                    }
                }
                if (shouldBlockUnreadableWrite(id)) {
                    blocked++;
                } else {
                    kept.add(item);
                }
            }
            if (blocked == 0) {
                return call(method, args);        // 批次里没有插件行 ⇒ 原样转发
            }
            int total = UNREADABLE_WRITES_BLOCKED.addAndGet(blocked);
            int calls = UNREADABLE_BLOCK_CALLS.incrementAndGet();
            if (calls <= 5 || calls % 20 == 0) {
                PluginLog.i(TAG, "扫库屏蔽：拦下 " + blocked + " 首插件曲目被标「不可读」（累计 " + total
                        + " 首；本地曲目不受影响）");
            }
            if (kept.isEmpty()) {
                Object unit = unitInstance();
                if (unit != null) {
                    return unit;
                }
            }
            return call(method, new Object[]{kept, args[1]});
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause == null ? e : cause;
            }
        }
    }

    /** 批次元素是否全是「不可读标记」（{@code \u0d37}）：空列表/非该类型一律按「不是」处理。 */
    private static boolean isReadableMarkList(List<?> list) {
        for (Object item : list) {
            if (item != null) {
                return isReadableMarkClass(item.getClass().getName());
            }
        }
        return false;
    }

    /** 纯判定（离线探针可测）：这个类名是不是扫库用的「不可读标记」数据类。 */
    public static boolean isReadableMarkClass(String className) {
        return CLS_TRACK_READABLE_MARK.equals(className);
    }

    /** 纯判定（离线探针可测）：这个 id 是不是插件写入的行（决定是否把它从「不可读」批次里剔除）。 */
    public static boolean shouldBlockUnreadableWrite(String trackId) {
        return trackId != null && trackId.startsWith(NativeLibrary.ID_PREFIX);
    }

    /** {@code kotlin.Unit.INSTANCE}（懒加载缓存）；取不到返回 null，调用方用「空批次空写」兜底。 */
    private static Object unitInstance() {
        Object unit = unitInstance;
        if (unit == null) {
            try {
                unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
                unitInstance = unit;
            } catch (Throwable t) {
                PluginLog.w(TAG, "取 kotlin.Unit.INSTANCE 失败（改用空批次空写）：" + brief(t));
            }
        }
        return unit;
    }

    private static Object gateFlow(Object flow) {
        Method map = mapMethod;
        Object transformer = mapTransformer;
        if (flow == null || map == null || transformer == null) {
            return flow;
        }
        try {
            return map.invoke(null, flow, transformer);
        } catch (Throwable t) {
            PluginLog.w(TAG, "曲目流变换包装失败（本次放行原样）：" + brief(t));
            return flow;
        }
    }

    /**
     * 列表变换器（宿主 {@code Function2} 的动态代理）：开关关闭时把列表里的 {@code netease-*}
     * 插件行剔除，其余（含全部本地行）原样保留。
     */
    private static final class ListMapHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name) && (args == null || args.length == 0)) {
                return "netease-merge-playlists-transformer";
            }
            if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if (!"invoke".equals(name)) {
                return null;
            }
            Object element = args == null || args.length == 0 ? null : args[0];
            try {
                return transform(element);
            } catch (Throwable t) {
                if (TRANSFORM_FAIL_LOGGED.compareAndSet(false, true)) {
                    PluginLog.w(TAG, "列表变换出错（本份列表原样放行）：" + brief(t));
                }
                return element;   // 判错就放行：绝不误藏用户自己的本地曲目
            }
        }
    }

    /**
     * 单次列表变换：DAO 的流元素是 {@code List<Track>}（不是单首歌），所以必须对<b>列表内部</b>
     * 逐首判定 —— 这正是首版 {@code FlowKt.filter} 直挂谓词失效的原因（谓词拿到的是
     * {@code ArrayList}，反射 {@code getId()} 抛异常被吞 ⇒ 整份放行）。
     */
    private static Object transform(Object element) {
        if (!(element instanceof List<?>)) {
            return element;                       // 元素不是列表（宿主结构不符）⇒ 原样放行
        }
        List<?> list = (List<?>) element;
        int n = TRANSFORMS.incrementAndGet();
        int size = list.size();
        boolean merge = PluginConfig.mergePlaylists();
        boolean[] plugin = new boolean[size];
        String[] titles = new String[size];
        String[] artists = new String[size];
        String[] albums = new String[size];
        long[] sizes = new long[size];
        long[] durations = new long[size];
        boolean fieldsOk = true;
        for (int i = 0; i < size; i++) {
            Object item = list.get(i);
            plugin[i] = isPluginTrack(item);
            try {
                titles[i] = TITLE.getString(item);
                artists[i] = ARTIST.getString(item);
                albums[i] = ALBUM.getString(item);
                sizes[i] = SIZE.getLong(item);
                durations[i] = DURATION.getLong(item);
            } catch (Throwable t) {
                fieldsOk = false;
                if (TRANSFORM_FAIL_LOGGED.compareAndSet(false, true)) {
                    PluginLog.w(TAG, "读曲目字段失败（本份列表不做去重、也不隐藏）：" + brief(t));
                }
                break;
            }
        }
        boolean[] dupHidden = fieldsOk
                ? dedupHiddenMask(titles, artists, albums, sizes, durations, plugin)
                : new boolean[size];
        List<Object> kept = new ArrayList<>(size);
        int droppedPlugin = 0;
        int droppedDup = 0;
        for (int i = 0; i < size; i++) {
            if (!merge && plugin[i]) {
                droppedPlugin++;
                continue;
            }
            if (dupHidden[i]) {
                droppedDup++;
                continue;
            }
            kept.add(list.get(i));
        }
        if (droppedPlugin > 0) {
            DROPPED_TOTAL.addAndGet(droppedPlugin);
        }
        if (droppedDup > 0) {
            DEDUP_DROPPED_TOTAL.addAndGet(droppedDup);
        }
        logTransform(n, size, droppedPlugin, droppedDup, merge);
        return droppedPlugin == 0 && droppedDup == 0 ? element : kept;   // 没隐藏任何行 ⇒ 原对象放行
    }

    /**
     * 去重判定（纯函数，离线探针可测）：三要素（歌名 / 作者 / 标签专辑）trim 后完全相同的分组里，
     * 只保留「最优」的一行，其余在返回值里标 true（= 应隐藏）。
     *
     * <p>组内优先级：<b>占用空间大者</b> &gt; 本地行（非 {@code netease-} 前缀）&gt; 时长（ms）大者 &gt;
     * 原顺序靠前者。插件流曲目的 size 通常为 0，所以与本地行重复时被隐藏的必然是插件行
     * （用户口径：「两个歌名、标签、作者完全一样 … 占用空间最小的那一个」隐藏掉）。</p>
     */
    public static boolean[] dedupHiddenMask(String[] titles, String[] artists, String[] albums,
                                            long[] sizes, long[] durations, boolean[] plugin) {
        int n = titles.length;
        boolean[] hidden = new boolean[n];
        HashMap<String, Integer> best = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String key = norm(titles[i]) + '\u0001' + norm(artists[i]) + '\u0001' + norm(albums[i]);
            Integer j = best.get(key);
            if (j == null) {
                best.put(key, i);
                continue;
            }
            if (prefer(i, sizes[i], durations[i], plugin[i], j, sizes[j], durations[j], plugin[j])) {
                hidden[j] = true;
                best.put(key, i);
            } else {
                hidden[i] = true;
            }
        }
        return hidden;
    }

    /** 分组键归一：trim + null→""（用户口径「完全一样」= 去掉首尾空白后的精确相等）。 */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /** 组内「第 i 个是否比第 j 个更该保留」：空间大 &gt; 本地 &gt; 时长长 &gt; 顺序靠前。 */
    private static boolean prefer(int i, long sizeI, long durationI, boolean pluginI,
                                  int j, long sizeJ, long durationJ, boolean pluginJ) {
        if (sizeI != sizeJ) {
            return sizeI > sizeJ;
        }
        if (pluginI != pluginJ) {
            return !pluginI;
        }
        if (durationI != durationJ) {
            return durationI > durationJ;
        }
        return i < j;
    }

    /** 变换日志（采样打印：首次 / 开关落定后首查 / 前 6 次 / 每 100 次）。 */
    private static void logTransform(int n, int total, int droppedPlugin, int droppedDup, boolean merge) {
        boolean first = TRANSFORM_LOGGED.compareAndSet(false, true);
        boolean awaited = AWAIT_REQUERY.compareAndSet(true, false);
        if (first || awaited || n <= 6 || n % 100 == 0) {
            PluginLog.i(TAG, "第 " + n + " 次列表变换：共 " + total + " 首；"
                    + (merge ? "开关=开 → 网易云全显示"
                             : "隐藏网易云 " + droppedPlugin + " 首（开关=关 → 只显示本地）")
                    + "；去重隐藏 " + droppedDup + " 首（同名同标签同作者保留占用空间最大者；累计去重 "
                    + DEDUP_DROPPED_TOTAL.get() + " 首，累计隐藏网易云 " + DROPPED_TOTAL.get() + " 首）");
        }
    }

    /** 是否插件写入的行（{@code Track.id} 以 {@code netease-} 开头）；取不到 ID 一律当本地放行。 */
    private static boolean isPluginTrack(Object item) {
        if (item == null) {
            return false;
        }
        try {
            Method getter = idGetter;
            if (getter == null) {
                getter = item.getClass().getMethod("getId");
                idGetter = getter;
            }
            Object id = getter.invoke(item);
            return (id instanceof String) && ((String) id).startsWith(NativeLibrary.ID_PREFIX);
        } catch (Throwable t) {
            if (TRANSFORM_FAIL_LOGGED.compareAndSet(false, true)) {
                PluginLog.w(TAG, "读取曲目 ID 失败（该首按本地放行）：" + brief(t));
            }
            return false;
        }
    }

    /**
     * 单个公开 getter 的读取器（按类缓存 {@code Method} 对象）：变换与扫库过滤都跑在宿主协程
     * 线程上、按曲目逐条调用，缓存后不必每次查方法表；赋值竞态无副作用（顶多重复解析同一方法）。
     */
    private static final class Getter {

        private final String name;
        private volatile Method method;

        Getter(String name) {
            this.name = name;
        }

        Object get(Object item) throws Exception {
            Method m = method;
            if (m == null || !m.getDeclaringClass().isInstance(item)) {
                m = item.getClass().getMethod(name);
                method = m;
            }
            return m.invoke(item);
        }

        String getString(Object item) throws Exception {
            Object v = get(item);
            return v instanceof String ? (String) v : null;
        }

        long getLong(Object item) throws Exception {
            Object v = get(item);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        }
    }

    // ------------------------------------------------------------ 杂项

    private static void note(String text, boolean ok, Throwable t) {
        lastNote = text;
        if (t != null) {
            PluginLog.e(TAG, "歌曲列表门：" + text, t);
        } else if (ok) {
            PluginLog.i(TAG, "歌曲列表门：" + text);
        } else {
            PluginLog.w(TAG, "歌曲列表门：" + text);
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "?";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
