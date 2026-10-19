package cn.aust.mytimetable.school

import com.google.gson.JsonElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection

fun interface SchoolTransport {
    fun post(path: String, fields: Map<String, String>, secret: String): JsonElement
}

class HttpsSchoolTransport : SchoolTransport {
    private val random = SecureRandom()

    override fun post(path: String, fields: Map<String, String>, secret: String): JsonElement {
        require(path in setOf("/verify-code", "/login", "/common/get-biz-types", "/common/get-semester",
            "/student/course/schedule/get-course-tables"))
        val connection = URL("https://jwglyd.aust.edu.cn/app-ws/ws/app-service$path")
            .openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", "https://jwglyd.aust.edu.cn")
            connection.setRequestProperty("Referer", "https://jwglyd.aust.edu.cn/app-web/")
            connection.setRequestProperty("token", fields["token"] ?: "")
            val signed = SchoolProtocol.signedFields(fields, secret, System.currentTimeMillis(), random.nextInt(100_000))
            val bytes = SchoolProtocol.form(signed).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status != 200) throw SchoolFailure(when (status) {
                429 -> "操作太频繁，请稍等一会儿再试。"
                in 500..599 -> "教务系统暂时无法使用，请稍后再试。"
                401, 403 -> "学校暂时不允许访问，请稍后重试。"
                else -> "暂时无法连接教务系统，请稍后重试。"
            })
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Interrupted")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw SchoolFailure("学校提供的数据过多，暂时无法读取，请稍后重试。")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return SchoolProtocol.decode(body)
        } catch (_: java.net.SocketTimeoutException) {
            throw SchoolFailure("连接学校超时，请稍后重试。")
        } catch (_: java.net.UnknownHostException) {
            throw SchoolFailure("无法连接学校，请检查网络后重试。")
        } catch (_: IOException) {
            throw SchoolFailure("连接学校失败，请检查网络后重试。")
        } finally {
            connection.disconnect()
        }
    }
}

class SchoolApi(private val transport: SchoolTransport = HttpsSchoolTransport()) {
    fun login(account: String, password: String, checkActive: () -> Unit = {}): SavedLogin {
        if (account.isBlank() || password.isEmpty()) throw SchoolFailure("请填写学号和教务密码。")
        checkActive()
        val config = transport.post("/verify-code", emptyMap(), SchoolProtocol.BOOTSTRAP_SECRET)
        val enabled = SchoolProtocol.parseSafely {
            val flag = config.asJsonObject.get("enable")
            if (flag == null || !flag.isJsonPrimitive || !flag.asJsonPrimitive.isBoolean) {
                throw SchoolFailure("暂时无法登录教务系统，请稍后重试。")
            }
            flag.asBoolean
        }
        if (enabled) throw SchoolFailure("学校要求验证码验证，暂时无法在应用内登录。请到教务网站查看课表。")
        checkActive()
        val response = transport.post("/login", mapOf("user_code" to account,
            "passwd" to SchoolProtocol.password(password)), SchoolProtocol.BOOTSTRAP_SECRET)
        return SavedLogin(account, password, SchoolProtocol.session(response))
    }

    fun businessTypes(session: SchoolSession): List<BusinessType> = SchoolProtocol.businessTypes(
        transport.post("/common/get-biz-types", mapOf("token" to session.token, "identity" to session.identity), session.secret))

    fun semesters(session: SchoolSession, business: BusinessType): SemesterCatalog = SchoolProtocol.semesterCatalog(
        transport.post("/common/get-semester", mapOf("token" to session.token, "biz_type_id" to business.id), session.secret))

    fun semester(session: SchoolSession, business: BusinessType): Semester = semesters(session, business).current

    fun week(session: SchoolSession, business: BusinessType, semester: Semester, week: Int): WeekPreview {
        val range = semester.range(week)
        val result = transport.post("/student/course/schedule/get-course-tables", mapOf(
            "token" to session.token, "semester_id" to semester.id, "biz_type_id" to business.id,
            "start_date" to range.start.toString(), "end_date" to range.end.toString()), session.secret)
        return WeekPreview(business, semester, week, SchoolProtocol.courses(result, range))
    }

    fun initialWeek(semester: Semester, today: LocalDate): Int = semester.weekAt(today)
        ?: if (today < semester.start) 1 else semester.weekCount

    fun fullSemester(
        session: SchoolSession, business: BusinessType, semester: Semester,
        checkActive: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }, requireCurrent: Boolean = true,
    ): SemesterDownload {
        val result = SemesterDownload.collectConcurrent(business, semester, fetch = { number ->
            checkActive()
            week(session, business, semester, number)
        }, checkActive = checkActive, progress = progress)
        checkActive()
        val verified = semesters(session, business)
        if (verified.semesters.singleOrNull { it.id == semester.id } != semester || (requireCurrent && verified.currentId != semester.id)) {
            throw SchoolFailure("读取期间学校学期信息发生变化，请重试。旧课表未更改。")
        }
        return result
    }
}
