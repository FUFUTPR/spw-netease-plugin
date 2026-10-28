@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.netease.nativeui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.netease.svc.PluginUpdater
import com.example.netease.svc.PluginUpdater.Phase
import com.example.netease.svc.PluginUpdateInstaller

/** Presentation only. Downloading/verification survive closing a foreground dialog. */
internal object NativeUpdateUi {
    var state by mutableStateOf(PluginUpdater.snapshot())
    private var visible by mutableStateOf(false)
    private var exiting by mutableStateOf(false)
    private var background by mutableStateOf(false)
    private var optedOut by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var installing by mutableStateOf(false)
    private var installed by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var epoch = 0L
    private var checking by mutableStateOf(false)
    private var checkNotice by mutableStateOf("")
    private var manualRequest = 0L
    private data class DialogState(val update:PluginUpdater.State,val checking:Boolean,val notice:String)
    fun attach() {
        val token=++epoch
        installing=false;installed=false
        checking=false;checkNotice="";++manualRequest
        PluginUpdater.observe { next -> if (token==epoch) state=next }
    }
    fun stop() {
        ++epoch;PluginUpdater.observe(null)
        visible=false;exiting=false;background=false;saving=false;installing=false;installed=false;error=""
        checking=false;checkNotice="";++manualRequest
        state=PluginUpdater.snapshot()
    }
    fun dialogOpen() = visible || exiting
    fun windowUnavailable() { visible=false;exiting=false;background=true;saving=false;checking=false;checkNotice="";++manualRequest }
    fun checkForUpdates(): Boolean {
        val engine=PluginUpdater.global() ?: return false
        if(checking)return true
        if(installing || saving || (NativeUiPresentation.anyDialogOpen() && !visible))return false
        if(engine.state().busy()) {state=engine.state();if(!visible)openDialog();return true}
        val lifecycle=epoch;val request=++manualRequest
        checking=true;checkNotice="";error="";installed=false;background=false;visible=true
        engine.checkManually().whenComplete { result, failure -> java.awt.EventQueue.invokeLater {
            if(lifecycle==epoch && request==manualRequest && checking && PluginUpdater.global()===engine) {
                checking=false
                if(failure!=null || result==null)checkNotice="检查更新失败，请稍后重试"
                else {
                    state=result.state()
                    checkNotice=when {
                        result.error().isNotBlank() -> result.error()
                        state.release()==null -> "没有可用的新版本"
                        else -> ""
                    }
                    optedOut=!engine.notificationsEnabled()
                }
            }
        }}
        return true
    }
    private fun closeDialog() {
        if (!visible) return
        visible=false;exiting=true
        NativeUiPresentation.requestFocusReturn()
    }
    fun openDialog() {
        if (NativeUiPresentation.anyDialogOpen() || state.release()==null) return
        optedOut=PluginUpdater.global()?.notificationsEnabled()==false
        background=false;error="";visible=true
    }
    private fun saveThen(action: () -> Unit) {
        if(saving)return
        val engine=PluginUpdater.global() ?: return
        val desired=!optedOut
        if(engine.notificationsEnabled()==desired){action();return}
        saving=true;val token=epoch
        engine.setNotifications(desired).whenComplete { ok, _ ->
            if(token==epoch) {
                saving=false
                if(ok==true)action() else error="无法保存提醒设置，请重试"
            }
        }
    }
    fun dismiss() {
        if(!visible || saving || installing)return
        if(checking || checkNotice.isNotBlank()) {checking=false;checkNotice="";++manualRequest;closeDialog();return}
        when {
            state.busy() -> { PluginUpdater.global()?.cancel();closeDialog() }
            state.phase()==Phase.COMPLETE -> closeDialog()
            else -> saveThen(::closeDialog)
        }
    }
    fun confirm(displayed:Phase) {
        if(!visible || saving || installing || checking || checkNotice.isNotBlank())return
        when {
            displayed in listOf(Phase.PROBING,Phase.DOWNLOADING,Phase.VERIFYING) -> {
                // Preserve the clicked button's intent even if completion arrived
                // before its next frame: Background must never become Open Folder.
                background=true;closeDialog()
            }
            displayed==Phase.COMPLETE && state.phase()==Phase.COMPLETE -> {
                install()
            }
            !state.busy() && state.phase()!=Phase.COMPLETE -> saveThen { error="";PluginUpdater.global()?.download() }
        }
    }
    private fun install() {
        if(installing||installed||state.phase()!=Phase.COMPLETE)return
        val navigation=HostGlobalSearch.captureModManagementNavigation()
        if(navigation==null){error="暂时无法打开模组管理页，请稍后重试";return}
        val token=epoch;val snapshot=state
        installing=true;error=""
        val accepted=PluginUpdateInstaller.request(snapshot,navigation) { result ->
            if(token==epoch) {
                installing=false;installed=result.installed()
                if(!result.installed()) {
                    error=result.message()
                    if(result.needsDownload())PluginUpdater.global()?.invalidateCompleted(snapshot,error)
                }
            }
        }
        if(accepted)closeDialog() else {installing=false;error="安装任务暂未启动，请稍后重试"}
    }
    @Composable fun BoxScope.Bubble(rootWidth: Dp) {
        val current=state
        val reminders=PluginUpdater.global()?.notificationsEnabled()!=false
        val offered=(reminders && current.phase() in listOf(Phase.AVAILABLE,Phase.CANCELLED)) ||
            current.phase() in listOf(Phase.FAILED,Phase.COMPLETE) || current.busy()
        val show=offered && !installed && !NativeUiPresentation.anyDialogOpen() && (!current.busy() || background)
        val retained=rememberRetained(current.takeIf { offered }) ?: current
        AnimatedVisibility(show, modifier=Modifier.align(Alignment.BottomStart).padding(16.dp)
            .width(minOf(188.dp,(rootWidth-32.dp).coerceAtLeast(0.dp))).testTag("update-bubble"),
            enter=fadeIn(Motion.dialogEnter())+slideInVertically(Motion.dialogEnter()){it/2},
            exit=fadeOut(Motion.dialogExit())+slideOutVertically(Motion.dialogExit()){it/2}) {
            val title=when {
                installing -> "正在安装"
                retained.busy() -> "正在下载"
                retained.phase()==Phase.COMPLETE -> "下载完毕"
                retained.phase()==Phase.FAILED -> "下载失败，点击重试"
                else -> "网易云插件已有新版本"
            }
            val titleMotion=updateTransition(title,label="update-bubble-title")
            Column(modifier=Modifier.fillMaxWidth().shadow(6.dp,HostStyle.shapes.small)
                .clip(HostStyle.shapes.small).background(HostStyle.colors.popup)
                .border(1.dp,HostStyle.colors.stroke,HostStyle.shapes.small).focusReturnTarget()
                .clickable(enabled=show&&!installing,role=Role.Button,onClick={
                if(show&&!installing) {
                    if(current.phase()==Phase.COMPLETE && !titleMotion.isRunning && titleMotion.currentState=="下载完毕")install()
                    else openDialog()
                }
                }).padding(12.dp)) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    titleMotion.AnimatedContent(transitionSpec={ (fadeIn(Motion.state()) togetherWith fadeOut(Motion.state())).using(null) }) {
                        SaltText(it,style=HostStyle.typography.bodySmall,maxLines=2)
                    }
                    if(retained.busy() || retained.phase()==Phase.COMPLETE) UpdateProgress(retained,small=true)
                    if(!retained.busy())SaltText(
                        if(installing) "请稍候，安装后请手动启动插件" else if(retained.phase()==Phase.COMPLETE) "点击安装" else "点击更新 · ${retained.release()?.version().orEmpty()}",
                        style=HostStyle.typography.labelSmall,maxLines=2)
                    if(error.isNotBlank())SaltText(error,style=HostStyle.typography.labelSmall,maxLines=2)
                }
            }
        }
    }
    @Composable fun Dialogs() {
        val window=LocalAwtWindow.current
        val transition=remember { MutableTransitionState(false) }
        transition.targetState=visible
        LaunchedEffect(transition.isIdle,transition.currentState,visible) {
            if(!visible && transition.isIdle && !transition.currentState) {
                exiting=false
                if(NativeUiPresentation.consumeFocusReturn()) FocusReturn.restore(window)
            }
        }
        DisposableEffect(Unit) { guardedCleanup { windowUnavailable() } }
        val presentation=DialogState(state,checking,checkNotice)
        val shown=rememberRetained(presentation.takeIf { visible }) ?: presentation
        AnimatedVisibility(visibleState=transition,modifier=Modifier.fillMaxSize(),
            enter=fadeIn(Motion.dialogEnter()),exit=fadeOut(Motion.dialogExit())) {
            val current=shown.update
            val checkingNow=shown.checking
            val notice=shown.notice
            val complete=current.phase()==Phase.COMPLETE
            val busy=current.busy()
            EmbeddedSaltDialog(
                title=when {checkingNow->"正在检查最新版本";notice.isNotBlank()->"检查更新";complete->"下载完毕";busy->"正在下载新版本，请耐心等待";else->"网易云插件已有新版本"},
                onDismiss=::dismiss,onConfirm={confirm(current.phase())},confirmVisible=!checkingNow&&notice.isBlank(),
                cancelText=when {checkingNow||notice.isNotBlank()->"关闭";busy->"取消下载";complete->"关闭";else->"取消"},
                confirmText=when {busy->"后台下载";complete->"安装";else->"确定"},
                interactive=visible,
            ) {
                Column(Modifier.fillMaxWidth().animateContentSize(Motion.state()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(checkingNow || notice.isNotBlank()) {
                        SaltText(if(checkingNow)"请稍候…" else notice)
                    } else if(busy || complete) {
                        SaltText(if(complete)"安装完成后请手动启动插件" else "下载完成后可点击安装",style=HostStyle.typography.bodySmall)
                        UpdateProgress(current,small=false)
                    } else {
                        SaltText("是否下载新版本 ${current.release()?.version().orEmpty()}？")
                        if(current.phase()==Phase.FAILED)SaltText(current.message(),style=HostStyle.typography.bodySmall)
                        OptOut()
                    }
                    if (!checkingNow && notice.isBlank() && current.release()?.notes()?.isNotBlank() == true) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).testTag("update-release-notes")) {
                            SaltText(current.release()!!.notes(), style = HostStyle.typography.bodySmall)
                        }
                    }
                    if(error.isNotBlank())SaltText(error,style=HostStyle.typography.bodySmall)
                }
            }
        }
    }
    @Composable private fun OptOut() {
        val color by animateColorAsState(if(optedOut)HostStyle.colors.highlight else HostStyle.colors.stroke,Motion.state(),label="update-optout-color")
        val check by animateFloatAsState(if(optedOut)1f else 0f,Motion.state(),label="update-optout-check")
        val tick=HostStyle.colors.background
        Row(Modifier.testTag("update-optout").heightIn(min=32.dp).clip(HostStyle.shapes.small)
            .focusReturnTarget().toggleable(value=optedOut,enabled=visible&&!saving,role=Role.Checkbox,
                onValueChange={optedOut=it}).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp)) {
                drawCircle(color,style=Stroke(1.4.dp.toPx()))
                if(check>0f) {
                    drawCircle(color.copy(alpha=check))
                    drawLine(tick.copy(alpha=check),Offset(size.width*.25f,size.height*.52f),Offset(size.width*.44f,size.height*.7f),1.6.dp.toPx(),StrokeCap.Round)
                    drawLine(tick.copy(alpha=check),Offset(size.width*.44f,size.height*.7f),Offset(size.width*.76f,size.height*.32f),1.6.dp.toPx(),StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(8.dp));SaltText("以后都不提醒",style=HostStyle.typography.labelSmall,color=HostStyle.colors.subText)
        }
    }
    @Composable private fun UpdateProgress(current:PluginUpdater.State,small:Boolean) {
        val total=current.total().coerceAtLeast(1)
        val ratio=(current.downloaded().toDouble()/total).toFloat().coerceIn(0f,1f)
        val progress by animateFloatAsState(ratio,Motion.state(),label="update-download-progress")
        Box(Modifier.fillMaxWidth().height(if(small)24.dp else 32.dp).testTag("update-progress")
            .semantics { progressBarRangeInfo=ProgressBarRangeInfo(ratio,0f..1f) }
            .clip(HostStyle.shapes.small).background(HostStyle.colors.subBackground)
            .border(1.dp,HostStyle.colors.stroke,HostStyle.shapes.small),contentAlignment=Alignment.Center) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(HostStyle.colors.highlight.copy(alpha=.32f)))
            }
            SaltText("${PluginUpdater.bytes(current.downloaded())}/${PluginUpdater.bytes(current.total())}",
                style=if(small)HostStyle.typography.labelSmall else HostStyle.typography.bodySmall,
                maxLines=1,overflow=TextOverflow.Clip)
        }
    }
}
