package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface

private val GlyphWellSize = 36.dp

private val GlyphWellRadius = 10.dp

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

/** Shared with the home screen's quick-access chips: two copies drift into two glyphs per kind. */
fun libraryIcon(kind: CollectionKind): ImageVector =
    when (kind) {
        CollectionKind.TVSHOWS -> Icons.Outlined.Tv
        CollectionKind.MUSIC -> Icons.Outlined.LibraryMusic
        else -> Icons.Outlined.Movie
    }

/** Lazy-list `contentType`: without one a lazy layout reuses no scrolled-off node. */
const val LIBRARY_CARD_CONTENT_TYPE = "card-library"

/**
 * @param width [Dp.Unspecified] fills the parent, which is what an adaptive grid cell wants.
 * @param subtitle formatted by the caller, since the plural belongs to its string resources.
 */
@Composable
fun LibraryCard(
    library: LibraryView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.LibraryTileWidth,
    subtitle: String? = null,
) {
    // One node, one sentence: "Library, Shows, 412 items" — the glyph is what says the kind, and
    // it has no label of its own.
    val description =
        MediaCardFacts(
            title = library.name,
            typeLabel = stringResource(R.string.media_card_type_library),
            subtitle = subtitle,
        ).describe()
    Row(
        modifier =
            modifier
                .cardWidth(width)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                }
                // A minimum, not a fixed height: the column outgrows 64dp around font scale 1.7 and
                // a hard `height` clipped the item count to nothing.
                .heightIn(min = Dimens.LibraryTileHeight)
                .glassSurface(RoundedCornerShape(Dimens.CardCornerRadius))
                .clickable(role = Role.Button, onClick = onClick)
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
                imageVector = libraryIcon(library.collectionType),
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
