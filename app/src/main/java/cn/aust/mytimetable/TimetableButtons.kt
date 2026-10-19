package cn.aust.mytimetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HeaderButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val background = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF2B2F34) else Color(0xFFE6E8EB)
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.width(44.dp).height(32.dp),
        shape = RoundedCornerShape(9.dp), color = background,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = if (label == "＋") 20.sp else 11.sp, maxLines = 1) }
    }
}

@Composable
internal fun WeekNumberButton(week: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(selected = selected, onClick = onClick, modifier = Modifier.size(44.dp), shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Box(contentAlignment = Alignment.Center) { Text("$week", fontSize = 14.sp) }
    }
}
