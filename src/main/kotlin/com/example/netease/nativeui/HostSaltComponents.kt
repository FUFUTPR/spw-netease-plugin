@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

// Bound image preparation so scrolling a new page cannot start a decode/write
// burst across the shared IO pool. Disposed rows cancel queued work.
private val artworkDispatcher = Dispatchers.IO.limitedParallelism(2)

/** The actual 1.19.0 components, not a second implementation of Salt UI.
 *
 * Names/signatures were recovered from the installed JAR's SMAP, Kotlin metadata,
 * Compose trace names, and bytecode. No host bytecode is shipped in the plugin.
 * All calls retain the current host Composer and CompositionLocals. Default masks
 * are the installed component's own defaults; Long colors are already packed.
 */
internal object HostSaltComponents {
    private fun component(owner: String, vararg parameters: String): Method {
        val matches = HostUiAbi.type(owner).declaredMethods.filter {
            JavaModifier.isStatic(it.modifiers) && it.name == "\u037f" &&
                it.parameterTypes.map(Class<*>::getName) == parameters.map(HostUiAbi::name)
        }
        check(matches.size == 1) { "Salt UI signature missing or ambiguous: $owner ${parameters.toList()}" }
        return matches.single().apply { isAccessible = true }
    }

    private const val C = "androidx.compose.runtime.Composer"
    private const val M = "androidx.compose.ui.Modifier"
    private const val F0 = "kotlin.jvm.functions.Function0"
    private const val F1 = "kotlin.jvm.functions.Function1"
    private const val F2 = "kotlin.jvm.functions.Function2"
    private const val F3 = "kotlin.jvm.functions.Function3"

    // Voxzen uikit Button adds the player's small shape and hairline border to
    // SaltUI BasicButton. Calling SaltUI Button directly would use pill corners.
    private val button by lazy { component("androidx.compose.ui.\u0168", F0, M, "boolean",
        "androidx.compose.ui.\u1078", "androidx.compose.ui.\u107b", F3, C, "int", "int") }
    private val appearances by lazy { Class.forName("androidx.compose.ui.\u1078").enumConstants.associateBy { (it as Enum<*>).name } }

    // SaltUI Text(String, ...), using the real ambient text style/content color.
    private val text by lazy { component("androidx.compose.ui.\u0622", "java.lang.String", M,
        "long", "long", "androidx.compose.ui.text.font.FontStyle", "androidx.compose.ui.text.font.FontWeight",
        "androidx.compose.ui.text.font.FontFamily", "long", "androidx.compose.ui.text.style.TextDecoration",
        "androidx.compose.ui.text.style.TextAlign", "long", "int", "boolean", "int", "int", F1,
        "androidx.compose.ui.text.TextStyle", C, "int", "int", "int") }
    private val overflowValue by lazy { TextOverflow::class.java.getDeclaredMethod("unbox-impl") }

