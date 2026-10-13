package com.example.datasail

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MobileWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: ConnectionStore
    private lateinit var queries: QueryStore
    private var original = emptyList<SavedConnection>()
    private var originalQueries = emptyList<SavedQuery>()
    private lateinit var fixtureFile: File
    private var copy: File? = null
    private val fixture = object : org.junit.rules.ExternalResource() {
        override fun before() {
            store = ConnectionStore(context); original = store.load()
            queries = QueryStore(context); originalQueries = queries.load()
            fixtureFile = File(context.cacheDir, "mobile-workflow.db"); fixtureFile.delete()
            SQLiteDatabase.openOrCreateDatabase(fixtureFile, null).use { db ->
                db.execSQL("CREATE TABLE mobile_fixture (id INTEGER PRIMARY KEY, name TEXT)")
                db.execSQL("INSERT INTO mobile_fixture VALUES (1, '0007')")
            }
            val id = store.importSqlite(Uri.fromFile(fixtureFile)); copy = store.sqliteFile(id)
            store.save(listOf(SavedConnection(name = "移动工作流", kind = DatabaseKind.SQLITE, port = "0", database = id)))
            queries.save(emptyList())
        }
        override fun after() { store.save(original); queries.save(originalQueries); fixtureFile.delete(); copy?.delete() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(fixture).around(compose)
    @Test fun savedConnectionOpensWorkbenchAndFavoriteLoadsWithoutExecuting() {
            compose.onNodeWithText("打开连接").performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("连接数据库").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("SQL 编辑器").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SQL 编辑器").performScrollTo().performClick()
            compose.onNodeWithText("单条 SELECT 查询").performScrollTo().performTextReplacement("SELECT id, name FROM mobile_fixture")
            compose.onNodeWithText("执行查询").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("导出当前页 CSV").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("收藏查询").performScrollTo().performClick()
            compose.onNodeWithText("查询名称").performTextInput("移动查询")
            compose.onNodeWithText("保存收藏").performClick()
            compose.waitUntil(5000) { queries.load().size == 1 }
            assertEquals("SELECT id, name FROM mobile_fixture", queries.load().single().sql)
            compose.onNodeWithText("收藏").performClick()
            compose.onNodeWithText("载入草稿").performScrollTo().performClick()
            compose.onNodeWithText("单条 SELECT 查询").performScrollTo().assertTextContains("SELECT id, name FROM mobile_fixture")
            compose.onNodeWithText("导出当前页 CSV").performScrollTo().performClick()
            compose.onNodeWithText("导出当前结果页？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
    }
}
