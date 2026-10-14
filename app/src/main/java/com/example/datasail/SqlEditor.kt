package com.example.datasail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.datasail.core.DatabaseKind

internal data class SqlSuggestion(val label: String, val insert: String, val start: Int, val end: Int)
internal object SqlCompletion {
    private val keywords = listOf("SELECT", "FROM", "WHERE", "ORDER BY", "GROUP BY", "HAVING", "LEFT JOIN", "INNER JOIN", "JOIN", "ON", "AS", "AND", "OR", "IS NULL", "IS NOT NULL", "DISTINCT", "COUNT", "SUM", "AVG", "MIN", "MAX", "ASC", "DESC", "LIMIT", "OFFSET", "TOP")
    private fun word(c: Char) = c.isLetterOrDigit() || c == '_'
    fun suggestions(text: String, cursor: Int, kind: DatabaseKind, tables: List<String>, columns: List<String>): List<SqlSuggestion> {
        if (cursor !in 0..text.length || text.length > 16000) return emptyList()
        // Never rewrite strings, quoted identifiers, or comments, including escaped quote pairs.
        var mode = 'n'; var depth = 0; var i = 0; var dollarQuote = ""
        while (i < cursor) {
            val c = text[i]; val next = text.getOrNull(i + 1)
            when (mode) {
                'n' -> when {
                    c == '-' && next == '-' -> { mode = 'l'; i++ }
                    c == '/' && next == '*' -> { mode = 'b'; depth = 1; i++ }
                    c == '$' -> {
                        val delimiter = Regex("^\\$[A-Za-z0-9_]*\\$").find(text.substring(i))?.value
                        if (delimiter != null) { dollarQuote = delimiter; mode = '$'; i += delimiter.length - 1 }
                    }
                    c == '\'' || c == '"' || c == '`' || c == '[' -> mode = c
                }
                '$' -> if (text.startsWith(dollarQuote, i)) { i += dollarQuote.length - 1; mode = 'n' }
                'l' -> if (c == '\n') mode = 'n'
                'b' -> when {
                    c == '/' && next == '*' -> { depth++; i++ }
                    c == '*' && next == '/' -> { depth--; if (depth == 0) mode = 'n'; i++ }
                }
                else -> {
                    val end = if (mode == '[') ']' else mode
                    if (c == '\\' && mode != '[' && next != null) i++
                    else if (c == end) { if (next == end) i++ else mode = 'n' }
                }
            }; i++
        }
        if (mode != 'n') return emptyList()
        var start = cursor; while (start > 0 && word(text[start - 1])) start--
        var end = cursor; while (end < text.length && word(text[end])) end++
        val prefix = text.substring(start, cursor)
        if (prefix.isNotEmpty() && !prefix.first().let { it.isLetter() || it == '_' }) return emptyList()
        fun quote(name: String) = when(kind) {
            DatabaseKind.SQL_SERVER -> "[" + name.replace("]", "]]") + "]"
            DatabaseKind.MYSQL, DatabaseKind.MARIADB -> "`" + name.replace("`", "``") + "`"
            else -> "\"" + name.replace("\"", "\"\"") + "\""
        }
        val qualified = start > 0 && text[start - 1] == '.'
        val before = text.substring(0, start).trimEnd()
        val last = Regex("[A-Za-z]+$").find(before)?.value?.uppercase()
        val needsTable = last in setOf("FROM", "JOIN")
        val needsColumn = last in setOf("WHERE", "AND", "OR", "ON", "SELECT", "BY", "HAVING") || qualified
        val exactClause = prefix.uppercase() in setOf("WHERE", "AND", "OR", "ON", "SELECT", "BY", "HAVING", "FROM", "JOIN") && cursor == end
        val clauseTable = prefix.uppercase() in setOf("FROM", "JOIN")
        val words = if (qualified) emptyList() else keywords.filter {
            (it != "TOP" || kind == DatabaseKind.SQL_SERVER) && (it != "LIMIT" || kind != DatabaseKind.SQL_SERVER)
        }.map { it to it }
        if (exactClause) return (if (clauseTable) tables else columns).take(8).map { SqlSuggestion(it, " " + quote(it), cursor, cursor) }
        val candidates = when {
            needsTable -> tables.take(200).map { it to quote(it) }
            needsColumn -> columns.map { it to quote(it) } + if (prefix.isNotEmpty()) words else emptyList()
            prefix.isEmpty() -> if (text.isBlank()) listOf("SELECT" to "SELECT ") else emptyList()
            else -> columns.map { it to quote(it) } + tables.take(200).map { it to quote(it) } + words
        }
        return candidates.filter { it.first.startsWith(prefix, true) }.distinctBy { it.second }.take(8)
            .map { SqlSuggestion(it.first, it.second, start, end) }

    }
    fun apply(text: String, suggestion: SqlSuggestion): Pair<String, Int> {
        require(suggestion.start in 0..suggestion.end && suggestion.end <= text.length)
        val value = text.replaceRange(suggestion.start, suggestion.end, suggestion.insert)
        return value to (suggestion.start + suggestion.insert.length)
    }
}

@Composable
internal fun SqlEditor(sql: String, change: (String) -> Unit, state: WorkspaceUiState, settings: AppSettings, ai: AiViewModel? = null, workspace: WorkspaceViewModel? = null) {
    var field by remember(state.target, state.catalog, state.schema) { mutableStateOf(TextFieldValue(sql, TextRange(sql.length))) }
    LaunchedEffect(sql) { if (field.text != sql) field = TextFieldValue(sql, TextRange(sql.length)) }
    val suggestions = remember(field, state.tables, (state.selectedSchema ?: state.result?.tableSchema), settings.completion) {
        if (!settings.completion || !field.selection.collapsed) emptyList() else SqlCompletion.suggestions(field.text, field.selection.start,
            state.kind, state.tables.map { it.name }, (state.selectedSchema ?: state.result?.tableSchema)?.columns?.map { it.name }.orEmpty())
    }
    fun complete(suggestion: SqlSuggestion) {
        val (text, cursor) = SqlCompletion.apply(field.text, suggestion)
        if (text.length <= 16000) { field = TextFieldValue(text, TextRange(cursor)); change(text) }
    }
    OutlinedTextField(field, { if (it.text.length <= 16000) { field = it; change(it.text) } },
        label = { Text("单条 SELECT 查询") }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = settings.editorFont.sp),
        modifier = Modifier.fillMaxWidth().testTag("sql-editor").onPreviewKeyEvent { event ->
            if (event.key == Key.Tab && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                // Consume Tab for a selected range: Android would otherwise replace it with a tab.
                if (!field.selection.collapsed) return@onPreviewKeyEvent true
                if (suggestions.isNotEmpty()) complete(suggestions.first()) else {
                    val text = field.text.replaceRange(field.selection.min, field.selection.max, "    ")
                    if (text.length <= 16000) { field = TextFieldValue(text, TextRange(field.selection.min + 4)); change(text) }
                }; true
            } else false
        }, placeholder = { Text("SELECT *\nFROM 表名\nWHERE 条件") }, minLines = 5, maxLines = 12)
    if (settings.completion) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = suggestions.isNotEmpty(), onClick = { complete(suggestions.first()) }) { Text("Tab 补全") }
            suggestions.forEach { item -> SuggestionChip(onClick = { complete(item) }, label = { Text(item.label) }) }
        }
        Text("Tab 接受首个候选 · WHERE / FROM 后提示字段或表名", style = MaterialTheme.typography.bodySmall)
    }
    if (ai != null && workspace != null) InlineAiSql(ai, workspace, field) { value -> field = value; change(value.text) }
}
