package com.fitbit.goldengatehost

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fitbit.goldengate.GoldenGateConnectionManagerModule
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/** Owns this process's Golden Gate/BitGatt runtime; the haptic worker releases it while idle. */
object AirBridgeRuntime {
    private val initialized = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val shutdown = Runnable {
        synchronized(this) {
            if (initialized.getAndSet(false)) {
                runCatching { GoldenGateConnectionManagerModule.shutdown() }
                    .onFailure { Timber.w(it, "Failed to release idle Air Bluetooth runtime") }
            }
        }
    }

    fun cancelShutdown() = handler.removeCallbacks(shutdown)

    /** Used only by the dedicated haptic worker process, after peer teardown has settled. */
    fun scheduleShutdown() {
        cancelShutdown()
        handler.postDelayed(shutdown, 1_000L)
    }

    @Synchronized
    fun ensureInitialized(context: Context) {
        cancelShutdown()
        if (initialized.get()) return
        try {
            GoldenGateConnectionManagerModule.init(
                context.applicationContext,
                loggingEnabled = true,
                isBleCentralRole = true
            )
            initialized.set(true)
        } catch (throwable: Throwable) {
            initialized.set(false)
            throw throwable
        }
    }
}
