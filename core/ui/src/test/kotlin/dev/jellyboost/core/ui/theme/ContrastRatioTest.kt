package dev.jellyboost.core.ui.theme

import androidx.compose.ui.graphics.Color
import dev.jellyboost.core.ui.component.ErrorBannerContent
import dev.jellyboost.core.ui.component.GlassIconTint
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.pow

/**
 * Nothing else in the gate can see these tokens: Lint's contrast check reads static `@color`
 * resources and ATF reads a rendered screen on a device, so neither has an opinion about
 * `Color.Black.copy(alpha = 0.62f)` composited over a white film frame.
 *
 * A translucent token has no ratio of its own — white@70% is 21:1 against nothing and 2.29:1 over a
 * nav capsule on a bright poster — so every case names the opaque stack it is drawn on, worst case
 * being a fully white frame. Tokens private to other modules are mirrored as literals and pinned by
 * the mirror test below. Adding a pair is one line in [CASES].
 */
class ContrastRatioTest {
    @Test
    fun `the formula reproduces WCAG's own reference values`() {
        contrastRatio(Color.Black.flat, Color.White.flat).round() shouldEqual 21.0
        contrastRatio(Color.White.flat, Color.White.flat).round() shouldEqual 1.0
        // #767676 is the darkest grey clearing 4.5:1 on white, #949494 the darkest clearing 3:1.
        contrastRatio(Color(0xFF767676).flat, Color.White.flat).round() shouldEqual 4.54
        contrastRatio(Color(0xFF949494).flat, Color.White.flat).round() shouldEqual 3.03
        contrastRatio(Color.White.flat, Color(0xFF595959).flat).round() shouldEqual
            contrastRatio(Color(0xFF595959).flat, Color.White.flat).round()
    }

    @TestFactory
    fun `every drawn token pair still holds what the remediation committed to`(): List<DynamicTest> =
        CASES.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val actual = contrastRatio(case.foreground, case.background)
                when (val demand = case.demand) {
                    is Demand.Floor ->
                        check(actual >= demand.minimum) {
                            "${case.name} is ${actual.round()}:1, below the ${demand.minimum}:1 " +
                                "${demand.rule} owes it (${case.source}). Fix the colour, not this test."
                        }

                    is Demand.Exempt ->
                        check(abs(actual - demand.recorded) <= TOLERANCE) {
                            "${case.name} is ${actual.round()}:1, was ${demand.recorded}:1 when the " +
                                "audit accepted it below 3:1 because ${demand.reason} (${case.source}). " +
                                "If the change is deliberate, update this entry — and check the " +
                                "exception's reasoning still holds."
                        }

                    is Demand.KnownViolation ->
                        check(abs(actual - demand.recorded) <= TOLERANCE) {
                            "${case.name} is ${actual.round()}:1, recorded as ${demand.recorded}:1 " +
                                "against a ${demand.owed}:1 obligation (${case.source}). If you " +
                                "raised it, delete this entry and add a Floor one instead."
                        }
                }
            }
        }

    @Test
    fun `the mirrored tokens still match the declarations they were copied from`() {
        val repo = repoRoot()
        val drifted =
            MIRRORED_DECLARATIONS.filterNot { (path, declaration) ->
                val file = repo.resolve(path)
                check(Files.exists(file)) { "$path no longer exists — update this test's mirror list" }
                Files.readString(file).lineSequence().any { it.trim() == declaration }
            }
        check(drifted.isEmpty()) {
            "These tokens are mirrored as literals in this test and have drifted from their " +
                "declarations — recompute the affected ratios and update CASES:\n" +
                drifted.joinToString("\n") { (path, declaration) -> "  $path: expected `$declaration`" }
        }
    }

    private infix fun Double.shouldEqual(expected: Double) = check(this == expected) { "expected $expected, was $this" }
}

/**
 * `Double` channels rather than a Compose [Color]: [Color] is 8 bits per channel, and round-tripping
 * each intermediate composite through it quantises twice on a two-layer stack, moving the third
 * decimal the KDocs quote.
 */
private data class Opaque(
    val red: Double,
    val green: Double,
    val blue: Double,
)

