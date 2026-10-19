package cn.aust.mytimetable.school

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

class SchoolProtocolTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/school/$name")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun envelope(data: String): String = """{"err_code":"00000","business_data":"${Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))}"}"""
    private val range = DateRange(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-25"))

    @Test fun signingMatchesIndependentVectorRegardlessOfInsertionOrder() {
        val fields = linkedMapOf("token" to "token+&=", "semester_id" to "340")
        val signed = SchoolProtocol.signedFields(fields, "test-secret", 1700000000123, 12345)
        assertEquals("D0ABEE161FACBEDD3E9924A5249B43E0", signed["sign"])
        assertEquals(2, fields.size)
        assertEquals(signed, SchoolProtocol.signedFields(fields.toSortedMap(), "test-secret", 1700000000123, 12345))
    }

    @Test fun formPreservesSpecialCharactersAndUnicode() {
        val value = "测试 +&=/"
        val body = SchoolProtocol.form(mapOf("passwd" to value))
        assertEquals(value, URLDecoder.decode(body.substringAfter('='), "UTF-8"))
        assertTrue(body.contains("%2B"))
        assertTrue(body.contains("%26"))
        assertEquals("5a+G56CB", SchoolProtocol.password("密码"))
    }

    @Test fun decodesSanitizedRealWeekWithoutCollapsingRepeatedLessons() {
        val rows = SchoolProtocol.courses(SchoolProtocol.decode(fixture("week-response.json")), range)
        assertEquals(11, rows.size)
        assertEquals(6, rows.map { it.lessonId }.distinct().size)
        assertEquals(LocalDate.parse("2026-10-19"), rows.first().date)
        assertEquals(LocalDate.parse("2026-10-23"), rows.last().date)
        assertTrue(rows.all { it.name.startsWith("测试课程") })
    }

    @Test fun resolvesCurrentSemesterAndWeekBoundaries() {
        val semester = SchoolProtocol.currentSemester(SchoolProtocol.decode(fixture("semester-response.json")))
        assertEquals("340", semester.id)
        assertEquals(20, semester.weekCount)
        assertEquals(7, semester.weekAt(LocalDate.parse("2026-10-15")))
        assertEquals(range, semester.range(8))
        assertNull(semester.weekAt(LocalDate.parse("2026-08-30")))
        assertNull(semester.weekAt(LocalDate.parse("2027-01-18")))
    }

    @Test fun partialFirstAndLastWeekAreClippedToSemesterDates() {
        val semester = Semester("s", "test", LocalDate.parse("2026-09-02"),
            LocalDate.parse("2026-09-15"), DayOfWeek.MONDAY)
        assertEquals(3, semester.weekCount)
        assertEquals(LocalDate.parse("2026-09-02"), semester.range(1).start)
        assertEquals(LocalDate.parse("2026-09-06"), semester.range(1).end)
        assertEquals(LocalDate.parse("2026-09-15"), semester.range(3).end)
        val sunday = semester.copy(weekStart = DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2026-09-05"), sunday.range(1).end)
    }

    @Test fun successfulEmptyArrayIsDifferentFromMissingOrBrokenResponse() {
        assertTrue(SchoolProtocol.courses(SchoolProtocol.decode(envelope("[]")), range).isEmpty())
        for (body in listOf("", "<html>login</html>", "{}", """{"err_code":"00000"}""",
            """{"err_code":"00000","business_data":"broken…"}""", envelope("null"),
            envelope("[] trailing"), envelope("[{unquoted:1}]"))) {
            assertThrows(SchoolFailure::class.java) { SchoolProtocol.decode(body) }
        }
        assertThrows(SchoolFailure::class.java) {
            SchoolProtocol.courses(SchoolProtocol.decode(envelope("{}")), range)
        }
    }

    @Test fun serverErrorDoesNotEchoSensitiveMessageAndDoesNotCountAsSuccess() {
        val failure = assertThrows(SchoolFailure::class.java) {
            SchoolProtocol.decode("""{"err_code":"03000","err_msg":"secret-password-and-token"}""")
        }
        assertFalse(failure.message!!.contains("03000"))
        assertFalse(failure.message!!.contains("业务码"))
        assertFalse(failure.message!!.contains("secret-password"))
    }

    @Test fun missingTeacherRejectsWholeWeekAndMissingRoomIsAllowed() {
        val data = SchoolProtocol.decode(fixture("week-response.json")).asJsonArray
        data[0].asJsonObject.remove("rooms")
        assertEquals(11, SchoolProtocol.courses(data, range).size)
        data[0].asJsonObject.remove("teachers")
        assertThrows(SchoolFailure::class.java) { SchoolProtocol.courses(data, range) }
    }

    private fun course(start: String = "08:00", end: String = "09:00") = SchoolCourse(
        "1", "测试课", range.start, LocalTime.parse(start), LocalTime.parse(end), listOf("教师"), emptyList())

    @Test fun enforcesSixtyMinutesAndEightToTwentyTwoBoundaries() {
        CourseRules.validate(listOf(course()), range)
        CourseRules.validate(listOf(course("21:00", "22:00")), range)
        for ((start, end) in listOf("08:00" to "08:59", "07:59" to "09:00", "21:00" to "22:01",
            "09:00" to "08:00", "08:00" to "08:00")) {
            assertThrows(SchoolFailure::class.java) { CourseRules.validate(listOf(course(start, end)), range) }
        }
    }

    @Test fun permitsTouchingIntervalsButRejectsOverlapAndDuplicates() {
        CourseRules.validate(listOf(course("09:00", "10:00"), course()), range)
        assertThrows(SchoolFailure::class.java) {
            CourseRules.validate(listOf(course(), course("08:59", "10:00")), range)
        }
        assertThrows(SchoolFailure::class.java) { CourseRules.validate(listOf(course(), course()), range) }
        assertThrows(SchoolFailure::class.java) {
            CourseRules.validate(listOf(course().copy(date = range.end.plusDays(1))), range)
        }
    }

    @Test fun malformedTimeAndUnknownSemesterWeekStartAreRejected() {
        val data = SchoolProtocol.decode(fixture("week-response.json")).asJsonArray
        data[0].asJsonObject.addProperty("start_time", "08:00:59")
        assertThrows(SchoolFailure::class.java) { SchoolProtocol.courses(data, range) }
        val semester = SchoolProtocol.decode(fixture("semester-response.json")).asJsonObject
        semester.getAsJsonArray("semesters")[0].asJsonObject.addProperty("week_start_day", 4)
        assertThrows(SchoolFailure::class.java) { SchoolProtocol.currentSemester(semester) }
    }

    @Test fun sessionRequiresStudentIdentityAndSecretsAreNotInToString() {
        val session = SchoolProtocol.session(JsonParser.parseString("""{"token":"dummy-token","secret":"dummy-secret","cur_identity":0}"""))
        assertFalse(session.toString().contains("dummy"))
        assertThrows(SchoolFailure::class.java) {
            SchoolProtocol.session(JsonParser.parseString("""{"token":"t","secret":"s","cur_identity":1}"""))
        }
    }
    @Test fun catalogKeepsAllSemestersAndRejectsAmbiguousIds() {
        val obj = SchoolProtocol.decode(fixture("semester-response.json")).asJsonObject
        val first = obj.getAsJsonArray("semesters")[0].asJsonObject
        val old = first.deepCopy().apply {
            addProperty("id", "previous")
            addProperty("start_date", "2026-02-23")
            addProperty("end_date", "2026-07-05")
        }
        obj.getAsJsonArray("semesters").add(old)
        val catalog = SchoolProtocol.semesterCatalog(obj)
        assertEquals("340", catalog.current.id)
        assertTrue(catalog.semesters.any { it.id == "previous" })
        obj.getAsJsonArray("semesters").add(old.deepCopy())
        assertThrows(SchoolFailure::class.java) { SchoolProtocol.semesterCatalog(obj) }
    }

    @Test fun missingFieldsNeverExposeInternalKeys() {
        val error = assertThrows(SchoolFailure::class.java) {
            SchoolProtocol.session(JsonParser.parseString("{}"))
        }
        assertFalse(error.message!!.contains("token"))
        assertFalse(error.message!!.contains("字段"))
    }

}
