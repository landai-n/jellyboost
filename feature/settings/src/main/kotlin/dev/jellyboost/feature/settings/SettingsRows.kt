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

// The invariant every row here upholds: the *whole* row is the touch target and carries the
// semantics, and the control it contains (`Switch`, `RadioButton`) is inert. `toggleable`/
// `selectable` on the container is also what gives TalkBack the role and state, which a `Switch`
// with its own `onCheckedChange` does not when the label sits in a sibling composable.

/** Material's minimum touch target, honoured whether or not the text needs the height. */
internal val SettingsRowMinHeight: Dp = 48.dp

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
                    // Settings is the app's longest list and has no other headings: without these, reaching
                    // "Account" means swiping past every preference above it.
                    .semantics { heading() },
        )
        content()
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
 * The caption is drawn but not *spoken*: every row carries [label] in its own description, which is
 * the only association a reader can rely on — a caption above a group is just a nearby sentence.
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
 * One node: a caption and the fact it captions are not two pieces of information, and read as two
 * stops they arrive with a swipe in between.
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
