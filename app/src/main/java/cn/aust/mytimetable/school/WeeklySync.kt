package cn.aust.mytimetable.school

import android.content.Context
import androidx.work.*
import cn.aust.mytimetable.data.LocalTimetableStore
import cn.aust.mytimetable.data.TimetableDatabase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// All credential and timetable writes share this queue, including background work.
internal object SchoolWorkQueue {
    val executor = Executors.newSingleThreadExecutor()
    val accountEpoch = SessionEpoch()
    val backgroundTasks = AtomicInteger()
}

internal object WeeklySync {
    private const val NAME = "weekly-school-sync"
    fun prefs(context: Context) = context.getSharedPreferences("sync_schedule", Context.MODE_PRIVATE)
    fun schedule(context: Context, successfulAt: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<WeeklySyncWorker>()
            .setInitialDelay(SyncTiming.automaticDelay(successfulAt, prefs(context).getLong("attempt", 0),
                System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, SyncTiming.RETRY, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
    }
    fun cancel(context: Context) {
        SchoolWorkQueue.accountEpoch.invalidate()
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
        prefs(context).edit().clear().apply()
    }
    fun attempt(context: Context) { prefs(context).edit().putLong("attempt", System.currentTimeMillis()).apply() }
    fun result(context: Context, error: String?) {
        prefs(context).edit().putLong("result", System.currentTimeMillis()).putString("error", error).apply()
    }
}

class WeeklySyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val ticket = SchoolWorkQueue.accountEpoch.current()
        SchoolWorkQueue.backgroundTasks.incrementAndGet()
        return try {
            SchoolWorkQueue.executor.submit<Result> {
                val guard = {
                    SchoolWorkQueue.accountEpoch.check(ticket)
                    if (isStopped) throw SchoolFailure("操作已取消。")
                }
                try {
                    guard()
                    val vault = LoginVault(applicationContext)
                    val login = vault.load() ?: return@submit Result.success()
                    val store = LocalTimetableStore(TimetableDatabase.get(applicationContext).timetable())
                    val local = store.load() ?: return@submit Result.success()
                    // Recheck after waiting for the shared queue: a manual attempt or restore may have run first.
                    if (!SyncTiming.due(local.successfulAt, WeeklySync.prefs(applicationContext).getLong("attempt", 0),
                            System.currentTimeMillis())) {
                        WeeklySync.schedule(applicationContext, local.successfulAt, ExistingWorkPolicy.APPEND_OR_REPLACE)
                        return@submit Result.success()
                    }
                    WeeklySync.attempt(applicationContext)
                    val api = SchoolApi()
                    val downloaded = withSessionRenewal(login, renew = { old ->
                        val fresh = api.login(old.account, old.password, guard)
                        SchoolWorkQueue.accountEpoch.commit(ticket) { guard(); vault.save(fresh) }
                        fresh
                    }, checkActive = guard) { active ->
                        val business = api.businessTypes(active.session).singleOrNull { it.id == local.download.business.id }
                            ?: throw SchoolFailure("学校已调整课表类型，请打开应用重新同步。")
                        val semester = api.semester(active.session, business)
                        api.fullSemester(active.session, business, semester, checkActive = guard)
                    }
                    SchoolWorkQueue.accountEpoch.commit(ticket) {
                        guard()
                        val stored = store.save(login.account, downloaded, System.currentTimeMillis())
                        WeeklySync.result(applicationContext, null)
                        WeeklySync.schedule(applicationContext, stored.successfulAt, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    }
                    Result.success()
                } catch (e: Exception) {
                    if (isStopped || SchoolWorkQueue.accountEpoch.current() != ticket) Result.success()
                    else {
                        WeeklySync.result(applicationContext, if (e is SchoolFailure) e.message else "自动同步失败，请稍后重试。")
                        Result.retry()
                    }
                }
            }.get()
        } catch (_: Exception) { Result.retry() }
        finally { SchoolWorkQueue.backgroundTasks.decrementAndGet() }
    }
}
