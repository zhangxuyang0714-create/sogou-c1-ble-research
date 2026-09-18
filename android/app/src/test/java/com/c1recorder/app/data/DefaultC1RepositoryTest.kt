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
    fun refreshSessions_singlePage_stopsWhenNextPageIsEmpty() = runBlocking {
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(0L to Result.success(listOf(entry(0x67d1acb0, 1740), entry(0x6aabb161, 12_000))))
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()

        val sessions = repository.sessions.value
        assertEquals(2, sessions.size)
        assertEquals(listOf(0x67d1acb0L, 0x6aabb161L), sessions.map { it.sessionId })
        assertEquals(listOf(1740L, 12_000L), sessions.map { it.durationMs })
    }

    @Test
    fun refreshSessions_paginatesUntilNextPageIsEmpty() = runBlocking {
        val page1Last = 0x100L
        val page2Last = 0x200L
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(
                0L to Result.success(listOf(entry(0x50, 1000), entry(page1Last, 2000))),
                page1Last to Result.success(listOf(entry(0x150, 3000), entry(page2Last, 4000))),
                // page2Last deliberately unconfigured -> defaults to empty page -> pagination stops
            )
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()

        val sessions = repository.sessions.value
        assertEquals(listOf(0x50L, page1Last, 0x150L, page2Last), sessions.map { it.sessionId })
    }

    @Test
    fun refreshSessions_stopsWhenPageDoesNotAdvance() = runBlocking {
        val stuckId = 0x100L
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(
                0L to Result.success(listOf(entry(0x50, 1000), entry(stuckId, 2000))),
                // device echoes the same last sessionId again instead of advancing
                stuckId to Result.success(listOf(entry(stuckId, 2000))),
            )
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()

        assertEquals(listOf(0x50L, stuckId), repository.sessions.value.map { it.sessionId })
    }

    @Test
    fun refreshSessions_stopsAtTwentySessionsEvenIfMorePagesExist() = runBlocking {
        val page1Last = 0x100L
        val page1 = (0 until 20).map { entry(it.toLong() + 1, 1000) } + entry(page1Last, 1000)
        val client = FakeC1BleClient().apply {
            fakeSessionPages = mapOf(
                0L to Result.success(page1),
                // never fetched: page1 alone already hits the cap
                page1Last to Result.success(listOf(entry(0x999, 1000))),
            )
        }
        val repository = DefaultC1Repository(client, Dispatchers.Unconfined)
        connectToReady(client, repository)

        repository.refreshSessions()

        assertEquals(21, repository.sessions.value.size) // cap is checked after appending a page, not mid-page
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
}
