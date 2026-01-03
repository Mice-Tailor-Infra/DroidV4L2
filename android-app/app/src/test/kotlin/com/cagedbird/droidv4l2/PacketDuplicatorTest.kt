package com.cagedbird.droidv4l2

import android.util.Log
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals

class PacketDuplicatorTest {

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `test start-stop broadcast`() {
        val sender1 = mockk<VideoSender>(relaxed = true)
        val sender2 = mockk<VideoSender>(relaxed = true)
        val duplicator = PacketDuplicator(listOf(sender1, sender2))

        duplicator.start()
        verify(exactly = 1) { sender1.start() }
        verify(exactly = 1) { sender2.start() }

        duplicator.stop()
        verify(exactly = 1) { sender1.stop() }
        verify(exactly = 1) { sender2.stop() }
    }

    @Test
    fun `test send broadcast`() {
        val sender1 = mockk<VideoSender>(relaxed = true)
        val sender2 = mockk<VideoSender>(relaxed = true)
        val duplicator = PacketDuplicator(listOf(sender1, sender2))

        val data = byteArrayOf(1, 2, 3)
        val ts = 1000L
        val flags = 1

        duplicator.send(data, ts, flags)

        verify(exactly = 1) { sender1.send(data, ts, flags) }
        verify(exactly = 1) { sender2.send(data, ts, flags) }
    }

    @Test
    fun `test resilience when one sender fails`() {
        val sender1 = mockk<VideoSender>()
        val sender2 = mockk<VideoSender>(relaxed = true)

        // sender1 throws exception
        every { sender1.send(any(), any(), any()) } throws RuntimeException("Network Error")
        every { sender1.getInfo() } returns "FailedSender"

        val duplicator = PacketDuplicator(listOf(sender1, sender2))
        val data = byteArrayOf(1, 2, 3)

        // Should not throw exception
        duplicator.send(data, 1000L, 0)

        verify(exactly = 1) { sender1.send(data, 1000L, 0) }
        verify(exactly = 1) { sender2.send(data, 1000L, 0) }
    }

    @Test
    fun `test getInfo aggregation`() {
        val sender1 = mockk<VideoSender>()
        val sender2 = mockk<VideoSender>()
        every { sender1.getInfo() } returns "SRT"
        every { sender2.getInfo() } returns "RTSP"

        val duplicator = PacketDuplicator(listOf(sender1, sender2))
        assertEquals("SRT + RTSP", duplicator.getInfo())
    }
}
