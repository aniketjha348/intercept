package com.intercept.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.intercept.R

/**
 * Mukta (Ek Type, Mumbai) carries Latin and Devanagari in a single design, so
 * Hindi and English read in the same voice instead of a translation in another
 * accent. The build ships three weights; anything heavier is synthesised.
 */
val Mukta = FontFamily(
    Font(R.font.mukta_regular, FontWeight.Normal),
    Font(R.font.mukta_semibold, FontWeight.SemiBold),
    Font(R.font.mukta_bold, FontWeight.Bold),
)

/**
 * Machine output — risk scores, session ids, signal codes, timestamps. This is
 * the AI's voice and nothing else's, so it is the one place a monospace appears.
 */
val Machine = FontFamily.Monospace

val InterceptTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.Bold,
        fontSize = 33.sp, lineHeight = 39.sp, letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mukta, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp,
    ),
)
