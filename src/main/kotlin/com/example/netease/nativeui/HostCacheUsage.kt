@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.runtime.*
import com.example.netease.host.HostSourceAbi
import com.example.netease.svc.CacheUsage
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import kotlinx.coroutines.*
import java.lang.reflect.Modifier

/** Copy presentation models only; ids, config paths, list values and callbacks remain native. */
internal object HostCacheUsage {
    private val handles=mutableListOf<HookHandle>()
    private lateinit var method: java.lang.reflect.Method
    private var scope:CoroutineScope?=null
    var usage by mutableStateOf(CacheUsage.Snapshot(0,0,0))
    fun install() {
        if(handles.isNotEmpty())return
        val owner=HostSourceAbi.find("ModConfigScreen.kt","ConfigGroupRenderer (")
        method=owner.declaredMethods.single { it.returnType==Void.TYPE && it.parameterCount==4 &&
            Modifier.isPublic(it.modifiers) && it.parameterTypes[1]==String::class.java && it.parameterTypes[2]==Composer::class.java }.apply { isAccessible=true }
        try {
            val callers=HostSourceAbi.callers(method,owner)
            check(callers.isNotEmpty()) {"找不到宿主缓存分组调用点"}
            callers.forEach{caller->handles+=WorkshopApi.hookRegistrar.replaceComposable(owner.name,method.name,caller,method.parameterTypes.take(2).map{it.name},100,Hook)}
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        }
        catch(error:Throwable){uninstall();throw error}
    }
    fun uninstall(){scope?.cancel();scope=null;handles.asReversed().forEach{runCatching{it.unhook()}};handles.clear()}
    internal fun decorate(group: Any,snapshot:CacheUsage.Snapshot):Any {
        fun values(value:Any)=value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map {it.isAccessible=true;it.get(value)}.toMutableList()
        fun copy(value:Any,args:List<Any?>):Any {
            val constructor=value.javaClass.declaredConstructors.single { c->c.parameterCount==args.size && c.parameterTypes.lastOrNull()?.name!="kotlin.jvm.internal.DefaultConstructorMarker" }
            constructor.isAccessible=true;return constructor.newInstance(*args.toTypedArray())
        }
        val groupArgs=values(group)
        if(groupArgs.size!=4 || (groupArgs[1]!="状态与缓存" && !(groupArgs[1] as? String).orEmpty().startsWith("状态与缓存  ")) || groupArgs[3] !is List<*>)return group
        val items=(groupArgs[3] as List<*>).map { item ->
            if(item==null)return@map item
            val args=values(item)
            if(args.size==5 && args.last() is String) {
                val size=when(args.last()) {
                    "com.example.netease.NeteasePlugin.clearCoverCache"->snapshot.cover()
                    "com.example.netease.NeteasePlugin.clearLyricCache"->snapshot.lyric()
                    "com.example.netease.NeteasePlugin.clearPlaybackCache"->snapshot.music()
                    else->return@map item
                }
                args[1]=args[1].toString().substringBefore("  ")+"  "+CacheUsage.format(size);copy(item,args)
            } else item
        }
        groupArgs[1]="状态与缓存  "+CacheUsage.format(snapshot.total());groupArgs[3]=items
        return copy(group,groupArgs)
    }
    private object Hook:ComposableHook(){
        @Composable override fun Content(call:UiHookCall){
            val group=if(call.arguments[1]=="com.example.netease")decorate(call.arguments[0]!!,usage)else call.arguments[0]
            if(group!==call.arguments[0])DisposableEffect(Unit){
                val job=scope?.launch{while(isActive){usage=withContext(Dispatchers.IO){CacheUsage.scan()};delay(2000)}}
                onDispose{job?.cancel()}
            }
            method.invoke(null,group,call.arguments[1],currentComposer,0)
        }
    }
}
