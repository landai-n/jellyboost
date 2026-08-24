package dev.jellyboost.core.datastore

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.model.HomeSectionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A file of its own rather than the settings DataStore, because this is a disposable server-derived cache:
 * clearing it costs one request, and the user's actual settings stay free of values they never chose.
 *
 * Stored as a comma-separated list of enum *names*, which survives reordering the enum. An entry that no
 * longer decodes is dropped rather than failing the read; a layout that decodes to nothing reads as absent.
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
            // apply(), not commit(): the next load re-fetches anyway, so a write lost to a crash costs nothing.
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
