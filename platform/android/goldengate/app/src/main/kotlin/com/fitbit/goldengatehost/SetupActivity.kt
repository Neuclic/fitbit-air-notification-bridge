// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.fitbit.goldengatehost.scan.EXTRA_SAVE_ONLY
import com.fitbit.goldengatehost.scan.ScanActivity

class SetupActivity : AppCompatActivity() {
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }
    private lateinit var notificationRules: EditText
    private lateinit var notificationAccessButton: Button
    private lateinit var notificationAccessStatus: TextView
    private lateinit var selectedDeviceStatus: TextView
    private lateinit var lastTaskStatus: TextView
    private lateinit var chooseDeviceButton: Button
    private lateinit var manualTestButton: Button
    private lateinit var openGoogleHealthButton: Button
    private lateinit var dncsProbeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.a_setup)
        bindViews()

        notificationRules.setText(AirBridgeNotificationSettings.loadText(this))
        notificationAccessButton.setOnClickListener {
            if (saveRules()) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        chooseDeviceButton.setOnClickListener {
            if (!AirBluetoothPermissions.hasAll(this)) {
                AirBluetoothPermissions.request(this, REQ_ID)
                Toast.makeText(this, "请先允许附近设备/蓝牙权限，再次点击选择 Air", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            runCatching { AirBridgeRuntime.ensureInitialized(this) }
                .onSuccess {
                    startActivity(
                        ScanActivity.getIntent(this)
                            .putExtra(EXTRA_SAVE_ONLY, true)
                    )
                }
                .onFailure {
                    Toast.makeText(this, "蓝牙运行时初始化失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
        manualTestButton.setOnClickListener {
            if (!saveRules()) return@setOnClickListener
            val accepted = AirBridgeOneShotService.enqueue(
                this,
                AirNotificationTrigger("manual.test", AirHapticPattern.SINGLE)
            )
            Toast.makeText(
                this,
                if (accepted) "已启动 single 测试；结束后可一键重开 Google Health" else "请先选择 Fitbit Air",
                Toast.LENGTH_LONG
            ).show()
            refreshStatus()
        }
        openGoogleHealthButton.setOnClickListener {
            val opened = GoogleHealthRecovery.open(this)
            Toast.makeText(
                this,
                if (opened) "正在重开 Google Health 以刷新设备会话" else "未找到 Google Health",
                Toast.LENGTH_LONG
            ).show()
        }
        dncsProbeButton.setOnClickListener {
            startActivity(Intent(this, AirDncsProbeActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.postDelayed(statusRefresh, STATUS_REFRESH_MILLIS)
        if (!AirBluetoothPermissions.hasAll(this)) {
            AirBluetoothPermissions.request(this, REQ_ID)
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun bindViews() {
        notificationRules = ActivityCompat.requireViewById(this, R.id.notification_package_allowlist)
        notificationAccessButton = ActivityCompat.requireViewById(this, R.id.notification_access_button)
        notificationAccessStatus = ActivityCompat.requireViewById(this, R.id.notification_access_status)
        selectedDeviceStatus = ActivityCompat.requireViewById(this, R.id.selected_device_status)
        lastTaskStatus = ActivityCompat.requireViewById(this, R.id.last_task_status)
        chooseDeviceButton = ActivityCompat.requireViewById(this, R.id.start_button)
        manualTestButton = ActivityCompat.requireViewById(this, R.id.manual_test_button)
        openGoogleHealthButton = ActivityCompat.requireViewById(this, R.id.open_google_health_button)
        dncsProbeButton = ActivityCompat.requireViewById(this, R.id.dncs_probe_button)
    }

    private fun saveRules(): Boolean {
        val parsed = AirBridgeNotificationSettings.parseRules(notificationRules.text.toString())
        if (!parsed.isValid) {
            notificationAccessStatus.text = "规则未保存：\n${parsed.errors.joinToString("\n")}"
            Toast.makeText(this, "请先修正规则格式", Toast.LENGTH_LONG).show()
            return false
        }
        AirBridgeNotificationSettings.saveRules(this, parsed.rules)
        notificationRules.setText(AirBridgeNotificationSettings.loadText(this))
        refreshStatus()
        return true
    }

    private fun refreshStatus() {
        val granted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        val rules = AirBridgeNotificationSettings.loadRules(this).joinToString("\n") {
            "${it.packageName} → ${it.pattern.wireName}"
        }
        notificationAccessStatus.text =
            "通知访问权限=${if (granted) "已授权" else "未授权"}\n当前规则：\n$rules"
        selectedDeviceStatus.text = "目标设备：${AirBridgeDeviceSettings.loadDeviceLabel(this)}"
        lastTaskStatus.text = "最近任务：${AirBridgeDeviceSettings.loadLastStatus(this)}"
        manualTestButton.isEnabled = AirBridgeDeviceSettings.loadAddress(this) != null
    }

    companion object {
        private const val STATUS_REFRESH_MILLIS = 500L
    }
}
