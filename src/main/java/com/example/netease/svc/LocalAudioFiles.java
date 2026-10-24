package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import java.io.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

/** A current-track lease over a verified complete stream cache, with a real codec suffix. */
public final class LocalAudioFiles {
    private static volatile boolean preparing;
    private static final Object LOCK=new Object();
    private static final java.util.Set<String> pendingLeases=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void resetPending() { pendingLeases.clear(); }
    public static void discard(Path path) { if(path!=null) pendingLeases.remove(CoverArt.fileUri(path)); }
    private LocalAudioFiles() { }
    public static Path dir() { return DataPaths.data().resolve("audio-local"); }
    /** Playback locations are filesystem paths; cover/database file URIs are a different contract. */
    public static String playbackLocation(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
    public static boolean hasLeases() {
        if(!Files.isDirectory(dir())) return false;
        try(var files=Files.list(dir())) { return files.findAny().isPresent(); }
        catch(IOException unavailable) { return true; }
    }
    public static Path prepare(PlaybackTransport.Selection item, BooleanSupplier valid) throws Exception {
        if(com.example.netease.core.Levels.canonical(item.level())==null) throw new IOException("无效音质档位");
        synchronized(LOCK) { preparing=true; }
        try {
            if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if(NativeStreamServer.start()<=0) throw new IOException("本地流服务不可用");
            StreamingAudioCache.select(item.songId(),true);
            Path complete=StreamingAudioCache.complete(item.songId(),item.level());
            if(complete==null) StreamPrefetcher.onPlaybackStart(item.songId(),item.level());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            while(complete==null) {
                if(!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                String failure=StreamPrefetcher.failureOf(item.songId(),item.level());
                if(failure!=null) throw new IOException(failure);
                if(System.nanoTime()-deadline>=0) throw new java.util.concurrent.TimeoutException("完整音频缓存超时");
                Thread.sleep(100);
                complete=StreamingAudioCache.complete(item.songId(),item.level());
            }
            return lease(complete,item.songId()+"-"+item.epoch()+"-"+item.level(),valid);
        } finally { synchronized(LOCK) { preparing=false; } }
    }
    static Path lease(Path complete, String key, BooleanSupplier valid) throws Exception {
        if(!complete.getFileName().toString().endsWith(".cache")) throw new IOException("只允许使用完整缓存");
        if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        String kind;
        try(var in=Files.newInputStream(complete)) { kind=codec(in.readNBytes(16)); }
        if(kind==null) throw new IOException("完整缓存不是可识别的音频文件");
        Files.createDirectories(dir());
        Path target=dir().resolve(key+"."+kind), temp=dir().resolve(key+".part");
        Files.deleteIfExists(temp);
        try {
            // Keep the player's file independent of the mutable cache. In particular, replacing
            // a same-song lease on Wine must never truncate the original through a hard link.
            long expected=Files.size(complete),copied=0;
            try(var in=Files.newInputStream(complete); var out=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[65536]; int n;
                while((n=in.read(buffer))!=-1) {
                    if(!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    out.write(buffer,0,n);copied+=n;
                }
            }
            if(copied==0 || copied!=expected || Files.size(complete)!=expected) throw new IOException("完整音频缓存已变更，请重试");
            if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unavailable) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
            pendingLeases.add(CoverArt.fileUri(target));
            return target;
        } finally { Files.deleteIfExists(temp); }
    }
    static String codec(byte[] b) {
        if(b.length<4) return null;
        if(b[0]=='f'&&b[1]=='L'&&b[2]=='a'&&b[3]=='C') return "flac";
        if(b[0]=='I'&&b[1]=='D'&&b[2]=='3') return "mp3";
        if((b[0]&255)==255 && (b[1]&224)==224) return "mp3";
        if(b[0]=='O'&&b[1]=='g'&&b[2]=='g'&&b[3]=='S') return "ogg";
        if(b.length>=12 && b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p') return "m4a";
        return null;
    }
    /** Runs on the maintenance thread, never the host callback/worker. Keep only the active lease. */
    public static boolean owns(String location) {
        if(location==null) return false;
        for(String prefix:java.util.Set.of(CoverArt.fileUri(dir())+"/",dir().toAbsolutePath().normalize().toString()+java.io.File.separator)) {
            if(location.startsWith(prefix)
                && location.substring(prefix.length()).matches("[0-9]+-[0-9]+-[a-zA-Z0-9_-]+\\.(mp3|flac|ogg|m4a)")) return true;
        }
        return false;
    }
    public static String levelOf(String location) {
        String file=location.substring(Math.max(location.lastIndexOf('/'),location.lastIndexOf('\\'))+1);
        int second=file.indexOf('-',file.indexOf('-')+1),dot=file.lastIndexOf('.');
        String level=second>=0&&dot>second ? com.example.netease.core.Levels.canonical(file.substring(second+1,dot)) : null;
        return level==null ? com.example.netease.cfg.PluginConfig.audioLevel() : level;
    }
    public static void cleanExcept(java.util.Set<String> referenced) { cleanExcept(referenced,() -> true); }
    public static void cleanExcept(java.util.Set<String> referenced,BooleanSupplier valid) {
        synchronized(LOCK) {
        if(!valid.getAsBoolean() || referenced==null || preparing || !Files.isDirectory(dir())) return;
        // Keep a single URI key even when the native player holds an absolute Linux path.
        java.util.Set<String> keys=new java.util.HashSet<>();
        for(String location:referenced) if(owns(location)) {
            String name=location.substring(Math.max(location.lastIndexOf('/'),location.lastIndexOf('\\'))+1);
            keys.add(CoverArt.fileUri(dir().resolve(name)));
        }
        pendingLeases.removeAll(keys);
        try(var files=Files.list(dir())) {
            for(Path p:files.toList()) if(valid.getAsBoolean() && !keys.contains(CoverArt.fileUri(p)) && !pendingLeases.contains(CoverArt.fileUri(p)))
                try { Files.deleteIfExists(p); } catch(IOException busy) { /* native player may still hold the previous file */ }
        } catch(IOException failure) { PluginLog.d("local-playback","清理临时音频失败："+failure.getClass().getSimpleName()); }
        }
    }
}
