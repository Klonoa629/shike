package cn.aust.mytimetable.school

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class ScheduleSlot(val weeks: Set<Int>, val day: DayOfWeek, val start: LocalTime, val end: LocalTime)

object ManualSchedule {
    fun expand(semester: Semester, name: String, teacher: String, location: String,
        notes: String, slots: List<ScheduleSlot>): List<SchoolCourse> {
        if (slots.isEmpty() || slots.any { it.weeks.isEmpty() }) throw SchoolFailure("每组安排至少选择一周。")
        val lesson = UUID.randomUUID().toString()
        val first = semester.start.with(TemporalAdjusters.previousOrSame(semester.weekStart))
        val courses = slots.flatMap { slot -> slot.weeks.sorted().map { week ->
            if (week !in 1..semester.weekCount) throw SchoolFailure("周次超出本学期。")
            SchoolCourse(lesson, name.trim(), first.plusWeeks((week - 1).toLong())
                .plusDays(Math.floorMod(slot.day.value - semester.weekStart.value, 7).toLong()),
                slot.start, slot.end, listOf(teacher.trim()), listOf(location.trim()).filter { it.isNotEmpty() },
                UUID.randomUUID().toString(), notes.trim(), true)
        } }
        CourseRules.validate(courses, DateRange(semester.start, semester.end))
        return courses
    }
}
