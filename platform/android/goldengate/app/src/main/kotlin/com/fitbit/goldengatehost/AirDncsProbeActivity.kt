// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deliberately separate diagnostic screen for the only stock-Android
 * coexistence path that does not compete for Air's existing BLE connection.
 *
 * It opens a local Fitbit DNCS GATT service and records whether the already
 * connected Air discovers or subscribes to it. It never scans for, connects
 * to, disconnects from, or writes settings to Air.
 */
class AirDncsProbeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var sendButton: Button
    private val logLines = CopyOnWriteArrayList<String>()
    private val uid = AtomicInteger((System.currentTimeMillis() and 0x7fffffff).toInt())

    private var gattServer: BluetoothGattServer? = null
    private var notificationSource: BluetoothGattCharacteristic? = null
    private var subscribedDevice: BluetoothDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.a_dncs_probe)
        status = ActivityCompat.requireViewById(this, R.id.dncs_probe_status)
        sendButton = ActivityCompat.requireViewById(this, R.id.dncs_send_test)

        ActivityCompat.requireViewById<Button>(this, R.id.dncs_refresh_links).setOnClickListener {
            refreshSystemLinks()
        }
        ActivityCompat.requireViewById<Button>(this, R.id.dncs_start_probe).setOnClickListener {
            startProbe()
        }
        sendButton.setOnClickListener { sendTestNotification() }
        ActivityCompat.requireViewById<Button>(this, R.id.dncs_stop_probe).setOnClickListener {
            stopProbe("用户停止探针")
        }

        append("探针未启动；Google Health 可保持后台运行。")
        refreshSystemLinks()
    }

    override fun onDestroy() {
        stopProbe(null)
        super.onDestroy()
    }

    private fun bluetoothManager(): BluetoothManager =
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

    private fun refreshSystemLinks() {
        val manager = bluetoothManager()
        append("目标=${AirBridgeDeviceSettings.loadDeviceLabel(this)}")
        append("系统 GATT(client)=${connected(manager, BluetoothProfile.GATT)}")
        append("系统 GATT_SERVER=${connected(manager, BluetoothProfile.GATT_SERVER)}")
    }

    private fun connected(manager: BluetoothManager, profile: Int): String = runCatching {
        manager.getConnectedDevices(profile).joinToString { deviceLabel(it) }.ifBlank { "无" }
    }.getOrElse { "读取失败:${it.javaClass.simpleName}:${it.message}" }

    private fun startProbe() {
        if (!AirBluetoothPermissions.hasAll(this)) {
            append("缺少附近设备/蓝牙权限，无法启动。")
            AirBluetoothPermissions.request(this, 2121)
            return
        }
        stopProbe(null)
        append("打开本机 GATT Server（不扫描、不连接 Air）…")
        val server = runCatching {
            bluetoothManager().openGattServer(applicationContext, callback)
        }.getOrElse {
            append("openGattServer 失败:${it.javaClass.simpleName}:${it.message}")
            return
        }
        if (server == null) {
            append("openGattServer 返回 null")
            return
        }
        gattServer = server
        val service = createDncsService()
        notificationSource = service.getCharacteristic(DNCS_NOTIFICATION_SOURCE_UUID)
        val accepted = runCatching { server.addService(service) }.getOrElse {
            append("addService 抛错:${it.javaClass.simpleName}:${it.message}")
            false
        }
        append("注册 Fitbit DNCS 服务请求=${if (accepted) "已受理" else "被拒绝"}")
        append("等待 onServiceAdded / Air 的读写或 CCCD 订阅…")
        refreshSystemLinks()
    }

    private fun stopProbe(reason: String?) {
        val server = gattServer ?: return
        runCatching { server.clearServices() }
        runCatching { server.close() }
        gattServer = null
        notificationSource = null
        subscribedDevice = null
        sendButton.isEnabled = false
        reason?.let(::append)
    }

    private fun sendTestNotification() {
        val server = gattServer
        val characteristic = notificationSource
        val device = subscribedDevice
        if (server == null || characteristic == null || device == null) {
            append("未观察到 Air 对 Notification Source 的 CCCD 订阅，拒绝盲发。")
            return
        }
        val payload = AirDncsProtocol.notificationSource(
            uid = uid.incrementAndGet(),
            category = AirDncsProtocol.CATEGORY_SOCIAL
        )
        @Suppress("DEPRECATION")
        characteristic.value = payload
        val accepted = runCatching {
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }.getOrElse {
            append("发送抛错:${it.javaClass.simpleName}:${it.message}")
            false
        }
        append("DNCS Notification Source -> ${device.address}; accepted=$accepted; bytes=${payload.hex()}")
    }

    private fun createDncsService(): BluetoothGattService {
        val service = BluetoothGattService(DNCS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val control = BluetoothGattCharacteristic(
            DNCS_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val source = notifyCharacteristic(DNCS_NOTIFICATION_SOURCE_UUID)
        val data = notifyCharacteristic(DNCS_DATA_SOURCE_UUID)
        val location = BluetoothGattCharacteristic(
            DNCS_LOCATION_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).apply { addDescriptor(cccd()) }
        service.addCharacteristic(location)
        service.addCharacteristic(source)
        service.addCharacteristic(control)
        service.addCharacteristic(data)
        return service
    }

    private fun notifyCharacteristic(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply { addDescriptor(cccd()) }

    private fun cccd() = BluetoothGattDescriptor(
        CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    ).apply {
        @Suppress("DEPRECATION")
        value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(statusCode: Int, service: BluetoothGattService) {
            append("onServiceAdded status=$statusCode uuid=${service.uuid}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            append("server link ${deviceLabel(device)} status=$statusCode state=${stateName(newState)}")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            append("MTU ${device.address} -> $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            append("READ ${device.address} ${characteristic.uuid} offset=$offset")
            val value = if (characteristic.uuid == DNCS_LOCATION_STATUS_UUID) byteArrayOf() else null
            runCatching {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (value != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset,
                    value
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            append("WRITE ${device.address} ${characteristic.uuid} bytes=${value.hex()}")
            if (responseNeeded) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val enabled = descriptor.characteristic.uuid == DNCS_NOTIFICATION_SOURCE_UUID &&
                subscribedDevice?.address == device.address
            val value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            append("DESC READ ${device.address} char=${descriptor.characteristic.uuid} enabled=$enabled")
            runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            append("CCCD ${device.address} char=${descriptor.characteristic.uuid} enabled=$enabled")
            if (descriptor.characteristic.uuid == DNCS_NOTIFICATION_SOURCE_UUID) {
                subscribedDevice = if (enabled) device else null
                runOnUiThread { sendButton.isEnabled = enabled }
            }
            if (responseNeeded) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
            }
        }
    }

    private fun append(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logLines.add("$stamp $message")
        while (logLines.size > 80) logLines.removeAt(0)
        runOnUiThread { status.text = logLines.joinToString("\n") }
    }

    @Suppress("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String =
        runCatching { "${device.name ?: "?"}(${device.address})" }.getOrElse { device.address }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "DISCONNECTED"
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private val DNCS_SERVICE_UUID = UUID.fromString("16bcfd00-253f-c348-e831-0db3e334d580")
        private val DNCS_CONTROL_POINT_UUID = UUID.fromString("16bcfd01-253f-c348-e831-0db3e334d580")
        private val DNCS_NOTIFICATION_SOURCE_UUID = UUID.fromString("16bcfd02-253f-c348-e831-0db3e334d580")
        private val DNCS_DATA_SOURCE_UUID = UUID.fromString("16bcfd03-253f-c348-e831-0db3e334d580")
        private val DNCS_LOCATION_STATUS_UUID = UUID.fromString("16bcfd04-253f-c348-e831-0db3e334d580")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
