package com.example.netease.host;

import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.core.Sql;
import com.example.netease.svc.NativeLibrary;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 0.7.0 <b>宿主同连接写库</b>通路：插件要写的行不再走外部 JDBC，而是交给宿主**自己的 Room 通路**执行。
 *
 * <p><b>为什么必须同连接（一手实证 + 字节码逆向，2026-09-29）</b>：宿主 Salt Player for Windows 1.18.5
 * 用的是 <b>Room KMP</b>（{@code androidx.sqlite} 直连驱动版，jar 里 {@code androidx/sqlite/db/*}
 * 一个类都没有）。它的失效追踪是 {@code androidx.room.TriggerBasedInvalidationTracker}：靠
 * TEMP 触发器把变更写进 {@code room_table_modification_log}，而 <b>TEMP 对象绑定在创建它的那条连接上</b>。
 * 外部 JDBC 是另一条连接 ⇒ 触发器不响 ⇒ 宿主 UI 的 Flow 不 re-emit：实测「purge 插件行 → 启动宿主 →
 * 自动同步写库 → 调 {@code RepoService.rebuildMusicLibrary()}」后，宿主界面依然只有本地 3 首
 * （截图 {@code native-pl-5.png}、{@code live-t1.png}）。同连接写入才会让 Flow 立刻重发。</p>
 *
 * <p><b>两条通路（本轮都带证据日志，谁成用谁）</b>：</p>
 * <ol>
 *   <li><b>池通路（首选）</b>{@code RoomDatabase.useConnection(false, Function2, Continuation)}：
 *       block 收到的 {@code Transactor} 实际是 {@code androidx.room.coroutines.PooledConnectionImpl}，
 *       它 implements {@code RawConnectionAccessor} ⇒ {@code getRawConnection()} 拿到
 *       {@code androidx.sqlite.SQLiteConnection}（池连接已由 {@code BaseRoomConnectionManager}
 *       逐个 {@code configureConnection} ⇒ 带失效触发器）；</li>
 *   <li><b>自带连接通路（后备）</b>{@code configuration.getSqliteDriver().open(path)} 自己开一条连接，
 *       显式 {@code tracker.configureConnection(conn)} 装上追踪对象，写完再 {@code refreshAsync()}。
 *       它对「追踪表是不是 TEMP」这一点最敏感 —— 探针日志会把真相写出来。</li>
 * </ol>
 *
 * <p><b>线程</b>：所有宿主类接触都在 {@link HostBridgeWorker} 的单条线程上（工程死锁铁律）。
 * 调用方（插件的 {@code netease-native-sync} 线程）用 {@link CountDownLatch} 等结果；
 * 「通路不可用」（取不到库 / 签名不匹配 / 超时）才返回 {@code null} 让上层回退外部 JDBC。</p>
 *
 * <p><b>0.11.7（P0）改动</b>：写库任务本身被 SQL 拒绝（NOT NULL / 外键 / 影响行数为 0）时不再
 * 当作「通路不可用」去换通路重跑，而是包成 {@link TaskFailure} 原样上抛中止本轮 —— 0.11.6 的
 * 5935 条孤儿关联行正是「池连接 787 外键失败 → 换自带连接重跑」写出来的。</p>
 */
public final class HostSqlBridge {

    private static final String TAG = "host-sql";

    /** 等 worker 线程做完的总预算（含两条通路）。 */
    private static final long TOTAL_WAIT_MS = 40_000L;

    /** 池通路单次等待上限（写语句就在这条通路里跑，给足时间；超时才会退到自带连接通路）。 */
    private static final long POOL_WAIT_MS = 20_000L;

    /**
     * {@code useConnection} <b>未挂起</b>返回时给写入块的宽限（毫秒）。
     *
     * <p>B2 真机踩坑：宿主的 {@code useConnection} 有的实现把块派到
     * {@code DefaultDispatcher-worker-*} 线程上跑，宿主线程拿到的是普通返回值而不是
     * {@code COROUTINE_SUSPENDED}。此时「返回时块还没跑」只是<b>还没跑</b>，不是通路坏了 ——
     * 立刻判死会写成「池通路不可用」并降级到自带连接，从而误报（用户会以为要重启宿主）。</p>
     */
    private static final long BLOCK_GRACE_MS = 5_000L;

    /** 取 AppDatabase 实例的候选锚点：混淆名用 \\u 转义写死（源码编码无关）。 */
    private static final String[] DB_HOLDER_CANDIDATES = {
            "androidx.compose.ui.\u0DAA",       // public static AppDatabase Ϳ()
            "com.xuncorp.voxzen.data.\u052F",   // public static AppDatabase Ϳ()
            "androidx.compose.ui.\u0C61",       // private static final AppDatabase Ԩ
            "androidx.compose.ui.\u047C",       // private static final AppDatabase Ԩ
    };

    private static final String CLS_DB = "com.xuncorp.voxzen.data.AppDatabase";
    private static final String CLS_CONN = "androidx.sqlite.SQLiteConnection";
    private static final String CLS_STMT = "androidx.sqlite.SQLiteStatement";
    private static final String CLS_FUNCTION2 = "kotlin.jvm.functions.Function2";
    private static final String CLS_CONTINUATION = "kotlin.coroutines.Continuation";
    private static final String CLS_EMPTY_CONTEXT = "kotlin.coroutines.EmptyCoroutineContext";
    private static final String CLS_UNIT = "kotlin.Unit";
    private static final String CLS_INTRINSICS = "kotlin.coroutines.intrinsics.IntrinsicsKt";
    private static final String CLS_RESULT_FAILURE = "kotlin.Result$Failure";

    /** 已经解析到的宿主数据库实例（同一进程内复用）。 */
    private static volatile Object database;

    /** 走自带连接时的提示（P0 铁律 4：回退必须写进结果摘要，用户才知道为什么要重启宿主）。 */
    private static final String ROUTE_OWN =
            "（本轮写入未走宿主池连接，走的是自带连接 ⇒ 宿主界面可能需重启才会显示）";

    /** 连自带连接都没走通、退回插件外部 JDBC 时的提示。 */
    private static final String ROUTE_EXTERNAL =
            "（本轮写入未走宿主连接，走的是插件自带 JDBC ⇒ 宿主界面可能需重启才会显示）";

    /** 本轮实际走的通路说明；池连接成功时为空串。 */
    private static volatile String routeNote = "";

    private HostSqlBridge() {
    }

    /** 在宿主连接上跑的任务；返回值原样交回调用方。 */
    public interface HostTask<T> {
        T run(Sql sql) throws Exception;
    }

    /**
     * 把 {@code task} 投到宿主交互线程、在<b>宿主的 Room 通路</b>上执行，并等它跑完。
     *
     * @param dbPath 宿主曲库文件路径（自带连接通路用；传 {@code null} 则只认 configuration.name）
     * @return 任务返回值；宿主通路不可用（取不到库/投递失败/超时/报错）时返回 {@code null}，由上层回退
     */
    public static <T> T onHostConnection(String dbPath, HostTask<T> task) {
        if (task == null) {
            return null;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Runnable job = () -> {
            try {
                out.set(runOnWorker(dbPath, task));
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        };
        if (HostBridgeWorker.get().isOnWorkerThread()) {
            job.run();
        } else if (!HostBridgeWorker.get().submit(job)) {
            PluginLog.w(TAG, "宿主同连接写库：任务投递失败（宿主交互线程未启动或队列满），回退外部 JDBC");
            return null;
        }
        try {
            if (!done.await(TOTAL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                PluginLog.w(TAG, "宿主同连接写库：等待超时 " + TOTAL_WAIT_MS + "ms，回退外部 JDBC");
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            PluginLog.w(TAG, "宿主同连接写库：等待被中断，回退外部 JDBC");
            return null;
        }
        if (err.get() != null) {
            // 0.11.7（P0 铁律 4）：任务体在宿主连接上「被 SQL 拒绝」（约束/外键/影响行数不符）不是
            // 「通路不可用」——原样上抛，让调用方中止本轮，**绝不许**换个通路把同一批语句重跑一遍。
            TaskFailure tf = findTaskFailure(err.get());
            if (tf != null) {
                throw tf;
            }
            PluginLog.w(TAG, "宿主同连接写库失败（回退外部 JDBC）：" + brief(err.get()));
            return null;
        }
        @SuppressWarnings("unchecked")
        T value = (T) out.get();
        return value;
    }

    /** 不带路径的旧签名（configuration.name 拿不到路径时两条通路都走不通）。 */
    public static <T> T onHostConnection(HostTask<T> task) {
        return onHostConnection(null, task);
    }

    // ------------------------------------------------------------ worker 线程内

    private static <T> T runOnWorker(String dbPath, HostTask<T> task) {
        Object db = resolveDatabase();
        if (db == null) {
            PluginLog.w(TAG, "取不到宿主 AppDatabase 实例（锚点全失败），回退外部 JDBC");
            routeNote = ROUTE_EXTERNAL;
            return null;
        }

        // 通路 1：宿主自己的池连接（有池身背书的失效追踪）
        Outcome<T> viaPool = null;
        try {
            viaPool = tryPoolConnection(db, task);
        } catch (Throwable t) {
            rethrowIfDataFailure(t, "池通路");            // 数据失败 ⇒ 抛（不换通路）
            PluginLog.w(TAG, "池通路异常（换自带连接通路）：" + brief(t));
        }
        if (viaPool != null && viaPool.ok) {
            routeNote = "";
            PluginLog.i(TAG, "同连接写库成功：走的是**宿主池连接**（useConnection）");
            refreshInvalidation(db);
            return viaPool.value;
        }
        rethrowIfDataFailure(viaPool == null ? null : viaPool.cause, "池通路");
        PluginLog.w(TAG, "池通路不可用：" + (viaPool == null ? "异常" : viaPool.why));

        // 通路 2：自己用宿主的 SQLiteDriver 开一条连接 + 显式配置失效追踪
        Outcome<T> viaOwn = null;
        try {
            viaOwn = tryOwnConnection(db, dbPath, task);
        } catch (Throwable t) {
            rethrowIfDataFailure(t, "自带连接通路");
            PluginLog.w(TAG, "自带连接通路异常：" + brief(t));
        }
        if (viaOwn != null && viaOwn.ok) {
            routeNote = ROUTE_OWN;
            PluginLog.w(TAG, "同连接写库**没走宿主池连接**" + routeNote);
            PluginLog.i(TAG, "同连接写库成功：走的是**自带连接**（driver.open + configureConnection）");
            refreshInvalidation(db);
            return viaOwn.value;
        }
        rethrowIfDataFailure(viaOwn == null ? null : viaOwn.cause, "自带连接通路");
        PluginLog.w(TAG, "自带连接通路也不可用：" + (viaOwn == null ? "异常" : viaOwn.why) + "，回退外部 JDBC");
        routeNote = ROUTE_EXTERNAL;
        PluginLog.w(TAG, "回退外部 JDBC" + routeNote);
        return null;
    }

    /**
     * 任务体（数据）失败一律原样上抛：写库被 SQL 拒绝 ≠ 通路不可用。
     *
     * <p>真机铁证（0.11.6）：宿主池连接上每轮都抛 {@code SQLiteException: Error code: 787,
     * FOREIGN KEY constraint failed}（因为 Track 行根本没写进去），本类把它当成「池通路不可用」，
     * 于是换插件自带连接把 5935 条关联行照单全收 ⇒ 变成 5935 条孤儿行。从 0.11.7 起这条路封死。</p>
     */
    private static void rethrowIfDataFailure(Throwable t, String way) {
        TaskFailure tf = findTaskFailure(t);
        if (tf == null) {
            return;
        }
        Throwable cause = tf.getCause() == null ? tf : tf.getCause();
        PluginLog.e(TAG, "宿主同连接写库**数据失败**（" + way + "：SQL 被拒 / 约束或外键不通过）⇒ 本轮中止，"
                + "**不换通路重跑**（0.11.6 就是换通道重跑写出 5935 条孤儿关联行的）：" + brief(cause), cause);
        throw tf;
    }

    // ------------------------------------------------------------ 通路 1：池连接

    private static <T> Outcome<T> tryPoolConnection(Object db, HostTask<T> task) throws Exception {
        Class<?> f2 = load(CLS_FUNCTION2, db);
        Class<?> cont = load(CLS_CONTINUATION, db);
        if (f2 == null || cont == null) {
            return Outcome.fail("宿主缺少 kotlin 函数/续体类型");
        }
        Method use = findUseConnection(db.getClass(), f2, cont);
        if (use == null) {
            return Outcome.fail("RoomDatabase 上没有 useConnection(Z,Function2,Continuation)");
        }
        PluginLog.i(TAG, "池通路：useConnection = " + use.getDeclaringClass().getName() + "#" + use.getName()
                + "(" + names(use.getParameterTypes()) + ")");

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicInteger stmts = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        // 写入块自己跑完的信号：未挂起返回时（本线程同步回调）它才是「写完了」的真凭证。
        // 0.11.12 教训：从前这里只认续体，于是每次写库都要白等满 5 s 宽限——
        // 唯一宿主交互线程被占死，换曲那一刻的播放条封面只能排在后面（真机实测晚 2.5~3 s）。
        CountDownLatch blockDone = new CountDownLatch(1);

        Object block = Proxy.newProxyInstance(classLoaderOf(db), new Class<?>[]{f2}, (proxy, method, args) -> {
            if (isObjectMethod(method, proxy, args)) {
                return objectMethod(method, proxy, args);
            }
            if (ran.compareAndSet(false, true)) {
                PluginLog.i(TAG, "池通路：写入块被回调（线程=" + Thread.currentThread().getName()
                        + "，参数=" + (args == null ? 0 : args.length) + " 个）");
                try {
                    Object transactor = args != null && args.length > 0 ? args[0] : null;
                    if (transactor != null) {
                        PluginLog.i(TAG, "池通路：Transactor 实际类型 = " + transactor.getClass().getName()
                                + "，接口 = " + names(transactor.getClass().getInterfaces()));
                    }
                    Object conn = rawConnection(transactor);
                    if (conn == null) {
                        throw new IllegalStateException("取不到原生连接（Transactor 上没有返回 SQLiteConnection 的无参方法）");
                    }
                    PluginLog.i(TAG, "池通路：原生连接 = " + conn.getClass().getName()
                            + "，inTransaction=" + inTransaction(conn));
                    HostSql sql = new HostSql(conn);
                    result.set(writeInTransaction(sql, conn, task));
                    stmts.set(sql.count());
                    // 证据：写入这条池连接上有没有「失效追踪对象」（触发器写进追踪表才算真点着）
                    PluginLog.i(TAG, "池通路证据：写入后本连接追踪表="
                            + sql.probe("SELECT name FROM sqlite_master WHERE name LIKE 'room%' OR name LIKE '%modification%'")
                            + "；TEMP 对象=" + sql.probe("SELECT type, name FROM sqlite_temp_master WHERE name LIKE 'room%'")
                            + "；失效日志=" + sql.probe("SELECT table_id, invalidated FROM room_table_modification_log"));
                } catch (Throwable t) {
                    failure.set(t);
                    PluginLog.w(TAG, "池通路：块内失败：" + brief(t));
                } finally {
                    blockDone.countDown();   // 块已跑完（成功或失败都算跑完）——不再白等宽限
                }
            } else {
                PluginLog.w(TAG, "池通路：写入块被重复回调（同一任务只跑第一次），忽略");
            }
            return unit(db);   // 块不挂起：直接返回 kotlin.Unit.INSTANCE
        });

        Object continuation = Proxy.newProxyInstance(classLoaderOf(db), new Class<?>[]{cont}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getContext":
                    return emptyContext(db);
                case "resumeWith": {
                    Object v = args == null || args.length == 0 ? null : args[0];
                    Throwable f = failureOf(v);
                    if (f != null) {
                        failure.compareAndSet(null, f);
                        PluginLog.w(TAG, "池通路：续体收到失败（线程=" + Thread.currentThread().getName() + "）：" + brief(f));
                    } else {
                        PluginLog.i(TAG, "池通路：续体收到成功结果（线程=" + Thread.currentThread().getName()
                                + "，值=" + desc(v) + "）");
                    }
                    finished.countDown();
                    return null;
                }
                default:
                    if (isObjectMethod(method, proxy, args)) {
                        return objectMethod(method, proxy, args);
                    }
                    return null;
            }
        });

        Object invoked;
        try {
            invoked = use.invoke(db, Boolean.FALSE, block, continuation);
        } catch (Throwable t) {
            return Outcome.fail("useConnection 调用抛错：" + brief(t));
        }
        Object suspended = coroutineSuspended(db);
        boolean didSuspend = suspended != null && invoked == suspended;
        // 挂起 ≠ 失败：suspend 的 useConnection 返回 COROUTINE_SUSPENDED 只表示块还没跑完，
        // 结果会经 continuation.resumeWith 送回来。只有「超时」或「块抛错」才算通路失败。
        PluginLog.i(TAG, didSuspend
                ? "池通路：useConnection 已挂起（等续体回调，" + POOL_WAIT_MS + "ms 上限）"
                : "池通路：useConnection 直接返回 " + outcomeDesc(invoked) + "，等写入块回调（" + BLOCK_GRACE_MS + "ms 宽限）");
        if (didSuspend) {
            if (!finished.await(POOL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return Outcome.fail("useConnection 挂起后 " + POOL_WAIT_MS + "ms 内没有续体回调"
                        + "（块被回调=" + ran.get() + "，语句=" + stmts.get() + " 条）");
            }
        } else {
            // 未挂起返回：块可能已跑完（本线程同步回调），也可能被派到别的线程稍后跑。
            // 判据用「块跑完」而不是「续体回来」——未挂起时续体根本不会来，等它就是白等满宽限。
            if (!blockDone.await(BLOCK_GRACE_MS, TimeUnit.MILLISECONDS)) {
                PluginLog.w(TAG, "池通路：宽限 " + BLOCK_GRACE_MS + "ms 内写入块未跑完（已回调=" + ran.get()
                        + "，语句=" + stmts.get() + " 条）");
            }
        }
        if (failure.get() != null) {
            // 带上 cause：上层要能认出「这是数据失败（该抛）」还是「通路问题（可换）」
            return Outcome.fail("池通路写入失败：" + brief(failure.get()), failure.get());
        }
        if (!ran.get()) {
            return Outcome.fail("池通路未回调到写入块（useConnection 直接返回 " + outcomeDesc(invoked)
                    + "，宽限 " + BLOCK_GRACE_MS + "ms 后块仍未执行；语句=" + stmts.get() + " 条）");
        }
        PluginLog.i(TAG, "池通路：写入完成，" + (stmts.get() > 0 ? stmts.get() + " 条语句" : "语句数未记录（续体先到）"));
        return Outcome.ok(result.get());
    }

    // ------------------------------------------------------------ 通路 2：自带连接

    private static <T> Outcome<T> tryOwnConnection(Object db, String dbPath, HostTask<T> task) {
        Object conn = null;
        try {
            Object cfg = fieldValue(db, "connectionManager");
            cfg = cfg == null ? null : fieldValue(cfg, "configuration");
            if (cfg == null) {
                return Outcome.fail("取不到 RoomDatabase.connectionManager.configuration");
            }
            Object driver = callNoArg(cfg, "getSqliteDriver");
            String name = str(callNoArg(cfg, "getName"));
            String path = pickPath(name, dbPath);
            PluginLog.i(TAG, "自带连接通路：configuration.name=" + name + "，driver="
                    + (driver == null ? "null" : driver.getClass().getName()) + "，路径=" + path);
            if (driver == null) {
                return Outcome.fail("configuration.getSqliteDriver() 取不到驱动");
            }
            if (path == null) {
                return Outcome.fail("拿不到曲库文件路径（configuration.name 与调用方都为空）");
            }
            conn = driver.getClass().getMethod("open", String.class).invoke(driver, path);
            if (conn == null) {
                return Outcome.fail("SQLiteDriver.open(" + path + ") 返回 null");
            }
            PluginLog.i(TAG, "自带连接通路：连接已打开 → " + conn.getClass().getName());
            Object tracker = callNoArg(db, "getInvalidationTracker");
            if (tracker == null) {
                return Outcome.fail("取不到 InvalidationTracker");
            }
            if (!configureConnection(tracker, conn)) {
                return Outcome.fail("失效追踪器 " + tracker.getClass().getName()
                        + " 上没有 configureConnection(SQLiteConnection)；其方法=" + methodNames(tracker.getClass()));
            }
            HostSql sql = new HostSql(conn);
            sql.exec("PRAGMA busy_timeout=5000");
            // 0.11.7（P0 铁律 4）：自带连接必须**显式**开外键检查 —— 0.11.6 的 5935 条孤儿关联行
            // 就是在这条「默认 foreign_keys=OFF」的连接上写出来的（池连接拒绝的语句它照单全收）。
            sql.exec("PRAGMA foreign_keys=ON");
            String fk = sql.probe("PRAGMA foreign_keys");
            if (!"1".equals(fk == null ? "" : fk.trim())) {
                return Outcome.fail("自带连接 PRAGMA foreign_keys=ON 未生效（读到 " + fk
                        + "）：拒绝在无外键检查的连接上写宿主曲库");
            }
            PluginLog.i(TAG, "自带连接通路：foreign_keys = " + fk.trim());
            PluginLog.i(TAG, "自带连接通路：追踪对象（持久）="
                    + sql.probe("SELECT name FROM sqlite_master WHERE name LIKE 'room%' OR name LIKE '%modification%'"));
            PluginLog.i(TAG, "自带连接通路：本连接 TEMP 对象="
                    + sql.probe("SELECT type, name FROM sqlite_temp_master WHERE name LIKE 'room%'"));
            T value = writeInTransaction(sql, conn, task);
            PluginLog.i(TAG, "自带连接通路：写入完成 " + sql.count() + " 条语句；失效日志="
                    + sql.probe("SELECT table_id, invalidated FROM room_table_modification_log"));
            PluginLog.i(TAG, "自带连接通路：写完后的本连接 TEMP 日志（若为 TEMP 表，关连接即消失）="
                    + sql.probe("SELECT name FROM sqlite_temp_master WHERE name LIKE '%modification%'"));
            // 刷新必须在**连接还活着**时做：追踪表若是 TEMP，关掉连接这些行就没了
            refreshInvalidation(db);
            return Outcome.ok(value);
        } catch (Throwable t) {
            return Outcome.fail("自带连接通路失败：" + brief(t), t);
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 在指定连接上装配失效追踪（追踪表 + TEMP 触发器）。
     *
     * <p>真机实证（2026-09-29）：本版宿主的追踪器实际类型是 {@code androidx.room.InvalidationTracker}，
     * 它的装配钩子叫 <b>{@code internalInit$room_runtime(SQLiteConnection)}</b>（就是
     * {@code RoomDatabase.internalInitInvalidationTracker(conn)} 逐连接调的那个），没有
     * {@code configureConnection}。所以这里按「名字候选 + 参数类型 isInstance(conn)」找 ——
     * {@code isInstance} 跨类加载器安全，比 Class 相等可靠。</p>
     */
    private static boolean configureConnection(Object tracker, Object conn) {
        String[] names = {"configureConnection", "internalInit$room_runtime", "internalInit"};
        for (String name : names) {
            for (Method m : tracker.getClass().getMethods()) {
                if (!name.equals(m.getName()) || m.getParameterCount() != 1) {
                    continue;
                }
                if (!m.getParameterTypes()[0].isInstance(conn)) {
                    continue;
                }
                try {
                    m.invoke(tracker, conn);
                    PluginLog.i(TAG, "自带连接通路：已调用 " + m.getDeclaringClass().getName() + "."
                            + m.getName() + "() 装配失效追踪");
                    return true;
                } catch (Throwable t) {
                    PluginLog.w(TAG, "装配失效追踪失败（" + m.getName() + "）：" + brief(t));
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 在宿主连接上跑任务：自带事务（连接已在事务里就不重复开），失败回滚。
     *
     * <p>{@code BEGIN} 用延迟式（与 sqlite-jdbc 的 {@code setAutoCommit(false)} 同语义），
     * 第一条写语句时才升级写锁；pool 连接已由 Room 配好 busy_timeout，自带连接我们自己设。</p>
     */
    private static <T> T writeInTransaction(HostSql sql, Object conn, HostTask<T> task) throws Exception {
        boolean own = !inTransaction(conn);
        boolean begun = false;
        if (own) {
            try {
                sql.exec("BEGIN");
                begun = true;
            } catch (Throwable t) {
                PluginLog.w(TAG, "宿主连接上开事务失败（改为逐条自动提交）：" + brief(t));
            }
        }
        try {
            T value = task.run(sql);
            if (begun) {
                sql.exec("COMMIT");
            }
            return value;
        } catch (Throwable t) {
            if (begun) {
                try {
                    sql.exec("ROLLBACK");
                } catch (Throwable ignored) {
                    // 回滚失败只能记日志：连接状态异常时再抛也没用
                }
            }
            // 0.11.7（P0 铁律 4）：任务体在宿主连接上失败 = **数据失败**，打上 TaskFailure 标记上抛，
            // 让通路层拒绝「换一条连接把同一批语句重跑一遍」（那正是 5935 条孤儿关联行的来源）。
            throw new TaskFailure(t);
        }
    }

    private static boolean inTransaction(Object conn) {
        try {
            return (Boolean) conn.getClass().getMethod("inTransaction").invoke(conn);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从 {@code Transactor} 取原生连接。
     *
     * <p>按名字认（含 Kotlin internal 的 {@code $room_runtime} 后缀），<b>不看返回类型是否等于某个
     * Class 对象</b> —— 跨类加载器比 Class 相等会假失败（2026-09-29 真机实证：池通路就是死在这一步，
     * 日志「Transactor 上没有返回 SQLiteConnection 的无参方法」，而它其实是
     * {@code androidx.room.coroutines.PooledConnectionImpl implements RawConnectionAccessor}）。</p>
     */
    private static Object rawConnection(Object transactor) {
        if (transactor == null) {
            return null;
        }
        Method[] methods = transactor.getClass().getMethods();
        // 1) 名字以 getRawConnection 开头（getRawConnection / getRawConnection$room_runtime …）
        for (Method m : methods) {
            if (m.getParameterCount() == 0 && m.getName().startsWith("getRawConnection")) {
                Object c = invokeFlexible(m, transactor);
                if (canPrepare(c)) {
                    PluginLog.i(TAG, "原生连接来自 " + m.getName() + "() → " + c.getClass().getName());
                    return c;
                }
                PluginLog.w(TAG, "取连接尝试失败：" + m.getName() + "() → "
                        + (c == null ? "null（或调用被拒，见上一条 warn）" : c.getClass().getName() + "（没有 prepare(String)）"));
            }
        }
        // 2) getDelegate()（PooledConnectionImpl → ConnectionWithLock，本身也 implements SQLiteConnection）
        for (Method m : methods) {
            if (m.getParameterCount() == 0 && m.getName().startsWith("getDelegate")) {
                Object c = invokeFlexible(m, transactor);
                if (canPrepare(c)) {
                    PluginLog.i(TAG, "原生连接来自 " + m.getName() + "() → " + c.getClass().getName());
                    return c;
                }
                PluginLog.w(TAG, "取连接尝试失败：" + m.getName() + "() → "
                        + (c == null ? "null（或调用被拒，见上一条 warn）" : c.getClass().getName() + "（没有 prepare(String)）"));
            }
        }
        // 3) 返回类型名以 androidx.sqlite. / androidx.room.coroutines. 开头的无参方法
        for (Method m : methods) {
            if (m.getParameterCount() != 0 || "getClass".equals(m.getName())) {
                continue;
            }
            String rt = m.getReturnType().getName();
            if (!rt.startsWith("androidx.sqlite.") && !rt.startsWith("androidx.room.coroutines.")) {
                continue;
            }
            Object c = invokeQuietly(m, transactor);
            if (canPrepare(c)) {
                PluginLog.i(TAG, "原生连接来自 " + m.getName() + "() → " + c.getClass().getName());
                return c;
            }
        }
        // 4) 实在找不到：把无参方法清单打进日志，下一轮按它改
        PluginLog.w(TAG, "Transactor " + transactor.getClass().getName() + " 无参方法="
                + noArgDump(transactor.getClass()));
        return null;
    }

    /**
     * 这个对象能不能当连接用 —— <b>只看形状，不看 Class 相等</b>：有公开的
     * {@code prepare(String)} 就行（{@code androidx.sqlite.SQLiteConnection} 的签名）。
     *
     * <p>为什么不比 Class：插件类加载器与宿主类加载器是**两个** loader，同一个类名加载出的
     * Class 对象不相等，{@code isAssignableFrom} 会假失败（真机实证踩过两次）。</p>
     */
    private static boolean canPrepare(Object o) {
        return prepareMethod(o) != null;
    }

    /** 类名链里有没有 {@code androidx.sqlite.SQLiteConnection}（只看日志/诊断用）。 */
    private static boolean isConnection(Object o) {
        if (o == null) {
            return false;
        }
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(o.getClass());
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            if (CLS_CONN.equals(c.getName())) {
                return true;
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            for (Class<?> i : c.getInterfaces()) {
                queue.add(i);
            }
        }
        return false;
    }

    /** 显式点一次失效刷新（{@code InvalidationTracker.refreshAsync()} 非挂起，公开方法名未被混淆）。 */
    private static void refreshInvalidation(Object db) {
        try {
            Object tracker = callNoArg(db, "getInvalidationTracker");
            if (tracker == null) {
                return;
            }
            tracker.getClass().getMethod("refreshAsync").invoke(tracker);
            PluginLog.i(TAG, "已请求宿主失效刷新：InvalidationTracker.refreshAsync()");
        } catch (Throwable t) {
            PluginLog.w(TAG, "失效刷新调用失败（忽略，Room 写入路径通常已自行刷新）：" + brief(t));
        }
    }

    // ------------------------------------------------------------ 数据库锚点

    private static Object resolveDatabase() {
        Object cached = database;
        if (cached != null) {
            return cached;
        }
        Class<?> dbCls = load(CLS_DB, null);
        List<String> found = new ArrayList<>();
        for (String holder : DB_HOLDER_CANDIDATES) {
            Object v = fromHolder(holder, dbCls);
            if (v != null) {
                found.add(holder + " → identityHash=" + System.identityHashCode(v)
                        + " class=" + v.getClass().getName());
                if (database == null) {
                    database = v;
                }
            }
        }
        PluginLog.i(TAG, "宿主 AppDatabase 候选 " + found.size() + " 个：" + found);
        return database;
    }

    /** 从持有者类里按<b>形状</b>取 AppDatabase（不认混淆成员名）。 */
    private static Object fromHolder(String holder, Class<?> dbCls) {
        Class<?> h = load(holder, null);
        if (h == null) {
            return null;
        }
        for (Method m : h.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                continue;
            }
            if (dbCls != null && !dbCls.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            Object v = invokeQuietly(m, null);
            if (v != null) {
                return v;
            }
        }
        for (Field f : h.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (dbCls != null && !dbCls.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 单个字段读不到继续找
            }
        }
        return null;
    }

    private static Method findUseConnection(Class<?> cls, Class<?> f2, Class<?> cont) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (!"useConnection".equals(m.getName()) || m.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps[0] != boolean.class && ps[0] != Boolean.class) {
                    continue;
                }
                if (!ps[1].isAssignableFrom(f2) || !ps[2].isAssignableFrom(cont)) {
                    continue;
                }
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 宿主连接上的 Sql

    /** {@link Sql} 的宿主实现：{@code androidx.sqlite.SQLiteConnection} + prepare/bind/step。 */
    private static final class HostSql implements NativeLibrary.SqlSchema {

        private final Object conn;
        private final Method prepare;
        private final Method step;
        private final Method close;
        private final Method bindNull;
        private final Method bindLong;
        private final Method bindDouble;
        private final Method bindText;
        private final Method bindBlob;
        private final Method getColumnCount;
        private final Method getColumnName;
        private final Method getText;
        private final Method getLong;
        private final Method isNull;
        private final AtomicInteger statements = new AtomicInteger();

        HostSql(Object conn) throws Exception {
            this.conn = conn;
            // 关键：语句类从**连接自己的** prepare(String) 返回类型取，而不是另加载一个 Class 对象
            // 再比相等（跨类加载器时同一个名字会是不同的 Class 实例，比相等必然失败）。
            Method prep = prepareMethod(conn);
            if (prep == null) {
                throw new IllegalStateException("连接 " + conn.getClass().getName() + " 上没有 prepare(String)");
            }
            Class<?> stmtCls = prep.getReturnType();
            this.prepare = prep;
            this.step = stmtCls.getMethod("step");
            this.close = stmtCls.getMethod("close");
            this.bindNull = stmtCls.getMethod("bindNull", int.class);
            this.bindLong = stmtCls.getMethod("bindLong", int.class, long.class);
            this.bindDouble = stmtCls.getMethod("bindDouble", int.class, double.class);
            this.bindText = stmtCls.getMethod("bindText", int.class, String.class);
            this.bindBlob = stmtCls.getMethod("bindBlob", int.class, byte[].class);
            this.getColumnCount = stmtCls.getMethod("getColumnCount");
            this.getColumnName = stmtCls.getMethod("getColumnName", int.class);
            this.getText = stmtCls.getMethod("getText", int.class);
            this.getLong = stmtCls.getMethod("getLong", int.class);
            this.isNull = stmtCls.getMethod("isNull", int.class);
        }

        int count() {
            return statements.get();
        }

        @Override
        public void exec(String sql, Object... args) throws Exception {
            execUpdate(sql, args);
        }

        /**
         * 执行并回报<b>影响行数</b>（0.11.7 / P0 铁律 2）。
         *
         * <p>{@code androidx.sqlite.SQLiteStatement.step()} 只回报「还有没有下一行」（写语句恒为 false），
         * 拿不到 changes ⇒ 紧接着在<b>同一条连接</b>上读 {@code SELECT changes()}（SQLite 保证它只反映
         * 最近一条已完成语句的影响行数，中间不能插别的语句）。读不到就抛：宁可知情失败，不许静默成功。</p>
         */
        @Override
        public int execUpdate(String sql, Object... args) throws Exception {
            statements.incrementAndGet();
            Object stmt = prepare.invoke(conn, sql);
            try {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        bind(stmt, i + 1, args[i]);
                    }
                }
                step.invoke(stmt);
            } finally {
                closeQuietly(stmt, close);
            }
            return changesAffected(sql);
        }

        /** 同连接读 {@code SELECT changes()}；索引基自适应（androidx.sqlite 的 get* 一般是 0 基）。 */
        private int changesAffected(String sql) {
            Object stmt = null;
            try {
                statements.incrementAndGet();
                stmt = prepare.invoke(conn, "SELECT changes()");
                if (!Boolean.TRUE.equals(step.invoke(stmt))) {
                    return 0;
                }
                try {
                    return (int) asLongOf(getLong.invoke(stmt, 0));
                } catch (Throwable zeroBased) {
                    return (int) asLongOf(getLong.invoke(stmt, 1));
                }
            } catch (Throwable t) {
                throw new IllegalStateException("宿主连接上读不到影响行数（SELECT changes() 失败）："
                        + brief(t) + "；SQL=" + sql, t);
            } finally {
                closeQuietly(stmt, close);
            }
        }

        /** 读多行（schema 探测 / 存在性断言用）：索引基与 {@link #probe} 同一套自适应。 */
        @Override
        public List<Object[]> rows(String sql, Object... args) throws Exception {
            statements.incrementAndGet();
            Object stmt = prepare.invoke(conn, sql);
            try {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        bind(stmt, i + 1, args[i]);
                    }
                }
                List<Object[]> out = new ArrayList<>();
                int base = -1;
                while (Boolean.TRUE.equals(step.invoke(stmt))) {
                    if (base < 0) {
                        base = 1;
                        try {
                            isNull.invoke(stmt, 0);
                            base = 0;
                        } catch (Throwable ignored) {
                            base = 1;
                        }
                    }
                    int cols = (Integer) getColumnCount.invoke(stmt);
                    Object[] row = new Object[cols];
                    for (int i = 0; i < cols; i++) {
                        int idx = i + base;
                        row[i] = Boolean.TRUE.equals(isNull.invoke(stmt, idx)) ? null : cell(stmt, idx);
                    }
                    out.add(row);
                }
                return out;
            } finally {
                closeQuietly(stmt, close);
            }
        }

        /** 只读探针：把前若干行压成一行文本（证据日志用；出错不抛，返回错误摘要）。 */
        String probe(String sql) {
            try {
                statements.incrementAndGet();
                Object stmt = prepare.invoke(conn, sql);
                try {
                    StringBuilder sb = new StringBuilder();
                    int rows = 0;
                    int base = -1; // getXxx 的索引基：androidx.sqlite 的 get* 是 0 基，bind* 是 1 基（真机踩过）
                    while (Boolean.TRUE.equals(step.invoke(stmt)) && rows < 8) {
                        if (rows > 0) {
                            sb.append(" | ");
                        }
                        int cols;
                        try {
                            cols = (Integer) getColumnCount.invoke(stmt);
                        } catch (Throwable t) {
                            return sb + "ERR(列数) " + brief(t);
                        }
                        if (base < 0) {
                            base = 1;
                            try {
                                isNull.invoke(stmt, 0);
                                base = 0;
                            } catch (Throwable ignored) {
                                base = 1;
                            }
                        }
                        for (int i = 0; i < cols; i++) {
                            if (i > 0) {
                                sb.append(',');
                            }
                            int idx = i + base;
                            try {
                                sb.append(Boolean.TRUE.equals(isNull.invoke(stmt, idx)) ? "null" : cell(stmt, idx));
                            } catch (Throwable t) {
                                sb.append("ERR(列" + i + ") " + brief(t));
                            }
                        }
                        rows++;
                    }
                    return rows == 0 ? "(空)" : sb.toString();
                } finally {
                    closeQuietly(stmt, close);
                }
            } catch (Throwable t) {
                return "ERR " + brief(t);
            }
        }

        private String cell(Object stmt, int i) {
            try {
                return str(getText.invoke(stmt, i));
            } catch (Throwable ignored) {
                try {
                    return str(getLong.invoke(stmt, i));
                } catch (Throwable ignored2) {
                    return "?";
                }
            }
        }

        private void bind(Object stmt, int index, Object value) throws Exception {
            if (value == null) {
                bindNull.invoke(stmt, index);
            } else if (value instanceof Integer) {
                bindLong.invoke(stmt, index, (long) (Integer) value);
            } else if (value instanceof Long) {
                bindLong.invoke(stmt, index, (Long) value);
            } else if (value instanceof Double) {
                bindDouble.invoke(stmt, index, (Double) value);
            } else if (value instanceof Float) {
                bindDouble.invoke(stmt, index, (double) (Float) value);
            } else if (value instanceof byte[]) {
                bindBlob.invoke(stmt, index, (byte[]) value);
            } else {
                bindText.invoke(stmt, index, String.valueOf(value));
            }
        }
    }

    // ------------------------------------------------------------ 小工具

    /** 通路结果：ok=写成功（value 有效）/ false=为什么没走通（{@code cause} 非空时是「数据失败」）。 */
    private static final class Outcome<T> {
        final boolean ok;
        final T value;
        final String why;
        final Throwable cause;

        private Outcome(boolean ok, T value, String why, Throwable cause) {
            this.ok = ok;
            this.value = value;
            this.why = why;
            this.cause = cause;
        }

        static <T> Outcome<T> ok(T value) {
            return new Outcome<>(true, value, null, null);
        }

        static <T> Outcome<T> fail(String why) {
            return new Outcome<>(false, null, why, null);
        }

        static <T> Outcome<T> fail(String why, Throwable cause) {
            return new Outcome<>(false, null, why, cause);
        }
    }

    /**
     * 宿主连接上的写库任务<b>数据失败</b>（SQL 被拒：NOT NULL / 外键 / 影响行数为 0 …）。
     *
     * <p>与「通路不可用」（取不到库、签名不匹配、超时）严格区分：前者必须原样上抛中止本轮，
     * 后者才允许换通路。0.11.6 的 5935 条孤儿关联行就是这两者被混为一谈的产物。</p>
     */
    private static final class TaskFailure extends RuntimeException {
        TaskFailure(Throwable cause) {
            super(cause == null ? "宿主连接上的写库任务失败" : brief(cause), cause);
        }
    }

    /** 沿 cause 链找 {@link TaskFailure}（Room/协程/Kotlin 反射可能再包几层）。 */
    private static TaskFailure findTaskFailure(Throwable t) {
        Throwable x = t;
        for (int i = 0; x != null && i < 32; i++) {
            if (x instanceof TaskFailure) {
                return (TaskFailure) x;
            }
            Throwable next = x.getCause();
            x = next == x ? null : next;
        }
        return null;
    }

    /** 本轮写入实际走的通路说明（由 {@code NeteasePlugin} 拼进结果摘要行）。 */
    public static String routeNote() {
        return routeNote;
    }

    private static String pickPath(String name, String fallback) {
        if (name != null && !name.isEmpty()) {
            try {
                if (java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(name))) {
                    return name;
                }
            } catch (Throwable ignored) {
                // 名字不是路径：退回调用方给的路径
            }
            if (name.endsWith(".db") || name.contains("\\") || name.contains("/")) {
                return name;
            }
        }
        return fallback;
    }

    private static Object fieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // 往父类找
            }
        }
        return null;
    }

    private static Object callNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isObjectMethod(Method m, Object proxy, Object[] args) {
        switch (m.getName()) {
            case "toString":
                return m.getParameterCount() == 0;
            case "hashCode":
                return m.getParameterCount() == 0;
            case "equals":
                return m.getParameterCount() == 1;
            default:
                return false;
        }
    }

    private static Object objectMethod(Method m, Object proxy, Object[] args) {
        switch (m.getName()) {
            case "toString":
                return "netease-host-bridge";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            default:
                return null;
        }
    }

    private static Object unit(Object hint) {
        try {
            Class<?> c = load(CLS_UNIT, hint);
            return c == null ? null : c.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object emptyContext(Object hint) {
        try {
            Class<?> c = load(CLS_EMPTY_CONTEXT, hint);
            return c == null ? null : c.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code COROUTINE_SUSPENDED} 单例（Kotlin 用它表示「挂起了、结果稍后由续体送达」）。
     * 取不到时返回 null ⇒ 调用方退化为「总是等 latch」。
     */
    private static Object coroutineSuspended(Object hint) {
        try {
            Class<?> c = load(CLS_INTRINSICS, hint);
            return c == null ? null : c.getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 续体收到的失败值：{@code Throwable} 本身，或 Kotlin 的 {@code Result$Failure} 包装。 */
    private static Throwable failureOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Throwable) {
            return (Throwable) value;
        }
        if (CLS_RESULT_FAILURE.equals(value.getClass().getName())) {
            try {
                Field f = value.getClass().getDeclaredField("exception");
                f.setAccessible(true);
                Object ex = f.get(value);
                return ex instanceof Throwable ? (Throwable) ex : new IllegalStateException(String.valueOf(ex));
            } catch (Throwable ignored) {
                return new IllegalStateException("宿主协程失败（无法取出异常）");
            }
        }
        return null;
    }

    /**
     * 调用一个宿主方法，**逐级退让**直到成功：
     * ① 直接 invoke；② {@code setAccessible(true)} 再试；③ 改调**接口上声明的同名方法**。
     *
     * <p>为什么要退让：宿主的 Kotlin internal 类（如 {@code androidx.room.coroutines.PooledConnectionImpl}）
     * 在字节码里不是 public class，JVM 的访问检查会拒绝反射调用它的 public final 成员 ——
     * 真机原文：{@code IllegalAccessException：class ...HostSqlBridge cannot access a member of class
     * androidx.room.coroutines.PooledConnectionImpl with modifiers "public final"}。
     * 同样的方法在**接口**（{@code RawConnectionAccessor}）上声明时，声明类是 public 接口，调用就合法。</p>
     */
    private static Object invokeFlexible(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable first) {
            Throwable c1 = unwrap(first);
            try {
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable second) {
                Method alt = declaredInInterface(target.getClass(), m.getName(), m.getParameterCount());
                if (alt != null) {
                    try {
                        Object r = alt.invoke(target);
                        PluginLog.i(TAG, "反射退让成功：" + m.getDeclaringClass().getSimpleName() + "." + m.getName()
                                + "() 改由接口 " + alt.getDeclaringClass().getName() + " 调用");
                        return r;
                    } catch (Throwable third) {
                        PluginLog.w(TAG, "反射调用被拒（三连）：" + m.getName() + "() → " + brief(c1)
                                + "；setAccessible 后 → " + brief(unwrap(second))
                                + "；接口调用 → " + brief(unwrap(third)));
                        return null;
                    }
                }
                PluginLog.w(TAG, "反射调用被拒：" + m.getDeclaringClass().getName() + "." + m.getName() + "() → "
                        + brief(c1) + "；setAccessible 后 → " + brief(unwrap(second)));
                return null;
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    /** 在对象的**全部接口**（含父接口）里找同名同参数个数的方法。 */
    private static Method declaredInInterface(Class<?> cls, String name, int paramCount) {
        for (Class<?> i : allInterfaces(cls)) {
            for (Method m : i.getMethods()) {
                if (m.getParameterCount() == paramCount && m.getName().equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> cls) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            Class<?> i = queue.poll();
            if (i == null || out.contains(i)) {
                continue;
            }
            out.add(i);
            for (Class<?> p : i.getInterfaces()) {
                queue.add(p);
            }
        }
        return out;
    }

    /**
     * 取连接上的 {@code prepare(String)}：优先**接口上声明的**（{@code androidx.sqlite.SQLiteConnection}），
     * 再退到具体类 + {@code setAccessible}。返回 null 表示这个对象不能当连接用。
     */
    private static Method prepareMethod(Object conn) {
        if (conn == null) {
            return null;
        }
        for (Class<?> i : allInterfaces(conn.getClass())) {
            for (Method m : i.getMethods()) {
                if ("prepare".equals(m.getName()) && m.getParameterCount() == 1
                        && String.class.equals(m.getParameterTypes()[0])) {
                    return m;
                }
            }
        }
        for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if ("prepare".equals(m.getName()) && m.getParameterCount() == 1
                        && String.class.equals(m.getParameterTypes()[0])) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 退让失败也在下面真调用时暴露
                    }
                    return m;
                }
            }
        }
        return null;
    }

    private static Object invokeQuietly(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            PluginLog.w(TAG, "反射调用被拒：" + m.getDeclaringClass().getName() + "." + m.getName() + "() → " + brief(c));
            return null;
        }
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) {
            return;
        }
        try {
            Method m = closeable.getClass().getMethod("close");
            m.invoke(closeable);
        } catch (Throwable ignored) {
            // 关连接失败不影响结果
        }
    }

    private static void closeQuietly(Object closeable, Method close) {
        if (closeable == null || close == null) {
            return;
        }
        try {
            close.invoke(closeable);
        } catch (Throwable ignored) {
            // 关语句失败不覆盖真正的错误
        }
    }

    private static ClassLoader classLoaderOf(Object hint) {
        ClassLoader cl = hint == null ? null : hint.getClass().getClassLoader();
        return cl != null ? cl : HostSqlBridge.class.getClassLoader();
    }

    /** 加载宿主侧类：宿主对象加载器 → 插件加载器 → 线程上下文 → 系统。 */
    private static Class<?> load(String name, Object hint) {
        ClassLoader[] loaders = {
                hint == null ? null : hint.getClass().getClassLoader(),
                HostSqlBridge.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                null,
        };
        for (ClassLoader cl : loaders) {
            try {
                return cl == null ? Class.forName(name) : Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // 换下一个加载器
            }
        }
        PluginLog.d(TAG, "类不可用：" + name);
        return null;
    }

    private static String names(Class<?>[] types) {
        if (types == null || types.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Class<?> t : types) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.getSimpleName());
        }
        return sb.toString();
    }

    private static String methodNames(Class<?> cls) {
        if (cls == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : cls.getMethods()) {
            if (sb.length() > 400) {
                sb.append(" …");
                break;
            }
            sb.append(m.getName()).append('/').append(m.getParameterCount()).append(' ');
        }
        return sb.toString();
    }

    /** 无参方法清单（名字 → 返回类型简单名），用于「找不到连接」时的下一轮线索。 */
    private static String noArgDump(Class<?> cls) {
        if (cls == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0 || "getClass".equals(m.getName())) {
                continue;
            }
            if (sb.length() > 500) {
                sb.append(" …");
                break;
            }
            sb.append(m.getName()).append(" → ").append(m.getReturnType().getSimpleName()).append("; ");
        }
        return sb.toString();
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        return String.valueOf(v);
    }

    /** 数值读取（{@code SELECT changes()} 之类；拿不到数字当 0，不抛）。 */
    private static long asLongOf(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String desc(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }

    /**
     * 通路判定用的描述：协程内部哨兵值（{@code CoroutineSingletons}）不按类名原样打出去 ——
     * 它出现在日志里会被误读成「通路坏了」（B2 真机就是这么误导的），这里显式说明它不是错误。
     */
    private static String outcomeDesc(Object v) {
        String d = desc(v);
        return d.contains("CoroutineSingletons") ? "CoroutineSingletons（协程内部哨兵值，非错误）" : d;
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
    }
}
