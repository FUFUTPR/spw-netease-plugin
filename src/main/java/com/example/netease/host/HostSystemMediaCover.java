package com.example.netease.host;

import com.example.netease.core.PluginLog;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Timers;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Windows JMTC artwork only. Uses the host's existing controller and serial executor. */
public final class HostSystemMediaCover {
    public record Track(String id,String title,String artists,String album) { }
    private record Artwork(Track track,File file) { }
    private static volatile boolean active;
    private static volatile Artwork artwork;
    private static Supplier<Track> current = HostSystemMediaCover::readCurrent;
    private static Class<?> controllerType,propertiesType;
    private static Field service;
    private static Method player,item,id,title,artists,album,submit,getProperties,update,thumbnail,loaded;
    private static Constructor<?> wideString;
    private static Field adapter;
    private static final AtomicLong sequence=new AtomicLong();
    private static Field[] values;
    private static Object manager;
    private HostSystemMediaCover() { }

    public static boolean supported() {return System.getProperty("os.name","").startsWith("Windows");}
    /** Resolve without initializing the native SMTC adapter or creating another media session. */
    public static synchronized Class<?> validate()throws ReflectiveOperationException {
        ClassLoader loader=HostSystemMediaCover.class.getClassLoader();
        controllerType=Class.forName("io.github.selemba1000.windows.\u037f",false,loader);
        var setter=Arrays.stream(controllerType.getDeclaredMethods()).filter(m->m.getName().equals("setMediaProperties")&&m.getParameterCount()==1).toList();
        if(setter.size()!=1)throw new NoSuchMethodException("Windows media properties setter");
        Method setProperties=setter.getFirst();
        getProperties=controllerType.getMethod("getMediaProperties");update=controllerType.getMethod("updateDisplay");
        propertiesType=Class.forName("androidx.compose.ui.\u0462",false,loader);
        if(!setProperties.getParameterTypes()[0].isAssignableFrom(propertiesType))throw new NoSuchMethodException("Music media properties type");
        String[] names={"\u037f","\u0528","\u0529"};
        Class<?>[] types={String.class,String[].class,String.class};
        values=new Field[names.length];
        for(int n=0;n<names.length;n++){values[n]=propertiesType.getField(names[n]);if(values[n].getType()!=types[n])throw new NoSuchFieldException("Music media properties layout");}
        Class<?> nativeAdapter=Class.forName("io.github.selemba1000.windows.SMTCAdapter",false,loader);
        var adapters=Arrays.stream(controllerType.getDeclaredFields()).filter(f->f.getType()==nativeAdapter).toList();
        if(adapters.size()!=1)throw new NoSuchFieldException("Existing Windows media adapter");
        adapter=adapters.getFirst();adapter.setAccessible(true);
        Class<?> wide=Class.forName("com.sun.jna.WString",false,loader);wideString=wide.getConstructor(String.class);
        thumbnail=nativeAdapter.getMethod("setThumbnail",wide);loaded=nativeAdapter.getMethod("thumbnailLoaded");
        Class<?> playback=Class.forName("com.xuncorp.voxzen.service.PlaybackController",false,loader);
        service=playback.getDeclaredField("service");service.setAccessible(true);
        player=service.getType().getMethod("getPlayer");
        Class<?> media=Class.forName("com.xuncorp.pisces.PiscesMediaItem",false,loader);
        var items=Arrays.stream(player.getReturnType().getMethods()).filter(m->!Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==0&&m.getReturnType()==media).toList();
        if(items.size()!=1)throw new NoSuchMethodException("Current native media item");item=items.getFirst();
        id=media.getMethod("\u037f");title=media.getMethod("\u0528");artists=media.getMethod("\u0529");album=media.getMethod("\u052a");
        Class<?> jmtc=Class.forName("com.xuncorp.voxzen.service.JmtcManager",false,loader);
        submit=jmtc.getDeclaredMethod("submitTask",Function1.class);submit.setAccessible(true);
        // Binding and manager initialization happen on HostBridgeWorker, never in the hook.
        manager=jmtc.getField("INSTANCE").get(null);
        return controllerType;
    }
    public static Class<?> propertiesParameter() {return Arrays.stream(controllerType.getDeclaredMethods()).filter(m->m.getName().equals("setMediaProperties")&&m.getParameterCount()==1).findFirst().orElseThrow().getParameterTypes()[0];}
    public static void setActive(boolean value) {active=value;if(!value){artwork=null;sequence.incrementAndGet();}}

