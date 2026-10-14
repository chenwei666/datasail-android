package com.example.datasail

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.util.Properties

/** Opt-in: scans a real TLS JDBC server from an initially blank target form. */
class DiscoveryUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private var password = ""
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            val config = Properties().apply { File(context.filesDir, "jdbc-fixture.properties").inputStream().use(::load) }
            password = config.getProperty("password")
            val ca = store.importCa(Uri.fromFile(File(context.filesDir, "jdbc-ca.pem")))
            store.save(listOf(SavedConnection(name = "服务器扫描", kind = DatabaseKind.valueOf(config.getProperty("kind")),
                host = "localhost", port = config.getProperty("port"), username = "datasail", ca = ca)))
        }
        override fun after() { ConnectionStore(context).save(original); password = "" }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun scan(): WorkspaceViewModel {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("密码").performScrollTo().performTextInput(password)
        compose.onNodeWithTag("connection-authorization").performScrollTo().performClick()
        compose.onNodeWithText("连接服务器并扫描").performScrollTo().assertIsEnabled().performClick()
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = androidx.lifecycle.ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.waitUntil(45000) { !model.discovery.value.busy }
        assertNotNull(model.discovery.value.message, model.discovery.value.result)
        assertFalse(model.state.value.connected)
        return model
    }
    @Test fun scanSelectAndConnectWithoutKnowingDatabaseName() {
        val model = scan()
        compose.onNodeWithTag("discovered-database-picker").performScrollTo().performClick()
        if (ConnectionStore(context).load().single().kind == DatabaseKind.POSTGRESQL) {
            compose.onNodeWithText(" synthetic ").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("picker-search").performTextInput("synthetic")
        compose.onNode(hasText("synthetic") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("数据库").assertTextContains("synthetic")
        compose.onNodeWithText("连接数据库").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(45000) { model.state.value.let { it.connected && !it.busy } }
        assertEquals("synthetic", model.state.value.catalog)
    }
    @Test fun editingEndpointInvalidatesDiscoveredTargetsAndAuthorization() {
        val model = scan()
        compose.onNodeWithText("服务器 DNS / IPv4").performScrollTo().performTextReplacement("127.0.0.1")
        compose.runOnIdle { assertNull(model.discovery.value.result) }
        compose.onNodeWithTag("discovered-database-picker").assertDoesNotExist()
        compose.onNodeWithText("连接服务器并扫描").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("密码").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }
}
