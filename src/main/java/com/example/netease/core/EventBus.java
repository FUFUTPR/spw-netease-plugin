package com.example.netease.core;

import com.example.netease.cfg.PluginConfig;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 插件内部事件总线：1 条守护线程 {@code netease-events} 串行消费。
 *
 * <p>使用者是**宿主回调线程**（扩展点）——那里只允许「拷参数 → 入队 → 立刻 return」，
 * 所以 {@link #offer} 永不阻塞、永不抛。</p>
 *
 * <p>0.5.0 变更（瘦身版）：歌词抓取随歌词功能一并删除，{@code FetchLyric} 事件与
 * 对应的网络工作已移除；总线上只剩状态/配置/曲目观测事件。串行单线程的形态不变。</p>
 */
public final class EventBus {

    /** 事件基类。全部为不可变值对象，跨线程只传它们。 */
    public sealed interface Event permits EventBus.PlaybackState,
            EventBus.PositionTick, EventBus.ConfigChanged, EventBus.TrackChanged {
    }

    /** 播放器状态变化 / 播放暂停切换。 */
    public record PlaybackState(String state, boolean playing) implements Event {
    }

    /** 进度 tick（约每秒一次）。 */
    public record PositionTick(long positionMs) implements Event {
    }

    /** 配置文件变化。 */
    public record ConfigChanged(String fileName) implements Event {
    }

    /** 当前曲目变化（仅用于观测/统计）。 */
    public record TrackChanged(TrackInfo track) implements Event {
    }

    private static final EventBus INSTANCE = new EventBus();
    private static final int CAPACITY = 1024;

    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(CAPACITY);
    private volatile Thread thread;
    private volatile boolean running;

    private EventBus() {
    }

    public static EventBus get() {
        return INSTANCE;
    }

    /** 启动事件消费线程（不触碰宿主类）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "netease-events");
        t.setDaemon(true);
        thread = t;
        t.start();
        PluginLog.d("events", "事件消费线程已启动");
    }

    /** 停止（幂等）。 */
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

    /** 入队。队列满则丢最旧的，保证宿主回调线程永远不被阻塞。 */
    public boolean offer(Event event) {
        if (event == null) {
            return false;
        }
        if (queue.offer(event)) {
            return true;
        }
        Event dropped = queue.poll();
        PluginLog.w("events", "事件队列已满，丢弃最旧事件：" + (dropped == null ? "?" : dropped.getClass().getSimpleName()));
        return queue.offer(event);
    }

    public int pending() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            Event event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            dispatch(event);
        }
        PluginLog.d("events", "事件消费线程已退出");
    }

    private void dispatch(Event event) {
        try {
            if (event instanceof ConfigChanged e) {
                PluginConfig.onConfigChanged(e.fileName());
            } else if (event instanceof TrackChanged e) {
                PluginLog.d("events", "曲目变化：" + e.track().displayName());
            } else if (event instanceof PlaybackState e) {
                PluginLog.d("events", "播放状态：" + e.state() + " playing=" + e.playing());
            } else if (event instanceof PositionTick) {
                // 1.18.5 无 getCurrentMediaItem / getLyricsLines，进度事件暂不驱动业务；
                // 保留事件类型是为了 P4 对齐 1.19.0 时不用改架构。
            } else {
                PluginLog.d("events", "未识别事件：" + event.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            // 单个事件失败不能拖垮消费线程
            PluginLog.e("events", "事件处理异常（已吞掉）：" + event.getClass().getSimpleName(), t);
        }
    }
}
