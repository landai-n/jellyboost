package dev.jellyboost.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

// The Settings screen's row vocabulary.
//
// Built here rather than in `:core:ui` because Settings is the only screen with rows of this shape;
// a shared component would be a generalisation from one example. If a second screen ever grows a
// preference list, this file is what moves.
//
// The invariant every row upholds: the *whole* row is the touch target and carries the semantics,
// and the control it contains (`Switch`, `RadioButton`) is inert. A 48 dp strip that only responds
// on the last 52 dp of its width is the single most common accessibility defect in settings UIs,
// and `toggleable`/`selectable` on the container is what prevents it — they also give TalkBack the
// role and the on/off state for free, which a `Switch` with its own `onCheckedChange` does not when
// the label sits in a sibling composable.

/** Material's minimum touch target; every row honours it whether or not its text needs the height. */
internal val SettingsRowMinHeight: Dp = 48.dp

/**
 * A titled group of rows.
 *
 * The heading wears the shared [JellyfinTypeExtras.SectionTitle] style but keeps its primary
 * accent colour rather than going plain white like a home or library row's heading: a long
 * scrolling preference list has no artwork or layout to orient by, so
 * the accent is the one piece of wayfinding a thumb flicking past divider lines gets, and dropping it
 * would leave "Playback"/"Downloads"/"Account" reading exactly like the [SettingsChoiceGroup] labels
 * one level below them.
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = JellyfinTypeExtras.SectionTitle,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceLarge,
                        bottom = Dimens.SpaceSmall,
                    )
                    // Settings is the longest scrolling list in the app and these five words are
                    // its only wayfinding. Marked as headings they are also TalkBack's: without
                    // them there is no heading anywhere in the app to jump between, so reaching
                    // "Account" means swiping past every playback and download preference above it.
                    .semantics { heading() },
        )
        content()
    }
}

/** A row whose entire surface toggles a boolean preference. */
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
 * One option inside a [SettingsSection]'s choice group; the whole row selects it.
 *
 * [supportingText] is for a fact about the option the label cannot carry — how much room is left on
 * a volume, say. It is not a place for explanation: a group whose options need explaining wants the
 * caveat under the group, the way the download-quality picker does it.
 *
 * @param groupLabel the name of the [SettingsChoiceGroup] this row belongs to, folded into what a
 *   screen reader says. Required, not optional, because the alternative is three "Off / Show
 *   button / Auto" rows and three more of them further down, with the two words that tell them
 *   apart — "Skip intro", "Skip outro" — in a caption above that belongs to nothing. A user
 *   landing on a row (from a heading jump, from a rotation, from anywhere but a linear swipe
 *   through the whole screen) would have no way to know which preference they were about to
 *   change. The group's caption is muted in turn, so it is said once per row rather than once
 *   more on its own.
 * @param actionHint what activating this row *does*, when that is not simply "select it". Rides in
 *   the description because `Modifier.selectable` has no `onClickLabel` — see the storage picker's
 *   recovery row, where re-picking the option already in force is the way out of a missing-volume
 *   state and looks, visually, like a row that is already selected.
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
 * Everything a choice row says, in the order it says it.
 *
 * Comma-joined rather than assembled from a format string: the pieces are independently optional,
 * and the separator is a pause for a speech engine rather than copy anyone reads. `Role.RadioButton`
 * and the selected state are appended by the platform after this, and the group's "2 of 3" by the
 * enclosing `selectableGroup()`.
 *
 * A pure function so the wording is checkable without a Compose harness.
 */
internal fun choiceRowDescription(
    groupLabel: String,
    label: String,
    supportingText: String?,
    actionHint: String?,
): String = listOfNotNull(groupLabel, label, supportingText, actionHint).joinToString(", ")

/**
 * A labelled group of mutually exclusive [SettingsChoiceRow]s.
 *
 * `selectableGroup()` is what tells TalkBack the rows belong together, so it announces "2 of 3"
 * instead of reading three unrelated radio buttons.
 *
 * The label is styled as a **subsection heading** — `labelLarge` in `onSurfaceVariant` — and not
 * like the rows beneath it: styled as `bodyLarge`/`onSurface`, pixel for pixel what [RowLabel]
 * draws, "Skip intro" would read as one more tappable row sitting above three others with nothing
 * saying otherwise until you pressed it. It stays quieter than [SettingsSection]'s
 * `titleSmall`-in-primary heading, which is the level above it.
 *
 * The caption is drawn but not *spoken*: every row inside carries [label] in its own description
 * (`SettingsChoiceRow`'s `groupLabel`), which is the only association a screen reader can actually
 * rely on — a caption above a group is, semantically, a sentence next to some radio buttons.
 * Muting it here is the same trade `JellyfinTextField` makes with its own field caption: said
 * once, on the thing it names, rather than twice.
 */
@Composable
internal fun SettingsChoiceGroup(
    label: String,
    modifier: Modifier = Modifier,
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
                        bottom = Dimens.SpaceExtraSmall,
                    ).clearAndSetSemantics {},
        )
        Column(modifier = Modifier.selectableGroup(), content = content)
    }
}

/**
 * A row that only reports something — a name, a server, a storage figure.
 *
 * One node, like every other row on this screen: a caption and the fact it captions are not two
 * pieces of information, and read as two stops they would arrive as "Version" … "0.1.0-debug"
 * with a swipe in between.
 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
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
