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
 * Shuffle and repeat in the media notification and on the lock screen.
 *
 * ### What Media3 1.9.0 actually renders (verified against the `media3-session` 1.9.0 artifact)
 * `DefaultMediaNotificationProvider.getMediaButtons(session, playerCommands,
 * mediaButtonPreferences, showPauseButton)` takes the session's **media button preferences** and
 * lays them out around the transport controls, and `CommandButton` in 1.9.0 ships predefined icons
 * for exactly this pair — `ICON_SHUFFLE_ON`/`ICON_SHUFFLE_OFF` and
 * `ICON_REPEAT_OFF`/`ICON_REPEAT_ALL`/`ICON_REPEAT_ONE` — so no drawable of ours is involved and
 * the system draws them in its own style. A button backed by a **custom** session command is only
 * offered to a controller that was granted that command, which is what [onConnect] does.
 *
 * That is why this is a callback and a button list rather than a notification of our own: the
 * whole feature is two `SessionCommand`s, two `CommandButton`s and a dispatch back into
 * [MusicController].
 *
 * ### Why the buttons carry custom commands rather than player commands
 * `Player.COMMAND_SET_SHUFFLE_MODE` and `COMMAND_SET_REPEAT_MODE` would move the *player* directly,
 * behind the controller's back — the shuffle flag would flip without `PlaybackOrder.SHUFFLE` ever
 * reaching the server, and the queue state the mini-player draws would go stale. Routing both
 * through [MusicController] keeps one owner of the mode, exactly as
 * `SyncPlayAwareForwardingPlayer` keeps one owner of transport.
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
         * Grants the two custom commands on top of everything the session offers by default —
         * minus the two *player* commands they exist to replace.
         *
         * The media notification connects as a controller like any other, so without the grant its
         * buttons would be filtered out as unavailable before they were ever drawn.
         *
         * `COMMAND_SET_SHUFFLE_MODE` and `COMMAND_SET_REPEAT_MODE` are stripped from the granted
         * player commands because the modes are owned by [MusicController] (see the class KDoc):
         * an external controller — Assistant, Bluetooth AVRCP, a companion app — holding the
         * player command would flip the player directly, behind the controller's back, with no
         * `PlaybackOrder`/`RepeatMode` ever reaching the server and the queue state going stale.
         * The notification's own buttons never used them (they carry the custom session commands
         * above). External shuffle requests land as a no-op until a follow-up routes them through
         * the controller.
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
         * The buttons for [state], or an empty list when nothing musical is loaded — which is what
         * a film's session gets, leaving the video notification exactly as it was.
         *
         * A **parked** queue gets none either: the state stays `Active` so the mini-player keeps
         * its resume affordance, but the media session now belongs to the film, and the queue's
         * shuffle/repeat buttons stamped onto the film's notification is exactly the confusion
         * the `parked` flag exists to prevent.
         *
         * Both sit in the secondary slots: the central play/pause and the primary previous/next
         * belong to the transport and must not be displaced by a mode toggle.
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
