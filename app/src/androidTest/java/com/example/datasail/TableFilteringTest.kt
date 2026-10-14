package com.example.datasail

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File

class TableFilteringTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous = emptyList<SavedConnection>()
    private var imported: File? = null
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); previous = store.load()
            val source = File(context.cacheDir, "filter-ui.db"); source.delete()
            try {
                SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                    db.execSQL("CREATE TABLE filter_table(id INTEGER PRIMARY KEY, code TEXT)")
                    db.beginTransaction()
                    try {
                        for (id in 1..205) db.execSQL("INSERT INTO filter_table VALUES(?, ?)", arrayOf(id, "0007"))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                val id = store.importSqlite(Uri.fromFile(source)); imported = store.sqliteFile(id)
                store.save(listOf(SavedConnection(name = "筛选排序夹具", kind = DatabaseKind.SQLITE, database = id, port = "0")))
            } finally { source.delete() }
        }
        override fun after() { ConnectionStore(context).save(previous); imported?.delete() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun openFixture(): WorkspaceViewModel {
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("object-filter_table").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-filter_table").performClick()
        compose.waitUntil(10000) { model.state.value.result != null && !model.state.value.busy }
        return model
    }
    @Test fun queryErrorsKeepRowsAndSuccessfulQueriesKeepCompletionMetadata() {
        val model = openFixture()
        val original = model.state.value.result
        compose.runOnIdle { model.query("SELECT missing_column FROM missing_table") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.resultStale }
        assertTrue(model.state.value.connected); assertSame(original, model.state.value.result)
        compose.runOnIdle { model.preview(MutationKind.DELETE, 0, emptyMap()) }
        assertNull(model.state.value.preview)
        compose.onNodeWithText("刷新", substring = false).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && !model.state.value.resultStale }
        compose.runOnIdle { model.query("SELECT 42 AS answer") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.tableSchema == null }
        assertEquals("filter_table", model.state.value.selectedSchema!!.table.name)
        assertEquals("SELECT 42 AS answer", model.memory.history(model.state.value.connectionKey).first().sql)
    }
    @Test fun filterChipRestoresValueAndFullscreenCanPage() {
        val model = openFixture()
        compose.onNodeWithTag("column-id").performScrollTo().performClick()
        compose.onNodeWithTag("table-filter-value").performTextInput("205")
        compose.onNodeWithText("应用筛选").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.rows?.size == 1 }
        compose.onNodeWithText("id 等于 205").performScrollTo().performClick()
        compose.onNodeWithTag("table-filter-value").assertTextContains("205").performTextReplacement("204")
        compose.onNodeWithText("应用筛选").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.view?.filters?.singleOrNull()?.value == "204" }
        assertEquals(DbValue.Integer(204), model.state.value.result!!.rows.single()[0])
        compose.onNodeWithText("清除条件").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.rows?.size == 100 }
        compose.onNodeWithText("全屏表格").performScrollTo().performClick()
        compose.onNode(hasText("下一页") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.offset == 100 }
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.onNode(hasText("刷新") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000) { !model.state.value.busy }
        assertEquals(100, model.state.value.result!!.offset)
        compose.onNode(hasTestTag("cell-0-0") and hasAnyAncestor(isDialog())).performScrollTo().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithText("A101 · id").performScrollTo().assertIsDisplayed()
    }
    @Test fun recreationAndExplicitReconnectKeepDraftAndTableView() {
        val model = openFixture()
        val table = model.state.value.result!!.tableSchema!!.table
        compose.runOnIdle {
            model.editSql("SELECT 'keep draft' AS note")
            model.page(table, view = TableView(listOf(ColumnFilter("id", FilterOperator.GT, "200"))))
        }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.result?.rows?.size == 5 }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(model.state.value.connected)
        assertEquals("SELECT 'keep draft' AS note", model.draftSql.value)
        assertEquals(5, model.state.value.result!!.rows.size)
        compose.runOnIdle { model.disconnect() }
        compose.onNodeWithText("重新连接并恢复工作台").performClick()
        compose.waitUntil(15000) { model.state.value.connected && !model.state.value.busy && model.state.value.result?.rows?.size == 5 }
        assertEquals("SELECT 'keep draft' AS note", model.draftSql.value)
        assertFalse(model.state.value.writable)
        compose.runOnIdle { model.disconnect() }
        SQLiteDatabase.openDatabase(imported!!.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE filter_table")
            db.execSQL("CREATE TABLE filter_table(new_id INTEGER PRIMARY KEY, code TEXT)")
            db.execSQL("INSERT INTO filter_table VALUES(1, 'changed schema')")
        }
        compose.onNodeWithText("重新连接并恢复工作台").performClick()
        compose.waitUntil(15000) { model.state.value.connected && !model.state.value.busy }
        assertNull(model.state.value.result)
        compose.onNodeWithTag("object-filter_table").performClick()
        compose.waitUntil(10000) { model.state.value.result?.rows?.size == 1 && !model.state.value.busy }
        assertEquals(TableView(), model.state.value.result!!.view)
        assertEquals("new_id", model.state.value.result!!.columns.first().name)
    }
    @Test fun columnMenuFiltersWholeTableAndSortsBeforePaging() {
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("object-filter_table").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-filter_table").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("result-grid").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100, model.state.value.result!!.rows.size)
        compose.onNodeWithTag("column-id").performScrollTo().performClick()
        compose.onNodeWithText("降序 ↓").performClick()
        compose.waitUntil(10000) { model.state.value.result?.view?.sorts == listOf(ColumnSort("id", true)) && !model.state.value.busy }
        assertEquals(DbValue.Integer(205), model.state.value.result!!.rows.first()[0])
        compose.onNodeWithText("下一页", substring = false).performClick()
        compose.waitUntil(10000) { model.state.value.result?.offset == 100 && !model.state.value.busy }
        assertEquals(DbValue.Integer(105), model.state.value.result!!.rows.first()[0])
        compose.onNodeWithTag("column-id").performScrollTo().performClick()
        compose.onNodeWithTag("table-filter-value").performTextInput("205")
        compose.onNodeWithText("应用筛选").performClick()
        compose.waitUntil(10000) { model.state.value.result?.rows?.size == 1 && !model.state.value.busy }
        assertEquals(0, model.state.value.result!!.offset)
        assertEquals(DbValue.Integer(205), model.state.value.result!!.rows.single()[0])
        compose.onNodeWithText("刷新", substring = false).performClick()
        compose.waitUntil(10000) { !model.state.value.busy }
        assertEquals(1, model.state.value.result!!.rows.size)
        compose.onNodeWithText("清除条件").performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.result?.rows?.size == 100 && !model.state.value.busy }
        assertEquals(DbValue.Integer(1), model.state.value.result!!.rows.first()[0])
    }
}
