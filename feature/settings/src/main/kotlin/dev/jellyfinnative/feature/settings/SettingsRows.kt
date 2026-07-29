package dev.jellyfinnative.feature.settings

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.ui.theme.Dimens
import java.util.Locale

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

/** A titled group of rows. */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.SpaceLarge,
                    bottom = Dimens.SpaceSmall,
                ),
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

/** One option inside a [SettingsSection]'s choice group; the whole row selects it. */
@Composable
internal fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceExtraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * A labelled group of mutually exclusive [SettingsChoiceRow]s.
 *
 * `selectableGroup()` is what tells TalkBack the rows belong together, so it announces "2 of 3"
 * instead of reading three unrelated radio buttons.
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier.padding(
                    horizontal = Dimens.ScreenPadding,
                    vertical = Dimens.SpaceSmall,
                ),
        )
        Column(modifier = Modifier.selectableGroup(), content = content)
    }
}

/** A row that only reports something — a name, a server, a storage figure. */
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

/**
 * Human-readable bytes, in the same powers-of-1000 SI form the Downloads screen uses.
 *
 * Duplicated rather than shared: `:feature:downloads` keeps its copy `internal`, features never
 * depend on each other (docs/PLAN.md, "Project skeleton"), and promoting eight lines to `:core:ui`
 * to serve two call sites would put a formatting helper in the design system.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < BYTE_UNIT) return "$bytes B"
    var value = bytes.toDouble()
    var index = -1
    while (value >= BYTE_UNIT && index < BYTE_UNITS.lastIndex) {
        value /= BYTE_UNIT
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, BYTE_UNITS[index])
}

private const val BYTE_UNIT = 1000.0
private val BYTE_UNITS = listOf("kB", "MB", "GB", "TB")
