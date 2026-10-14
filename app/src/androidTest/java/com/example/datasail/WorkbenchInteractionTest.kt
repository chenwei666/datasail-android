package com.example.datasail

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.sql.Types

@OptIn(ExperimentalTestApi::class)
class WorkbenchInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fullscreenShowsTableRestrictionWithoutSelectingACell() {
        val schema = TableSchema(TableRef("db", "dbo", "records", 1, false), listOf(ColumnDef("code", Types.NVARCHAR, "nvarchar", true, 20, 0, false, false)), false)
        val page = ResultPage(listOf(ResultColumn("code", "nvarchar", Types.NVARCHAR)), listOf(listOf(DbValue.Text("0007"))), false, tableSchema = schema)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ResultGrid(page, WorkspaceUiState(connected = true, writable = true), AppSettings(), {}, { _, _ -> })
        } } } }
        compose.onNodeWithText("全屏表格").performScrollTo().performClick()
        compose.onNode(hasTestTag("table-editing-status") and hasAnyAncestor(isDialog())).assertIsDisplayed().assertTextContains("没有主键", substring = true)
    }
    @Test fun pickerSearchesHundredsWithoutGrowingThePage() {
        var chosen = ""
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            SearchPicker("选择数据库", (1..500).map { "catalog_$it" }, "", {}, select = { chosen = it })
        } } }
        compose.onNodeWithTag("picker-search").performTextInput("catalog_500")
        compose.onNodeWithText("1 / 500 项").assertIsDisplayed()
        compose.onNode(hasText("catalog_500") and !hasSetTextAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("catalog_500", chosen) }
    }
    @Test fun pinchZoomKeepsValuesAndResetRestoresScale() {
        val page = ResultPage(listOf(ResultColumn("code", "nvarchar", Types.NVARCHAR)), listOf(listOf(DbValue.Text("0007"))), false)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ResultGrid(page, WorkspaceUiState(), AppSettings(), {}, { _, _ -> })
        } } } }
        compose.onNodeWithTag("result-grid").performScrollTo().performTouchInput {
            val a = Offset(center.x - 30f, center.y); val b = Offset(center.x + 30f, center.y)
            down(0, a); down(1, b)
            for (step in 1..10) { updatePointerTo(0, a - Offset(step * 6f, 0f)); updatePointerTo(1, b + Offset(step * 6f, 0f)); move(30) }
            up(0); up(1)
        }
        compose.onNodeWithTag("grid-zoom").assertTextContains("200% · 重置")
        compose.onNodeWithTag("cell-0-0").assertTextContains("0007")
        compose.onNodeWithTag("grid-zoom").performScrollTo().performClick()
        compose.onNodeWithTag("grid-zoom").assertTextContains("100% · 重置")
        compose.onNodeWithTag("result-grid").performScrollTo().performTouchInput {
            val a = Offset(center.x - 60f, center.y); val b = Offset(center.x + 60f, center.y)
            down(0, a); down(1, b)
            for (step in 1..10) { updatePointerTo(0, a + Offset(step * 4f, 0f)); updatePointerTo(1, b - Offset(step * 4f, 0f)); move(30) }
            up(0); up(1)
        }
        compose.onNodeWithTag("grid-zoom").assertTextContains("65% · 重置")
        compose.onNodeWithText("0007", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(layouts)); assertEquals(1, layouts.size); assertFalse("Shrunk cell must not clip text vertically", layouts.single().didOverflowHeight)
        }
    }
    @Test fun whereOffersColumnAndHardwareTabAcceptsWithoutExecution() {
        val table = TableRef("db", "dbo", "orders", 1, false)
        val schema = TableSchema(table, listOf(ColumnDef("order_id", Types.INTEGER, "int", false, 4, 0, false, false, 1)), false)
        var sql = "SELECT * FROM orders WHERE"
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { Column {
            var draft by remember { mutableStateOf(sql) }
            SqlEditor(draft, { draft = it; sql = it }, WorkspaceUiState(result = ResultPage(emptyList(), emptyList(), false, tableSchema = schema)), AppSettings())
        } } } }
        compose.onNodeWithText("order_id", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("sql-editor").performTextReplacement("SELECT * FROM orders WHERE")
        compose.onNodeWithTag("sql-editor").performKeyInput { pressKey(Key.Tab) }
        compose.runOnIdle { assertEquals("SELECT * FROM orders WHERE [order_id]", sql) }
        compose.onNodeWithTag("sql-editor").performTextInputSelection(androidx.compose.ui.text.TextRange(0, sql.length))
        compose.onNodeWithTag("sql-editor").performKeyInput { pressKey(Key.Tab) }
        compose.runOnIdle { assertEquals("SELECT * FROM orders WHERE [order_id]", sql) }
    }
}
