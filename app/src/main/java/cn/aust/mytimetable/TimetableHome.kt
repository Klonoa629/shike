package cn.aust.mytimetable

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aust.mytimetable.school.SchoolCourse
import cn.aust.mytimetable.school.SchoolViewModel
import cn.aust.mytimetable.school.WeekPreview
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay

@Composable
internal fun TimetableTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFF86D5C0), onPrimary = Color(0xFF103B31), background = Color(0xFF11161B), surface = Color(0xFF11161B),
        primaryContainer = Color(0xFF254B40), onPrimaryContainer = Color(0xFFB5EFDA),
        onBackground = Color(0xFFE2E8ED), onSurface = Color(0xFFE2E8ED),
    ) else lightColorScheme(
        primary = Color(0xFF24735F), onPrimary = Color.White, background = Color(0xFFF9FBFC), surface = Color(0xFFF9FBFC),
        primaryContainer = Color(0xFFDCEEE5), onPrimaryContainer = Color(0xFF164B3E),
        onBackground = Color(0xFF202B34), onSurface = Color(0xFF202B34),
    ), content = content)
}

@Composable
fun TimetableHome(model: SchoolViewModel) {
    val prefs = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var mode by rememberSaveable { mutableStateOf(prefs.getString("mode", "system") ?: "system") }
    val state = model.state
    TimetableTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else AnimatedContent(targetState = state.signedIn, transitionSpec = {
                val direction = if (targetState) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(300)) togetherWith slideOutOfContainer(direction, tween(300))
            }, label = "accountNavigation") { signedIn ->
                if (signedIn) SignedInTimetable(model, mode) { value ->
                    mode = value
                    prefs.edit().putString("mode", value).apply()
                } else LoginPage(model)
            }
        }
    }
}

