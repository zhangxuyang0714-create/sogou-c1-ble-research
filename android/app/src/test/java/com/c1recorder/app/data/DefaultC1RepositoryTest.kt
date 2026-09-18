package com.c1recorder.app.data

import com.c1recorder.app.ble.FakeC1BleClient
import com.c1recorder.app.ble.RequiredCharacteristics
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
