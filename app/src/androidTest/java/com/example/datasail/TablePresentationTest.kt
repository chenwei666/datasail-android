package com.example.datasail

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.view.WindowManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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

class TablePresentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous = emptyList<SavedConnection>()
    private var imported: File? = null
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); previous = store.load()
            val source = File(context.cacheDir, "wide-ui.db"); source.delete()
            try {
                SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                    db.execSQL("CREATE TABLE wide_table(id INTEGER PRIMARY KEY," + (1..129).joinToString(",") { "c$it TEXT" } + ")")
                    db.execSQL("INSERT INTO wide_table(id,c128,c129) VALUES (1,'最后一组','0007 完整已加载值 中文换行')")
                }
                val id = store.importSqlite(Uri.fromFile(source)); imported = store.sqliteFile(id)
                store.save(listOf(SavedConnection(name = "宽表展示", kind = DatabaseKind.SQLITE, database = id, port = "0")))
            } finally { source.delete() }
        }
        override fun after() { ConnectionStore(context).save(previous); imported?.delete() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun finishSearchInput() {
        // Browse metadata after dismissing the soft keyboard, as on a phone in landscape.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
        compose.waitUntil(5000) {
            compose.runOnIdle { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
        }
    }
    @Test fun all130ColumnsAreReachableWithFullscreenAndRowDetails() {
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.onNodeWithText("连接并进入工作台").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("object-wide_table").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-list").assertIsDisplayed(); compose.onNodeWithTag("object-wide_table").performClick()
        // Semantics reads synchronize Compose; raw StateFlow polling can observe a pending UI callback.
        compose.waitUntil(10000) { compose.onAllNodesWithTag("result-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNotNull(model.state.value.result) }
        compose.onNodeWithText("表结构").performClick()
        compose.onNodeWithTag("structure-search").performScrollTo().performTextInput("c129")
        finishSearchInput()
        compose.onNodeWithTag("structure-columns").performScrollTo()
        compose.onNodeWithText("130. c129").assertIsDisplayed()
        compose.onNodeWithText("显示 1 / 130 个字段").assertExists()
        compose.onNodeWithText("仅主键").performScrollTo().assertIsDisplayed().performClick()
        // Input and scroll callbacks can finish after Compose's first idle frame on a software API30 device.
        try {
            compose.waitUntil(10000) { compose.onAllNodes(hasText("仅主键") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            compose.onRoot().printToLog("DataSailWideFilter")
            throw error
        }
        compose.onNodeWithText("仅主键").assertIsSelected()
        compose.onNodeWithText("没有匹配字段").assertExists()
        compose.onNodeWithTag("structure-search").performScrollTo().performTextReplacement("id")
        finishSearchInput()
        compose.onNodeWithTag("structure-columns").performScrollTo()
        compose.onNodeWithText("1. id").assertIsDisplayed()
        compose.onNodeWithTag("copy-column-0").performScrollTo().performClick()
        // The Android clipboard service updates after the input callback on API30.
        // Wait for the actual copied value, retaining the same content assertion.
        compose.waitUntil(10000) {
            compose.runOnIdle {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "id"
            }
        }
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("id", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("清除筛选").performScrollTo().performClick()
        compose.onNodeWithText("显示 130 / 130 个字段").assertExists()
        compose.onNodeWithText("数据").performClick()
        for (offset in listOf(64,128)) {
            compose.onNodeWithText("下一组字段").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("字段 ${offset + 1}–${minOf(offset + 64, 130)} / 130", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { assertEquals(offset, model.state.value.result!!.columnOffset) }
        }
        assertEquals(listOf("c128", "c129"), model.state.value.result!!.columns.map { it.name })
        compose.onNodeWithText("下一组字段").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("grid-tools").performScrollTo()
        compose.onNodeWithText("全屏表格").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithText("退出全屏").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.onAllNodesWithText("换行").onLast().performClick()
        compose.onAllNodesWithTag("cell-0-1").onLast().performScrollTo().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        val detailsButton = compose.onNode(hasText("整行详情") and hasAnyAncestor(isDialog()))
        // Its immediate parent scrolls horizontally. Bring that toolbar into the
        // outer vertical viewport before injecting a real touch on the button.
        detailsButton.onParent().performScrollTo()
        detailsButton.assertIsDisplayed().performClick()
        // Wait for the nested details dialog; the same value is already visible
        // in the underlying grid and cannot prove the click callback completed.
        compose.waitUntil(10000) { compose.onAllNodesWithText("第 1 行 · 已加载字段").fetchSemanticsNodes().isNotEmpty() }
        val details = isDialog() and hasAnyDescendant(hasText("第 1 行 · 已加载字段"))
        compose.onNode(hasText("0007 完整已加载值 中文换行") and hasAnyAncestor(details)).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        if (android.os.Build.VERSION.SDK_INT >= 28) compose.onNodeWithText("退出全屏").onParent().onParent().captureToImage().asAndroidBitmap().let { bitmap -> File(context.filesDir,"table-v091.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        compose.onNodeWithText("退出全屏").performClick()
    }
}