    // SaltUI ItemEdit(value, onValueChange, modifier, hint, textColor, readOnly, ...).
    private val edit by lazy { component("androidx.compose.ui.\u0824", "java.lang.String", F1, M,
        "java.lang.String", "long", "boolean", "androidx.compose.foundation.text.KeyboardOptions",
        "androidx.compose.foundation.text.KeyboardActions", "androidx.compose.ui.text.input.VisualTransformation", F2,
        C, "int", "int") }
    private val basicEdit by lazy { component("androidx.compose.ui.\u02c9", "java.lang.String", F1,
        "androidx.compose.ui.\u07f5", M, "java.lang.String", "long", "boolean",
        "androidx.compose.foundation.text.KeyboardOptions", "androidx.compose.foundation.text.KeyboardActions",
        "androidx.compose.ui.text.input.VisualTransformation", F2, C, "int", "int", "int") }
    private val compactEditPadding by lazy { Class.forName("androidx.compose.ui.\u07f5")
        .getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Byte::class.javaPrimitiveType)
        .newInstance(12f, 0f, 0.toByte()) }
    private val item by lazy { component("androidx.compose.ui.\u0824", F0, "java.lang.String", M, "boolean",
        "androidx.compose.ui.graphics.painter.Painter", "androidx.compose.foundation.layout.PaddingValues",
        "androidx.compose.ui.graphics.Color", "long", "java.lang.String", "long", F2, "java.lang.String",
        "androidx.compose.ui.\u02c7", C, "int", "int", "int") }
    private val track by lazy { component("androidx.compose.ui.\u0994", "com.xuncorp.voxzen.data.entity.Track", M,
        "androidx.compose.ui.\u045d", C, "int", "int") }
    private val trackClass by lazy { Class.forName("com.xuncorp.voxzen.data.entity.Track", false, javaClass.classLoader) }
    private val trackConstructor by lazy { trackClass.constructors.single { it.parameterCount == 25 } }
    private val trackPropertiesConstructor by lazy { HostUiAbi.type("androidx.compose.ui.\u045d")
        .getConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
    private val appConfigType by lazy { Class.forName("com.xuncorp.voxzen.util.AppConfig") }
    private val appConfig by lazy { appConfigType.getField("INSTANCE").get(null) }
    private val qualityBadge by lazy { appConfigType.getMethod("getShowAudioQualityBadge") }
    private val trackPropertiesCache = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    private fun trackProperties(showImage: Boolean = true): Any {
        val badge = qualityBadge.invoke(appConfig) as Boolean
        val key = (if (showImage) 1 else 0) or (if (badge) 2 else 0)
        return trackPropertiesCache.computeIfAbsent(key) { trackPropertiesConstructor.newInstance(showImage, false, badge, 2) }
    }

    private const val F4 = "kotlin.jvm.functions.Function4"
    private val screen by lazy { component("androidx.compose.ui.\u03b4", M, F2, "java.lang.String",
        "java.lang.String", F2, F4, "androidx.compose.ui.\u012f", F4, C, "int", "int") }
    private val group by lazy { component("androidx.compose.ui.\u02bf", M,
        "androidx.compose.foundation.layout.PaddingValues", "long", F3, C, "int", "int") }
    private val back by lazy { component("androidx.compose.ui.\u0682", F0, C, "int") }

    private fun dialogPart(vararg parameters: String): Method =
        Class.forName("androidx.compose.ui.\u0675").declaredMethods.single {
            JavaModifier.isStatic(it.modifiers) && it.parameterTypes.map(Class<*>::getName) == parameters.toList()
        }.apply { isAccessible = true }
    private val dialogFrame by lazy { dialogPart(F3, C, "int") }
    private val dialogBody by lazy { dialogPart("java.lang.String", F3, "boolean", F0, "java.lang.String",
        "boolean", F0, "java.lang.String", "androidx.compose.foundation.layout.ColumnScope", C, "int") }
    val dialogMaxWidth: Float by lazy {
        val type = Class.forName("androidx.compose.ui.\u037a")
        val max = type.enumConstants.single { (it as Enum<*>).name == "Max" }
        type.getMethod("\u037f").invoke(max) as Float
    }

    /** Resolve every ABI before installing the UI hook. Never fall back to M3. */
    fun validate() {
        listOf(button, text, edit, basicEdit, item, track, screen, group, back, dialogFrame, dialogBody)
        compactEditPadding
        check(dialogMaxWidth > 0)
        check(appearances.keys.containsAll(listOf("Filled", "Subtle", "Plain")))
        trackProperties()
        displayTrack(Dto.Song(1, "", "", "", 0, 0, true, ""), 0)
    }

    fun describe(): String = "宿主 Voxzen Button / Salt Text / ItemEdit / Item / TrackItem / BasicScreenBox / ItemOuter"

    fun drawBack(onClick: () -> Unit, composer: Composer) { back.invoke(null, onClick, composer, 0) }

    fun drawButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, appearance: String,
                   content: Any, composer: Composer) {
        button.invoke(null, onClick, modifier, enabled, appearances.getValue(appearance), null,
            content, composer, 0, 1 shl 4)
    }

    fun drawText(value: String, modifier: Modifier, color: Color, style: TextStyle, weight: FontWeight?,
                 maxLines: Int, overflow: TextOverflow, composer: Composer) {
        var defaults = (1 shl 3) or (1 shl 4) or (1 shl 6) or (1 shl 7) or (1 shl 8) or
            (1 shl 9) or (1 shl 10) or (1 shl 15)
        if (color == Color.Unspecified) defaults = defaults or (1 shl 2)
        if (weight == null) defaults = defaults or (1 shl 5)
        if (style == TextStyle.Default) defaults = defaults or (1 shl 16)
        text.invoke(null, value, modifier, color.value.toLong(), 0L, null, weight, null, 0L, null, null, 0L,
            overflowValue.invoke(overflow), true, maxLines, 1, null, style, composer, 0, 0, defaults)
    }

    fun drawEdit(value: String, change: (String) -> Unit, modifier: Modifier, hint: String,
                 composer: Composer, compact: Boolean = false) {
        if (compact) {
            basicEdit.invoke(null, value, change, compactEditPadding, modifier, hint, 0L, false,
                null, null, null, null, composer, 0, 0,
                (1 shl 5) or (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 10))
            return
        }
        // Native readOnly=false, keyboard options/actions/visual transformation defaults.
        edit.invoke(null, value, change, modifier, hint, 0L, false, null, null, null, null,
            composer, 0, (1 shl 4) or (1 shl 6) or (1 shl 7) or (1 shl 8) or (1 shl 9))
    }

    fun drawItem(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit, composer: Composer) {
        val explicit = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 8)
        item.invoke(null, onClick, title, modifier, true, null, null, null, 0L, subtitle,
            0L, null, null, null, composer, 0, 0, ((1 shl 13) - 1) xor explicit)
    }

    /** A read-only presentation object; never inserted into the host database. */
    fun displayTrack(song: Dto.Song, index: Int, coverPath: String? = null): Any {
        val explicit = listOf(0, 1, 2, 3, 4, 7, 10, 11, 12, 17).fold(0) { mask, bit -> mask or (1 shl bit) }
        return trackConstructor.newInstance("netease-${song.id()}", song.name().orEmpty(), song.artists().orEmpty(),
            song.album().orEmpty(), song.artists().orEmpty(), "", 0, index + 1, false, 0, index,
            coverPath ?: "netease-${song.id()}.flac", song.playable(), 0L, 0L, 0L, 0, song.durationMs(), 0, 0, 0f,
            null, "", ((1 shl 23) - 1) xor explicit, null)
    }

    fun drawTrack(presentation: Any, modifier: Modifier, composer: Composer, showImage: Boolean = true) {
        track.invoke(null, presentation, modifier, trackProperties(showImage), composer, 0, 0)
    }

    fun drawScreen(modifier: Modifier, title: String, action: Any, tools: Any, content: Any, composer: Composer) {
        // Keep the host's screen style, padding, header and material context.
        screen.invoke(null, modifier, action, title, null, tools, null, null, content, composer, 0,
            (1 shl 3) or (1 shl 5) or (1 shl 6))
    }
    fun drawGroup(content: Any, composer: Composer) {
        group.invoke(null, null, null, 0L, content, composer, 0, 7)
    }

    fun drawDialogFrame(content: Any, composer: Composer) { dialogFrame.invoke(null, content, composer, 0) }
    fun drawDialogBody(title: String, content: Any, dismiss: () -> Unit, confirm: () -> Unit,
                       confirmVisible: Boolean, cancelText: String, confirmText: String,
                       scope: ColumnScope, composer: Composer) {
        dialogBody.invoke(null, title, content, true, dismiss, cancelText, confirmVisible, confirm, confirmText,
            scope, composer, 0)
    }

}

