import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import androidx.compose.runtime.Composer;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.jar.asm.Type;

/** Actual installed caller bytes and transformer; only dispatch is a fixture. */
public class GlobalSearchHookProbe {
 static int checks;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static Object call(Object obj,String base)throws Exception {
  Method m=Arrays.stream(obj.getClass().getDeclaredMethods()).filter(x->(x.getName().equals(base)||x.getName().startsWith(base+"$"))&&x.getParameterCount()==0).findFirst().orElseThrow();
  m.setAccessible(true);return m.invoke(obj);
 }
 static byte[] bytes(String name)throws Exception {try(var in=Class.forName(name).getResourceAsStream("/"+name.replace('.','/')+".class")){return in.readAllBytes();}}
 static int count(byte[] b,String owner,String name,String desc){
  int[] n={0};new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9){public MethodVisitor visitMethod(int a,String nm,String d,String sig,String[] ex){return new MethodVisitor(Opcodes.ASM9){public void visitMethodInsn(int op,String o,String nm,String d,boolean itf){if(o.equals(owner)&&nm.equals(name)&&(desc==null||d.equals(desc)))n[0]++;}};}},0);return n[0];
 }
 public static void main(String[] args)throws Exception {
  Class<?> global=Class.forName("com.example.netease.nativeui.HostGlobalSearch");Object instance=global.getField("INSTANCE").get(null);
  for(String kind:List.of("root","title","popup")) {
   Object receiver=kind.equals("popup") ? Class.forName("com.example.netease.nativeui.HostCloudSongMenu").getField("INSTANCE").get(null) : instance;
   Method original=(Method)call(receiver,kind+"Method");String caller=(String)call(receiver,kind+"Caller");
   String owner=original.getDeclaringClass().getName().replace('.','/'),desc=Type.getMethodDescriptor(original);
   byte[] real=bytes(caller);check(count(real,owner,original.getName(),desc)==1,kind+" has one exact installed caller");
   Class<?> info=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a$\u0528");
   List<String> names=kind.equals("root")?List.of("enableStack"):kind.equals("title")?List.of("type","captionLayout","playerState","modifier"):List.of("positionProvider","onDismissRequest","menu");
   Object target=info.getConstructors()[0].newInstance(Class.forName(caller),original,names);
   Class<?> site=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u0529$\u052d");Object slot=site.getConstructors()[0].newInstance(4231,target);
   Arrays.stream(site.getDeclaredMethods()).filter(m->Arrays.equals(m.getParameterTypes(),new Class[]{kotlin.ranges.IntRange.class})).findFirst().orElseThrow()
       .invoke(slot,new kotlin.ranges.IntRange(original.getParameterCount()-1,original.getParameterCount()-1));
   Class<?> transformer=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052d");Object t=transformer.getConstructor(site).newInstance(slot);
   Method rewrite=transformer.getMethod("\u037f",String.class,byte[].class);
   byte[] rewritten=(byte[])rewrite.invoke(t,caller,real);
   if(count(rewritten,"com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge","RenderReplacement",null)!=1) {
    System.out.println("DEBUG byte lengths "+real.length+" -> "+(rewritten==null?-1:rewritten.length));
    if(rewritten!=null)new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9){public MethodVisitor visitMethod(int a,String nm,String d,String sig,String[] ex){return new MethodVisitor(Opcodes.ASM9){public void visitMethodInsn(int op,String o,String n,String d,boolean itf){if(o.contains("WorkshopHook"))System.out.println("DEBUG "+o+"#"+n+d);}};}},0);
   }
   check(count(rewritten,"com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge","RenderReplacement",null)==1,kind+" actual caller gets one dispatcher");
   var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(69,Opcodes.ACC_PUBLIC,caller.replace('.','/'),null,"java/lang/Object",null);
   var run=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run",desc,null,null);run.visitCode();
   int local=0;for(Type arg:Type.getArgumentTypes(desc)){run.visitVarInsn(arg.getOpcode(Opcodes.ILOAD),local);local+=arg.getSize();}
   run.visitMethodInsn(Opcodes.INVOKESTATIC,owner,original.getName(),desc,false);run.visitInsn(Opcodes.RETURN);run.visitMaxs(0,0);run.visitEnd();writer.visitEnd();
   byte[] fixture=(byte[])rewrite.invoke(t,caller,writer.toByteArray());String bridge="com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge";
   var definitions=Map.of(caller,fixture,bridge,Files.readAllBytes(Path.of(args[0]).resolve(bridge.replace('.','/')+".class")));
   ClassLoader loader=new ClassLoader(GlobalSearchHookProbe.class.getClassLoader()){
    protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {synchronized(getClassLoadingLock(name)){Class<?> c=findLoadedClass(name);if(c==null&&definitions.containsKey(name)){byte[] b=definitions.get(name);c=defineClass(name,b,0,b.length);}if(c==null)c=super.loadClass(name,false);if(resolve)resolveClass(c);return c;}}
   };
   Class<?> shim=loader.loadClass(bridge);Object[][] received={null};shim.getField("replacement").set(null,(Function3<Object[],Composer,Integer,Unit>)(a,c,n)->{received[0]=a;return Unit.INSTANCE;});
   Object[] values=new Object[original.getParameterCount()];if(kind.equals("root"))values[0]=false;
   for(int i=0;i<values.length;i++)if(original.getParameterTypes()[i]==Composer.class)values[i]=Proxy.newProxyInstance(GlobalSearchHookProbe.class.getClassLoader(),new Class[]{Composer.class},(p,m,a)->{
       if(m.getReturnType()==Composer.class)return p;if(m.getReturnType()==boolean.class)return true;if(m.getReturnType()==int.class)return 0;if(m.getReturnType()==long.class)return 0L;return null;});
   for(int i=0;i<values.length;i++)if(original.getParameterTypes()[i]==int.class)values[i]=0;
   loader.loadClass(caller).getMethod("run",original.getParameterTypes()).invoke(null,values);
   check(shim.getField("replacements").getInt(null)==1,kind+" transformed call executes dispatcher");
   check(received[0]!=null&&received[0].length==names.size(),kind+" preserves all business arguments");
  }
  System.out.println("GLOBAL_SEARCH_HOOK_RESULT="+checks+" PASS");
 }
}
