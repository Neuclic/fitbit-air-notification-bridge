// Copyright 2026
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import java.io.ByteArrayOutputStream

/**
 * Minimal clean-room encoder for the Switchboard notification records used by Google Health 5.06.1.
 *
 * This deliberately implements only the fields needed by the manual interoperability probe. It is
 * not a copy of Google Health code and it is not wired to the background notification listener.
 */
internal object AirSwitchboardNotificationProtocol {

    const val CREATE_PATH = "sb/records"
    const val DELETE_PATH = "sb/delete-records"
    const val RECORD_VERSION = "0.4.10"
    const val DEFAULT_TTL_SECONDS = 60L

    private const val MAX_LOCAL_ID = 0x3fffffff
    private const val CHILD_ID_COUNT = 4
    private const val MOBILE_ID_NAMESPACE = Int.MIN_VALUE
    private const val EVENT_SHOW = 0x30L
    private const val SCHEDULER_TRIGGER_IMMEDIATE = 0L
    private const val TTL_POLICY_POSIX_TIMESTAMP = 2L
    private const val DEFAULT_APP_COLOR = 0xffb0b0b0.toInt()

    data class EncodedNotification(
        val rootId: Int,
        val record: ByteArray,
        val deleteCommand: ByteArray,
        val expiresAtEpochSeconds: Long
    ) {
        val unsignedRootId: Long
            get() = rootId.toLong() and 0xffffffffL
    }

    /**
     * Builds the smallest official-shaped record that can present a normal popup notification.
     * Haptic pattern is omitted by default so the firmware chooses its normal notification haptic.
     */
    fun encodeDefaultNotification(
        idSeed: Int,
        appId: String,
        appName: String,
        title: String,
        content: String,
        timestampEpochSeconds: Long,
        expiresAtEpochSeconds: Long = timestampEpochSeconds + DEFAULT_TTL_SECONDS,
        hapticPattern: Int? = null
    ): EncodedNotification {
        require(idSeed in 1..(MAX_LOCAL_ID - CHILD_ID_COUNT)) { "idSeed is outside the mobile id range" }
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(timestampEpochSeconds >= 0) { "timestamp must not be negative" }
        require(expiresAtEpochSeconds > timestampEpochSeconds) { "expiry must follow timestamp" }
        require(hapticPattern == null || hapticPattern >= 0) { "hapticPattern must be unsigned" }

        // Google Health allocates the record and its children from the mobile (10xx...) namespace.
        val rootId = mobileId(idSeed)
        val popupExpressionId = mobileId(idSeed + 1)
        val schedulerConfigurationId = mobileId(idSeed + 2)
        val ttlConfigurationId = mobileId(idSeed + 3)
        val notificationCommonExpressionId = mobileId(idSeed + 4)

        val schedulerEvent = ProtoWriter().apply {
            writeUInt32Field(1, EVENT_SHOW)
            writeFixed32Field(2, popupExpressionId)
        }.toByteArray()

        val scheduler = ProtoWriter().apply {
            // Presence matters even though IMMEDIATE is enum value zero.
            writeUInt32Field(1, SCHEDULER_TRIGGER_IMMEDIATE)
            writeMessageField(2, schedulerEvent)
        }.toByteArray()

        val schedulerConfiguration = ProtoWriter().apply {
            writeFixed32Field(1, schedulerConfigurationId)
            writeMessageField(32, scheduler)
        }.toByteArray()

        val ttl = ProtoWriter().apply {
            writeUInt32Field(1, TTL_POLICY_POSIX_TIMESTAMP)
            writeUInt64Field(3, expiresAtEpochSeconds)
        }.toByteArray()

        val ttlConfiguration = ProtoWriter().apply {
            writeFixed32Field(1, ttlConfigurationId)
            writeMessageField(33, ttl)
        }.toByteArray()

        val common = ProtoWriter().apply {
            writeStringField(1, appId)
            writeUInt32Field(2, DEFAULT_APP_COLOR.toLong() and 0xffffffffL)
            if (appName.isNotEmpty()) writeStringField(4, appName)
            if (title.isNotEmpty()) writeStringField(5, title)
            if (content.isNotEmpty()) writeStringField(6, content)
            writeUInt64Field(7, timestampEpochSeconds)
            hapticPattern?.let { writeUInt32Field(10, it.toLong()) }
        }.toByteArray()

        val commonExpression = ProtoWriter().apply {
            writeFixed32Field(1, notificationCommonExpressionId)
            writeMessageField(36, common)
        }.toByteArray()

        val popupTemplate = ProtoWriter().apply {
            writeUInt32Field(1, notificationCommonExpressionId.toLong() and 0xffffffffL)
        }.toByteArray()

        val popup = ProtoWriter().apply {
            writeMessageField(4, popupTemplate)
        }.toByteArray()

        val popupExpression = ProtoWriter().apply {
            writeFixed32Field(1, popupExpressionId)
            writeMessageField(34, popup)
        }.toByteArray()

        val record = ProtoWriter().apply {
            writeFixed32Field(1, rootId)
            writeStringField(2, RECORD_VERSION)
            writeMessageField(3, schedulerConfiguration)
            writeMessageField(3, ttlConfiguration)
            writeMessageField(4, commonExpression)
            writeMessageField(4, popupExpression)
        }.toByteArray()

        // DeleteRecordsCommand.record_ids is a repeated uint32 field. Deleting the root removes
        // the complete record tree; TTL remains a fail-safe if the explicit cleanup cannot run.
        val deleteCommand = ProtoWriter().apply {
            writeUInt32Field(1, rootId.toLong() and 0xffffffffL)
        }.toByteArray()

        return EncodedNotification(rootId, record, deleteCommand, expiresAtEpochSeconds)
    }

