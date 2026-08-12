// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Fitbit Device Notification Center Service (DNCS) wire helpers.
 *
 * The notification-source record is compatible with the format used by the
 * Google Health APK: event, flags, category, category count, and a LE uid.
 */
object AirDncsProtocol {
    const val EVENT_ADDED: Byte = 0
    const val FLAG_IMPORTANT: Byte = 2
    const val CATEGORY_OTHER: Byte = 0
    const val CATEGORY_SOCIAL: Byte = 4

    fun notificationSource(
        uid: Int,
        event: Byte = EVENT_ADDED,
        flags: Byte = FLAG_IMPORTANT,
        category: Byte = CATEGORY_OTHER,
        categoryCount: Byte = 1
    ): ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(event)
        .put(flags)
        .put(category)
        .put(categoryCount)
        .putInt(uid)
        .array()
}