@Composable
private fun SignedInTimetable(model: SchoolViewModel, mode: String, changeMode: (String) -> Unit) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var chooseWeek by remember { mutableStateOf(false) }
    var chooseSemester by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editCourse by remember { mutableStateOf<SchoolCourse?>(null) }
    var detail by remember { mutableStateOf<SchoolCourse?>(null) }
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))) }
    val state = model.state
    val semester = state.stored?.download?.semester
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportBackup)
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::previewBackup)
    }
    val pager = rememberPagerState(initialPage = (state.preview?.week ?: 1) - 1,
        pageCount = { semester?.weekCount ?: 1 })
    val scope = rememberCoroutineScope()
    // Only a new semester resets the pager. Settled swipes update the model, never bounce back.
    LaunchedEffect(semester?.id) {
        if (semester != null) pager.scrollToPage(((state.preview?.week ?: 1) - 1).coerceIn(0, semester.weekCount - 1))
    }
    LaunchedEffect(pager, semester?.id) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { model.readWeek(it + 1) }
    }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")); delay(30_000) } }
    LaunchedEffect(state.syncNotice) {
        state.syncNotice?.let { (_, error) ->
            syncResult = (if (error == null) "同步成功" else "同步失败") to error
        }
    }
    LaunchedEffect(state.busy, state.signedIn, state.backupPreview, state.syncReview) {
        if (!state.busy && state.signedIn) model.checkAutomaticSync()
    }
    val goToWeek: (Int) -> Unit = { week ->
        scope.launch { pager.animateScrollToPage(week - 1, animationSpec = tween(380)) }
    }
    Box(Modifier.fillMaxSize()) {
        BackHandler(settings) { settings = false }
        AnimatedContent(targetState = editing, modifier = Modifier.fillMaxSize(), transitionSpec = {
            val direction = if (targetState) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
            slideIntoContainer(direction, tween(300)) togetherWith slideOutOfContainer(direction, tween(300))
        }, label = "editorNavigation") { showingEditor ->
        if (showingEditor && semester != null) CourseEditor(semester, editCourse, state.busy,
            onDismiss = { editing = false },
            onSave = { courses, delete, done -> model.editCourses(semester, courses, editCourse, delete, done) })
        else Scaffold { padding ->
            AnimatedContent(targetState = settings, modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    val direction = if (targetState) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                    slideIntoContainer(direction, tween(300)) togetherWith slideOutOfContainer(direction, tween(300))
                }, label = "settingsNavigation") { showingSettings ->
                if (showingSettings) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(onClick = { settings = false }) { Text("‹ 返回课表") }
                        Text("设置", style = MaterialTheme.typography.headlineSmall)
                        Text("学期", style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(onClick = { chooseSemester = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(semester?.label ?: "选择学期", textAlign = TextAlign.Center)
                        }
                        Text("外观", style = MaterialTheme.typography.titleMedium)
                        listOf("light" to "白天", "dark" to "黑夜", "system" to "跟随系统").forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected = mode == value, role = Role.RadioButton, onClick = {
                                changeMode(value)
                            }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mode == value, onClick = null)
                                Text(label, Modifier.padding(start = 12.dp))
                            }
                        }
                        HorizontalDivider()
                        Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = {
                                exportBackup.launch("时课备份-${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json")
                            }, enabled = !state.busy && state.stored != null && !state.storageProblem,
                                modifier = Modifier.weight(1f)) { Text("导出备份") }
                            FilledTonalButton(onClick = { restoreBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                                enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("恢复备份") }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { model.logout(); settings = false }, enabled = state.signedIn || state.vaultProblem,
                            modifier = Modifier.fillMaxWidth()) { Text("退出账号") }
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        // Long jumps pre-position currentPage; only a settled page is a displayed week.
                        val preview = state.stored?.week((pager.settledPage + 1).coerceAtMost(semester?.weekCount ?: 1))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(preview?.let { "第 ${it.week} 周" } ?: "我的课表",
                                    Modifier.clip(RoundedCornerShape(9.dp)).clickable(enabled = preview != null) { chooseWeek = true }
                                        .padding(vertical = 5.dp, horizontal = 2.dp),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                if (state.viewingCurrent) HeaderButton("本周", enabled = semester?.weekAt(now.toLocalDate()) != null) {
                                    semester?.weekAt(now.toLocalDate())?.let(goToWeek)
                                }
                                Spacer(Modifier.weight(1f))
                                if (state.viewingCurrent) HeaderButton("＋", enabled = semester != null && !state.busy) { editCourse = null; editing = true }
                                if (state.viewingCurrent) HeaderButton("同步", enabled = !state.busy && !state.storageProblem) {
                                    model.readCurrent()
                                }
                                HeaderButton("设置") { settings = true }
                            }
                            if (!state.viewingCurrent) Text(semester?.label.orEmpty(), fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            preview?.let { val range = it.semester.range(it.week)
                                Text("${range.start.format(DateTimeFormatter.ofPattern("M.d"))}—${range.end.format(DateTimeFormatter.ofPattern("M.d"))}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (editing) "保存中…" else if (state.status == "登录中…") "登录中…" else "同步中…", Modifier.padding(horizontal = 12.dp), fontSize = 10.sp)
                        }
                        if (preview == null) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(state.status)
                            }
                        } else {
                            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                                state.stored?.week(page + 1)?.let {
                                    WeekTimeline(it, now, { detail = it }, { if (!state.busy && state.viewingCurrent) { editCourse = it; editing = true } }, Modifier.fillMaxSize())
                                }
                            }
                        }
                        if (state.signedIn && state.viewingCurrent && state.businesses.size > 1) {
                            Row { state.businesses.forEach { business ->
                                FilledTonalButton(onClick = { model.readCurrent(business) }, enabled = !state.busy) { Text(business.name) }
                            } }
                        }
                    }
                }
            }
        }
        }
        if (chooseSemester) {
            AlertDialog(onDismissRequest = { chooseSemester = false }, title = { Text("切换学期") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.semesters.forEach { choice ->
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = if (choice.semester == semester) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                model.selectSemester(choice); chooseSemester = false; settings = false
                            }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(choice.semester.label, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(if (choice.key == state.currentKey) "当前学期" else null,
                                    if (choice.saved) "已保存" else "首次查看需联网").joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooseSemester = false }) { Text("关闭") } },
                dismissButton = { TextButton(onClick = model::loadSemesters, enabled = !state.busy) { Text("获取学校学期") } })
        }
        androidx.compose.animation.AnimatedVisibility(visible = state.syncReview != null,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it },
            exit = androidx.compose.animation.slideOutHorizontally(tween(300)) { it }) {
            val displayedReview = remember { state.syncReview }
            displayedReview?.let { review ->
                SyncReviewPage(review, state.busy, model::cancelSyncReview, model::confirmSyncReview)
            }
        }
        state.notice?.let { message ->
            AlertDialog(onDismissRequest = model::dismissNotice, containerColor = MaterialTheme.colorScheme.background,
                title = { Text("提示") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissNotice) { Text("确认") } })
        }
        state.backupPreview?.let { backup ->
            AlertDialog(onDismissRequest = model::cancelRestore, containerColor = MaterialTheme.colorScheme.background,
                title = { Text("恢复这份备份？") }, text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("备份时间：${java.time.Instant.ofEpochMilli(backup.createdAt).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                        Text("${backup.semesters.size} 个学期 · ${backup.courses} 次上课")
                        Text(backup.semesters.joinToString("\n"))
                        Text("将覆盖全部学期的课表和备注，备份后的修改将丢失。")
                    }
                }, confirmButton = { TextButton(onClick = model::restoreBackup, enabled = !state.busy) { Text("确认恢复") } },
                dismissButton = { TextButton(onClick = model::cancelRestore, enabled = !state.busy) { Text("取消") } })
        }
        if (chooseWeek && semester != null) {
            AlertDialog(onDismissRequest = { chooseWeek = false }, title = { Text("选择周次") }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    (1..semester.weekCount).chunked(4).forEach { weeks ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            weeks.forEach { week -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                WeekNumberButton(week, week == pager.currentPage + 1) { goToWeek(week); chooseWeek = false }
                            } }
                            repeat(4 - weeks.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }, confirmButton = { FilledTonalButton(onClick = { chooseWeek = false }) { Text("关闭") } })
        }
        syncResult?.let { (title, reason) ->
            Dialog(onDismissRequest = { syncResult = null; model.dismissSyncNotice() }) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        reason?.let { Text(it, Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), textAlign = TextAlign.Center) }
                        FilledTonalButton(onClick = { syncResult = null; model.dismissSyncNotice() }, modifier = Modifier.fillMaxWidth()) { Text("确认") }
                    }
                }
            }
        }
        detail?.let { course ->
            AlertDialog(onDismissRequest = { detail = null }, title = { Text(course.name) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${course.date}  周${"一二三四五六日"[course.date.dayOfWeek.value - 1]}")
                    Text("时间  ${course.start}—${course.end}")
                    Text("教师  ${course.teachers.joinToString("、")}")
                    Text("地点  ${course.rooms.joinToString("、").ifBlank { "待定" }}")
                    Text("备注  ${course.notes.ifBlank { "暂无" }}")
                    Text(if (course.manual) "手动添加" else if (course.localId != null) "学校教务 · 已单独修改" else "学校教务", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, confirmButton = { FilledTonalButton(onClick = { detail = null }) { Text("关闭") } })
        }
    }
}

