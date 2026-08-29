package dev.jellyboost.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults

/**
 * The hub, drawn as a scroll of panels — the compact shape, where the list *is* the screen and
 * nothing on it is selected, because tapping a row leaves for the category's own destination.
 */
@Composable
internal fun SettingsHubPanels(
    account: AccountInfo?,
    summaries: HubSummaries,
    onOpenAccount: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(HubContentPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        SettingsPanel {
            IdentityRow(account = account, onClick = onOpenAccount)
        }
        SettingsPanel {
            SettingsCategory.entries.forEachIndexed { index, category ->
                if (index > 0) SettingsRowSeparator()
                CategoryRow(
                    category = category,
                    summaries = summaries,
                    onClick = { onOpenCategory(category) },
                )
            }
        }
    }
}

/**
 * The hub, drawn as the two-pane rail — loose rows on the background rather than in a panel,
 * because here the list is chrome beside the content and the selected row is the thing with a
 * surface under it.
 */
@Composable
internal fun SettingsHubRail(
    account: AccountInfo?,
    summaries: HubSummaries,
    openPane: SettingsPane,
    onOpenPane: (SettingsPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(RailContentPadding),
        verticalArrangement = Arrangement.spacedBy(RailRowGap),
    ) {
        RailSlot(selected = openPane == SettingsPane.ACCOUNT) {
            IdentityRow(account = account, onClick = { onOpenPane(SettingsPane.ACCOUNT) })
        }
        SettingsCategory.entries.forEach { category ->
            val pane = SettingsPane.of(category)
            RailSlot(selected = openPane == pane) {
                CategoryRow(
                    category = category,
                    summaries = summaries,
                    onClick = { onOpenPane(pane) },
                )
            }
        }
    }
}

/**
 * The "you are here" a rail needs and a push list must not have. Purely visual: the row inside
 * still announces as a Button, because activating it is still what opens the category — a
 * `selectable` here would promise a radio group that the compact shape does not have, and the two
 * shapes must not read differently to a screen reader.
 */
@Composable
private fun RailSlot(
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    color =
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = shape,
                ).border(
                    width = GlassDefaults.HairlineWidth,
                    color = if (selected) GlassDefaults.PanelHairline else Color.Transparent,
                    shape = shape,
                ),
    ) {
        content()
    }
}

@Composable
private fun IdentityRow(
    account: AccountInfo?,
    onClick: () -> Unit,
) {
    SettingsIdentityRow(
        name = account?.userName ?: stringResource(R.string.settings_account_unknown),
        server = account?.serverName,
        onClick = onClick,
    )
}

@Composable
private fun CategoryRow(
    category: SettingsCategory,
    summaries: HubSummaries,
    onClick: () -> Unit,
) {
    SettingsCategoryRow(
        icon = category.icon(),
        title = stringResource(category.titleRes),
        summary = summaries.of(category).summaryText(),
        onClick = onClick,
    )
}

private val HubContentPadding =
    PaddingValues(
        start = Dimens.ScreenPadding,
        end = Dimens.ScreenPadding,
        top = Dimens.SpaceSmall,
        bottom = Dimens.SpaceExtraLarge,
    )

private val RailContentPadding =
    PaddingValues(
        start = Dimens.SpaceMedium,
        end = Dimens.SpaceMedium,
        top = Dimens.SpaceSmall,
        bottom = Dimens.SpaceExtraLarge,
    )

/** Rail rows nearly touch: the gap says they are separate targets, the surface says which is open. */
private val RailRowGap: Dp = 2.dp