internal val LocalSaltSnapshot = staticCompositionLocalOf<HostSaltTheme.Snapshot> { error("Host SaltTheme is required") }

/** Ambient values are read from the host; no MaterialTheme or default palette. */
internal object HostStyle {
    val colors: HostSaltTheme.Palette @Composable get() = LocalSaltSnapshot.current.palette
    val shapes: HostSaltTheme.ShapeSet @Composable get() = LocalSaltSnapshot.current.shapes
    val typography: SaltTypography @Composable get() = SaltTypography(LocalSaltSnapshot.current.textStyles)
}

internal class SaltTypography(t: HostSaltTheme.TypeSet) {
    val bodyMedium = t.main
    val bodySmall = t.sub
    val labelMedium = t.sub
    val labelSmall = t.sub
    val titleMedium = t.main
    val headlineSmall = t.largeTitle
}

@Composable
internal fun SaltText(text: String, modifier: Modifier = Modifier, style: TextStyle = TextStyle.Default,
                      color: Color = Color.Unspecified, fontWeight: FontWeight? = null,
                      maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    HostSaltComponents.drawText(text, modifier, color, style, fontWeight, maxLines, overflow, currentComposer)
}

@Composable
internal fun SaltButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                        content: @Composable RowScope.() -> Unit) {
    HostSaltComponents.drawButton(onClick, modifier, enabled, "Filled", content, currentComposer)
}

