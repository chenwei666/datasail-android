package com.example.datasail

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

class MongoUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private var password = ""
    private val fixture = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            val config = Properties().apply { File(context.filesDir, "mongo-fixture.properties").inputStream().use(::load) }
            password = config.getProperty("password")
            val ca = store.importCa(Uri.fromFile(File(context.filesDir, "mongo-ca.pem")))
            store.save(listOf(SavedConnection(name = "文档工作流", kind = DatabaseKind.MONGODB, host = "localhost", port = config.getProperty("port"), database = "synthetic", username = "reader", ca = ca)))
        }
        override fun after() { ConnectionStore(context).save(original); password = "" }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(fixture).around(compose)
    @Test fun connectionCollectionPagingAndTypedFilter() {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithText("认证数据库").performScrollTo().assertTextContains("admin")
        compose.onNodeWithText("密码（不保存）").performScrollTo().performTextInput(password)
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().assertIsEnabled().performClick()
        var model: WorkspaceViewModel? = null
        compose.runOnIdle { model = androidx.lifecycle.ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        try { compose.waitUntil(45000) { model!!.state.value.let { it.connected && !it.busy && it.collections.size == 2 } } }
        catch (error: Throwable) {
            val state = model!!.state.value
            throw AssertionError("Document UI: kind=${state.kind}, connected=${state.connected}, busy=${state.busy}, collections=${state.collections.size}, message=${state.message}", error)
        }
        compose.onNodeWithTag("mongo-collections").performScrollTo()
        compose.onNodeWithText("documents").performScrollTo().performClick()
        fun page(text: String) {
            try { compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
            catch (error: Throwable) {
                val state = model!!.state.value
                throw AssertionError("Document result UI: connected=${state.connected}, busy=${state.busy}, page=${state.documents?.page}, count=${state.documents?.documents?.size}, message=${state.message}", error)
            }
        }
        page("结果：documents · 第 1 页 · 100 个文档")
        compose.onNodeWithText("下一页文档").performScrollTo().performClick()
        page("结果：documents · 第 2 页 · 100 个文档")
        compose.onNodeWithText("文档筛选 JSON").performScrollTo().performTextReplacement("{\"name\":\"0007\"}")
        compose.onNodeWithText("下一页文档").assertIsNotEnabled()
        compose.onNodeWithText("重新查询文档").performScrollTo().performClick()
        page("结果：documents · 第 1 页 · 1 个文档")
        compose.onNodeWithText("下一页文档").assertIsNotEnabled()
        compose.onNodeWithText("9007199254740993", substring = true).performScrollTo().assertExists()
    }
}
