package com.example.datasail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.datasail.core.DatabaseKind

internal val DatabaseKind.connectionCategory: String get() = when {
    this == DatabaseKind.SQLITE -> "本地文件"
    isKeyValue -> "键值"
    isDocument -> "文档"
    isSearch -> "搜索"
    else -> "关系型"
}

private val DatabaseKind.accent: Color get() = when (this) {
    DatabaseKind.SQL_SERVER -> Color(0xFFAA3445)
    DatabaseKind.MYSQL -> Color(0xFF006C8B)
    DatabaseKind.MARIADB -> Color(0xFF785A26)
    DatabaseKind.POSTGRESQL -> Color(0xFF385FA0)
    DatabaseKind.SQLITE -> Color(0xFF266F8B)
    DatabaseKind.REDIS -> Color(0xFFAC3E30)
    DatabaseKind.VALKEY -> Color(0xFF336C83)
    DatabaseKind.MONGODB -> Color(0xFF237344)
    DatabaseKind.ELASTICSEARCH -> Color(0xFF786018)
    DatabaseKind.OPENSEARCH -> Color(0xFF405FA3)
}

@Composable
internal fun EngineBadge(kind: DatabaseKind) {
    Surface(color = kind.accent, contentColor = Color.White, shape = RoundedCornerShape(14.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text(when (kind) {
                DatabaseKind.SQL_SERVER -> "MS"
                DatabaseKind.MYSQL -> "My"
                DatabaseKind.MARIADB -> "Ma"
                DatabaseKind.POSTGRESQL -> "Pg"
                DatabaseKind.SQLITE -> "Sq"
                DatabaseKind.REDIS -> "Re"
                DatabaseKind.VALKEY -> "Vk"
                DatabaseKind.MONGODB -> "Mo"
                DatabaseKind.ELASTICSEARCH -> "Es"
                DatabaseKind.OPENSEARCH -> "Os"
            }, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun EngineGrid(kinds: List<DatabaseKind>, select: (DatabaseKind) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale
        val columns = when { maxWidth / scale >= 720.dp -> 3; maxWidth / scale >= 340.dp -> 2; else -> 1 }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            kinds.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEach { kind ->
                        OutlinedCard(onClick = { select(kind) }, modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                EngineBadge(kind)
                                Text(kind.label, style = MaterialTheme.typography.titleMedium)
                                Text(kind.connectionCategory + if (kind == DatabaseKind.SQLITE) " · 只读副本" else " · TLS",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun ConnectionSection(number: String, title: String, detail: String) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(number, Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DatabaseTargetRow(name: String, selected: Boolean, enabled: Boolean, supported: Boolean, select: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationGlyph("连接")
            Text(name + if (supported) "" else "（暂不支持此名称）", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

/** Small original line icons; labels provide the accessible navigation names. */
@Composable
internal fun NavigationGlyph(title: String) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink, Offset(x1*u,y1*u), Offset(x2*u,y2*u), 1.8f*u)
        when (title) {
            "连接" -> {
                drawOval(ink, Offset(4*u,3*u), Size(16*u,6*u), style = stroke)
                line(4f,6f,4f,18f); line(20f,6f,20f,18f)
                drawArc(ink, 0f,180f,false,Offset(4*u,9*u),Size(16*u,6*u),style=stroke)
                drawArc(ink, 0f,180f,false,Offset(4*u,15*u),Size(16*u,6*u),style=stroke)
            }
            "工作台" -> {
                drawRect(ink,Offset(2*u,4*u),Size(20*u,16*u),style=stroke)
                line(6f,9f,9f,12f); line(9f,12f,6f,15f); line(12f,16f,18f,16f)
            }
            "收藏" -> {
                val path = Path().apply { moveTo(6*u,3*u); lineTo(18*u,3*u); lineTo(18*u,21*u); lineTo(12*u,17*u); lineTo(6*u,21*u); close() }
                drawPath(path,ink,style=stroke)
            }
            "设置" -> {
                line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f)
                drawCircle(ink,2.5f*u,Offset(8*u,6*u)); drawCircle(ink,2.5f*u,Offset(16*u,12*u)); drawCircle(ink,2.5f*u,Offset(10*u,18*u))
            }
            else -> {
                val path = Path().apply { moveTo(12*u,2*u); lineTo(15*u,9*u); lineTo(22*u,12*u); lineTo(15*u,15*u); lineTo(12*u,22*u); lineTo(9*u,15*u); lineTo(2*u,12*u); lineTo(9*u,9*u); close() }
                drawPath(path,ink,style=stroke)
            }
        }
    }
}
