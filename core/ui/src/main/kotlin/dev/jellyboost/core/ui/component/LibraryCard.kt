package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface

/** The rounded square the library's glyph sits in. */
private val GlyphWellSize = 36.dp

private val GlyphWellRadius = 10.dp

/** Fill of that well — a step fainter than a glass surface, since it sits *on* one. */
private val GlyphWellFill = Color.White.copy(alpha = 0.06f)

private val GlyphSize = 18.dp

private val TileLabel =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
    )

private val TileSubtitle =
    TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

/**
 * A user library tile — the home screen's library row and the Libraries tab.
 *
 * A wide, short glass tile with a glyph rather than the 16:9 artwork card it replaced: a Jellyfin
 * library's own image is usually a collage of its first few posters, which at tile size is visual
 * noise that says less about the library than its name does (2026-refresh mocks, "Your libraries").
 *
 * @param width fixed tile width, as a row of tiles needs; [Dp.Unspecified] fills the available
 *   width instead, which is what an adaptive grid cell wants.
 * @param subtitle optional second line — the library's item count ("412 items"). Formatted by the
 *   caller, since the plural belongs to the screen's string resources. Hidden when `null`, which is
 *   what every caller passes until the counts are wired up.
 */
@Composable
fun LibraryCard(
    library: LibraryView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.LibraryTileWidth,
    subtitle: String? = null,
) {
    Row(
        modifier =
            modifier
                .cardWidth(width)
                .height(Dimens.LibraryTileHeight)
                .glassSurface(RoundedCornerShape(Dimens.CardCornerRadius))
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Box(
            modifier =
                Modifier
                    .size(GlyphWellSize)
                    .background(color = GlyphWellFill, shape = RoundedCornerShape(GlyphWellRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector =
                    when (library.collectionType) {
                        CollectionKind.TVSHOWS -> Icons.Outlined.Tv
                        else -> Icons.Outlined.Movie
                    },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(GlyphSize),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall / 2)) {
            Text(
                text = library.name,
                style = TileLabel,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TileSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "LibraryCard", showBackground = true, backgroundColor = 0xFF101010, widthDp = 280)
@Composable
private fun LibraryCardPreview() {
    JellyfinTheme {
        Column(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            LibraryCard(
                library = LibraryView(id = "lib-1", name = "Movies", collectionType = CollectionKind.MOVIES),
                onClick = {},
            )
            LibraryCard(
                library = LibraryView(id = "lib-2", name = "Shows", collectionType = CollectionKind.TVSHOWS),
                onClick = {},
                subtitle = "412 items",
            )
        }
    }
}
