package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** One sync at a time. Tickets never revive after account, lifecycle or merge changes. */
public final class SyncCoordinator {
    public enum RequestResult { STARTED, BUSY, DISABLED, UNAUTHENTICATED, STOPPED, FAILED }
    public enum Phase { IDLE, WAITING, FETCHING, WRITING, COMPLETED, ABORTED, FAILED }
    public record Status(Phase phase, long requestId, String message, int completed, int total) {
        public String describe() {
            String label = switch (phase) {
                case IDLE -> "尚未同步"; case WAITING -> "等待同步"; case FETCHING -> "拉取中";
                case WRITING -> "写入中"; case COMPLETED -> "同步完成";
                case ABORTED -> "同步中止"; case FAILED -> "同步失败";
            };
            return label + (total > 0 ? " " + completed + "/" + total : "")
                    + (message.isBlank() ? "" : "：" + message);
        }
    }
    public record Ticket(long id, long generation, long uid, long session, long lifecycle, long deadline) { }
    private final BooleanSupplier enabled;
    private final LongSupplier uid;
    private final LongSupplier session;
    private final ToLongFunction<String> begin;
    private final LongPredicate valid;
    private final Consumer<List<String[]>> execute;
    private final Supplier<String> summary;
    private final Consumer<String> end;
    private final long timeoutNanos;
    private final ThreadLocal<Ticket> current = new ThreadLocal<>();
    private volatile Thread worker;
    private record Pending(List<String[]> list, String reason, long uid, long session, long lifecycle) { }
    private Pending pending;
    private volatile boolean stopped;
    private volatile long lifecycle;
    private long sequence;
    private volatile Status status = new Status(Phase.IDLE, 0, "", 0, 0);

    public SyncCoordinator(BooleanSupplier enabled, LongSupplier uid, LongSupplier session,
                           ToLongFunction<String> begin, LongPredicate valid,
                           Consumer<List<String[]>> execute, Supplier<String> summary, Consumer<String> end) {
        this(enabled, uid, session, begin, valid, execute, summary, end, 600_000L);
    }
    public SyncCoordinator(BooleanSupplier enabled, LongSupplier uid, LongSupplier session,
                           ToLongFunction<String> begin, LongPredicate valid,
                           Consumer<List<String[]>> execute, Supplier<String> summary, Consumer<String> end,
                           long timeoutMs) {
        this.enabled = enabled; this.uid = uid; this.session = session; this.begin = begin;
        this.valid = valid; this.execute = execute; this.summary = summary; this.end = end;
        timeoutNanos = Math.max(1L, timeoutMs) * 1_000_000L;
    }
    public Status status() { return status; }
    /** Only an explicit plugin start may reopen admission; old tickets remain invalid. */
    public synchronized void beginLifecycle() {
        lifecycle++;
        pending = null;
        stopped = false;
        status = new Status(worker == null ? Phase.IDLE : Phase.WAITING, sequence,
                worker == null ? "" : "等待上一轮同步退出", 0, 0);
    }
    public Ticket ticket() { return current.get(); }
    public boolean isValid(Ticket t) {
        return t != null && !stopped && lifecycle == t.lifecycle() && session.getAsLong() == t.session()
                && uid.getAsLong() == t.uid() && enabled.getAsBoolean() && valid.test(t.generation())
                && System.nanoTime() - t.deadline() < 0;
    }
    public void check(Ticket t) {
        if (!isValid(t)) { throw new CancellationException("任务失效：停用、账号或开关变化，或同步超时"); }
    }
    public synchronized void progress(Ticket t, Phase phase, String message, int done, int total) {
        check(t);
        status = new Status(phase, t.id(), message == null ? "" : message, done, total);
    }
    public synchronized RequestResult request(List<String[]> list, String why) {
        if (stopped) { return RequestResult.STOPPED; }
        if (worker != null) { return RequestResult.BUSY; }
        List<String[]> snapshot = list == null ? List.of() : list.stream().map(String[]::clone).toList();
        if (!enabled.getAsBoolean()) {
            status = new Status(Phase.ABORTED, sequence, "合并歌单已关闭", 0, 0);
            return RequestResult.DISABLED;
        }
        long owner = uid.getAsLong();
        if (snapshot.isEmpty() && owner <= 0) {
            status = new Status(Phase.ABORTED, sequence, "请先登录", 0, 0);
            return RequestResult.UNAUTHENTICATED;
        }
        String reason = why == null || why.isBlank() ? "未说明" : why;
        long epoch = session.getAsLong();
        long generation = begin.applyAsLong(reason);
        if (generation < 0) { return RequestResult.BUSY; }
        Ticket ticket = new Ticket(++sequence, generation, owner, epoch, lifecycle,
                System.nanoTime() + timeoutNanos);
        status = new Status(Phase.WAITING, ticket.id(), reason, 0, 0);
        try {
            worker = new Thread(() -> run(snapshot, ticket), "netease-native-sync");
            worker.setDaemon(true);
            worker.start();
            return RequestResult.STARTED;
        } catch (Throwable error) {
            worker = null;
            status = new Status(Phase.FAILED, ticket.id(), "无法启动同步任务", 0, 0);
            end.accept(status.describe());
            PluginLog.e("sync", "原生同步线程启动失败（已放闸）", error);
            return RequestResult.FAILED;
        }
    }

