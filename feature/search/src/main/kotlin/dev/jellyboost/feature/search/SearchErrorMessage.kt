package dev.jellyboost.feature.search

import androidx.compose.runtime.Composable
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.resolve

/** An unclassified failure here happened while searching; a missing thing is already the default. */
internal val SearchErrorCopy = AppErrorCopy(unknown = R.string.search_error_unknown)

@Composable
internal fun AppError.toMessage(): String = toUiText(SearchErrorCopy).resolve()
