package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.*;
import com.example.netease.host.VoxzenBridge;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Maintains the watchdog outside host callbacks. Network and file I/O stay on owned workers. */
public final class LocalPlaybackCompatibility {
    private static volatile PlaybackTransport controller;
    private static volatile ExecutorService worker;
    private static volatile ScheduledExecutorService maintenance;
    private static volatile Future<?> pending;
    private static volatile boolean playing;
    private static final AtomicLong load = new AtomicLong();
    private static final AtomicReference<java.util.Set<String>> referencedFiles = new AtomicReference<>();
    private static final AtomicBoolean tickQueued = new AtomicBoolean();
    private static volatile long localSong,localEpoch,localRequest,localLoad;
    private LocalPlaybackCompatibility() { }
    public static void onState(String name) { if("Buffering".equals(name)) load.incrementAndGet(); }
    public static void onPlaying(boolean value) { playing=value; }
    public static long loadSerial() { return load.get(); }
    public static boolean isPlaying() { return playing; }
    /** Host metadata refreshes can restore an HTTP label while the same native local stream is still open. */
    public static void noteLocalFile(long song,long epoch,long request,long serial) {
        localEpoch=epoch;localRequest=request;localLoad=serial;localSong=song;
    }
    private static boolean sameLocalLoad(PlaybackTransport.Selection s) {
        return s!=null && s.songId()==localSong && s.epoch()==localEpoch && s.request()==localRequest && s.load()==localLoad;
    }
    public static synchronized void start() {
        if(controller!=null) return;
        playing=false; referencedFiles.set(null); tickQueued.set(false); load.incrementAndGet();
        LocalAudioFiles.resetPending();
        localSong=0;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
            r -> daemon(r,"netease-local-audio"),new ThreadPoolExecutor.DiscardOldestPolicy());
        maintenance=Executors.newSingleThreadScheduledExecutor(r -> daemon(r,"netease-local-watch"));
        long delay=PlaybackTransport.compatibilityEnvironment(System.getProperty("os.name",""),System.getenv()) ? 1500 : 8000;
        var jobs=new PlaybackTransport.Jobs() {
            public void cancel() { var p=pending; if(p!=null) p.cancel(true); }
            public void run(java.util.function.Supplier<Path> fetch,java.util.function.Consumer<Path> success,
                            java.util.function.Consumer<Throwable> failure) {
                var exec=worker;
                if(exec==null) return;
                pending=exec.submit(() -> {
                    try {
                        Path result=fetch.get();
                        if(!HostBridgeWorker.get().submitFast(() -> success.accept(result))) LocalAudioFiles.discard(result);
                    }
                    catch(Throwable error) { HostBridgeWorker.get().submitFast(() -> failure.accept(error)); }
                });
            }
        };
        controller=new PlaybackTransport(jobs,new PlaybackTransport.Port() {
            public long lastRequest(long id) { return NativeStreamServer.lastPlaybackRequest(id); }
            public Path prepare(PlaybackTransport.Selection item,java.util.function.BooleanSupplier valid) throws Exception {
                return LocalAudioFiles.prepare(item,() -> valid.getAsBoolean() && item.epoch()==AccountService.sessionEpoch());
            }
            public boolean replay(PlaybackTransport.Selection item,Path local) { return VoxzenBridge.replayLocalOnWorker(item,local); }
            public void discard(Path local) { LocalAudioFiles.discard(local); }
            public void report(PlaybackTransport.Selection item,String text) {
                if(item.epoch()!=AccountService.sessionEpoch()) return;
                PluginLog.w("playback-transport","#"+item.songId()+" "+text);
                VoxzenBridge.transportReport(item,text);
            }
        },System::currentTimeMillis,delay);
        PlaybackTransport owned=controller;
        maintenance.scheduleWithFixedDelay(() -> {
            boolean restore=LocalAudioFiles.hasLeases();
            if(tickQueued.compareAndSet(false,true) && !HostBridgeWorker.get().submit(() -> {
                try {
                    if(controller!=owned) return;
                    var selection=VoxzenBridge.transportSelectionOnWorker();
                    referencedFiles.set(restore ? VoxzenBridge.restoreLocalQueueOnWorker(false) : java.util.Set.of());
                    if(sameLocalLoad(selection)) selection=null;
                    owned.observe(selection,playing,PluginConfig.playbackTransport());
                } catch(Throwable error) { PluginLog.d("playback-transport","读取播放状态失败："+error.getClass().getSimpleName()); }
                finally { tickQueued.set(false); }
            })) tickQueued.set(false);
            LocalAudioFiles.cleanExcept(referencedFiles.get(),() -> controller==owned);
        },500,500,TimeUnit.MILLISECONDS);
        PluginLog.i("playback-transport","取流监测已启用，未请求检测窗口="+delay+"ms；位置推进不作为已出声证据");
    }
    private static Thread daemon(Runnable task,String name) { var t=new Thread(task,name); t.setDaemon(true); return t; }
    public static synchronized void stop() {
        var current=controller; controller=null; if(current!=null) current.close();
        var m=maintenance; maintenance=null; if(m!=null) m.shutdownNow();
        var w=worker; worker=null; if(w!=null) w.shutdownNow();
        pending=null; playing=false; referencedFiles.set(null); load.incrementAndGet();
        localSong=0;
    }
}
