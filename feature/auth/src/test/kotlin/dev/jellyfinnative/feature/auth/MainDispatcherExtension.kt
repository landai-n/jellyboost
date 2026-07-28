package dev.jellyfinnative.feature.auth

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
 * Swaps `Dispatchers.Main` for a [TestDispatcher] around each test, so that `viewModelScope`
 * work is driven by the test's virtual clock.
 *
 * An [UnconfinedTestDispatcher] by default: ViewModels start work in their `init` block, and
 * eager execution means a test can assert on that work without first pumping the scheduler.
 * `runTest` picks up this dispatcher's scheduler, so `delay` inside collected flows stays
 * virtual.
 *
 * Duplicated in `:app`'s test source set: cross-module test fixtures are not wired up in this
 * project, and one small extension is cheaper than a shared test module.
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
