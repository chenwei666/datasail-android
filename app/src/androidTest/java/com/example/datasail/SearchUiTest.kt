package com.example.datasail

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

class SearchUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private var password = ""
    private val fixture = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            val config = Properties().apply { File(context.filesDir, "search-fixture.properties").inputStream().use(::load) }
            password = config.getProperty("password")
            val ca = store.importCa(Uri.fromFile(File(context.filesDir, "search-ca.pem")))
            store.save(listOf(SavedConnection(name = "搜索工作流", kind = DatabaseKind.valueOf(config.getProperty("kind")), host = "localhost", port = config.getProperty("port"), database = "synthetic", username = "reader", ca = ca)))
        }
        override fun after() { ConnectionStore(context).save(original); password = "" }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(fixture).around(compose)
    @Test fun mappingKeepsItsOriginalIndexWhileSelectionChanges() {
        compose.runOnUiThread {
            val model = androidx.lifecycle.ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]
            // A retained result while the selection changes; the disconnected model cannot issue I/O.
            compose.activity.setContent {
                MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    SearchWorkbench(model, WorkspaceUiState(kind = DatabaseKind.OPENSEARCH,
                        indices = listOf("new-index"), mapping = IndexMapping("original-index", "{\"original-index\":{}}")))
                } }
            }
        }
        compose.onNodeWithText("new-index").performScrollTo().performClick()
        compose.onNodeWithText("当前索引：new-index").performScrollTo().assertExists()
        compose.onNodeWithText("索引映射：original-index").performScrollTo().assertExists()
        compose.onNodeWithText("索引映射：new-index").assertDoesNotExist()
    }
    @Test fun connectionCollectionPagingAndTypedFilter() {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithText("索引范围（如 logs-*）").performScrollTo().assertTextContains("synthetic")
        compose.onNodeWithText("密码（不保存）").performScrollTo().performTextInput(password)
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().assertIsEnabled().performClick()
        var model: WorkspaceViewModel? = null
        compose.runOnIdle { model = androidx.lifecycle.ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        try { compose.waitUntil(45000) { model!!.state.value.let { it.connected && !it.busy && it.indices.size == 1 } } }
        catch (error: Throwable) {
            val state = model!!.state.value
            throw AssertionError("Document UI: kind=${state.kind}, connected=${state.connected}, busy=${state.busy}, collections=${state.indices.size}, message=${state.message}", error)
        }
        compose.onNodeWithTag("search-indices").performScrollTo()
        compose.onNodeWithText("synthetic").performScrollTo().performClick()
        fun page(text: String) {
            try { compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
            catch (error: Throwable) {
                val state = model!!.state.value
                throw AssertionError("Document result UI: connected=${state.connected}, busy=${state.busy}, page=${state.searchPage?.offset}, count=${state.searchPage?.hits?.size}, message=${state.message}", error)
            }
        }
        page("结果：synthetic · 第 1 页 · 100 条")
        compose.onNodeWithText("下一页搜索结果").performScrollTo().performClick()
        page("结果：synthetic · 第 2 页 · 100 条")
        compose.onNodeWithText("查询条件 DSL").performScrollTo().performTextReplacement("{\"term\":{\"code\":\"0007\"}}")
        compose.onNodeWithText("下一页搜索结果").assertIsNotEnabled()
        compose.onNodeWithText("重新搜索").performScrollTo().performClick()
        page("结果：synthetic · 第 1 页 · 1 条")
        compose.onNodeWithText("下一页搜索结果").assertIsNotEnabled()
        compose.onNodeWithText("9007199254740993", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("查看映射").performScrollTo().performClick()
        compose.waitUntil(20000) { model!!.state.value.mapping != null }
        compose.onNodeWithText("索引映射：synthetic").performScrollTo().assertExists()
    }
}
