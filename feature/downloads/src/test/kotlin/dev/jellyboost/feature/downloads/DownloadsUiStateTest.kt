package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.model.DownloadKind
import dev.jellyboost.data.downloads.model.StorageUsage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

// One state class's derivations over one set of fixtures; splitting the cases across files would
// duplicate those fixtures rather than shorten anything.
@Suppress("LargeClass")
class DownloadsUiStateTest {
    @Test
    fun `an empty queue has nothing to sum and reads as idle`() {
        val state = DownloadsUiState(queue = emptyList())

        state.queueStats shouldBe QueueStats(itemCount = 0, remainingBytes = 0L, bytesPerSecond = 0L, etaSeconds = null)
        state.queueStats.isIdle shouldBe true
    }

    @Test
    fun `item count is the queue's own size`() {
        val state = DownloadsUiState(queue = listOf(queued("1"), queued("2"), queued("3")))

        state.queueStats.itemCount shouldBe 3
    }

    @Test
    fun `remaining bytes sums displayTotalBytes minus bytesDownloaded across every row`() {
        val a = queued("1", bytesDownloaded = 100L, bytesTotal = 300L) // 200 remaining
        val b = queued("2", bytesDownloaded = 50L, bytesTotal = 150L) // 100 remaining
        val state = DownloadsUiState(queue = listOf(a, b))

        state.queueStats.remainingBytes shouldBe 300L
    }

    @Test
    fun `a row already past its own total contributes nothing negative`() {
        // `displayTotalBytes` clamps into [bytesDownloaded, ceiling], so the sum of differences
        // must not go negative even at that boundary.
        val atItsOwnTotal = queued("1", bytesDownloaded = 300L, bytesTotal = 300L)
        val state = DownloadsUiState(queue = listOf(atItsOwnTotal))

        state.queueStats.remainingBytes shouldBe 0L
    }

