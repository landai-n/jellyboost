package dev.jellyboost.feature.library.libraries

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.LibraryView

data class LibrariesUiState(
    val isLoading: Boolean = true,
    val libraries: List<LibraryView> = emptyList(),
    val error: AppError? = null,
) {
    val isEmpty: Boolean
        get() = libraries.isEmpty() && error == null
}
