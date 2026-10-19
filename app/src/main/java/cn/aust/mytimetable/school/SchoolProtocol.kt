package cn.aust.mytimetable.school

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.Locale

object SchoolProtocol {
    // Public bootstrap signing value in the school's official JS; not a user secret.
    const val BOOTSTRAP_SECRET = "supwisdom_eams_app_secret"

    fun signedFields(
        fields: Map<String, String>, secret: String, timestamp: Long, random: Int,
    ): Map<String, String> {
        require(fields.keys.none { it in setOf("sign", "timestamp", "random") })
        val input = secret + "|" + fields.toSortedMap().values.joinToString("") { "$it|" } +
            "$timestamp|$random"
        // Required by the school's wire protocol, not used for password storage.
        val sign = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }.uppercase(Locale.ROOT)
        return fields + mapOf("sign" to sign, "timestamp" to "$timestamp", "random" to "$random")
    }

    fun form(fields: Map<String, String>): String = fields.toSortedMap().entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    fun password(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(body: String): JsonElement = parseSafely {
        if (body.isBlank()) throw SchoolFailure("未收到学校的数据，请稍后重试。")
        val envelope = strictJson(body).asJsonObject
        val code = envelope.required("err_code")
        if (code != "00000") {
            // Verified against the school's endpoint with an intentionally invalid token.
            if (code == "20") throw SchoolSessionExpired()
            throw SchoolFailure("学校暂时无法完成本次操作，请稍后重试。")
        }
        val encoded = envelope.required("business_data")
        val bytes = Base64.getDecoder().decode(encoded)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val decoded = strictJson(json)
        if (decoded.isJsonNull) throw SchoolFailure("未能获取课表数据，原有课表已保留。")
        decoded
    }

    fun session(data: JsonElement): SchoolSession = parseSafely {
        val obj = data.asJsonObject
        val session = SchoolSession(obj.required("token"), obj.required("secret"), obj.required("cur_identity"))
        if (session.identity != "0") throw SchoolFailure("请使用学生账号登录。")
        session
    }

    fun businessTypes(data: JsonElement): List<BusinessType> = parseSafely {
        data.asJsonArray.map { BusinessType(it.asJsonObject.required("id"), it.asJsonObject.required("name")) }
            .also { if (it.isEmpty()) throw SchoolFailure("学校尚未为该账号提供课表，请联系教务老师确认。") }
    }

    fun currentSemester(data: JsonElement): Semester = semesterCatalog(data).current

    fun semesterCatalog(data: JsonElement): SemesterCatalog = parseSafely {
        val obj = data.asJsonObject
        val id = obj.required("cur_semester_id")
        val semesters = obj.getAsJsonArray("semesters").map { element ->
            val item = element.asJsonObject
            val startDay = when (item.required("week_start_day")) {
                "1" -> DayOfWeek.MONDAY
                "0" -> DayOfWeek.SUNDAY
                else -> throw SchoolFailure("学校的周次安排暂时无法识别，请稍后重试。")
            }
            Semester(item.required("id"), "${item.required("year")} 学年 · 第 ${item.required("name")} 学期",
                LocalDate.parse(item.required("start_date")), LocalDate.parse(item.required("end_date")), startDay)
        }
        if (semesters.map { it.id }.distinct().size != semesters.size || semesters.count { it.id == id } != 1)
            throw SchoolFailure("暂时无法确认当前学期，请稍后重试。")
        SemesterCatalog(id, semesters.sortedByDescending { it.start })
    }

    fun courses(data: JsonElement, range: DateRange): List<SchoolCourse> = parseSafely {
        val courses = data.asJsonArray.map { item ->
            val obj = item.asJsonObject
            val rooms = obj.get("rooms")?.takeUnless { it.isJsonNull }?.asJsonArray ?: JsonArray()
            SchoolCourse(
                obj.required("lesson_id"), obj.required("course_name"), LocalDate.parse(obj.required("date")),
                time(obj.required("start_time")), time(obj.required("end_time")),
                obj.getAsJsonArray("teachers").map { it.asJsonObject.required("name") },
                rooms.map { it.asJsonObject.required("name") },
            )
        }.sortedWith(compareBy({ it.date }, { it.start }))
        CourseRules.validate(courses, range)
        courses
    }

    private fun time(text: String): LocalTime {
        require(text.matches(Regex("[0-9]{2}:[0-9]{2}")))
        return LocalTime.parse(text)
    }

    private fun strictJson(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.strictness = Strictness.STRICT
        val value = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        value
    }

    internal fun JsonObject.required(key: String): String {
        val value = get(key) ?: throw SchoolFailure("学校提供的信息不完整，请稍后重试。")
        if (!value.isJsonPrimitive) throw SchoolFailure("学校提供的信息暂时无法读取，请稍后重试。")
        return value.asString.also { if (it.isBlank()) throw SchoolFailure("学校提供的信息不完整，请稍后重试。") }
    }

    internal fun <T> parseSafely(block: () -> T): T = try {
        block()
    } catch (e: SchoolFailure) {
        throw e
    } catch (_: RuntimeException) {
        throw SchoolFailure("学校提供的数据暂时无法读取，请稍后重试。")
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw SchoolFailure("学校提供的数据暂时无法读取，请稍后重试。")
    } catch (_: java.io.IOException) {
        throw SchoolFailure("学校提供的数据暂时无法读取，请稍后重试。")
    }
}
