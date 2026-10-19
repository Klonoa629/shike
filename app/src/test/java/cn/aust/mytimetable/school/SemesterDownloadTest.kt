package cn.aust.mytimetable.school

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SemesterDownloadTest {
    private val business = BusinessType("test", "测试")
    private val semester = Semester("s", "测试学期", LocalDate.parse("2026-08-31"),
        LocalDate.parse("2027-01-17"), DayOfWeek.MONDAY)
    private fun week(number: Int, empty: Boolean = false) = WeekPreview(business, semester, number,
        if (empty) emptyList() else listOf(SchoolCourse("1", "测试课", semester.range(number).start,
            LocalTime.of(8, 0), LocalTime.of(9, 0), listOf("测试教师"), emptyList())))

    @Test fun readsEveryWeekOnceIncludingSuccessfulEmptyWeeks() {
        val calls = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val result = SemesterDownload.collect(business, semester,
            fetch = { calls.add(it); week(it, it == 10) }, progress = { current, total ->
                assertEquals(20, total); progress.add(current)
            })
        assertEquals((1..20).toList(), calls)
        assertEquals(calls, progress)
        assertEquals(19, result.courses.size)
        assertEquals((1..20).toSet(), result.completedWeeks)
    }

    @Test fun failedWeekStopsAndDoesNotReturnPartialSnapshot() {
        val calls = mutableListOf<Int>()
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collect(business, semester, fetch = {
                calls.add(it)
                if (it == 3) throw SchoolFailure("模拟网络中断")
                week(it)
            })
        }
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test fun missingCoverageWrongWeekAndOutOfWeekCoursesAreRejected() {
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload(business, semester, emptyList(), setOf(1)).validate()
        }
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collect(business, semester, fetch = { week(1) })
        }
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collect(business, semester, fetch = {
                week(it).copy(courses = week(1).courses)
            })
        }
    }

    @Test fun explicitEmptyWholeSemesterRemainsAValidCompleteResult() {
        val result = SemesterDownload.collect(business, semester, fetch = { week(it, empty = true) })
        assertEquals(20, result.completedWeeks.size)
        assertTrue(result.courses.isEmpty())
    }

    @Test fun cancellationAfterResponseStopsBeforeTheNextWeekAndBeforeCommit() {
        val epoch = SessionEpoch()
        val ticket = epoch.current()
        var calls = 0
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collect(business, semester, fetch = {
                calls++
                epoch.invalidate()
                week(it)
            }, checkActive = { epoch.check(ticket) })
        }
        assertEquals(1, calls)
        var persisted = false
        assertThrows(SchoolFailure::class.java) { epoch.commit(ticket) { persisted = true } }
        assertFalse(persisted)
    }

    @Test fun concurrentDownloadUsesTwoReadersAndStillIncludesEmptyWeeks() {
        val firstPair = CountDownLatch(2)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val result = SemesterDownload.collectConcurrent(business, semester, fetch = {
            val count = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, count) }
            try {
                calls.add(it)
                if (it <= 2) { firstPair.countDown(); check(firstPair.await(3, TimeUnit.SECONDS)) }
                week(it, it == 10)
            } finally { active.decrementAndGet() }
        })
        assertEquals(2, peak.get())
        assertEquals((1..20).toList(), calls.sorted())
        assertEquals(19, result.courses.size)
        assertEquals((1..20).toSet(), result.completedWeeks)
    }

    @Test fun concurrentFailureCancelsOtherReaderWithoutReturningPartialData() {
        val secondStarted = CountDownLatch(1)
        val secondStopped = CountDownLatch(1)
        val failure = assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collectConcurrent(business, semester, fetch = {
                if (it == 1) {
                    check(secondStarted.await(3, TimeUnit.SECONDS))
                    throw SchoolFailure("模拟失败")
                }
                try { secondStarted.countDown(); CountDownLatch(1).await(); week(it) }
                finally { secondStopped.countDown() }
            })
        }
        assertEquals("模拟失败", failure.message)
        assertTrue(secondStopped.await(3, TimeUnit.SECONDS))
    }

    @Test fun concurrentDownloadRejectsWrongWeekAndCancelledSession() {
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collectConcurrent(business, semester, fetch = { week(20) })
        }
        val epoch = SessionEpoch()
        val ticket = epoch.current()
        assertThrows(SchoolFailure::class.java) {
            SemesterDownload.collectConcurrent(business, semester, fetch = {
                epoch.invalidate(); week(it)
            }, checkActive = { epoch.check(ticket) })
        }
    }

    @Test fun logoutAndCommitCannotInterleave() {
        val epoch = SessionEpoch()
        val ticket = epoch.current()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val commit = pool.submit {
                epoch.commit(ticket) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val logout = pool.submit<Long> { epoch.invalidate() }
            release.countDown()
            commit.get(3, TimeUnit.SECONDS)
            assertEquals(ticket + 1, logout.get(3, TimeUnit.SECONDS))
            assertThrows(SchoolFailure::class.java) { epoch.commit(ticket) { fail("旧任务不应写入") } }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
