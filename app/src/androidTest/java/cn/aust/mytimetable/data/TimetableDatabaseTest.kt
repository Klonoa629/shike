package cn.aust.mytimetable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.aust.mytimetable.school.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TimetableDatabaseTest {
    private lateinit var db: TimetableDatabase
    private lateinit var store: LocalTimetableStore
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val semester = Semester("test", "测试学期", LocalDate.parse("2026-08-31"),
        LocalDate.parse("2027-01-17"), DayOfWeek.MONDAY)
    private val course = SchoolCourse("test", "测试课", semester.start, LocalTime.of(8, 0),
        LocalTime.of(9, 0), listOf("测试教师甲", "测试教师乙"), emptyList())
    private fun download(courses: List<SchoolCourse> = listOf(course)) = SemesterDownload(
        BusinessType("test", "测试业务"), semester, courses, (1..20).toSet())

    @Before fun create() {
        db = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java).build()
        store = LocalTimetableStore(db.timetable())
    }
    @After fun close() { db.close() }

    @Test fun backupRestoresAllSemestersNotesAndDeletionRecords() {
        store.save("test-account", download(), 1000)
        store.edit(semester, emptyList(), course, delete = true)
        val manual = course.copy(lessonId = "manual", date = course.date.plusDays(1), notes = "实验改到这里")
        store.edit(semester, listOf(manual))
        val oldKey = store.currentKey()!!
        val next = semester.copy(id = "next", label = "下一学期")
        store.save("test-account", download().copy(semester = next), 1500)
        val nextCourse = store.load()!!.effectiveCourses.single()
        store.edit(next, listOf(nextCourse.copy(notes = "带电脑")), nextCourse)
        val before = store.backup("test-account", 2000)
        val encoded = BackupCodec.encode(before)
        val decoded = BackupCodec.decode(java.io.ByteArrayInputStream(encoded))
        // Replacing a newer local edit exercises actual restoration rather than an unchanged round trip.
        store.edit(next, listOf(manual.copy(date = manual.date.plusDays(1))))
        val summary = store.restore("test-account", decoded)
        assertEquals(2, summary.semesters.size)
        assertEquals(2, summary.courses)
        assertEquals(before, store.backup("test-account", 2000))
        assertEquals("实验改到这里", store.load(oldKey)!!.effectiveCourses.single().notes)
        assertEquals("带电脑", store.load()!!.effectiveCourses.single().notes)
        // A later school sync must still honor the restored deletion tombstone and manual course.
        store.save("test-account", download(), 3000, current = false)
        assertEquals("manual", store.load(oldKey)!!.effectiveCourses.single().lessonId)
    }

    @Test fun invalidBackupRollsBackEvenAfterReplacementHasStarted() {
        store.save("test-account", download(), 1000)
        val before = store.backup("test-account", 2000)
        val invalid = before.copy(courses = before.courses.map { it.copy(endMinute = 8 * 60 + 30) })
        assertThrows(SchoolFailure::class.java) { store.restore("test-account", invalid) }
        assertEquals(before, store.backup("test-account", 2000))
        assertThrows(SchoolFailure::class.java) {
            store.restore("test-account", before.copy(courses = before.courses + before.courses))
        }
        assertEquals(before, store.backup("test-account", 2000))
    }

    @Test fun wrongAccountOrOrphanChangesNeverReplaceData() {
        store.save("test-account", download(), 1000)
        val before = store.backup("test-account", 2000)
        assertThrows(SchoolFailure::class.java) { store.restore("another-account", before) }
        assertThrows(SchoolFailure::class.java) {
            store.restore("test-account", before.copy(changes = listOf(LocalChangeRow("orphan", "missing", null, false, "{}"))))
        }
        assertEquals(before, store.backup("test-account", 2000))
    }

    @Test fun previewInIsolatedDatabaseDoesNotChangeLiveCourses() {
        store.save("test-account", download(), 1000)
        val backup = store.backup("test-account", 2000)
        store.edit(semester, listOf(course.copy(date = course.date.plusDays(1), notes = "新安排")))
        val before = store.backup("test-account", 3000)
        val staging = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java).build()
        try {
            assertEquals(1, LocalTimetableStore(staging.timetable()).restore("test-account", backup).courses)
        } finally { staging.close() }
        assertEquals(before, store.backup("test-account", 3000))
    }

    @Test fun allWeeksAndSuccessfulTimeRoundTripWithoutNetwork() {
        store.save("test-account", download(), 1000)
        val restored = store.load()!!
        assertEquals(1000L, restored.successfulAt)
        assertEquals(listOf(course), restored.week(1).courses)
        assertTrue(restored.week(20).courses.isEmpty())
        assertEquals(20, restored.download.completedWeeks.size)
    }

    @Test fun insertionFailureRollsBackDeletedRowsAndTimestamp() {
        store.save("test-account", download(), 1000)
        val before = db.timetable().readSnapshot()!!
        // Force a real SQLite constraint failure after DELETE inside the transaction.
        try {
            db.timetable().replace(before.first.copy(successfulAt = 2000), before.second + before.second)
            fail("重复主键应该失败")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(before, db.timetable().readSnapshot())
        assertEquals(1000L, store.load()!!.successfulAt)
    }

    @Test fun incompleteOrConflictingCandidateLeavesOldSemesterUntouched() {
        store.save("test-account", download(), 1000)
        val before = store.load()
        assertThrows(SchoolFailure::class.java) {
            store.save("test-account", download().copy(completedWeeks = setOf(1)), 2000)
        }
        assertThrows(SchoolFailure::class.java) {
            store.save("test-account", download(listOf(course, course)), 2000)
        }
        assertEquals(before, store.load())
    }

    @Test fun differentAccountCannotOverwriteExistingTimetable() {
        store.save("test-account", download(), 1000)
        assertThrows(SchoolFailure::class.java) { store.save("another-account", download(emptyList()), 2000) }
        assertEquals(listOf(course), store.load()!!.download.courses)
    }

    @Test fun fullySuccessfulEmptySemesterReplacesOldSchoolSnapshot() {
        store.save("test-account", download(), 1000)
        store.save("test-account", download(emptyList()), 2000)
        val restored = store.load()!!
        assertTrue(restored.download.courses.isEmpty())
        assertEquals(2000L, restored.successfulAt)
        assertEquals(20, restored.download.completedWeeks.size)
    }

    @Test fun diskDatabaseSurvivesCloseAndReopen() {
        // Isolated test database only; never open/delete the user's timetable.db.
        val name = "timetable-test-${UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, TimetableDatabase::class.java, name).build()
            try {
                LocalTimetableStore(first.timetable()).save("test-account", download(), 3000)
            } finally { first.close() }
            val reopened = Room.databaseBuilder(context, TimetableDatabase::class.java, name).build()
            try {
                val restored = LocalTimetableStore(reopened.timetable()).load()!!
                assertEquals(listOf(course), restored.week(1).courses)
                assertEquals(3000L, restored.successfulAt)
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun manual() = ManualSchedule.expand(semester, "实验", "老师", "", "带电脑", listOf(
        ScheduleSlot(setOf(7, 8), DayOfWeek.SATURDAY, LocalTime.of(18, 30), LocalTime.of(21, 30))))

    @Test fun batchEditMoveAndDeleteOnlyAffectOneOccurrence() {
        store.save("test-account", download(), 1000)
        val added = manual()
        store.edit(semester, added)
        val moved = added[1].copy(date = added[1].date.plusDays(1), notes = "改到周日")
        store.edit(semester, listOf(moved), added[1])
        assertEquals(listOf(added[0]), store.load()!!.week(7).courses)
        assertEquals(listOf(moved), store.load()!!.week(8).courses)
        store.edit(semester, emptyList(), moved, delete = true)
        assertTrue(store.load()!!.week(8).courses.isEmpty())
        assertEquals(listOf(added[0]), store.load()!!.week(7).courses)
        assertEquals(1000L, store.load()!!.successfulAt)
    }

    @Test fun rejectedBatchAndRejectedEditRollBackEveryRow() {
        store.save("test-account", download(), 1000)
        val added = manual()
        val before = store.load()
        val conflict = added[0].copy(localId = "conflict", date = course.date, start = course.start, end = course.end)
        assertThrows(SchoolFailure::class.java) { store.edit(semester, added + conflict) }
        assertEquals(before, store.load())
        store.edit(semester, added)
        val saved = store.load()
        assertThrows(SchoolFailure::class.java) { store.edit(semester, listOf(conflict), added[0]) }
        assertEquals(saved, store.load())
    }

    @Test fun syncPreservesManualCoursesAndSingleSchoolOverride() {
        store.save("test-account", download(), 1000)
        store.edit(semester, manual())
        store.edit(semester, listOf(course.copy(notes = "老师临时通知", start = LocalTime.of(10, 0), end = LocalTime.of(11, 0))), course)
        val before = store.load()!!.effectiveCourses
        store.save("test-account", download(), 2000)
        assertEquals(before, store.load()!!.effectiveCourses)
        assertEquals(2000L, store.load()!!.successfulAt)
        assertEquals(listOf(course), store.load()!!.download.courses)
    }

    @Test fun conflictingOrAmbiguouslyRescheduledSchoolSyncPreservesEverything() {
        store.save("test-account", download(), 1000)
        val added = manual()
        store.edit(semester, added)
        val before = store.load()
        assertThrows(SchoolFailure::class.java) { store.save("test-account", download(listOf(course, added[0].copy(manual = false, localId = null))), 2000) }
        assertEquals(before, store.load())
        store.edit(semester, listOf(course.copy(notes = "备注")), course)
        val edited = store.load()
        assertThrows(SchoolFailure::class.java) { store.save("test-account", download(listOf(course.copy(date = course.date.plusDays(1)))), 3000) }
        assertEquals(edited, store.load())
    }

    @Test fun deletedSchoolOccurrenceStaysDeletedOnSync() {
        store.save("test-account", download(), 1000)
        store.edit(semester, emptyList(), course, delete = true)
        assertTrue(store.load()!!.effectiveCourses.isEmpty())
        store.save("test-account", download(), 2000)
        assertTrue(store.load()!!.effectiveCourses.isEmpty())
    }

    @Test fun migrationFromVersionOnePreservesSchoolDataAndAllowsLocalEdits() {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE IF NOT EXISTS `school_snapshot` (`id` INTEGER NOT NULL, `ownerKey` TEXT NOT NULL, `businessId` TEXT NOT NULL, `businessName` TEXT NOT NULL, `semesterId` TEXT NOT NULL, `semesterLabel` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL, `weekStart` INTEGER NOT NULL, `weekCount` INTEGER NOT NULL, `occurrenceCount` INTEGER NOT NULL, `successfulAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                old.execSQL("CREATE TABLE IF NOT EXISTS `school_courses` (`lessonId` TEXT NOT NULL, `date` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, `name` TEXT NOT NULL, `teachersJson` TEXT NOT NULL, `roomsJson` TEXT NOT NULL, PRIMARY KEY(`lessonId`, `date`, `startMinute`, `endMinute`))")
                old.execSQL("INSERT INTO school_snapshot VALUES (1, 'owner', 'test', '测试业务', 'test', '测试学期', '2026-08-31', '2027-01-17', 1, 20, 1, 1234)")
                old.execSQL("INSERT INTO school_courses VALUES ('test', '2026-08-31', 480, 540, '测试课', '[\"测试教师甲\",\"测试教师乙\"]', '[]')")
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
                .addMigrations(TimetableDatabase.MIGRATION_1_2, TimetableDatabase.MIGRATION_2_3).build()
            try {
                val local = LocalTimetableStore(migrated.timetable())
                assertEquals(listOf(course), local.load()!!.effectiveCourses)
                assertEquals(1234L, local.load()!!.successfulAt)
                local.edit(semester, manual())
                assertEquals(3, local.load()!!.effectiveCourses.size)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun newSemesterPreservesOldCoursesManualNotesAndDeletes() {
        store.save("test-account", download(), 1000)
        store.edit(semester, manual())
        store.edit(semester, emptyList(), course, delete = true)
        val old = store.load()!!
        val next = semester.copy(id = "next", start = semester.start.plusYears(1), end = semester.end.plusYears(1))
        val nextCourse = course.copy(date = next.start)
        val nextDownload = SemesterDownload(download().business, next, listOf(nextCourse), (1..next.weekCount).toSet())
        store.save("test-account", nextDownload, 2000)
        assertEquals("next", store.load()!!.download.semester.id)
        assertEquals(old, store.load(semesterKey("test", "test")))
        assertEquals(2, store.semesters().size)
        assertEquals(manual().map { it.date }, store.load(semesterKey("test", "test"))!!.effectiveCourses.map { it.date })
        assertTrue(store.load(semesterKey("test", "test"))!!.effectiveCourses.all { it.notes == "带电脑" })
    }

    @Test fun importingHistoryDoesNotChangeCurrentOrCurrentSyncTime() {
        store.save("test-account", download(), 1000)
        val current = store.load()
        val old = semester.copy(id = "history", start = semester.start.minusYears(1), end = semester.end.minusYears(1))
        val data = SemesterDownload(download().business, old, listOf(course.copy(date = old.start)), (1..old.weekCount).toSet())
        store.save("test-account", data, 2000, current = false)
        assertEquals(current, store.load())
        assertEquals(semesterKey("test", "test"), store.currentKey())
        assertEquals("history", store.load(semesterKey("test", "history"))!!.download.semester.id)
        assertThrows(SchoolFailure::class.java) { store.save("test-account", data.copy(completedWeeks = emptySet()), 3000, current = false) }
        assertEquals(2000L, store.load(semesterKey("test", "history"))!!.successfulAt)
        assertEquals(current, store.load())
    }

    @Test fun migrationFromVersionTwoPreservesPersonalChanges() {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE IF NOT EXISTS `school_snapshot` (`id` INTEGER NOT NULL, `ownerKey` TEXT NOT NULL, `businessId` TEXT NOT NULL, `businessName` TEXT NOT NULL, `semesterId` TEXT NOT NULL, `semesterLabel` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL, `weekStart` INTEGER NOT NULL, `weekCount` INTEGER NOT NULL, `occurrenceCount` INTEGER NOT NULL, `successfulAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                old.execSQL("CREATE TABLE IF NOT EXISTS `school_courses` (`lessonId` TEXT NOT NULL, `date` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, `name` TEXT NOT NULL, `teachersJson` TEXT NOT NULL, `roomsJson` TEXT NOT NULL, PRIMARY KEY(`lessonId`, `date`, `startMinute`, `endMinute`))")
                old.execSQL("INSERT INTO school_snapshot VALUES (1, 'owner', 'test', '测试业务', 'test', '测试学期', '2026-08-31', '2027-01-17', 1, 20, 1, 1234)")
                old.execSQL("INSERT INTO school_courses VALUES ('test', '2026-08-31', 480, 540, '测试课', '[\"测试教师甲\",\"测试教师乙\"]', '[]')")
                old.execSQL("CREATE TABLE local_changes (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, originalKey TEXT, deleted INTEGER NOT NULL, payload TEXT NOT NULL)")
                old.execSQL("INSERT INTO local_changes VALUES (?, ?, NULL, 0, ?)", arrayOf("manual-v2", "[\"owner\",\"test\",\"test\"]", """{"lesson":"manual","name":"原有实验课","date":"2026-09-01","start":"10:00","end":"11:00","notes":"原有备注","teachers":["老师"],"rooms":[]}"""))
                old.version = 2
            }
            val migrated = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
                .addMigrations(TimetableDatabase.MIGRATION_1_2, TimetableDatabase.MIGRATION_2_3).build()
            try {
                val local = LocalTimetableStore(migrated.timetable())
                assertTrue(local.load()!!.effectiveCourses.contains(course))
                assertEquals("原有备注", local.load()!!.effectiveCourses.single { it.localId == "manual-v2" }.notes)
                assertEquals(1234L, local.load()!!.successfulAt)
                local.edit(semester, manual())
                assertEquals(4, local.load()!!.effectiveCourses.size)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun previouslySavedUpcomingSemesterCanBecomeCurrentWithoutLosingEitherSemester() {
        store.save("test-account", download(), 1000)
        store.edit(semester, manual())
        val original = store.load()
        val next = semester.copy(id = "upcoming", start = semester.start.plusYears(1), end = semester.end.plusYears(1))
        val data = SemesterDownload(download().business, next, listOf(course.copy(date = next.start)), (1..next.weekCount).toSet())
        val upcoming = store.save("test-account", data, 2000, current = false)
        assertEquals(original, store.load())
        assertEquals(upcoming, store.recognizeCurrent(semesterKey("test", "upcoming")))
        assertEquals(upcoming, store.load())
        assertEquals(original, store.load(semesterKey("test", "test")))
        assertNull(store.recognizeCurrent("not-downloaded"))
        assertEquals(upcoming, store.load())
        store.edit(next, listOf(course.copy(date = next.start, notes = "新学期备注")), upcoming.effectiveCourses.single())
        assertEquals("新学期备注", store.load()!!.effectiveCourses.single().notes)
        assertEquals(original, store.load(semesterKey("test", "test")))
    }

}