    @Test
    fun `bytes per second sums every row's current speed, keyed by item id`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1"), queued("2"), queued("3")),
                speeds = mapOf("1" to 1_000_000L, "2" to 2_500_000L),
                // "3" has no entry yet: contributes zero rather than crashing.
            )

        state.queueStats.bytesPerSecond shouldBe 3_500_000L
    }

    @Test
    fun `a speed entry for a row not on the queue is never summed`() {
        // `speeds` is keyed across the whole session, not scoped to the queue, so a stale entry for
        // a finished row must not inflate the aggregate.
        val state =
            DownloadsUiState(
                queue = listOf(queued("1")),
                speeds = mapOf("1" to 1_000_000L, "stale" to 9_000_000L),
            )

        state.queueStats.bytesPerSecond shouldBe 1_000_000L
    }

    @Test
    fun `zero total speed means idle and no ETA, rather than a division by zero`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 100L, bytesTotal = 300L)),
                speeds = emptyMap(),
            )

        state.queueStats.isIdle shouldBe true
        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `nothing remaining means no ETA even while transferring`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 300L, bytesTotal = 300L)),
                speeds = mapOf("1" to 1_000_000L),
            )

        state.queueStats.isIdle shouldBe false
        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `an exact division gives a whole number of seconds, ceiling division shared with a row's own ETA`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 400L, bytesTotal = 500L)), // 100 remaining
                speeds = mapOf("1" to 10L),
            )

        state.queueStats.etaSeconds shouldBe 10L
    }

    @Test
    fun `a division with a remainder rounds up, never short`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 399L, bytesTotal = 500L)), // 101 remaining
                speeds = mapOf("1" to 10L),
            )

        state.queueStats.etaSeconds shouldBe 11L
    }

    @Test
    fun `an aggregate estimate beyond 24 hours is guarded out as guesswork, same threshold as a row's own ETA`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 0L, bytesTotal = 86_401L)),
                speeds = mapOf("1" to 1L),
            )

        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `an aggregate estimate exactly at the 24-hour guard is still shown`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 0L, bytesTotal = 86_400L)),
                speeds = mapOf("1" to 1L),
            )

        state.queueStats.etaSeconds shouldBe 86_400L
    }

    // ---- The precomputed chrome -----------------------------------------------------------------

    @Test
    fun `downloaded bytes are summed once, across every section`() {
        val state =
            DownloadsUiState(
                downloaded =
                    listOf(
                        episode("1", "Chestnut", series = "Westworld", onDisk = 100L),
                        episode("2", "The Original", series = "Westworld", onDisk = 200L),
                        film("3", "Dune", onDisk = 700L),
                    ).toSections(),
            )

        state.downloadedBytes shouldBe 1_000L
    }

    @Test
    fun `a group's own size is the sum of its rows`() {
        DownloadGroup(
            key = "SERIES:Westworld",
            title = "Westworld",
            items = listOf(finished("1", 100L), finished("2", 250L)),
            isCollapsible = true,
        ).bytesOnDisk shouldBe 350L
    }

    // ---- the three sections ---------------------------------------------------------------------

    @Test
    fun `sections are always ordered movies, series, music`() {
        val sections =
            listOf(
                track("1", "Dreams", album = "Rumours"),
                episode("2", "Chestnut", series = "Westworld"),
                film("3", "Dune"),
            ).toSections()

        sections.map { it.kind } shouldContainExactly
            listOf(DownloadKind.MOVIE, DownloadKind.SERIES, DownloadKind.MUSIC)
    }

    @Test
    fun `a kind with nothing downloaded gets no section at all`() {
        val sections = listOf(film("1", "Dune"), track("2", "Dreams", album = "Rumours")).toSections()

        sections.map { it.kind } shouldContainExactly listOf(DownloadKind.MOVIE, DownloadKind.MUSIC)
    }

    @Test
    fun `one kind on the tab needs no label above it`() {
        val state = DownloadsUiState(downloaded = listOf(film("1", "Dune"), film("2", "Arrival")).toSections())

        state.downloaded.single().kind shouldBe DownloadKind.MOVIE
        state.showKindHeaders shouldBe false
    }

    @Test
    fun `a second kind brings the labels back`() {
        val state =
            DownloadsUiState(
                downloaded = listOf(film("1", "Dune"), episode("2", "Chestnut", series = "Westworld")).toSections(),
            )

        state.showKindHeaders shouldBe true
    }

    @Test
    fun `every film shares one group that never folds`() {
        val sections = listOf(film("1", "Dune"), film("2", "Arrival")).toSections()

        val films = sections.single().groups.single()
        films.isCollapsible shouldBe false
        films.title shouldBe ""
        films.items.map { it.itemId } shouldContainExactly listOf("2", "1")
    }

    @Test
    fun `a series and an album sharing a name stay two groups`() {
        // Mutation check: keying groups on the heading text alone merges them into one.
        val sections =
            listOf(
                episode("1", "Chestnut", series = "Rumours"),
                track("2", "Dreams", album = "Rumours"),
            ).toSections()

        sections.map { it.kind } shouldContainExactly listOf(DownloadKind.SERIES, DownloadKind.MUSIC)
        sections.flatMap { it.groups }.map { it.key } shouldContainExactly
            listOf("SERIES:Rumours", "MUSIC:Rumours")
    }

    @Test
    fun `two shows of the same name stay two groups, told apart by their heading id`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val sections =
            listOf(
                episode("1", "Chestnut", series = "Westworld", groupId = first),
                episode("2", "The Original", series = "Westworld", groupId = second),
            ).toSections()

        val groups = sections.single().groups
        groups.map { it.key } shouldContainExactly listOf(first.toString(), second.toString())
        groups.map { it.title } shouldContainExactly listOf("Westworld", "Westworld")
    }

    @Test
    fun `a track with no album lands in the music catch-all, which never folds`() {
        val sections =
            listOf(
                track("1", "Dreams", album = "Rumours"),
                track("2", "Stray", album = null),
            ).toSections()

        val groups = sections.single().groups
        groups.map { it.title } shouldContainExactly listOf("Rumours", "")
        groups.last().isCollapsible shouldBe false
        groups.last().items.map { it.itemId } shouldContainExactly listOf("2")
    }

    @Test
    fun `a legacy row with no type but a series name still appears, under that series`() {
        // Nothing but the denormalised heading survives a wiped cache on a pre-column row, and no
        // download may drop out of the only list it is deletable from.
        val sections = listOf(episode("1", "Chestnut", series = "Westworld", type = null)).toSections()

        val group = sections.single().groups.single()
        sections.single().kind shouldBe DownloadKind.SERIES
        group.key shouldBe "SERIES:Westworld"
        group.items.map { it.itemId } shouldContainExactly listOf("1")
    }

    // ---- what an album's header carries ----------------------------------------------------------

    @Test
    fun `an album header carries its artist and its cover`() {
        val sections =
            listOf(
                track("1", "Dreams", album = "Rumours").copy(
                    artistName = "Fleetwood Mac",
                    item = albumArt("https://example.invalid/rumours.jpg"),
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Fleetwood Mac"
        group.artworkUrl shouldBe "https://example.invalid/rumours.jpg"
    }

    @Test
    fun `one track missing its artist or cover does not blank the album's header`() {
        // Metadata lands per row, so the first row is routinely the one still without it.
        val sections =
            listOf(
                track("1", "Dreams", album = "Rumours"),
                track("2", "Go Your Own Way", album = "Rumours").copy(
                    artistName = "Fleetwood Mac",
                    item = albumArt("https://example.invalid/rumours.jpg"),
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Fleetwood Mac"
        group.artworkUrl shouldBe "https://example.invalid/rumours.jpg"
    }

    @Test
    fun `a series header takes neither the artist column nor the row's own artwork`() {
        // The two the album header reads are wrong answers here: an episode is credited to nobody,
        // and its `primaryImageUrl` is the still that belongs to its own row.
        val sections =
            listOf(
                episode("1", "Chestnut", series = "Westworld").copy(
                    artistName = "Fleetwood Mac",
                    item = albumArt("https://example.invalid/chestnut.jpg"),
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe null
        group.artworkUrl shouldBe null
    }

    @Test
    fun `an album nobody is credited on keeps a one-line header`() {
        val sections = listOf(track("1", "Dreams", album = "Rumours")).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe null
        group.artworkUrl shouldBe null
    }

    // ---- what a series' header carries -----------------------------------------------------------

    @Test
    fun `a series header carries its season and that season's poster`() {
        val sections =
            listOf(
                episode(
                    "1",
                    "Chestnut",
                    series = "Westworld",
                    season = "Season 1",
                    seasonNumber = 1,
                    seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
                ),
            ).toSections()

        val group = sections.single().groups.single()
        // The server's own wording, not a "Season $n" composed here and owed 69 translations.
        group.subtitle shouldBe "Season 1"
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
    }

    @Test
    fun `the header's poster is the season's, and the row keeps its own still`() {
        val sections =
            listOf(
                episode(
                    "1",
                    "Chestnut",
                    series = "Westworld",
                    season = "Season 1",
                    seasonNumber = 1,
                    imageUrl = "https://example.invalid/chestnut.jpg",
                    seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
        // Why series rows are not stripped of their artwork the way an album's tracks are.
        group.items
            .single()
            .item
            ?.primaryImageUrl shouldBe "https://example.invalid/chestnut.jpg"
    }

    @Test
    fun `several seasons under one series are joined in season order`() {
        // The group is keyed by the *series*, so one header can be asked to stand for several
        // seasons. Sorted by title, these two arrive in the reverse of their season order.
        val sections =
            listOf(
                episode(
                    "1",
                    "Chestnut",
                    series = "Westworld",
                    season = "Season 2",
                    seasonNumber = 2,
                    seasonArtworkUrl = "https://example.invalid/westworld-s2.jpg",
                ),
                episode(
                    "2",
                    "The Bicameral Mind",
                    series = "Westworld",
                    season = "Season 1",
                    seasonNumber = 1,
                    seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Season 1 · Season 2"
        // One thumbnail cannot stand for two, so the choice is the lowest-numbered season — never
        // whichever the list opens with, which the title sort makes a different season entirely.
        group.items.first().itemId shouldBe "1"
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
    }

    @Test
    fun `a season the server left unnumbered is named after the numbered ones`() {
        val sections =
            listOf(
                episode("1", "A Special", series = "Westworld", season = "Specials"),
                episode("2", "Chestnut", series = "Westworld", season = "Season 1", seasonNumber = 1),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Season 1 · Specials"
    }

    @Test
    fun `one episode still missing its season does not blank the series header`() {
        // Metadata lands per row, so the first row is routinely the one still without it.
        val sections =
            listOf(
                episode("1", "Chestnut", series = "Westworld"),
                episode(
                    "2",
                    "The Bicameral Mind",
                    series = "Westworld",
                    season = "Season 1",
                    seasonNumber = 1,
                    seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Season 1"
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
    }

    @Test
    fun `a season's poster comes from the first row of that season that has one`() {
        // The season join answers per row, so two rows of one season disagree about its poster
        // whenever one of their own items is still unparsed.
        val sections =
            listOf(
                episode("1", "Chestnut", series = "Westworld", season = "Season 1", seasonNumber = 1),
                episode(
                    "2",
                    "The Bicameral Mind",
                    series = "Westworld",
                    season = "Season 1",
                    seasonNumber = 1,
                    seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
                ),
            ).toSections()

        val group = sections.single().groups.single()
        group.items.first().itemId shouldBe "1"
        group.subtitle shouldBe "Season 1"
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
    }

    @Test
    fun `a season whose row has left the cache costs the poster, not the season line`() {
        val sections =
            listOf(episode("1", "Chestnut", series = "Westworld", season = "Season 1", seasonNumber = 1)).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe "Season 1"
        group.artworkUrl shouldBe null
    }

    @Test
    fun `a series no row's season reached keeps the one-line header it had before`() {
        val sections = listOf(episode("1", "Chestnut", series = "Westworld")).toSections()

        val group = sections.single().groups.single()
        group.subtitle shouldBe null
        group.artworkUrl shouldBe null
    }

    // ---- the queue's own sections ----------------------------------------------------------------

    @Test
    fun `the queue takes the same fixed order as the downloaded tab`() {
        val state =
            DownloadsUiState(
                queue =
                    listOf(
                        queuedTrack("1", album = "Rumours"),
                        queuedEpisode("2", series = "Westworld"),
                        queued("3"),
                    ),
            )

        state.queueSections.map { it.kind } shouldContainExactly
            listOf(DownloadKind.MOVIE, DownloadKind.SERIES, DownloadKind.MUSIC)
    }

    @Test
    fun `a queue section keeps its rows in the order the queue put them`() {
        // The list arrives sorted by `queuePosition`; grouping must not re-sort it, or the arrows
        // would move rows against the order they are drawn in.
        val state =
            DownloadsUiState(
                queue =
                    listOf(
                        queuedEpisode("1", series = "Westworld"),
                        queued("2"),
                        queuedEpisode("3", series = "Westworld"),
                    ),
            )

        state.queueSections
            .single { it.kind == DownloadKind.SERIES }
            .items
            .map { it.itemId } shouldContainExactly listOf("1", "3")
    }

    @Test
    fun `one kind in the queue needs no label above it`() {
        val state = DownloadsUiState(queue = listOf(queued("1"), queued("2")))

        state.queueSections.single().kind shouldBe DownloadKind.MOVIE
        state.showQueueKindHeaders shouldBe false
    }

    @Test
    fun `a second kind in the queue brings the labels back`() {
        val state = DownloadsUiState(queue = listOf(queued("1"), queuedTrack("2", album = "Rumours")))

        state.showQueueKindHeaders shouldBe true
    }

    @Test
    fun `an empty queue has no sections and no labels`() {
        val state = DownloadsUiState(queue = emptyList())

        state.queueSections.shouldBeEmpty()
        state.showQueueKindHeaders shouldBe false
    }

    @Test
    fun `the flat queue is untouched, since the stats and the reorder arithmetic read it`() {
        val rows = listOf(queuedTrack("1", album = "Rumours"), queued("2"))
        val state = DownloadsUiState(queue = rows)

        state.queue shouldContainExactly rows
        state.queueStats.itemCount shouldBe 2
    }

    // ---- folding --------------------------------------------------------------------------------

    @Test
    fun `a fresh screen has every group folded`() {
        DownloadsUiState().expandedGroups shouldBe emptySet()
    }

    @Test
    fun `the size on the storage header does not depend on what is unfolded`() {
        val rows =
            listOf(
                episode("1", "Chestnut", series = "Westworld", onDisk = 100L),
                track("2", "Dreams", album = "Rumours", onDisk = 200L),
            )
        val folded = DownloadsUiState(downloaded = rows.toSections())
        val unfolded =
            DownloadsUiState(
                downloaded = rows.toSections(),
                expandedGroups = setOf("SERIES:Westworld", "MUSIC:Rumours"),
            )

        folded.downloadedBytes shouldBe 300L
        unfolded.downloadedBytes shouldBe folded.downloadedBytes
    }

    @Test
    fun `a row is found by id wherever its section is`() {
        val sections =
            listOf(film("1", "Dune"), track("2", "Dreams", album = "Rumours")).toSections()

        sections.itemOrNull("2")?.title shouldBe "Dreams"
        sections.itemOrNull("nobody") shouldBe null
    }

    @Test
    fun `the storage figure is floored at what the downloaded tab accounts for`() {
        // The walk runs on a tick, so a just-landed download must not make the screen claim less
        // used space than its own rows add up to.
        val summary =
            storageSummary(storage = StorageUsage(usedBytes = 400L, availableBytes = 600L), downloadedBytes = 900L)

        summary.usedBytes shouldBe 900L
        summary.availableBytes shouldBe 600L
        // The denominator stays used-*as-walked* plus free, or the bar's end moves under it.
        summary.totalBytes shouldBe 1_000L
    }

    @Test
    fun `a walk ahead of the tab wins, since it sees files the tab does not`() {
        val summary =
            storageSummary(storage = StorageUsage(usedBytes = 800L, availableBytes = 200L), downloadedBytes = 100L)

        summary.usedBytes shouldBe 800L
        summary.totalBytes shouldBe 1_000L
    }

    @Test
    fun `a not-yet-known total gives an empty bar rather than a division by zero`() {
        usageFraction(used = 500L, total = 0L) shouldBe 0f
    }

    @Test
    fun `a fraction past its own end is clamped`() {
        usageFraction(used = 1_500L, total = 1_000L) shouldBe 1f
    }

    @Test
    fun `the chrome carries the queue's progress, so the wide panel does not re-sum it per frame`() {
        val state =
            DownloadsUiState(
                queue =
                    listOf(
                        queued("1", bytesDownloaded = 250L, bytesTotal = 500L),
                        queued("2", bytesDownloaded = 250L, bytesTotal = 1_500L),
                    ),
            )

        state.chrome.queueProgress shouldBe 0.25f
        state.chrome.hasQueue shouldBe true
        state.chrome.queueStats shouldBe state.queueStats
    }

    @Test
    fun `an empty queue reports no progress and no queue`() {
        val state = DownloadsUiState(queue = emptyList())

        state.chrome.queueProgress shouldBe 0f
        state.chrome.hasQueue shouldBe false
    }

    @Test
    fun `the chrome carries the bulk buttons' own enablement, and nothing else about the queue`() {
        val state =
            DownloadsUiState(
                selectedTab = DownloadsTab.QUEUE,
                queue = listOf(queued("1"), paused("2")),
                wifiOnly = false,
            )

        state.chrome.selectedTab shouldBe DownloadsTab.QUEUE
        state.chrome.canPauseAll shouldBe true
        state.chrome.canResumeAll shouldBe true
        // The raw preference is gone from the chrome — Settings owns the switch, and all the chrome
        // needs is the derived verdict.
        state.chrome.queuePausedForWifi shouldBe false
    }

    // ---- waiting for Wi-Fi -----------------------------------------------------------------------

    @Test
    fun `Wi-Fi only, a metered network and a queued row is the one combination that pauses for Wi-Fi`() {
        val state =
            DownloadsUiState(
                queue = listOf(status("1", DownloadStatus.QUEUED)),
                wifiOnly = true,
                onMeteredNetwork = true,
            )

        state.queuePausedForWifi shouldBe true
        state.chrome.queuePausedForWifi shouldBe true
    }

    @Test
    fun `a transfer already in flight counts too`() {
        val state =
            DownloadsUiState(
                queue = listOf(status("1", DownloadStatus.DOWNLOADING)),
                wifiOnly = true,
                onMeteredNetwork = true,
            )

        state.queuePausedForWifi shouldBe true
    }

    @Test
    fun `an empty queue is not waiting for anything`() {
        val state = DownloadsUiState(queue = emptyList(), wifiOnly = true, onMeteredNetwork = true)

        state.queuePausedForWifi shouldBe false
    }

    @Test
    fun `a queue of only paused and failed rows is the user's doing, not the network's`() {
        val state =
            DownloadsUiState(
                queue = listOf(status("1", DownloadStatus.PAUSED), status("2", DownloadStatus.ERROR)),
                wifiOnly = true,
                onMeteredNetwork = true,
            )

        state.queuePausedForWifi shouldBe false
    }

    @Test
    fun `an unmetered network is not the reason, whatever the preference says`() {
        val state =
            DownloadsUiState(
                queue = listOf(status("1", DownloadStatus.QUEUED)),
                wifiOnly = true,
                onMeteredNetwork = false,
            )

        state.queuePausedForWifi shouldBe false
    }

    @Test
    fun `with Wi-Fi only off, a metered network holds nothing back`() {
        val state =
            DownloadsUiState(
                queue = listOf(status("1", DownloadStatus.QUEUED)),
                wifiOnly = false,
                onMeteredNetwork = true,
            )

        state.queuePausedForWifi shouldBe false
    }

    @Test
    fun `only QUEUED and DOWNLOADING rows count as held back by the constraint`() {
        // Pinned directly, because the status set is the whole claim the notice makes: a download
        // stopped by leaving Wi-Fi goes back to QUEUED, so PAUSED and ERROR are decisions of the
        // user's or the server's and must not be counted as waiting for a network.
        val everyStatus = DownloadStatus.entries.map { status(it.name, it) }
        val state = DownloadsUiState(queue = everyStatus)

        state.wifiBlockedCount shouldBe 2
    }

    @Test
    fun `finished rows never reach the count, because they never reach the queue`() {
        val state = DownloadsUiState(queue = listOf(status("1", DownloadStatus.DOWNLOADED)))

        state.wifiBlockedCount shouldBe 0
    }

    private fun film(
        itemId: String,
        title: String,
        onDisk: Long = 0L,
    ) = downloadItem(
        itemId = itemId,
        title = title,
        status = DownloadStatus.DOWNLOADED,
        bytesOnDisk = onDisk,
        itemType = ItemType.MOVIE,
    )

    @Suppress("LongParameterList")
    private fun episode(
        itemId: String,
        title: String,
        series: String,
        onDisk: Long = 0L,
        type: ItemType? = ItemType.EPISODE,
        groupId: UUID? = null,
        season: String? = null,
        seasonNumber: Int? = null,
        imageUrl: String? = null,
        seasonArtworkUrl: String? = null,
    ) = downloadItem(
        itemId = itemId,
        title = title,
        seriesName = series,
        status = DownloadStatus.DOWNLOADED,
        bytesOnDisk = onDisk,
        itemType = type,
        groupId = groupId,
        // No cached item at all is the case a wiped cache leaves, and the default here.
        item =
            if (season == null && imageUrl == null) {
                null
            } else {
                JellyfinItem(
                    id = itemId,
                    name = title,
                    type = ItemType.EPISODE,
                    primaryImageUrl = imageUrl,
                    seasonName = season,
                    parentIndexNumber = seasonNumber,
                )
            },
        seasonArtworkUrl = seasonArtworkUrl,
    )

    private fun track(
        itemId: String,
        title: String,
        album: String?,
        onDisk: Long = 0L,
    ) = downloadItem(
        itemId = itemId,
        title = title,
        status = DownloadStatus.DOWNLOADED,
        bytesOnDisk = onDisk,
        itemType = ItemType.AUDIO,
        albumName = album,
    )

    private fun finished(
        itemId: String,
        bytesOnDisk: Long,
    ) = downloadItem(
        itemId = itemId,
        title = "Title $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = bytesOnDisk,
        bytesTotal = bytesOnDisk,
        bytesOnDisk = bytesOnDisk,
        queuePosition = 0,
    )

    private fun albumArt(url: String) =
        JellyfinItem(id = "art", name = "Rumours", type = ItemType.MUSIC_ALBUM, primaryImageUrl = url)

    private fun queuedEpisode(
        itemId: String,
        series: String,
    ) = queued(itemId).copy(seriesName = series, itemType = ItemType.EPISODE)

    private fun queuedTrack(
        itemId: String,
        album: String,
    ) = queued(itemId).copy(albumName = album, itemType = ItemType.AUDIO)

    private fun paused(itemId: String) = queued(itemId).copy(status = DownloadStatus.PAUSED)

    private fun status(
        itemId: String,
        status: DownloadStatus,
    ) = queued(itemId).copy(status = status)

    @Suppress("LongParameterList")
    private fun queued(
        itemId: String,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 1_000L,
    ) = downloadItem(
        itemId = itemId,
        title = "Title $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADING,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        bytesOnDisk = 0L,
        queuePosition = 0,
    )
}
