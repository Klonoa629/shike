package cn.aust.mytimetable.school

import org.junit.Assert.*
import org.junit.Test

class SessionRenewalTest {
    private val old = SavedLogin("fixture", "dummy", SchoolSession("old", "secret", "0"))
    private val fresh = SavedLogin("fixture", "dummy", SchoolSession("new", "secret", "0"))

    @Test fun expiredTokenRenewsAndRestartsReadOnce() {
        val reads = mutableListOf<String>()
        var logins = 0
        val result = withSessionRenewal(old, { logins++; fresh }, {}) {
            reads.add(it.session.token)
            if (it === old) throw SchoolSessionExpired()
            "complete snapshot"
        }
        assertEquals("complete snapshot", result)
        assertEquals(listOf("old", "new"), reads)
        assertEquals(1, logins)
    }

    @Test fun secondExpiryDoesNotLoop() {
        var logins = 0
        assertThrows(SchoolSessionExpired::class.java) {
            withSessionRenewal(old, { logins++; fresh }, {}) { throw SchoolSessionExpired() }
        }
        assertEquals(1, logins)
    }

    @Test fun networkOrBusinessFailureDoesNotSendPassword() {
        for (error in listOf(SchoolFailure("网络超时"), SchoolFailure("业务拒绝"))) {
            var logins = 0
            assertSame(error, assertThrows(SchoolFailure::class.java) {
                withSessionRenewal(old, { logins++; fresh }, {}) { throw error }
            })
            assertEquals(0, logins)
        }
    }

    @Test fun logoutDuringReadPreventsRenewal() {
        val epoch = SessionEpoch()
        var logins = 0
        assertThrows(SchoolFailure::class.java) {
            withSessionRenewal(old, { logins++; fresh }, { epoch.check(0) }) {
                epoch.invalidate()
                throw SchoolSessionExpired()
            }
        }
        assertEquals(0, logins)
    }

    @Test fun failedLoginDoesNotReadAgain() {
        var reads = 0
        assertThrows(SchoolFailure::class.java) {
            withSessionRenewal(old, { throw SchoolFailure("需要验证码") }, {}) {
                reads++
                throw SchoolSessionExpired()
            }
        }
        assertEquals(1, reads)
    }

    @Test fun onlyVerifiedCodeIsSessionExpiry() {
        assertThrows(SchoolSessionExpired::class.java) { SchoolProtocol.decode("""{"err_code":"20","err_msg":"token invalid"}""") }
        for (code in listOf("03000", "401", "unknown")) {
            val error = assertThrows(SchoolFailure::class.java) { SchoolProtocol.decode("""{"err_code":"$code"}""") }
            assertFalse(error is SchoolSessionExpired)
        }
    }

    @Test fun weeklyBoundaryAndFailureCooldown() {
        val success = 1_000L
        assertFalse(SyncTiming.due(success, 0, success + SyncTiming.WEEK - 1))
        assertTrue(SyncTiming.due(success, 0, success + SyncTiming.WEEK))
        val attempt = success + SyncTiming.WEEK
        assertFalse(SyncTiming.due(success, attempt, attempt + SyncTiming.RETRY - 1))
        assertTrue(SyncTiming.due(success, attempt, attempt + SyncTiming.RETRY))
        assertEquals(SyncTiming.WEEK, SyncTiming.delay(success, success))
    }

    @Test fun queuedBackgroundWorkHonorsRecentForegroundFailure() {
        val success = 1000L
        val failure = success + SyncTiming.WEEK
        assertEquals(SyncTiming.RETRY - 100, SyncTiming.automaticDelay(success, failure, failure + 100))
        assertFalse(SyncTiming.due(success, failure, failure + 100))
        assertEquals(0L, SyncTiming.automaticDelay(success, failure, failure + SyncTiming.RETRY))
    }

    @Test fun restoredOldBackupKeepsCooldownWhenScheduleIsRecreated() {
        val originalSuccess = 1000L
        val restoreTime = originalSuccess + 3 * SyncTiming.WEEK
        assertEquals(SyncTiming.RETRY, SyncTiming.automaticDelay(originalSuccess, restoreTime, restoreTime))
        val restartTime = restoreTime + 60_000
        assertEquals(SyncTiming.RETRY - 60_000, SyncTiming.automaticDelay(originalSuccess, restoreTime, restartTime))
    }

    @Test fun newSuccessUsesWeeklyDeadlineInsteadOfFailureRetryDeadline() {
        val attempt = 1000L + SyncTiming.WEEK
        val success = attempt + 5000
        assertEquals(SyncTiming.WEEK, SyncTiming.automaticDelay(success, attempt, success))
        assertFalse(SyncTiming.due(success, attempt, attempt + SyncTiming.RETRY))
        assertTrue(SyncTiming.due(success, attempt, success + SyncTiming.WEEK))
    }

    @Test fun logoutWhileRenewingPreventsReadingWithNewSession() {
        val epoch = SessionEpoch()
        var reads = 0
        assertThrows(SchoolFailure::class.java) {
            withSessionRenewal(old, { epoch.invalidate(); fresh }, { epoch.check(0) }) {
                reads++
                throw SchoolSessionExpired()
            }
        }
        assertEquals(1, reads)
    }
}
