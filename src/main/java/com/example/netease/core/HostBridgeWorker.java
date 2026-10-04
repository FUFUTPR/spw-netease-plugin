package com.example.netease.core;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * ⚠️ 本线程是**唯一允许触碰宿主 API / 宿主类**的地方。
 *
 * <p>为什么必须这样（一手事故教训，见 docs/01 §C7）：</p>
 * <p>宿主启动时会在 {@code AppConfig.<clinit>}（Kotlin object 静态初始化）里回调插件；如果插件同时
 * 在别的线程反射读宿主类，两者就会交叉等待 → **永久死锁**，现象是「只有插件跟着播放器一起启动时才卡死」。</p>
 *
 * <p>因此：</p>
 * <ul>
 *   <li>单条守护线程 {@code spw-netease-host} 串行执行所有宿主交互；</li>
 *   <li>{@link #submit(Runnable)} 永不阻塞调用方，也永不抛异常；</li>
 *   <li>绝不在持有自己锁的情况下去加载宿主类（循环里执行任务时**不持锁**）。</li>
 * </ul>
 *
 * <p><b>0.11.12 新增「快车道」（{@link #submitFast}）</b>：这条线程是<b>串行</b>的，队列容量 512，
 * 上面排着原生装配、行封面前瞻（每 3~5 秒一批 4 首投递）、库读写等长任务；而后台的
 * 「播放条封面投递」是要赶在用户看见播放条之前完成的（换曲那一刻），排在队尾就会晚
 * <b>0~5 秒</b>（0.11.11 真机实测，见 docs/51-播放条封面零延迟-0.11.12.md）——
 * 用户看到的现象就是「音乐都响了，封面过几秒才出来」。快车道把这类任务插到队首：
 * 仍在唯一线程上执行（契约 §4 规则 1 不变），只是<b>不再排在长任务后面</b>。</p>
 *
 * <p>快车道是<b>后进先出</b>的（每个 {@code submitFast} 都插队首）——这正是投递类任务想要的
 * 语义（「最新一首」优先，旧的连按下一首时的中间曲目可以整批作废）。容量小（{@link #FAST_CAPACITY}），
 * 满即丢并记一行日志，绝不让它变成第二个积压队列。</p>
 */
public final class HostBridgeWorker {

    private static final HostBridgeWorker INSTANCE = new HostBridgeWorker();
    private static final int CAPACITY = 512;
    private static final int FAST_CAPACITY = 64;

    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(CAPACITY);
    private volatile Thread thread;
    private volatile boolean running;

    /** 上一个任务的耗时与当前任务起点：用于给「快车道为什么还等了 N ms」归因（0.11.12 真机取证）。 */
    private static volatile long lastTaskMs = -1L;
    private static volatile long busySince = -1L;

    private HostBridgeWorker() {
    }

    public static HostBridgeWorker get() {
        return INSTANCE;
    }

    /** 启动宿主交互线程（由 {@code start()} 调用；不触碰任何宿主类）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "spw-netease-host");
        t.setDaemon(true);
        // 兜底：让宿主类加载时能沿插件类加载器链上溯（docs/01 §C7 的兜底顺序第 1 步）
        t.setContextClassLoader(HostBridgeWorker.class.getClassLoader());
        thread = t;
        t.start();
        PluginLog.d("host", "宿主交互线程已启动：" + t.getName());
    }

    /** 停止（幂等）。由 {@code stop()} 调用。 */
    public synchronized void stop() {
        if (!running && thread == null) {
            return;
        }
        running = false;
        Thread t = thread;
        thread = null;
        queue.clear();
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isOnWorkerThread() {
        return Thread.currentThread() == thread && thread != null;
    }

    /**
     * 投递一个宿主交互任务。**永不阻塞、永不抛**；队列满或未启动时丢弃并记日志。
     *
     * @return 是否成功入队
     */
    public boolean submit(Runnable task) {
        if (task == null) {
            return false;
        }
        if (!running) {
            PluginLog.w("host", "宿主交互线程未启动，任务被丢弃");
            return false;
        }
        if (!queue.offerLast(task)) {
            PluginLog.w("host", "宿主交互队列已满（容量 " + CAPACITY + "），任务被丢弃");
            return false;
        }
        return true;
    }

    /**
     * 快车道投递：插到<b>队首</b>，下一次取任务就执行它（不排在已积压的长任务后面）。
     *
     * <p>用途只有一个：那些「用户正等着的、毫秒级的宿主内存态写」——目前是播放条 / 播放页封面投递
     * （{@code svc.PlaybarCover}）。仍然在唯一的宿主交互线程上跑，线程模型不变。</p>
     *
     * <p>语义是<b>后进先出</b>：同一批连投时最后投的那个先执行（投递类任务要的就是「最新一首赢」）。
     * 永不阻塞、永不抛；快车道满则退回普通队列，普通队列也满才丢弃。</p>
     *
     * @return 是否成功入队
     */
    public boolean submitFast(Runnable task) {
        if (task == null) {
            return false;
        }
        if (!running) {
            PluginLog.w("host", "宿主交互线程未启动，快车道任务被丢弃");
            return false;
        }
        long enq = System.currentTimeMillis();
        Runnable fast = () -> {
            long waited = System.currentTimeMillis() - enq;
            if (waited >= 300L) {
                // 「换曲那一刻封面还没出来」的排队部分：这一行是它的唯一机器证据
                PluginLog.i("host", "宿主交互快车道等待 " + waited + " ms（上一个任务耗时 " + lastTaskMs
                        + " ms，队列剩余 " + queue.size() + "）");
            }
            safeRun(task);
        };
        if (queue.offerFirst(fast)) {
            return true;
        }
        if (queue.offerLast(fast)) {
            PluginLog.d("host", "宿主交互快车道已满（容量 " + FAST_CAPACITY + "），已退回普通队列");
            return true;
        }
        PluginLog.w("host", "宿主交互队列已满（容量 " + CAPACITY + "），快车道任务被丢弃");
        return false;
    }

    /** 已在 worker 线程上就直接执行，否则投递。 */
    public void runNow(Runnable task) {
        if (task == null) {
            return;
        }
        if (isOnWorkerThread()) {
            safeRun(task);
        } else {
            submit(task);
        }
    }

    private void loop() {
        while (running) {
            Runnable task;
            try {
                task = queue.takeFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            safeRun(task);   // ⚠️ 此处不持任何锁
        }
        PluginLog.d("host", "宿主交互线程已退出");
    }

    private void safeRun(Runnable task) {
        long t0 = System.currentTimeMillis();
        busySince = t0;
        try {
            task.run();
        } catch (Throwable t) {
            // 任何异常都不能向上冒泡到宿主
            PluginLog.e("host", "宿主交互任务异常（已吞掉）", t);
        } finally {
            long ms = System.currentTimeMillis() - t0;
            lastTaskMs = ms;
            busySince = -1L;
            if (ms >= 500L) {
                // 谁把唯一线程占住了：长任务必须自报家门，否则「封面慢」只能靠猜
                PluginLog.w("host", "宿主交互任务耗时 " + ms + " ms（队列剩余 " + queue.size() + "）");
            }
        }
    }
}
