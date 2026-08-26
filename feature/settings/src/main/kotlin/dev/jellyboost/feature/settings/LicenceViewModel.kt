package dev.jellyboost.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/**
 * A line indented this far in `gpl_3_0.txt` is a centred heading, never wrapped prose.
 */
private const val HEADING_INDENT = 8

private val BlankLine = Regex("\\n[ \\t]*\\n")

/** Survives a configuration change, so rotating does not re-read 35 KB off the disk. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

@HiltViewModel
class LicenceViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val blocks: StateFlow<List<String>> =
            flow { emit(readLicence()) }
                .flowOn(ioDispatcher)
                .catch { error ->
                    // Permanent: the text is packaged in the APK, so a failure here is a broken install and
                    // no retry can mend it. The screen still states the licence from its own copy above.
                    Timber.e(error, "Could not read the bundled licence text")
                    emit(emptyList())
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                    initialValue = emptyList(),
                )

        private fun readLicence(): List<String> =
            context.resources
                .openRawResource(R.raw.gpl_3_0)
                .bufferedReader()
                .use { reader -> licenceBlocks(reader.readText()) }
    }

/**
 * The bundled licence is hard-wrapped at ~70 columns, which on a phone breaks every line twice. Each
 * returned block is one paragraph, rewrapped by the text layout instead; headings stand alone.
 */
internal fun licenceBlocks(raw: String): List<String> = raw.split(BlankLine).flatMap(::blocksOf)

private fun blocksOf(source: String): List<String> {
    val blocks = mutableListOf<String>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += paragraph.toString()
            paragraph.clear()
        }
    }

    source.lineSequence().forEach { line ->
        val text = line.trim()
        if (text.isEmpty()) return@forEach
        if (line.length - line.trimStart().length >= HEADING_INDENT) {
            flush()
            blocks += text
        } else {
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(text)
        }
    }
    flush()
    return blocks
}
