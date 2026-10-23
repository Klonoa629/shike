package cn.aust.mytimetable.data

import cn.aust.mytimetable.school.*
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class StoredSemester(val download: SemesterDownload, val successfulAt: Long, val effectiveCourses: List<SchoolCourse> = download.courses) {
    fun week(number: Int): WeekPreview {
        val range = download.semester.range(number)
        return WeekPreview(download.business, download.semester, number,
            effectiveCourses.filter { it.date >= range.start && it.date <= range.end })
    }
}

class LocalTimetableStore(private val dao: TimetableDao) {
    fun backup(account: String, now: Long = System.currentTimeMillis()): TimetableBackup {
        var result: TimetableBackup? = null
        dao.atomic {
            val rows = dao.snapshots()
            if (rows.isEmpty()) throw SchoolFailure("还没有可备份的课表，请先同步学期。")
            if (rows.any { it.ownerKey != ownerKey(account) }) throw SchoolFailure("本机课表不属于当前账号，无法备份。")
            result = TimetableBackup(now, requireNotNull(dao.currentKey()), rows,
                rows.flatMap { dao.courses(it.id) }, dao.allChanges())
        }
        return result!!
    }

    // Used both on an isolated in-memory database for preview and on the real database after confirmation.
    // Validation and replacement form one transaction; any malformed row rolls everything back.
    fun restore(account: String, backup: TimetableBackup): BackupSummary {
        var summary: BackupSummary? = null
        try {
            dao.atomic {
                val rows = backup.snapshots
                require(rows.size in 1..100 && backup.createdAt > 0)
                if (rows.any { it.ownerKey != ownerKey(account) })
                    throw SchoolFailure("这份备份不属于当前账号，请登录导出备份时使用的账号。")
                val ids = rows.map { it.id }.toSet()
                require(ids.size == rows.size && backup.currentKey in ids)
                require(rows.all { it.id == semesterKey(it.businessId, it.semesterId) &&
                    it.businessId.isNotBlank() && it.semesterId.isNotBlank() && it.semesterLabel.isNotBlank() && it.successfulAt > 0 })
                require(backup.courses.all { it.snapshotId in ids && it.lessonId.isNotBlank() })
                val scopes = rows.associateBy { scope(it) }
                require(backup.changes.map { it.id }.toSet().size == backup.changes.size)
                backup.changes.forEach { change ->
                    require(change.id.isNotBlank() && change.scope in scopes)
                    require(!change.deleted || change.originalKey != null)
                    val row = scopes.getValue(change.scope)
                    CourseRules.validate(listOf(unpack(change)), DateRange(LocalDate.parse(row.startDate), LocalDate.parse(row.endDate)))
                }
                backup.changes.groupBy { it.scope }.values.forEach { changes ->
                    val originals = changes.mapNotNull { it.originalKey }
                    require(originals.distinct().size == originals.size)
                }
                dao.clearChanges(); dao.clearCourses(); dao.clearSnapshots(); dao.clearMeta()
                rows.forEach { dao.writeSnapshot(it) }
                dao.insertCourses(backup.courses)
                dao.putChanges(backup.changes)
                dao.writeCurrent(TimetableMeta(currentKey = backup.currentKey))
                val count = rows.sumOf { load(it.id)!!.effectiveCourses.size }
                summary = BackupSummary(backup.createdAt, rows.map { it.semesterLabel }, count)
            }
        } catch (e: SchoolFailure) { throw e }
        catch (_: Exception) { throw SchoolFailure("备份数据不完整或格式有误，原有课表已保留。") }
        return summary!!
    }

    fun save(account: String, download: SemesterDownload, successfulAt: Long, current: Boolean = true): StoredSemester {
        download.validate()
        require(account.isNotBlank() && successfulAt > 0)
        val owner = ownerKey(account)
        val existing = dao.snapshot()
        if (existing != null && existing.ownerKey != owner) {
            throw SchoolFailure("这台手机保存的是其他账号的课表，请使用原账号登录。")
        }
        val semester = download.semester
        val row = SnapshotRow(id = semesterKey(download.business.id, semester.id), ownerKey = owner, businessId = download.business.id,
            businessName = download.business.name, semesterId = semester.id, semesterLabel = semester.label,
            startDate = semester.start.toString(), endDate = semester.end.toString(), weekStart = semester.weekStart.value,
            weekCount = semester.weekCount, occurrenceCount = download.courses.size, successfulAt = successfulAt)
        val courses = download.courses.map {
            CourseRow(it.lessonId, it.date.toString(), it.start.toSecondOfDay() / 60, it.end.toSecondOfDay() / 60,
                it.name, encode(it.teachers), encode(it.rooms), row.id)
        }
        var result: StoredSemester? = null
        dao.atomic {
            val effective = merge(download, dao.changes(scope(row)))
            dao.replace(row, courses, current)
            result = StoredSemester(download, successfulAt, effective)
        }
        return result!!
    }