    /** Login is a hand-off: retain the latest account request while cancelled I/O drains. */
    public synchronized RequestResult requestAfterLogin() {
        if (stopped) return RequestResult.STOPPED;
        if (!enabled.getAsBoolean()) return RequestResult.DISABLED;
        long owner = uid.getAsLong();
        if (owner <= 0) return RequestResult.UNAUTHENTICATED;
        if (worker == null) return request(List.of(), "force:登录后");
        invalidate("账号登录已更新");
        pending = new Pending(List.of(), "force:登录后", owner, session.getAsLong(), lifecycle);
        status = new Status(Phase.WAITING, sequence, "正在获取歌单，请稍等片刻（等待上一轮任务退出）", 0, 0);
        return RequestResult.STARTED;
    }
    private void run(List<String[]> list, Ticket ticket) {
        current.set(ticket);
        try {
            check(ticket);
            progress(ticket, Phase.FETCHING, "获取歌单清单", 0, 0);
            execute.accept(list);
            synchronized (this) {
                if (status.phase() != Phase.FAILED && status.phase() != Phase.ABORTED) {
                    check(ticket);
                    status = new Status(Phase.COMPLETED, ticket.id(), summary.get(), 0, 0);
                }
            }
        } catch (CancellationException cancelled) {
            synchronized (this) { if (lifecycle == ticket.lifecycle()) status = new Status(Phase.ABORTED, ticket.id(), cancelled.getMessage(), 0, 0); }
        } catch (Throwable error) {
            synchronized (this) { if (lifecycle == ticket.lifecycle()) status = new Status(Phase.FAILED, ticket.id(), "同步异常，详情见日志", 0, 0); }
            PluginLog.e("sync", "同步任务失败", error);
        } finally {
            try { end.accept(lifecycle == ticket.lifecycle() ? status.describe() : "上一轮同步已中止"); }
            finally {
                current.remove();
                synchronized (this) {
                    worker = null;
                    if (!stopped && lifecycle != ticket.lifecycle() && status.phase() == Phase.WAITING)
                        status = new Status(Phase.IDLE, sequence, "", 0, 0);
                    notifyAll();
                    Pending next = pending;
                    pending = null;
                    if (next != null && !stopped && next.lifecycle() == lifecycle && enabled.getAsBoolean()
                            && next.uid() == uid.getAsLong() && next.session() == session.getAsLong())
                        request(next.list(), next.reason());
                }
            }
        }
    }
    public synchronized void invalidate(String reason) {
        lifecycle++;
        pending = null;
        if (worker != null) {
            worker.interrupt();
            status = new Status(Phase.ABORTED, sequence, reason, 0, 0);
        }
    }
    public void stop() {
        Thread pending;
        synchronized (this) { stopped = true; invalidate("插件已停用"); pending = worker; }
        if (pending != null && pending != Thread.currentThread()) {
            try { pending.join(1500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
