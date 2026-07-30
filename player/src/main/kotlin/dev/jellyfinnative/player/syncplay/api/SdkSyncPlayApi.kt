package dev.jellyfinnative.player.syncplay.api

import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyfinnative.player.syncplay.model.TimeSyncSample
import dev.jellyfinnative.player.syncplay.toSample
import dev.jellyfinnative.player.syncplay.toSdk
import dev.jellyfinnative.player.syncplay.toSdkWallClock
import dev.jellyfinnative.player.syncplay.toSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.timeSyncApi
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.IgnoreWaitRequestDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.MovePlaylistItemRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.NextItemRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.PreviousItemRequestDto
import org.jellyfin.sdk.model.api.QueueRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.RemoveFromPlaylistRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SetPlaylistItemRequestDto
import org.jellyfin.sdk.model.api.SetRepeatModeRequestDto
import org.jellyfin.sdk.model.api.SetShuffleModeRequestDto
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncPlayApi] backed by jellyfin-sdk-kotlin's `syncPlayApi` and `timeSyncApi`.
 *
 * Like `SdkPlayerApi`, every call hops onto the IO dispatcher — the SDK's operations are `suspend`
 * but block on OkHttp underneath, and these are issued from the controller's own scope and from
 * player callbacks.
 *
 * The [clock] is the injected one (`UserDataModule.provideClock`) rather than
 * `System.currentTimeMillis`, so the time-sync exchange in [sampleServerTime] can be driven from a
 * fixed clock in tests.
 */
@Singleton
@Suppress("TooManyFunctions") // Mirrors the SyncPlayApi seam; see the interface.
internal class SdkSyncPlayApi
    @Inject
    constructor(
        private val apiClient: ApiClient,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : SyncPlayApi {
        override suspend fun getGroups(): List<SyncPlayGroupSummary> =
            withContext(ioDispatcher) {
                apiClient.syncPlayApi
                    .syncPlayGetGroups()
                    .content
                    .map { it.toSummary() }
            }

        override suspend fun createGroup(name: String): SyncPlayGroupSummary =
            withContext(ioDispatcher) {
                apiClient.syncPlayApi
                    .syncPlayCreateGroup(NewGroupRequestDto(groupName = name))
                    .content
                    .toSummary()
            }

        override suspend fun joinGroup(groupId: UUID) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId = groupId))
            }
        }

        override suspend fun leaveGroup() {
            withContext(ioDispatcher) { apiClient.syncPlayApi.syncPlayLeaveGroup() }
        }

        override suspend fun reportBuffering(
            at: Instant,
            positionTicks: Long,
            isPlaying: Boolean,
            playlistItemId: UUID,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayBuffering(
                    BufferRequestDto(
                        `when` = at.toSdkWallClock(),
                        positionTicks = positionTicks,
                        isPlaying = isPlaying,
                        playlistItemId = playlistItemId,
                    ),
                )
            }
        }

        override suspend fun reportReady(
            at: Instant,
            positionTicks: Long,
            isPlaying: Boolean,
            playlistItemId: UUID,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayReady(
                    ReadyRequestDto(
                        `when` = at.toSdkWallClock(),
                        positionTicks = positionTicks,
                        isPlaying = isPlaying,
                        playlistItemId = playlistItemId,
                    ),
                )
            }
        }

        override suspend fun reportPing(pingMillis: Long) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayPing(PingRequestDto(ping = pingMillis))
            }
        }

        override suspend fun setIgnoreWait(ignoreWait: Boolean) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySetIgnoreWait(IgnoreWaitRequestDto(ignoreWait = ignoreWait))
            }
        }

        override suspend fun requestPause() {
            withContext(ioDispatcher) { apiClient.syncPlayApi.syncPlayPause() }
        }

        override suspend fun requestUnpause() {
            withContext(ioDispatcher) { apiClient.syncPlayApi.syncPlayUnpause() }
        }

        override suspend fun requestSeek(positionTicks: Long) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks = positionTicks))
            }
        }

        override suspend fun requestStop() {
            withContext(ioDispatcher) { apiClient.syncPlayApi.syncPlayStop() }
        }

        override suspend fun requestNextItem(playlistItemId: UUID) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayNextItem(NextItemRequestDto(playlistItemId = playlistItemId))
            }
        }

        override suspend fun requestPreviousItem(playlistItemId: UUID) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayPreviousItem(PreviousItemRequestDto(playlistItemId = playlistItemId))
            }
        }

        override suspend fun setPlaylistItem(playlistItemId: UUID) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySetPlaylistItem(
                    SetPlaylistItemRequestDto(playlistItemId = playlistItemId),
                )
            }
        }

        override suspend fun setNewQueue(
            itemIds: List<UUID>,
            playingItemPosition: Int,
            startPositionTicks: Long,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySetNewQueue(
                    PlayRequestDto(
                        playingQueue = itemIds,
                        playingItemPosition = playingItemPosition,
                        startPositionTicks = startPositionTicks,
                    ),
                )
            }
        }

        override suspend fun addToQueue(
            itemIds: List<UUID>,
            mode: SyncPlayQueueMode,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayQueue(QueueRequestDto(itemIds = itemIds, mode = mode.toSdk()))
            }
        }

        override suspend fun movePlaylistItem(
            playlistItemId: UUID,
            newIndex: Int,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayMovePlaylistItem(
                    MovePlaylistItemRequestDto(playlistItemId = playlistItemId, newIndex = newIndex),
                )
            }
        }

        override suspend fun removeFromPlaylist(
            playlistItemIds: List<UUID>,
            clearPlaylist: Boolean,
            clearPlayingItem: Boolean,
        ) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlayRemoveFromPlaylist(
                    RemoveFromPlaylistRequestDto(
                        playlistItemIds = playlistItemIds,
                        clearPlaylist = clearPlaylist,
                        clearPlayingItem = clearPlayingItem,
                    ),
                )
            }
        }

        override suspend fun setShuffleMode(mode: SyncPlayShuffleMode) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySetShuffleMode(SetShuffleModeRequestDto(mode = mode.toSdk()))
            }
        }

        override suspend fun setRepeatMode(mode: SyncPlayRepeatMode) {
            withContext(ioDispatcher) {
                apiClient.syncPlayApi.syncPlaySetRepeatMode(SetRepeatModeRequestDto(mode = mode.toSdk()))
            }
        }

        override suspend fun sampleServerTime(): TimeSyncSample =
            withContext(ioDispatcher) {
                val requestSent = clock.instant()
                val response = apiClient.timeSyncApi.getUtcTime().content
                response.toSample(requestSent = requestSent, responseReceived = clock.instant())
            }
    }