    /** Already-cached image only; called on the existing plugin host worker after artwork delivery. */
    public static void offer(long song,File file) {
        if(!active||file==null)return;
        try {
            Track track=current.get();if(track==null||!track.id().equals("netease-"+song))return;
            Artwork next=new Artwork(track,file.getAbsoluteFile());artwork=next;
            // A host refresh may already have sent its default image. Repair only its artwork,
            // on its own JMTC executor, and recheck both track ID and displayed metadata there.
            submit.invoke(manager,(Function1<Object,Unit>)controller->{
                try {
                    if(!active||artwork!=next||!controllerType.isInstance(controller))return Unit.INSTANCE;
                    onMetadata(controller,getProperties.invoke(controller));
                }catch(Throwable error){PluginLog.d("system-cover","系统封面交付失败："+error.getClass().getSimpleName());}
                return Unit.INSTANCE;
            });
        }catch(Throwable error){PluginLog.d("system-cover","系统封面排队失败："+error.getClass().getSimpleName());}
    }

    /** Hook does no file/network work and never waits on the plugin worker or the UI thread. */
    public static void onMetadata(Object controller,Object original)throws ReflectiveOperationException {
        Artwork saved=artwork;
        if(!active||saved==null||original==null||original.getClass()!=propertiesType||!controllerType.isInstance(controller))return;
        Track track=current.get();
        if(track==null||!saved.track().equals(track)||!matches(original,track))return;
        long token=sequence.incrementAndGet();
        Object nativeAdapter=adapter.get(controller);
        thumbnail.invoke(nativeAdapter,wideString.newInstance(saved.file().toURI().toString()));
        checkLoaded(controller,nativeAdapter,saved,token,0);
    }
    private static boolean matches(Object properties,Track track)throws ReflectiveOperationException {
        return Objects.equals(values[0].get(properties),track.title())&&Objects.equals(values[2].get(properties),track.album())
            &&String.join("/",(String[])values[1].get(properties)).equals(track.artists());
    }
    private static void checkLoaded(Object controller,Object nativeAdapter,Artwork saved,long token,int attempt)throws ReflectiveOperationException {
        if(!active||artwork!=saved||sequence.get()!=token||!saved.track().equals(current.get()))return;
        if(Boolean.TRUE.equals(loaded.invoke(nativeAdapter))){update.invoke(controller);return;}
        if(attempt>=10){PluginLog.w("system-cover","Windows 系统封面加载超时，已停止等待，不阻塞媒体控制");return;}
        // The native setMediaProperties implementation spins until thumbnailLoaded().
        // Never pass our file through that unbounded loop: check completion in bounded,
        // cancellable tasks on the host's own serial executor instead.
        Timers.later(100,()->{
            if(!active||artwork!=saved||sequence.get()!=token)return;
            HostBridgeWorker.get().submit(()->{
                if(!active||artwork!=saved||sequence.get()!=token)return;
                try{submit.invoke(manager,(Function1<Object,Unit>)live->{
                    try{if(live==controller)checkLoaded(controller,nativeAdapter,saved,token,attempt+1);}
                    catch(Throwable error){PluginLog.d("system-cover","系统封面完成检查失败："+error.getClass().getSimpleName());}
                    return Unit.INSTANCE;
                });}catch(ReflectiveOperationException unavailable){PluginLog.d("system-cover","系统封面完成检查不可用");}
            });
        });
    }
    private static Track readCurrent() {
        try {
            // The host JMTC executor also performs this read: only existing player state is read;
            // all class/ABI resolution and initialization were completed on HostBridgeWorker.
            Object live=service.get(null);if(live==null)return null;
            Object p=player.invoke(live);if(p==null)return null;
            Object media=item.invoke(p);if(media==null)return null;
            return new Track((String)id.invoke(media),(String)title.invoke(media),(String)artists.invoke(media),(String)album.invoke(media));
        }catch(ReflectiveOperationException unavailable){return null;}
    }
}
