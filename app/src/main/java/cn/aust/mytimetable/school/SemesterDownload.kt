package cn.aust.mytimetable.school

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ExecutionException

data class SemesterDownload(
    val business: BusinessType,
    val semester: Semester,
    val courses: List<SchoolCourse>,
    val completedWeeks: Set<Int>,
) {
    fun validate() {
        if (completedWeeks != (1..semester.weekCount).toSet()) {
            throw SchoolFailure("未能获取完整学期，原有课表已保留，请重新同步。")
        }
        CourseRules.validate(courses, DateRange(semester.start, semester.end))
    }

    companion object {
        // At most two weekly requests in flight; never queue an entire semester.
        fun collectConcurrent(
            business: BusinessType,
            semester: Semester,
            fetch: (Int) -> WeekPreview,
            checkActive: () -> Unit = {},
            progress: (Int, Int) -> Unit = { _, _ -> },
        ): SemesterDownload {
            val pool = Executors.newFixedThreadPool(2)
            val pending = mutableMapOf<Int, Future<WeekPreview>>()
            fun submit(number: Int) {
                checkActive()
                pending[number] = pool.submit<WeekPreview> {
                    checkActive()
                    fetch(number).also { checkActive() }
                }
            }
            try {
                for (number in 1..minOf(2, semester.weekCount)) submit(number)
                return collect(business, semester, fetch = { number ->
                    val response = try { pending.getValue(number).get() }
                    catch (e: ExecutionException) { throw (e.cause ?: e) }
                    checkActive()
                    if (response.business != business || response.semester != semester || response.week != number) {
                        throw SchoolFailure("学校返回的课表周次有误，请重新同步。")
                    }
                    CourseRules.validate(response.courses, semester.range(number))
                    pending.remove(number)
                    if (number + 2 <= semester.weekCount) submit(number + 2)
                    response
                }, checkActive = checkActive, progress = progress)
            } finally {
                pending.values.forEach { it.cancel(true) }
                pool.shutdownNow()
            }
        }

        fun collect(
            business: BusinessType,
            semester: Semester,
            fetch: (Int) -> WeekPreview,
            checkActive: () -> Unit = {},
            progress: (Int, Int) -> Unit = { _, _ -> },
        ): SemesterDownload {
            val courses = mutableListOf<SchoolCourse>()
            val completed = mutableSetOf<Int>()
            for (week in 1..semester.weekCount) {
                checkActive()
                progress(week, semester.weekCount)
                val response = fetch(week)
                checkActive()
                if (response.business != business || response.semester != semester || response.week != week) {
                    throw SchoolFailure("学校返回的课表与所选学期不一致，请重新同步。")
                }
                CourseRules.validate(response.courses, semester.range(week))
                courses.addAll(response.courses)
                completed.add(week)
            }
            return SemesterDownload(business, semester,
                courses.sortedWith(compareBy({ it.date }, { it.start })), completed).also { it.validate() }
        }
    }
}

/** One linearization point for logout and persistence; never hold the lock across network requests. */
class SessionEpoch {
    @Volatile private var value = 0L
    fun current(): Long = value
    @Synchronized fun invalidate(): Long = ++value
    fun check(ticket: Long) {
        if (ticket != value) throw SchoolFailure("操作已取消。")
    }
    @Synchronized fun <T> commit(ticket: Long, action: () -> T): T {
        check(ticket)
        return action()
    }
}
