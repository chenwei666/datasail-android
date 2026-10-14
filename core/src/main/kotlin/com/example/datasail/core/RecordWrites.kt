package com.example.datasail.core

/** Fixed single-object mutations. Each session retains the exact preview and consumes its token once. */
data class RecordDraft(val kind: MutationKind, val namespace: String, val identity: String = "", val original: String? = null, val replacement: String = "")
interface RecordWrites {
    fun previewRecord(draft: RecordDraft): MutationPreview = throw WorkspaceRejected("此对象暂不支持编辑")
    fun confirmRecord(token: String): MutationResult = throw WorkspaceRejected("确认已失效")
    fun discardRecord() {}
}
internal fun recordPreview(draft: RecordDraft, target: String, command: String) = MutationPreview(
    java.util.UUID.randomUUID().toString(), draft.kind, target, command,
    listOf(ChangePreview(draft.identity.ifEmpty { "文档" }, draft.original, draft.replacement.takeUnless { draft.kind == MutationKind.DELETE })),
    "单个对象；原值或版本改变则拒绝提交", System.currentTimeMillis() + 120000)
internal fun requireRecord(draft: RecordDraft) {
    if (draft.kind == MutationKind.DROP_TABLE || (draft.original?.length ?: 0) > 8192 || draft.replacement.length > 8192 || draft.identity.toByteArray().size > 1024)
        throw WorkspaceRejected("只支持有界的单个对象编辑")
    if (draft.kind != MutationKind.INSERT && draft.original == null) throw WorkspaceRejected("请先完整读取对象")
}
internal fun requireToken(preview: MutationPreview, token: String) {
    if (preview.token != token || System.currentTimeMillis() > preview.expiresAtMillis) throw WorkspaceRejected("确认已失效，请重新读取并预览")
}
