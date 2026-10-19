package cn.aust.mytimetable.school

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ManualScheduleTest {
    private val semester = Semester("test", "test", LocalDate.parse("2026-08-31"), LocalDate.parse("2027-01-17"), DayOfWeek.MONDAY)
    private fun slot(weeks: Set<Int> = setOf(4), day: DayOfWeek = DayOfWeek.FRIDAY, start: String = "18:30", end: String = "21:30") =
        ScheduleSlot(weeks, day, LocalTime.parse(start), LocalTime.parse(end))
    private fun expand(vararg slots: ScheduleSlot) = ManualSchedule.expand(semester, "课程设计", "老师", "", "带电脑", slots.toList())

    @Test fun differentWeeksAndMultipleTimesKeepTheirOwnSchedules() {
        val courses = expand(slot(), slot(day = DayOfWeek.SATURDAY, start = "08:00", end = "11:00"),
            slot(day = DayOfWeek.SATURDAY, start = "14:00", end = "17:00"), slot(setOf(5), start = "14:00", end = "17:00"))
        assertEquals(4, courses.size)
        assertEquals(listOf("2026-09-25", "2026-09-26", "2026-09-26", "2026-10-02"), courses.map { it.date.toString() })
        assertEquals(4, courses.map { it.localId }.toSet().size)
        assertEquals(1, courses.map { it.lessonId }.toSet().size)
        assertTrue(courses.all { it.rooms.isEmpty() && it.notes == "带电脑" && it.manual })
    }
    @Test fun multipleWeeksExpandToIndependentOccurrences() {
        val courses = expand(slot(setOf(7, 8), DayOfWeek.SATURDAY))
        assertEquals(2, courses.size)
        assertNotEquals(courses[0].localId, courses[1].localId)
        assertEquals(courses[0].date.plusWeeks(1), courses[1].date)
    }
    @Test fun invalidDurationBoundsOverlapAndEmptyWeeksAreRejected() {
        listOf(arrayOf(slot(start = "18:30", end = "19:29")), arrayOf(slot(start = "07:59", end = "09:00")),
            arrayOf(slot(end = "22:01")), arrayOf(slot(emptySet())), arrayOf(slot(setOf(21))), arrayOf(slot(), slot())).forEach {
            assertThrows(SchoolFailure::class.java) { expand(*it) }
        }
    }
    @Test fun adjacentCoursesAndExactlyOneHourAreAllowed() {
        assertEquals(2, expand(slot(start = "20:00", end = "21:00"), slot(start = "21:00", end = "22:00")).size)
    }
    @Test fun courseAndTeacherRequiredButLocationOptional() {
        assertThrows(SchoolFailure::class.java) { ManualSchedule.expand(semester, "", "老师", "", "", listOf(slot())) }
        assertThrows(SchoolFailure::class.java) { ManualSchedule.expand(semester, "实验", " ", "", "", listOf(slot())) }
    }
    @Test fun partialFirstWeekCannotCreateDatesBeforeSemester() {
        assertThrows(SchoolFailure::class.java) { ManualSchedule.expand(semester.copy(start = semester.start.plusDays(2)), "实验", "老师", "", "", listOf(slot(setOf(1), DayOfWeek.MONDAY))) }
    }
}
