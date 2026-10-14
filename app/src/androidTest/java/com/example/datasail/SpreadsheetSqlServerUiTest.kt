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
    private var originalFavorites = emptyList<TableFavorite>()
    private lateinit var connection: SavedConnection
    private lateinit var config: Properties
    private fun raw() = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(connection.profile(context), it) }
    private fun value(): String? = raw().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT code FROM dbo.ui_grid_v081 WHERE id=1").use { r -> assertTrue(r.next()); r.getString(1) } } }
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            originalFavorites = TableFavoriteStore(context).load(); TableFavoriteStore(context).save(emptyList())
            config = Properties().apply { File(context.filesDir, "fixture.properties").inputStream().use(::load) }
            val ca = store.importCa(Uri.fromFile(File(context.filesDir, "fixture-ca.pem")))
            connection = SavedConnection(name = "表格实连夹具", production = true, host = "localhost", port = config.getProperty("port"), database = "datasail_v020", username = "sa", ca = ca)
            raw().use { c -> c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS dbo.ui_grid_v081")
                s.execute("CREATE TABLE dbo.ui_grid_v081(id int PRIMARY KEY, code nvarchar(64) NULL, amount decimal(24,8) NULL, version rowversion)")
                s.execute("INSERT dbo.ui_grid_v081(id,code,amount) VALUES(1,N'0007',1234567890123456.12345678)")
                com.example.datasail.fixture.SqlServerTemporalContract.createTable(c)
            } }
            store.save(listOf(connection))
        }
        override fun after() {
            TableFavoriteStore(context).save(originalFavorites)
            ConnectionStore(context).save(original); PasswordVault(context).delete(connection.id)
            raw().use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS dbo.ui_grid_v081; DROP TABLE IF EXISTS dbo.ui_temporal_v091") } }; config.clear()
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
    private fun finishCellInput() {
        compose.runOnIdle {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(5000) {
            compose.runOnIdle { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
        }
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
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(45000) { !model.state.value.busy }
        assertTrue(model.state.value.message, model.state.value.connected)
        compose.onNodeWithText("关闭", substring = false).performClick()
        compose.onNodeWithText("SQL 编辑器").performClick()
        compose.onNodeWithText("单条 SELECT 查询").performScrollTo().performTextReplacement("SEL")
        compose.onNodeWithText("单条 SELECT 查询").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("单条 SELECT 查询").assertTextContains("SELECT")
        compose.onNodeWithText("数据").assertIsDisplayed().performClick()
        // Regression: no ViewModel calls to load metadata or open the first table.
        assertEquals("dbo", model.state.value.schema)
        compose.onNodeWithTag("catalog-picker").performClick()
        compose.onNodeWithText("master", substring = false).performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.catalog == "master" }
        assertNull(model.state.value.result)
        compose.onNodeWithTag("catalog-picker").performClick()
        compose.onNodeWithText("datasail_v020", substring = false).performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.tables.any { it.name == "ui_grid_v081" } }
        compose.onNodeWithTag("schema-picker").performClick()
        compose.onNodeWithText("sys", substring = false).performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.schema == "sys" }
        assertTrue(model.state.value.tables.isEmpty())
        compose.onNodeWithTag("schema-picker").performClick()
        compose.onNodeWithText("dbo", substring = false).performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.tables.any { it.name == "ui_grid_v081" } }
        val table = model.state.value.tables.single { it.name == "ui_grid_v081" }
        // User regression: listing succeeds, then viewing a table with a date used to disconnect.
        compose.onNodeWithText("浏览表 / 视图（", substring = true).performClick()
        compose.onNodeWithText("筛选表 / 视图").performTextReplacement("ui_temporal_v091")
        compose.onNodeWithTag("object-list").assertIsDisplayed()
        compose.onNodeWithTag("object-ui_temporal_v091").performClick()
        compose.waitUntil(20000) { !model.state.value.busy }
        assertTrue(model.state.value.message, model.state.value.connected)
        com.example.datasail.fixture.SqlServerTemporalContract.verifyPage(requireNotNull(model.state.value.result))
        compose.onNodeWithTag("result-grid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cell-0-1").performScrollTo().assertTextContains("2024-02-29")
        compose.onNodeWithText("浏览表 / 视图（", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("筛选表 / 视图").assertIsDisplayed().performTextReplacement("ui_grid_v081")
        compose.onNodeWithTag("object-list").assertIsDisplayed()
        compose.onNodeWithTag("object-ui_grid_v081").performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.result?.tableSchema?.table?.name == "ui_grid_v081" }
        compose.onNodeWithText("更多 ▾").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("收藏此表").performClick()
        compose.waitUntil(10000) { TableFavoriteStore(context).load().size == 1 }
        compose.onNodeWithTag("catalog-picker").performClick()
        compose.onNodeWithText("master", substring = false).performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.catalog == "master" }
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("打开收藏表").performScrollTo().performClick()
        compose.waitUntil(20000) { !model.state.value.busy && model.state.value.result?.tableSchema?.table?.name == "ui_grid_v081" }
        assertEquals("datasail_v020", model.state.value.catalog)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("object-list").fetchSemanticsNodes().isEmpty() }
        assertFalse(model.state.value.writable)
        compose.onNodeWithTag("allow-session-write").performScrollTo().performClick()
        compose.onNodeWithText("开启生产库编辑？").assertIsDisplayed()
        compose.onNodeWithText("确认开启编辑").performClick()
        compose.waitUntil(10000) { model.state.value.writable && !model.state.value.busy }
        selectCell()
        // Only synthetic fixture data is visible; production retains FLAG_SECURE.
        compose.runOnIdle { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.filesDir, "v081-grid.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("编辑单元格").performScrollTo().performClick()
        compose.onNodeWithTag("result-grid").performScrollTo()
        compose.onNodeWithTag("cell-editor").performScrollTo().assertIsDisplayed().performTextReplacement("0009")
        finishCellInput()
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
        compose.onNodeWithTag("cell-null").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("cell-null").assertIsOn()
        compose.onNodeWithText("待提交值：NULL").assertExists()
        // NULL removes the focused cell editor; wait for the keyboard resize before clicking the moving button.
        finishCellInput()
        compose.onNodeWithText("预览单元格修改").performScrollTo().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText("确认提交").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            android.util.Log.e("DataSailEditPreview", model.state.value.message)
            compose.onRoot().printToLog("DataSailEditPreview")
            throw error
        }
        assertNotNull(model.state.value.preview)
        compose.onNodeWithText("确认提交").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.preview == null }
        assertNull(value())
        assertEquals(DbValue.Null, model.state.value.result!!.rows[0][1])
        compose.runOnIdle { model.query("SELECT id, code FROM dbo.ui_grid_v081") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result != null }
        selectCell()
        compose.onNodeWithText("编辑单元格").performScrollTo().assertIsNotEnabled()
    }
}
