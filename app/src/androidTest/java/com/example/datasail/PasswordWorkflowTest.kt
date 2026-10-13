package com.example.datasail

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class PasswordWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private val connection = SavedConnection(name = "密码界面夹具", host = "localhost", username = "fixture", database = "synthetic")
    private val fixture = object : ExternalResource() {
        override fun before() { val store = ConnectionStore(context); original = store.load(); store.save(listOf(connection)) }
        override fun after() { ConnectionStore(context).save(original); PasswordVault(context).delete(connection.id) }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    @Test fun saveReopenAutofillAndForgetWithoutRevealingPassword() {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithText("密码").performScrollTo().performTextInput("synthetic-ui-password")
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("保存配置").performScrollTo().performClick()
        compose.waitUntil(10000) { PasswordVault(context).load(connection) != null }
        compose.onNodeWithText("返回我的连接").performScrollTo().performClick()
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onNodeWithText("密码").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.length == "synthetic-ui-password".length }
        val visible = compose.onNodeWithText("密码").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertTrue("Password semantics must be masked", visible.all { it == '\u2022' })
        assertArrayEquals("synthetic-ui-password".toCharArray(), PasswordVault(context).load(connection))
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onNodeWithText("密码").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithText("忘记密码").performScrollTo().performClick()
        compose.waitUntil(10000) { PasswordVault(context).load(connection) == null }
        compose.onNodeWithText("密码").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }
}
