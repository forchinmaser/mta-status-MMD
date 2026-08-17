package com.example.transitkompakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.R
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/**
 * Section label. Always full ink: MMD's onSurface is pure black, and grey is
 * reserved for genuinely disabled controls (where MMD applies its own alpha).
 * Single line and fixed height, because it is the top row on every screen and
 * everything below it must start at the same y.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = TextMMD(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.fillMaxWidth().height(24.dp)
)

@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)?, onHome: (() -> Unit)?) = TopAppBarMMD(
    title = {
        TextMMD(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    },
    navigationIcon = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.chevron_filled_left), contentDescription = "Back")
            }
        }
    },
    actions = {
        if (onHome != null) {
            // MMD ships no home icon, and borrowing a scrollbar chevron would read as
            // a scroll hint — so the action is a plain MMD text label.
            TextButton(onClick = onHome) {
                TextMMD(
                    text = "Home",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
)

/**
 * The one full-width row used by boroughs, home mode buttons and retry rows.
 * Fixed height, so a row on one screen occupies the pixels a row on the next
 * screen occupies.
 */
@Composable
fun ListRowButton(label: String, sub: String? = null, onClick: () -> Unit) = OutlinedButtonMMD(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(Design.RowHeight)
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextMMD(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sub != null) TextMMD(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Icon(painterResource(R.drawable.chevron_filled_right), contentDescription = null)
    }
}

private fun chipShape(circle: Boolean) = if (circle) CircleShape else RoundedCornerShape(8.dp)

private fun chipModifier(code: String, circle: Boolean): Modifier = when {
    circle -> Modifier.size(Design.ChipHeight)
    Design.isWideCode(code) -> Modifier.width(Design.ChipWidthWide).height(Design.ChipHeight)
    else -> Modifier.width(Design.ChipWidth).height(Design.ChipHeight)
}

/** Subway bullet (circle) or bus route plate (rounded), in one of two fixed widths. */
@Composable
fun LineChip(code: String, circle: Boolean, onClick: () -> Unit) =
    OutlinedButtonMMD(
        onClick = onClick,
        shape = chipShape(circle),
        modifier = chipModifier(code, circle)
    ) {
        Box(contentAlignment = Alignment.Center) { ChipLabel(code, circle) }
    }

/**
 * Filled variant for the current route on the detail header — same shape, size
 * and position as the chip that was tapped on the previous screen.
 */
@Composable
fun LineChipFilled(code: String, circle: Boolean) =
    ButtonMMD(
        onClick = {},
        shape = chipShape(circle),
        modifier = chipModifier(code, circle)
    ) {
        ChipLabel(code, circle)
    }

@Composable
private fun ChipLabel(code: String, circle: Boolean) = TextMMD(
    text = code,
    style = if (!circle && Design.isWideCode(code)) MaterialTheme.typography.labelSmall
    else MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    maxLines = 1
)

@Composable
fun SourceLine(text: String) = TextMMD(text, style = MaterialTheme.typography.bodySmall)
