package com.example.datasail

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class AiNavigationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous = AiPreferences()
    private val fixture = object : ExternalResource() {
        override fun before() { previous = AiStore(context).load(); AiStore(context).save(AiPreferences()) }
        override fun after() { AiStore(context).save(previous) }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun unconfiguredAssistantRoutesToSettingsWithoutInlineCredentials() {
        compose.onNodeWithText("AI 助手").performClick()
        compose.onNodeWithTag("ai-key").assertDoesNotExist()
        compose.onNodeWithTag("ai-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("ai-enabled").assertDoesNotExist()
        compose.onNodeWithText("发送").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("前往 AI 设置").performScrollTo().performClick()
        compose.onNodeWithTag("ai-endpoint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai-key").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("配置 AI 服务与模型").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("ai-model").assertExists()
        compose.onNodeWithText("打开 AI 助手").performScrollTo().performClick()
        compose.onNodeWithTag("ai-key").assertDoesNotExist()
        compose.onNodeWithTag("ai-model").assertDoesNotExist()
    }
}
