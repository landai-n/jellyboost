package dev.jellyboost.feature.settings

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.data.downloads.model.StorageUsage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SettingsSummariesTest {
    @Test
    @DisplayName("Playback leads with the intro-skip choice, then picture-in-picture's state")
    fun playbackSummaryLeadsWithSkipIntro() {
        playbackSummary(introSkipMode = SegmentSkipMode.AUTO_SKIP, pipOnLeave = true) shouldBe
            listOf(
                SummaryPart.Label(R.string.settings_skip_mode_auto),
                SummaryPart.Toggle(R.string.settings_pip, on = true),
            )
    }

    @Test
    @DisplayName("Playback's picture-in-picture part follows the switch both ways")
    fun playbackSummaryFollowsThePipSwitch() {
        playbackSummary(SegmentSkipMode.OFF, pipOnLeave = false) shouldBe
            listOf(
                SummaryPart.Label(R.string.settings_skip_mode_off),
                SummaryPart.Toggle(R.string.settings_pip, on = false),
            )
    }

    @Test
    @DisplayName("every skip mode has a label of its own, so no two read alike")
    fun everySkipModeHasItsOwnLabel() {
        SegmentSkipMode.entries
            .map { it.labelRes() }
            .distinct()
            .size shouldBe
            SegmentSkipMode.entries.size
    }

    @Test
    @DisplayName("Downloads carries the Wi-Fi switch's state, never a bare label")
    fun downloadsSummaryCarriesTheWifiState() {
        downloadsSummary(downloadOverWifiOnly = true, usedBytes = 12_300_000_000L) shouldBe
            listOf(
                SummaryPart.Toggle(R.string.settings_wifi_only, on = true),
                SummaryPart.Storage(12_300_000_000L),
            )
        downloadsSummary(downloadOverWifiOnly = false, usedBytes = 0L) shouldBe
            listOf(
                SummaryPart.Toggle(R.string.settings_wifi_only, on = false),
                SummaryPart.Storage(0L),
            )
    }

    @Test
    @DisplayName("Appearance names the theme and, where there is one, the wallpaper palette")
    fun appearanceSummaryNamesBoth() {
        appearanceSummary(
            themeMode = ThemeMode.SYSTEM,
            dynamicColorEnabled = true,
            dynamicColorAvailable = true,
        ) shouldBe
            listOf(
                SummaryPart.Label(R.string.settings_theme_system),
                SummaryPart.Toggle(R.string.settings_dynamic_color, on = true),
            )
    }

    @Test
    @DisplayName("below API 31 the summary drops the row the page will not draw either")
    fun appearanceSummaryDropsDynamicColourWhenTheRowIsAbsent() {
        appearanceSummary(
            themeMode = ThemeMode.DARK,
            // Even set, because the preference survives a downgrade and the page still hides its row.
            dynamicColorEnabled = true,
            dynamicColorAvailable = false,
        ) shouldBe listOf(SummaryPart.Label(R.string.settings_theme_dark))
    }

    @Test
    @DisplayName("every theme mode has a label of its own")
    fun everyThemeModeHasItsOwnLabel() {
        ThemeMode.entries
            .map { it.labelRes() }
            .distinct()
            .size shouldBe ThemeMode.entries.size
    }

    @Test
    @DisplayName("Network says offline mode's state either way — 'off' is the fact, not the absence of one")
    fun networkSummaryStatesTheSwitchBothWays() {
        networkSummary(forceOffline = false) shouldBe
            listOf(SummaryPart.Toggle(R.string.settings_offline_mode, on = false))
        networkSummary(forceOffline = true) shouldBe
            listOf(SummaryPart.Toggle(R.string.settings_offline_mode, on = true))
    }

    @Test
    @DisplayName("About carries the two facts GPL-3.0 makes load-bearing: what this is, and its licence")
    fun aboutSummaryCarriesVersionAndLicence() {
        aboutSummary(appVersion = "0.1.0") shouldBe
            listOf(
                SummaryPart.Raw("0.1.0"),
                SummaryPart.Label(R.string.settings_licence_short),
            )
    }

    @Test
    @DisplayName("every category on the hub has a summary; none can fall through to an empty row")
    fun everyCategoryResolvesToANonEmptySummary() {
        val summaries = hubSummaries(FULL_STATE, appVersion = "0.1.0", dynamicColorAvailable = true)

        SettingsCategory.entries.forEach { category ->
            summaries.of(category).isEmpty() shouldBe false
        }
    }

    @Test
    @DisplayName("the bundle routes each category to the summary derived for it")
    fun theBundleRoutesEachCategoryToItsOwnSummary() {
        val summaries = hubSummaries(FULL_STATE, appVersion = "0.1.0", dynamicColorAvailable = true)

        summaries.of(SettingsCategory.PLAYBACK) shouldBe
            playbackSummary(FULL_STATE.introSkipMode, FULL_STATE.pipOnLeave)
        summaries.of(SettingsCategory.DOWNLOADS) shouldBe
            downloadsSummary(FULL_STATE.downloadOverWifiOnly, FULL_STATE.storage.usedBytes)
        summaries.of(SettingsCategory.APPEARANCE) shouldBe
            appearanceSummary(FULL_STATE.themeMode, FULL_STATE.dynamicColorEnabled, true)
        summaries.of(SettingsCategory.NETWORK) shouldBe networkSummary(FULL_STATE.forceOffline)
        summaries.of(SettingsCategory.ABOUT) shouldBe aboutSummary("0.1.0")
    }

    @Test
    @DisplayName("the hub's Appearance summary hides the wallpaper row on the same fact the page does")
    fun theBundleHonoursTheDynamicColourAvailabilityItIsGiven() {
        val without =
            hubSummaries(FULL_STATE, appVersion = "0.1.0", dynamicColorAvailable = false)

        without.of(SettingsCategory.APPEARANCE) shouldBe
            listOf(SummaryPart.Label(R.string.settings_theme_system))
    }

    @Test
    @DisplayName("every category has a title of its own, and Network is not the page's Connectivity")
    fun everyCategoryHasItsOwnTitle() {
        SettingsCategory.entries
            .map { it.titleRes }
            .distinct()
            .size shouldBe
            SettingsCategory.entries.size
        SettingsCategory.NETWORK.titleRes shouldBe R.string.settings_section_network
    }

    @Test
    @DisplayName("every pane has a title, including the Account pane the category list has no member for")
    fun everyPaneHasATitle() {
        SettingsPane.entries
            .map { it.titleRes() }
            .distinct()
            .size shouldBe
            SettingsPane.entries.size
        SettingsPane.ACCOUNT.titleRes() shouldBe R.string.settings_section_account
    }

    @Test
    @DisplayName("every category maps to a pane, and the panes are the categories plus Account")
    fun everyCategoryMapsToAPane() {
        val fromCategories = SettingsCategory.entries.map { SettingsPane.of(it) }

        fromCategories.distinct().size shouldBe SettingsCategory.entries.size
        (SettingsPane.entries - fromCategories.toSet()) shouldBe listOf(SettingsPane.ACCOUNT)
    }

    private companion object {
        val FULL_STATE =
            SettingsUiState(
                introSkipMode = SegmentSkipMode.AUTO_SKIP,
                outroSkipMode = SegmentSkipMode.SHOW_BUTTON,
                pipOnLeave = true,
                downloadOverWifiOnly = true,
                downloadQuality = DownloadQuality.MEDIUM,
                forceOffline = false,
                themeMode = ThemeMode.SYSTEM,
                dynamicColorEnabled = false,
                storage = StorageUsage(usedBytes = 12_300_000_000L, availableBytes = 41_000_000_000L),
                account = AccountInfo(userName = "casey", serverName = "test-server"),
            )
    }
}
