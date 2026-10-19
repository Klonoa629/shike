package cn.aust.mytimetable.school

import cn.aust.mytimetable.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupCodecTest {
    private val snapshot = SnapshotRow("1:bs", "account-hash", "b", "本科", "s", "测试学期",
        "2026-09-01", "2026-12-31", 1, 18, 0, 1000)
    private val backup = TimetableBackup(2000, snapshot.id, listOf(snapshot), emptyList(), emptyList())

    @Test fun versionedFileRoundTripsWithoutCredentials() {
        val bytes = BackupCodec.encode(backup)
        assertEquals(backup, BackupCodec.decode(ByteArrayInputStream(bytes)))
        val text = bytes.toString(Charsets.UTF_8)
        listOf("password", "token", "secret", "identity").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun damagedUnsupportedAndUnrelatedFilesAreRejected() {
        val file = BackupCodec.encode(backup).toString(Charsets.UTF_8)
        listOf(file.take(file.length / 2), file.replace("account-hash", "changed-hash"),
            file.replace("\"version\":1", "\"version\":2"), "{}", "not a backup").forEach { text ->
            assertThrows(SchoolFailure::class.java) { BackupCodec.decode(ByteArrayInputStream(text.toByteArray())) }
        }
    }

    @Test fun oversizedFileStopsAtLimit() {
        val input = object : java.io.InputStream() {
            var readCount = 0
            override fun read(): Int { readCount++; return 65 }
        }
        assertThrows(SchoolFailure::class.java) { BackupCodec.decode(input) }
        assertEquals(BackupCodec.MAX_BYTES + 1, input.readCount)
    }
}
