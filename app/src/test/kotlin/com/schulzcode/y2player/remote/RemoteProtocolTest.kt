package com.schulzcode.y2player.remote

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {

    @Test
    fun `encode and parse hello message`() {
        val jsonStr = RemoteProtocol.encodeHello(device = "Innioasis Y2", protocol = 1)
        val msg = RemoteProtocol.parseMessage(jsonStr)
        assertTrue(msg is RemoteMessage.Hello)
        val hello = msg as RemoteMessage.Hello
        assertEquals("Innioasis Y2", hello.device)
        assertEquals(1, hello.protocol)
    }

    @Test
    fun `encode and parse state message`() {
        val jsonStr = RemoteProtocol.encodeState(
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            status = RemoteProtocol.STATUS_PLAYING,
            positionMs = 120000L,
            durationMs = 300000L
        )
        val msg = RemoteProtocol.parseMessage(jsonStr)
        assertTrue(msg is RemoteMessage.PlayerState)
        val state = msg as RemoteMessage.PlayerState
        assertEquals("Test Song", state.title)
        assertEquals("Test Artist", state.artist)
        assertEquals("Test Album", state.album)
        assertEquals(RemoteProtocol.STATUS_PLAYING, state.status)
        assertEquals(120000L, state.positionMs)
        assertEquals(300000L, state.durationMs)
    }

    @Test
    fun `encode and parse toggle command`() {
        val jsonStr = RemoteProtocol.encodeCommand(RemoteCommand.Toggle)
        val msg = RemoteProtocol.parseMessage(jsonStr)
        assertTrue(msg is RemoteMessage.Command)
        assertEquals(RemoteCommand.Toggle, (msg as RemoteMessage.Command).command)
    }

    @Test
    fun `encode and parse seek command`() {
        val jsonStr = RemoteProtocol.encodeCommand(RemoteCommand.Seek(150000L))
        val msg = RemoteProtocol.parseMessage(jsonStr)
        assertTrue(msg is RemoteMessage.Command)
        val cmd = (msg as RemoteMessage.Command).command
        assertTrue(cmd is RemoteCommand.Seek)
        assertEquals(150000L, (cmd as RemoteCommand.Seek).positionMs)
    }

    @Test
    fun `encode and parse forward and rewind commands`() {
        val forwardJson = RemoteProtocol.encodeCommand(RemoteCommand.Forward(15000L))
        val forwardMsg = RemoteProtocol.parseMessage(forwardJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Forward(15000L), forwardMsg.command)

        val rewindJson = RemoteProtocol.encodeCommand(RemoteCommand.Rewind(5000L))
        val rewindMsg = RemoteProtocol.parseMessage(rewindJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Rewind(5000L), rewindMsg.command)
    }

    @Test
    fun `encode and parse play and pause commands`() {
        val playJson = RemoteProtocol.encodeCommand(RemoteCommand.Play)
        val playMsg = RemoteProtocol.parseMessage(playJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Play, playMsg.command)

        val pauseJson = RemoteProtocol.encodeCommand(RemoteCommand.Pause)
        val pauseMsg = RemoteProtocol.parseMessage(pauseJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Pause, pauseMsg.command)
    }

    @Test
    fun `encode and parse next and previous commands`() {
        val nextJson = RemoteProtocol.encodeCommand(RemoteCommand.Next)
        val nextMsg = RemoteProtocol.parseMessage(nextJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Next, nextMsg.command)

        val prevJson = RemoteProtocol.encodeCommand(RemoteCommand.Previous)
        val prevMsg = RemoteProtocol.parseMessage(prevJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.Previous, prevMsg.command)
    }

    @Test
    fun `malformed and empty inputs return null`() {
        assertNull(RemoteProtocol.parseMessage(""))
        assertNull(RemoteProtocol.parseMessage("   "))
        assertNull(RemoteProtocol.parseMessage("not a json"))
        assertNull(RemoteProtocol.parseMessage("{\"type\":\"invalid_json"))
    }

    @Test
    fun `oversized line returns null`() {
        val oversized = "{\"type\":\"hello\",\"payload\":\"" + "a".repeat(RemoteProtocol.MAX_LINE_LENGTH + 10) + "\"}"
        assertNull(RemoteProtocol.parseMessage(oversized))
    }

    @Test
    fun `encode snapshot maps playback status accurately`() {
        val snapshotPlaying = PlaybackSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = 1000L,
            durationMs = 5000L
        )
        val track = Track(
            id = 1L,
            volumeId = "sdcard",
            absolutePath = "/storage/music/song.mp3",
            relativePath = "music/song.mp3",
            title = "Track Title",
            artist = "Track Artist",
            album = "Track Album",
            albumArtist = null,
            trackNumber = 1,
            discNumber = 1,
            durationMs = 5000L,
            fileSize = 1000L,
            modifiedAt = 0L
        )
        val encoded = RemoteProtocol.encodeSnapshot(snapshotPlaying, track, volumePercent = 85)
        val msg = RemoteProtocol.parseMessage(encoded) as RemoteMessage.PlayerState
        assertEquals("Track Title", msg.title)
        assertEquals("Track Artist", msg.artist)
        assertEquals("Track Album", msg.album)
        assertEquals(RemoteProtocol.STATUS_PLAYING, msg.status)
        assertEquals(1000L, msg.positionMs)
        assertEquals(5000L, msg.durationMs)
        assertEquals(85, msg.volumePercent)
    }

    @Test
    fun `encode and parse volume commands`() {
        val upJson = RemoteProtocol.encodeCommand(RemoteCommand.VolumeUp)
        val upMsg = RemoteProtocol.parseMessage(upJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.VolumeUp, upMsg.command)

        val downJson = RemoteProtocol.encodeCommand(RemoteCommand.VolumeDown)
        val downMsg = RemoteProtocol.parseMessage(downJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.VolumeDown, downMsg.command)

        val setJson = RemoteProtocol.encodeCommand(RemoteCommand.SetVolume(60))
        val setMsg = RemoteProtocol.parseMessage(setJson) as RemoteMessage.Command
        assertEquals(RemoteCommand.SetVolume(60), setMsg.command)
    }

    @Test
    fun `encode and parse artwork message`() {
        val fakeBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val artworkJson = RemoteProtocol.encodeArtwork(fakeBase64)
        val msg = RemoteProtocol.parseMessage(artworkJson) as RemoteMessage.Artwork
        assertEquals(fakeBase64, msg.base64)
    }
}