@Composable
internal fun SaltSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                                 content: @Composable RowScope.() -> Unit) {
    HostSaltComponents.drawButton(onClick, modifier, enabled, "Subtle", content, currentComposer)
}

@Composable
internal fun SaltPlainButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                             content: @Composable RowScope.() -> Unit) {
    HostSaltComponents.drawButton(onClick, modifier, enabled, "Plain", content, currentComposer)
}

@Composable
internal fun SaltInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
                       hint: String = "", compact: Boolean = false) {
    val focusManager = LocalFocusManager.current
    HostSaltComponents.drawEdit(value, onValueChange, modifier.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Tab) {
            focusManager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            true
        } else false
    }, hint, currentComposer, compact)
}

@Composable
internal fun SaltGroup(content: @Composable ColumnScope.() -> Unit) {
    HostSaltComponents.drawGroup(content, currentComposer)
}

@Composable
internal fun SaltBackButton(onClick: () -> Unit) {
    Box(Modifier.clearAndSetSemantics {
        contentDescription = "返回配置"
        onClick { onClick(); true }
    }) { HostSaltComponents.drawBack(onClick, currentComposer) }
}

@Composable
internal fun SaltScreen(title: String, modifier: Modifier = Modifier, action: @Composable () -> Unit, tools: @Composable () -> Unit,
                        content: @Composable BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    HostSaltComponents.drawScreen(modifier, title, action, tools, content, currentComposer)
}

@Composable
internal fun SaltItem(title: String, subtitle: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    HostSaltComponents.drawItem(title, subtitle, modifier, onClick, currentComposer)
}

@Composable
internal fun SaltTrack(song: Dto.Song, index: Int, modifier: Modifier = Modifier,
                       onPress: () -> Unit = {}, onSelect: () -> Unit,
                       onPlay: (() -> Unit)? = null, showNote: Boolean = true,
                       onContext: ((Offset) -> Unit)? = null) {
    // Native TrackItem reads its image from Track.path. Feed the existing tiny
    // cover-only FLAC, never an invented audio filename or a new music download.
    val coverPath by produceState<String?>(null, song) {
        value = withContext(artworkDispatcher) {
            runCatching {
                com.example.netease.svc.CoverStore.ensure(song.album(), song.artists(), song.picUrl())
            }.getOrNull()
        }
    }
    val presentation = remember(song, index, coverPath) { HostSaltComponents.displayTrack(song, index, coverPath) }
    val currentPress by rememberUpdatedState(onPress)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentPlay by rememberUpdatedState(onPlay)
    val currentContext by rememberUpdatedState(onContext)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(modifier) {
        Column {
            // TrackItem's album link normally navigates the local library. These
            // are online results: retain the plugin's existing full-row actions.
            HostSaltComponents.drawTrack(presentation,
                Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {}, currentComposer, !coverPath.isNullOrBlank())
            if (showNote && song.playable() && song.fee() == 1) {
                SaltText(trackNote(song), style = HostStyle.typography.bodySmall,
                    color = HostStyle.colors.subText, modifier = Modifier.padding(start = 76.dp, bottom = 4.dp))
            }
        }
        Box(Modifier.matchParentSize().semantics {
            contentDescription = listOf(song.name(), song.artists(), song.album(), trackNote(song)).joinToString("，")
            onClick { onPress(); onSelect(); true }
            if (onPlay != null) customActions = listOf(CustomAccessibilityAction("播放") { onPress(); onPlay(); true })
        }.onGloballyPositioned { coordinates[0] = it }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (event.buttons.isSecondaryPressed && currentContext != null) {
                val position = coordinates[0]?.localToWindow(event.changes.first().position) ?: Offset.Zero
                event.changes.forEach { it.consume() }
                currentPress(); currentSelect(); currentContext?.invoke(position)
            }
        }.pointerInput(song.id(), onPlay != null) {
            detectTapGestures(onPress = {
                currentPress()
                if (currentPlay != null) currentSelect()
            }, onTap = { if (currentPlay == null) currentSelect() },
                onDoubleTap = if (onPlay == null) null else { { currentPlay?.invoke() } })
        })
    }
}

