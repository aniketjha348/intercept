package com.intercept.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.intercept.domain.model.RiskLevel
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.RoomInk
import com.intercept.presentation.theme.RoomMuted
import com.intercept.presentation.theme.RoomWire
import com.intercept.presentation.theme.Wire
import com.intercept.presentation.theme.risk
import com.intercept.presentation.theme.riskOnRoom

/**
 * The risk reading, at a glance. The score is set in the machine voice because
 * it is model output; the prose around it stays in Mukta because it is written
 * for a person.
 */
@Composable
fun RiskMeter(
    score: Int,
    level: RiskLevel,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    showScore: Boolean = true,
    showLevel: Boolean = true,
) {
    val clamped = score.coerceIn(0, 100)
    val track = if (onDark) RoomWire else Wire
    val labelColor = if (onDark) RoomMuted else Muted
    val valueColor = if (onDark) RoomInk else Ink
    val accent = if (onDark) level.riskOnRoom else level.risk

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Risk", style = MaterialTheme.typography.labelMedium, color = labelColor)
            if (showScore) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        clamped.toString(),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Machine),
                        color = valueColor,
                    )
                    Text(
                        " / 100",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Machine),
                        color = labelColor,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(track)
        ) {
            if (clamped > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(clamped / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
        }
        if (showLevel) {
            Spacer(Modifier.height(12.dp))
            RiskChip(level = level, onDark = onDark)
        }
    }
}
