package cn.aust.mytimetable.school

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SchoolApiTest {
    @Test fun cancelledLoginDoesNotSendPasswordAfterCaptchaResponse() {
        val calls = mutableListOf<String>()
        var cancelled = false
        val api = SchoolApi { path, _, _ ->
            calls.add(path)
            cancelled = true
            JsonParser.parseString("""{"enable":false}""")
        }
        assertThrows(SchoolFailure::class.java) {
            api.login("student", "dummy") { if (cancelled) throw SchoolFailure("操作已取消。") }
        }
        assertEquals(listOf("/verify-code"), calls)
    }

    @Test fun captchaStopsBeforeAnyPasswordRequest() {
        val calls = mutableListOf<String>()
        val api = SchoolApi { path, _, _ ->
            calls.add(path)
            JsonParser.parseString("""{"enable":true}""")
        }
        assertThrows(SchoolFailure::class.java) { api.login("test", "not-a-real-password") }
        assertEquals(listOf("/verify-code"), calls)
    }

    @Test fun unknownCaptchaStateFailsClosed() {
        for (config in listOf("{}", """{"enable":"false"}""", "null")) {
            val calls = mutableListOf<String>()
            val api = SchoolApi { path, _, _ -> calls.add(path); JsonParser.parseString(config) }
            assertThrows(SchoolFailure::class.java) { api.login("test", "dummy") }
            assertEquals(listOf("/verify-code"), calls)
        }
    }

    @Test fun ordinaryLoginUsesVerifiedFieldsAndDoesNotRetryErrors() {
        val calls = mutableListOf<String>()
        val api = SchoolApi { path, fields, secret ->
            calls.add(path)
            assertEquals(SchoolProtocol.BOOTSTRAP_SECRET, secret)
            if (path == "/verify-code") JsonParser.parseString("""{"enable":false}""") else {
                assertEquals(mapOf("user_code" to "student", "passwd" to "cGFzcw=="), fields)
                throw SchoolFailure("拒绝")
            }
        }
        assertThrows(SchoolFailure::class.java) { api.login("student", "pass") }
        assertEquals(listOf("/verify-code", "/login"), calls)
    }

    @Test fun requestUsesSelectedBusinessSemesterAndExactWeekRange() {
        val api = SchoolApi { path, fields, secret ->
            assertEquals("/student/course/schedule/get-course-tables", path)
            assertEquals("test-secret", secret)
            assertEquals(mapOf("token" to "test-token", "biz_type_id" to "b2", "semester_id" to "s3",
                "start_date" to "2026-09-07", "end_date" to "2026-09-13"), fields)
            JsonParser.parseString("[]")
        }
        val semester = Semester("s3", "test", LocalDate.parse("2026-08-31"), LocalDate.parse("2027-01-17"), DayOfWeek.MONDAY)
        val preview = api.week(SchoolSession("test-token", "test-secret", "0"), BusinessType("b2", "测试"), semester, 2)
        assertEquals(2, preview.week)
        assertTrue(preview.courses.isEmpty())
        assertEquals(1, api.initialWeek(semester, semester.start.minusDays(1)))
        assertEquals(20, api.initialWeek(semester, semester.end.plusDays(1)))
    }
    @Test fun historicalDownloadChecksSelectedSemesterWithoutReplacingCurrentIdentity() {
        val history = Semester("old", "2025 学年 · 第 1 学期", LocalDate.parse("2025-09-01"), LocalDate.parse("2025-09-07"), DayOfWeek.MONDAY)
        val api = SchoolApi { path, fields, _ ->
            if (path.endsWith("get-course-tables")) {
                assertEquals("old", fields["semester_id"])
                JsonParser.parseString("[]")
            } else JsonParser.parseString("""{"cur_semester_id":"new","semesters":[
                {"id":"old","year":"2025","name":"1","start_date":"2025-09-01","end_date":"2025-09-07","week_start_day":"1"},
                {"id":"new","year":"2026","name":"1","start_date":"2026-09-01","end_date":"2026-09-07","week_start_day":"1"}
            ]}""")
        }
        val session = SchoolSession("fixture", "fixture", "0")
        val business = BusinessType("b", "fixture")
        assertEquals(history, api.fullSemester(session, business, history, requireCurrent = false).semester)
        assertThrows(SchoolFailure::class.java) { api.fullSemester(session, business, history) }
    }

}
