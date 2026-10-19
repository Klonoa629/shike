package cn.aust.mytimetable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import cn.aust.mytimetable.school.*
import java.time.DayOfWeek
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseEditor(semester: Semester, original: SchoolCourse?, busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<SchoolCourse>, Boolean, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var teacher by remember { mutableStateOf(original?.teachers?.joinToString("、") ?: "") }
    var location by remember { mutableStateOf(original?.rooms?.joinToString("、") ?: "") }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var slots by remember { mutableStateOf(listOf(ScheduleSlot(
        original?.let { semester.weekAt(it.date)?.let(::setOf) } ?: emptySet(), original?.date?.dayOfWeek ?: DayOfWeek.MONDAY,
        original?.start ?: LocalTime.of(8, 0), original?.end ?: LocalTime.of(10, 0)))) }
    var weekPicker by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val dirty = name != (original?.name ?: "") || teacher != (original?.teachers?.joinToString("、") ?: "") ||
        location != (original?.rooms?.joinToString("、") ?: "") || notes != (original?.notes ?: "") ||
        slots != listOf(ScheduleSlot(original?.let { semester.weekAt(it.date)?.let(::setOf) } ?: emptySet(),
            original?.date?.dayOfWeek ?: DayOfWeek.MONDAY, original?.start ?: LocalTime.of(8, 0), original?.end ?: LocalTime.of(10, 0)))
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() } }
    fun submit(delete: Boolean = false) {
        error = null
        try {
            val expanded = if (delete) emptyList() else ManualSchedule.expand(semester, name, teacher, location, notes, slots)
            val courses = if (original != null && expanded.isNotEmpty()) listOf(expanded.single().copy(
                lessonId = original.lessonId, localId = original.localId, manual = original.manual,
                teachers = if (teacher == original.teachers.joinToString("、")) original.teachers else expanded.single().teachers,
                rooms = if (location == original.rooms.joinToString("、")) original.rooms else expanded.single().rooms)) else expanded
            onSave(courses, delete) { failure -> if (failure == null) onDismiss() else error = failure }
        } catch (e: SchoolFailure) { error = e.message }
    }
    Box(Modifier.fillMaxSize()) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(if (original == null) "添加课程" else "编辑这次上课",
                        Modifier.fillMaxWidth().padding(horizontal = 56.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Box(Modifier.align(Alignment.CenterStart)) { HeaderButton("返回", enabled = !busy, onClick = close) }
                    Box(Modifier.align(Alignment.CenterEnd)) { HeaderButton(if (busy) "稍候" else "保存", enabled = !busy) { submit() } }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("课程信息", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("课程名称") }, singleLine = true)
                    OutlinedTextField(teacher, { teacher = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("教师") }, singleLine = true)
                    OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("地点（选填）") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("备注（选填）") }, minLines = 2, maxLines = 4)
                    Spacer(Modifier.height(4.dp))
                    Text("上课安排", style = MaterialTheme.typography.titleMedium)
                    slots.forEachIndexed { index, slot ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("安排 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                    if (original == null && slots.size > 1) FilledTonalButton(onClick = { slots = slots.filterIndexed { i, _ -> i != index } }, enabled = !busy) { Text("移除") }
                                }
                                OutlinedButton(onClick = { weekPicker = index }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (slot.weeks.isEmpty()) "选择周次" else "第 ${slot.weeks.sorted().joinToString("、")} 周")
                                }
                                Row(Modifier.fillMaxWidth()) {
                                    DayOfWeek.entries.forEach { day ->
                                        FilledTonalButton(onClick = { slots = slots.toMutableList().also { it[index] = slot.copy(day = day) } }, enabled = !busy,
                                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.textButtonColors(containerColor = if (slot.day == day) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                            Text("一二三四五六日"[day.value - 1].toString())
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(true, false).forEach { start ->
                                        val time = if (start) slot.start else slot.end
                                        OutlinedButton(onClick = {
                                            timePicker = index to start
                                        }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text("${if (start) "开始" else "结束"} $time") }
                                    }
                                }
                            }
                        }
                    }
                    if (original == null) OutlinedButton(onClick = { slots = slots + slots.last().copy(weeks = emptySet()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加另一组时间") }
                    Text("08:00—22:00 · 每次至少 1 小时 · 时间不可重叠", style = MaterialTheme.typography.bodySmall)
                    if (original != null) FilledTonalButton(onClick = { confirmation = "delete" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除这次上课", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        timePicker?.let { (index, start) ->
            val time = if (start) slots[index].start else slots[index].end
            val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
            AlertDialog(onDismissRequest = { timePicker = null }, containerColor = MaterialTheme.colorScheme.background,
                title = { Text(if (start) "开始时间" else "结束时间") }, text = { TimeInput(picker) },
                confirmButton = { FilledTonalButton(onClick = {
                    val value = LocalTime.of(picker.hour, picker.minute)
                    slots = slots.toMutableList().also { it[index] = if (start) it[index].copy(start = value) else it[index].copy(end = value) }
                    timePicker = null
                }) { Text("确认") } }, dismissButton = { FilledTonalButton(onClick = { timePicker = null }) { Text("取消") } })
        }
        weekPicker?.let { index ->
            var selected by remember(index) { mutableStateOf(slots[index].weeks) }
            AlertDialog(onDismissRequest = { weekPicker = null }, title = { Text(if (original == null) "选择上课周次" else "选择周次") }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    (1..semester.weekCount).chunked(4).forEach { weeks ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { weeks.forEach { week ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                WeekNumberButton(week, week in selected) {
                                    selected = if (original != null) setOf(week) else if (week in selected) selected - week else selected + week
                                }
                            }
                        }; repeat(4 - weeks.size) { Spacer(Modifier.weight(1f)) } }
                    }
                }
            }, confirmButton = { FilledTonalButton(onClick = { slots = slots.toMutableList().also { it[index] = it[index].copy(weeks = selected) }; weekPicker = null }, enabled = selected.isNotEmpty()) { Text("确认") } },
                dismissButton = { FilledTonalButton(onClick = { weekPicker = null }) { Text("取消") } })
        }
        confirmation?.let {
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("删除这次上课？") },
                text = { Text("只删除这一次，其他周的课程保留。") },
                confirmButton = { FilledTonalButton(onClick = { confirmation = null; submit(delete = true) }) { Text("确认") } },
                dismissButton = { FilledTonalButton(onClick = { confirmation = null }) { Text("取消") } })
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("放弃") } }, dismissButton = { FilledTonalButton(onClick = { discard = false }) { Text("继续编辑") } })
        error?.let { reason -> AlertDialog(onDismissRequest = { error = null },
            containerColor = MaterialTheme.colorScheme.background,
            title = { Text("暂时无法保存") }, text = { Text(reason) },
            confirmButton = { FilledTonalButton(onClick = { error = null }) { Text("确认") } }) }
    }
}
