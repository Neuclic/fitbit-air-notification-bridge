// Copyright 2026
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirSwitchboardNotificationProtocolTest {

    @Test
    fun encodesOfficialShapedImmediateNotificationWithCleanup() {
        val encoded = AirSwitchboardNotificationProtocol.encodeDefaultNotification(
            idSeed = 1,
            appId = "test.app",
            appName = "Air Bridge",
            title = "Test",
            content = "Notification probe",
            timestampEpochSeconds = 100,
            expiresAtEpochSeconds = 160
        )

        assertEquals(0x80000001L, encoded.unsignedRootId)
        assertEquals(160L, encoded.expiresAtEpochSeconds)
        assertArrayEquals(
            byteArrayOf(0x08, 0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x08),
            encoded.deleteCommand
        )

        val record = parse(encoded.record)
        assertEquals(0x80000001L, record.singleField(1).fixed32)
        assertEquals("0.4.10", record.singleField(2).bytes!!.toString(Charsets.UTF_8))
        assertEquals(2, record.fields(3).size)
        assertEquals(2, record.fields(4).size)

        val schedulerConfiguration = parse(record.fields(3)[0].bytes!!)
        assertEquals(0x80000003L, schedulerConfiguration.singleField(1).fixed32)
        val scheduler = parse(schedulerConfiguration.singleField(32).bytes!!)
        assertEquals(0L, scheduler.singleField(1).varint)
        val showEvent = parse(scheduler.singleField(2).bytes!!)
        assertEquals(0x30L, showEvent.singleField(1).varint)
        assertEquals(0x80000002L, showEvent.singleField(2).fixed32)

        val ttlConfiguration = parse(record.fields(3)[1].bytes!!)
        assertEquals(0x80000004L, ttlConfiguration.singleField(1).fixed32)
        val ttl = parse(ttlConfiguration.singleField(33).bytes!!)
        assertEquals(2L, ttl.singleField(1).varint)
        assertEquals(160L, ttl.singleField(3).varint)

        val commonExpression = parse(record.fields(4)[0].bytes!!)
        assertEquals(0x80000005L, commonExpression.singleField(1).fixed32)
        val common = parse(commonExpression.singleField(36).bytes!!)
        assertEquals("test.app", common.singleField(1).bytes!!.toString(Charsets.UTF_8))
        assertEquals(0xffb0b0b0L, common.singleField(2).varint)
        assertEquals("Air Bridge", common.singleField(4).bytes!!.toString(Charsets.UTF_8))
        assertEquals("Test", common.singleField(5).bytes!!.toString(Charsets.UTF_8))
        assertEquals("Notification probe", common.singleField(6).bytes!!.toString(Charsets.UTF_8))
        assertEquals(100L, common.singleField(7).varint)
        assertTrue(common.fields(10).isEmpty())

        val popupExpression = parse(record.fields(4)[1].bytes!!)
        assertEquals(0x80000002L, popupExpression.singleField(1).fixed32)
        val popup = parse(popupExpression.singleField(34).bytes!!)
        val popupTemplate = parse(popup.singleField(4).bytes!!)
        assertEquals(0x80000005L, popupTemplate.singleField(1).varint)
    }

    @Test
    fun includesExplicitHapticOnlyWhenRequested() {
        val encoded = AirSwitchboardNotificationProtocol.encodeDefaultNotification(
            idSeed = 20,
            appId = "test.app",
            appName = "",
            title = "",
            content = "",
            timestampEpochSeconds = 1,
            hapticPattern = 7
        )

        val record = parse(encoded.record)
        val commonExpression = parse(record.fields(4)[0].bytes!!)
        val common = parse(commonExpression.singleField(36).bytes!!)
        assertEquals(7L, common.singleField(10).varint)
    }

    @Test
    fun qualifiesOnlySuccessfulOrMethodNotAllowedReadOnlyEndpointProbes() {
        assertTrue(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(2, 1))
        assertTrue(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(2, 5))
        assertTrue(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(4, 5))

        assertFalse(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(4, 4))
        assertFalse(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(4, 0))
        assertFalse(AirSwitchboardNotificationProtocol.qualifiesReadOnlyEndpointProbe(5, 3))
    }

    private data class Field(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val fixed32: Long? = null,
        val bytes: ByteArray? = null
    )

    private class Message(private val values: List<Field>) {
        fun fields(number: Int): List<Field> = values.filter { it.number == number }
        fun singleField(number: Int): Field = fields(number).single()
    }

    private fun parse(bytes: ByteArray): Message {
        var position = 0
        val fields = mutableListOf<Field>()

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < bytes.size) { "Truncated varint" }
                val value = bytes[position++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            error("Malformed varint")
        }

        while (position < bytes.size) {
            val tag = readVarint().toInt()
            val number = tag ushr 3
            val wireType = tag and 7
            fields += when (wireType) {
                0 -> Field(number, wireType, varint = readVarint())
                2 -> {
                    val length = readVarint().toInt()
                    require(length >= 0 && position + length <= bytes.size) { "Truncated bytes" }
                    val value = bytes.copyOfRange(position, position + length)
                    position += length
                    Field(number, wireType, bytes = value)
                }
                5 -> {
                    require(position + 4 <= bytes.size) { "Truncated fixed32" }
                    val value = (bytes[position].toLong() and 0xff) or
                        ((bytes[position + 1].toLong() and 0xff) shl 8) or
                        ((bytes[position + 2].toLong() and 0xff) shl 16) or
                        ((bytes[position + 3].toLong() and 0xff) shl 24)
                    position += 4
                    Field(number, wireType, fixed32 = value)
                }
                else -> error("Unsupported wire type $wireType")
            }
        }
        return Message(fields)
    }
}
