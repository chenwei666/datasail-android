package com.example.datasail

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val compactGrid: Boolean = true,
    val editorFont: Int = 14, val gridFont: Int = 14, val completion: Boolean = true,
    val showColumnTypes: Boolean = true, val defaultProduction: Boolean = false, val wrapCells: Boolean = false, val columnWidth: Int = 200)
internal class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("workspace-settings-v1", Context.MODE_PRIVATE)
    fun load(): AppSettings = AppSettings(
        theme = runCatching { ThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        compactGrid = preferences.getBoolean("compactGrid", true),
        editorFont = preferences.getInt("editorFont", 14).takeIf { it in listOf(12,14,16,18,20) } ?: 14,
        gridFont = preferences.getInt("gridFont", 14).takeIf { it in listOf(12,14,16,18,20) } ?: 14,
        completion = preferences.getBoolean("completion", true),
        showColumnTypes = preferences.getBoolean("showColumnTypes", true),
        defaultProduction = preferences.getBoolean("defaultProduction", false),
        wrapCells = preferences.getBoolean("wrapCells", false),
        columnWidth = preferences.getInt("columnWidth", 200).takeIf { it in listOf(140,200,280,360) } ?: 200)
    // KTX edit discards commit() success; keep explicit error reporting.
    @android.annotation.SuppressLint("UseKtx")
    fun save(value: AppSettings) {
        require(value.columnWidth in listOf(140,200,280,360))
        require(value.editorFont in listOf(12,14,16,18,20) && value.gridFont in listOf(12,14,16,18,20))
        check(preferences.edit().putString("theme", value.theme.name).putBoolean("compactGrid", value.compactGrid)
            .putInt("editorFont", value.editorFont).putInt("gridFont", value.gridFont).putBoolean("completion", value.completion)
            .putBoolean("wrapCells", value.wrapCells).putInt("columnWidth", value.columnWidth)
            .putBoolean("showColumnTypes", value.showColumnTypes).putBoolean("defaultProduction", value.defaultProduction).commit())
    }
}
