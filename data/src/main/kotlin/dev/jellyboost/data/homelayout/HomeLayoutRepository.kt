package dev.jellyboost.data.homelayout

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.network.di.IoDispatcher
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.runCatchingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which rows the home screen shows, and in which order, as the user configured it in jellyfin-web
 * (Settings → Home).
 *
 * Deliberately **not** part of `JellyfinRepository`. That interface is the browse contract, split
 * online/offline and delegated per call; this is one small piece of configuration with a different
 * shape — it has an offline answer of its own (the last layout seen, not a Room query), it must
 * never fail, and no other screen wants it. Keeping it separate leaves both implementations of the
 * browse repository untouched.
 *
 * Failure policy: this class never throws and never returns nothing useful. A server that cannot
 * be reached, a record that will not parse, a brand-new install — every one of them resolves to a
 * layout, falling back last-persisted → jellyfin-web's defaults. The home screen's shape is not
 * worth an error state.
 */
@Singleton
class HomeLayoutRepository
    @Inject
    constructor(
        private val apiClient: ApiClient,
        private val store: HomeLayoutStore,
        private val connectivity: ConnectivityRefresher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * The rows to render, in order, with [HomeSectionType.NONE] dropped and duplicates removed.
         *
         * Online this is a fresh read, so changing the layout in jellyfin-web and pulling to
         * refresh shows the new one; the result is persisted for the next offline load.
         */
        suspend fun getHomeSections(): List<HomeSectionType> =
            withContext(ioDispatcher) {
                if (!connectivity.isOnline) return@withContext persistedOrDefaults()

                when (val prefs = fetchCustomPrefs()) {
                    is AppResult.Success -> resolveHomeSections(prefs.value).also(store::write)
                    is AppResult.Failure -> {
                        Timber.w("Home layout unavailable (%s); using the last known one", prefs.error)
                        persistedOrDefaults()
                    }
                }
            }

        /**
         * Reads the `usersettings` DisplayPreferences record jellyfin-web writes.
         *
         * Both magic strings are load-bearing. Preferences are partitioned by
         * `(userId, itemId, client)`: any id other than `usersettings` is MD5-hashed into an
         * unrelated record, and any client other than the legacy `emby` reads a private,
         * always-empty one. Passing this app's own client name would therefore look exactly like
         * "the user has configured nothing" — forever.
         */
        private suspend fun fetchCustomPrefs(): AppResult<Map<String, String?>> =
            runCatchingApi {
                apiClient.displayPreferencesApi
                    .getDisplayPreferences(
                        displayPreferencesId = USER_SETTINGS_RECORD,
                        client = LEGACY_WEB_CLIENT,
                    ).content.customPrefs
            }

        private fun persistedOrDefaults(): List<HomeSectionType> = store.read() ?: DEFAULT_HOME_SECTIONS

        private companion object {
            /** The DisplayPreferences record jellyfin-web keeps the home layout in. */
            const val USER_SETTINGS_RECORD = "usersettings"

            /** The partition key every client sharing the web-configured layout passes. */
            const val LEGACY_WEB_CLIENT = "emby"
        }
    }
