package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanResultReducerTest {

    private val c1Name = C1Protocol.Identity.DEVICE_NAME

    @Test
    fun accept_filtersOutDevicesWithWrongOrMissingName() {
        var devices = emptyList<ScannedDevice>()
        devices = ScanResultReducer.accept(devices, "Some Other Device", "AA:AA:AA:AA:AA:AA", -60)
        devices = ScanResultReducer.accept(devices, null, "BB:BB:BB:BB:BB:BB", -60)

        assertTrue(devices.isEmpty())
    }

    @Test
    fun accept_keepsDeviceMatchingC1Name() {
        val devices = ScanResultReducer.accept(emptyList(), c1Name, "AA:BB:CC:DD:EE:FF", -55)

        assertEquals(1, devices.size)
        assertEquals("AA:BB:CC:DD:EE:FF", devices[0].address)
        assertEquals(-55, devices[0].rssi)
    }

    @Test
    fun accept_dedupesByAddressAndUpdatesRssi() {
        var devices = ScanResultReducer.accept(emptyList(), c1Name, "AA:BB:CC:DD:EE:FF", -70)
        devices = ScanResultReducer.accept(devices, c1Name, "AA:BB:CC:DD:EE:FF", -40)

        assertEquals(1, devices.size)
        assertEquals(-40, devices[0].rssi)
    }

    @Test
    fun accept_keepsMultipleDistinctAddressesSeparate() {
        var devices = ScanResultReducer.accept(emptyList(), c1Name, "AA:AA:AA:AA:AA:AA", -60)
        devices = ScanResultReducer.accept(devices, c1Name, "BB:BB:BB:BB:BB:BB", -65)

        assertEquals(2, devices.size)
    }
}
