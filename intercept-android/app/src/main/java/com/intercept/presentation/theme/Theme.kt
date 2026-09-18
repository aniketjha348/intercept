package com.intercept.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Two schemes, one identity.
 *
 * [RoomScheme] exists because the live interception is deliberately a dark
 * "control room" — the same surface the website opens with. Passing `room = true`
 * re-colours everything inside so no Material default can leak a light-on-light
 * or dark-on-dark pair.
 */
private val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Band,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Paper,
    secondaryContainer = Band,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Band,
    onSurfaceVariant = Muted,
    outline = Wire,
    outlineVariant = Wire,
    error = RiskCritical,
    onError = Paper,
    errorContainer = RiskCriticalTint,
    onErrorContainer = RiskCritical,
)

private val RoomScheme = darkColorScheme(
    primary = RoomInk,
    onPrimary = Room,
    primaryContainer = RoomRaised,
    onPrimaryContainer = RoomInk,
    secondary = RoomInk,
    onSecondary = Room,
    secondaryContainer = RoomRaised,
    onSecondaryContainer = RoomInk,
    tertiary = RoomInk,
    onTertiary = Room,
    background = Room,
    onBackground = RoomInk,
    surface = Room,
    onSurface = RoomInk,
    surfaceVariant = RoomRaised,
    onSurfaceVariant = RoomMuted,
    outline = RoomWire,
    outlineVariant = RoomWire,
    error = RiskCritical,
    onError = Paper,
    errorContainer = RoomRaised,
    onErrorContainer = RoomInk,
)

@Composable
fun InterceptTheme(room: Boolean = false, content: @Composable () -> Unit) {
    // The app rides on the framework's dark Material theme, so the status bar
    // ships dark with light icons no matter what is under it. Paint it to match
    // whichever surface is on screen — background and icon colour are always set
    // together, so they can never disagree.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = if (room) Room.toArgb() else Paper.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !room
        }
    }

    MaterialTheme(
        colorScheme = if (room) RoomScheme else LightScheme,
        typography = InterceptTypography,
        shapes = InterceptShapes,
        content = content,
    )
}
