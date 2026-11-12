package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * 工小助's mark (Logo A2 · 墨朱): 工 built from three tiles. The top bar is the day beginning, the
 * bottom bar the day ending, and the vermilion stem is "now". Each animation tells one story of it:
 *
 *  · WEEK   — the opening: a little week timetable condenses into the character. Your whole week, one glance.
 *  · SIGNAL — campus login: a Wi-Fi symbol straightens into 工; connected is what 工小助 means.
 *  · BOUNCE — any other wait: "now" is a drop bouncing between the day's start and its end.
 *
 * All geometry is in the 108-unit space of an adaptive icon, shared with the launcher and splash vectors.
 */
internal enum class MarkMotion { WEEK, SIGNAL, BOUNCE }

private object G {
    // The finished mark: three tiles, 3-unit gaps.
    const val TOP_X = 33f; const val TOP_Y = 27f; const val TOP_W = 42f
    const val STEM_X = 48f; const val STEM_Y = 41.5f; const val STEM_W = 12f; const val STEM_H = 25f
    const val BOTTOM_X = 27f; const val BOTTOM_Y = 69.5f; const val BOTTOM_W = 54f
    const val BAR_H = 11.5f; const val R = 4f
    // The drawing keeps a margin around the glyph for squash, bounce and the Wi-Fi arcs.
    const val VIEW_FROM = 20f; const val VIEW = 68f
}

internal const val WEEK_MS = 1500f
internal const val SIGNAL_MS = 2400f
internal const val BOUNCE_MS = 1300f

private val Emphasized = CubicBezierEasing(.2f, 0f, 0f, 1f)
private val Fly = CubicBezierEasing(.6f, 0f, .1f, 1f)
private val Morph = CubicBezierEasing(.65f, 0f, .25f, 1f)
private val Back = CubicBezierEasing(.3f, 1.6f, .5f, 1f)

private fun span(t: Float, start: Float, length: Float) = ((t - start) / length).coerceIn(0f, 1f)
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** A soft knock: down, back, a small rebound, still — 0 outside 230 ms after [start]. */
private fun knock(t: Float, start: Float): Float {
    val l = t - start
    return when {
        l < 0f -> 0f
        l < 120f -> sin(PI * l / 120f).toFloat()
        l < 230f -> -.3f * sin(PI * (l - 120f) / 110f).toFloat()
        else -> 0f
    }
}

/** Maps glyph units to pixels and offers the few shapes every animation needs. */
private class Pen(val scope: DrawScope, val ink: Color, val red: Color) {
    val u = min(scope.size.width, scope.size.height) / G.VIEW
    val ox = (scope.size.width - G.VIEW * u) / 2 - G.VIEW_FROM * u
    val oy = (scope.size.height - G.VIEW * u) / 2 - G.VIEW_FROM * u
    fun x(v: Float) = ox + v * u
    fun y(v: Float) = oy + v * u
    fun tile(x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float = 1f, radius: Float = G.R) {
        if (w <= .05f || h <= .05f || alpha <= 0f) return
        scope.drawRoundRect(color, Offset(x(x), y(y)), Size(w * u, h * u),
            CornerRadius(min(radius, min(w, h) / 2) * u), alpha = alpha.coerceAtMost(1f))
    }
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
        scope.drawOval(color, Offset(x(cx - rx), y(cy - ry)), Size(2 * rx * u, 2 * ry * u))
    fun rest() {
        tile(G.TOP_X, G.TOP_Y, G.TOP_W, G.BAR_H, ink)
        tile(G.STEM_X, G.STEM_Y, G.STEM_W, G.STEM_H, red)
        tile(G.BOTTOM_X, G.BOTTOM_Y, G.BOTTOM_W, G.BAR_H, ink)
    }
}

// ─── WEEK · the opening ─────────────────────────────────────────────────────────────────────────
// A five-day timetable, one column per weekday. Each card knows which piece of 工 it becomes:
// the morning cards make the top bar, the afternoon ones the bottom bar, and the vermilion card
// (the class in progress) is stretched into the stem.
private class Card(val column: Int, val row: Int, val rows: Int, val target: Int, val segment: Int)

private val Week = listOf(
    Card(0, 0, 2, 0, 0), Card(1, 1, 1, 0, 1), Card(3, 0, 1, 0, 2), Card(4, 1, 2, 0, 3),
    Card(2, 1, 2, 1, 0),
    Card(0, 3, 1, 2, 0), Card(1, 3, 2, 2, 1), Card(2, 4, 1, 2, 2), Card(3, 2, 2, 2, 3), Card(4, 4, 1, 2, 4),
)

