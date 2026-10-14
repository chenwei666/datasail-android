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
                    for (id in 1..205) db.execSQL("INSERT INTO filter_table VALUES(?, ?)", arrayOf(id, "0007"))
                }
                val id = store.importSqlite(Uri.fromFile(source)); imported = store.sqliteFile(id)
                store.save(listOf(SavedConnection(name = "筛选排序夹具", kind = DatabaseKind.SQLITE, database = id, port = "0")))
            } finally { source.delete() }
        }
        override fun after() { ConnectionStore(context).save(previous); imported?.delete() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
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
