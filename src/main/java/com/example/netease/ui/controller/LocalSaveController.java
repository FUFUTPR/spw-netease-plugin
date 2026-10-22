package com.example.netease.ui.controller;

import com.example.netease.net.Dto;
import com.example.netease.svc.LocalPlaylists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 本地歌单命名控制器（批次 3；契约 build/_tmp/batch3-contracts.md「队友 2」，需求 docs/PLAN.md §8.3）。
 *
 * <p>把 {@code PlaylistWindow.saveCurrentToLocal} 的手工保存流程抽成<b>无 UI 依赖</b>的状态机：
 * 打开面板时捕获「来源 + 曲目快照」并给出默认名；提交时在调用栈内固定名称与曲目快照，
 * 写盘交给注入的 {@link Async} 在后台执行，成功后在同一次回调里刷新本地歌单条目；
 * 连续点击由 {@code busy} 拦截，保证不重复写盘。本类不 import 任何 Swing / AWT / Kotlin / Compose 类型。</p>
 *
 * <p>线程规则：
 * <ul>
 *   <li>所有公共方法可被任意线程调用；状态变更与 {@link Listener} 通知在同一把锁内完成
 *       （先真正改状态、再通知，通知顺序与变更顺序一致）；</li>
 *   <li>{@link Async} 的 run / cancel <b>在锁外</b>调用：生产实现会在自己的监视器里执行回调，
 *       持锁调用会形成「Async 锁 → 控制器锁」与「控制器锁 → Async 锁」的反向锁序死锁；</li>
 *   <li>listener 可能来自工作线程，UI 侧需自行切到 UI 线程；listener 抛异常会被吞掉，不破坏状态机。</li>
 * </ul>
 * </p>
 *
 * <p>契约要点：空白名称与空曲目都不写盘；保存前固定 finalName / finalSongs（之后再改源列表不影响本次写盘）；
 * {@code save} 成功后同一次回调内读 {@code list()} 刷新 entries，刷新失败保留旧条目且不覆盖成功文案；
 * {@link #cancelPending()} 只取消「刷新条目」这类只读任务，不取消已提交的写盘。</p>
 */
public final class LocalSaveController {

    /** 来源为空时的兜底默认名（与 {@code PlaylistWindow.saveCurrentToLocal} 的 {@code def} 一致）。 */
    public static final String DEFAULT_NAME = "我的本地歌单";

    /** 读取当前曲目失败（{@link Ports#tracks()} 抛异常时的反馈）。 */
    public static final String TEXT_READ_FAILED = "读取当前曲目失败";

    /** 写盘进行中再提交的反馈。 */
    public static final String TEXT_BUSY = "正在保存，请稍候";

    /** 空名称 / 全空白名称的反馈。 */
    public static final String TEXT_EMPTY_NAME = "名称不能为空";

    /** 快照为空时的反馈。 */
    public static final String TEXT_NO_TRACKS = "当前没有曲目可保存，请先载入歌单";

    /** 写盘提交后的等待文案。 */
    public static final String TEXT_SAVING = "正在保存…";

    /** 写盘失败（端口返回 null / 空 key，或抛异常）的反馈。 */
    public static final String TEXT_SAVE_FAILED = "保存本地歌单失败，请重试";

    /** 刷新本地歌单条目失败的反馈。 */
    public static final String TEXT_REFRESH_FAILED = "刷新本地歌单失败";

    /**
     * 一次性状态快照（不可变；字符串永不为 null，entries 永不为 null）。
     *
     * @param hasTracks   当前快照是否有曲目
     * @param trackCount  当前快照曲目数
     * @param sourceName  曲目来源名（null 归一为 ""；只做归一不 trim）
     * @param defaultName 命名输入框的默认名（来源 trim 后非空则用来源，否则 {@link #DEFAULT_NAME}）
     * @param busy        写盘是否进行中（提交后、回调前为 true）
     * @param message     面向用户的一行反馈
     * @param saved       最近一次写盘是否成功
     * @param entries     本地歌单条目（只读快照）
     */
    public record State(boolean hasTracks, int trackCount, String sourceName, String defaultName,
                        boolean busy, String message, boolean saved, List<LocalPlaylists.Entry> entries) {
    }

    /** 控制器依赖的端口：全部由 UI / 生产适配层注入，控制器不直接做文件 IO。 */
    public interface Ports {

        /** 当前曲目（可为 null / 空列表；控制器只读取一次并做防御性拷贝）。 */
        List<Dto.Song> tracks();

        /** 当前曲目来源名（可为 null）。 */
        String sourceName();

        /**
         * 写盘：把 {@code songs} 存成一个本地歌单。
         *
         * @return 成功时返回非空 key；失败返回 null（契约：key 非空才算成功）
         */
        String save(String name, List<Dto.Song> songs);

        /** 列出本地歌单条目（刷新用）。 */
        List<LocalPlaylists.Entry> list();
    }

    /** 后台任务体（允许抛受检异常，由 {@link Async} 归到 failure 回调）。 */
    public interface Fetcher<T> {
        T fetch() throws Exception;
    }

    /** 后台执行通道（生产实现通常是 LatestTask / 线程池包装）。 */
    public interface Async {
        <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure);

        void cancel();

        void close();
    }

    /** 状态观察者（可能在任意线程被调用）。 */
    public interface Listener {
        void onState(State state);
    }

    private final Object lock = new Object();
    private final Ports ports;
    private final Async async;
    private final Listener listener;

    /** 当前曲目快照（防御性拷贝，永不为 null；元素按原样保留，含 Song record 的浅拷贝）。 */
    private List<Dto.Song> songs = List.of();
    private String sourceName = "";
    private String defaultName = DEFAULT_NAME;
    private String message = "";
    private boolean busy;
    private boolean saved;
    private List<LocalPlaylists.Entry> entries = List.of();

    /**
     * 构造：entries 为空、message 为空、saved=false，<b>不自动读盘</b>（不调 ports）。
     *
     * @param ports    端口（必填）
     * @param async    后台通道（必填）
     * @param listener 状态观察者；null 视为不关心（内部用空实现）
     */
    public LocalSaveController(Ports ports, Async async, Listener listener) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.async = Objects.requireNonNull(async, "async");
        this.listener = listener == null ? state -> { } : listener;
    }

    /** 当前状态快照。 */
    public State state() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    /** 命名输入框的默认名（最近一次 {@link #capture} 计算出的值；构造初值为 {@link #DEFAULT_NAME}）。 */
    public String defaultName() {
        synchronized (lock) {
            return defaultName;
        }
    }

    // ------------------------------------------------------------------ 捕获

    /**
     * 从端口读取当前曲目与来源名后捕获快照（打开命名面板时调用）。
     *
     * <p>{@code ports.tracks()} 抛异常时按空列表处理，并把 message 置为 {@link #TEXT_READ_FAILED}，
     * 面板仍可打开。</p>
     */
    public void capture() {
        List<Dto.Song> tracks;
        String failure = "";
        try {
            tracks = ports.tracks();
        } catch (Throwable t) {
            tracks = List.of();
            failure = TEXT_READ_FAILED;
        }
        String source;
        try {
            source = ports.sourceName();
        } catch (Throwable t) {
            source = "";
        }
        applyCapture(tracks, source, failure);
    }

    /**
     * 用调用方给出的曲目 / 来源捕获快照（打开命名面板时调用；曲目列表为空也允许）。
     *
     * <p>内部保存 {@code new ArrayList} 的防御性拷贝，调用方之后 add / clear 不会影响快照。
     * 捕获成功会清空上一轮提示并复位 saved（新快照尚未保存）。</p>
     *
     * @param tracks     曲目快照（null 视为空列表）
     * @param sourceName 来源名（null 归一为 ""；计算默认名时才 trim）
     */
    public void capture(List<Dto.Song> tracks, String sourceName) {
        applyCapture(tracks, sourceName, "");
    }

    private void applyCapture(List<Dto.Song> tracks, String sourceName, String nextMessage) {
        synchronized (lock) {
            songs = copySongs(tracks);
            this.sourceName = norm(sourceName);
            defaultName = defaultNameFor(this.sourceName);
            saved = false;
            message = norm(nextMessage);
            publishLocked();
        }
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 提交保存。
     *
     * <p>busy / 空名称 / 空曲目三种情况只改 message 并返回 false，绝不调用 {@link Ports#save}。
     * 正常提交时<b>在本调用栈内</b>固定 trim 后的名称与曲目快照，之后改源列表不影响本次写盘；
     * 写盘由 {@link Async} 在后台执行，回调返回后刷新 entries。</p>
     *
     * @param rawName 用户输入的名称（null 视为 ""）
     * @return 是否真的发起了一次写盘
     */
    public boolean submit(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        List<Dto.Song> finalSongs;
        synchronized (lock) {
            if (busy) {
                message = TEXT_BUSY;
                publishLocked();
                return false;
            }
            if (name.isEmpty()) {
                message = TEXT_EMPTY_NAME;
                publishLocked();
                return false;
            }
            if (songs.isEmpty()) {
                message = TEXT_NO_TRACKS;
                publishLocked();
                return false;
            }
            // 契约：在 submit 调用栈内固定 finalName / finalSongs（不可变副本，端口也改不动快照）。
            finalSongs = Collections.unmodifiableList(new ArrayList<>(songs));
            busy = true;
            saved = false;
            message = TEXT_SAVING;
            publishLocked();
        }
        final String finalName = name;
        final int count = finalSongs.size();
        try {
            async.run(() -> ports.save(finalName, finalSongs),
                    key -> onSaved(finalName, count, key),
                    this::onSaveFailed);
        } catch (Throwable dispatchError) {
            // 通道拒绝投递：按保存失败收尾，不留「永远 busy」的死状态。
            onSaveFailed(dispatchError);
            return false;
        }
        return true;
    }

    /** 写盘回调：key 非空才算成功；成功后在同一次回调内同步刷新 entries。 */
    private void onSaved(String finalName, int count, String key) {
        boolean ok = key != null && !key.isEmpty();
        synchronized (lock) {
            busy = false;
            if (!ok) {
                saved = false;
                message = TEXT_SAVE_FAILED;
                publishLocked();
                return;
            }
            saved = true;
            message = "已存为本地歌单「" + finalName + "」（" + count + " 首）";
        }
        List<LocalPlaylists.Entry> refreshed = null;
        try {
            // 契约：同一次异步回调内刷新；list 抛异常则保留旧 entries、不覆盖成功文案。
            refreshed = copyEntries(ports.list());
        } catch (Throwable ignored) {
            // 刷新失败不影响「已保存」结果。
        }
        synchronized (lock) {
            if (refreshed != null) {
                entries = refreshed;
            }
            publishLocked();
        }
    }

    /** 写盘失败（端口抛异常 / 通道异常 / 迟到失败）：只复位 busy 并给失败文案，不动 entries。 */
    private void onSaveFailed(Throwable error) {
        synchronized (lock) {
            busy = false;
            saved = false;
            message = TEXT_SAVE_FAILED;
            publishLocked();
        }
    }

    // ------------------------------------------------------------------ 刷新

    /** 异步刷新本地歌单条目；busy（写盘进行中）时直接忽略。成功更新 entries，失败保留旧值。 */
    public void refreshEntries() {
        synchronized (lock) {
            if (busy) {
                return;
            }
        }
        try {
            async.run(ports::list, this::onEntriesLoaded, this::onRefreshFailed);
        } catch (Throwable dispatchError) {
            onRefreshFailed(dispatchError);
        }
    }

    private void onEntriesLoaded(List<LocalPlaylists.Entry> list) {
        List<LocalPlaylists.Entry> next = copyEntries(list);
        synchronized (lock) {
            entries = next;
            publishLocked();
        }
    }

    private void onRefreshFailed(Throwable error) {
        synchronized (lock) {
            message = TEXT_REFRESH_FAILED;
            publishLocked();
        }
    }

    // ------------------------------------------------------------------ 取消

    /**
     * 取消「刷新条目」这类只读后台任务。
     *
     * <p><b>注意：不会取消已提交的写盘</b>——写盘一旦投递就必须跑完（否则磁盘上半成品无法回收），
     * 因此 busy 保持不变，写盘结果仍会通过 listener 发布；本方法也不发布状态。</p>
     */
    public void cancelPending() {
        async.cancel();
    }

    /**
     * 关闭控制器（插件停用 / 原生页面生命周期结束）。
     *
     * <p>取消在途的只读刷新并关闭 {@link Async} 通道；<b>已提交的写盘不会被撤销</b>
     * （与 {@link #cancelPending()} 语义一致，见上）。重复调用必须无副作用。</p>
     */
    public void close() {
        async.cancel();
        async.close();
    }

    // ------------------------------------------------------------------ 内部工具

    private State snapshotLocked() {
        return new State(!songs.isEmpty(), songs.size(), sourceName, defaultName,
                busy, message, saved, entries);
    }

    /** 通知 listener（在锁内调用；回调异常一律吞掉，不让 UI 影响状态机）。 */
    private void publishLocked() {
        State next = snapshotLocked();
        try {
            listener.onState(next);
        } catch (Throwable ignored) {
            // 契约限定控制器只依赖 Dto / LocalPlaylists 等插件类，这里不打日志，仅保证状态机不被 UI 异常破坏。
        }
    }

    /** 字符串 null 归一为 ""（契约 §通用规则 6）。 */
    private static String norm(String value) {
        return value == null ? "" : value;
    }

    /** 默认名：来源 trim 后非空则用 trim 后的来源，否则兜底名。 */
    private static String defaultNameFor(String source) {
        String trimmed = source == null ? "" : source.trim();
        return trimmed.isEmpty() ? DEFAULT_NAME : trimmed;
    }

    /** 曲目快照的防御性拷贝（契约指定 new ArrayList；空列表统一 List.of()）。 */
    private static List<Dto.Song> copySongs(List<Dto.Song> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(source);
    }

    /** entries 快照：过滤 null 元素、空列表统一 List.of()、结果不可变。 */
    private static List<LocalPlaylists.Entry> copyEntries(List<LocalPlaylists.Entry> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<LocalPlaylists.Entry> copy = new ArrayList<>(source.size());
        for (LocalPlaylists.Entry entry : source) {
            if (entry != null) {
                copy.add(entry);
            }
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }
}