    /**
     * Fail-closed interpretation of a read-only GET sent to a known POST endpoint.
     *
     * A 2.xx response proves that the path accepted the safe GET. A 4.05 response proves that the
     * path exists but does not allow GET, which is the expected result for a POST-only endpoint.
     * No other response is enough evidence to unlock the manual write step.
     */
    fun qualifiesReadOnlyEndpointProbe(responseClass: Int, detail: Int): Boolean =
        responseClass == COAP_SUCCESS_CLASS ||
            (responseClass == COAP_CLIENT_ERROR_CLASS && detail == COAP_METHOD_NOT_ALLOWED_DETAIL)

    private fun mobileId(localId: Int): Int = MOBILE_ID_NAMESPACE or (localId and MAX_LOCAL_ID)

    private class ProtoWriter {
        private val output = ByteArrayOutputStream()

        fun writeUInt32Field(fieldNumber: Int, value: Long) {
            require(value in 0..0xffffffffL)
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeUInt64Field(fieldNumber: Int, value: Long) {
            require(value >= 0)
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeFixed32Field(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, WIRE_FIXED_32)
            output.write(value)
            output.write(value ushr 8)
            output.write(value ushr 16)
            output.write(value ushr 24)
        }

        fun writeStringField(fieldNumber: Int, value: String) =
            writeMessageField(fieldNumber, value.toByteArray(Charsets.UTF_8))

        fun writeMessageField(fieldNumber: Int, value: ByteArray) {
            writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
            writeVarint(value.size.toLong())
            output.write(value)
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            require(fieldNumber > 0)
            writeVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            while (true) {
                if ((remaining and -128L) == 0L) {
                    output.write(remaining.toInt())
                    return
                }
                output.write(((remaining and 0x7fL) or 0x80L).toInt())
                remaining = remaining ushr 7
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private const val COAP_SUCCESS_CLASS = 2
    private const val COAP_CLIENT_ERROR_CLASS = 4
    private const val COAP_METHOD_NOT_ALLOWED_DETAIL = 5
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5
}
