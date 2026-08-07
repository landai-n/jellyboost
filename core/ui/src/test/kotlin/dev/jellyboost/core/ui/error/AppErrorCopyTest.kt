package dev.jellyboost.core.ui.error

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.text.UiText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [toUiText] — the one mapping that replaced five (audit H8, DUP-1 = CPX-13).
 *
 * The point of these is not that a branch produces *some* copy; it is that the branches a screen
 * may override are exactly the three [AppErrorCopy] exposes, and that the rest are pinned to
 * `:core:ui`'s resources so no screen can drift them back apart. Assertions are on resource ids
 * rather than sentences on purpose: an English literal in a test is how the old mappers passed
 * while showing untranslated copy on 68 locales.
 */
class AppErrorCopyTest {
    private val screen =
        AppErrorCopy(
            unknown = SCREEN_UNKNOWN,
            notFound = SCREEN_NOT_FOUND,
            server = SCREEN_SERVER,
            serverWithCode = SCREEN_SERVER_WITH_CODE,
        )

    private val default = AppErrorCopy(unknown = SCREEN_UNKNOWN)

    // ---- the shared branches ----------------------------------------------------------------------

    @Test
    fun `a network failure is the shared sentence, whatever the screen says elsewhere`() {
        AppError.Network().toUiText(screen) shouldBe UiText.res(R.string.error_network)
    }

    @Test
    fun `an unusable address is the same dead end as an unreachable server`() {
        AppError.ServerResolution(unreachableAddresses = listOf("jelly.local")).toUiText(screen) shouldBe
            AppError.Network().toUiText(screen)
    }

    @Test
    fun `a rejected session is the shared sentence`() {
        AppError.Unauthorized().toUiText(screen) shouldBe UiText.res(R.string.error_unauthorized)
    }

    @Test
    fun `a local storage failure is the shared sentence`() {
        AppError.Storage().toUiText(screen) shouldBe UiText.res(R.string.error_storage)
    }

    // ---- the overridable branches -----------------------------------------------------------------

    @Test
    fun `unknown is always the screen's own — it has to name what failed`() {
        AppError.Unknown().toUiText(screen) shouldBe UiText.res(SCREEN_UNKNOWN)
    }

    @Test
    fun `a screen that asked about an item gets the item wording without overriding anything`() {
        AppError.NotFound("id").toUiText(default) shouldBe UiText.res(R.string.error_not_found_item)
    }

    @Test
    fun `a screen that asked about a library overrides not-found`() {
        AppError.NotFound("id").toUiText(screen) shouldBe UiText.res(SCREEN_NOT_FOUND)
    }

    @Test
    fun `the library and item wordings are distinct resources`() {
        R.string.error_not_found_library shouldNotBe R.string.error_not_found_item
    }

    // ---- the status code -------------------------------------------------------------------------

    @Test
    fun `a status code picks the formatted resource and is carried as an argument`() {
        AppError.Server(statusCode = 502).toUiText(screen) shouldBe
            UiText.Res(SCREEN_SERVER_WITH_CODE, listOf(502))
    }

    @Test
    fun `no status code picks the unformatted resource, never a blank pair of brackets`() {
        AppError.Server(statusCode = null).toUiText(screen) shouldBe UiText.res(SCREEN_SERVER)
    }

    @Test
    fun `the server branches default to the shared wording`() {
        AppError.Server(statusCode = null).toUiText(default) shouldBe UiText.res(R.string.error_server)
        AppError.Server(statusCode = 500).toUiText(default) shouldBe
            UiText.Res(R.string.error_server_with_code, listOf(500))
    }

    private companion object {
        // Stand-ins for a feature module's own resources: any four distinct ids will do, and
        // `:core:ui` cannot see a feature's R.
        const val SCREEN_UNKNOWN = 1
        const val SCREEN_NOT_FOUND = 2
        const val SCREEN_SERVER = 3
        const val SCREEN_SERVER_WITH_CODE = 4
    }
}
