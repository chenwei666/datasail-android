package com.example.datasail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Driver

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchShowsHonestNavigation() {
        compose.onNodeWithText("数舟").assertIsDisplayed()
        compose.onNodeWithText("AI 助手").assertIsNotEnabled()
        compose.onNodeWithText("工作台").assertIsNotEnabled()
        compose.onNodeWithText("收藏").assertIsNotEnabled()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("开发占位包名：com.example.datasail\n首次发行前需确认正式身份与签名。").assertIsDisplayed()
    }
    @Test fun jdbcEntryPointsLoadOnAndroid() {
        // Class loading alone is not TLS/login/database compatibility evidence.
        val sqlServer = Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver").getDeclaredConstructor().newInstance() as Driver
        val mysql = Class.forName("org.mariadb.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        assertTrue(sqlServer.acceptsURL("jdbc:sqlserver://localhost:1433"))
        assertTrue(mysql.acceptsURL("jdbc:mariadb://localhost:3306/synthetic"))
    }
}
