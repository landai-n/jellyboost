package dev.jellyboost.feature.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The hub's rows, in the order it draws them. **Account is not a member**: it is reached from the
 * identity row, which draws a face rather than a glyph and sits in its own panel above these.
 *
 * Growth rule: a category splits at roughly seven rows, and Subtitles is the first thing that leaves
 * Playback when it does.
 */
enum class SettingsCategory(
    @param:StringRes val titleRes: Int,
) {
    PLAYBACK(R.string.settings_section_playback),
    DOWNLOADS(R.string.settings_section_downloads),
    APPEARANCE(R.string.settings_section_appearance),

    NETWORK(R.string.settings_section_network),
    ABOUT(R.string.settings_section_about),
}

/**
 * What a two-pane window can have open, which is every category *plus* Account — the identity row
 * selects a pane like any other rail row rather than pushing a screen out from under the rail.
 *
 * An enum because it is `rememberSaveable` state on the Settings destination: at ≥840dp the open
 * category is not a navigation destination, so a rotation is the only thing that could lose it.
 */
internal enum class SettingsPane {
    ACCOUNT,
    PLAYBACK,
    DOWNLOADS,
    APPEARANCE,
    NETWORK,
    ABOUT,
    ;

    companion object {
        fun of(category: SettingsCategory): SettingsPane =
            when (category) {
                SettingsCategory.PLAYBACK -> PLAYBACK
                SettingsCategory.DOWNLOADS -> DOWNLOADS
                SettingsCategory.APPEARANCE -> APPEARANCE
                SettingsCategory.NETWORK -> NETWORK
                SettingsCategory.ABOUT -> ABOUT
            }
    }
}

/**
 * A category is called the same thing on its hub row and on its own page, so this agrees with
 * [SettingsCategory.titleRes] everywhere the two overlap. Account is the one pane with no hub row of
 * its own — the identity row opens it — so it needs a title from somewhere.
 */
@StringRes
internal fun SettingsPane.titleRes(): Int =
    when (this) {
        SettingsPane.ACCOUNT -> R.string.settings_section_account
        SettingsPane.PLAYBACK -> R.string.settings_section_playback
        SettingsPane.DOWNLOADS -> R.string.settings_section_downloads
        SettingsPane.APPEARANCE -> R.string.settings_section_appearance
        SettingsPane.NETWORK -> R.string.settings_section_network
        SettingsPane.ABOUT -> R.string.settings_section_about
    }

/**
 * Whether the platform has a wallpaper palette to take colours from. The dynamic-colour row is
 * **absent, not disabled** below API 31 — a switch with nothing behind it is worse than no switch —
 * and the hub summary reads the same fact so the two cannot disagree.
 */
internal val dynamicColorAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal fun SettingsCategory.icon(): ImageVector =
    when (this) {
        SettingsCategory.PLAYBACK -> Icons.Filled.PlayArrow
        SettingsCategory.DOWNLOADS -> Icons.Filled.Download
        SettingsCategory.APPEARANCE -> Icons.Outlined.Contrast
        SettingsCategory.NETWORK -> Icons.Outlined.Cloud
        SettingsCategory.ABOUT -> Icons.Outlined.Info
    }

/**
 * The same 840dp cutoff `ServerSetupScreen`'s `AuthTwoPaneMinWidth` uses, so the app has one width
 * at which a screen becomes two panes rather than one per feature.
 */
internal val SettingsTwoPaneMinWidth: Dp = 840.dp

/** The mocks' rail: wide enough for a 36dp glyph, a title and a state summary that does not wrap. */
internal val SettingsRailWidth: Dp = 340.dp

/**
 * Width only, deliberately: unlike Home's wide hero — which needs height for a 104dp copy inset —
 * a rail beside a scrolling pane is legible in any window 840dp across, and adding a landscape
 * condition would drop an 840dp portrait tablet back to the phone push flow for no reason a user
 * could see.
 */
internal fun isTwoPaneSettings(maxWidth: Dp): Boolean = maxWidth >= SettingsTwoPaneMinWidth
