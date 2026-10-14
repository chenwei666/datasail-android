package com.example.datasail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchPicker(title: String, values: List<String>, selected: String, dismiss: () -> Unit,
    enabled: (String) -> Boolean = { true }, select: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val matches = remember(values, search) { values.filter { it.contains(search, true) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                label = { Text("搜索名称") }, singleLine = true)
            Text("${matches.size} / ${values.size} 项", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("picker-list")) {
                items(matches, key = { it }) { name ->
                    ListItem(headlineContent = { Text(name) }, leadingContent = {
                        RadioButton(selected == name, onClick = null, enabled = enabled(name))
                    }, modifier = Modifier.clickable(enabled = enabled(name)) { select(name); dismiss() })
                    HorizontalDivider()
                }
            }
            if (matches.isEmpty()) Text("没有匹配项")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
