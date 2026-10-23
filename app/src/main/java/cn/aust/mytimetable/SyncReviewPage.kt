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
import cn.aust.mytimetable.data.SyncReview
import cn.aust.mytimetable.school.SchoolCourse

@Composable
internal fun SyncReviewPage(review: SyncReview, busy: Boolean, cancel: () -> Unit, confirm: (Set<String>) -> Unit) {
    var useSchool by remember(review) { mutableStateOf(emptySet<String>()) }
    BackHandler { if (!busy) cancel() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                HeaderButton("取消", enabled = !busy, onClick = cancel)
                Text("核对调课", style = MaterialTheme.typography.titleMedium)
                HeaderButton(if (busy) "稍候" else "确认", enabled = !busy) { confirm(useSchool) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按组选择安排，确认后同步。", style = MaterialTheme.typography.bodyMedium)
                review.conflicts.forEach { conflict ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(conflict.local.first().course.name, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(conflict.id !in useSchool, onClick = { useSchool = useSchool - conflict.id }, enabled = !busy)
                                Text("保留我的安排")
                            }
                            conflict.local.forEach { local ->
                                CourseComparison(local.course, if (local.deleted) "已删除，不再上课" else null)
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(conflict.id in useSchool, onClick = { useSchool = useSchool + conflict.id }, enabled = !busy)
                                Text("采用学校新安排")
                            }
                            if (conflict.school.isEmpty()) Text("无对应安排，选择后移除上方本地安排。",
                                style = MaterialTheme.typography.bodySmall)
                            conflict.school.forEach { CourseComparison(it) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CourseComparison(course: SchoolCourse, extra: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${course.date}  周${"一二三四五六日"[course.date.dayOfWeek.value - 1]}  ${course.start}—${course.end}")
        Text(course.rooms.joinToString("、").ifBlank { "地点未填写" }, style = MaterialTheme.typography.bodySmall)
        Text(course.teachers.joinToString("、"), style = MaterialTheme.typography.bodySmall)
        if (course.notes.isNotBlank()) Text(course.notes, style = MaterialTheme.typography.bodySmall)
        extra?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
