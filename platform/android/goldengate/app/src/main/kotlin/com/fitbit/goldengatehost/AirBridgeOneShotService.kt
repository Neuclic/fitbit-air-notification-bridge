package com.fitbit.goldengatehost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.GattConnection
import com.fitbit.bluetooth.fbgatt.rx.PeripheralConnectionChangeListener
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.CoapEndpointBuilder
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
import com.fitbit.goldengate.bt.DEFAULT_CLIENT_ADDRESS
import com.fitbit.goldengate.bt.DEFAULT_SERVER_ADDRESS
import com.fitbit.goldengate.bt.PeerRole
import com.fitbit.goldengate.node.stack.StackPeer
import com.fitbit.linkcontroller.LinkControllerProvider
import com.fitbit.linkcontroller.services.configuration.GeneralPurposeCommandCode
import com.fitbit.linkcontroller.services.configuration.PreferredConnectionConfiguration
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Owns the Air connection only for one queued notification task:
 * join the known system link -> Gattlink/DTLS -> haptic pattern -> verify -> disconnect our client.
 */
class AirBridgeOneShotService : Service() {
    private val queue = ArrayDeque<AirNotificationTrigger>()
    private val disposables = CompositeDisposable()
    private var processing = false
    private var activePeer: StackPeer<CoapEndpoint>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待按需震动任务"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE)
        val pattern = intent?.getStringExtra(EXTRA_PATTERN)?.let(AirHapticPattern::fromWireName)
        if (sourcePackage != null && pattern != null) {
            if (queue.size < MAX_QUEUE_SIZE) {
                queue.addLast(AirNotificationTrigger(sourcePackage, pattern))
            } else {
                updateStatus("任务队列已满，忽略 $sourcePackage")
            }
        }
        processNext()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        disposables.dispose()
        releasePeer()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun processNext() {
        if (processing) return
        val trigger = queue.pollFirst()
        if (trigger == null) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            return
        }
        acquireWakeLock()
        processing = true
        updateStatus("准备连接 Air：${trigger.sourcePackage} / ${trigger.pattern.wireName}")
        disposables.add(
            executeWithRetry(trigger, 0)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        val status = "震动成功：${trigger.sourcePackage} / ${trigger.pattern.wireName}，" +
                            "${result.haptic.groups.size} 组，强度已恢复为 " +
                            AirDirectSettingsProtocol.hapticsIntensityName(result.haptic.finalIntensity) +
                            "；交还=${result.handoff.command}；systemGatt(before/after)=" +
                            "${result.before.systemGattConnectedBefore}/" +
                            "${result.handoff.linkCycle.connected}，" +
                            "sawDisconnect=${result.handoff.linkCycle.sawDisconnected}"
                        updateStatus(status)
                        processing = false
                        processNext()
                    },
                    { throwable ->
                        val status = "失败：${trigger.sourcePackage} / ${trigger.pattern.wireName}：" +
                            "${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
                        Timber.w(throwable, status)
                        updateStatus(status)
                        processing = false
                        processNext()
                    }
                )
        )
    }

    private fun executeWithRetry(
        trigger: AirNotificationTrigger,
        attemptIndex: Int
    ): Single<DirectAirExecutionResult> = executeOnce(trigger)
        .onErrorResumeNext { throwable ->
            releasePeer()
            if (attemptIndex >= RETRY_DELAYS_MILLIS.size) {
                Single.error(throwable)
            } else {
                val delay = RETRY_DELAYS_MILLIS[attemptIndex]
                updateStatus(
                    "已知地址直连暂不可用，${delay / 1000}s 后进行第 ${attemptIndex + 2} 次尝试"
                )
                Single.timer(delay, TimeUnit.MILLISECONDS)
                    .flatMap { executeWithRetry(trigger, attemptIndex + 1) }
            }
        }

    private fun executeOnce(trigger: AirNotificationTrigger): Single<DirectAirExecutionResult> {
        val address = AirBridgeDeviceSettings.loadAddress(this)
            ?: return Single.error(IllegalStateException("尚未选择 Fitbit Air"))
        return Single.fromCallable {
            AirBridgeRuntime.ensureInitialized(this)
            address
        }.flatMap { prepareKnownDevice(it) }
            .flatMap { prepared ->
                connectStack(prepared)
                    .map { endpoint -> prepared to endpoint }
            }
            .flatMap { (prepared, endpoint) ->
                updateStatus("已建立 DTLS，正在执行 ${trigger.pattern.wireName}")
                AirHapticPatternExecutor.execute(endpoint, trigger.pattern)
                    .map { result -> prepared to result }
            }
            .flatMap { (prepared, result) ->
                updateStatus("震动完成，正在请求 Air 主动断链并把协议所有权交还 Google Health")
                handBackToGoogleHealth(prepared)
                    .map { handoff ->
                    DirectAirExecutionResult(
                        haptic = result,
                        before = prepared,
                        handoff = handoff
                    )
                }
            }
            .doOnError { releasePeer() }
    }

    /**
     * A local BluetoothGatt.disconnect() leaves Google Health's client and the physical link up,
     * but Air's DTLS endpoint has already switched to this process. Ask the tracker itself to
     * disconnect so every Android GATT client observes a real link reset and Google Health can
     * establish a fresh protocol session.
     */
    private fun handBackToGoogleHealth(prepared: PreparedAirGatt): Single<AirHandoffResult> {
        val address = prepared.connection.device.address
        val controller = LinkControllerProvider.INSTANCE.getLinkController(prepared.connection)
        val preferredDisconnect = PreferredConnectionConfiguration.Builder()
            .requestDisconnect()
            .build()
        val command = controller.setPreferredConnectionConfiguration(preferredDisconnect)
            .timeout(HANDOFF_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .andThen(Single.just("PREFERRED_CONFIG_DISCONNECT"))
            .onErrorResumeNext { preferredError ->
                controller.setGeneralPurposeCommand(GeneralPurposeCommandCode.DISCONNECT)
                    .timeout(HANDOFF_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .andThen(Single.just("GENERAL_PURPOSE_DISCONNECT"))
                    .onErrorReturn { generalError ->
                        "UNAVAILABLE:" +
                            "${preferredError.javaClass.simpleName}/" +
                            generalError.javaClass.simpleName
                    }
            }
        return command
            .flatMap { commandName ->
                Single.timer(HANDOFF_COMMAND_SETTLE_MILLIS, TimeUnit.MILLISECONDS)
                    .map { commandName }
            }
            .flatMap { commandName ->
                releasePeer()
                observeSystemGattCycle(address)
                    .map { linkCycle -> AirHandoffResult(commandName, linkCycle) }
            }
    }

    private fun observeSystemGattCycle(address: String): Single<SystemGattCycle> {
        val initialConnected = isSystemGattConnected(address)
        val initial = SystemGattCycle(
            connected = initialConnected,
            sawDisconnected = !initialConnected
        )
        return Observable.interval(
            0,
            HANDOFF_POLL_MILLIS,
            TimeUnit.MILLISECONDS,
            Schedulers.computation()
        )
            .take(HANDOFF_OBSERVE_MILLIS / HANDOFF_POLL_MILLIS)
            .map { isSystemGattConnected(address) }
            .scan(initial) { state, connected ->
                SystemGattCycle(
                    connected = connected,
                    sawDisconnected = state.sawDisconnected || !connected
                )
            }
            .takeUntil { it.sawDisconnected && it.connected }
            .lastOrError()
    }

    /**
     * Register the saved Bluetooth address with BitGatt without scanning. Android can expose a
     * device already connected on the system GATT profile even when the peripheral is not
     * advertising. BitGatt's own connected-device import is asynchronous, so give it a short
     * bounded window before creating the local connection record ourselves.
     */
    private fun prepareKnownDevice(address: String): Single<PreparedAirGatt> = Single.defer {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: return@defer Single.error(IllegalStateException("Bluetooth adapter unavailable"))
        if (!adapter.isEnabled) {
            return@defer Single.error(IllegalStateException("Bluetooth is disabled"))
        }
        val systemConnectedBefore = isSystemGattConnected(address)
        val fitbitGatt = FitbitGatt.getInstance()
        fitbitGatt.addConnectedDevices()
        updateStatus(
            "不扫描：按已保存地址加入 GATT；systemGatt=" +
                if (systemConnectedBefore) "CONNECTED" else "NOT_CONNECTED"
        )
        Single.timer(KNOWN_DEVICE_IMPORT_SETTLE_MILLIS, TimeUnit.MILLISECONDS)
            .map {
                val existing = fitbitGatt.getMatchingConnectionsForDeviceNames(null)
                    .firstOrNull { it.device.address.equals(address, ignoreCase = true) }
                val connection = existing ?: run {
                    val wrappedDevice = FitbitBluetoothDevice(adapter.getRemoteDevice(address))
                    GattConnection(wrappedDevice, Looper.getMainLooper()).also {
                        fitbitGatt.putConnectionIntoDevices(wrappedDevice, it)
                    }
                }
                PreparedAirGatt(
                    connection = connection,
                    systemGattConnectedBefore = systemConnectedBefore,
                    localGattConnectedBefore = connection.isConnected,
                    registration = if (existing == null) "KNOWN_ADDRESS" else "SYSTEM_IMPORT"
                )
            }
    }

    private fun isSystemGattConnected(address: String): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT)
                .any { it.address.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun connectStack(prepared: PreparedAirGatt): Single<CoapEndpoint> {
        val address = prepared.connection.device.address
        updateStatus(
            "正在建立 Gattlink / DTLS；systemGatt=" +
                "${if (prepared.systemGattConnectedBefore) "CONNECTED" else "NOT_CONNECTED"}, " +
                "localGatt=${if (prepared.localGattConnectedBefore) "CONNECTED" else "NEW"}, " +
                "source=${prepared.registration}"
        )
        return connectStack(address).onErrorResumeNext { error ->
            Single.error(
                IllegalStateException(
                    "阶段=Gattlink/DTLS；systemGatt=" +
                        "${prepared.systemGattConnectedBefore}, localGatt=" +
                        "${prepared.localGattConnectedBefore}, source=${prepared.registration}；" +
                        "${error.javaClass.simpleName}: ${error.message ?: "unknown"}",
                    error
                )
            )
        }
    }

    private fun connectStack(address: String): Single<CoapEndpoint> {
        DiagnosticTlsIdentityRecorder.clear()
        val stackConfig = DtlsSocketNetifGattlink(
            Inet4Address.getByName(DEFAULT_SERVER_ADDRESS) as Inet4Address,
            0,
            Inet4Address.getByName(DEFAULT_CLIENT_ADDRESS) as Inet4Address,
            0
        )
        val peer = StackPeer(
            BluetoothAddressNodeKey(address),
            PeerRole.Peripheral,
            stackConfig,
            CoapEndpointBuilder(),
            { connection -> PeripheralConnectionChangeListener().register(connection) },
            { stack -> stack.dtlsEventObservable }
        )
        activePeer = peer
        return peer.connection()
            .firstOrError()
            .timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { peer.stackService }
    }

    @Synchronized
    private fun releasePeer() {
        activePeer?.let { peer ->
            activePeer = null
            runCatching { peer.close() }
                .onFailure { Timber.w(it, "Failed to close one-shot Air peer") }
        }
    }

    private fun updateStatus(status: String) {
        Timber.i("Air one-shot: %s", status)
        AirBridgeDeviceSettings.saveLastStatus(this, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitbitairbridge:one-shot")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.jiji)
        .setContentTitle("Fitbit Air Notification Bridge")
        .setContentText(status)
        .setStyle(NotificationCompat.BigTextStyle().bigText(status))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, SetupActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Fitbit Air 按需连接",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val EXTRA_SOURCE_PACKAGE = "source_package"
        private const val EXTRA_PATTERN = "pattern"
        private const val CHANNEL_ID = "air_bridge_connection"
        private const val NOTIFICATION_ID = 14001
        private const val MAX_QUEUE_SIZE = 10
        private const val CONNECT_TIMEOUT_SECONDS = 18L
        private const val KNOWN_DEVICE_IMPORT_SETTLE_MILLIS = 500L
        private const val HANDOFF_COMMAND_TIMEOUT_SECONDS = 2L
        private const val HANDOFF_COMMAND_SETTLE_MILLIS = 350L
        private const val HANDOFF_POLL_MILLIS = 250L
        private const val HANDOFF_OBSERVE_MILLIS = 12_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 120_000L
        private val RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L)

        fun enqueue(context: Context, trigger: AirNotificationTrigger): Boolean {
            if (AirBridgeDeviceSettings.loadAddress(context) == null) return false
            val intent = Intent(context, AirBridgeOneShotService::class.java)
                .putExtra(EXTRA_SOURCE_PACKAGE, trigger.sourcePackage)
                .putExtra(EXTRA_PATTERN, trigger.pattern.wireName)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Timber.w(it, "Unable to start one-shot Air service")
                AirBridgeDeviceSettings.saveLastStatus(
                    context,
                    "无法启动后台任务：${it.javaClass.simpleName}: ${it.message ?: "unknown"}"
                )
                false
            }
        }
    }

    private data class PreparedAirGatt(
        val connection: GattConnection,
        val systemGattConnectedBefore: Boolean,
        val localGattConnectedBefore: Boolean,
        val registration: String
    )

    private data class DirectAirExecutionResult(
        val haptic: AirHapticExecutionResult,
        val before: PreparedAirGatt,
        val handoff: AirHandoffResult
    )

    private data class AirHandoffResult(
        val command: String,
        val linkCycle: SystemGattCycle
    )

    private data class SystemGattCycle(
        val connected: Boolean,
        val sawDisconnected: Boolean
    )
}