private val Color.flat: Opaque
    get() {
        check(alpha == 1f) { "$this is translucent — composite it over something with `over`" }
        return Opaque(red.toDouble(), green.toDouble(), blue.toDouble())
    }

/** Plain source-over, chainable so a stack reads bottom-up in one expression. */
private infix fun Color.over(background: Opaque): Opaque {
    val a = alpha.toDouble()
    return Opaque(
        red = a * red + (1 - a) * background.red,
        green = a * green + (1 - a) * background.green,
        blue = a * blue + (1 - a) * background.blue,
    )
}

/** WCAG 2.x relative luminance. */
private fun relativeLuminance(color: Opaque): Double {
    fun channel(v: Double): Double =
        if (v <= LINEAR_THRESHOLD) v / LINEAR_DIVISOR else ((v + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA)
    return RED_WEIGHT * channel(color.red) +
        GREEN_WEIGHT * channel(color.green) +
        BLUE_WEIGHT * channel(color.blue)
}

/** `(L1 + 0.05) / (L2 + 0.05)`, lighter over darker whichever way round the pair was written. */
private fun contrastRatio(
    foreground: Opaque,
    background: Opaque,
): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (maxOf(a, b) + AMBIENT) / (minOf(a, b) + AMBIENT)
}

private fun Double.round(): Double = kotlin.math.round(this * ROUNDING) / ROUNDING

private const val LINEAR_THRESHOLD = 0.03928
private const val LINEAR_DIVISOR = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_SCALE = 1.055
private const val GAMMA = 2.4
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val AMBIENT = 0.05
private const val ROUNDING = 100.0

/** Two decimal places is how the KDocs quote these ratios. */
private const val TOLERANCE = 0.01

/** WCAG 1.4.3: body and label text below 18pt (or 14pt bold). */
private const val NORMAL_TEXT = 4.5

/** WCAG 1.4.11 / 2.4.7 / 1.4.3 large text: component boundaries, graphics, the focus ring. */
private const val COMPONENT = 3.0

private sealed interface Demand {
    /** Failing means the *colour* is wrong. */
    data class Floor(
        val minimum: Double,
        val rule: String,
    ) : Demand

    /** Accepted below the floor for [reason], frozen so the acceptance stays a choice. */
    data class Exempt(
        val recorded: Double,
        val reason: String,
    ) : Demand

    /** Below its floor and not yet argued for; frozen so the debt cannot grow quietly. */
    data class KnownViolation(
        val recorded: Double,
        val owed: Double,
    ) : Demand
}

private class ContrastCase(
    val name: String,
    val foreground: Opaque,
    val background: Opaque,
    val demand: Demand,
    val source: String,
)

/** Every scrim in the app is sized against this proxy, which is what makes the KDoc numbers match. */
private val BrightArtwork = Color.White.flat

private val DarkestArtwork = Color.Black.flat

private val Background = Color(0xFF101010).flat
private val Surface = Color(0xFF202020).flat
private val SurfaceVariant = Color(0xFF292929).flat
private val PrimaryFill = Color(0xFF00A4DC).flat
private val SolidWhite = Color.White.flat

private val ControlsScrim = Color.Black.copy(alpha = 0.62f) over BrightArtwork

private val VideoGlassFill = Color.Black.copy(alpha = 0.6f) over BrightArtwork

private val CastBackdrop = Color.Black.copy(alpha = 0.62f) over BrightArtwork

private val ChromeOverArtwork = GlassDefaults.ChromeFill over BrightArtwork
private val BottomNavOverArtwork = GlassDefaults.BottomNavFill over BrightArtwork

private val FieldWell = Color.White.copy(alpha = 0.04f) over Background
private val ChipWell = Color.White.copy(alpha = 0.05f) over Background

private val BannerWash = JellyfinColors.Error.copy(alpha = 0.10f) over Background

private val TopBadgeOverArtwork = Color.Black.copy(alpha = 0.60f) over BrightArtwork
private val TimeChipOverArtwork = Color.Black.copy(alpha = 0.70f) over BrightArtwork
private val RatingOverArtwork = Color.Black.copy(alpha = 0.65f) over BrightArtwork
private val IndicatorOverArtwork = Color.Black.copy(alpha = 0.60f) over BrightArtwork

