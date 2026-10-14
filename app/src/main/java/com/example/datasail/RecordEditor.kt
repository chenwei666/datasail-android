package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.datasail.core.*

@Composable
internal fun RecordEditor(draft: RecordDraft, kind: DatabaseKind, dismiss: () -> Unit, preview: (RecordDraft) -> Unit) {
    var id by remember(draft) { mutableStateOf(draft.identity) }
    var value by remember(draft) { mutableStateOf(draft.replacement) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${draft.kind.label} · ${draft.namespace}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先预览，再确认提交。原对象被修改后会拒绝覆盖。")
            if (!kind.isDocument) OutlinedTextField(id, { if (it.toByteArray().size <= 1024) id = it }, label = { Text(if (kind.isKeyValue) "键名" else "文档 ID") }, enabled = draft.kind == MutationKind.INSERT, modifier = Modifier.fillMaxWidth())
            Text(if (kind.isKeyValue) "UTF-8 字符串；修改保留当前过期时间。" else if (kind.isDocument) "完整 Extended JSON；保留 BSON 类型，修改时不可改变 _id。" else "完整 _source JSON；修改时校验服务器版本。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, { if (it.length <= 8192) value = it }, label = { Text(if (kind.isKeyValue) "字符串值" else "完整文档") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("record-editor-value"))
        }
    }, confirmButton = { TextButton(enabled = kind.isDocument || id.isNotEmpty(), onClick = { preview(draft.copy(identity = id, replacement = value)) }) { Text("预览变更") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
