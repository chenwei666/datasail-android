package com.example.datasail

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.util.Properties

/** Real isolated SQL Server only; exercises visible cell editing and parameterized commit. */
@OptIn(ExperimentalTestApi::class)
class SpreadsheetSqlServerUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private lateinit var connection: SavedConnection
    private lateinit var config: Properties
    private fun raw() = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(connection.profile(context), it) }
    private fun value(): String? = raw().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT code FROM dbo.ui_grid_v080 WHERE id=1").use { r -> assertTrue(r.next()); r.getString(1) } } }
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            config = Properties().apply { File(context.filesDir, "fixture.properties").inputStream().use(::load) }
            val ca = store.importCa(Uri.fromFile(File(context.filesDir, "fixture-ca.pem")))
            connection = SavedConnection(name = "表格实连夹具", host = "localhost", port = config.getProperty("port"), database = "datasail_v020", username = "sa", ca = ca)
            raw().use { c -> c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS dbo.ui_grid_v080")
                s.execute("CREATE TABLE dbo.ui_grid_v080(id int PRIMARY KEY, code nvarchar(64) NULL, amount decimal(24,8) NULL, version rowversion)")
                s.execute("INSERT dbo.ui_grid_v080(id,code,amount) VALUES(1,N'0007',1234567890123456.12345678)")
            } }
            store.save(listOf(connection))
        }
        override fun after() {
            ConnectionStore(context).save(original); PasswordVault(context).delete(connection.id)
            raw().use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS dbo.ui_grid_v080") } }; config.clear()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun selectCell() {
        compose.onNodeWithTag("result-grid").performScrollTo()
        compose.onNodeWithTag("cell-0-1").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        // combinedClickable waits for a possible double tap on the test clock.
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(5000) { compose.onAllNodesWithText("编辑单元格").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun tabCompletionCellCommitNullAndReadonlyQuery() {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithText("密码").performScrollTo().performTextInput(config.getProperty("password"))
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithTag("connection-authorization").performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().performClick()
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.waitUntil(45000) { !model.state.value.busy }
        assertTrue(model.state.value.message, model.state.value.connected)
        compose.waitUntil(10000) { PasswordVault(context).load(connection) != null }
        compose.runOnIdle { model.disconnect() }
        compose.onNodeWithText("连接", substring = false).performClick()
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onNodeWithText("密码").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isNotEmpty() }
        compose.onNodeWithTag("connection-authorization").performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().performClick()
        compose.waitUntil(45000) { !model.state.value.busy }
        assertTrue(model.state.value.message, model.state.value.connected)
        compose.onNodeWithText("SQL 编辑器").performScrollTo().performClick()
        compose.onNodeWithText("单条 SELECT 查询").performScrollTo().performTextReplacement("SEL")
        compose.onNodeWithText("单条 SELECT 查询").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("单条 SELECT 查询").assertTextContains("SELECT")
        compose.onNodeWithText("数据浏览").performScrollTo().performClick()
        compose.runOnIdle { model.tables("dbo") }
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.tables.any { it.name == "ui_grid_v080" } }
        val table = model.state.value.tables.single { it.name == "ui_grid_v080" }
        compose.runOnIdle { model.page(table) }
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.result != null }
        compose.onNodeWithTag("allow-session-write").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.writable && !model.state.value.busy }
        selectCell()
        // Only synthetic fixture data is visible; production retains FLAG_SECURE.
        compose.runOnIdle { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.filesDir, "v080-grid.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("编辑单元格").performScrollTo().performClick()
        compose.onNodeWithTag("result-grid").performScrollTo()
        compose.onNodeWithTag("cell-editor").performScrollTo().assertIsDisplayed().performTextReplacement("0009")
        compose.onNodeWithText("预览单元格修改").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.preview != null }
        assertEquals("0007", value())
        compose.onNodeWithText("确认提交").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.preview == null }
        assertEquals("0009", value())
        assertEquals(DbValue.Text("0009"), model.state.value.result!!.rows[0][1])
        compose.runOnIdle { model.page(table) }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result != null }
        selectCell()
        compose.onNodeWithText("编辑单元格").performScrollTo().performClick()
        compose.onNodeWithTag("cell-null").performScrollTo().performClick()
        compose.onNodeWithText("预览单元格修改").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.preview != null }
        compose.onNodeWithText("确认提交").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.preview == null }
        assertNull(value())
        assertEquals(DbValue.Null, model.state.value.result!!.rows[0][1])
        compose.runOnIdle { model.query("SELECT id, code FROM dbo.ui_grid_v080") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result != null }
        selectCell()
        compose.onNodeWithText("编辑单元格").performScrollTo().assertIsNotEnabled()
    }
}