private val SeekTrackBand = Color.White.copy(alpha = 0.55f) over ControlsScrim

private val TagFill = JellyfinColors.Primary.copy(alpha = 0.18f) over ControlsScrim

/**
 * The `source` string names the file the tokens live in; where that file is outside `:core:ui`, the
 * literal is also mirrored in [MIRRORED_DECLARATIONS].
 */
private val CASES =
    listOf(
        ContrastCase(
            name = "onSurface body text on surface",
            foreground = JellyfinColors.OnSurface over Surface,
            background = Surface,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnSurface on Surface — 16.29:1",
        ),
        ContrastCase(
            name = "onSurface body text on the page background",
            foreground = JellyfinColors.OnSurface over Background,
            background = Background,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnSurface on Background — 19.03:1",
        ),
        ContrastCase(
            name = "onSurfaceVariant label text on surface",
            foreground = JellyfinColors.OnSurfaceVariant over Surface,
            background = Surface,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnSurfaceVariant (white@70%) on Surface — 8.63:1",
        ),
        ContrastCase(
            name = "onSurfaceVariant label text on the page background",
            foreground = JellyfinColors.OnSurfaceVariant over Background,
            background = Background,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnSurfaceVariant on Background — 9.57:1",
        ),
        ContrastCase(
            name = "onSurfaceVariant label text on surfaceVariant",
            foreground = JellyfinColors.OnSurfaceVariant over SurfaceVariant,
            background = SurfaceVariant,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnSurfaceVariant on SurfaceVariant — 7.93:1",
        ),
        ContrastCase(
            name = "primary accent text on the page background",
            foreground = JellyfinColors.Primary over Background,
            background = Background,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.Primary #00A4DC on Background — 6.65:1 (audit's own figure)",
        ),
        ContrastCase(
            name = "onPrimary content on a filled primary container",
            foreground = JellyfinColors.OnPrimary over PrimaryFill,
            background = PrimaryFill,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.OnPrimary (black) on Primary — 7.34:1",
        ),
        ContrastCase(
            name = "error colour as text on the page background",
            foreground = JellyfinColors.Error over Background,
            background = Background,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinColors.Error #CF6679 on Background — 5.28:1 (audit's own figure)",
        ),
        ContrastCase(
            name = "outline role against the page background",
            foreground = JellyfinColors.Outline over Background,
            background = Background,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "JellyfinColors.Outline #6E6E6E, KDoc says 3.73:1 (was #3C3C3C at 1.72:1)",
        ),
        ContrastCase(
            name = "outline role against a card's surface",
            foreground = JellyfinColors.Outline over Surface,
            background = Surface,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "JellyfinColors.Outline #6E6E6E, KDoc says 3.20:1 (was 1.48:1)",
        ),
        ContrastCase(
            name = "primary pill label on its white fill",
            foreground = Color(0xFF101010) over SolidWhite,
            background = SolidWhite,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinButtons.PrimaryPillContent on PrimaryPillContainer — 19.03:1",
        ),
        ContrastCase(
            name = "disabled pill label on the page background",
            foreground = Color.White.copy(alpha = 0.48f) over Background,
            background = Background,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinButtons.PrimaryPillDisabledContent, KDoc says 5.00:1 (was 0.35 at 3.20:1)",
        ),
        ContrastCase(
            name = "disabled pill label on a card's surface",
            foreground = Color.White.copy(alpha = 0.48f) over Surface,
            background = Surface,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinButtons.PrimaryPillDisabledContent, KDoc says 4.78:1",
        ),
        ContrastCase(
            name = "disabled bulk-action label on the downloads bar",
            foreground = Color.White.copy(alpha = 0.48f) over Surface,
            background = Surface,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "DownloadsScreen.BULK_BUTTON_DISABLED_ALPHA, KDoc says 4.78:1",
        ),
        ContrastCase(
            name = "ghost button's border — its only edge",
            foreground = GlassDefaults.GhostBorder over Background,
            background = Background,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "GlassDefaults.GhostBorder, KDoc says 3.82:1 (was 0.12 at 1.38:1)",
        ),
        ContrastCase(
            name = "ghost button's border on a card",
            foreground = GlassDefaults.GhostBorder over Surface,
            background = Surface,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "GlassDefaults.GhostBorder, KDoc says 3.75:1",
        ),
        ContrastCase(
            name = "focused field's border — the app's only focus indicator",
            foreground = Color.White.copy(alpha = 0.42f) over Background,
            background = Background,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11 / 2.4.7"),
            source = "JellyfinTextField.FieldActiveBorder, KDoc says 4.09:1 (was 0.22 at 1.97:1)",
        ),
        ContrastCase(
            name = "focused field's border on a card",
            foreground = Color.White.copy(alpha = 0.42f) over Surface,
            background = Surface,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11 / 2.4.7"),
            source = "JellyfinTextField.FieldActiveBorder, KDoc says 3.99:1",
        ),
        ContrastCase(
            name = "field placeholder text in its well",
            foreground = Color.White.copy(alpha = 0.48f) over FieldWell,
            background = FieldWell,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "JellyfinTextField.FieldPlaceholder over FieldFill — 4.91:1",
        ),
        ContrastCase(
            name = "unselected filter chip's label",
            foreground = Color.White over ChipWell,
            background = ChipWell,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PillChip.ChipFill with onSurface content — 17.05:1",
        ),
        ContrastCase(
            name = "selected filter chip's label on its solid fill",
            foreground = Color(0xFF101010) over SolidWhite,
            background = SolidWhite,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PillChip.ChipSelectedContent on ChipSelectedFill — 19.03:1",
        ),
        ContrastCase(
            name = "informational chip's dimmed label",
            foreground = Color.White.copy(alpha = 0.7f) over ChipWell,
            background = ChipWell,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PillChip.DISABLED_CHIP_ALPHA over ChipFill — 8.87:1",
        ),
        ContrastCase(
            name = "error banner's message on its wash",
            foreground = ErrorBannerContent over BannerWash,
            background = BannerWash,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "ErrorBanner.ErrorBannerContent #F0A3AE over error@10% — 8.63:1",
        ),
        ContrastCase(
            name = "card's inset progress track over the darkest artwork",
            foreground = Color.White.copy(alpha = 0.40f) over DarkestArtwork,
            background = DarkestArtwork,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "MediaCardArtwork.PROGRESS_TRACK_ALPHA, KDoc says 3.66:1 (was 0.22 at 1.79:1)",
        ),
        ContrastCase(
            name = "detail header's resume track on the page background",
            foreground = Color.White.copy(alpha = 0.40f) over Background,
            background = Background,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "ItemDetailHeader.PROGRESS_TRACK_ALPHA, KDoc says 3.82:1 (was 1.97:1)",
        ),
        ContrastCase(
            name = "download queue row's progress track on its card",
            foreground = Color.White.copy(alpha = 0.40f) over Surface,
            background = Surface,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "DownloadRows.QUEUE_TRACK_ALPHA, KDoc says 3.75:1",
        ),
        ContrastCase(
            name = "storage usage bar's filled portion on its stat panel",
            foreground = JellyfinColors.Primary over Surface,
            background = Surface,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "DownloadsScreen.UsageBar fill (colorScheme.primary) on the panel — 5.70:1",
        ),
        ContrastCase(
            // TODO(a11y): the fourth progress track; its three siblings were raised 0.22 -> 0.40
            //  and this one kept white@12%. Same one-literal fix, plus the siblings' KDoc line.
            name = "storage usage bar's unfilled track on its stat panel [KNOWN VIOLATION]",
            foreground = Color.White.copy(alpha = 0.12f) over Surface,
            background = Surface,
            demand = Demand.KnownViolation(recorded = 1.45, owed = COMPONENT),
            source = "DownloadsScreen.UsageBarTrackColor — white@12%, undocumented",
        ),
        ContrastCase(
            name = "card's top badge label over bright artwork",
            foreground = Color.White over TopBadgeOverArtwork,
            background = TopBadgeOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "MediaCardArtwork.TopBadgeScrim — 5.74:1",
        ),
        ContrastCase(
            name = "card's time chip over bright artwork",
            foreground = Color.White over TimeChipOverArtwork,
            background = TimeChipOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "MediaCardArtwork.TimeChipScrim — 8.52:1",
        ),
        ContrastCase(
            name = "card's rating badge over bright artwork",
            foreground = Color.White over RatingOverArtwork,
            background = RatingOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "MediaCardArtwork.RatingScrim — 6.98:1",
        ),
        ContrastCase(
            name = "download badge's glyph over bright artwork",
            foreground = Color.White over RatingOverArtwork,
            background = RatingOverArtwork,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "DownloadBadge.BadgeScrim — 6.98:1",
        ),
        ContrastCase(
            name = "unselected selection ring over bright artwork",
            foreground = Color.White.copy(alpha = 0.85f) over IndicatorOverArtwork,
            background = IndicatorOverArtwork,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "MediaCardArtwork.UNSELECTED_INDICATOR_ALPHA over IndicatorScrim — 4.69:1",
        ),
        ContrastCase(
            name = "selected top-nav tab label over bright artwork",
            foreground = Color.White over ChromeOverArtwork,
            background = ChromeOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "GlassDefaults.ChromeFill, KDoc says 7.70:1 (was 0.45 at 3.05:1)",
        ),
        ContrastCase(
            name = "unselected top-nav tab label over bright artwork",
            foreground = JellyfinColors.OnSurfaceVariant over ChromeOverArtwork,
            background = ChromeOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "GlassDefaults.ChromeFill, KDoc says 4.77:1 (was 2.29:1 — the audit's worst text case)",
        ),
        ContrastCase(
            name = "bottom nav pill's label over bright artwork",
            foreground = Color.White over BottomNavOverArtwork,
            background = BottomNavOverArtwork,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "GlassDefaults.BottomNavFill, KDoc says 7.70:1",
        ),
        ContrastCase(
            name = "glass icon button's glyph over bright artwork",
            foreground = GlassIconTint over ChromeOverArtwork,
            background = ChromeOverArtwork,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "JellyfinButtons.GlassIconTint (white@80%) over ChromeFill — 5.65:1",
        ),
        ContrastCase(
            name = "player title over the controls scrim",
            foreground = Color.White over ControlsScrim,
            background = ControlsScrim,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerControls.SCRIM, KDoc says 6.20:1 (was 0.35 at 2.44:1)",
        ),
        ContrastCase(
            name = "player subtitle line over the controls scrim",
            foreground = Color.White.copy(alpha = 0.85f) over ControlsScrim,
            background = ControlsScrim,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerControls.SUBTITLE_ALPHA, KDoc says 5.03:1 (was 1.92:1)",
        ),
        ContrastCase(
            name = "player clock's duration half over the controls scrim",
            foreground = Color.White.copy(alpha = 0.85f) over ControlsScrim,
            background = ControlsScrim,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerControls.CLOCK_DIM_ALPHA, KDoc says 5.03:1 (was 0.6 at 1.77:1)",
        ),
        ContrastCase(
            name = "seek bar's unplayed track over the controls scrim",
            foreground = Color.White.copy(alpha = 0.55f) over ControlsScrim,
            background = ControlsScrim,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "PlayerControls.TRACK_COLOR, KDoc says 3.12:1 (was 1.23:1)",
        ),
        ContrastCase(
            name = "seek bar's buffered band over the controls scrim",
            foreground = Color.White.copy(alpha = 0.8f) over ControlsScrim,
            background = ControlsScrim,
            demand = Demand.Floor(COMPONENT, "WCAG 1.4.11"),
            source = "PlayerControls.BUFFERED_COLOR, KDoc says 4.67:1 (was 1.38:1)",
        ),
        ContrastCase(
            name = "glass control content over a bright film frame",
            foreground = Color.White over VideoGlassFill,
            background = VideoGlassFill,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerControls.VIDEO_GLASS_FILL — 5.74:1 (the token that already passed)",
        ),
        ContrastCase(
            name = "SyncPlay participant list on its waiting panel",
            foreground = Color.White.copy(alpha = 0.85f) over VideoGlassFill,
            background = VideoGlassFill,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerScreen.DIM_ALPHA over OVERLAY_SCRIM, KDoc says 4.69:1 (was 0.7 at 3.76:1)",
        ),
        ContrastCase(
            name = "casting label over the dimmed artwork",
            foreground = Color.White over CastBackdrop,
            background = CastBackdrop,
            demand = Demand.Floor(NORMAL_TEXT, "WCAG 1.4.3"),
            source = "PlayerScreen.BACKDROP_SCRIM, KDoc says 6.20:1 (was 0.45 at 3.35:1)",
        ),
        ContrastCase(
            // TODO(a11y): the transcoding tag is 10sp text, so 1.4.3 asks 4.5:1; over a white frame
            //  it lands at 3.44:1. Lightening TAG_TEXT to roughly #A8E4F7 clears it without
            //  touching the fill.
            name = "player's transcoding tag label over a bright frame [KNOWN VIOLATION]",
            foreground = Color(0xFF7FD8F5) over TagFill,
            background = TagFill,
            demand = Demand.KnownViolation(recorded = 3.44, owed = NORMAL_TEXT),
            source = "PlayerControls.TAG_TEXT on primary@TAG_FILL_ALPHA over SCRIM",
        ),
        ContrastCase(
            name = "glass surface hairline on the page background",
            foreground = GlassDefaults.Hairline over Background,
            background = Background,
            demand =
                Demand.Exempt(
                    recorded = 1.25,
                    reason = "it seams a surface that already has a fill, and never says alone where a control is",
                ),
            source = "GlassDefaults.Hairline (white@9%)",
        ),
        ContrastCase(
            name = "panel hairline on a card's surface",
            foreground = GlassDefaults.PanelHairline over Surface,
            background = Surface,
            demand =
                Demand.Exempt(
                    recorded = 1.19,
                    reason = "it is the edge of a large filled panel, deliberately fainter than the standard hairline",
                ),
            source = "GlassDefaults.PanelHairline (white@6%)",
        ),
        ContrastCase(
            name = "artwork's inner hairline on the page background",
            foreground = GlassDefaults.ArtworkInnerHairline over Background,
            background = Background,
            demand =
                Demand.Exempt(
                    recorded = 1.18,
                    reason = "it lifts an image off a same-coloured page; the image is its own boundary",
                ),
            source = "GlassDefaults.ArtworkInnerHairline (white@7%)",
        ),
        ContrastCase(
            name = "in-content glass fill over bright artwork",
            foreground = GlassDefaults.Fill over BrightArtwork,
            background = BrightArtwork,
            demand =
                Demand.Exempt(
                    recorded = 1.00,
                    reason =
                        "in-content glass only ever lands on artwork the card has already scrimmed — " +
                            "chrome, which floats over anything, uses ChromeFill instead",
                ),
            source = "GlassDefaults.Fill (white@6%) — the audit's 1.00:1, kept on purpose",
        ),
        ContrastCase(
            name = "error banner's border against the page",
            foreground = JellyfinColors.Error.copy(alpha = 0.28f) over Background,
            background = Background,
            demand =
                Demand.Exempt(
                    recorded = 1.45,
                    reason =
                        "the banner is a fill, an icon and a sentence; the border only holds the shape " +
                            "where the wash fades out",
                ),
            source = "ErrorBanner.BANNER_BORDER_ALPHA",
        ),
        ContrastCase(
            name = "seek bar's buffered band against its unplayed track",
            foreground = Color.White.copy(alpha = 0.8f) over ControlsScrim,
            background = SeekTrackBand,
            demand =
                Demand.Exempt(
                    recorded = 1.50,
                    reason =
                        "band-against-band is a hierarchy, not a boundary — both bands clear 3:1 " +
                            "against the scrim on their own, and the KDoc pins this exact separation",
                ),
            source = "PlayerControls.BUFFERED_COLOR vs TRACK_COLOR — KDoc says 1.50:1, up from 1.12:1",
        ),
    )

