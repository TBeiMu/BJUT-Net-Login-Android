package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.math.abs
import kotlin.math.sign

internal val WeekSnapAnimation = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow, visibilityThreshold = .5f)
internal const val WeekSnapFraction = .25f

/** Only the settled week is saved. Each gesture owns at most the current and adjacent anchors. */
internal class WeekPagerState(initialWeek: Int, val weekCount: Int, private val onSelected: (Int) -> Unit) {
    var week by mutableIntStateOf(initialWeek.coerceIn(1, weekCount))
        private set
    var session by mutableStateOf(WeekDragSession(week, weekCount, 1f, this))
        private set
    private var disposed = false

    fun resize(height: Float) {
        if (height > 0 && abs(height - session.height) > .5f) replace(week, height)
    }

    fun select(number: Int) {
        if (disposed) return
        val target = number.coerceIn(1, weekCount)
        replace(target, session.height)
        onSelected(target)
    }

    fun cancel(expected: WeekDragSession = session) {
        if (!disposed && session === expected) replace(week, session.height)
    }

    fun dispose() { disposed = true; session.invalidate() }

    internal fun finish(expected: WeekDragSession, epoch: Int) {
        if (disposed || session !== expected || expected.epoch != epoch || expected.dragging) return
        val target = expected.anchors.anchors.closestAnchor(expected.anchors.requireOffset()) ?: return
        if (abs(expected.anchors.requireOffset() - expected.anchors.anchors.positionOf(target)) > .5f) return
        // The delegate is finishing inside its own mutex. Swap the session without cancelling that
        // coroutine; its remaining cleanup has no reference to the newly selected week.
        expected.active = false
        val next = target.coerceIn(maxOf(1, week - 1), minOf(weekCount, week + 1))
        week = next
        session = WeekDragSession(next, weekCount, expected.height, this)
        if (next != expected.baseWeek) onSelected(next)
    }

    private fun replace(target: Int, height: Float) {
        session.invalidate()
        week = target
        session = WeekDragSession(target, weekCount, height, this)
    }
}

internal class WeekDragSession(val baseWeek: Int, weekCount: Int, val height: Float, private val owner: WeekPagerState) {
    val anchors = AnchoredDraggableState(baseWeek, DraggableAnchors {
        baseWeek at 0f
        if (baseWeek > 1) (baseWeek - 1) at height
        if (baseWeek < weekCount) (baseWeek + 1) at -height
    })
    val interactions = MutableInteractionSource()
    var dragging by mutableStateOf(false)
        private set
    var settling by mutableStateOf(false)
        private set
    var edge by mutableFloatStateOf(0f)
        private set
    var epoch = 0
        private set
    var active = true
    private var flingJob: Job? = null
    val offset: Float get() = anchors.requireOffset() + edge
    val fraction: Float get() = offset / height
    val moving: Boolean get() = dragging || settling || abs(offset) > .5f

    fun beginGesture() {
        if (!active || dragging) return
        epoch++
        dragging = true
        settling = false
    }

    fun endGesture() { dragging = false }
    fun cancelGesture() { if (active) owner.cancel(this) }
    fun invalidate() { active = false; epoch++; flingJob?.cancel() }

    /** Consume only excess movement with resistance. Rendering uses the same offset for dates. */
    val overscroll = object : OverscrollEffect {
        override val isInProgress: Boolean get() = abs(edge) > .5f

        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            beginGesture()
            var remaining = delta.y
            if (edge != 0f && edge * remaining < 0f) {
                val releasing = if (abs(remaining) * .2f < abs(edge)) remaining else -edge / .2f
                edge += releasing * .2f
                remaining -= releasing
            }
            val consumed = performScroll(Offset(delta.x, remaining))
            edge = (edge + (remaining - consumed.y) * .2f).coerceIn(-height * .06f, height * .06f)
            return delta
        }

        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            if (!active) { performFling(Velocity.Zero); return }
            dragging = false
            settling = true
            val token = epoch
            flingJob = currentCoroutineContext().job
            try {
                // Foundation 1.9.4 picks the nearest anchor for exactly zero velocity, bypassing
                // positionalThreshold. A negligible directional velocity preserves the 25% rule
                // when the user pauses before release; its built-in fast-fling threshold is intact.
                val release = when {
                    edge != 0f -> Velocity.Zero
                    velocity.y == 0f && anchors.requireOffset() != 0f -> Velocity(velocity.x, sign(anchors.requireOffset()) * .001f)
                    else -> velocity
                }
                performFling(release)
                if (edge != 0f) animate(edge, 0f, animationSpec = WeekSnapAnimation) { value, _ ->
                    if (active && token == epoch) edge = value
                }
                if (active && token == epoch) { edge = 0f; owner.finish(this@WeekDragSession, token) }
            } finally {
                if (token == epoch) { settling = false; flingJob = null }
            }
        }
    }
}
