package com.example.netease.host;

import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Locate exact source/trace markers in the active host; member signatures remain mandatory. */
public final class HostSourceAbi {
    private HostSourceAbi() {}
    /** Include source-generated lambdas: private composables are often called from nested classes. */
    public static List<String> callers(java.lang.reflect.Method method,Class<?> sourceOwner) throws Exception {
        String owner=sourceOwner.getName().replace('.','/');
        var connection=(JarURLConnection)sourceOwner.getResource("/"+owner+".class").openConnection();
        var result=new ArrayList<String>();
        try(var jar=new java.util.jar.JarFile(connection.getJarFileURL().toURI().getPath())) {
            for(var entry:jar.stream().filter(e->e.getName().endsWith(".class")&&(e.getName().equals(owner+".class")||e.getName().startsWith(owner+"$"))).toList())
                try(var in=jar.getInputStream(entry)){if(HostDailyPlaylistAbi.references(in.readAllBytes(),method))result.add(entry.getName().replace('/','.').replace(".class",""));}
        }
        return List.copyOf(result);
    }
    public static Class<?> find(String source,String marker) throws Exception {
        ClassLoader loader=HostSourceAbi.class.getClassLoader();
        var resource=loader.getResource("com/xuncorp/voxzen/ui/navigation/ScreenRoute.class");
        if(resource==null || !(resource.openConnection() instanceof JarURLConnection connection))throw new IllegalStateException("宿主源码标记不可用");
        var matches=new ArrayList<String>();
        try(var jar=new java.util.jar.JarFile(connection.getJarFileURL().toURI().getPath())) {
            for(var entry:jar.stream().filter(e->e.getName().startsWith("androidx/compose/ui/")&&e.getName().endsWith(".class")&&!e.getName().contains("$")).toList()) {
                try(var in=jar.getInputStream(entry)) {
                    String body=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
                    if(body.contains(source)&&body.contains(marker))matches.add(entry.getName().replace('/','.').replace(".class",""));
                }
            }
        }
        if(matches.size()!=1)throw new IllegalStateException("宿主组件签名不唯一："+source+" / "+marker+" / "+matches.size());
        return Class.forName(matches.getFirst(),false,loader);
    }
}
