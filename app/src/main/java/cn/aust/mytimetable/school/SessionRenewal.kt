package cn.aust.mytimetable.school

/** Retry the complete read once, and only for the verified expired-session response. */
internal fun <T> withSessionRenewal(
    original: SavedLogin,
    renew: (SavedLogin) -> SavedLogin,
    checkActive: () -> Unit,
    read: (SavedLogin) -> T,
): T {
    checkActive()
    return try { read(original) } catch (_: SchoolSessionExpired) {
        checkActive()
        val refreshed = renew(original)
        checkActive()
        read(refreshed)
    }
}

internal object SyncTiming {
    const val WEEK = 7L * 24 * 60 * 60 * 1000
    const val RETRY = 5L * 60 * 60 * 1000
    fun delay(successfulAt: Long, now: Long): Long = (successfulAt + WEEK - now).coerceAtLeast(0)
    // Both foreground and WorkManager honor the same persisted attempt time.
    fun automaticDelay(successfulAt: Long, lastAttempt: Long, now: Long): Long =
        maxOf(delay(successfulAt, now), if (lastAttempt == 0L) 0L else (lastAttempt + RETRY - now).coerceAtLeast(0))
    fun due(successfulAt: Long, lastAttempt: Long, now: Long): Boolean =
        automaticDelay(successfulAt, lastAttempt, now) == 0L
}
