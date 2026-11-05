package io.github.kahme247.ompweb.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.domain.providerMarker
import io.github.kahme247.ompweb.ui.theme.LocalOmpColors
import io.github.kahme247.ompweb.ui.theme.OmpColors
import io.github.kahme247.ompweb.ui.theme.OmpDropdownMenu

/** How strongly the provider tint fills and outlines the monogram square. */
internal const val PROVIDER_BADGE_BACKGROUND_ALPHA = 0.16f
internal const val PROVIDER_BADGE_BORDER_ALPHA = 0.5f

/**
 * The theme tokens a provider tint may borrow. The tint only fills the square
 * and its border; the monogram stays [OmpColors.text], so it keeps the
 * theme's readable foreground color on every palette.
 */
internal enum class ProviderTone { Accent, SecondaryAccent, Success, Warning, Modified, Renamed }

/**
 * One stable tint per provider. The known providers keep a fixed token and
 * every other id hashes into the same short list, so an alias (`-a`, `-b`)
 * never changes the color of its provider.
 */
internal fun providerTone(baseId: String): ProviderTone {
    if (baseId.isEmpty()) return ProviderTone.Accent
    PROVIDER_TONES[baseId]?.let { return it }
    val slot = (baseId.hashCode().toLong() and 0xFFFFFFFFL) % ProviderTone.entries.size
    return ProviderTone.entries[slot.toInt()]
}

private val PROVIDER_TONES: Map<String, ProviderTone> = mapOf(
    "commandcode" to ProviderTone.Accent,
    "openai" to ProviderTone.Success,
    "openai-codex" to ProviderTone.Success,
    "anthropic" to ProviderTone.Modified,
    "google" to ProviderTone.Renamed,
    "gemini" to ProviderTone.Renamed,
    "xai" to ProviderTone.SecondaryAccent,
    "deepseek" to ProviderTone.Warning,
    "openrouter" to ProviderTone.SecondaryAccent,
    "mistral" to ProviderTone.Modified,
)

internal fun ProviderTone.color(colors: OmpColors): Color = when (this) {
    ProviderTone.Accent -> colors.accent
    ProviderTone.SecondaryAccent -> colors.secondaryAccent ?: colors.statusRenamed
    ProviderTone.Success -> colors.statusSuccess
    ProviderTone.Warning -> colors.statusWarning
    ProviderTone.Modified -> colors.statusModified
    ProviderTone.Renamed -> colors.statusRenamed
}

/**
 * The compact provider marker for usage and account rows: two letters in a
 * tinted square, the alias letter when omp's id carries one, and the full
 * provider id in the accessibility label and in the menu behind a tap or long
 * press.
 *
 * The square grows with the font scale (a fixed 16 dp square would clip the
 * monogram at 200 %) and never shrinks, so narrow rows ellipsize the e-mail
 * instead of the badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProviderMarkerBadge(
    provider: String,
    modifier: Modifier = Modifier,
) {
    val marker = remember(provider) { providerMarker(provider) }
    val colors = LocalOmpColors.current
    val tone = providerTone(marker.baseId).color(colors)
    val shape = RoundedCornerShape(4.dp)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val badgeSize = 18.dp * fontScale
    var open by remember(provider) { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .semantics(mergeDescendants = true) {
                    contentDescription = marker.label
                }
                .combinedClickable(
                    onClick = { open = true },
                    onLongClick = { open = true },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(badgeSize)
                    .widthIn(min = badgeSize)
                    .background(tone.copy(alpha = PROVIDER_BADGE_BACKGROUND_ALPHA), shape)
                    .border(1.dp, tone.copy(alpha = PROVIDER_BADGE_BORDER_ALPHA), shape)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = marker.monogram,
                    color = colors.text,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
            marker.alias?.let { alias ->
                Text(
                    text = alias,
                    color = colors.text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        OmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = stringResource(R.string.provider_usage_badge_provider, marker.label),
                color = colors.text,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
