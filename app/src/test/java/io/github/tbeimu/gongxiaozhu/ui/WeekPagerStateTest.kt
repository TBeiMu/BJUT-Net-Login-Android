package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeekPagerStateTest {
    private fun TestScope.pager(initial: Int = 2, count: Int = 6, selected: (Int) -> Unit = {}): WeekPagerState {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return WeekPagerState(initial, count, CoroutineScope(coroutineContext + clock), selected).apply { resize(400f) }
    }

    @Test fun releaseCommitsImmediatelyWhileAnimationStillHasDistanceToTravel() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(selected = selected::add)
        pager.beginGesture(); pager.dragBy(-140f)
        assertEquals(2, pager.week)
        assertEquals(2.35f, pager.visualPosition, .001f)
        assertTrue(selected.isEmpty())
        pager.endGesture(0f)
        assertEquals(3, pager.week)
        assertEquals(listOf(3), selected)
        assertEquals(2.35f, pager.visualPosition, .001f)
        assertTrue(pager.settling)
        advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun slowReleaseUsesQuarterPageAndShortDragReturnsWithoutSelecting() = runTest {
        for ((distance, expected) in listOf(-60f to 2, -100f to 3, 60f to 2, 100f to 1)) {
            val selected = mutableListOf<Int>()
            val pager = pager(selected = selected::add)
            pager.beginGesture(); pager.dragBy(distance); pager.endGesture(0f)
            assertEquals(expected, pager.week)
            assertEquals(if (expected == 2) emptyList<Int>() else listOf(expected), selected)
            advanceUntilIdle()
            assertEquals(expected.toFloat(), pager.visualPosition, .001f)
        }
    }

    @Test fun fastFlingUsesDensityAdjustedThresholdAndReleaseDirection() = runTest {
        for ((speed, expected) in listOf(-374f to 2, -375f to 3, 375f to 1)) {
            val pager = pager().apply { resize(400f, 375f) }
            pager.beginGesture(); pager.dragBy(-20f); pager.endGesture(speed)
            assertEquals(expected, pager.week)
            advanceUntilIdle()
        }
    }

    @Test fun manyImmediateFlicksKeepTouchedPositionAndAdvanceExactlyOnePerRelease() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(initial = 1, count = 9, selected = selected::add)
        repeat(6) { n ->
            val before = pager.visualPosition
            pager.beginGesture()
            assertEquals(before, pager.visualPosition, .001f)
            pager.dragBy(-20f)
            assertEquals(before + .05f, pager.visualPosition, .001f)
            pager.endGesture(-1500f)
            assertEquals(n + 2, pager.week)
        }
        assertEquals((2..7).toList(), selected)
        advanceUntilIdle()
        assertEquals(7f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun retouchPausesAnimationAndEachReverseFlickHasItsOwnTarget() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(-1000f)
        advanceTimeBy(64)
        val before = pager.visualPosition
        pager.beginGesture()
        advanceTimeBy(500)
        assertEquals(before, pager.visualPosition, .001f)
        pager.dragBy(30f); pager.endGesture(1000f)
        assertEquals(2, pager.week)
        advanceUntilIdle()
        assertEquals(2f, pager.visualPosition, .001f)
    }

    @Test fun retouchWithoutMovementKeepsAcceptedTarget() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        advanceTimeBy(48)
        pager.beginGesture(); pager.endGesture(1000f)
        assertEquals(3, pager.week)
        advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
    }

    @Test fun longDragStillSelectsOnlyOneNeighborAndEdgesNeverWrap() = runTest {
        for (initial in 1..5) for (direction in listOf(-1, 1)) {
            val pager = pager(initial, 5)
            pager.beginGesture(); repeat(10) { pager.dragBy(direction * 400f) }; pager.endGesture(direction * 2000f)
            val expected = (initial - direction).coerceIn(1, 5)
            assertEquals(expected, pager.week)
            advanceUntilIdle()
            assertEquals(expected.toFloat(), pager.visualPosition, .001f)
        }
    }

    @Test fun edgeResistanceIsBoundedAndRetouchDoesNotJumpDuringRebound() = runTest {
        val pager = pager(1, 1)
        pager.beginGesture(); pager.dragBy(50f)
        assertEquals(.975f, pager.visualPosition, .001f)
        pager.dragBy(500f)
        assertEquals(.94f, pager.visualPosition, .001f)
        pager.dragBy(-120f)
        assertEquals(1f, pager.visualPosition, .001f)
        pager.dragBy(100f); pager.endGesture(0f)
        advanceTimeBy(48)
        val before = pager.visualPosition
        pager.beginGesture()
        assertEquals(before, pager.visualPosition, .001f)
        pager.dragBy(-1f)
        assertTrue(pager.visualPosition > before)
        assertTrue(pager.visualPosition < before + .005f)
        pager.endGesture(0f); advanceUntilIdle()
        assertEquals(1f, pager.visualPosition, .001f)
    }

    @Test fun reversalBeforeReleaseUsesNetGestureDistance() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.dragBy(140f); pager.endGesture(0f)
        advanceUntilIdle()
        assertEquals(2, pager.week)
        assertFalse(pager.moving)
    }

    @Test fun touchCancellationDiscardsUnreleasedDragButKeepsEarlierAcceptedWeek() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(selected = selected::add)
        pager.beginGesture(); pager.dragBy(-140f)
        pager.interactions.emit(DragInteraction.Cancel(DragInteraction.Start()))
        pager.endGesture(0f)
        assertEquals(2, pager.week)
        assertTrue(selected.isEmpty())
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        pager.beginGesture(); pager.dragBy(-140f)
        pager.interactions.tryEmit(DragInteraction.Cancel(DragInteraction.Start()))
        pager.endGesture(0f); advanceUntilIdle()
        assertEquals(3, pager.week)
        assertEquals(3f, pager.visualPosition, .001f)
        assertEquals(listOf(3), selected)
    }

    @Test fun cancelledAnimationCannotOverrideExplicitSelectionOrNewDrag() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        advanceTimeBy(48)
        pager.select(5)
        pager.beginGesture(); pager.dragBy(30f); pager.endGesture(1000f)
        advanceUntilIdle()
        assertEquals(4, pager.week)
        assertEquals(4f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun resizeCancelAndDisposalRetainOnlyReleasedSelection() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.resize(500f)
        assertEquals(2f, pager.visualPosition, .001f)
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        assertEquals(3, pager.week)
        pager.cancel(); advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
        pager.beginGesture(); pager.dragBy(-140f); pager.dispose()
        pager.endGesture(-1000f); pager.select(5); advanceUntilIdle()
        assertEquals(3, pager.week)
        assertFalse(pager.moving)
    }
}