    fun load(key: String? = null): StoredSemester? {
        val (row, courses) = dao.readSnapshot(key) ?: return null
        val semester = Semester(row.semesterId, row.semesterLabel, LocalDate.parse(row.startDate),
            LocalDate.parse(row.endDate), DayOfWeek.of(row.weekStart))
        check(row.weekCount == semester.weekCount && row.occurrenceCount == courses.size)
        val data = SemesterDownload(BusinessType(row.businessId, row.businessName), semester,
            courses.map { SchoolCourse(it.lessonId, it.name, LocalDate.parse(it.date),
                LocalTime.ofSecondOfDay(it.startMinute * 60L), LocalTime.ofSecondOfDay(it.endMinute * 60L),
                decode(it.teachersJson), decode(it.roomsJson)) }, (1..semester.weekCount).toSet())
        data.validate()
        return StoredSemester(data, row.successfulAt, merge(data, dao.changes(scope(row))))
    }

    fun currentKey(): String? = dao.currentKey()
    // Only call after the school identifies this saved semester as current.
    fun recognizeCurrent(key: String): StoredSemester? {
        var result: StoredSemester? = null
        dao.atomic {
            result = load(key)
            if (result != null) dao.writeCurrent(TimetableMeta(currentKey = key))
        }
        return result
    }
    fun semesters(): List<SemesterChoice> = dao.snapshots().map { row ->
        SemesterChoice(BusinessType(row.businessId, row.businessName),
            Semester(row.semesterId, row.semesterLabel, LocalDate.parse(row.startDate), LocalDate.parse(row.endDate), DayOfWeek.of(row.weekStart)), true)
    }

    // All callers use one process-owned worker; transaction also protects validation + writes.
    fun edit(expected: Semester, additions: List<SchoolCourse>, original: SchoolCourse? = null,
        delete: Boolean = false): StoredSemester {
        var result: StoredSemester? = null
        dao.atomic {
            val before = load() ?: throw SchoolFailure("请先同步学期。")
            if (before.download.semester != expected) throw SchoolFailure("学期已变化，请重新打开编辑页。")
            val row = dao.snapshot()!!
            val scope = scope(row)
            if (original != null && original !in before.effectiveCourses) throw SchoolFailure("这次课程已变化，请重新打开。")
            if (original == null && (delete || additions.isEmpty())) throw SchoolFailure("请添加上课安排。")
            if (original != null && !delete && additions.size != 1) throw SchoolFailure("只能修改这一次上课。")
            if (delete) {
                if (original!!.manual) dao.removeChange(original.localId!!)
                else {
                    val id = original.localId ?: java.util.UUID.randomUUID().toString()
                    val key = original.localId?.let { dao.changes(scope).single { r -> r.id == it }.originalKey } ?: key(original)
                    dao.putChanges(listOf(LocalChangeRow(id, scope, key, true, payload(original))))
                }
            } else {
                val changes = additions.map { course ->
                    val id = original?.localId ?: course.localId ?: java.util.UUID.randomUUID().toString()
                    val originalKey = if (original == null || original.manual) null else
                        original.localId?.let { dao.changes(scope).single { r -> r.id == it }.originalKey } ?: key(original)
                    val updated = course.copy(localId = id, manual = original?.manual ?: true,
                        lessonId = original?.lessonId ?: course.lessonId)
                    LocalChangeRow(id, scope, originalKey, false, payload(updated))
                }
                dao.putChanges(changes)
            }
            // Any invalid merged schedule throws and rolls the entire batch back.
            result = load()
        }
        return result!!
    }

