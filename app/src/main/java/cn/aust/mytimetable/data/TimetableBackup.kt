package cn.aust.mytimetable.data

import cn.aust.mytimetable.school.SchoolFailure
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.security.MessageDigest

// Explicit allow-list of timetable tables: never serialize the login vault or preferences.
data class TimetableBackup(val createdAt: Long, val currentKey: String,
    val snapshots: List<SnapshotRow>, val courses: List<CourseRow>, val changes: List<LocalChangeRow>)

data class BackupSummary(val createdAt: Long, val semesters: List<String>, val courses: Int)

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val gson = Gson()
    private fun checksum(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(backup: TimetableBackup): ByteArray {
        val payload = gson.toJson(backup)
        val bytes = JsonObject().apply {
            addProperty("format", "shike-timetable")
            addProperty("version", 1)
            addProperty("checksum", checksum(payload))
            addProperty("payload", payload)
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw SchoolFailure("课表数据过大，暂时无法导出。")
        return bytes
    }

    fun decode(input: InputStream): TimetableBackup {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() <= MAX_BYTES) {
            val read = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        val bytes = buffer.toByteArray()
        if (bytes.size > MAX_BYTES) throw SchoolFailure("备份文件过大，请选择时课导出的备份。")
        try {
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            if (json["format"].asString != "shike-timetable") throw SchoolFailure("这不是时课的备份文件。")
            if (json["version"].asInt != 1) throw SchoolFailure("这个备份版本暂不支持，请更新时课后重试。")
            val payload = json["payload"].asString
            require(checksum(payload) == json["checksum"].asString)
            return gson.fromJson(payload, TimetableBackup::class.java).also {
                require(it.createdAt > 0 && it.currentKey.isNotBlank())
                require(it.snapshots.size in 1..100 && it.courses.size <= 100_000 && it.changes.size <= 100_000)
            }
        } catch (e: SchoolFailure) { throw e }
        catch (_: Exception) { throw SchoolFailure("备份文件不完整或已损坏，请重新选择。") }
    }
}
