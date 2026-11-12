package io.github.tbeimu.gongxiaozhu.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sign

/**
 * One motion vocabulary for the whole app.
 *
 * Things arrive on a long, decelerating curve and leave quickly; touch responds with springs, so a
 * press feels physical and an interrupted motion never jumps. Siblings arrive as a short wave
 * (Motion.STAGGER apart) rather than all at once. Nothing loops except progress and loading.
 */
internal object Motion {
    /** Default for movement on screen: quick start, long gentle landing. */
    val Emphasized = CubicBezierEasing(.2f, 0f, 0f, 1f)
    /** Arrivals: almost all of the distance is covered at once, the rest settles. */
    val Decelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    /** Departures: leave without drawing attention. */
    val Accelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)

    const val SHORT = 150
    const val MEDIUM = 280
    const val LONG = 440
    /** Delay between siblings in an entrance wave. */
    const val STAGGER = 45

    /** Selections and indicators: settles fast, the faintest overshoot. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = .82f, stiffness = 520f)
    /** Things released by a finger: a visible, friendly bounce. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = .55f, stiffness = 380f)
    /** Large surfaces returning to rest. */
    fun <T> soft(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 200f)
}

/** True when the system animation scale is off; decorative motion then steps aside. */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** Shrinks slightly while pressed and springs back on release. */
internal fun Modifier.pressScale(interaction: InteractionSource, pressedScale: Float = .96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
        if (pressed) spring(stiffness = 900f) else Motion.bouncy(), label = "按压回弹")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** [clickable] with ripple and a press spring. */
internal fun Modifier.springClick(enabled: Boolean = true, role: Role? = null, pressedScale: Float = .96f,
    onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    pressScale(interaction, pressedScale)
        .clickable(interaction, LocalIndication.current, enabled = enabled, role = role, onClick = onClick)
}

/**
 * Rises into place when first composed. Only appearance: it never changes layout, so nothing
 * around it moves. [enabled] is read once; turning it off later does not cut a running entrance.
 */
internal fun Modifier.entrance(delayMillis: Int = 0, distance: Dp = 16.dp, fromScale: Float = 1f,
    enabled: Boolean = true): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val run = remember { enabled && !reduced }
    if (!run) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(Motion.LONG + 80, delayMillis, Motion.Decelerate)) }
    val shift = with(LocalDensity.current) { distance.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = (p * 1.7f).coerceAtMost(1f)
        translationY = (1 - p) * shift
        val s = fromScale + (1 - fromScale) * p
        scaleX = s; scaleY = s
    }
}

/** Pops in with a small overshoot: for marks that confirm something, like a tick or today's date. */
internal fun Modifier.popIn(delayMillis: Int = 0, enabled: Boolean = true): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val run = remember { enabled && !reduced }
    if (!run) return@composed Modifier
    val scale = remember { Animatable(.5f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        launch { alpha.animateTo(1f, tween(120)) }
        scale.animateTo(1f, Motion.bouncy())
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
}

/** A large title that drifts at half speed and dissolves as the page scrolls over it. */
internal fun Modifier.parallaxHeader(scroll: ScrollState): Modifier = composed {
    if (LocalReducedMotion.current) return@composed Modifier
    val fade = with(LocalDensity.current) { 150.dp.toPx() }
    graphicsLayer {
        val y = scroll.value.toFloat()
        translationY = y * .45f
        alpha = (1 - y / fade).coerceIn(0f, 1f)
        val s = 1 - (y / fade).coerceIn(0f, 1f) * .04f
        scaleX = s; scaleY = s
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
    }
}

/** A soft band of light sweeping across, while content is on its way. */
internal fun Modifier.shimmer(active: Boolean, color: Color): Modifier = composed {
    if (!active || LocalReducedMotion.current) return@composed Modifier
    val transition = rememberInfiniteTransition(label = "加载微光")
    val x by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "微光位置")
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, color, Color.Transparent),
            start = Offset(w * x - w * .6f, 0f), end = Offset(w * x, size.height)))
    }
}

/** Rotation in degrees while [active]; 0 otherwise. */
@Composable
internal fun rememberSpin(active: Boolean): Float {
    if (!active || LocalReducedMotion.current) return 0f
    val transition = rememberInfiniteTransition(label = "旋转")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "旋转角度")
    return angle
}

private val Decimal = Regex("""^-?\d{1,12}(?:\.(\d{1,6}))?$""")

/**
 * A figure that rolls from its previous value to the new one, keeping its decimal places.
 * Text that is not a plain number is shown as is, and the last frame is always the exact text.
 */
@Composable
internal fun RollingNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val match = remember(text) { Decimal.matchEntire(text) }
    if (match == null || LocalReducedMotion.current) {
        Text(text, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val target = text.toDouble()
    val decimals = match.groupValues[1].length
    var from by remember { mutableDoubleStateOf(0.0) }
    var to by remember { mutableDoubleStateOf(target) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        from = from + (to - from) * progress.value
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1000, easing = Motion.Decelerate))
    }
    val shown = if (progress.value >= 1f) text
        else String.format(Locale.US, "%.${decimals}f", from + (to - from) * progress.value)
    Text(shown, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Where the user is: a root tab, or a page opened from it. */
internal data class Screen(val tab: Tab, val destination: MyDestination?) {
    val key: String get() = destination?.let { "detail/${it.name}" } ?: "tab/${tab.name}"
}

/**
 * Page changes. Opening a page slides it in from the right over a receding parent; going back
 * reverses it. Between root tabs the new page fades through with a small drift toward the side
 * of the tab that was chosen, so the dock and the page agree about direction.
 */
@Composable
internal fun ScreenHost(screen: Screen, content: @Composable (Screen) -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(screen, Modifier.fillMaxSize(), transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None else screenTransition()
    }, contentKey = { it.key }, label = "页面切换") { content(it) }
}

private fun AnimatedContentTransitionScope<Screen>.screenTransition(): ContentTransform {
    val from = initialState; val to = targetState
    val forward = to.destination != null && from.destination != to.destination
    val back = from.destination != null && to.destination == null
    return when {
        forward -> (slideInHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { it / 3 } +
            fadeIn(tween(Motion.MEDIUM, 40))) togetherWith
            (slideOutHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { -it / 8 } + fadeOut(tween(Motion.SHORT)))
        back -> ((slideInHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { -it / 8 } +
            fadeIn(tween(Motion.MEDIUM, 40))) togetherWith
            (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Accelerate)) { it / 3 } + fadeOut(tween(Motion.MEDIUM))))
            .apply { targetContentZIndex = -1f }
        else -> {
            val direction = (to.tab.ordinal - from.tab.ordinal).toFloat().sign.toInt()
            (fadeIn(tween(220, 70)) + scaleIn(tween(Motion.LONG, easing = Motion.Decelerate), initialScale = .985f) +
                slideInHorizontally(tween(Motion.LONG, easing = Motion.Decelerate)) { direction * it / 14 }) togetherWith
                (fadeOut(tween(90)) + slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Accelerate)) { -direction * it / 24 })
        }
    } using SizeTransform(clip = false)
}
