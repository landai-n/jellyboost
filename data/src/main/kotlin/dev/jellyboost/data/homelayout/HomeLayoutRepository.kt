package dev.jellyboost.data.homelayout

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.network.runCatchingApi
import dev.jellyboost.data.ConnectivityRefresher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which rows the home screen shows, in the order the user configured in jellyfin-web
 * (Settings → Home).
 *
 * Never throws and never returns nothing useful: unreachable server, unparseable record or fresh
 * install all resolve to a layout, falling back last-persisted → jellyfin-web's defaults. The home
 * screen's shape is not worth an error state.
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
        /** [HomeSectionType.NONE] dropped, duplicates removed; the result is persisted for offline. */
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
         * Both magic strings are load-bearing: preferences partition by `(userId, itemId, client)`,
         * so any id but `usersettings` is MD5-hashed into an unrelated record and any client but the
         * legacy `emby` reads a private, always-empty one — indistinguishable from "nothing
         * configured", forever.
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
            const val USER_SETTINGS_RECORD = "usersettings"

            const val LEGACY_WEB_CLIENT = "emby"
        }
    }
