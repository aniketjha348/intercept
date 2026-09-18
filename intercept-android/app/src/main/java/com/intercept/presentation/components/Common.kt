package com.intercept.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intercept.R
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.Wire

/**
 * In-button progress. Material's default indicator is 40dp and pushes the
 * button's height around the moment work starts; this keeps the row still and
 * takes the button's own content colour, so it reads on filled and outlined
 * buttons alike.
 */
@Composable
fun InlineLoader(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}

/** A dotted status light. One dot, one meaning — never a coloured card. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * The logo lockup: the mint bird on its ink tile, exactly as it appears on the
 * website. Mint is confined to this mark so it can never be read as a risk level.
 */
@Composable
fun BrandMark(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.23f))
            .background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bird),
            contentDescription = null,
            modifier = Modifier.size(size * 0.74f),
        )
    }
}

/** A quiet label that opens a section, with a hairline running to the margin. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val color = if (onDark) com.intercept.presentation.theme.RoomMuted else Muted
    val rule = if (onDark) com.intercept.presentation.theme.RoomWire else Wire
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        HorizontalDivider(Modifier.weight(1f), color = rule)
    }
}

/**
 * One thing you can do, as a row in a list rather than a button in a stack of
 * identical buttons. The icon is optional — most rows read fine without one.
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    showRule: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (showRule) HorizontalDivider(color = Wire)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
