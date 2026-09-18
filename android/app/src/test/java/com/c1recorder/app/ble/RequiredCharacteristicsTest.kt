package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredCharacteristicsTest {

    @Test
    fun findMissing_withNoDiscoveredCharacteristics_returnsAllOfThem() {
        val missing = RequiredCharacteristics.findMissing(emptySet())

        assertEquals(RequiredCharacteristics.all.map { it.first }.toSet(), missing.toSet())
    }

    @Test
    fun findMissing_withAllRequiredCharacteristics_returnsEmpty() {
        val allUuids = RequiredCharacteristics.all.map { it.second }.toSet()

        val missing = RequiredCharacteristics.findMissing(allUuids)

        assertTrue(missing.isEmpty())
    }

    @Test
    fun findMissing_withOneCharacteristicAbsent_reportsOnlyThatOne() {
        val allButCmdIndicate = RequiredCharacteristics.all
            .map { it.second }
            .filterNot { it == C1Protocol.Characteristic.CMD_INDICATE }
            .toSet()

        val missing = RequiredCharacteristics.findMissing(allButCmdIndicate)

        assertEquals(listOf("cmdIndicate"), missing)
    }

    @Test
    fun findMissing_ignoresUnrelatedExtraCharacteristics() {
        val allUuidsPlusExtra = RequiredCharacteristics.all.map { it.second }.toSet() +
            C1Protocol.Characteristic.FILE_TRIGGER

        val missing = RequiredCharacteristics.findMissing(allUuidsPlusExtra)

        assertTrue(missing.isEmpty())
    }
}
