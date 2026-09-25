package com.c1recorder.app.data

import com.c1recorder.app.ble.FakeC1BleClient
import com.c1recorder.app.ble.RequiredCharacteristics
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses Dispatchers.Unconfined so the repository's internal state-collecting
 * coroutine (and the fake client's non-suspending reads) run synchronously
 * within each test call, with no need for kotlinx-coroutines-test/runTest.
 */
class DefaultC1RepositoryTest {

    private val allRequiredUuids = RequiredCharacteristics.all.map { it.second }.toSet()

    private fun connectToReady(client: FakeC1BleClient, repository: C1Repository, address: String = "AA:BB:CC:DD:EE:FF") {
        repository.connect(address)
        client.simulateConnected()
        client.simulateServicesDiscovered(allRequiredUuids)
        client.simulateHandshakeAcked()
    }

    @Test
    fun reachingReady_populatesDeviceFromClientReads() {
        val client = FakeC1BleClient()
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)

        connectToReady(client, repository)

        val device = repository.device.value
        assertEquals("AA:BB:CC:DD:EE:FF", device?.address)
        assertEquals(C1Protocol.Identity.DEVICE_NAME, device?.name)
        assertEquals("5200000000000000", device?.sn)
        assertEquals("V127", device?.firmware)
        assertEquals(80, device?.batteryPercent)
        assertEquals(C1Protocol.StateValue.IDLE, device?.stateRaw)
        assertEquals(15_258_988L, device?.totalStorageKB)
        assertEquals(1_048_576L, device?.freeStorageKB)
    }

    @Test
    fun disconnecting_clearsDevice() {
        val client = FakeC1BleClient()
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.disconnect()

        assertNull(repository.device.value)
    }

    @Test
    fun partialReadFailure_leavesOtherFieldsPopulated() {
        val client = FakeC1BleClient().apply {
            fakeBatteryLevel = Result.failure(IllegalStateException("read failed"))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)

        connectToReady(client, repository)

        val device = repository.device.value
        assertEquals("5200000000000000", device?.sn)
        assertNull(device?.batteryPercent)
    }

    private fun entry(sessionId: Long, durationMs: Long) = C1Protocol.SessionEntry(sessionId, durationMs, thirdFieldUnknown = 1)

    @Test
    fun refreshSessions_success_updatesSessionsAtomically() = runBlocking {
        val entries = (1..25).map { entry(0x6aade000L + it, 1000L * it) }
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(0L to Result.success(entries))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()

        val sessions = repository.sessions.value
        val expected = entries.sortedByDescending { it.sessionId }
        assertEquals(25, sessions.size)
        assertEquals(expected.map { it.sessionId }, sessions.map { it.sessionId })
        assertEquals(expected.map { it.durationMs }, sessions.map { it.durationMs })
    }

    @Test
    fun refreshSessions_failure_preservesExistingSessions() = runBlocking {
        val initialEntries = listOf(entry(0x100L, 2000L))
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(0L to Result.success(initialEntries))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()
        assertEquals(1, repository.sessions.value.size)

        // Now subsequent fetch fails
        client.fakeSessionPages = mapOf(0L to Result.failure(IllegalStateException("stream timeout")))
        repository.refreshSessions()

        // Should preserve previous sessions rather than clearing or corrupting
        assertEquals(1, repository.sessions.value.size)
        assertEquals(0x100L, repository.sessions.value.first().sessionId)
    }

    @Test
    fun refreshSessions_whenNotReady_doesNothing() = runBlocking {
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(0L to Result.success(listOf(entry(0x50, 1000))))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)

        repository.refreshSessions()

        assertTrue(repository.sessions.value.isEmpty())
    }

    // downloadSession: a downloadRecording() failure (timeout without
    // FILE_TAIL, CRC mismatch, decode failure — see AndroidC1BleClient) must
    // never be reported as Completed. This is the core property of the
    // "don't fake success" fix: the UI can only ever show a real
    // crcVerified=true Completed state, or an Error carrying the real reason.

    @Test
    fun downloadSession_bleFailure_reportsErrorNotCompleted() = runBlocking {
        val client = FakeC1BleClient().apply {
            fakeGetFiles = Result.success(listOf(com.c1recorder.app.protocol.C1Protocol.FileEntry(fileId = 1, size = 63220)))
            fakeDownloadRecording = Result.failure(java.io.IOException("CRC 校验失败 (设备=0x1234, 本地计算=0x5678)，数据可能不完整或损坏，未生成文件"))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        val session = RecordingSession(sessionId = 0x6aadf714L, durationMs = 63220, thirdFieldUnknown = 1)
        repository.downloadSession(session, java.io.File("/tmp/does-not-need-to-exist"))

        val state = repository.downloadStates.value[session.sessionId]
        assertTrue("expected Error, got $state", state is SessionDownloadState.Error)
        assertEquals(
            "CRC 校验失败 (设备=0x1234, 本地计算=0x5678)，数据可能不完整或损坏，未生成文件",
            (state as SessionDownloadState.Error).message,
        )
    }

    @Test
    fun downloadSession_success_marksCompletedWithCrcVerified() = runBlocking {
        val wav = java.io.File("/tmp/session_x_1.wav")
        val avc = java.io.File("/tmp/session_x_1.avc")
        val client = FakeC1BleClient().apply {
            fakeGetFiles = Result.success(listOf(com.c1recorder.app.protocol.C1Protocol.FileEntry(fileId = 1, size = 63220)))
            fakeDownloadRecording = Result.success(
                com.c1recorder.app.ble.BleDownloadResult(
                    sessionId = 0x6aadf714L,
                    fileId = 1,
                    rawAvcFile = avc,
                    wavFile = wav,
                    bytesReceived = 505760,
                    packetCount = 6322,
                    crc16 = 0x1234,
                    crcVerified = true,
                ),
            )
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        val session = RecordingSession(sessionId = 0x6aadf714L, durationMs = 63220, thirdFieldUnknown = 1)
        repository.downloadSession(session, java.io.File("/tmp"))

        val state = repository.downloadStates.value[session.sessionId]
        assertTrue("expected Completed, got $state", state is SessionDownloadState.Completed)
        assertTrue((state as SessionDownloadState.Completed).crcVerified)
    }
}
