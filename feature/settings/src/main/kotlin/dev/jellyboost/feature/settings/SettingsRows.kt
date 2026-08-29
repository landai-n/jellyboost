package dev.jellyboost.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.mSurface
import dev.jellyboost.core.ui.theme.pageInk

// The invariant every row here upholds: the *whole* row is the touch target and carries the
// semantics, and the control it contains (`Switch`, `RadioButton`) is inert. `toggleable`/
// `selectable` on the container is also what gives TalkBack the role and state, which a `Switch`
// with its own `onCheckedChange` does not when the label sits in a sibling composable.

/** Material's minimum touch target, honoured whether or not the text needs the height. */
internal val SettingsRowMinHeight: Dp = 48.dp

/** The 36dp glass circle that identifies a category on the hub; never drawn inside a category. */
private val CategoryIconSize: Dp = 36.dp

private val CategoryGlyphSize: Dp = 18.dp

/** The identity row's face, a step larger than a category glyph because it is a person, not a topic. */
private val IdentityAvatarSize: Dp = 44.dp

/** Between a row's leading circle and its text, and between its text and a trailing chevron. */
private val RowGap: Dp = 14.dp

// Same three values `:feature:downloads`' UsageBar draws; the two meters measure the same quantity.
private val MeterHeight: Dp = 6.dp

private val MeterRadius: Dp = 3.dp

private const val METER_TRACK_ALPHA = 0.12f

/**
 * A category page's section heading: an eyebrow rather than [JellyfinTypeExtras.SectionTitle],
 * because a pushed page already carries a 28sp title and a 17sp primary heading under it competes
 * with it.
 *
 * Uppercased in the **UI locale** and spoken sentence-case, for the reason `KindHeader` states —
 * a Turkish dotted I is not what the reader should hear, and `heading()` is what lets TalkBack jump
 * between sections instead of swiping every preference on the page.
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Text(
            text = title.uppercase(locale),
            style = JellyfinTypeExtras.Eyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = Dimens.SpaceExtraSmall)
                    .semantics {
                        heading()
                        contentDescription = title
                    },
        )
        SettingsPanel(content = content)
    }
}

/**
 * The rounded surface a group of rows sits on. Separators live **inside** a panel and never between
 * two of them: the gap is what says one group ended and another began.
 */
@Composable
internal fun SettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .mSurface(
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    radius = Dimens.PanelRadius,
                ),
        content = content,
    )
}

/** The hairline between two rows of the same panel. */
@Composable
internal fun SettingsRowSeparator(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = GlassDefaults.HairlineWidth,
        color = GlassDefaults.PanelHairline,
    )
}

/**
 * Hub only. [summary] is the category's **current state** — that sentence is what pays for the extra
 * tap, and a table of contents would not.
 *
 * The chevron is decorative: `clickable` merges the row into one node, so the title, the summary and
 * the Button role already say what a tap does.
 */
