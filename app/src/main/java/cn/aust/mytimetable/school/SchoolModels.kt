package cn.aust.mytimetable.school

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

open class SchoolFailure(message: String) : Exception(message)
class SchoolSessionExpired : SchoolFailure("登录已失效，请重新登录。")

// Deliberately not data classes: generated toString must not disclose credentials.
class SchoolSession(val token: String, val secret: String, val identity: String)
class SavedLogin(val account: String, val password: String, val session: SchoolSession)

data class BusinessType(val id: String, val name: String)

data class Semester(
    val id: String,
    val label: String,
    val start: LocalDate,
    val end: LocalDate,
    val weekStart: DayOfWeek,
) {
    init {
        require(!end.isBefore(start))
        require(ChronoUnit.DAYS.between(start, end) <= 370)
    }

    private val firstWeek = start.with(TemporalAdjusters.previousOrSame(weekStart))
    val weekCount: Int get() = (ChronoUnit.DAYS.between(firstWeek, end) / 7 + 1).toInt()

    fun weekAt(date: LocalDate): Int? = if (date < start || date > end) null else
        (ChronoUnit.DAYS.between(firstWeek, date) / 7 + 1).toInt()

    fun range(week: Int): DateRange {
        require(week in 1..weekCount)
        val first = firstWeek.plusWeeks((week - 1).toLong())
        return DateRange(maxOf(first, start), minOf(first.plusDays(6), end))
    }
}

data class DateRange(val start: LocalDate, val end: LocalDate)

data class SchoolCourse(
    val lessonId: String,
    val name: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val teachers: List<String>,
    val rooms: List<String>,
    val localId: String? = null,
    val notes: String = "",
    val manual: Boolean = false,
)

data class WeekPreview(
    val business: BusinessType,
    val semester: Semester,
    val week: Int,
    val courses: List<SchoolCourse>,
)

object CourseRules {
    fun validate(courses: List<SchoolCourse>, range: DateRange) {
        for (course in courses) {
            if (course.date < range.start || course.date > range.end) {
                throw SchoolFailure("课程日期超出所选学期或周次。")
            }
            if (course.name.isBlank() || course.teachers.isEmpty() || course.teachers.any { it.isBlank() }) {
                throw SchoolFailure("请填写课程名称和教师。")
            }
            if (course.end <= course.start) throw SchoolFailure("「${course.name}」的结束时间应晚于开始时间。")
            if (course.start < LocalTime.of(8, 0) || course.end > LocalTime.of(22, 0))
                throw SchoolFailure("${course.date}「${course.name}」超出 08:00—22:00，请调整上课时间。")
            if (ChronoUnit.MINUTES.between(course.start, course.end) < 60)
                throw SchoolFailure("${course.date}「${course.name}」不足一小时，请调整上课时间。")
        }
        val ordered = courses.sortedWith(compareBy({ it.date }, { it.start }))
        ordered.zipWithNext().forEach { (a, b) ->
            if (a.date == b.date && b.start < a.end) {
                throw SchoolFailure("${a.date}「${a.name}」${a.start}—${a.end} 与「${b.name}」${b.start}—${b.end} 重叠，请核对上课时间。")
            }
        }
    }
}


fun semesterKey(businessId: String, semesterId: String) = "${businessId.length}:$businessId$semesterId"
data class SemesterChoice(val business: BusinessType, val semester: Semester, val saved: Boolean) {
    val key: String get() = semesterKey(business.id, semester.id)
}
data class SemesterCatalog(val currentId: String, val semesters: List<Semester>) {
    val current: Semester get() = semesters.single { it.id == currentId }
}
