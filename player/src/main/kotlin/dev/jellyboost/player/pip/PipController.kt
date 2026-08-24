package dev.jellyboost.player.pip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picture-in-picture is an activity capability whose conditions are the player's, so the two publish
 * to each other here: the player screen through [setPlayerState], `MainActivity` through
 * [setInPictureInPicture].
 */
@Singleton
class PipController
    @Inject
    constructor() {
        private val _state = MutableStateFlow(PipState())

        val state: StateFlow<PipState> = _state.asStateFlow()

        /**
         * @param active must already fold in every condition (route composed, playing, preference on);
         *   the activity does not re-check.
         * @param videoWidth / @param videoHeight zero until the first frame is decoded, which the
         *   activity treats as "no hint".
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

        fun setInPictureInPicture(inPictureInPicture: Boolean) {
            _state.update { it.copy(isInPictureInPicture = inPictureInPicture) }
        }

        internal fun clear() {
            _state.value = PipState()
        }
    }

data class PipState(
    val canEnter: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isInPictureInPicture: Boolean = false,
) {
    /**
     * Clamped because `PictureInPictureParams.setAspectRatio` throws outside 1:2.39 … 2.39:1, and a
     * 2.76:1 film would take the activity down as the user presses Home. `null` = size unknown.
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
