package com.example.netease.host;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Uses the host's queued UpdateMediaItems command: no queue replacement or database writes. */
public final class LocalPlaybackBridge {
    private LocalPlaybackBridge() { }
    public static String[] fields(Object item) throws Exception {
        var values=new String[6];
        String[] getters={"\u037f","\u0528","\u0529","\u052a","\u052b","\u052c"};
        for(int i=0;i<values.length;i++) values[i]=(String)item.getClass().getMethod(getters[i]).invoke(item);
        return values;
    }
    public static boolean replay(Object controller, Object expected, Object current, String local,
                                 BooleanSupplier valid) throws Exception {
        if(expected==null || current!=expected || !valid.getAsBoolean() || !localLocation(local)) return false;
        String[] values=fields(expected);
        if(!values[0].matches("netease-[0-9]+") || !values[5].startsWith("http://127.0.0.1:")) return false;
        Class<?> itemType=expected.getClass();
        Object replacement=withLocation(expected,local);
        Object array=Array.newInstance(itemType,1); Array.set(array,0,replacement);
        // Resolve the entire ABI before emitting any command.
        var update=controller.getClass().getMethod("updateMediaItems",array.getClass());
        var pause=controller.getClass().getMethod("pause");
        var prepare=controller.getClass().getMethod("prepare");
        var play=controller.getClass().getMethod("play");
        if(!valid.getAsBoolean()) return false;
        // Stop the old voice before preparing another source for the same song. Keep the
        // native output device alive: releaseAudioEngine() can leave Proton's next load silent.
        pause.invoke(controller);
        update.invoke(controller,array);
        prepare.invoke(controller);
        play.invoke(controller);
        return true;
    }
    private static boolean localLocation(String location) {
        if(location==null) return false;
        if(location.startsWith("file:/")) return true;
        try { return java.nio.file.Path.of(location).isAbsolute(); }
        catch(java.nio.file.InvalidPathException invalid) { return false; }
    }
    public static Object withLocation(Object item,String path) throws Exception {
        String[] values=fields(item); values[5]=path;
        return item.getClass().getConstructor(String.class,String.class,String.class,String.class,String.class,String.class)
            .newInstance((Object[])values);
    }
    public static Set<String> restore(Object controller,Object current,List<Object> queued,boolean all) throws Exception {
        String active=current==null ? "" : fields(current)[0];
        var keep=new LinkedHashSet<String>();
        if(current!=null && com.example.netease.svc.LocalAudioFiles.owns(fields(current)[5])) keep.add(fields(current)[5]);
        var replacements=new LinkedHashMap<String,Object>();
        for(Object media:queued) {
            String[] data=fields(media);
            if(!com.example.netease.svc.LocalAudioFiles.owns(data[5])) continue;
            if(!data[0].matches("netease-[0-9]+")) throw new IllegalStateException("无法确认临时音频的歌曲身份");
            keep.add(data[5]);
            if(all || !data[0].equals(active)) {
                long id=Long.parseLong(data[0].substring(8));
                replacements.putIfAbsent(data[0],withLocation(media,com.example.netease.svc.NativeStreamServer.urlFor(id,
                    com.example.netease.svc.LocalAudioFiles.levelOf(data[5]))));
            }
        }
        if(!replacements.isEmpty()) {
            Class<?> itemType=replacements.values().iterator().next().getClass();
            Object array=Array.newInstance(itemType,replacements.size());int index=0;
            for(Object item:replacements.values()) Array.set(array,index++,item);
            controller.getClass().getMethod("updateMediaItems",array.getClass()).invoke(controller,array);
        }
        return Set.copyOf(keep);
    }
}
