package dev.jellyfinnative.data.downloads.plan

import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DownloadPaths].
 *
 * Naming looks trivial and is not: these strings become directories on an exFAT SD card, they are
 * the only thing the delete cascade has to find the files by once the item row is gone, and a
 * colon in a film title is enough to make `mkdirs()` fail on some devices and not others.
 */
class DownloadPathsTest {
    // ---- directory names ------------------------------------------------------------------------

    @Test
    fun `a movie directory is title and year`() {
        DownloadPaths.itemDirectoryName(movie(name = "Arrival", year = 2016)) shouldBe "Arrival (2016)"
    }

    @Test
    fun `a movie with no year keeps just its title`() {
        DownloadPaths.itemDirectoryName(movie(name = "Arrival", year = null)) shouldBe "Arrival"
    }

    @Test
    fun `an episode directory is series, episode code and title`() {
        DownloadPaths.itemDirectoryName(episode()) shouldBe "Westworld - S01E02 - Chestnut"
    }

    @Test
    fun `an episode with no season number still gets an episode code`() {
        DownloadPaths.itemDirectoryName(episode(seasonNumber = null)) shouldBe "Westworld - E02 - Chestnut"
    }

    @Test
    fun `an episode with no numbers at all falls back to series and title`() {
        DownloadPaths.itemDirectoryName(episode(seasonNumber = null, episodeNumber = null)) shouldBe
            "Westworld - Chestnut"
    }

    @Test
    fun `the plan's separator survives sanitising`() {
        // ` - ` is the format's own separator; a sanitiser that stripped hyphens would destroy it.
        DownloadPaths.itemDirectoryName(episode()) shouldBe "Westworld - S01E02 - Chestnut"
    }

    @Test
    fun `hyphens inside a title are preserved`() {
        DownloadPaths.itemDirectoryName(movie(name = "Spider-Man", year = 2002)) shouldBe "Spider-Man (2002)"
    }

    // ---- sanitising -----------------------------------------------------------------------------

    @Test
    fun `characters exFAT rejects are replaced`() {
        val name = DownloadPaths.itemDirectoryName(movie(name = "Mission: Impossible", year = 1996))

        name shouldNotContain ":"
        name shouldBe "Mission Impossible (1996)"
    }

    @Test
    fun `a path separator can never appear in a directory name`() {
        DownloadPaths.sanitize("AC/DC: Live") shouldBe "AC DC Live"
    }

    @Test
    fun `a trailing dot is dropped`() {
        // Windows and several SMB implementations silently strip it, which would desynchronise the
        // name in Room from the name on disk.
        DownloadPaths.sanitize("Adaptation.") shouldBe "Adaptation"
    }

    @Test
    fun `a very long title is truncated to one safe segment`() {
        DownloadPaths.sanitize("x".repeat(400)).length shouldBe 120
    }

    @Test
    fun `an item with no usable name falls back to its id`() {
        val id = uuid(7)

        DownloadPaths.itemDirectoryName(movie(id = id, name = "", year = null)) shouldBe "download-$id"
    }

    // ---- media file names -----------------------------------------------------------------------

    @Test
    fun `the server's own filename is kept`() {
        val item = movie(path = "/media/films/Arrival (2016)/Arrival.2016.2160p.mkv")

        DownloadPaths.mediaFileName(item, "Arrival (2016)") shouldBe "Arrival.2016.2160p.mkv"
    }

    @Test
    fun `a windows-style server path is split on the right separator`() {
        val item = movie(path = """D:\Media\Films\Arrival.2016.mkv""")

        DownloadPaths.mediaFileName(item, "Arrival (2016)") shouldBe "Arrival.2016.mkv"
    }

    @Test
    fun `without a server path the container becomes the extension`() {
        // The PATH field is only served to users allowed to see it, so this is the common case for
        // a non-admin account.
        val item = movie(path = null)

        DownloadPaths.mediaFileName(item, "Arrival (2016)") shouldBe "Arrival (2016).mkv"
    }

    @Test
    fun `a multi-container item takes the first container`() {
        val item = movie(path = null).copy(container = "mp4,mkv")

        DownloadPaths.mediaFileName(item, "Arrival (2016)") shouldBe "Arrival (2016).mp4"
    }

    @Test
    fun `an item with neither path nor container still gets a playable extension`() {
        val item = movie(path = null).copy(container = null)

        DownloadPaths.mediaFileName(item, "Arrival (2016)") shouldBe "Arrival (2016).mkv"
    }
}
