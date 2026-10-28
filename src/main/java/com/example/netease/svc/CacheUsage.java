package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import java.nio.file.*;
import java.util.Locale;

public final class CacheUsage {
    public record Snapshot(long cover,long lyric,long music) { public long total(){return cover+lyric+music;} }
    private CacheUsage() {}
    public static Snapshot scan() {
        Path root=DataPaths.data();
        return new Snapshot(bytes(root.resolve("cover")),bytes(root.resolve("lyric")),
                bytes(root.resolve("audio"))+bytes(root.resolve("audio-stream")));
    }
    public static long bytes(Path root) {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return 0;
        try(var files=Files.walk(root)) {
            return files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).mapToLong(p->{try{return Files.size(p);}catch(Exception ignored){return 0;}}).sum();
        }catch(Exception ignored){return 0;}
    }
    public static String format(long bytes) {
        if(bytes>=1024L*1024*1024)return String.format(Locale.ROOT,"%.2f GB",bytes/(1024d*1024*1024));
        if(bytes>=1024L*1024)return String.format(Locale.ROOT,"%.1f MB",bytes/(1024d*1024));
        if(bytes>=1024)return String.format(Locale.ROOT,"%.1f KB",bytes/1024d);
        return bytes+" B";
    }
}
