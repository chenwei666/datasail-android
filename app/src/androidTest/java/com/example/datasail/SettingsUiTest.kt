package com.example.datasail

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class SettingsUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: AppSettings
    private val fixture = object : ExternalResource() {
        override fun before() { original = AppSettingsStore(context).load(); AppSettingsStore(context).save(AppSettings()) }
        override fun after() { AppSettingsStore(context).save(original) }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)
    @Test fun changeThemeAndCompletionPersistAndResetRequiresConfirmation() {
        compose.onNodeWithText("设置").performClick()
        // Only synthetic fixture data is visible; production retains FLAG_SECURE.
        compose.runOnIdle { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.filesDir, "v080-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.waitUntil(10000) { AppSettingsStore(context).load().theme == ThemeMode.DARK }
        // Two grid switches precede SQL completion in this screen.
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
        compose.waitUntil(10000) { !AppSettingsStore(context).load().completion }
        compose.onNodeWithText("恢复默认设置").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(ThemeMode.DARK, AppSettingsStore(context).load().theme)
        compose.onNodeWithText("恢复默认设置").performScrollTo().performClick()
        compose.onNodeWithText("确认恢复设置").performClick()
        compose.waitUntil(10000) { AppSettingsStore(context).load() == AppSettings() }
    }
}