private fun Pen.week(t: Float) {
    val colW = 10.6f; val rowH = 11f; val cardW = 9.2f
    val merge = Emphasized.transform(span(t, 930f, 220f))
    // The last moment: everything pulls together a hair and lets go, like a latch clicking shut.
    val click = knock(t, 1150f)
    val s = 1 - .035f * click
    scope.withTransform({ scale(s, s, Offset(x(54f), y(54f))) }) {
        Week.forEachIndexed { index, card ->
            // 1 · the week appears, Monday first, each day top to bottom.
            val pop = span(t, card.column * 55f + card.row * 18f, 280f)
            val grow = Back.transform(pop)
            // 2 · the cards fly to their places in 工, one after another.
            val fly = Fly.transform(span(t, 470f + index * 28f, 470f))
            val fromX = 27f + card.column * colW + (colW - cardW) / 2
            val fromY = 26f + card.row * rowH
            val fromH = card.rows * rowH - 1.6f
            val (toX, toY, toW, toH) = when (card.target) {
                0 -> listOf(G.TOP_X + card.segment * G.TOP_W / 4, G.TOP_Y, G.TOP_W / 4, G.BAR_H)
                1 -> listOf(G.STEM_X, G.STEM_Y, G.STEM_W, G.STEM_H)
                else -> listOf(G.BOTTOM_X + card.segment * G.BOTTOM_W / 5, G.BOTTOM_Y, G.BOTTOM_W / 5, G.BAR_H)
            }
            // While they are separate cards a hairline gap shows between them; it closes as they merge.
            val gap = if (card.target == 1) 0f else .9f * (1 - merge)
            val w = lerp(cardW, toW - gap, fly) * grow
            val h = lerp(fromH, toH, fly) * grow
            val cx = lerp(fromX + cardW / 2, toX + toW / 2, fly)
            val cy = lerp(fromY + fromH / 2, toY + toH / 2, fly)
            val color = if (card.target == 1) red else ink
            // The week is drawn softly, as on paper; it darkens into ink as it becomes the mark.
            val alpha = (if (card.target == 1) 1f else lerp(.28f, 1f, fly)) * span(t, card.column * 55f + card.row * 18f, 120f)
            val radius = if (card.target == 1) G.R else lerp(2.6f, 2f, fly)
            tile(cx - w / 2, cy - h / 2, w, h, color, alpha, radius)
        }
        // 3 · the joins vanish: each bar becomes one tile with the mark's own rounded ends.
        if (merge > 0f) {
            tile(G.TOP_X, G.TOP_Y, G.TOP_W, G.BAR_H, ink, merge)
            tile(G.BOTTOM_X, G.BOTTOM_Y, G.BOTTOM_W, G.BAR_H, ink, merge)
            tile(G.STEM_X, G.STEM_Y, G.STEM_W, G.STEM_H, red)
        }
    }
}

