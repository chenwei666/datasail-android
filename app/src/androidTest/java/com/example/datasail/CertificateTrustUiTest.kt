package com.example.datasail

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
import java.util.Properties

/** Opt-in real SQL Server with its automatic self-signed certificate; no imported CA. */
class CertificateTrustUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyList<SavedConnection>()
    private var config = Properties()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val store = ConnectionStore(context); original = store.load()
            config = Properties().apply { File(context.filesDir, "fixture.properties").inputStream().use(::load) }
            store.save(listOf(SavedConnection(name = "自动证书测试", host = "127.0.0.1", port = config.getProperty("port"), username = "sa")))
        }
        override fun after() { ConnectionStore(context).save(original); config.clear() }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    @Test fun inspectConfirmPersistScanAndConnectWithAutomaticCertificate() {
        compose.onNodeWithText("打开连接").performScrollTo().performClick()
        compose.onNodeWithTag("connection-authorization").performScrollTo().performClick()
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.onNodeWithText("密码").performScrollTo().performTextReplacement(config.getProperty("password"))
        compose.onNodeWithText("连接服务器并扫描").performScrollTo().performClick()
        compose.waitUntil(45000) { !model.discovery.value.busy }
        assertTrue(model.discovery.value.tlsFailure)
        // State emission precedes the LaunchedEffect scroll; require automatic visibility, without scrolling the test.
        compose.waitUntil(10000) { runCatching { compose.onNodeWithText("需要确认服务器证书").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("需要确认服务器证书").assertIsDisplayed()
        compose.onNodeWithText("选择 CA 证书").assertDoesNotExist()
        compose.onNodeWithText("获取并核对服务器证书").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(45000) { !model.discovery.value.busy }
        assertNotNull(model.discovery.value.message, model.pendingCertificate.value)
        assertFalse(model.state.value.connected)
        assertNull(ConnectionStore(context).load().single().certificateTrust)
        compose.onNodeWithText("信任并保存到此连接").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        compose.onNodeWithText("信任并保存到此连接").assertIsEnabled().performClick()
        compose.waitUntil(10000) { ConnectionStore(context).load().single().certificateTrust != null }
        val stored = ConnectionStore(context).load().single()
        assertEquals(stored.id, stored.certificateTrust!!.connectionId)
        assertEquals("127.0.0.1", stored.certificateTrust!!.host)
        assertEquals(stored.certificateTrust!!.sha256, stored.copy(database = "master").profile(context).certificatePin)
        compose.onNodeWithText("密码").performScrollTo().performTextReplacement(config.getProperty("password"))
        compose.onNodeWithText("连接服务器并扫描").performScrollTo().performClick()
        compose.waitUntil(45000) { !model.discovery.value.busy }
        assertTrue(model.discovery.value.message, "master" in model.discovery.value.result!!.names)
        compose.onNodeWithTag("discovered-database-picker").performScrollTo().performClick()
        compose.onNodeWithText("master").performScrollTo().performClick()
        compose.onNodeWithText("连接数据库").performScrollTo().performClick()
        compose.waitUntil(45000) { model.state.value.let { it.connected && !it.busy } }
        assertEquals("master", model.state.value.catalog)
    }
    @Test fun cancellingInspectionAndEditingEndpointCannotSaveTrust() {
        lateinit var model: WorkspaceViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java] }
        compose.waitUntil(10000) { model.saved.value.isNotEmpty() }
        compose.runOnIdle { model.inspectCertificate(model.saved.value.single(), "") }
        compose.waitUntil(45000) { !model.discovery.value.busy }
        assertNotNull(model.pendingCertificate.value)
        compose.runOnIdle {
            assertNull(model.confirmCertificate(model.saved.value.single().copy(port = "1434")))
            model.cancelDiscovery()
            assertNull(model.confirmCertificate(model.saved.value.single()))
        }
        assertNull(ConnectionStore(context).load().single().certificateTrust)
        compose.runOnIdle { model.inspectCertificate(model.saved.value.single(), ""); model.cancelDiscovery() }
        compose.runOnIdle { assertNull(model.pendingCertificate.value) }
    }
}
