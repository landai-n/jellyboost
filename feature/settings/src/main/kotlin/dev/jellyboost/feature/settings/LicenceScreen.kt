package dev.jellyboost.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

@Composable
fun LicenceScreen(
    viewModel: LicenceViewModel,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()

    LicenceContent(blocks = blocks, onBack = onBack, onHome = onHome, modifier = modifier)
}

/**
 * The licence body is never translated and never paraphrased: the row above says what it grants in
 * the user's language, and the text below is the document GPL-3.0 §4 requires the binary to convey.
 */
@Composable
internal fun LicenceContent(
    blocks: List<String>,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onBack = onBack, onHome = onHome) {
            ScreenHeaderTitle(text = stringResource(R.string.settings_licence))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            item(key = "intro") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = SettingsContentMaxWidth)
                            // The licence's name and the sentence explaining it are one fact, not two stops.
                            .semantics(mergeDescendants = true) {}
                            .padding(
                                start = Dimens.ScreenPadding,
                                end = Dimens.ScreenPadding,
                                top = Dimens.SpaceMedium,
                                bottom = Dimens.SpaceLarge,
                            ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                ) {
                    Text(
                        text = stringResource(R.string.settings_licence_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_licence_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // No key: the licence repeats whole sentences, so nothing here is unique but its position.
            items(items = blocks) { block ->
                Text(
                    text = block,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = SettingsContentMaxWidth)
                            .padding(horizontal = Dimens.ScreenPadding, vertical = BlockSpacing),
                )
            }
        }
    }
}

/** Paragraph separation for a 35 KB document: tighter than [Dimens.SpaceSmall] would read as a wall. */
private val BlockSpacing = 6.dp

@Preview(name = "Licence", showBackground = true, backgroundColor = 0xFF101010, heightDp = 700)
@Composable
private fun LicencePreview() {
    JellyfinTheme {
        LicenceContent(
            blocks =
                listOf(
                    "GNU GENERAL PUBLIC LICENSE",
                    "Version 3, 29 June 2007",
                    "Preamble",
                    "The GNU General Public License is a free, copyleft license for software and other " +
                        "kinds of works.",
                ),
            onBack = {},
            onHome = {},
        )
    }
}
