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
 * The cast rail: circular headshots under a section title, the row the refresh adds to the detail
 * screen (spec section 4c).
 *
 * The people were already fetched — `JellyfinItem.people` is populated by the detail screen's full
 * `getItem` re-fetch, and `creditLine` in the header has been naming four of them all along — so
 * this row costs no request. It draws nothing at all when the server credited nobody, rather than
 * leaving an empty shelf (the rule `MediaRow` follows).
 *
 * Not clickable: a person page is not in v1 scope (docs/PLAN.md, "Screens"), and a card that
 * ripples but goes nowhere promises one.
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
 * One face in the rail — and **one** node for a screen reader.
 *
 * Unmerged it was two stops per person, a name and then a role floating free of whoever plays it
 * ("Dolores Abernathy" is not a fact on its own), so a twelve-strong rail was twenty-four swipes to
 * get past (accessibility audit 2026-08-05, A11Y-21). Merged, each person is one stop that says
 * both, in the words the credit would be written in. The column is not clickable and gains no role:
 * a person page is not in v1 scope, and the rail is a list of facts.
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
 * Who the rail shows, in what order: the people the user came to see first, then everyone else in
 * the order the server billed them.
 *
 * A stable sort by credit kind rather than a filter, so a film whose server record happens to list
 * its director before its cast still leads with the cast — and a documentary credited entirely to
 * crew still has a rail instead of nothing. Duplicates are dropped because Jellyfin credits one
 * person once per role, and the same face twice reads as a bug.
 */
internal fun castMembers(
    people: List<Person>,
    limit: Int = CAST_LIMIT,
): List<Person> =
    people
        .distinctBy { it.id }
        .sortedBy { it.kind.billing }
        .take(limit)

/** Lower bills first. Actors and guest stars share the top, because a rail is a row of faces. */
private val PersonKind.billing: Int
    get() =
        when (this) {
            PersonKind.ACTOR, PersonKind.GUEST_STAR -> 0
            PersonKind.DIRECTOR -> 1
            PersonKind.WRITER -> 2
            PersonKind.PRODUCER -> 3
            PersonKind.OTHER -> 4
        }

/** How many faces the rail shows before it stops — a full cast list is a screen of its own. */
internal const val CAST_LIMIT = 12

private const val CAST_CONTENT_TYPE = "cast-member"

private const val CAST_TEXT_LINES = 2

/** Width of one headshot column: wider than the 72dp photo, so two-word names get two lines. */
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
