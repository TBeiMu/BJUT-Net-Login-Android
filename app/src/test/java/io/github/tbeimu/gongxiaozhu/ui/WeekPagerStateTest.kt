package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WeekPagerStateTest {
    @Test fun dragDoesNotCommitWeekBeforeRelease() = runTest {
        val selected = mutableListOf<Int>()
        val pager = WeekPagerState(2, 5, selected::add)
        pager.resize(400f)
        val session = pager.session
        session.beginGesture()
        session.anchors.dispatchRawDelta(-150f)
        assertEquals(2, pager.week)
        assertEquals(-.375f, session.fraction, .001f)
        assertTrue(selected.isEmpty())
        session.overscroll.applyToFling(Velocity(0f, -300f)) {
            session.anchors.dispatchRawDelta(-250f); Velocity.Zero
        }
        assertEquals(3, pager.week)
        assertEquals(listOf(3), selected)
        assertEquals(0f, pager.session.offset, .001f)
    }

    @Test fun shortDragReturningToOriginDoesNotNotifyOrChangeWeek() = runTest {
        val selected = mutableListOf<Int>()
        val pager = WeekPagerState(2, 5, selected::add).apply { resize(400f) }
        val session = pager.session
        session.beginGesture(); session.anchors.dispatchRawDelta(60f)
        session.overscroll.applyToFling(Velocity.Zero) { session.anchors.dispatchRawDelta(-60f); Velocity.Zero }
        assertEquals(2, pager.week)
        assertTrue(selected.isEmpty())
        assertEquals(0f, pager.session.offset, .001f)
    }

    @Test fun zeroReleaseKeepsThresholdDirectionWithoutBecomingAFastFling() = runTest {
        for (distance in listOf(-60f, -140f, 60f, 140f)) {
            val pager = WeekPagerState(2, 5) {}.apply { resize(400f) }
            val session = pager.session
            session.beginGesture(); session.anchors.dispatchRawDelta(distance)
            session.overscroll.applyToFling(Velocity.Zero) { velocity ->
                assertTrue(velocity.y * distance > 0)
                assertTrue(kotlin.math.abs(velocity.y) < 1f)
                session.anchors.dispatchRawDelta(-distance)
                Velocity.Zero
            }
        }
    }

    @Test fun anchorsLimitAWholeGestureToOneNeighborAndRespectSemesterEdges() {
        for (initial in 1..5) {
            val pager = WeekPagerState(initial, 5) {}.apply { resize(400f) }
            val session = pager.session
            assertEquals(if (initial in 2..4) 3 else 2, session.anchors.anchors.size)
            assertEquals(if (initial < 5) -400f else 0f, session.anchors.anchors.minPosition(), .01f)
            assertEquals(if (initial > 1) 400f else 0f, session.anchors.anchors.maxPosition(), .01f)
        }
    }

    @Test fun boundaryMovementHasResistanceWithoutCreatingAnInvalidWeek() {
        val pager = WeekPagerState(1, 1) {}.apply { resize(400f) }
        val session = pager.session
        fun scroll(delta: Float) = session.overscroll.applyToScroll(Offset(0f, delta), NestedScrollSource.UserInput) {
            Offset(0f, session.anchors.dispatchRawDelta(it.y))
        }
        scroll(50f)
        assertEquals(10f, session.offset, .001f)
        scroll(500f)
        assertEquals(24f, session.offset, .001f)
        scroll(-120f)
        assertEquals(0f, session.offset, .001f)
        assertEquals(1, pager.week)
        assertEquals(1, session.anchors.anchors.size)
    }

    @Test fun cancelledAndOldCallbacksCannotOverrideExplicitWeekSelection() {
        val selected = mutableListOf<Int>()
        val pager = WeekPagerState(2, 5, selected::add).apply { resize(400f) }
        val old = pager.session
        old.beginGesture(); old.anchors.dispatchRawDelta(-400f); old.endGesture()
        pager.select(5)
        pager.finish(old, old.epoch)
        old.cancelGesture()
        assertEquals(5, pager.week)
        assertEquals(listOf(5), selected)
        assertEquals(0f, pager.session.offset, .001f)
    }

    @Test fun geometryChangesCancellationAndDisposalRetainOnlyCommittedWeek() {
        val selected = mutableListOf<Int>()
        val pager = WeekPagerState(2, 5, selected::add).apply { resize(400f) }
        pager.session.anchors.dispatchRawDelta(-150f)
        pager.resize(500f)
        assertEquals(2, pager.week)
        assertEquals(0f, pager.session.offset, .001f)
        pager.session.anchors.dispatchRawDelta(100f); pager.cancel()
        assertEquals(0f, pager.session.offset, .001f)
        val old = pager.session
        old.anchors.dispatchRawDelta(-500f)
        pager.dispose(); pager.finish(old, old.epoch); pager.select(3)
        assertEquals(2, pager.week)
        assertTrue(selected.isEmpty())
    }
}
