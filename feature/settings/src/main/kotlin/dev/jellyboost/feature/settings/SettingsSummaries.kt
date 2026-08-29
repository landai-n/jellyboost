package dev.jellyboost.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode

/**
 * A hub row's summary is the category's **current state**, never a list of what it contains — that
 * sentence is the whole payment for the extra tap.
 *
 * The parts are resource *ids*, not text: a summary derived into a `String` would be resolved in
 * whatever language happened to be current when the state was built, and `MissingTranslation`
 * cannot see a Kotlin literal (see `UiText`'s KDoc, and `docs/features/localization.md`). Each part
 * is resolved by [summaryText] at draw time instead, which is also what makes every function below
 * a pure one a unit test can read.
 */
@Immutable
internal sealed interface SummaryPart {
    /** A preference's own value, drawn as its label says it. */
    @Immutable
    data class Label(
        @StringRes val labelRes: Int,
    ) : SummaryPart

    /**
     * A switch, drawn as its label plus its state. The frame is a format string rather than a
     * concatenation so a language can put the state word first; `%1$s` is [labelRes] resolved in
     * the same locale, in the same frame.
     */
    @Immutable
    data class Toggle(
        @StringRes val labelRes: Int,
        val on: Boolean,
    ) : SummaryPart

    /** How much of the device the downloads have taken. */
    @Immutable
    data class Storage(
        val usedBytes: Long,
    ) : SummaryPart

    /** Text that arrives already worded from outside the string table — a version name. */
    @Immutable
    data class Raw(
        val value: String,
    ) : SummaryPart
}

/**
 * The mocks' separator. Punctuation rather than a resource: it is a speech pause between two
 * independently-translated clauses, and no language reorders a middle dot.
 */
internal const val SUMMARY_SEPARATOR = " · "

/**
 * Intro rather than outro, and the skip choice before picture-in-picture: one row cannot hold four
 * preferences, so it leads with the two a user is most likely to have changed.
 */
internal fun playbackSummary(
    introSkipMode: SegmentSkipMode,
    pipOnLeave: Boolean,
): List<SummaryPart> =
    listOf(
        SummaryPart.Label(introSkipMode.labelRes()),
        SummaryPart.Toggle(R.string.settings_pip, pipOnLeave),
    )

/**
 * The two facts the Downloads page draws at its top, in the order it draws them.
 *
 * `Wi-Fi only` carries its state rather than appearing bare: a bare label reads as "this is on"
 * whichever way the switch is set, which is the one thing a state summary must not do.
 */
internal fun downloadsSummary(
    downloadOverWifiOnly: Boolean,
    usedBytes: Long,
): List<SummaryPart> =
    listOf(
        SummaryPart.Toggle(R.string.settings_wifi_only, downloadOverWifiOnly),
        SummaryPart.Storage(usedBytes),
    )

/**
 * @param dynamicColorAvailable false below API 31, where the row itself is **absent, not disabled**
 *   — so the summary must not name a preference the page will not show either.
 */
internal fun appearanceSummary(
    themeMode: ThemeMode,
    dynamicColorEnabled: Boolean,
    dynamicColorAvailable: Boolean,
): List<SummaryPart> =
    listOfNotNull(
        SummaryPart.Label(themeMode.labelRes()),
        SummaryPart
            .Toggle(R.string.settings_dynamic_color, dynamicColorEnabled)
            .takeIf { dynamicColorAvailable },
    )

internal fun networkSummary(forceOffline: Boolean): List<SummaryPart> =
    listOf(SummaryPart.Toggle(R.string.settings_offline_mode, forceOffline))

/** What the binary is and what may be done with it — the two facts GPL-3.0 §4 makes load-bearing. */
internal fun aboutSummary(appVersion: String): List<SummaryPart> =
    listOf(
        SummaryPart.Raw(appVersion),
        SummaryPart.Label(R.string.settings_licence_short),
    )

/**
 * Every hub summary, derived once. The hub takes this rather than the whole [SettingsUiState]: a
 * state parameter would redraw all six rows on a storage tick that only one of them draws, which is
 * exactly the whole-UiState parameter strong skipping cannot help with.
 */
@Immutable
internal data class HubSummaries(
    val playback: List<SummaryPart>,
    val downloads: List<SummaryPart>,
    val appearance: List<SummaryPart>,
    val network: List<SummaryPart>,
    val about: List<SummaryPart>,
) {
    fun of(category: SettingsCategory): List<SummaryPart> =
        when (category) {
            SettingsCategory.PLAYBACK -> playback
            SettingsCategory.DOWNLOADS -> downloads
            SettingsCategory.APPEARANCE -> appearance
            SettingsCategory.NETWORK -> network
            SettingsCategory.ABOUT -> about
        }
}

/**
 * @param dynamicColorAvailable the *same* fact the Appearance page tests before drawing the
 *   wallpaper-colour row. Threaded through rather than read here so the row and the summary cannot
 *   drift into one naming a preference the other refuses to show.
 */
internal fun hubSummaries(
    state: SettingsUiState,
    appVersion: String,
    dynamicColorAvailable: Boolean,
): HubSummaries =
    HubSummaries(
        playback = playbackSummary(state.introSkipMode, state.pipOnLeave),
        downloads = downloadsSummary(state.downloadOverWifiOnly, state.storage.usedBytes),
        appearance =
            appearanceSummary(state.themeMode, state.dynamicColorEnabled, dynamicColorAvailable),
        network = networkSummary(state.forceOffline),
        about = aboutSummary(appVersion),
    )

/**
 * Built with a loop rather than `joinToString`: its transform is not a `@Composable` lambda, and
 * every part below is resolved from a resource.
 */
@Composable
@ReadOnlyComposable
internal fun List<SummaryPart>.summaryText(): String {
    val joined = StringBuilder()
    forEachIndexed { index, part ->
        if (index > 0) joined.append(SUMMARY_SEPARATOR)
        joined.append(part.text())
    }
    return joined.toString()
}

@Composable
@ReadOnlyComposable
private fun SummaryPart.text(): String =
    when (this) {
        is SummaryPart.Label -> stringResource(labelRes)
        is SummaryPart.Toggle ->
            stringResource(
                if (on) R.string.settings_hub_state_on else R.string.settings_hub_state_off,
                stringResource(labelRes),
            )

        is SummaryPart.Storage ->
            stringResource(R.string.settings_hub_storage_used, formatBytes(usedBytes))

        is SummaryPart.Raw -> stringResource(R.string.settings_hub_version, value)
    }

internal fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

internal fun SegmentSkipMode.labelRes(): Int =
    when (this) {
        SegmentSkipMode.OFF -> R.string.settings_skip_mode_off
        SegmentSkipMode.SHOW_BUTTON -> R.string.settings_skip_mode_show_button
        SegmentSkipMode.AUTO_SKIP -> R.string.settings_skip_mode_auto
    }
