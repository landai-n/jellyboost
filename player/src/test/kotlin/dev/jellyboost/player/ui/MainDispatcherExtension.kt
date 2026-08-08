package dev.jellyboost.player.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Swaps `Dispatchers.Main` for a [TestDispatcher] around each test so `viewModelScope` work runs
 * on the test's virtual clock.
 *
 * Deliberately duplicated from `:app`'s test source set — this project has no shared test-fixtures
 * module, and one small extension is cheaper than introducing one. Shared across this module's
 * test packages (here, and `syncplay.ui` by import) rather than duplicated a second time within
 * `:player` itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : BeforeEachCallback,
    AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
