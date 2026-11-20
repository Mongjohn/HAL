package com.evofactory.hal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

internal enum class CalmOverviewMode { Stacked, Horizontal, Scaled }

internal fun calmOverviewMode(available: Int, stacked: Int, horizontal: Int): CalmOverviewMode = when {
    stacked <= available -> CalmOverviewMode.Stacked
    horizontal <= available -> CalmOverviewMode.Horizontal
    else -> CalmOverviewMode.Scaled
}

/** Measure the real labels before rearranging them; shrinking is the last resort. */
@Composable
internal fun CalmOverview(title: String, value: String, detail: String?, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier.cycleUiColors()) { constraints ->
        val natural = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val stacked = subcompose(CalmOverviewMode.Stacked) { CalmOverviewText(title, value, detail, false) }
            .single().measure(natural)
        val inline = if (stacked.height > constraints.maxHeight) {
            subcompose(CalmOverviewMode.Horizontal) { CalmOverviewText(title, value, detail, true) }.single().measure(natural)
        } else null
        val mode = calmOverviewMode(constraints.maxHeight, stacked.height, inline?.height ?: stacked.height)
        val chosen = when (mode) {
            CalmOverviewMode.Stacked -> stacked
            CalmOverviewMode.Horizontal -> inline!!
            CalmOverviewMode.Scaled -> if (stacked.height <= inline!!.height) stacked else inline
        }
        val scale = if (mode == CalmOverviewMode.Scaled)
            (constraints.maxHeight.toFloat() / chosen.height.coerceAtLeast(1)).coerceIn(0f, 1f) else 1f
        layout(constraints.maxWidth, constraints.maxHeight) {
            chosen.placeWithLayer(((constraints.maxWidth - chosen.width * scale) / 2).toInt(),
                ((constraints.maxHeight - chosen.height * scale) / 2).toInt()) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

@Composable
private fun CalmOverviewText(title: String, value: String, detail: String?, horizontal: Boolean) {
    val colors = MaterialTheme.colorScheme
    @Composable fun TitleAndDetail() {
        if (title.isNotBlank()) Text(title, fontSize = 22.sp, fontWeight = FontWeight.Light,
            color = colors.onSurfaceVariant, overflow = TextOverflow.Clip)
        if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant,
            overflow = TextOverflow.Clip)
    }
    @Composable fun Value() {
        Text(value, fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Light,
            overflow = TextOverflow.Clip)
    }
    if (horizontal) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End) { TitleAndDetail() }
            Column(Modifier.weight(1f)) { Value() }
        }
    } else {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title.isNotBlank()) Text(title, fontSize = 22.sp, fontWeight = FontWeight.Light,
                color = colors.onSurfaceVariant, overflow = TextOverflow.Clip)
            Value()
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant,
                overflow = TextOverflow.Clip)
        }
    }
}