@Composable
private fun LoginPage(model: SchoolViewModel) {
    // Credentials deliberately do not survive saved-instance-state serialization.
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state = model.state
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val ready = !state.busy && !state.vaultProblem && !state.storageProblem && account.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (ready) { focus.clearFocus(); model.login(account, password) }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF194F46))) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text("时课", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(),
                label = { Text("学号") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = {
                    focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                }))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("教务密码") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = submit, enabled = ready, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)) {
                Text(if (state.busy) "请稍候…" else "登录")
            }
            if (state.vaultProblem) TextButton(onClick = model::logout, enabled = !state.busy) { Text("重置登录") }
        }
    }
}

@Composable
private fun WeekTimeline(preview: WeekPreview, now: ZonedDateTime, onCourse: (SchoolCourse) -> Unit, onEdit: (SchoolCourse) -> Unit, modifier: Modifier) {
    val first = preview.semester.start.with(TemporalAdjusters.previousOrSame(preview.semester.weekStart)).plusWeeks((preview.week - 1).toLong())
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
    val primary = MaterialTheme.colorScheme.primary
    val lightCards = listOf(0xFFDCEEE2, 0xFFDCEAF8, 0xFFEAE1F5, 0xFFF8E8D7, 0xFFDCEFF0, 0xFFF4E0E5)
    val darkCards = listOf(0xFF263F35, 0xFF253B52, 0xFF3C304D, 0xFF4B382A, 0xFF233F43, 0xFF4A303A)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp)) {
            Spacer(Modifier.width(40.dp))
            repeat(7) { day ->
                val date = first.plusDays(day.toLong())
                Column(Modifier.weight(1f).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("一二三四五六日"[date.dayOfWeek.value - 1].toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.padding(top = 2.dp).size(24.dp).clip(RoundedCornerShape(12.dp)).background(if (date == now.toLocalDate()) primary else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text("${date.dayOfMonth}", fontSize = 10.sp, lineHeight = 10.sp,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)),
                            color = if (date == now.toLocalDate()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp, end = 4.dp, bottom = 8.dp)) {
            val labelHeight = with(LocalDensity.current) { 20.sp.toDp() }
            val totalHeight = (maxHeight - labelHeight).coerceAtLeast(0.dp)
            val origin = labelHeight / 2
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(40.dp).fillMaxHeight()) {
                    repeat(15) { hour ->
                        if (hour != 5) Box(Modifier.offset(y = totalHeight * timelineFraction(hour * 60))
                            .fillMaxWidth().height(labelHeight), contentAlignment = Alignment.Center) {
                            Text("%02d:00".format(hour + 8), fontSize = 9.sp, lineHeight = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                repeat(7) { day ->
                    val date = first.plusDays(day.toLong())
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Canvas(Modifier.fillMaxWidth().offset(y = origin).height(totalHeight)) {
                            repeat(15) { hour ->
                                if (hour != 5) {
                                    val y = size.height * timelineFraction(hour * 60)
                                    drawLine(line, Offset(0f, y), Offset(size.width, y))
                                }
                            }
                            drawLine(line, Offset.Zero, Offset(0f, size.height))
                        }
                        preview.courses.filter { it.date == date }.forEach { course ->
                            val start = timelineFraction(course.start.toSecondOfDay() / 60 - 480)
                            val end = timelineFraction(course.end.toSecondOfDay() / 60 - 480)
                            val index = Math.floorMod(course.lessonId.hashCode(), lightCards.size)
                            Box(Modifier.offset(y = origin + totalHeight * start).fillMaxWidth().height(totalHeight * (end - start))
                                .padding(horizontal = 2.dp, vertical = 2.dp).clip(RoundedCornerShape(7.dp))
                                .background(Color((if (dark) darkCards else lightCards)[index]))
                                .combinedClickable(onClick = { onCourse(course) }, onLongClickLabel = "编辑这次上课", onLongClick = { onEdit(course) }).padding(horizontal = 3.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(course.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 10.sp,
                                    lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
