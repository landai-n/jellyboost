package dev.jellyboost.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.Person
import dev.jellyboost.core.common.model.PersonKind
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * Costs no request: `JellyfinItem.people` is already populated by the detail screen's full `getItem`
 * re-fetch. Not clickable — there is no person page to open, and a card that ripples promises one.
 */
@Composable
internal fun CastRail(
    people: List<Person>,
    modifier: Modifier = Modifier,
) {
    val cast = castMembers(people)
    if (cast.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        DetailSectionTitle(text = stringResource(R.string.detail_section_cast))
        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))
        LazyRow(
            contentPadding = PaddingValues(horizontal = DetailEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            items(items = cast, key = { it.id }, contentType = { CAST_CONTENT_TYPE }) { person ->
                CastMember(person = person)
            }
        }
    }
}

/**
 * **One** merged node per person: unmerged, a twelve-strong rail is twenty-four swipes, and the role
 * floats free of whoever plays it. No click and no role — the rail is a list of facts.
 */
@Composable
private fun CastMember(
    person: Person,
    modifier: Modifier = Modifier,
) {
    val role = person.role?.takeIf { it.isNotBlank() }
    val description =
        role?.let { stringResource(R.string.detail_cast_member_as, person.name, it) } ?: person.name

    Column(
        modifier =
            modifier
                .width(CastColumnWidth)
                .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        JellyfinAsyncImage(
            url = person.primaryImageUrl,
            contentDescription = null,
            modifier = Modifier.size(Dimens.CastHeadshotSize).clip(CircleShape),
            placeholderIcon = Icons.Outlined.Person,
        )
        Text(
            text = person.name,
            style = CastNameStyle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = CAST_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        role?.let {
            Text(
                text = it,
                style = CastRoleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = CAST_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A stable **sort** by credit kind, never a filter: a documentary credited entirely to crew still
 * gets a rail. Duplicates are dropped — Jellyfin credits one person once per role.
 */
internal fun castMembers(
    people: List<Person>,
    limit: Int = CAST_LIMIT,
): List<Person> =
    people
        .distinctBy { it.id }
        .sortedBy { it.kind.billing }
        .take(limit)

/** Lower bills first; actors and guest stars share the top. */
private val PersonKind.billing: Int
    get() =
        when (this) {
            PersonKind.ACTOR, PersonKind.GUEST_STAR -> 0
            PersonKind.DIRECTOR -> 1
            PersonKind.WRITER -> 2
            PersonKind.PRODUCER -> 3
            PersonKind.OTHER -> 4
        }

internal const val CAST_LIMIT = 12

private const val CAST_CONTENT_TYPE = "cast-member"

private const val CAST_TEXT_LINES = 2

/** Wider than the 72dp photo, so two-word names get two lines. */
private val CastColumnWidth: Dp = 96.dp

private val CastNameStyle =
    TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 16.sp,
    )

private val CastRoleStyle =
    TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

@Preview(name = "CastRail", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun CastRailPreview() {
    JellyfinTheme {
        CastRail(people = previewPeople)
    }
}

private val previewPeople =
    listOf(
        Person(id = "1", name = "Evan Rachel Wood", role = "Dolores Abernathy", kind = PersonKind.ACTOR),
        Person(id = "2", name = "Thandiwe Newton", role = "Maeve Millay", kind = PersonKind.ACTOR),
        Person(id = "3", name = "Jonathan Nolan", kind = PersonKind.DIRECTOR),
        Person(id = "4", name = "Jeffrey Wright", role = "Bernard Lowe", kind = PersonKind.ACTOR),
    )
