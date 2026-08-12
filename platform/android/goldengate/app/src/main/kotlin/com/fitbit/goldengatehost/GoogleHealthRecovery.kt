package com.fitbit.goldengatehost

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Visible, user-authorized recovery for Google Health's stale in-process Air session. */
object GoogleHealthRecovery {
    private const val HEALTH_PACKAGE = "com.fitbit.FitbitMobile"
    private const val HEALTH_LAUNCH_ALIAS = "com.fitbit.HealthBrandedAlias"

    fun launchIntent(context: Context): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(HEALTH_PACKAGE)
            ?: Intent(Intent.ACTION_MAIN).setClassName(HEALTH_PACKAGE, HEALTH_LAUNCH_ALIAS)
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    fun pendingIntent(context: Context): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            15001,
            launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    fun open(context: Context): Boolean = runCatching {
        context.startActivity(launchIntent(context))
        true
    }.getOrDefault(false)
}