// ─── SIGNAL · campus login ──────────────────────────────────────────────────────────────────────
// Two Wi-Fi arcs and a dot. The arcs straighten into the bars — the outer one rising into the top,
// the inner one sinking to the bottom — while the dot climbs through the inner arc and stretches
// into the stem. Then it turns back into a signal and searches again.
private fun Pen.signal(t: Float) {
    val l = t % SIGNAL_MS
    val m = when {
        l < 700f -> 0f
        l < 1150f -> Morph.transform(span(l, 700f, 450f))
        l < 1650f -> 1f
        l < 2100f -> 1 - Morph.transform(span(l, 1650f, 450f))
        else -> 0f
    }
    // Searching: the arcs light up from the dot outward.
    fun search(i: Int) = if (m > 0f) 1f else .3f + .7f * (knock(l, 80f + i * 170f).coerceAtLeast(0f) +
        (if (l > 80f + i * 170f + 120f) 1f else 0f)).coerceAtMost(1f)
    val cx = 54f; val cy = 80f; val half = (PI / 4.1).toFloat()
    fun arc(radius: Float, toY: Float, toLength: Float, alpha: Float) {
        val stroke = lerp(8.5f, G.BAR_H, m)
        val path = Path()
        for (i in 0..24) {
            val f = i / 24f * 2 - 1
            val ax = cx + radius * sin(half * f); val ay = cy - radius * cos(half * f)
            val bx = cx + f * (toLength - G.BAR_H) / 2; val by = toY
            val px = lerp(ax, bx, m); val py = lerp(ay, by, m)
            if (i == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py))
        }
        scope.drawPath(path, ink, alpha = alpha * (1 - span(m, .9f, .1f)),
            style = Stroke(stroke * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (m > .9f) tile(54f - toLength / 2, toY - G.BAR_H / 2, toLength, G.BAR_H, ink, span(m, .9f, .1f) * alpha)
    }
    arc(40f, G.TOP_Y + G.BAR_H / 2, G.TOP_W, search(1))
    arc(23f, G.BOTTOM_Y + G.BAR_H / 2, G.BOTTOM_W, search(0))
    // The dot rises through the inner arc, a little to one side and back, and becomes the stem.
    val dy = lerp(78f, G.STEM_Y + G.STEM_H / 2, m)
    val sway = 5f * sin(PI * m).toFloat()
    val w = lerp(12f, G.STEM_W, m); val h = lerp(12f, G.STEM_H, m)
    val beat = if (m >= 1f) knock(l, 1250f) else 0f
    tile(54f + sway - w / 2, dy - h / 2 - beat, w, h + 2 * beat, red, radius = lerp(6f, G.R, m))
}

// ─── BOUNCE · any other wait ────────────────────────────────────────────────────────────────────
// "Now" as a drop of vermilion between the day's start and its end. It drips from the top bar —
// stretching, pinching off — falls faster and faster, splats on the bottom bar, which gives under it,
// springs back up, and is drawn onto the top bar again, nudging it.
private fun Pen.bounce(t: Float) {
    val l = t % BOUNCE_MS
    val r = 6f
    val topEdge = G.TOP_Y + G.BAR_H; val bottomEdge = G.BOTTOM_Y
    val high = topEdge + r; val low = bottomEdge - r
    val impact = knock(l, 520f); val attach = knock(l, 1180f)
    var cy: Float; var rx = r; var ry = r
    when {
        l < 520f -> { // falling: gravity, and stretching with speed
            val f = span(l, 0f, 520f)
            cy = lerp(high, low, f * f)
            val speed = f
            rx = r * (1 - .16f * speed); ry = r * (1 + .32f * speed)
            cy = cy.coerceAtMost(bottomEdge - ry)
        }
        l < 700f -> { // flattened on the bottom bar
            val squash = knock(l, 520f).coerceAtLeast(0f)
            rx = r * (1 + .38f * squash); ry = r * (1 - .36f * squash)
            cy = bottomEdge - ry + 1.4f * impact
        }
        l < 1180f -> { // springing back up, slowing
            val f = span(l, 700f, 480f); val e = 1 - (1 - f) * (1 - f)
            cy = lerp(low, high, e)
            rx = r * (1 - .1f * (1 - f)); ry = r * (1 + .2f * (1 - f))
        }
        else -> { // caught by the top bar, pressed flat against it
            val squash = knock(l, 1180f).coerceAtLeast(0f)
            rx = r * (1 + .25f * squash); ry = r * (1 - .25f * squash)
            cy = topEdge + ry - 1.1f * attach
        }
    }
    // The faint track of the stem: where "now" lives.
    tile(G.STEM_X, G.STEM_Y, G.STEM_W, G.STEM_H, red, .12f)
    tile(G.TOP_X, G.TOP_Y - 1.1f * attach, G.TOP_W, G.BAR_H, ink)
    val sink = 1.4f * impact
    tile(G.BOTTOM_X - .8f * impact.coerceAtLeast(0f), G.BOTTOM_Y + sink, G.BOTTOM_W + 1.6f * impact.coerceAtLeast(0f),
        G.BAR_H - .8f * impact.coerceAtLeast(0f), ink)
    // A neck of liquid while the drop is still close to the top bar, leaving or arriving.
    val gap = cy - ry - topEdge
    if (gap < 6f) {
        val pull = (1 - gap / 6f).coerceIn(0f, 1f)
        val base = 5f + 6f * pull; val waist = 1.4f + 3.4f * pull
        val top = topEdge - .5f; val joint = cy - ry * .4f; val mid = (top + joint) / 2
        val neck = Path().apply {
            moveTo(x(54f - base / 2), y(top))
            quadraticTo(x(54f - waist / 2), y(mid), x(54f - rx * .8f), y(joint))
            lineTo(x(54f + rx * .8f), y(joint))
            quadraticTo(x(54f + waist / 2), y(mid), x(54f + base / 2), y(top))
            close()
        }
        scope.drawPath(neck, red)
    }
    ellipse(54f, cy, rx, ry, red)
}

/** The mark at a moment of one of its animations; with null [time] it is at rest. */
@Composable
internal fun BrandMark(modifier: Modifier = Modifier, motion: MarkMotion = MarkMotion.BOUNCE, time: Float? = null,
    ink: Color = gxz.ink, red: Color = gxz.accent) {
    Canvas(modifier) {
        val pen = Pen(this, ink, red)
        when {
            time == null -> pen.rest()
            motion == MarkMotion.WEEK -> if (time >= WEEK_MS) pen.rest() else pen.week(time)
            motion == MarkMotion.SIGNAL -> pen.signal(time)
            else -> pen.bounce(time)
        }
    }
}

/**
 * The mark, animated: [MarkMotion.WEEK] plays once and rests; the others loop as loading indicators.
 * With system animations off it is simply the mark at rest.
 */
@Composable
internal fun BrandLoader(modifier: Modifier = Modifier, motion: MarkMotion = MarkMotion.BOUNCE,
    ink: Color = gxz.ink, red: Color = gxz.accent) {
    val loops = motion != MarkMotion.WEEK
    val described = if (loops) modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate } else modifier
    if (LocalReducedMotion.current) { BrandMark(described, motion, null, ink, red); return }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        val start = withInfiniteAnimationFrameMillis { it }
        while (loops || time < WEEK_MS) withInfiniteAnimationFrameMillis { time = (it - start).toFloat() }
    }
    BrandMark(described, motion, time, ink, red)
}