@Composable
internal fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceMedium),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(icon = icon)
        CategoryText(title = title, summary = summary, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The hub's Account row. It draws a face rather than a glyph because it names a *person*, and its
 * summary is the server that person is signed in to.
 */
@Composable
internal fun SettingsIdentityRow(
    name: String,
    server: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceMedium),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(IdentityAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Decorative: the name itself is written beside it inside the same merged row.
                text = name.take(1).uppercase(locale),
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        CategoryText(title = name, summary = server, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Flat [GlassDefaults.Fill], never `Modifier.glassSurface`, for `QuickAccessChip`'s reason: a real
 * blur samples the haze source, which is the panel this circle is drawn *on*.
 */
@Composable
private fun CategoryIcon(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .size(CategoryIconSize)
                .background(color = GlassDefaults.Fill, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CategoryGlyphSize),
        )
    }
}

@Composable
private fun CategoryText(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        // Inert: the row above owns the click, so a tap anywhere lands exactly once.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * [supportingText] is for a fact the label cannot carry — how much room is left on a volume, say —
 * never for explanation, which belongs under the group.
 *
 * @param groupLabel required, not optional: two groups draw the same three option names, and a user
 *   landing on a row from a heading jump would otherwise have no way to know which preference they
 *   were about to change. The group caption is muted in turn, so it is said once per row.
 * @param actionHint what activating the row *does*, when that is not simply "select it". Rides in
 *   the description because `Modifier.selectable` has no `onClickLabel`.
 */
@Composable
internal fun SettingsChoiceRow(
    groupLabel: String,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionHint: String? = null,
) {
    val description = choiceRowDescription(groupLabel, label, supportingText, actionHint)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .semantics { contentDescription = description }
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceExtraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * Comma-joined rather than a format string: the pieces are independently optional and the separator
 * is a speech pause. The role, the selected state and the group's "2 of 3" are appended after this.
 */
internal fun choiceRowDescription(
    groupLabel: String,
    label: String,
    supportingText: String?,
    actionHint: String?,
): String = listOfNotNull(groupLabel, label, supportingText, actionHint).joinToString(", ")

/**
 * `selectableGroup()` is what tells TalkBack the rows belong together, so it announces "2 of 3".
 *
 * [label] is drawn but not *spoken*: every row carries it in its own description already, which is the
 * only association a reader can rely on — a caption above a group is just a nearby sentence — so
 * leaving it audible would say it twice per row.
 *
 * [supportingText] is the opposite case and stays audible: no row repeats it, so silencing it is the
 * one way to lose it entirely. It gets one traversal stop of its own, ahead of the rows, which is why
 * it is for a caveat that governs the whole group and never for a per-row fact — that belongs in
 * [SettingsChoiceRow]'s own `supportingText`, inside the row's description.
 */
@Composable
internal fun SettingsChoiceGroup(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceMedium,
                        bottom = if (supportingText == null) Dimens.SpaceExtraSmall else 0.dp,
                    ).clearAndSetSemantics {},
        )
        supportingText?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(
                            start = Dimens.ScreenPadding,
                            end = Dimens.ScreenPadding,
                            bottom = Dimens.SpaceExtraSmall,
                        ),
            )
        }
        Column(modifier = Modifier.selectableGroup(), content = content)
    }
}

/**
 * The trailing glyph is decorative: `clickable` merges the row into one node, and the label plus the
 * button role already say what a tap does.
 */
@Composable
internal fun SettingsActionRow(
    label: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label = label, supportingText = supportingText, modifier = Modifier.weight(1f))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One node: a caption and the fact it captions are not two pieces of information, and read as two
 * stops they arrive with a swipe in between.
 *
 * @param usedFraction draws a meter under [value]. It restates the figures [value] already gives in
 *   words, so it is a picture of this node's text rather than a datum of its own.
 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    usedFraction: Float? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (usedFraction != null) {
            InfoRowMeter(fraction = usedFraction)
        }
    }
}

/**
 * Deliberately carries **no** `progressBarRangeInfo`, unlike `:feature:downloads`' `UsageBar`, and
 * the difference is the text beside each. That one is drawn loose, three to a wide layout, with no
 * sentence saying which volume it measures, so an explicit range is the only thing that makes it
 * speak at all. This one sits inside a merged row whose own text already says "12.3 GB used · 41.0
 * GB free on this device"; a progress node here would follow that sentence with a bare "23 percent"
 * of nothing nameable. `MediaCardArtwork.InsetProgressBar` is the same call made the same way.
 *
 * Metrics and track ink match `UsageBar` exactly — two meters of the same quantity in one app should
 * not be two different objects.
 */
@Composable
private fun InfoRowMeter(fraction: Float) {
    val shape = RoundedCornerShape(MeterRadius)
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier =
            Modifier
                .clearAndSetSemantics {}
                .padding(top = Dimens.SpaceSmall)
                .fillMaxWidth()
                .height(MeterHeight)
                .clip(shape)
                .background(pageInk(darkAlpha = METER_TRACK_ALPHA)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(color = MaterialTheme.colorScheme.primary, shape = shape),
        )
    }
}

@Composable
private fun RowLabel(
    label: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(end = Dimens.SpaceMedium)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
