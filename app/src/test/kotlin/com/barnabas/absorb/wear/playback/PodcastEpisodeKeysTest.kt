package com.barnabas.absorb.wear.playback

import com.barnabas.absorb.wear.download.keepKey
import com.barnabas.absorb.wear.player.PlayerStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastEpisodeKeysTest {

    @Test
    fun aBookIsKeptUnderItsOwnIdAndEpisodesApart() {
        assertEquals("li_1", keepKey("li_1", null))
        assertNotEquals(keepKey("li_1", "ep_a"), keepKey("li_1", "ep_b"))
        assertNotEquals("li_1", keepKey("li_1", "ep_a"))
    }

    @Test
    fun anEpisodeSessionIdNamesThePodcastAndTheEpisode() {
        val id = AbsLocalPlayback.newSessionId("8f2c-11aa", "e5d0-2b")
        assertTrue(AbsLocalPlayback.isLocalSession(id))
        assertTrue(id.startsWith("local-8f2c-11aa~e5d0-2b-"))
    }

    @Test
    fun aStartOnThePhoneWaitsForTheRightEpisode() {
        val episode = PlayerStateStore.PhoneStart("pod", "Episode 12", 0L)
        assertTrue(episode.isFor("pod", "Episode 12"))
        assertFalse(episode.isFor("pod", "Episode 11"))
        assertFalse(episode.isFor("other", "Episode 12"))
        val book = PlayerStateStore.PhoneStart("book", null, 0L)
        assertTrue(book.isFor("book", "Whatever the phone calls it"))
    }
}
