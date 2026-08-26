package dev.jellyboost.feature.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.chipColors
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle

/**
 * The list is generated at build time from `:app`'s resolved dependency graph, so it cannot drift
 * from what the bundle actually ships. `:app` owns that graph and therefore the resource.
 *
 * @param librariesRawResId `R.raw.aboutlibraries` in `:app`, which this module cannot name.
 */
@Composable
fun ThirdPartyLicencesScreen(
    @RawRes librariesRawResId: Int,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraries by produceLibraries(librariesRawResId)

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onBack = onBack, onHome = onHome) {
            ScreenHeaderTitle(text = stringResource(R.string.settings_third_party))
        }

        if (libraries == null) {
            // Carries its own polite live region, so the wait is announced rather than silent.
            LoadingState()
        } else {
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.fillMaxSize(),
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                // Transparent so the app's own background shows through; every colour the defaults
                // would derive from it is therefore named here instead.
                colors =
                    LibraryDefaults.libraryColors(
                        libraryBackgroundColor = Color.Transparent,
                        libraryContentColor = MaterialTheme.colorScheme.onBackground,
                        versionChipColors =
                            LibraryDefaults.chipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        dialogBackgroundColor = MaterialTheme.colorScheme.surface,
                    ),
                // The library's own default is the literal string "OK"; the platform's is translated.
                licenseDialogConfirmText = stringResource(android.R.string.ok),
            )
        }
    }
}
