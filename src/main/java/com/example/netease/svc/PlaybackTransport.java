package com.example.netease.svc;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Detects a selected HTTP item that the host never opens. Position/Ready are not audio evidence. */
public final class PlaybackTransport implements AutoCloseable {
    public record Selection(long songId, String location, String level, long epoch, long request, long load) { }
    public interface Jobs {
        void run(Supplier<Path> fetch, Consumer<Path> success, Consumer<Throwable> failure);
        void cancel();
    }
    public interface Port {
        long lastRequest(long songId);
        Path prepare(Selection item, BooleanSupplier valid) throws Exception;
        boolean replay(Selection item, Path local);
        void report(Selection item, String message);
        default void discard(Path local) { }
    }
    private final Jobs jobs;
    private final Port port;
    private final LongSupplier clock;
    private final long waitMs;
    private Selection selected;
    private long since, generation;
    private boolean playing, attempted, running, closed;
    private String currentMode="auto";
    // Once a path has actually been opened, pauses/seeks/repeats must not trigger a replay.
    private final Set<String> proven = new LinkedHashSet<>();
    public PlaybackTransport(Jobs jobs, Port port, LongSupplier clock, long waitMs) {
        this.jobs=jobs; this.port=port; this.clock=clock; this.waitMs=waitMs;
    }
    private static String key(Selection s) { return s.epoch()+":"+s.location(); }
    public synchronized void observe(Selection next, boolean isPlaying, String mode) {
        if(closed) return;
        playing=isPlaying;
        if(!Objects.equals(next,selected) || !isPlaying || !Objects.equals(mode,currentMode)) {
            generation++; jobs.cancel(); selected=next; since=clock.getAsLong(); attempted=false; running=false;
            currentMode=mode;
        }
        if(next==null || !isPlaying) return;
        long last=port.lastRequest(next.songId());
        if(last>0) {
            proven.add(key(next));
            if(proven.size()>256) proven.remove(proven.iterator().next());
        }
        boolean force="local".equals(mode);
        if(!force && proven.contains(key(next))) {
            if(running) { generation++; jobs.cancel(); running=false; }
            return;
        }
        if(attempted) return;
        if(clock.getAsLong()-since < (force ? 0 : waitMs)) return;
        attempted=true;
        port.report(next,force ? "已选择本地文件兼容；正在准备本地兼容音频" : "宿主未请求播放地址"+
            ("http".equals(mode) ? "；可在音乐设置切换为本地文件兼容" : "；正在准备本地兼容音频"));
        if("http".equals(mode)) return;
        long token=generation;
        running=true;
        jobs.run(() -> {
            try { return port.prepare(next,() -> valid(token,next)); }
            catch(Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, local -> {
            synchronized(this) {
                if(!valid(token,next)) { port.discard(local); return; }
                running=false;
                if(!force && port.lastRequest(next.songId())>0) { port.discard(local); return; }
                // The host adapter checks the authoritative current item and account again.
                if(local==null || !port.replay(next,local)) {
                    port.discard(local);
                    port.report(next,"本地兼容播放失败，请重新选择歌曲，详情见日志");
                }
            }
        }, error -> {
            synchronized(this) {
                if(valid(token,next)) { running=false; port.report(next,"本地兼容音频准备失败，请重试："+error.getClass().getSimpleName()); }
            }
        });
    }
    private synchronized boolean valid(long token, Selection s) {
        return !closed && playing && token==generation && Objects.equals(s,selected);
    }
    public synchronized void invalidate() { generation++; jobs.cancel(); selected=null; attempted=false; running=false; proven.clear(); }
    @Override public synchronized void close() { closed=true; invalidate(); }

    /** Windows JVMs under Proton still report os.name=Windows; inherited Wine/Proton markers matter. */
    public static boolean compatibilityEnvironment(String os, Map<String,String> env) {
        return os.toLowerCase(Locale.ROOT).contains("linux") ||
            List.of("WINEPREFIX","WINELOADERNOEXEC","STEAM_COMPAT_DATA_PATH","PROTON_LOG_DIR")
                .stream().anyMatch(k -> env.get(k)!=null && !env.get(k).isBlank());
    }
}
