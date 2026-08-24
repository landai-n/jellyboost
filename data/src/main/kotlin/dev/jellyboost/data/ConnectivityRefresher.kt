package dev.jellyboost.data

import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.connectivity.onlineStateChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The connection changed — what you are showing came from the other source." A screen that already
 * fetched keeps its answer, since `DelegatingJellyfinRepository` only picks per *call*.
 *
 * `Flow<Unit>` and `Boolean` rather than `ConnectionState`: feature modules depend on `:data`, not
 * `:core:network`, and only the offline banner has any use for the reason taxonomy.
 *
 * Fires on **both** edges, plus every [ConnectionStateProvider.serverReconfirmed] — a request that
 * already fell back leaves downloads-only rows on screen while the state still reads online, so no
 * edge is coming to correct it. It says nothing about the state a screen *starts* with.
 */
@Singleton
class ConnectivityRefresher
    @Inject
    constructor(
        private val connectionStateProvider: ConnectionStateProvider,
    ) {
        /** Never completes. */
        val connectivityChanged: Flow<Unit> =
            merge(
                connectionStateProvider.state.onlineStateChanges().map { },
                connectionStateProvider.serverReconfirmed,
            )

        /**
         * A point read for one-shot "is this request worth making" decisions. Screens that need to
         * *react* collect [connectivityChanged] instead.
         */
        val isOnline: Boolean
            get() = connectionStateProvider.state.value.isOnline
    }

/**
 * Both directions matter: a screen opened offline keeps showing downloads after the server returns,
 * and one opened online keeps showing links the app can no longer play after the user walks out of
 * range. The reload is the same call either way.
 *
 * **An extension rather than a member, on purpose.** Every ViewModel test fakes
 * `ConnectivityRefresher` with a MockK stub of `connectivityChanged`; a virtual member would have to
 * be stubbed as well in fifteen-odd test classes, while an extension resolves statically and runs
 * this real body over the stubbed flow.
 *
 * @param onlyIf checked immediately before [reload] — for screens where a reload would be wasted
 *   work rather than a correction (nothing fetched yet, or a fetch already in flight).
 * @param reload deliberately not `suspend`, so this helper never serialises two loads.
 */
fun ConnectivityRefresher.reloadOnChange(
    scope: CoroutineScope,
    onlyIf: () -> Boolean = { true },
    reload: () -> Unit,
): Job =
    scope.launch {
        connectivityChanged.collect { if (onlyIf()) reload() }
    }
