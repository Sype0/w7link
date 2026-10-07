// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity

/** Semantic colours that Material's ColorScheme has no slot for. */
@Immutable
data class HeartlineColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurfaceVariant: Color,
    val divider: Color,
    val primary: Color,
    val ecg: Color,
    val bp: Color,
    val spo2: Color,
    val temp: Color,
    val body: Color,
    val stress: Color,
    val heartRate: Color,
    val statusNormal: Color,
    val statusWarn: Color,
    val statusAlert: Color,
    val ecgGridMajor: Color,
    val ecgGridMinor: Color,
    val ecgTrace: Color,
    val isDark: Boolean,
    /** Text and icons on [primary]: white on the classic palette, whatever Material You pairs with it otherwise. */
    val onPrimary: Color = Color.White,
) {
    fun metric(metric: Metric): Color = when (metric) {
        Metric.ECG -> ecg
        Metric.BLOOD_PRESSURE -> bp
        Metric.HEART_RATE -> heartRate
        Metric.SPO2 -> spo2
        Metric.SKIN_TEMPERATURE -> temp
        Metric.BODY_COMPOSITION -> body
        Metric.STRESS -> stress
    }

    fun severity(severity: Severity): Color = when (severity) {
        Severity.NORMAL -> statusNormal
        Severity.WARN -> statusWarn
        Severity.ALERT -> statusAlert
        Severity.NEUTRAL -> onSurfaceVariant
    }
}

private fun c(argb: Long) = Color(argb)

val LightHeartlineColors = with(Palette.Light) {
    HeartlineColors(
        c(BACKGROUND), c(SURFACE), c(SURFACE_VARIANT), c(ON_BACKGROUND), c(ON_SURFACE_VARIANT), c(DIVIDER),
        c(PRIMARY), c(ECG), c(BP), c(SPO2), c(TEMP), c(BODY), c(STRESS), c(HEART_RATE),
        c(STATUS_NORMAL), c(STATUS_WARN), c(STATUS_ALERT), c(ECG_GRID_MAJOR), c(ECG_GRID_MINOR), c(ECG_TRACE),
        isDark = false,
    )
}

val DarkHeartlineColors = with(Palette.Dark) {
    HeartlineColors(
        c(BACKGROUND), c(SURFACE), c(SURFACE_VARIANT), c(ON_BACKGROUND), c(ON_SURFACE_VARIANT), c(DIVIDER),
        c(PRIMARY), c(ECG), c(BP), c(SPO2), c(TEMP), c(BODY), c(STRESS), c(HEART_RATE),
        c(STATUS_NORMAL), c(STATUS_WARN), c(STATUS_ALERT), c(ECG_GRID_MAJOR), c(ECG_GRID_MINOR), c(ECG_TRACE),
        isDark = true,
    )
}

val LocalHeartlineColors = staticCompositionLocalOf { LightHeartlineColors }

object HeartlineTheme {
    val colors: HeartlineColors
        @Composable get() = LocalHeartlineColors.current
}

enum class ThemeStyle { MATERIAL_YOU, CLASSIC }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The user's theme choices, as Compose state so the whole app follows a change at once. */
object ThemePrefs {
    var style by mutableStateOf(ThemeStyle.MATERIAL_YOU)
        private set
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(context: Context) {
        style = ThemeStyle.entries.firstOrNull { it.name == prefs(context).getString("style", null) } ?: ThemeStyle.MATERIAL_YOU
        mode = ThemeMode.entries.firstOrNull { it.name == prefs(context).getString("mode", null) } ?: ThemeMode.SYSTEM
    }

    fun setStyle(context: Context, value: ThemeStyle) {
        style = value
        prefs(context).edit().putString("style", value.name).apply()
    }

    fun setMode(context: Context, value: ThemeMode) {
        mode = value
        prefs(context).edit().putString("mode", value.name).apply()
    }

    @Composable
    fun dark(): Boolean = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

@Composable
fun HeartlineTheme(darkTheme: Boolean = ThemePrefs.dark(), content: @Composable () -> Unit) {
    if (ThemePrefs.style == ThemeStyle.MATERIAL_YOU) {
        // Material 3 with the system's wallpaper colours; the metric and status colours keep their meaning.
        val context = LocalContext.current
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val base = if (darkTheme) DarkHeartlineColors else LightHeartlineColors
        val themed = base.copy(
            background = dynamic.surface,
            surface = dynamic.surfaceContainer,
            surfaceVariant = dynamic.surfaceContainerHighest,
            onBackground = dynamic.onSurface,
            onSurfaceVariant = dynamic.onSurfaceVariant,
            divider = dynamic.outlineVariant,
            primary = dynamic.primary,
            onPrimary = dynamic.onPrimary,
        )
        CompositionLocalProvider(LocalHeartlineColors provides themed) {
            MaterialTheme(colorScheme = dynamic, typography = HeartlineTypography, content = content)
        }
        return
    }
    val colors = if (darkTheme) DarkHeartlineColors else LightHeartlineColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onBackground,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            surfaceContainer = colors.surface,
            outlineVariant = colors.divider,
            error = colors.statusAlert,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onBackground,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            surfaceContainer = colors.surface,
            outlineVariant = colors.divider,
            error = colors.statusAlert,
        )
    }
    CompositionLocalProvider(LocalHeartlineColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = HeartlineTypography, content = content)
    }
}
