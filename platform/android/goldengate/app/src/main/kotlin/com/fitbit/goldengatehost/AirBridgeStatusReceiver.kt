package com.fitbit.goldengatehost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Persist worker status in the UI process; SharedPreferences caches are not cross-process IPC. */
class AirBridgeStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_STATUS)?.let {
            AirBridgeDeviceSettings.saveLastStatus(context, it)
        }
    }

    companion object {
        const val EXTRA_STATUS = "air_task_status"
    }
}
