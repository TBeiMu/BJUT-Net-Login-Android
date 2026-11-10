package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.ui.weekDragDirection
import org.junit.Assert.*
import org.junit.Test

class SchedulePresentationTest {
    @Test fun downwardSwipesAdvanceAndUpwardSwipesGoBack() {
        assertEquals(1, weekDragDirection(50f))
        assertEquals(-1, weekDragDirection(-50f))
        assertEquals(0, weekDragDirection(0f))
    }
}
