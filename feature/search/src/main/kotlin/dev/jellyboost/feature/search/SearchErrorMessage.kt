package dev.jellyboost.feature.search

import androidx.compose.runtime.Composable
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.resolve

/**
 * What this screen calls the one branch it does not share: an unclassified failure here happened
 * while searching. A missing thing is an item, which is already the shared default.
 */
internal val SearchErrorCopy = AppErrorCopy(unknown = R.string.search_error_unknown)

/** Turns the domain failure taxonomy into copy a user can act on. */
@Composable
internal fun AppError.toMessage(): String = toUiText(SearchErrorCopy).resolve()
