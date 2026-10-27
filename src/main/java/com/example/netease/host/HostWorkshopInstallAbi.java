package com.example.netease.host;

import com.example.netease.core.PluginLog;
import com.example.netease.svc.PluginUpdateInstaller;
import com.example.netease.svc.PluginUpdater;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.WorkshopPluginManager;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.io.File;
import java.awt.EventQueue;
import java.util.Arrays;

/** Native import binding: fail closed unless the exact facade signatures match. */
public final class HostWorkshopInstallAbi {
    private HostWorkshopInstallAbi() { }
    public static void check(Class<?> facade)throws Exception { bind(facade); }
    private static Method[] bind(Class<?> facade)throws Exception {
        if(!WorkshopApi.class.isAssignableFrom(facade)||!facade.getName().startsWith("com.xuncorp.voxzen.workshop."))
            throw new IllegalStateException("当前宿主不支持自动安装");
        Method update=facade.getMethod("\u037f",String.class,File.class);
        if(update.getReturnType()!=void.class||Modifier.isStatic(update.getModifiers()))throw new NoSuchMethodException("Native update signature mismatch");
        var managers=Arrays.stream(facade.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())&&Modifier.isStatic(m.getModifiers())
            &&m.getParameterCount()==0&&WorkshopPluginManager.class.isAssignableFrom(m.getReturnType())).toList();
        if(managers.size()!=1)throw new NoSuchMethodException("Native manager signature mismatch");
        return new Method[]{update,managers.getFirst()};
    }
    public static PluginUpdateInstaller.Host capture(Runnable navigation)throws Exception {
        Object facade=WorkshopApi.Companion.getInstance();Method[] methods=bind(facade.getClass());
        Object manager=methods[1].invoke(null);
        Method plugin=manager.getClass().getMethod("getPlugin",String.class);
        Object current=plugin.invoke(manager,PluginUpdater.ID);
        if(current==null)throw new IllegalStateException("当前插件未由宿主加载");
        Method descriptor=current.getClass().getMethod("getDescriptor");
        Method version=descriptor.getReturnType().getMethod("getVersion");
        Method state=current.getClass().getMethod("getPluginState");
        Method id=current.getClass().getMethod("getPluginId");
        var ui=WorkshopApi.ui();
        return new PluginUpdateInstaller.Host() {
            public void install(Path file)throws Exception {
                synchronized(facade) {
                    Object wrapper=plugin.invoke(manager,PluginUpdater.ID);
                    if(wrapper==null)throw new IllegalStateException("当前插件已卸载，未执行安装");
                    String target;
                    try(var zip=new java.util.zip.ZipFile(file.toFile());var input=zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
                        target=new java.util.jar.Manifest(input).getMainAttributes().getValue("Plugin-Version");
                    }
                    String existing=(String)version.invoke(descriptor.invoke(wrapper));
                    if(PluginUpdater.compareVersions(target,existing)<=0)throw new IllegalStateException("宿主已安装相同或更新版本，请在模组管理页查看");
                    methods[0].invoke(facade,PluginUpdater.ID,file.toFile());
                }
            }
            public PluginUpdateInstaller.Installed installed()throws Exception {
                Object wrapper=plugin.invoke(manager,PluginUpdater.ID);if(wrapper==null)return null;
                String lifecycle=state.invoke(wrapper).toString();
                if(!java.util.Set.of("RESOLVED","DISABLED","STOPPED").contains(lifecycle))
                    throw new IllegalStateException("宿主尚未完成安装："+lifecycle);
                return new PluginUpdateInstaller.Installed((String)id.invoke(wrapper),(String)version.invoke(descriptor.invoke(wrapper)),false);
            }
            public void notice(String message,boolean success) {
                // An explicit install result must remain visible after self-unload.
                ui.toast(message,success?WorkshopApi.Ui.ToastType.Success:WorkshopApi.Ui.ToastType.Error);
            }
            public void navigate() {EventQueue.invokeLater(()->{
                try{navigation.run();}catch(Throwable error){PluginLog.w("updates","打开模组管理页失败："+error.getClass().getSimpleName());}
            });}
        };
    }
}
