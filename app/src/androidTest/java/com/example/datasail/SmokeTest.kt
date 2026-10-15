package com.example.datasail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Driver

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchShowsHonestNavigation() {
        compose.onNodeWithText("数舟").assertIsDisplayed()
        compose.onNodeWithText("AI 助手").assertIsEnabled()
        compose.onNodeWithText("工作台").performClick()
        compose.onNodeWithText("请在连接页选择数据库并连接，然后浏览表或执行查询。").assertIsDisplayed()
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("常用查询").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("开发诊断与数据库支持").performScrollTo().performClick()
        compose.onNodeWithText("应用标识：com.example.datasail · 构建：${BuildConfig.BUILD_TYPE}").performScrollTo().assertIsDisplayed()
    }
    @Test fun jdbcEntryPointsLoadOnAndroid() {
        // Class loading alone is not TLS/login/database compatibility evidence.
        val sqlServer = Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver").getDeclaredConstructor().newInstance() as Driver
        val mysql = Class.forName("org.mariadb.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        assertTrue(sqlServer.acceptsURL("jdbc:sqlserver://localhost:1433"))
        assertTrue(mysql.acceptsURL("jdbc:mariadb://localhost:3306/synthetic"))
    }
    @Test fun metadataRoundTripPreservesConnectionPolicy() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConnectionStore(context)
        val original = store.load()
        try {
            val connection = SavedConnection(name = "合成连接", host = "localhost", database = "synthetic", username = "fixture", production = true)
            val multi = com.example.datasail.core.DatabaseKind.entries.map { connection.copy(id = it.name, kind = it) }
            store.save(multi)
            assertEquals(multi, ConnectionStore(context).load())
            val file = java.io.File(context.filesDir, "connections-v1.json").readText()
            assertFalse(file.contains("password"))
            assertEquals(com.example.datasail.core.ConnectionEnvironment.PRODUCTION, store.load().first().profile(context).environment)
        } finally { store.save(original) }
    }

}
