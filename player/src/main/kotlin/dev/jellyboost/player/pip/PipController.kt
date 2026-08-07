package dev.jellyboost.player.pip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that knows whether leaving the app right now should float the video
 * (docs/PLAN.md, "M9 Polish" → PiP).
 *
 * Picture-in-picture is an *activity* capability, but the conditions for it are entirely the
 * player's: the player route has to be on screen, something has to actually be playing, and the user
 * has to have left `pipOnLeave` on. `MainActivity` hosts the whole app, so without this seam it
 * would either have to reach into the player's ViewModel or guess — and guessing means an item
 * detail page that shrinks into a floating window when the user presses Home.
 *
 * The traffic runs both ways. The player screen publishes readiness through [setPlayerState]; the
 * activity publishes the mode changes the system hands it through [setInPictureInPicture], which is
 * how the controls know to get out of the way and come back.
 */
@Singleton
class PipController
    @Inject
    constructor() {
        private val _state = MutableStateFlow(PipState())

        /** Observed by `MainActivity` (to arm PiP) and by the player screen (to hide its controls). */
        val state: StateFlow<PipState> = _state.asStateFlow()

        /**
         * Publishes what the player screen is doing.
         *
         * @param active `true` while the player route is composed **and** playing **and** the
         *   preference allows it — everything the activity needs, already decided.
         * @param videoWidth / @param videoHeight the decoded video size, for the window's aspect
         *   ratio; zero until the first frame is decoded, which the activity treats as "no hint".
         */
        internal fun setPlayerState(
            active: Boolean,
            videoWidth: Int = 0,
            videoHeight: Int = 0,
        ) {
            _state.update {
                it.copy(canEnter = active, videoWidth = videoWidth, videoHeight = videoHeight)
            }
        }

        /** Records the system's picture-in-picture mode change. */
        fun setInPictureInPicture(inPictureInPicture: Boolean) {
            _state.update { it.copy(isInPictureInPicture = inPictureInPicture) }
        }

        /** Forgets everything — the player screen is gone. */
        internal fun clear() {
            _state.value = PipState()
        }
    }

/**
 * @property canEnter `true` while entering picture-in-picture on user-leave is wanted.
 * @property isInPictureInPicture `true` while the app is already in the floating window.
 */
data class PipState(
    val canEnter: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isInPictureInPicture: Boolean = false,
) {
    /**
     * The video's aspect ratio as a numerator/denominator pair, clamped to what Android accepts.
     *
     * `PictureInPictureParams.setAspectRatio` throws outside 1:2.39 … 2.39:1, and a 2.76:1 Ultra
     * Panavision film is not a hypothetical — an uncaught `IllegalArgumentException` there takes the
     * whole activity down as the user presses Home. `null` when the size is not known yet, which
     * leaves the system to pick its default window.
     */
    val aspectRatio: Pair<Int, Int>?
        get() {
            if (videoWidth <= 0 || videoHeight <= 0) return null
            val ratio = videoWidth.toFloat() / videoHeight
            return when {
                ratio < MIN_RATIO -> MIN_NUMERATOR to MIN_DENOMINATOR
                ratio > MAX_RATIO -> MIN_DENOMINATOR to MIN_NUMERATOR
                else -> videoWidth to videoHeight
            }
        }

    private companion object {
        /** Android's documented limits, as jellyfin-android's `PIP_MIN_RATIONAL` spells them. */
        const val MIN_NUMERATOR = 100
        const val MIN_DENOMINATOR = 239
        const val MIN_RATIO = MIN_NUMERATOR.toFloat() / MIN_DENOMINATOR
        const val MAX_RATIO = MIN_DENOMINATOR.toFloat() / MIN_NUMERATOR
    }
}
