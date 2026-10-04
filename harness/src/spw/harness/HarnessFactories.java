package spw.harness;

import com.xuncorp.spw.workshop.api.PluginContext;

import org.pf4j.ExtensionFactory;
import org.pf4j.Plugin;
import org.pf4j.PluginFactory;
import org.pf4j.PluginWrapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/**
 * PF4J 工厂实现（harness 必须自定义，这是字节码实测结论）。
 *
 * <p><b>接口签名（javap 实测 PF4J 3.12.0）</b>：
 * <pre>
 * public interface PluginFactory    { Plugin create(PluginWrapper); }        // 注意：非泛型，参数只有 wrapper
 * public interface ExtensionFactory { &lt;T&gt; T create(Class&lt;T&gt;); }
 * </pre>
 *
 * <p><b>为什么默认工厂在真插件上必然失败</b>（{@code javap -c org.pf4j.DefaultPluginFactory} 实测）：
 * <pre>
 * create(wrapper):
 *   wrapper.getDescriptor().getPluginClass()                 → 类名字符串
 *   wrapper.getPluginClassLoader().loadClass(类名)            → Class
 *   Plugin.class.isAssignableFrom(clazz) 不成立 → error 日志 + null
 *   createInstance(clazz, wrapper):
 *       clazz.getConstructor(PluginWrapper.class) → newInstance(wrapper)   ← 第 1 条
 *       NoSuchMethodException → createUsingNoParametersConstructor(clazz)  ← 第 2 条
 *   → 两条都不中：返回 null
 * </pre>
 * 而 {@code NeteasePlugin} 继承 {@code SpwPlugin}，唯一构造器是
 * {@code SpwPlugin(PluginContext)}，{@code PluginContext} 只能由宿主给
 * （pluginId / pluginVersion / pluginPath / spwVersion / channel）。
 * → 默认工厂直接返回 null，插件永远起不来。harness 按
 * {@code (PluginContext)} → 无参 → {@code (PluginWrapper)} 顺序找，并记录走了哪条。
 */
public final class HarnessFactories {

    private HarnessFactories() {
    }

    /** 记录「用哪条构造器路径实例化」，供报告使用。 */
    public static volatile String lastPluginFactoryPath = "(未调用)";
    public static volatile String lastExtensionFactoryPath = "(未调用)";

    public static PluginFactory pluginFactory(final Supplier<PluginContext> ctxSupplier) {
        return new PluginFactory() {
            @Override
            public Plugin create(PluginWrapper pluginWrapper) {
                PluginContext ctx = (ctxSupplier == null) ? null : ctxSupplier.get();
                return createPlugin(pluginWrapper, ctx);
            }
        };
    }

    /**
     * 按 wrapper 解析插件类并实例化。类解析方式与 PF4J 默认实现一致
     * （{@code descriptor.getPluginClass()} + {@code wrapper.getPluginClassLoader().loadClass}），
     * 保证「类名写错」这类错误在 harness 里和在宿主里表现一致。
     */
    public static Plugin createPlugin(PluginWrapper wrapper, PluginContext ctx) {
        if (wrapper == null) {
            lastPluginFactoryPath = "wrapper 为 null";
            return null;
        }
        String className = null;
        try {
            className = wrapper.getDescriptor().getPluginClass();
        } catch (Throwable t) {
            lastPluginFactoryPath = "读 descriptor.getPluginClass() 失败: " + t;
            return null;
        }
        if (className == null || className.trim().isEmpty()) {
            lastPluginFactoryPath = "Plugin-Class 为空";
            return null;
        }

        Class<?> pluginClass;
        try {
            pluginClass = wrapper.getPluginClassLoader().loadClass(className);
        } catch (Throwable t) {
            lastPluginFactoryPath = "loadClass(" + className + ") 失败: " + t;
            return null;
        }

        if (pluginClass.isInterface() || Modifier.isAbstract(pluginClass.getModifiers())) {
            lastPluginFactoryPath = "跳过：抽象类/接口 " + pluginClass.getName();
            return null;
        }
        if (!Plugin.class.isAssignableFrom(pluginClass)) {
            lastPluginFactoryPath = "跳过：不是 org.pf4j.Plugin 子类 " + pluginClass.getName();
            return null;
        }

        // 1) (PluginContext) —— SpwPlugin 的唯一构造器（宿主真实路径）
        if (ctx != null) {
            try {
                Constructor<?> c = pluginClass.getConstructor(PluginContext.class);
                c.setAccessible(true);
                Plugin o = (Plugin) c.newInstance(ctx);
                lastPluginFactoryPath = "(" + PluginContext.class.getSimpleName() + ") 构造器";
                return o;
            } catch (NoSuchMethodException notFound) {
                // 继续探测下一条
            } catch (Throwable t) {
                lastPluginFactoryPath = "(PluginContext) 构造器抛异常: " + t;
                throw new IllegalStateException("插件无法用 (PluginContext) 构造器实例化：" + pluginClass.getName(), t);
            }
        }

        // 2) 无参
        try {
            Constructor<?> c = pluginClass.getConstructor();
            c.setAccessible(true);
            Plugin o = (Plugin) c.newInstance();
            lastPluginFactoryPath = "无参构造器";
            return o;
        } catch (NoSuchMethodException notFound) {
            // 继续探测下一条
        } catch (Throwable t) {
            lastPluginFactoryPath = "无参构造器抛异常: " + t;
            throw new IllegalStateException("插件无法用无参构造器实例化：" + pluginClass.getName(), t);
        }

        // 3) (PluginWrapper) —— PF4J 默认路径
        try {
            Constructor<?> c = pluginClass.getConstructor(PluginWrapper.class);
            c.setAccessible(true);
            Plugin o = (Plugin) c.newInstance(wrapper);
            lastPluginFactoryPath = "(PluginWrapper) 构造器";
            return o;
        } catch (Throwable t) {
            lastPluginFactoryPath = "无可用构造器（(PluginContext)/无参/(PluginWrapper) 都不行）: " + t;
            return null;
        }
    }

    public static ExtensionFactory extensionFactory() {
        return new ExtensionFactory() {
            @Override
            public <T> T create(Class<T> extensionClass) {
                return createExtension(extensionClass);
            }
        };
    }

    public static <T> T createExtension(Class<T> extensionClass) {
        try {
            Constructor<T> c = extensionClass.getDeclaredConstructor();
            c.setAccessible(true);
            T o = c.newInstance();
            lastExtensionFactoryPath = "无参构造器";
            return o;
        } catch (Throwable t) {
            lastExtensionFactoryPath = "无参构造失败: " + t;
            throw new IllegalStateException("扩展类无法实例化：" + extensionClass.getName(), t);
        }
    }
}
