package com.c1recorder.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeC1BleClientTest {

    private val allRequiredUuids = RequiredCharacteristics.all.map { it.second }.toSet()

    @Test
    fun connect_setsConnectingAndRecordsAddress() {
        val client = FakeC1BleClient()

        client.connect("AA:BB:CC:DD:EE:FF")

        assertEquals(C1ClientState.Connecting, client.state.value)
        assertEquals("AA:BB:CC:DD:EE:FF", client.lastConnectedAddress)
    }

    @Test
    fun fullHappyPath_reachesConnectedWithAllCharacteristics() {
        val client = FakeC1BleClient()

        client.connect("AA:BB:CC:DD:EE:FF")
        client.simulateConnected()
        client.simulateServicesDiscovered(allRequiredUuids)

        assertEquals(C1ClientState.Connected, client.state.value)
    }

    @Test
    fun servicesDiscovered_withMissingCharacteristic_reportsIt() {
        val client = FakeC1BleClient()
        val missingOneUuid = allRequiredUuids - RequiredCharacteristics.all.first().second

        client.connect("AA:BB:CC:DD:EE:FF")
        client.simulateConnected()
        client.simulateServicesDiscovered(missingOneUuid)

        val state = client.state.value
        assertTrue(state is C1ClientState.MissingCharacteristics)
        assertEquals(listOf(RequiredCharacteristics.all.first().first), (state as C1ClientState.MissingCharacteristics).missing)
    }

    @Test
    fun simulateConnected_whileNotConnecting_isIgnored() {
        val client = FakeC1BleClient()

        client.simulateConnected()

        assertEquals(C1ClientState.Disconnected, client.state.value)
    }

    @Test
    fun simulateServicesDiscovered_whileNotDiscovering_isIgnored() {
        val client = FakeC1BleClient()

        client.simulateServicesDiscovered(allRequiredUuids)

        assertEquals(C1ClientState.Disconnected, client.state.value)
    }

    @Test
    fun disconnect_fromAnyState_returnsToDisconnected() {
        val client = FakeC1BleClient()
        client.connect("AA:BB:CC:DD:EE:FF")
        client.simulateConnected()
        client.simulateServicesDiscovered(allRequiredUuids)

        client.disconnect()

        assertEquals(C1ClientState.Disconnected, client.state.value)
    }

    @Test
    fun connectionFailed_canBeSimulatedDirectly() {
        val client = FakeC1BleClient()

        client.simulateConnectionFailed("连接超时")

        assertEquals(C1ClientState.ConnectionFailed("连接超时"), client.state.value)
    }
}
