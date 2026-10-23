import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import kotlin.ranges.IntRange;
import androidx.compose.runtime.Composer;

/** Executes the actual transformed 1.19.0 navigation lambda, not a direct call
 * to PanelHook. Only the bridge's manager/permissions dispatch is a fixture. */
public final class HostHookEngineProbe {
    static final String NAV="com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt";
    static Class<?> bridge;
    static Method navigation;
    static byte[] bytes(String name)throws Exception {
        try(var in=Class.forName(name).getResourceAsStream("/"+name.replace('.','/')+".class")) {
            return in.readAllBytes();
        }
    }
    public static Method prepare(Path shim, boolean fixed, Function3<Object[],Composer,Integer,Unit> render)throws Exception {
        Class<?> abi=Class.forName("com.example.netease.nativeui.HostUiAbi");
        Class<?> screen=(Class<?>)abi.getMethod("type",String.class).invoke(abi.getField("INSTANCE").get(null),"androidx.compose.ui.\u022b");
        Method original=Arrays.stream(screen.getDeclaredMethods()).filter(m->m.getParameterCount()==5 &&
            m.getParameterTypes()[0].getName().endsWith("$ModConfigContent")).findFirst().orElseThrow();
        Class<?> info=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a$\u0528");
        Object target=info.getConstructors()[0].newInstance(Class.forName(NAV),original,List.of("route","viewModel"));
        Class<?> siteType=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u0529$\u052d");
        Object site=siteType.getConstructors()[0].newInstance(12345,target);
        Method range=Arrays.stream(siteType.getDeclaredMethods()).filter(m->Arrays.equals(m.getParameterTypes(),new Class[]{IntRange.class})).findFirst().orElseThrow();
        range.invoke(site,new IntRange(4,4));
        Class<?> transformerType=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052d");
        Object transformer=transformerType.getConstructor(siteType).newInstance(site);
        Object priorTransformer=transformer;
        Arrays.stream(siteType.getDeclaredMethods()).filter(m->m.getParameterCount()==1 &&
            m.getParameterTypes()[0].getName().equals("com.xuncorp.voxzen.workshop.hooks.\u037f")).findFirst().orElseThrow().invoke(site,transformer);
        AutoCloseable restore=null;
        if(fixed) {
            Class<?> adapter=Class.forName("com.example.netease.nativeui.HostReplacementDefaults");
            restore=(AutoCloseable)adapter.getMethod("prepare",Object.class,Method.class,String.class).invoke(adapter.getField("INSTANCE").get(null),new Registry(site),original,NAV);
            transformer=Arrays.stream(siteType.getDeclaredMethods()).filter(m->m.getParameterCount()==0 &&
                m.getReturnType().getName().equals("com.xuncorp.voxzen.workshop.hooks.\u037f")).findFirst().orElseThrow().invoke(site);
        }
        byte[] rewritten=(byte[])transformerType.getMethod("\u037f",String.class,byte[].class).invoke(transformer,NAV,bytes(NAV));
        if(restore!=null) {
            restore.close();
            Object masks=Arrays.stream(siteType.getDeclaredMethods()).filter(m->m.getReturnType()==IntRange.class && m.getParameterCount()==0).findFirst().orElseThrow().invoke(site);
            if(!masks.equals(new IntRange(4,4)))throw new AssertionError("Uninstall must restore the site's default guard");
            Object restored=Arrays.stream(siteType.getDeclaredMethods()).filter(m->m.getParameterCount()==0 &&
                m.getReturnType().getName().equals("com.xuncorp.voxzen.workshop.hooks.\u037f")).findFirst().orElseThrow().invoke(site);
            if(restored!=priorTransformer)throw new AssertionError("Uninstall must restore the original transformer");
        }
        String bridgeName="com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge";
        Map<String,byte[]> classes=Map.of(NAV,rewritten,bridgeName,Files.readAllBytes(shim.resolve(bridgeName.replace('.','/')+".class")));
        ClassLoader loader=new ClassLoader(HostHookEngineProbe.class.getClassLoader()) {
            protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                synchronized(getClassLoadingLock(name)) {
                    Class<?> c=findLoadedClass(name);
                    if(c==null && classes.containsKey(name)){byte[] b=classes.get(name);c=defineClass(name,b,0,b.length);}
                    if(c==null)c=super.loadClass(name,false);
                    if(resolve)resolveClass(c);return c;
                }
            }
        };
        bridge=loader.loadClass(bridgeName);bridge.getField("replacement").set(null,render);
        navigation=Arrays.stream(loader.loadClass(NAV).getDeclaredMethods()).filter(m->m.getName().equals("lambda_378910767$lambda$0")).findFirst().orElseThrow();
        navigation.setAccessible(true);
        return navigation;
    }
    public static int replacements()throws Exception { return bridge.getField("replacements").getInt(null); }
    public static void main(String[] args)throws Exception {
        Method call=prepare(Path.of(args[0]),false,(a,c,n)->Unit.INSTANCE);
        Composer composer=(Composer)Proxy.newProxyInstance(HostHookEngineProbe.class.getClassLoader(),new Class[]{Composer.class},(p,m,a)->{
            if(m.getReturnType()==Composer.class)return p;
            if(m.getName().equals("shouldExecute")||m.getName().equals("getDefaultsInvalid"))return true;
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==int.class)return 0;
            if(m.getReturnType()==long.class)return 0L;
            return null;
        });
        Object route=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("com.example.netease");
        Throwable error=null;
        try{call.invoke(null,route,composer,0);}catch(InvocationTargetException e){error=e.getCause();}
        if(replacements()!=0)throw new AssertionError("Expected 0.12.2's replacement to be skipped");
        if(error==null || !error.toString().contains("No ViewModelStoreOwner"))throw new AssertionError("Expected original host configuration path, got "+error,error);
        System.out.println("HOST_UI_ENGINE_BASELINE_RESULT=PASS (actual mask=2 skips replacement and reaches original host configuration)");
    }
    static final class Registry {
        final Map<String,Object> sites;
        Registry(Object site){sites=Map.of("only-test-site",site);}
    }
}
