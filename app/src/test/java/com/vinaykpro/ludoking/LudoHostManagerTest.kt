package com.vinaykpro.ludoking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vinaykpro.ludoking.network.LudoHostManager
import com.vinaykpro.ludoking.network.LudoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LudoHostManagerTest {

    @Test
    fun testApplicationScopedSingleton() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instance1 = LudoHostManager.getInstance(context)
        val instance2 = LudoHostManager.getInstance(context)

        assertNotNull(instance1)
        assertSame("LudoHostManager must return the exact same Application-scoped instance", instance1, instance2)
    }

    @Test
    fun testInitialStateIsStopped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)
        assertFalse(manager.isListening)
        assertFalse(manager.isActive)
        assertEquals(0, manager.clientCount)
    }

    @Test
    fun testStartAndStopLifecycleTransitions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        val freePort = ServerSocket(0).use { it.localPort }
        assertTrue(manager.setConfiguredPort(freePort))

        val recordedStates = mutableListOf<LudoHostManager.ServerState>()
        val listener = LudoHostManager.StateListener { newState, _ ->
            recordedStates.add(newState)
        }

        manager.registerListener(listener)
        ShadowLooper.idleMainLooper()

        assertTrue(recordedStates.contains(LudoHostManager.ServerState.STOPPED))
        recordedStates.clear()

        // 1. Start host
        manager.startHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertTrue(manager.isListening)
        assertEquals(LudoHostManager.ServerState.LISTENING, manager.state)

        // 2. Stop host
        manager.stopHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)
        assertFalse(manager.isListening)
    }

    @Test
    fun testRestartAfterShutdown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        val freePort = ServerSocket(0).use { it.localPort }
        manager.setConfiguredPort(freePort)

        // Initial start
        manager.startHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        assertTrue(manager.isListening)

        // Restart
        manager.restartHost()
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()

        assertTrue(manager.isListening)
        assertEquals(LudoHostManager.ServerState.LISTENING, manager.state)

        manager.stopHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)
    }

    @Test
    fun testDuplicateStartAndRepeatedStopSafety() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        val freePort = ServerSocket(0).use { it.localPort }
        manager.setConfiguredPort(freePort)

        manager.startHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        assertEquals(LudoHostManager.ServerState.LISTENING, manager.state)

        // Duplicate start request: must remain LISTENING safely
        manager.startHost()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals(LudoHostManager.ServerState.LISTENING, manager.state)

        // Stop host
        manager.stopHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)

        // Repeated stop call: must remain STOPPED safely
        manager.stopHost()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)
    }

    @Test
    fun testBindFailureTransitionsToErrorWithoutFalseListening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        // Occupy a port with an existing ServerSocket
        val blockingSocket = ServerSocket(0)
        val occupiedPort = blockingSocket.localPort

        try {
            manager.setConfiguredPort(occupiedPort)

            val errorLatch = CountDownLatch(1)
            var reachedListening = false

            manager.registerStateListener { newState, _ ->
                if (newState == LudoHostManager.ServerState.LISTENING) {
                    reachedListening = true
                }
                if (newState == LudoHostManager.ServerState.ERROR) {
                    errorLatch.countDown()
                }
            }

            manager.startHost()
            errorLatch.await(3, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()

            assertFalse("Must never report LISTENING on bind failure", reachedListening)
            assertEquals(LudoHostManager.ServerState.ERROR, manager.state)
            assertNotNull(manager.lastError)
        } finally {
            blockingSocket.close()
            manager.stopHost()
            Thread.sleep(100)
        }
    }

    @Test
    fun testShutdownWithConnectedClients() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        val freePort = ServerSocket(0).use { it.localPort }
        manager.setConfiguredPort(freePort)

        val clientCountRecorded = AtomicInteger(0)
        manager.registerClientListener { count, _ ->
            clientCountRecorded.set(count)
        }

        manager.startHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        // Connect client socket
        val client = Socket()
        client.connect(InetSocketAddress("127.0.0.1", freePort), 2000)
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()

        assertEquals(1, manager.clientCount)

        // Stop host: must close all clients and reset count to 0
        manager.stopHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(0, manager.clientCount)
        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)
        val readEof = try { client.getInputStream().read() } catch (e: Exception) { -1 }
        assertTrue(readEof == -1 || client.isClosed)
        client.close()
    }

    @Test
    fun testResilientExecutorRecreationAfterDestroy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        // Call destroy (simulates cleanup)
        manager.destroy()
        assertEquals(LudoHostManager.ServerState.STOPPED, manager.state)

        // Starting host after destroy must safely recreate background executor without crashing
        val freePort = ServerSocket(0).use { it.localPort }
        manager.setConfiguredPort(freePort)
        manager.startHost()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        assertEquals(LudoHostManager.ServerState.LISTENING, manager.state)

        manager.stopHost()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun testConfigurationValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LudoHostManager(context)

        // Default must be 4
        assertEquals(LudoProtocol.DEFAULT_BOX_COUNT, manager.activeBoxCount)

        // Valid ranges (2..4)
        assertTrue(manager.setActiveBoxCount(2))
        assertEquals(2, manager.activeBoxCount)
        assertTrue(manager.setActiveBoxCount(3))
        assertEquals(3, manager.activeBoxCount)
        assertTrue(manager.setActiveBoxCount(4))
        assertEquals(4, manager.activeBoxCount)

        // Out of range (beyond Controller verified capability) rejected
        assertFalse(manager.setActiveBoxCount(1))
        assertFalse(manager.setActiveBoxCount(5))
        assertFalse(manager.setActiveBoxCount(6))
        assertEquals(4, manager.activeBoxCount)

        // Port configuration
        assertTrue(manager.setConfiguredPort(9000))
        assertEquals(9000, manager.configuredPort)
        assertFalse(manager.setConfiguredPort(80)) // below 1024
        assertFalse(manager.setConfiguredPort(70000)) // exceeds 65535
        assertEquals(9000, manager.configuredPort)
    }
}