    fun previewSync(account: String, download: SemesterDownload): SyncReview? {
        download.validate()
        if (dao.snapshotByKey(semesterKey(download.business.id, download.semester.id)) == null) return null
        val baseline = backup(account, 1)
        val row = baseline.snapshots.singleOrNull { it.id == semesterKey(download.business.id, download.semester.id) }
            ?: return null
        val changes = baseline.changes.filter { it.scope == scope(row) }
        val newKeys = download.courses.map(::key).toSet()
        val missing = changes.filter { it.originalKey != null && it.originalKey !in newKeys }
        if (missing.isEmpty()) return null
        val oldKeys = load(row.id)!!.download.courses.map(::key).toSet()
        val groups = missing.groupBy { unpack(it).lessonId }.map { (lesson, edits) ->
            SyncConflict(lesson, edits.map { SyncLocal(it.id, unpack(it), it.deleted) },
                download.courses.filter { it.lessonId == lesson && key(it) !in oldKeys })
        }
        return SyncReview(download, baseline, groups)
    }

    fun resolveSync(account: String, review: SyncReview, useSchool: Set<String>, now: Long): StoredSemester {
        var result: StoredSemester? = null
        dao.atomic {
            if (backup(account, 1) != review.baseline)
                throw SchoolFailure("课表已发生变化，请取消后重新同步。原有安排已保留。")
            require(useSchool.all { id -> review.conflicts.any { it.id == id } })
            val row = review.baseline.snapshots.single { it.id == semesterKey(review.download.business.id, review.download.semester.id) }
            review.conflicts.forEach { conflict ->
                conflict.local.forEach { local ->
                    val change = review.baseline.changes.single { it.id == local.id }
                    if (conflict.id in useSchool || local.deleted) dao.removeChange(local.id)
                    else dao.putChanges(listOf(change.copy(originalKey = null)))
                }
                if (conflict.id !in useSchool) {
                    // The user explicitly chooses the entire displayed group; never guess a one-to-one match.
                    dao.putChanges(conflict.school.map { course ->
                        LocalChangeRow(java.util.UUID.randomUUID().toString(), scope(row), key(course), true, payload(course))
                    })
                }
            }
            result = save(account, review.download, now)
        }
        return result!!
    }

    private fun scope(row: SnapshotRow) = encode(listOf(row.ownerKey, row.businessId, row.semesterId))
    private fun key(c: SchoolCourse) = encode(listOf(c.lessonId, c.date.toString(), c.start.toString(), c.end.toString()))
    private fun merge(data: SemesterDownload, changes: List<LocalChangeRow>): List<SchoolCourse> {
        val keys = data.courses.map(::key).toSet()
        if (changes.any { it.originalKey != null && it.originalKey !in keys }) {
            throw SchoolFailure("学校调整了你编辑过的课程，本次同步未覆盖本地安排。请核对学校的调课信息，本地修改已保留。")
        }
        val replaced = changes.mapNotNull { it.originalKey }.toSet()
        val effective = data.courses.filter { key(it) !in replaced } + changes.filterNot { it.deleted }.map { unpack(it) }
        CourseRules.validate(effective, DateRange(data.semester.start, data.semester.end))
        return effective.sortedWith(compareBy({ it.date }, { it.start }))
    }

    private fun payload(c: SchoolCourse) = JsonObject().apply {
        addProperty("lesson", c.lessonId); addProperty("name", c.name); addProperty("date", c.date.toString())
        addProperty("start", c.start.toString()); addProperty("end", c.end.toString()); addProperty("notes", c.notes)
        add("teachers", JsonParser.parseString(encode(c.teachers))); add("rooms", JsonParser.parseString(encode(c.rooms)))
    }.toString()
    private fun unpack(row: LocalChangeRow): SchoolCourse {
        val j = JsonParser.parseString(row.payload).asJsonObject
        return SchoolCourse(j["lesson"].asString, j["name"].asString, LocalDate.parse(j["date"].asString),
            LocalTime.parse(j["start"].asString), LocalTime.parse(j["end"].asString),
            j["teachers"].asJsonArray.map { it.asString }, j["rooms"].asJsonArray.map { it.asString },
            row.id, j["notes"].asString, row.originalKey == null)
    }

    private fun encode(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }.toString()
    private fun decode(value: String) = JsonParser.parseString(value).asJsonArray.map { it.asString }
    private fun ownerKey(account: String) = MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

// Pending downloads are memory-only. Cancelling a review never writes to the database.
data class SyncLocal(val id: String, val course: SchoolCourse, val deleted: Boolean)
data class SyncConflict(val id: String, val local: List<SyncLocal>, val school: List<SchoolCourse>)
data class SyncReview(val download: SemesterDownload, val baseline: TimetableBackup, val conflicts: List<SyncConflict>)
