package com.example.netease.svc;

import com.example.netease.core.*;
import com.example.netease.host.HostWorkshopInstallAbi;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit user action only; hands replacement/rollback to the native host. */
public final class PluginUpdateInstaller {
    public record Installed(String id,String version,boolean started) { }
    public record Outcome(boolean installed,boolean packageDeleted,boolean needsDownload,String message) { }
    public interface Host {
        void install(Path packageFile)throws Exception;
        Installed installed()throws Exception;
        void notice(String message,boolean success);
        void navigate();
    }
    @FunctionalInterface public interface HostFactory { Host capture(Runnable navigation)throws Exception; }
    private static volatile HostFactory factory;
    private static final AtomicBoolean pending=new AtomicBoolean();
    private static boolean running;
    private static long generation;
    private PluginUpdateInstaller() { }

    public static boolean request(PluginUpdater.State state,Runnable navigation,Consumer<Outcome> completion) {
        if(state==null||state.phase()!=PluginUpdater.Phase.COMPLETE||state.file()==null||state.release()==null||navigation==null)return false;
        final long token;
        synchronized(PluginUpdateInstaller.class){if(!pending.compareAndSet(false,true))return false;token=++generation;}
        if(HostBridgeWorker.get().submit(()->{
            synchronized(PluginUpdateInstaller.class){if(token!=generation||!pending.get())return;running=true;}
            run(state,navigation,completion,token);
        }))return true;
        synchronized(PluginUpdateInstaller.class){if(token==generation)pending.set(false);}return false;
    }
    /** Discard a queued request, but let an already handed-off self-update finish. */
    public static synchronized void onPluginStop() {if(!running){generation++;pending.set(false);}}
    private static void finished(long token) {synchronized(PluginUpdateInstaller.class){running=false;if(token==generation)pending.set(false);}}
    private static void run(PluginUpdater.State state,Runnable navigation,Consumer<Outcome> completion,long token) {
        Host host=null;Path staging=null,directory=null;boolean handedOff=false,validated=false,validating=false;
        try {
            HostFactory selected=factory;
            host=selected==null?HostWorkshopInstallAbi.capture(navigation):selected.capture(navigation);
            validating=true;
            Path source=state.file();
            if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("安装包不存在，请重新下载");
            var original=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            Path root=PrivateFiles.directory(DataPaths.data().resolve("updates"));
            directory=Files.createTempDirectory(root,"install-");PrivateFiles.restrict(directory,true);
            Path temporary=PrivateFiles.temporary(directory,"package-");staging=temporary;
            try(var output=Files.newOutputStream(temporary)){Files.copy(source,output);}
            PluginUpdater.verify(temporary,state.release());
            String fingerprint=hash(temporary);
            staging=Files.move(temporary,directory.resolve(state.release().name()));
            validated=true;
            Host bound=host;Path snapshot=staging,ownedDirectory=directory;
            handedOff=HostBridgeWorker.get().handoffNativeInstall(()->{
                try { install(bound,state,source,original,fingerprint,snapshot,completion); }
                catch(Throwable failure){failure(bound,failure,false,completion);}
                finally {cleanup(snapshot,ownedDirectory);finished(token);}
            });
            if(!handedOff)throw new IOException("无法交接原生安装，请稍后重试");
        }catch(Throwable failure) {failure(host,failure,validating&&!validated,completion);}
        finally {if(!handedOff){cleanup(staging,directory);finished(token);}}
    }
    private static void install(Host host,PluginUpdater.State state,Path source,BasicFileAttributes original,
            String fingerprint,Path staging,Consumer<Outcome> completion)throws Exception {
            host.install(staging);
            Installed installed=host.installed();
            if(installed==null||!PluginUpdater.ID.equals(installed.id())||!state.release().version().equals(installed.version())||installed.started())
                throw new IOException("无法确认新版本处于未启动状态，下载文件已保留");
            boolean deleted=false;
            try {
                if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) {
                    var current=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(Objects.equals(original.fileKey(),current.fileKey())&&original.size()==current.size()
                        &&original.lastModifiedTime().equals(current.lastModifiedTime())&&fingerprint.equals(hash(source))) {
                        Files.delete(source);deleted=true;
                    }
                }
            }catch(Exception cleanup){PluginLog.w("updates","安装成功，下载文件暂未清理："+cleanup.getClass().getSimpleName());}
            String message=deleted?"安装完成，请手动启动插件":"安装完成，请手动启动插件；下载文件未清理，可稍后手动处理";
            try{host.notice(message,true);}catch(Throwable feedback){PluginLog.w("updates","安装结果提示暂不可用："+feedback.getClass().getSimpleName());}
            try{host.navigate();}catch(Throwable feedback){PluginLog.w("updates","打开模组管理页失败："+feedback.getClass().getSimpleName());}
            finish(completion,new Outcome(true,deleted,false,message));
    }
    private static void failure(Host host,Throwable failure,boolean needsDownload,Consumer<Outcome> completion) {
            while(failure instanceof java.lang.reflect.InvocationTargetException wrapped&&wrapped.getCause()!=null)failure=wrapped.getCause();
            String detail=failure.getMessage();if(detail==null||detail.isBlank())detail=failure.getClass().getSimpleName();
            if(detail.length()>160)detail=detail.substring(0,160);
            String message=needsDownload?"安装未完成，请重新下载："+detail:"安装失败，下载文件已保留："+detail;
            PluginLog.w("updates",message);
            if(host!=null)try{host.notice(message,false);}catch(Throwable ignored){}
            finish(completion,new Outcome(false,false,needsDownload,message));
    }
    private static void cleanup(Path staging,Path directory) {
            if(staging!=null)try{Files.deleteIfExists(staging);}catch(IOException ignored){}
            if(directory!=null)try{Files.deleteIfExists(directory);}catch(IOException ignored){}
    }
    private static String hash(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void finish(Consumer<Outcome> callback,Outcome result) { if(callback!=null)try{callback.accept(result);}catch(Throwable ignored){} }
}
