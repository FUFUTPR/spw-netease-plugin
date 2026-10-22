package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 深浅色适配：宿主 1.19.0 的界面是自绘配色，没有使用 Material3 的 MaterialTheme，
 * 因此 Hook 点继承不到宿主配色。这里用 Compose 提供的系统深色检测（与宿主
 * com.xuncorp.voxzen.util.AppConfig 使用的是同一个 isSystemInDarkTheme）决定用
 * Material3 深色还是浅色配色，至少让「深色/浅色模式」与宿主保持一致；
 * 具体品牌色等宿主专属颜色仍无法从宿主读取（记在 ITERATION 的已知偏差）。
 */
@Composable
private fun NativeUiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Hook 注册结果，供页面显示与真机排查（只写内存，不落盘）。 */
internal object NativeUiRuntime {
    var hostVersion: String = "unknown"
    var pluginVersion: String = "unknown"
}

/** 页面状态：visible=false 时只留右下角入口，宿主原有配置内容照常可见可用。 */
internal object NativeUiState {
    var visible by mutableStateOf(true)
    var clicks by mutableStateOf(0)
    var status by mutableStateOf("尚未注册 Hook")
}

/**
 * 最小原生页面：在宿主「插件配置」页的内容区之上渲染。
 *
 * <p>约束（docs/PLAN.md 批次 1）：标题、正文、按钮、状态；主题跟随宿主
 * （不写死颜色与字号，深浅色由 NativeUiTheme 按系统深色模式选择）；缩放跟随宿主
 * （dp/字体缩放来自宿主 CompositionLocals）。关闭后只撤掉本层，宿主内容不动。</p>
 */
@Composable
internal fun NativeUiPanel() = NativeUiTheme { NativeUiPanelContent() }

@Composable
private fun NativeUiPanelContent() {
    // 宿主提供的密度/字体缩放：页面不加任何自己的 Density 提供者，直接用宿主的 CompositionLocals。
    val density = LocalDensity.current
    if (!NativeUiState.visible) {
        // 关闭后仅保留入口；Box 本身不拦截指针事件，宿主配置页照常可操作。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp),
            ) {
                TextButton(onClick = { NativeUiState.visible = true }) {
                    Text("打开原生 UI 测试区域")
                }
            }
        }
        return
    }

    // 页面打开时铺满内容区并吞掉指针事件，避免点击穿透到宿主的配置项。
    Box(Modifier.fillMaxSize().consumeAllPointerEvents()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "网易云音乐 · 原生 UI 测试区域",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "插件 " + NativeUiRuntime.pluginVersion + " · 宿主 " + NativeUiRuntime.hostVersion,
                    style = MaterialTheme.typography.labelLarge,
                )
                // 分隔线用最基础的原语，避免依赖可能随版本变动的 Divider API。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                val densityText = String.format("%.2f×", density.density)
                val fontScaleText = String.format("%.2f×", density.fontScale)
                Text(
                    text = "这一区域由插件在宿主主窗口内用 Compose 渲染，不创建任何独立窗口。" +
                        "密度 " + densityText + "、字体缩放 " + fontScaleText +
                        " 都取自宿主 CompositionLocals；深浅色跟随系统深色模式（" +
                        (if (isSystemInDarkTheme()) "当前为深色" else "当前为浅色") + "）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "挂载状态：" + NativeUiState.status,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { NativeUiState.clicks += 1 }) {
                        Text("状态自检 +1（" + NativeUiState.clicks + "）")
                    }
                    OutlinedButton(onClick = { NativeUiState.visible = false }) {
                        Text("关闭并返回宿主配置")
                    }
                }
                Text(
                    text = "验证点：关闭后宿主原有配置内容应照常显示与操作；再次进入配置页时，" +
                        "右下角会保留「打开原生 UI 测试区域」入口，不会重复注册 Hook。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 消费指针事件：本层在最上面，宿主内容在下面，不消费会点穿。 */
private fun Modifier.consumeAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
