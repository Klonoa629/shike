package cn.aust.mytimetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.aust.mytimetable.school.SchoolCourse
import cn.aust.mytimetable.school.SchoolViewModel
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId

@Composable
fun SchoolAccountScreen(model: SchoolViewModel, openPortal: () -> Unit) {
    val state = model.state
    var account by remember { mutableStateOf("") }
    // Password is deliberately not rememberSaveable or part of a saved-state bundle.
    var password by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<SchoolCourse?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.signedIn) { if (state.signedIn) password = "" }

    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("教务与同步", style = MaterialTheme.typography.headlineLarge)
                    Text("本地课表 · 加密登录", color = MaterialTheme.colorScheme.primary)
                    Text("完整同步后，所有周次保存在本机，关闭重开也能查看。只有点击同步时才访问学校。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!state.signedIn && !state.vaultProblem) {
                item {
                    OutlinedTextField(account, { account = it }, label = { Text("学号") },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                }
                item {
                    OutlinedTextField(password, { password = it }, label = { Text("教务密码") },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                item {
                    Text("登录成功后，在这台手机加密保存登录信息。关闭应用不会退出登录。",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { keyboard?.hide(); model.login(account, password) },
                        enabled = !state.busy && !state.storageProblem && account.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) { Text("登录并同步整学期") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.status)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (state.signedIn) {
                item {
                    Button(onClick = { model.readCurrent() }, enabled = !state.busy && !state.storageProblem,
                        modifier = Modifier.fillMaxWidth()) { Text("同步整学期") }
                }
                if (state.businesses.size > 1) {
                    item { Text("教学业务类型", style = MaterialTheme.typography.titleMedium) }
                    items(state.businesses) { business ->
                        OutlinedButton(onClick = { model.readCurrent(business) }, enabled = !state.busy && !state.storageProblem,
                            modifier = Modifier.fillMaxWidth()) { Text(business.name) }
                    }
                }
            }
            state.stored?.let { stored ->
                item {
                    val time = Instant.ofEpochMilli(stored.successfulAt).atZone(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    Text("本地已保存 ${stored.download.semester.weekCount} 周 · ${stored.download.courses.size} 次课")
                    Text("上次成功同步：$time", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.preview?.let { preview ->
                item {
                    val range = preview.semester.range(preview.week)
                    val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                    val current = preview.semester.weekAt(today)
                    Text(preview.semester.label, style = MaterialTheme.typography.titleMedium)
                    Text("${preview.business.name} · 第 ${preview.week} / ${preview.semester.weekCount} 周")
                    Text("${range.start} 至 ${range.end}")
                    Text(current?.let { "当前教学周：第 $it 周" }
                        ?: if (today < preview.semester.start) "当前学期尚未开始" else "当前学期已结束")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { model.readWeek(preview.week - 1) },
                            enabled = preview.week > 1) { Text("上一周") }
                        TextButton(onClick = { current?.let(model::readWeek) },
                            enabled = current != null) { Text("本周") }
                        TextButton(onClick = { model.readWeek(preview.week + 1) },
                            enabled = preview.week < preview.semester.weekCount) { Text("下一周") }
                    }
                    if (preview.courses.isEmpty()) Text("已保存的课表：本周无课。")
                }
                items(preview.courses) { course ->
                    Card(onClick = { detail = course }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(course.name, style = MaterialTheme.typography.titleMedium)
                            Text("${course.date}  ${course.start}—${course.end}")
                            Text(course.rooms.joinToString("、").ifBlank { "地点待定" })
                        }
                    }
                }
            }
            if (state.signedIn) {
                item {
                    OutlinedButton(onClick = model::relogin, enabled = !state.busy && !state.storageProblem, modifier = Modifier.fillMaxWidth()) {
                        Text("使用已保存账号重新登录")
                    }
                    Text("若学校提示会话失效，可点此重登，无需重新输入。本版尚未自动判定会话过期。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.signedIn || state.vaultProblem || state.busy) {
                item { TextButton(onClick = model::logout) { Text("退出原生登录") } }
            }
            item {
                OutlinedButton(onClick = openPortal, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("打开学校网页核对")
                }
                Text("网页与原生登录独立。手动排课和每周自动同步将在后续加入。",
                    Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    detail?.let { course ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(course.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日期：${course.date}")
                Text("时间：${course.start}—${course.end}")
                Text("教师：${course.teachers.joinToString("、")}")
                Text("地点：${course.rooms.joinToString("、").ifBlank { "地点待定" }}")
                Text("来源：学校教务")
                Text("备注：暂无；本版暂不支持编辑。")
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
}
