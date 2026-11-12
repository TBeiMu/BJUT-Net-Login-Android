package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val WeekSnapFraction = .25f
// Matches Foundation 1.9.4's anchored draggable threshold; expressed in dp/s at the UI boundary.
internal const val WeekFlingVelocityDp = 125f

/** Stable for the page lifetime: re-touching never replaces the pointer node or restarts at zero. */
internal class WeekPagerState(initialWeek: Int, val weekCount: Int, private val scope: CoroutineScope,
    private val onSelected: (Int) -> Unit) {
    var week by mutableIntStateOf(initialWeek.coerceIn(1, weekCount))
        private set
    var position by mutableFloatStateOf(week.toFloat())
        private set
    private var edge by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
        private set
    var settling by mutableStateOf(false)
        private set
    var height = 1f
        private set
    private var velocityThreshold = WeekFlingVelocityDp
    private var baseWeek = week
    private var dragMin = week.toFloat()
    private var dragMax = week.toFloat()
    private var distance = 0f
    private var epoch = 0
    private var animation: Job? = null
    private var disposed = false
    val visualPosition: Float get() = position - edge / height
    val moving: Boolean get() = dragging || settling || abs(position - week) > .001f || abs(edge) > .5f

    private val events = MutableInteractionSource()
    // Draggable reports cancellation before calling onDragStopped(0). Handle it synchronously so a
    // cancelled long drag cannot be interpreted as an accepted release by a delayed Flow collector.
    val interactions = object : MutableInteractionSource {
        override val interactions = events.interactions
        override suspend fun emit(interaction: Interaction) {
            if (interaction is DragInteraction.Cancel) cancel()
            events.emit(interaction)
        }
        override fun tryEmit(interaction: Interaction): Boolean {
            if (interaction is DragInteraction.Cancel) cancel()
            return events.tryEmit(interaction)
        }
    }

    fun resize(height: Float, velocityThreshold: Float = this.velocityThreshold) {
        this.velocityThreshold = velocityThreshold
        if (height > 0 && abs(height - this.height) > .5f) { cancel(); this.height = height }
    }

    fun beginGesture() {
        if (disposed) return
        interruptAnimation()
        baseWeek = week
        val visual = visualPosition
        position = visual.coerceIn(1f, weekCount.toFloat())
        edge = (position - visual) * height
        // Several accepted flicks can be ahead of the animation. Include the touched position
        // in the new bounds, otherwise the third quick gesture jumps to the previous target.
        dragMin = minOf(position, maxOf(1, baseWeek - 1).toFloat())
        dragMax = maxOf(position, minOf(weekCount, baseWeek + 1).toFloat())
        distance = 0f
        dragging = true
        // Keep position and edge. A second flick starts from the previous accepted target week,
        // while the rendered old/new cards stay exactly where the user touched them.
    }

    fun dragBy(delta: Float) {
        if (disposed || !dragging) return
        distance += delta
        var remaining = delta
        if (edge != 0f && edge * remaining < 0f) {
            val release = if (abs(remaining) * .2f < abs(edge)) remaining else -edge / .2f
            edge += release * .2f
            remaining -= release
        }
        val proposed = position - remaining / height
        val bounded = proposed.coerceIn(dragMin, dragMax)
        val consumed = (position - bounded) * height
        position = bounded
        edge = (edge + (remaining - consumed) * .2f).coerceIn(-height * .06f, height * .06f)
    }

    fun endGesture(velocity: Float) {
        if (disposed || !dragging) return
        dragging = false
        val delta = when {
            distance == 0f -> 0
            abs(velocity) >= velocityThreshold -> weekDragDirection(velocity)
            abs(distance) >= height * WeekSnapFraction -> weekDragDirection(distance)
            else -> 0
        }
        val target = (baseWeek + delta).coerceIn(1, weekCount)
        if (week != target) { week = target; onSelected(target) }
        val start = visualPosition
        position = start
        edge = 0f
        val token = ++epoch
        if (abs(start - target) < .001f) { position = target.toFloat(); settling = false; return }
        settling = true
        animation = scope.launch {
            try {
                // Bounded settling has no long spring tail. A new touch cancels this job and owns
                // the existing visual position, even when several flicks arrive within 150 ms.
                animate(start, target.toFloat(), animationSpec = tween(
                    if (abs(velocity) >= velocityThreshold) 150 else 200, easing = FastOutSlowInEasing)) { value, _ ->
                    if (!disposed && epoch == token) position = value
                }
                if (!disposed && epoch == token) position = target.toFloat()
            } finally { if (epoch == token) { settling = false; animation = null } }
        }
    }

    fun select(number: Int) {
        if (disposed) return
        cancel()
        val target = number.coerceIn(1, weekCount)
        if (target != week) { week = target; onSelected(target) }
        position = target.toFloat()
    }

    fun cancel() {
        if (disposed) return
        interruptAnimation()
        dragging = false
        distance = 0f
        position = week.toFloat()
        edge = 0f
    }

    fun dispose() { cancel(); disposed = true }
    private fun interruptAnimation() { epoch++; animation?.cancel(); animation = null; settling = false }
}
