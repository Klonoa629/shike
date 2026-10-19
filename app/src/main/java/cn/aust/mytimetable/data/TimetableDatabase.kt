package cn.aust.mytimetable.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "local_changes")
data class LocalChangeRow(@PrimaryKey val id: String, val scope: String,
    val originalKey: String?, val deleted: Boolean, val payload: String)

@Entity(tableName = "school_snapshot")
data class SnapshotRow(
    @PrimaryKey val id: String,
    val ownerKey: String,
    val businessId: String,
    val businessName: String,
    val semesterId: String,
    val semesterLabel: String,
    val startDate: String,
    val endDate: String,
    val weekStart: Int,
    val weekCount: Int,
    val occurrenceCount: Int,
    val successfulAt: Long,
)

// Each semester owns its school snapshot; personal changes keep their existing scope.
// Composite identity describes this snapshot, not a promise of stability across school rescheduling.
@Entity(tableName = "school_courses", primaryKeys = ["snapshotId", "lessonId", "date", "startMinute", "endMinute"])
data class CourseRow(
    val lessonId: String,
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val name: String,
    val teachersJson: String,
    val roomsJson: String,
    val snapshotId: String,
)

@Entity(tableName = "timetable_meta")
data class TimetableMeta(@PrimaryKey val id: Int = 1, val currentKey: String)

@Dao
abstract class TimetableDao {
    @Query("SELECT * FROM local_changes ORDER BY id")
    abstract fun allChanges(): List<LocalChangeRow>

    @Query("DELETE FROM local_changes")
    abstract fun clearChanges()

    @Query("DELETE FROM school_courses")
    abstract fun clearCourses()

    @Query("DELETE FROM school_snapshot")
    abstract fun clearSnapshots()

    @Query("DELETE FROM timetable_meta")
    abstract fun clearMeta()

    @Query("SELECT * FROM local_changes WHERE scope = :scope")
    abstract fun changes(scope: String): List<LocalChangeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putChanges(rows: List<LocalChangeRow>)

    @Query("DELETE FROM local_changes WHERE id = :id")
    abstract fun removeChange(id: String)

    @Transaction
    open fun atomic(action: () -> Unit) { action() }

    @Query("SELECT * FROM school_snapshot WHERE id = :key")
    abstract fun snapshotByKey(key: String): SnapshotRow?

    @Query("SELECT * FROM school_snapshot ORDER BY startDate DESC")
    abstract fun snapshots(): List<SnapshotRow>

    @Query("SELECT currentKey FROM timetable_meta WHERE id = 1")
    abstract fun currentKey(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeCurrent(meta: TimetableMeta)

    open fun snapshot(): SnapshotRow? = currentKey()?.let { snapshotByKey(it) }

    @Query("SELECT * FROM school_courses WHERE snapshotId = :key ORDER BY date, startMinute")
    abstract fun courses(key: String): List<CourseRow>

    @Query("DELETE FROM school_courses WHERE snapshotId = :key")
    abstract fun deleteSchoolCourses(key: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertCourses(rows: List<CourseRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeSnapshot(row: SnapshotRow)

    @Transaction
    open fun replace(row: SnapshotRow, courses: List<CourseRow>, current: Boolean = true) {
        val old = snapshots().firstOrNull()
        check(old == null || old.ownerKey == row.ownerKey) { "Account mismatch" }
        check(courses.all { it.snapshotId == row.id })
        deleteSchoolCourses(row.id)
        insertCourses(courses)
        writeSnapshot(row)
        if (current) writeCurrent(TimetableMeta(currentKey = row.id))
    }

    @Transaction
    open fun readSnapshot(key: String? = null): Pair<SnapshotRow, List<CourseRow>>? {
        val metadata = (if (key == null) snapshot() else snapshotByKey(key)) ?: return null
        return metadata to courses(metadata.id)
    }
}

@Database(entities = [SnapshotRow::class, CourseRow::class, LocalChangeRow::class, TimetableMeta::class], version = 3, exportSchema = true)
abstract class TimetableDatabase : RoomDatabase() {
    abstract fun timetable(): TimetableDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `local_changes` (`id` TEXT NOT NULL, `scope` TEXT NOT NULL, `originalKey` TEXT, `deleted` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE school_snapshot_new (id TEXT NOT NULL PRIMARY KEY, ownerKey TEXT NOT NULL, businessId TEXT NOT NULL, businessName TEXT NOT NULL, semesterId TEXT NOT NULL, semesterLabel TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, weekStart INTEGER NOT NULL, weekCount INTEGER NOT NULL, occurrenceCount INTEGER NOT NULL, successfulAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO school_snapshot_new SELECT length(businessId) || ':' || businessId || semesterId, ownerKey, businessId, businessName, semesterId, semesterLabel, startDate, endDate, weekStart, weekCount, occurrenceCount, successfulAt FROM school_snapshot")
                db.execSQL("CREATE TABLE school_courses_new (lessonId TEXT NOT NULL, date TEXT NOT NULL, startMinute INTEGER NOT NULL, endMinute INTEGER NOT NULL, name TEXT NOT NULL, teachersJson TEXT NOT NULL, roomsJson TEXT NOT NULL, snapshotId TEXT NOT NULL, PRIMARY KEY(snapshotId, lessonId, date, startMinute, endMinute))")
                db.execSQL("INSERT INTO school_courses_new SELECT c.lessonId, c.date, c.startMinute, c.endMinute, c.name, c.teachersJson, c.roomsJson, s.id FROM school_courses c CROSS JOIN school_snapshot_new s")
                db.execSQL("CREATE TABLE timetable_meta (id INTEGER NOT NULL PRIMARY KEY, currentKey TEXT NOT NULL)")
                db.execSQL("INSERT INTO timetable_meta SELECT 1, id FROM school_snapshot_new")
                db.execSQL("DROP TABLE school_courses")
                db.execSQL("DROP TABLE school_snapshot")
                db.execSQL("ALTER TABLE school_courses_new RENAME TO school_courses")
                db.execSQL("ALTER TABLE school_snapshot_new RENAME TO school_snapshot")
                // local_changes is untouched: owner/business/semester scopes remain byte-for-byte identical.
            }
        }
        @Volatile private var instance: TimetableDatabase? = null
        fun get(context: Context): TimetableDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TimetableDatabase::class.java,
                "timetable.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
