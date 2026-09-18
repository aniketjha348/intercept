package com.intercept.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.intercept.domain.model.RiskLevel
import com.intercept.presentation.theme.RoomRaised
import com.intercept.presentation.theme.risk
import com.intercept.presentation.theme.riskOnRoom
import com.intercept.presentation.theme.riskTint

/**
 * "SUSPICIOUS" is how the model names it; "Suspicious" is how a person reads it.
 * The audience for this app includes people who are not comfortable with
 * technology, so the enum label is never shown raw.
 */
val RiskLevel.displayName: String
    get() = label.lowercase().replaceFirstChar { it.uppercase() }

/** The risk level as a labelled token. The dot carries the colour; the text stays readable. */
@Composable
fun RiskChip(level: RiskLevel, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val container = if (onDark) RoomRaised else level.riskTint
    val accent = if (onDark) level.riskOnRoom else level.risk
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Text(
            level.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}