/**
 * Every literal in [CASES] standing in for a `private` token elsewhere, matched by exact trimmed
 * line — the one comparison that cannot quietly accept a changed alpha.
 */
private val MIRRORED_DECLARATIONS =
    listOf(
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private val SCRIM = Color.Black.copy(alpha = 0.62f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "internal val VIDEO_GLASS_FILL = Color.Black.copy(alpha = 0.6f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private const val SUBTITLE_ALPHA = 0.85f",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private const val CLOCK_DIM_ALPHA = 0.85f",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private val TRACK_COLOR = Color.White.copy(alpha = 0.55f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private val BUFFERED_COLOR = Color.White.copy(alpha = 0.8f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private val TAG_TEXT = Color(0xFF7FD8F5)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerControls.kt" to
            "private const val TAG_FILL_ALPHA = 0.18f",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerScreen.kt" to
            "private val OVERLAY_SCRIM = Color.Black.copy(alpha = 0.6f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerScreen.kt" to
            "private val BACKDROP_SCRIM = Color.Black.copy(alpha = 0.62f)",
        "player/src/main/kotlin/dev/jellyboost/player/ui/PlayerScreen.kt" to
            "private const val DIM_ALPHA = 0.85f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/JellyfinButtons.kt" to
            "private val PrimaryPillContent = Color(0xFF101010)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/JellyfinButtons.kt" to
            "private val PrimaryPillDisabledContent = Color.White.copy(alpha = 0.48f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/JellyfinTextField.kt" to
            "private val FieldFill = Color.White.copy(alpha = 0.04f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/JellyfinTextField.kt" to
            "private val FieldActiveBorder = Color.White.copy(alpha = 0.42f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/JellyfinTextField.kt" to
            "private val FieldPlaceholder = Color.White.copy(alpha = 0.48f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/PillChip.kt" to
            "private val ChipFill = Color.White.copy(alpha = 0.05f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/PillChip.kt" to
            "private val ChipSelectedContent = Color(0xFF101010)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/PillChip.kt" to
            "private const val DISABLED_CHIP_ALPHA = 0.7f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/ErrorBanner.kt" to
            "private const val BANNER_FILL_ALPHA = 0.10f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/ErrorBanner.kt" to
            "private const val BANNER_BORDER_ALPHA = 0.28f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private const val PROGRESS_TRACK_ALPHA = 0.40f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private val TopBadgeScrim = Color.Black.copy(alpha = 0.60f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private val TimeChipScrim = Color.Black.copy(alpha = 0.70f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private val RatingScrim = Color.Black.copy(alpha = 0.65f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private val IndicatorScrim = Color.Black.copy(alpha = 0.60f)",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/MediaCardArtwork.kt" to
            "private const val UNSELECTED_INDICATOR_ALPHA = 0.85f",
        "core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/DownloadBadge.kt" to
            "private val BadgeScrim = Color.Black.copy(alpha = 0.65f)",
        "feature/detail/src/main/kotlin/dev/jellyboost/feature/detail/ItemDetailHeader.kt" to
            "private const val PROGRESS_TRACK_ALPHA = 0.40f",
        "feature/downloads/src/main/kotlin/dev/jellyboost/feature/downloads/DownloadRows.kt" to
            "private const val QUEUE_TRACK_ALPHA = 0.40f",
        "feature/downloads/src/main/kotlin/dev/jellyboost/feature/downloads/DownloadsScreen.kt" to
            "private val UsageBarTrackColor = Color.White.copy(alpha = 0.12f)",
        "feature/downloads/src/main/kotlin/dev/jellyboost/feature/downloads/DownloadsScreen.kt" to
            "private const val BULK_BUTTON_DISABLED_ALPHA = 0.48f",
    )

/**
 * Fails loudly rather than skipping: a mirror check that passes without reading the sources looks
 * like coverage and is not.
 */
private fun repoRoot(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
        dir = dir.parent
    }
    error("could not locate the repository root from ${Paths.get("").toAbsolutePath()}")
}
