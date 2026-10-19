package cn.aust.mytimetable

import org.junit.Assert.*
import org.junit.Test

class TimelineScaleTest {
    @Test fun endpointsAndLunchCompression() {
        assertEquals(0f, timelineFraction(0), 0.00001f)
        assertEquals(1f, timelineFraction(840), 0.00001f)
        val hour = timelineFraction(60)
        assertEquals(hour * 1.3f, timelineFraction(360) - timelineFraction(240), 0.00001f)
        assertEquals(hour, timelineFraction(420) - timelineFraction(360), 0.00001f)
    }
    @Test fun everyMinuteRemainsVisibleAndOrderedIncludingLunchCrossingCourses() {
        for (minute in 0 until 840) assertTrue(timelineFraction(minute + 1) > timelineFraction(minute))
        assertEquals((timelineFraction(240) + timelineFraction(360)) / 2, timelineFraction(300), 0.00001f)
    }
}
