package com.example.netease.nativeui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 原生 UI 的唯一节奏来源。
 *
 * Compose 的 Transition/Animatable 会从组合的协程上下文读取宿主
 * MotionDurationScale：宿主把动画时长缩放设为 0 时，所有过渡都在首帧完成，
 * 但状态机与回调顺序与正常时长完全一致，因此无需另写一条“无动画”分支。
 */
internal object Motion {
    const val PAGE_MS = 180
    const val DIALOG_ENTER_MS = 160
    const val DIALOG_EXIT_MS = 120
    const val STATE_MS = 120

    /** 0.12.x 既有顶栏节奏，本批次保持不变。 */
    const val TOPBAR_MS = 220

    fun easing() = FastOutSlowInEasing

    fun <T> page(): FiniteAnimationSpec<T> = tween(PAGE_MS, easing = FastOutSlowInEasing)
    fun <T> dialogEnter(): FiniteAnimationSpec<T> = tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing)
    fun <T> dialogExit(): FiniteAnimationSpec<T> = tween(DIALOG_EXIT_MS, easing = FastOutSlowInEasing)
    fun <T> state(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = FastOutSlowInEasing)
    fun <T> topbar(): FiniteAnimationSpec<T> = tween(TOPBAR_MS, easing = FastOutSlowInEasing)
}

/**
 * 退场动画期间仍需渲染“最后一次打开”的内容，而业务状态已经清空。
 * 只在拿到非空值时更新，保证退场阶段读到的是不可交互的快照。
 */
@Composable
internal fun <T : Any> rememberRetained(value: T?): T? {
    var retained by remember { mutableStateOf<T?>(null) }
    if (value != null && value !== retained) retained = value
    return retained
}
