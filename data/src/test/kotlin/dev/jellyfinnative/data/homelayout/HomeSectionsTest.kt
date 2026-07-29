package dev.jellyfinnative.data.homelayout

import dev.jellyfinnative.core.common.model.HomeSectionType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for the DisplayPreferences → ordered-rows resolver. */
class HomeSectionsTest {
    @Test
    fun `an empty map resolves to jellyfin-web's defaults`() {
        // The single most common case: a user who never opened Settings → Home has no
        // `homesectionN` keys at all, and must still get the standard home screen.
        resolveHomeSections(emptyMap()) shouldContainExactly
            listOf(
                HomeSectionType.SMALL_LIBRARY_TILES,
                HomeSectionType.RESUME,
                HomeSectionType.RESUME_AUDIO,
                HomeSectionType.RESUME_BOOK,
                HomeSectionType.LIVE_TV,
                HomeSectionType.NEXT_UP,
                HomeSectionType.LATEST_MEDIA,
            )
    }

    @Test
    fun `the published default is what an unconfigured user gets`() {
        DEFAULT_HOME_SECTIONS shouldContainExactly resolveHomeSections(emptyMap())
    }

    @Test
    fun `a fully configured layout is honoured in order`() {
        val prefs =
            mapOf(
                "homesection0" to "resume",
                "homesection1" to "nextup",
                "homesection2" to "latestmedia",
                "homesection3" to "smalllibrarytiles",
                "homesection4" to "livetv",
                "homesection5" to "none",
                "homesection6" to "none",
                "homesection7" to "none",
                "homesection8" to "none",
                "homesection9" to "none",
            )

        resolveHomeSections(prefs) shouldContainExactly
            listOf(
                HomeSectionType.RESUME,
                HomeSectionType.NEXT_UP,
                HomeSectionType.LATEST_MEDIA,
                HomeSectionType.SMALL_LIBRARY_TILES,
                HomeSectionType.LIVE_TV,
            )
    }

    @Test
    fun `an explicitly empty slot is not a row`() {
        val prefs = mapOf("homesection0" to "none", "homesection1" to "none")

        val sections = resolveHomeSections(prefs)

        sections shouldNotContain HomeSectionType.NONE
        // Slots 2-9 keep their own defaults: hiding the first two rows does not hide the rest.
        sections.first() shouldBe HomeSectionType.RESUME_AUDIO
    }

    @Test
    fun `the legacy folders value still means the libraries row`() {
        val prefs = mapOf("homesection0" to "folders")

        resolveHomeSections(prefs).first() shouldBe HomeSectionType.SMALL_LIBRARY_TILES
    }

    @Test
    fun `a value this build does not know falls back to that slot's default`() {
        val prefs = mapOf("homesection1" to "holographicsuite", "homesection5" to "")

        val sections = resolveHomeSections(prefs)

        // Slot 1 defaults to resume and slot 5 to nextup — one unreadable value costs one row,
        // never the whole screen.
        sections shouldContainExactly resolveHomeSections(emptyMap())
    }

    @Test
    fun `the same section configured twice is drawn once`() {
        val prefs =
            mapOf(
                "homesection0" to "resume",
                "homesection1" to "resume",
                "homesection2" to "latestmedia",
                "homesection3" to "none",
                "homesection4" to "none",
                "homesection5" to "none",
                "homesection6" to "none",
                "homesection7" to "none",
                "homesection8" to "none",
                "homesection9" to "none",
            )

        // First occurrence wins: the row keeps the position the user put it in first.
        resolveHomeSections(prefs) shouldContainExactly
            listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA)
    }

    @Test
    fun `a layout that hides everything resolves to no rows`() {
        val prefs = (0..9).associate { "homesection$it" to "none" }

        resolveHomeSections(prefs).shouldBeEmpty()
    }

    @Test
    fun `keys beyond the ten slots are ignored`() {
        val prefs = mapOf("homesection10" to "livetv", "someotherpref" to "resume")

        resolveHomeSections(prefs) shouldContainExactly resolveHomeSections(emptyMap())
    }
}
