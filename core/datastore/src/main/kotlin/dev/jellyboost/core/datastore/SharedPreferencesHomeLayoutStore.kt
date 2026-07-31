package dev.jellyboost.core.datastore

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.model.HomeSectionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HomeLayoutStore] backed by a plain `SharedPreferences` file of its own (`home_layout`).
 *
 * A file of its own, and not the settings DataStore, because this is a disposable server-derived
 * cache: clearing it costs one request, and keeping it out of `app_preferences` keeps the user's
 * actual settings free of values the user never chose here.
 *
 * The layout is stored as a comma-separated list of enum *names* — a short, human-readable value
 * that survives reordering the enum. An entry that no longer decodes (an older build's name) is
 * dropped rather than failing the read; a layout that decodes to nothing is treated as absent, so
 * the caller falls back to the defaults.
 */
@Singleton
class SharedPreferencesHomeLayoutStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : HomeLayoutStore {
        private val preferences: SharedPreferences by lazy {
            context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
        }

        override fun read(): List<HomeSectionType>? =
            preferences
                .getString(KEY_SECTIONS, null)
                ?.split(SEPARATOR)
                ?.mapNotNull { name -> HomeSectionType.entries.firstOrNull { it.name == name.trim() } }
                ?.takeIf { it.isNotEmpty() }

        override fun write(sections: List<HomeSectionType>) {
            // apply(), not commit(): the next load re-fetches anyway, so a write lost to a crash
            // costs nothing and is not worth blocking the IO dispatcher for.
            preferences
                .edit()
                .putString(KEY_SECTIONS, sections.joinToString(SEPARATOR.toString()) { it.name })
                .apply()
        }

        override fun clear() {
            preferences.edit().remove(KEY_SECTIONS).apply()
        }

        private companion object {
            const val STORE_NAME = "home_layout"
            const val KEY_SECTIONS = "sections"
            const val SEPARATOR = ','
        }
    }
