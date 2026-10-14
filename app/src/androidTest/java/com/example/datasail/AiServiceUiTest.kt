package com.example.datasail

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.graphics.asAndroidBitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.*
import org.junit.rules.*
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.*

/** Real platform HTTPS against a local synthetic upstream; trust injection is test-only. */
class AiServiceUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var previous: AiPreferences
    private var connections = emptyList<SavedConnection>()
    private lateinit var profile: AiProfile
    private lateinit var connection: SavedConnection
    private lateinit var ssl: SSLContext
    private var sqlite: File? = null
    private fun transport(service: AiService, key: CharArray): AiClient = AiClient::class.java
        .getDeclaredConstructor(AiService::class.java, CharArray::class.java, SSLContext::class.java).newInstance(service, key, ssl)
    private val fixture = object : ExternalResource() {
        override fun before() {
            val ca = File(context.filesDir, "ai-fixture-ca.crt"); check(ca.exists()) { "Run scripts/test-ai-android.sh" }
            val cert = ca.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("synthetic", cert) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            ssl = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
            previous = AiStore(context).load(); connections = ConnectionStore(context).load()
            profile = AiProfile(name = "合成上游", endpoint = "https://localhost:18743/openai")
            AiStore(context).save(AiPreferences(true, profile.id, listOf(profile)))
            PasswordVault(context, "ai-keys-v1").saveBound(profile.id, profile.binding, "synthetic-ai-only".toCharArray())
            val source = File(context.cacheDir, "ai-ui.db"); source.delete()
            try {
                SQLiteDatabase.openOrCreateDatabase(source, null).use { db -> db.execSQL("CREATE TABLE ai_fixture(id INTEGER PRIMARY KEY, name TEXT)"); db.execSQL("INSERT INTO ai_fixture VALUES(1,'ROW_MUST_NOT_LEAK')") }
                val store = ConnectionStore(context); val id = store.importSqlite(Uri.fromFile(source)); sqlite = store.sqliteFile(id)
                connection = SavedConnection(name = "AI 合成数据库", kind = DatabaseKind.SQLITE, database = id, port = "0", allowExternalAi = true)
                store.save(listOf(connection))
            } finally { source.delete() }
        }
        override fun after() { AiStore(context).save(previous); PasswordVault(context, "ai-keys-v1").delete(profile.id); ConnectionStore(context).save(connections); sqlite?.delete() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    @Test fun directChatKeepsHistoryAndClearsOnProfileChange() {
        lateinit var ai: AiViewModel; lateinit var workspace: WorkspaceViewModel
        compose.runOnIdle {
            ai = ViewModelProvider(compose.activity)[AiViewModel::class.java]
            workspace = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]
            ai.createClient = ::transport
        }
        compose.waitUntil(10000) { ai.state.value.ready }
        compose.runOnIdle { ai.save(profile.copy(model = "fixture-model"), charArrayOf()) }
        compose.waitUntil(10000) { !ai.state.value.busy }
        compose.onNodeWithText("AI 助手").performClick()
        compose.onNodeWithTag("ai-chat-input").performScrollTo().performTextInput("你好，我叫小舟")
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.onNodeWithTag("ai-preview").assertDoesNotExist()
        compose.waitUntil(20000) { !ai.state.value.busy && ai.state.value.chat.size == 1 }
        assertEquals("你好，小舟", ai.state.value.chat.single().answer)
        compose.onNodeWithTag("ai-chat-input").performScrollTo().performTextInput("我叫什么？")
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.waitUntil(20000) { !ai.state.value.busy && ai.state.value.chat.size == 2 }
        assertEquals("你叫小舟，已收到上一轮对话", ai.state.value.chat.last().answer)
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.result)
        compose.runOnIdle { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("chat-answer-1").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image -> File(context.filesDir, "ai-chat-v0121.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        compose.runOnIdle { ai.save(profile.copy(model = "fixture-second"), charArrayOf()) }
        compose.waitUntil(10000) { !ai.state.value.busy }
        assertTrue(ai.state.value.chat.isEmpty())
        compose.onNodeWithTag("ai-chat-input").performScrollTo().performTextInput("你好，我叫小舟")
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.waitUntil(20000) { !ai.state.value.busy && ai.state.value.chat.size == 1 }
        compose.onNodeWithText("清空对话").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(ai.state.value.chat.isEmpty()) }
    }
    @Test fun modelDiscoveryPreviewAndDraftNeverRunSqlOrSendRows() {
        lateinit var ai: AiViewModel; lateinit var workspace: WorkspaceViewModel
        compose.runOnIdle { ai = ViewModelProvider(compose.activity)[AiViewModel::class.java]; workspace = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]; ai.createClient = ::transport }
        compose.waitUntil(10000) { ai.state.value.ready }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(15000) { !workspace.state.value.busy && workspace.state.value.tables.isNotEmpty() }
        compose.onNodeWithTag("object-list").assertIsDisplayed(); compose.onNodeWithTag("object-ai_fixture").performClick()
        compose.waitUntil(10000) { workspace.state.value.result != null }
        val originalSql = workspace.draftSql.value
        compose.onNodeWithText("AI 助手").performClick()
        compose.onNodeWithTag("ai-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("ai-key").assertDoesNotExist()
        compose.onNodeWithText("前往 AI 设置").performScrollTo().performClick()
        assertTrue("AI must be enabled", ai.state.value.preferences.enabled)
        assertEquals(profile, ai.state.value.preferences.active)
        compose.onNodeWithText("获取上游模型").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { image -> File(context.filesDir, "ai-before-models.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        compose.onNodeWithText("获取上游模型").performClick()
        compose.waitUntil(15000) { !ai.state.value.busy }
        assertEquals(ai.state.value.message, 2, ai.state.value.models.size)
        compose.onNodeWithText("选择模型（2）").performScrollTo().performClick()
        compose.onNodeWithText("fixture-model").performClick()
        compose.onNodeWithText("保存服务").performScrollTo().performClick()
        compose.waitUntil(10000) { !ai.state.value.busy && ai.state.value.preferences.active?.model == "fixture-model" }
        compose.onNodeWithText("打开 AI 助手").performScrollTo().performClick()
        compose.onNodeWithTag("ai-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("ai-model").assertDoesNotExist()
        compose.onNodeWithText("问答 / SQL 草稿").performScrollTo().performClick()
        compose.onNodeWithTag("ai-prompt").performScrollTo().performTextInput("请生成只读查询")
        compose.onNodeWithTag("ai-include-structure").performScrollTo().performClick()
        compose.onNodeWithText("预览发送内容").performScrollTo().performClick()
        compose.onNodeWithTag("ai-preview").assertTextContains("请生成只读查询", substring = true).assertTextContains("ai_fixture", substring = true)
        assertFalse(compose.onNodeWithTag("ai-preview").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains("ROW_MUST_NOT_LEAK"))
        compose.onNodeWithTag("ai-preview").performTextReplacement("已脱敏问题：生成只读查询")
        assertEquals(originalSql, workspace.draftSql.value)
        compose.onNodeWithText("确认发送").performClick()
        compose.waitUntil(20000) { !ai.state.value.busy && ai.state.value.answer.isNotBlank() }
        assertEquals(originalSql, workspace.draftSql.value)
        assertEquals(DbValue.Text("ROW_MUST_NOT_LEAK"), workspace.state.value.result!!.rows.single()[1])
        compose.onNodeWithText("载入 SQL 草稿").performScrollTo().performClick()
        compose.onNodeWithText("确认载入草稿").performClick()
        compose.onNodeWithText("单条 SELECT 查询").performScrollTo().assertTextContains("SELECT id, name FROM ai_fixture")
        assertEquals(DbValue.Text("ROW_MUST_NOT_LEAK"), workspace.state.value.result!!.rows.single()[1])
        compose.runOnIdle {
            val old = AiApproval(ai.state.value.preferences.active!!, aiScope(workspace), "old approval")
            workspace.editSql("SELECT 2"); ai.askApproved(workspace, old)
            assertFalse(ai.state.value.busy)
            workspace.save(connection.copy(allowExternalAi = false))
        }
        compose.waitUntil(10000) { workspace.saved.value.single().allowExternalAi == false }
        compose.onNodeWithText("AI 助手").performClick()
        compose.onNodeWithText("问答 / SQL 草稿").performScrollTo().performClick()
        compose.onNodeWithText("此连接禁止外部 AI。可在连接配置中显式允许，重新连接后生效。").assertExists()
        compose.onNodeWithText("预览发送内容").performScrollTo().assertIsNotEnabled()
    }
    @Test fun realtimeSuggestionsRequireConsentAndNeverExecuteOrReuseAfterLeaving() {
        lateinit var ai: AiViewModel; lateinit var workspace: WorkspaceViewModel
        var requests = 0
        compose.runOnIdle {
            ai = ViewModelProvider(compose.activity)[AiViewModel::class.java]
            workspace = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]
            ai.createClient = { service, key -> requests++; transport(service, key) }
        }
        compose.waitUntil(10000) { ai.state.value.ready }
        compose.runOnIdle { ai.save(profile.copy(model = "fixture-model"), charArrayOf()) }
        compose.waitUntil(10000) { !ai.state.value.busy }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("object-ai_fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-ai_fixture").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("result-grid").fetchSemanticsNodes().isNotEmpty() }
        val original = workspace.state.value.result
        compose.onNodeWithText("SQL 编辑器").performClick()
        compose.onNodeWithTag("sql-editor").performScrollTo().performTextReplacement("SELECT id FROM ai_fixture WHERE")
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("AI 实时建议：关").performScrollTo().performClick()
        compose.onNodeWithText("开启本次编辑的实时 AI 建议？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("确认范围并开启").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("接受 AI 建议").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests > 0)
        assertEquals("SELECT id FROM ai_fixture WHERE", workspace.draftSql.value)
        assertEquals(original, workspace.state.value.result)
        compose.onNodeWithText("接受 AI 建议").performScrollTo().performClick()
        compose.onNodeWithTag("sql-editor").assertTextContains("SELECT id, name FROM ai_fixture")
        assertEquals(original, workspace.state.value.result)
        compose.onNodeWithText("数据", substring = false).performClick()
        compose.onNodeWithText("SQL 编辑器").performClick()
        compose.onNodeWithText("AI 实时建议：关").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("接受 AI 建议").assertDoesNotExist()
    }
    @Test fun naturalLanguageQueryRequiresApprovedContextAndShowsBoundDatabaseResults() {
        lateinit var ai: AiViewModel; lateinit var workspace: WorkspaceViewModel
        compose.runOnIdle { ai = ViewModelProvider(compose.activity)[AiViewModel::class.java]; workspace = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]; ai.createClient = ::transport }
        compose.waitUntil(10000) { ai.state.value.ready }
        compose.runOnIdle { ai.save(profile.copy(model = "fixture-model"), charArrayOf()) }
        compose.waitUntil(10000) { !ai.state.value.busy }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("object-ai_fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-ai_fixture").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("result-grid").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, workspace.state.value.result!!.rows.size)
        compose.onNodeWithText("AI 助手").performClick()
        compose.onNodeWithText("操作当前表").performClick()
        compose.onNodeWithTag("ai-table-prompt").performScrollTo().performTextInput("查找 id 等于 2 的记录")
        compose.onNodeWithText("预览操作请求").performScrollTo().performClick()
        assertEquals(1, workspace.state.value.result!!.rows.size)
        compose.onNodeWithText("确认发送并查询 / 预览").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("AI 查询完成：0 行").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(workspace.state.value.result!!.rows.isEmpty())
        assertEquals(listOf(ColumnFilter("id", FilterOperator.EQ, "2")), workspace.state.value.result!!.view.filters)
        assertFalse(workspace.state.value.writable)
        compose.onNodeWithText("查看表格结果").performScrollTo().performClick()
        compose.onNodeWithText("清除条件").performScrollTo().performClick()
        compose.waitUntil(10000) { workspace.state.value.result?.rows?.size == 1 && !workspace.state.value.busy }
    }
    @Test fun androidTlsAndAllThreeProtocolContracts() {
        AiProtocol.entries.zip(listOf("openai", "anthropic", "gemini")).forEach { (protocol, path) ->
            transport(AiService(protocol, "https://localhost:18743/$path"), "synthetic-ai-only".toCharArray()).use { client ->
                assertTrue(client.models().names.isNotEmpty()); assertTrue(client.ask("fixture-model", "synthetic question").contains("SELECT id")); assertEquals(TableAction.READ, client.plan("fixture-model", "synthetic table action").action)
                assertEquals("你好，小舟", client.converse("fixture-model", emptyList(), "你好，我叫小舟"))
                assertEquals("你叫小舟，已收到上一轮对话", client.converse("fixture-model", listOf(AiChatTurn("你好，我叫小舟", "你好，小舟")), "我叫什么？"))
            }
        }
        transport(AiService(AiProtocol.OPENAI, "https://127.0.0.1:18743/openai"), "synthetic-ai-only".toCharArray()).use { client -> assertThrows(AiFailure::class.java) { client.models() } }
        AiClient(AiService(AiProtocol.OPENAI, "https://localhost:18743/openai"), "synthetic-ai-only".toCharArray()).use { client -> assertThrows(AiFailure::class.java) { client.models() } }
        transport(AiService(AiProtocol.OPENAI, "https://localhost:18743/redirect"), "synthetic-ai-only".toCharArray()).use { client -> assertTrue(assertThrows(AiFailure::class.java) { client.models() }.safeMessage.contains("重定向")) }
    }
}
