package cn.aust.mytimetable.school

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import cn.aust.mytimetable.data.LocalTimetableStore
import cn.aust.mytimetable.data.StoredSemester
import cn.aust.mytimetable.data.TimetableDatabase
import java.time.LocalDate
import java.time.ZoneId
import androidx.work.ExistingWorkPolicy
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import cn.aust.mytimetable.data.BackupCodec
import cn.aust.mytimetable.data.BackupSummary
import cn.aust.mytimetable.data.TimetableBackup
import cn.aust.mytimetable.data.SyncReview

data class SchoolUiState(
    val initialized: Boolean = false,
    val busy: Boolean = true,
    val signedIn: Boolean = false,
    val vaultProblem: Boolean = false,
    val storageProblem: Boolean = false,
    val status: String = "正在读取本机课表与登录信息…",
    val error: String? = null,
    val syncNotice: Pair<Long, String?>? = null,
    val businesses: List<BusinessType> = emptyList(),
    val stored: StoredSemester? = null,
    val preview: WeekPreview? = null,
    val currentKey: String? = null,
    val currentSuccessfulAt: Long? = null,
    val semesters: List<SemesterChoice> = emptyList(),
    val notice: String? = null,
    val backupPreview: BackupSummary? = null,
    val syncReview: SyncReview? = null,
) {
    val viewingCurrent: Boolean get() = stored == null || currentKey == semesterKey(stored.download.business.id, stored.download.semester.id)
}

class SchoolViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(SchoolUiState())
        private set
    private val worker = SchoolWorkQueue.executor
    private val main = Handler(Looper.getMainLooper())
    private val epoch = SessionEpoch()
    private val api = SchoolApi()
    private val vault = LoginVault(application)
    private val store = LocalTimetableStore(TimetableDatabase.get(application).timetable())
    // Only the worker accesses credentials. UI state never contains them.
    private var saved: SavedLogin? = null
    private var pendingBackup: TimetableBackup? = null
    private val schedulePrefs = WeeklySync.prefs(application)
    private var observedResult = schedulePrefs.getLong("seen", 0)
    private val resultListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "result") main.post { refreshBackgroundResult() }
    }

    init {
        schedulePrefs.registerOnSharedPreferenceChangeListener(resultListener)
        worker.execute {
            var local: StoredSemester? = null
            var storageProblem = false
            var vaultProblem = false
            val errors = mutableListOf<String>()
            try { local = store.load() } catch (_: Exception) {
                storageProblem = true
                errors.add("本地课表读取失败，未清空或覆盖数据。")
            }
            try { saved = vault.load() } catch (_: Exception) {
                vaultProblem = true
                errors.add("无法读取保存的登录信息，请重置登录后重试。")
            }
            val signedIn = saved != null
            val restored = local
            val choices = if (!storageProblem) store.semesters() else emptyList()
            val currentKey = if (!storageProblem) store.currentKey() else null
            if (signedIn && restored != null) WeeklySync.schedule(application, restored.successfulAt)
            publish(0) { it.copy(initialized = true, busy = false, signedIn = signedIn, stored = restored,
                storageProblem = storageProblem, vaultProblem = vaultProblem,
                semesters = choices, currentKey = currentKey, currentSuccessfulAt = restored?.successfulAt,
                preview = restored?.week(initialWeek(restored.download.semester)),
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                status = when {
                    restored != null -> "已打开本地课表，可离线切换周次。"
                    signedIn -> "已恢复登录信息，点击同步整学期。"
                    else -> "登录后可同步整学期课表。"
                }) }
        }
    }

    fun login(account: String, password: String) {
        if (state.busy || state.signedIn || state.vaultProblem || state.storageProblem) return
        runTask("登录中…") { ticket ->
            val login = api.login(account.trim(), password) { epoch.check(ticket) }
            saveLogin(ticket, login)
            syncInitial(ticket, login)
        }
    }

    fun relogin() {
        if (state.busy || !state.signedIn || state.storageProblem) return
        runTask("使用本机保存的账号登录中…") { ticket ->
            val old = saved ?: throw SchoolFailure("本机没有登录信息。")
            val login = api.login(old.account, old.password) { epoch.check(ticket) }
            saveLogin(ticket, login)
            syncInitial(ticket, login)
        }
    }

    private fun saveLogin(ticket: Long, login: SavedLogin) {
        epoch.commit(ticket) {
            try { vault.save(login) } catch (_: Exception) {
                throw SchoolFailure("登录成功，但无法保存登录信息，请重试。")
            }
            saved = login
        }
        publish(ticket) { it.copy(signedIn = true, status = "登录成功，正在读取学期…") }
    }

    fun readCurrent(business: BusinessType? = null, automatic: Boolean = false) {
        if (state.busy || !state.signedIn || state.storageProblem) return
        val preferred = business ?: state.stored?.download?.business
        runTask("正在读取学期信息…") { ticket ->
            val syncBusiness = if (automatic) store.load()?.download?.business else preferred
            WeeklySync.attempt(getApplication())
            val login = vault.load() ?: throw SchoolFailure("请先登录。")
            withSessionRenewal(login, renew = { old ->
                publish(ticket) { it.copy(status = "登录中…") }
                val fresh = api.login(old.account, old.password) { epoch.check(ticket) }
                saveLogin(ticket, fresh)
                fresh
            }, checkActive = { epoch.check(ticket) }) { active -> syncInitial(ticket, active, syncBusiness, preserveView = automatic) }
        }
    }

    private fun syncInitial(ticket: Long, login: SavedLogin, selected: BusinessType? = null, preserveView: Boolean = false) {
        epoch.check(ticket)
        val types = api.businessTypes(login.session)
        publish(ticket) { it.copy(businesses = types) }
        val business = selected?.let { item -> types.singleOrNull { it.id == item.id }
            ?: throw SchoolFailure("学校已调整课表类型，请重新选择。") } ?: types.singleOrNull()
        if (business == null) {
            publish(ticket) { it.copy(status = "请选择课表类型。") }
            return
        }
        epoch.check(ticket)
        val semester = api.semester(login.session, business)
        val downloaded = api.fullSemester(login.session, business, semester,
            checkActive = { epoch.check(ticket) }, progress = { week, total ->
                publish(ticket) { it.copy(status = "正在同步第 $week / $total 周…") }
            })
        val review = store.previewSync(login.account, downloaded)
        if (review != null) {
            publish(ticket) { it.copy(syncReview = review, status = "请核对调课安排") }
            return
        }
        val stored = epoch.commit(ticket) {
            store.save(login.account, downloaded, System.currentTimeMillis())
        }
        val choices = store.semesters()
        val currentKey = store.currentKey()
        WeeklySync.schedule(getApplication(), stored.successfulAt, ExistingWorkPolicy.REPLACE)
        publish(ticket) { current ->
            val previous = current.preview
            val week = if (previous?.semester == semester && previous.business == business)
                previous.week else initialWeek(semester)
            val keepHistory = preserveView && !current.viewingCurrent && current.stored != null
            current.copy(stored = if (keepHistory) current.stored else stored,
                preview = if (keepHistory) current.preview else stored.week(week), currentKey = currentKey,
                currentSuccessfulAt = stored.successfulAt, semesters = mergeChoices(current.semesters, choices),
                status = "同步成功 · ${semester.weekCount} 周，共 ${downloaded.courses.size} 次课，已保存到本机。")
        }
    }

    // Browsing has no network or login dependency, even during sync or after logout.
    fun readWeek(week: Int) {
        val stored = state.stored ?: return
        if (week !in 1..stored.download.semester.weekCount) return
        state = state.copy(preview = stored.week(week))
    }

    fun editCourses(semester: Semester, courses: List<SchoolCourse>, original: SchoolCourse?,
        delete: Boolean = false, done: (String?) -> Unit) {
        if (state.busy || state.storageProblem || !state.viewingCurrent) { done("请稍候再试。"); return }
        val ticket = epoch.current()
        state = state.copy(busy = true, error = null, status = "保存中…")
        worker.execute {
            try {
                val result = epoch.commit(ticket) {
                    store.edit(semester, courses, original, delete)
                }
                main.post {
                    if (epoch.current() == ticket) {
                        state = state.copy(busy = false, stored = result,
                            preview = result.week(state.preview?.week ?: 1), status = "已保存")
                        done(null)
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (epoch.current() == ticket) {
                        state = state.copy(busy = false)
                        done(if (e is SchoolFailure) e.message else "保存失败，请重试。")
                    }
                }
            }
        }
    }

    fun logout() {
        pendingBackup = null
        WeeklySync.cancel(getApplication())
        val ticket = epoch.invalidate()
        state = state.copy(busy = true, signedIn = false, status = "正在退出登录…", error = null, syncNotice = null, businesses = emptyList(), backupPreview = null, syncReview = null)
        worker.execute {
            saved = null
            try {
                vault.clear()
                publish(ticket) { it.copy(busy = false, vaultProblem = false, status = "已退出登录，本地课表保留。") }
            } catch (_: Exception) {
                publish(ticket) { it.copy(busy = false, vaultProblem = true, error = "退出未完成，请再试一次。") }
            }
        }
    }

    private fun runTask(status: String, action: (Long) -> Unit) {
        val ticket = epoch.current()
        state = state.copy(busy = true, error = null, syncNotice = null, status = status)
        worker.execute {
            var failure: String? = null
            try {
                epoch.check(ticket)
                action(ticket)
            } catch (e: SchoolFailure) {
                failure = e.message
                publish(ticket) { it.copy(error = e.message, status = "本次操作失败，本地课表及上次成功时间不变。") }
            } catch (_: Exception) {
                failure = "本次操作未完成，请重试。原有课表已保留。"
                publish(ticket) { it.copy(error = "本次操作未完成，请重试。原有课表已保留。", status = "本次操作失败。") }
            } finally {
                if (epoch.current() == ticket) main.post {
                    if (epoch.current() == ticket && state.syncReview == null) WeeklySync.result(getApplication(), failure)
                }
                publish(ticket) { it.copy(busy = false) }
            }
        }
    }

    private fun initialWeek(semester: Semester) = api.initialWeek(semester, LocalDate.now(ZoneId.of("Asia/Shanghai")))

    private fun publish(ticket: Long, update: (SchoolUiState) -> SchoolUiState) {
        main.post { if (epoch.current() == ticket) state = update(state) }
    }

    fun checkAutomaticSync() {
        if (state.backupPreview != null || state.syncReview != null) return
        refreshBackgroundResult()
        if (!state.busy && state.signedIn && !state.storageProblem && !state.vaultProblem && SchoolWorkQueue.backgroundTasks.get() == 0) {
            val successfulAt = state.currentSuccessfulAt ?: return
            if (SyncTiming.due(successfulAt, schedulePrefs.getLong("attempt", 0), System.currentTimeMillis())) {
                readCurrent(automatic = true)
            }
        }
    }

    fun dismissSyncNotice() {
        state.syncNotice?.let { schedulePrefs.edit().putLong("seen", it.first).apply() }
        state = state.copy(syncNotice = null)
    }

    private fun refreshBackgroundResult() {
        // Finish an explicit switch/edit first, then refresh its selected semester.
        if (state.busy || state.syncReview != null) return
        val result = schedulePrefs.getLong("result", 0)
        if (result <= observedResult) return
        observedResult = result
        val error = schedulePrefs.getString("error", null)
        val ticket = epoch.current()
        val viewed = state.stored?.takeIf { !state.viewingCurrent }?.download
        worker.execute {
            try {
                val current = store.load()
                val local = viewed?.let { store.load(semesterKey(it.business.id, it.semester.id)) } ?: current
                val choices = store.semesters()
                val currentKey = store.currentKey()
                saved = vault.load()
                val signedIn = saved != null
                publish(ticket) { ui ->
                    val week = if (ui.preview?.semester == local?.download?.semester)
                        ui.preview?.week ?: 1 else local?.let { initialWeek(it.download.semester) } ?: 1
                    ui.copy(stored = local, preview = local?.week(week), signedIn = signedIn,
                        currentKey = currentKey, currentSuccessfulAt = current?.successfulAt,
                        semesters = mergeChoices(ui.semesters, choices), syncNotice = result to error)
                }
            } catch (_: Exception) {
                publish(ticket) { it.copy(syncNotice = result to "本地课表读取失败，请重新打开应用。") }
            }
        }
    }

    private fun mergeChoices(remote: List<SemesterChoice>, local: List<SemesterChoice>) =
        (remote + local).associateBy { it.key }.values.sortedByDescending { it.semester.start }

    private fun <T> authenticated(ticket: Long, action: (SavedLogin) -> T): T {
        val login = vault.load() ?: throw SchoolFailure("请先登录。")
        return withSessionRenewal(login, renew = { old ->
            publish(ticket) { it.copy(status = "登录中…") }
            val fresh = api.login(old.account, old.password) { epoch.check(ticket) }
            saveLogin(ticket, fresh)
            fresh
        }, checkActive = { epoch.check(ticket) }, read = action)
    }

    fun loadSemesters() {
        if (state.busy || !state.signedIn || state.storageProblem) return
        val business = state.stored?.download?.business ?: return
        val ticket = epoch.current()
        state = state.copy(busy = true, notice = null)
        worker.execute {
            try {
                val catalog = authenticated(ticket) { api.semesters(it.session, business) }
                val choices = mergeChoices(catalog.semesters.map { SemesterChoice(business, it, false) }, store.semesters())
                val key = semesterKey(business.id, catalog.currentId)
                val changed = store.currentKey() != key
                val current = epoch.commit(ticket) { store.recognizeCurrent(key) }
                if (changed && current != null) {
                    WeeklySync.schedule(getApplication(), current.successfulAt, ExistingWorkPolicy.REPLACE)
                }
                publish(ticket) { it.copy(semesters = choices, currentKey = key,
                    currentSuccessfulAt = current?.successfulAt ?: it.currentSuccessfulAt) }
            } catch (e: Exception) {
                publish(ticket) { it.copy(notice = if (e is SchoolFailure) e.message else "暂时无法获取学期列表，已保存的学期仍可查看。") }
            } finally { publish(ticket) { it.copy(busy = false) } }
        }
    }

    fun selectSemester(choice: SemesterChoice) {
        if (state.busy || !state.signedIn || state.storageProblem) return
        val ticket = epoch.current()
        state = state.copy(busy = true, notice = null)
        worker.execute {
            try {
                var local = store.load(choice.key)
                var schoolCurrent: String? = null
                if (local == null) {
                    local = authenticated(ticket) { login ->
                        val catalog = api.semesters(login.session, choice.business)
                        val semester = catalog.semesters.singleOrNull { it.id == choice.semester.id }
                            ?: throw SchoolFailure("学校已不再提供这个学期的课表。")
                        val isCurrent = semester.id == catalog.currentId
                        val download = api.fullSemester(login.session, choice.business, semester,
                            checkActive = { epoch.check(ticket) }, requireCurrent = isCurrent)
                        schoolCurrent = semesterKey(choice.business.id, catalog.currentId)
                        epoch.commit(ticket) { store.save(login.account, download, System.currentTimeMillis(), current = isCurrent) }
                    }
                    if (choice.key == schoolCurrent) WeeklySync.schedule(getApplication(), local.successfulAt, ExistingWorkPolicy.REPLACE)
                }
                val selected = local
                val choices = store.semesters()
                val currentTime = store.load()?.successfulAt
                publish(ticket) {
                    val currentKey = schoolCurrent ?: it.currentKey
                    val week = if (choice.key == currentKey) initialWeek(selected.download.semester) else 1
                    it.copy(stored = selected, preview = selected.week(week),
                        semesters = mergeChoices(it.semesters, choices), currentSuccessfulAt = currentTime,
                        currentKey = currentKey, status = "已切换学期")
                }
            } catch (e: Exception) {
                publish(ticket) { it.copy(notice = if (e is SchoolFailure) e.message else "暂时无法打开这个学期，请稍后重试。") }
            } finally { publish(ticket) { it.copy(busy = false) } }
        }
    }

    fun cancelSyncReview() {
        if (!state.busy) state = state.copy(syncReview = null, status = "已保留原有课表")
    }

    fun confirmSyncReview(useSchool: Set<String>) {
        if (state.busy || !state.signedIn) return
        val review = state.syncReview ?: return
        val ticket = epoch.current()
        state = state.copy(busy = true, notice = null)
        worker.execute {
            try {
                val account = vault.load()?.account ?: throw SchoolFailure("请先登录。")
                val stored = epoch.commit(ticket) { store.resolveSync(account, review, useSchool, System.currentTimeMillis()) }
                val choices = store.semesters()
                WeeklySync.schedule(getApplication(), stored.successfulAt, ExistingWorkPolicy.REPLACE)
                publish(ticket) { ui ->
                    val week = if (ui.preview?.semester == stored.download.semester) ui.preview.week else initialWeek(stored.download.semester)
                    ui.copy(syncReview = null, stored = stored, preview = stored.week(week),
                        semesters = mergeChoices(ui.semesters, choices), currentKey = semesterKey(stored.download.business.id, stored.download.semester.id),
                        currentSuccessfulAt = stored.successfulAt, notice = "同步成功", status = "同步成功")
                }
            } catch (e: Exception) {
                publish(ticket) { it.copy(notice = if (e is SchoolFailure) e.message else "保存失败，原有课表已保留，请重试。") }
            } finally { publish(ticket) { it.copy(busy = false) } }
        }
    }

    fun dismissNotice() { state = state.copy(notice = null) }

    private fun backupTask(status: String, action: (Long, String) -> Unit) {
        if (state.busy || !state.signedIn) return
        val ticket = epoch.current()
        state = state.copy(busy = true, notice = null, status = status)
        worker.execute {
            try {
                epoch.check(ticket)
                val account = vault.load()?.account ?: throw SchoolFailure("请先登录。")
                action(ticket, account)
            } catch (e: Exception) {
                publish(ticket) { it.copy(notice = if (e is SchoolFailure) e.message else
                    "无法完成文件操作，请检查文件位置和剩余空间后重试。原有课表已保留。") }
            } finally { publish(ticket) { it.copy(busy = false) } }
        }
    }

    fun exportBackup(uri: Uri) = backupTask("备份中…") { ticket, account ->
        val bytes = BackupCodec.encode(store.backup(account))
        epoch.check(ticket)
        val resolver = getApplication<Application>().contentResolver
        (resolver.openOutputStream(uri, "wt") ?: throw SchoolFailure("无法写入所选位置，请换一个位置重试。"))
            .use { it.write(bytes); it.flush() }
        publish(ticket) { it.copy(notice = "备份已保存。") }
    }

    fun previewBackup(uri: Uri) {
        if (state.busy) return
        pendingBackup = null
        state = state.copy(backupPreview = null)
        backupTask("正在读取备份…") { ticket, account ->
            val resolver = getApplication<Application>().contentResolver
            val backup = (resolver.openInputStream(uri) ?: throw SchoolFailure("无法打开这份文件，请重新选择。"))
                .use(BackupCodec::decode)
            val staging = Room.inMemoryDatabaseBuilder(getApplication(), TimetableDatabase::class.java).build()
            val summary = try {
                LocalTimetableStore(staging.timetable()).restore(account, backup)
            } finally { staging.close() }
            main.post {
                if (epoch.current() == ticket) {
                    pendingBackup = backup
                    state = state.copy(backupPreview = summary)
                }
            }
        }
    }

    fun cancelRestore() {
        if (state.busy) return
        pendingBackup = null
        state = state.copy(backupPreview = null)
    }

    fun restoreBackup() {
        if (state.busy) return
        val backup = pendingBackup ?: return
        pendingBackup = null
        state = state.copy(backupPreview = null)
        backupTask("恢复中…") { ticket, account ->
            epoch.commit(ticket) { store.restore(account, backup) }
            val current = store.load()!!
            val choices = store.semesters()
            val key = store.currentKey()
            // Do not immediately overwrite a restored older backup on returning from the picker.
            WeeklySync.attempt(getApplication())
            WeeklySync.schedule(getApplication(), current.successfulAt, ExistingWorkPolicy.REPLACE)
            publish(ticket) { it.copy(stored = current, preview = current.week(initialWeek(current.download.semester)),
                semesters = choices, currentKey = key, currentSuccessfulAt = current.successfulAt,
                storageProblem = false, notice = "课表已恢复。", status = "已恢复") }
        }
    }

    override fun onCleared() {
        schedulePrefs.unregisterOnSharedPreferenceChangeListener(resultListener)
        epoch.invalidate()
    }
}
