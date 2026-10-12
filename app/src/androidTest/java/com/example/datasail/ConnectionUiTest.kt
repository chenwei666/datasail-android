package com.example.datasail

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class ConnectionUiTest {
    private lateinit var store: ConnectionStore
    private var original = emptyList<SavedConnection>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
            original = store.load()
            store.save(listOf(SavedConnection(name = "UI 测试连接", host = "localhost", port = "6379", database = "0", username = "default", kind = DatabaseKind.REDIS)))
        }
        override fun after() { store.save(original) }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    // Seed before Activity/ViewModel construction; recreation intentionally retains ViewModels.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    @Test fun engineSearchAdaptsFormAndPreventsInvalidConnection() {
        compose.onNodeWithText("新建连接").performClick()
        compose.onNodeWithText("搜索可连接的数据库").performTextInput("Valkey")
        compose.onNode(hasText("Valkey") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("配置 Valkey").assertIsDisplayed()
        compose.onNodeWithText("数据库编号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ACL 用户名").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("连接数据库").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("返回我的连接").performScrollTo().performClick()
        compose.onNodeWithText("新建连接").performClick()
        compose.onNodeWithText("搜索可连接的数据库").performTextInput("SQLite")
        compose.onNode(hasText("SQLite") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("选择 SQLite 文件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("密码（不保存）").assertDoesNotExist()
    }
    @Test fun searchAndDeleteRequireConfirmation() {
        compose.onNodeWithText("搜索连接名称、数据库或主机").performTextInput("missing")
        compose.onNodeWithText("没有匹配的连接").assertIsDisplayed()
        compose.onNodeWithText("搜索连接名称、数据库或主机").performTextReplacement("UI 测试")
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, store.load().size)
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) { store.load().isEmpty() }
    }
}
