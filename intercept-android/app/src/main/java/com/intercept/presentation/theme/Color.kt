package com.intercept.presentation.theme

import androidx.compose.ui.graphics.Color
import com.intercept.domain.model.RiskLevel

/**
 * "Quiet until it matters": the chrome is monochrome, and colour is information.
 * Risk is this product's entire output, so risk gets the entire colour budget —
 * nothing decorative is allowed to compete with it.
 */

// ---- Chrome ----
val Ink = Color(0xFF14181D)
val Paper = Color(0xFFFFFFFF)
val Band = Color(0xFFF3F4F6)
val Wire = Color(0xFFE3E6EA)
val Muted = Color(0xFF6B7280)

// ---- The risk ramp: the only saturation in the app ----
val RiskLow = Color(0xFF1E7F4F)
val RiskSuspicious = Color(0xFFA9700C)
val RiskHigh = Color(0xFFC2410C)
val RiskCritical = Color(0xFFC1121F)

// Tints for containers that carry ink text on top of a risk colour.
val RiskLowTint = Color(0xFFEAF6EE)
val RiskSuspiciousTint = Color(0xFFFBF2E2)
val RiskHighTint = Color(0xFFFBEDE5)
val RiskCriticalTint = Color(0xFFFBEAEC)

// ---- The room ----
// The live interception is the one dark surface, and the product's signature
// moment. Same values on the website, so both feel like the same room.
val Room = Color(0xFF14181D)
val RoomRaised = Color(0xFF1E242B)
val RoomWire = Color(0xFF2B333C)
val RoomInk = Color(0xFFF2F5F7)
val RoomMuted = Color(0xFF96A1AD)

// The same ramp, lifted for the dark room. Identical hues, enough luminance to
// stay legible on #14181D — the light ramp would go muddy against it.
val RiskLowOnRoom = Color(0xFF57C98A)
val RiskSuspiciousOnRoom = Color(0xFFE0A93C)
val RiskHighOnRoom = Color(0xFFF0895D)
val RiskCriticalOnRoom = Color(0xFFFF6B74)

/** The brand mint. Lives in the logo mark only, where it never reads as a risk level. */
val Mint = Color(0xFF2FD07F)

/** The risk ramp, keyed to the domain enum so no screen re-declares a hex. */
val RiskLevel.risk: Color
    get() = when (this) {
        RiskLevel.LOW -> RiskLow
        RiskLevel.SUSPICIOUS -> RiskSuspicious
        RiskLevel.HIGH -> RiskHigh
        RiskLevel.CRITICAL -> RiskCritical
    }

/** A container tint for [risk] — light enough that ink text stays legible on it. */
val RiskLevel.riskTint: Color
    get() = when (this) {
        RiskLevel.LOW -> RiskLowTint
        RiskLevel.SUSPICIOUS -> RiskSuspiciousTint
        RiskLevel.HIGH -> RiskHighTint
        RiskLevel.CRITICAL -> RiskCriticalTint
    }

/** The same risk, on the dark room instead of paper. */
val RiskLevel.riskOnRoom: Color
    get() = when (this) {
        RiskLevel.LOW -> RiskLowOnRoom
        RiskLevel.SUSPICIOUS -> RiskSuspiciousOnRoom
        RiskLevel.HIGH -> RiskHighOnRoom
        RiskLevel.CRITICAL -> RiskCriticalOnRoom
    }
