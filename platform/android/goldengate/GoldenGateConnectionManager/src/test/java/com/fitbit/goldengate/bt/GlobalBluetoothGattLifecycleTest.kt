package com.fitbit.goldengate.bt

import android.content.Context
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.GattServerConnection
import com.fitbit.bluetooth.fbgatt.rx.server.BitGattServer
import com.fitbit.goldengate.bt.gatt.GattServerListenerRegistrar
import com.fitbit.goldengate.bt.gatt.server.services.gattcache.GattCacheServiceHandler
import com.fitbit.linkcontroller.LinkControllerProvider
import io.reactivex.Completable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GlobalBluetoothGattLifecycleTest {
    private val context = mock<Context>()
    private val gatt = mock<FitbitGatt>()
    private val server = mock<GattServerConnection>()
    private val registrar = mock<GattServerListenerRegistrar>()
    private val controller = mock<LinkControllerProvider>()
    private val initializer = GlobalBluetoothGattInitializer(
        fitbitGatt = gatt,
        gattServer = mock<BitGattServer>(),
        gattServerListenerRegistrar = registrar,
        linkControllerProvider = controller,
        gattCacheServiceHandler = mock<GattCacheServiceHandler>(),
        gattlinkServiceProvider = { error("Peripheral services should not be created") }
    )

    @Before
    fun setup() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        whenever(gatt.server).thenReturn(server)
        whenever(controller.addLinkConfigurationService()).thenReturn(Completable.complete())
    }

    @After
    fun cleanup() {
        initializer.stop()
        RxJavaPlugins.reset()
    }

    @Test
    fun `stop removes the Bluetooth callback registered at start`() {
        initializer.start(context)
        val callback = argumentCaptor<FitbitGatt.FitbitGattCallback>()
        verify(gatt).registerGattEventListener(callback.capture())

        initializer.stop()

        verify(gatt).unregisterGattEventListener(callback.firstValue)
    }

    @Test
    fun `stop cancels pending GATT service registration`() {
        val pending = CompletableSubject.create()
        whenever(registrar.registerGattServerListeners(any())).thenReturn(pending)
        initializer.start(context)
        val callback = argumentCaptor<FitbitGatt.FitbitGattCallback>()
        verify(gatt).registerGattEventListener(callback.capture())
        callback.firstValue.onGattServerStarted(server)
        assertTrue(pending.hasObservers())

        initializer.stop()

        assertFalse(pending.hasObservers())
    }

    @Test
    fun `start after stop can register GATT services again`() {
        val first = CompletableSubject.create()
        val second = CompletableSubject.create()
        whenever(registrar.registerGattServerListeners(any())).thenReturn(first, second)
        initializer.start(context)
        val callback = argumentCaptor<FitbitGatt.FitbitGattCallback>()
        verify(gatt).registerGattEventListener(callback.capture())
        callback.firstValue.onGattServerStarted(server)
        initializer.stop()

        initializer.start(context)
        callback.firstValue.onGattServerStarted(server)

        assertFalse(first.hasObservers())
        assertTrue(second.hasObservers())
        second.onComplete()
    }
}
