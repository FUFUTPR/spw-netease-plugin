@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.example.netease.host.HostSourceAbi
import com.example.netease.svc.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*

internal object HostManagedPlaylists {
    data class Managed(val id:Long,val title:String,val description:String)
    private val handles=mutableListOf<HookHandle>()
    private val restores=mutableListOf<AutoCloseable>()
    private var editing by mutableStateOf<Managed?>(null)
    private var title by mutableStateOf("")
    private var description by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var epoch=0L
    fun dialogOpen()=editing!=null
    fun dismiss(){editing=null;NativeUiPresentation.requestFocusReturn()}
    fun accountChanged(){epoch++;editing=null;busy=false;message=""}
    internal fun managed(value:Any?):Managed?=runCatching {
        if(value?.javaClass?.name!="com.xuncorp.voxzen.data.entity.Playlist")return null
        val id=value.javaClass.getMethod("getId").invoke(value) as String
        if(!id.startsWith(NativeLibrary.PLAYLIST_PREFIX))return null
        val number=id.removePrefix(NativeLibrary.PLAYLIST_PREFIX).toLongOrNull()?:return null
        Managed(number,value.javaClass.getMethod("getTitle").invoke(value) as String,value.javaClass.getMethod("getDescription").invoke(value) as String)
    }.getOrNull()
    internal fun managedInBuilder(builder:Any?):Managed? {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        fun visit(value:Any?,depth:Int):Managed? {
            if(value==null||depth>5||seen.size>=64||!seen.add(value))return null
            if(value.javaClass.name=="com.xuncorp.voxzen.data.entity.Playlist")return managed(value)
            if(value !is kotlin.Function<*> && !value.javaClass.name.startsWith("androidx.compose.ui."))return null
            for(field in value.javaClass.declaredFields.filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}) {
                val child=runCatching{field.isAccessible=true;field.get(value)}.getOrNull()
                visit(child,depth+1)?.let{return it}
            }
            return null
        }
        return visit(builder,0)
    }
    fun install(){
        if(handles.isNotEmpty())return
        val owner=HostSourceAbi.find("Sidebar.kt","PlaylistItemMenuFlyout (")
        val wrapper=HostCloudSongMenu.popupMethod().declaringClass
        val popups=wrapper.declaredMethods.filter {it.returnType==Void.TYPE&&it.parameterCount==6&&it.parameterTypes[2].name=="kotlin.jvm.functions.Function1"&&it.parameterTypes[3]==Composer::class.java}
        try {
            popups.forEach{method->HostSourceAbi.callers(method,owner).forEach{caller->
                method.isAccessible=true
                handles+=WorkshopApi.hookRegistrar.replaceComposable(wrapper.name,method.name,caller,method.parameterTypes.take(3).map{it.name},100,Popup(method))
                restores+=HostReplacementDefaults.prepare(WorkshopApi.hookRegistrar,method,caller)
            }}
            check(handles.isNotEmpty()) {"找不到宿主歌单菜单弹层调用点"}
        }catch(error:Throwable){uninstall();throw error}
    }
    fun uninstall(){restores.asReversed().forEach{runCatching{it.close()}};restores.clear();handles.asReversed().forEach{runCatching{it.unhook()}};handles.clear();accountChanged()}
    internal fun decorate(original:Any?,dismiss:()->Unit):Any?=managedInBuilder(original)?.let{builder(it,dismiss)}?:original
    private class Popup(private val method:java.lang.reflect.Method):ComposableHook(){
        @Composable override fun Content(call:UiHookCall){
            val positioned=method.parameterTypes[0]==PopupPositionProvider::class.java
            @Suppress("UNCHECKED_CAST") val dismiss=call.arguments[if(positioned)1 else 0] as ()->Unit
            val content=decorate(call.arguments[2],dismiss)
            val defaults=if(positioned){if(call.arguments[0]==null)1 else 0}else{if(call.arguments[1]==null)2 else 0}
            method.invoke(null,call.arguments[0],call.arguments[1],content,currentComposer,0,defaults)
        }
    }
    private fun builder(selected:Managed,dismiss:()->Unit):(Any)->Unit={scope->
        // Deletion is intentionally absent only for plugin-owned playlist identities.
        HostSongPopup.item(scope,"编辑",selected.id>0) {
            dismiss();editing=selected;title=selected.title;description=selected.description;message="";busy=false
        }
        if(selected.id<=0)HostSongPopup.item(scope,"每日推荐由插件管理",false){}
    }
    private fun save(){
        val selected=editing?:return
        if(busy)return
        if(title.isBlank()||title.length>40){message="歌单标题须为1至40字";return}
        val token=epoch;busy=true;message="正在同步至网易云…"
        CloudPlaylistTitles.rename(selected.id,title,description).whenComplete { error,failure->java.awt.EventQueue.invokeLater {
            if(token==epoch){busy=false;message=if(failure!=null)"修改未完成，请刷新歌单核对"else error.orEmpty()
                if(message.isBlank()){CloudMenuCache.invalidateAll();dismiss()}}
        }}
    }
    @Composable fun Dialogs(){
        editing?.let {
            EmbeddedSaltDialog("编辑歌单",::dismiss,::save,confirmVisible=!busy,cancelText="取消",confirmText="保存") {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    SaltText("修改标题会同步至网易云歌单",style=HostStyle.typography.bodySmall)
                    SaltInput(title,{title=it},Modifier.fillMaxWidth(),hint="歌单标题")
                    SaltInput(description,{description=it},Modifier.fillMaxWidth(),hint="描述")
                    if(message.isNotBlank())SaltText(message,style=HostStyle.typography.bodySmall)
                }
            }
        }
    }
}
