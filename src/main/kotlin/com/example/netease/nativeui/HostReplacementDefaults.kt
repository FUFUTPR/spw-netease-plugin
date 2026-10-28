package com.example.netease.nativeui

import java.lang.reflect.Method
import kotlin.ranges.IntRange

/** The host replacement guard rejects nonzero default masks. Our configuration
 * and playlist dispatchers explicitly restore the ViewModel default, allowing
 * their individual sites to accept mask=2. Rebuild only the site's transformer before finishStart
 * publishes it. No host class, global transformer or other site is modified.
 */
internal object HostReplacementDefaults {
    private const val SITE = "com.xuncorp.voxzen.workshop.hooks.\u0529\$\u052d"
    private const val TRANSFORMER = "com.xuncorp.voxzen.workshop.hooks.\u052d"

    fun prepare(registrar: Any, original: Method, caller: String): AutoCloseable {
        val siteType = Class.forName(SITE)
        val sites = registrar.javaClass.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }
            .flatMap { field ->
                field.isAccessible = true
                (field.get(registrar) as? Map<*, *>)?.values?.filterNotNull().orEmpty()
            }.filter { siteType.isInstance(it) }.distinct()
        val site = sites.single { candidate ->
            val target = candidate.javaClass.methods.single { it.returnType == Method::class.java && it.parameterCount == 0 }
                .invoke(candidate) as Method
            val info = candidate.javaClass.declaredMethods.single {
                it.parameterCount == 0 && it.returnType.name == "com.xuncorp.voxzen.workshop.hooks.\u052a\$\u0528"
            }.invoke(candidate)
            val inClass = info.javaClass.declaredMethods.single { it.returnType == Class::class.java && it.parameterCount == 0 }
                .invoke(info) as Class<*>
            target == original && inClass.name == caller
        }
        return prepareSite(site)
    }

    internal fun prepareSite(site: Any): AutoCloseable {
        check(site.javaClass.name == SITE) { "Unsupported UI hook site" }
        val methods = site.javaClass.declaredMethods
        val read = methods.single { it.returnType == IntRange::class.java && it.parameterCount == 0 }
        val target=site.javaClass.methods.single { it.parameterCount==0 && it.returnType==Method::class.java }.invoke(site) as Method
        val expected=target.parameterCount-1
        check(read.invoke(site) == (expected..expected)) { "Unexpected Compose default-mask layout" }
        val write = methods.single { it.parameterTypes.toList() == listOf(IntRange::class.java) }
        val transformerType = Class.forName(TRANSFORMER)
        val setter = methods.single {
            it.parameterCount == 1 && it.parameterTypes[0].name == "com.xuncorp.voxzen.workshop.hooks.\u037f"
        }
        val prior = read.invoke(site)
        val getTransformer = methods.single {
            it.parameterCount == 0 && it.returnType.name == "com.xuncorp.voxzen.workshop.hooks.\u037f"
        }
        val priorTransformer = getTransformer.invoke(site)
        try {
            write.invoke(site, IntRange.EMPTY)
            val transformer = transformerType.getConstructor(site.javaClass).newInstance(site)
            setter.invoke(site, transformer)
            return AutoCloseable {
                write.invoke(site, prior)
                setter.invoke(site, priorTransformer)
            }
        } catch (failure: Throwable) {
            write.invoke(site, prior)
            setter.invoke(site, priorTransformer)
            throw failure
        }
    }
}
