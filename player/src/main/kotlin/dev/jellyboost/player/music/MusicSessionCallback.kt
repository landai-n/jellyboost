package dev.jellyboost.player.music

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.player.R
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The buttons must carry **custom** session commands, not `COMMAND_SET_SHUFFLE_MODE`/`COMMAND_SET_REPEAT_MODE`:
 * a player command moves the player directly, so the mode never reaches [MusicController] or the server.
 *
 * A custom-command button is only offered to a controller that was granted the command — see [onConnect].
 */
@UnstableApi
@Singleton
class MusicSessionCallback
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val controller: MusicController,
    ) : MediaSession.Callback {
        /**
         * The media notification connects as a controller like any other: without the grant its buttons are
         * filtered out as unavailable before being drawn.
         *
         * The two player commands are stripped so an external controller (Assistant, AVRCP) cannot flip the
         * player behind [MusicController]'s back; such requests become no-ops.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default =
                MediaSession.ConnectionResult.accept(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                )
            return MediaSession.ConnectionResult
                .accept(
                    default.availableSessionCommands
                        .buildUpon()
                        .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                        .build(),
                    default.availablePlayerCommands
                        .buildUpon()
                        .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                        .remove(Player.COMMAND_SET_REPEAT_MODE)
                        .build(),
                )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> {
                    val active = this.controller.state.value as? MusicPlaybackState.Active
                    this.controller.setShuffle(!(active?.shuffleEnabled ?: false))
                }

                ACTION_CYCLE_REPEAT -> this.controller.cycleRepeat()

                else -> {
                    Timber.d("Unknown media-session custom command %s", customCommand.customAction)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Empty for a parked queue as well as an idle one: parked means the media session now belongs to a film,
         * whose notification must not gain the queue's shuffle/repeat buttons.
         *
         * Secondary slots only — a mode toggle must not displace the transport's own buttons.
         */
        fun buttonsFor(state: MusicPlaybackState): List<CommandButton> {
            val active = state as? MusicPlaybackState.Active ?: return emptyList()
            if (active.parked) return emptyList()
            val shuffleIcon =
                if (active.shuffleEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
            val shuffleLabel =
                if (active.shuffleEnabled) {
                    R.string.music_notification_shuffle_on
                } else {
                    R.string.music_notification_shuffle_off
                }
            return listOf(
                CommandButton
                    .Builder(shuffleIcon)
                    .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .setDisplayName(context.getString(shuffleLabel))
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY)
                    .build(),
                CommandButton
                    .Builder(active.repeatMode.icon)
                    .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                    .setDisplayName(context.getString(active.repeatMode.label))
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                    .build(),
            )
        }

        private val MusicRepeatMode.icon: Int
            get() =
                when (this) {
                    MusicRepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF
                    MusicRepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                    MusicRepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                }

        private val MusicRepeatMode.label: Int
            get() =
                when (this) {
                    MusicRepeatMode.OFF -> R.string.music_notification_repeat_off
                    MusicRepeatMode.ALL -> R.string.music_notification_repeat_all
                    MusicRepeatMode.ONE -> R.string.music_notification_repeat_one
                }

        companion object {
            const val ACTION_TOGGLE_SHUFFLE = "dev.jellyboost.music.TOGGLE_SHUFFLE"
            const val ACTION_CYCLE_REPEAT = "dev.jellyboost.music.CYCLE_REPEAT"
        }
    }
