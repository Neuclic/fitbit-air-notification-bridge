// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AirDncsProtocolTest {
    @Test
    fun notificationSourceMatchesFitbitDncsLayout() {
        assertArrayEquals(
            byteArrayOf(0, 2, 4, 1, 0x78, 0x56, 0x34, 0x12),
            AirDncsProtocol.notificationSource(
                uid = 0x12345678,
                category = AirDncsProtocol.CATEGORY_SOCIAL
            )
        )
    }
}
