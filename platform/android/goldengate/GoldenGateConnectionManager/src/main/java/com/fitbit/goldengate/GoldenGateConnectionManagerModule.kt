// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengate

import android.content.Context
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bt.GlobalBluetoothGattInitializer
import com.fitbit.bluetooth.fbgatt.FitbitGatt

object GoldenGateConnectionManagerModule {

    private val globalBluetoothGattInitializer = GlobalBluetoothGattInitializer()

    /**
     * Initialized GG module.
     */
    @JvmStatic
    fun init(applicationContext: Context, loggingEnabled: Boolean = true, isBleCentralRole: Boolean = true) {
        GoldenGate.init(loggingEnabled)
        globalBluetoothGattInitializer.start(applicationContext, isBleCentralRole)
    }

    /** Release this process's client/server registrations when an on-demand worker becomes idle. */
    @JvmStatic
    fun shutdown() {
        try {
            globalBluetoothGattInitializer.stop()
        } finally {
            FitbitGatt.getInstance().shutdown()
        }
    }
}
