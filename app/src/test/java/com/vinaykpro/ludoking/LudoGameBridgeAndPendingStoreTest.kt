package com.vinaykpro.ludoking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vinaykpro.ludoking.network.LudoGameBridge
import com.vinaykpro.ludoking.network.LudoHostManager
import com.vinaykpro.ludoking.network.LudoNsdHelper
import com.vinaykpro.ludoking.network.LudoProtocol
import com.vinaykpro.ludoking.network.LudoProtocolMessage
import com.vinaykpro.ludoking.network.PendingNumberStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LudoGameBridgeAndPendingStoreTest {

    private lateinit var context: Context
    private lateinit var store: PendingNumberStore
    private lateinit var bridge: LudoGameBridge

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PendingNumberStore()
        bridge = LudoGameBridge.getInstance(context)
        bridge.pendingNumberStore.clear()
    }

    // ==========================================
    // 1. PendingNumberStore Tests
    // ==========================================

    @Test
    fun testEnqueueAndPollFifoPerChannel() {
        val cmd1 = PendingNumberStore.PendingCommand("req_1", "B1", 1, 6, "client_a", System.currentTimeMillis())
        val cmd2 = PendingNumberStore.PendingCommand("req_2", "B1", 1, 4, "client_a", System.currentTimeMillis() + 10)

        assertTrue(store.enqueue(cmd1))
        assertTrue(store.enqueue(cmd2))

        assertEquals(2, store.totalPendingCount)
        assertTrue(store.hasPendingForChannel(1))
        assertFalse(store.hasPendingForChannel(2))

        val polled1 = store.pollForChannel(1)
        assertNotNull(polled1)
        assertEquals("req_1", polled1?.requestId)
        assertEquals(6, polled1?.value)
        assertEquals(1, store.totalPendingCount)

        val polled2 = store.pollForChannel(1)
        assertNotNull(polled2)
        assertEquals("req_2", polled2?.requestId)
        assertEquals(4, polled2?.value)
        assertEquals(0, store.totalPendingCount)

        // Queue now empty
        assertNull(store.pollForChannel(1))
    }

    @Test
    fun testDuplicateRequestIdRejection() {
        val cmd1 = PendingNumberStore.PendingCommand("dup_req_1", "B1", 1, 6, "client_a", System.currentTimeMillis())
        val cmdDup = PendingNumberStore.PendingCommand("dup_req_1", "B1", 1, 3, "client_a", System.currentTimeMillis())

        assertTrue(store.enqueue(cmd1))
        // Duplicate active requestId must be rejected
        assertFalse("Duplicate active requestId must be rejected", store.enqueue(cmdDup))
        assertEquals(1, store.totalPendingCount)

        // Poll and mark completed
        val polled = store.pollForChannel(1)
        assertNotNull(polled)
        store.markCompleted("dup_req_1")

        // Re-enqueuing an already completed requestId must also be rejected
        val cmdReplay = PendingNumberStore.PendingCommand("dup_req_1", "B1", 1, 5, "client_b", System.currentTimeMillis())
        assertFalse("Completed requestId must not be allowed to replay", store.enqueue(cmdReplay))
    }

    @Test
    fun testMultiChannelIndependence() {
        val cmdCh1 = PendingNumberStore.PendingCommand("req_ch1", "B1", 1, 6, "client_1", System.currentTimeMillis())
        val cmdCh2 = PendingNumberStore.PendingCommand("req_ch2", "B2", 2, 5, "client_2", System.currentTimeMillis())
        val cmdCh3 = PendingNumberStore.PendingCommand("req_ch3", "B3", 3, 3, "client_3", System.currentTimeMillis())
        val cmdCh4 = PendingNumberStore.PendingCommand("req_ch4", "B4", 4, 1, "client_4", System.currentTimeMillis())

        store.enqueue(cmdCh1)
        store.enqueue(cmdCh2)
        store.enqueue(cmdCh3)
        store.enqueue(cmdCh4)

        assertEquals(4, store.totalPendingCount)
        assertTrue(store.hasPendingForChannel(1))
        assertTrue(store.hasPendingForChannel(2))
        assertTrue(store.hasPendingForChannel(3))
        assertTrue(store.hasPendingForChannel(4))

        // Poll channel 3 first - others should remain intact
        val polledCh3 = store.pollForChannel(3)
        assertEquals("req_ch3", polledCh3?.requestId)
        assertEquals(3, polledCh3?.value)
        assertEquals(3, store.totalPendingCount)
        assertFalse(store.hasPendingForChannel(3))
        assertTrue(store.hasPendingForChannel(1))
        assertTrue(store.hasPendingForChannel(2))
        assertTrue(store.hasPendingForChannel(4))

        // Clear remaining
        store.clear()
        assertEquals(0, store.totalPendingCount)
        assertNull(store.pollForChannel(1))
    }

    @Test
    fun testRemoveByRequestId() {
        val cmd = PendingNumberStore.PendingCommand("req_remove", "B2", 2, 6, "client_x", System.currentTimeMillis())
        store.enqueue(cmd)
        assertEquals(1, store.totalPendingCount)

        val removed = store.removeByRequestId("req_remove")
        assertTrue(removed)
        assertEquals(0, store.totalPendingCount)
        assertFalse(store.hasPendingForChannel(2))
    }

    // ==========================================
    // 2. Channel & Player Mapping Tests
    // ==========================================

    @Test
    fun testPlayerPositionAndColorMapping() {
        // Position-based mapping (Classic Ludo quadrant)
        // Pos 1 (Top-Left) -> Channel 2 (Green / B2)
        assertEquals(2, bridge.getChannelForPlayer(1, null))
        // Pos 2 (Top-Right) -> Channel 3 (Yellow / B3)
        assertEquals(3, bridge.getChannelForPlayer(2, null))
        // Pos 3 (Bottom-Left) -> Channel 1 (Red / B1)
        assertEquals(1, bridge.getChannelForPlayer(3, null))
        // Pos 4 (Bottom-Right) -> Channel 4 (Blue / B4)
        assertEquals(4, bridge.getChannelForPlayer(4, null))

        // Color-based mapping
        assertEquals(1, bridge.getChannelForPlayer(3, "red"))
        assertEquals(2, bridge.getChannelForPlayer(1, "green"))
        assertEquals(3, bridge.getChannelForPlayer(2, "yellow"))
        assertEquals(4, bridge.getChannelForPlayer(4, "blue"))

        // Box ID mapping
        assertEquals("B1", LudoGameBridge.getBoxIdForChannel(1))
        assertEquals("B2", LudoGameBridge.getBoxIdForChannel(2))
        assertEquals("B3", LudoGameBridge.getBoxIdForChannel(3))
        assertEquals("B4", LudoGameBridge.getBoxIdForChannel(4))

        assertEquals(1, LudoGameBridge.getChannelForBoxId("B1"))
        assertEquals(2, LudoGameBridge.getChannelForBoxId("B2"))
        assertEquals(3, LudoGameBridge.getChannelForBoxId("B3"))
        assertEquals(4, LudoGameBridge.getChannelForBoxId("B4"))
    }

    @Test
    fun testTwoPlayerModeAddressingRedAndYellow() {
        // In 2-player mode, Red is Player 1 (pos 3) -> Channel 1 (B1)
        val redChannel = bridge.getChannelForPlayer(3, "red")
        assertEquals(1, redChannel)
        assertEquals("B1", LudoGameBridge.getBoxIdForChannel(redChannel))

        // In 2-player mode, Yellow is Player 2 (pos 2) -> Channel 3 (B3)
        val yellowChannel = bridge.getChannelForPlayer(2, "yellow")
        assertEquals(3, yellowChannel)
        assertEquals("B3", LudoGameBridge.getBoxIdForChannel(yellowChannel))

        // Independent pending commands can be queued for both Red and Yellow
        val redCmd = PendingNumberStore.PendingCommand("cmd_red_1", "B1", 1, 6, "client_1", System.currentTimeMillis())
        val yellowCmd = PendingNumberStore.PendingCommand("cmd_yellow_1", "B3", 3, 5, "client_1", System.currentTimeMillis())

        bridge.pendingNumberStore.enqueue(redCmd)
        bridge.pendingNumberStore.enqueue(yellowCmd)

        // Red rolling consumes only Channel 1
        val redRoll = bridge.prepareDiceRoll(redChannel, 1)
        assertEquals(6, redRoll.value)
        redRoll.confirmApplied()

        // Yellow pending command remains untouched in Channel 3
        assertTrue(bridge.pendingNumberStore.hasPendingForChannel(3))
        val yellowRoll = bridge.prepareDiceRoll(yellowChannel, 1)
        assertEquals(5, yellowRoll.value)
        yellowRoll.confirmApplied()

        assertFalse(bridge.pendingNumberStore.hasPendingForChannel(3))
    }

    // ==========================================
    // 3. Two-Phase Dice Roll & NUMBER_RESULT Confirmation
    // ==========================================

    @Test
    fun testFallbackToNaturalRollWhenNoCommandPending() {
        bridge.pendingNumberStore.clear()

        // When no command is queued for channel 1, consumeDiceRoll must return the natural random value
        val naturalVal = bridge.consumeDiceRoll(1, 4)
        assertEquals(4, naturalVal)
    }

    @Test
    fun testTwoPhasePrepareAndConfirmApplied() {
        bridge.pendingNumberStore.clear()

        val selectionJson = """{"type":"NUMBER_SELECTION","boxId":"B2","channel":2,"value":5,"requestId":"cmd_two_phase_77"}"""
        val message = LudoProtocolMessage.fromJson(selectionJson)
        assertNotNull(message)
        bridge.handleNumberSelection("mock_client_id", message!!)

        var observerCalled = false
        val observer = LudoGameBridge.DiceRollObserver { _, _, _, _, _ ->
            observerCalled = true
        }
        bridge.addObserver(observer)

        // Phase 1: Prepare roll
        val appliedRoll = bridge.prepareDiceRoll(2, 1)
        assertEquals(5, appliedRoll.value)
        assertTrue(appliedRoll.isFromController)

        // Before confirmApplied() is called, observer must NOT have been notified yet
        assertFalse("Observer must not be notified before dice is confirmed applied", observerCalled)

        // Phase 2: Confirm applied
        appliedRoll.confirmApplied()
        assertTrue("Observer must be notified after dice is confirmed applied", observerCalled)

        // Calling confirmApplied() again must be a no-op (idempotent, single delivery)
        observerCalled = false
        appliedRoll.confirmApplied()
        assertFalse("Secondary confirmApplied call must not re-dispatch", observerCalled)

        bridge.removeObserver(observer)
    }

    @Test
    fun testClearingPendingStoreDoesNotCorruptInFlightRoll() {
        bridge.pendingNumberStore.clear()

        val selectionJson = """{"type":"NUMBER_SELECTION","boxId":"B1","channel":1,"value":6,"requestId":"cmd_inflight_88"}"""
        bridge.handleNumberSelection("mock_client", LudoProtocolMessage.fromJson(selectionJson)!!)

        // Dice roll starts and captures command in flight
        val inFlightRoll = bridge.prepareDiceRoll(1, 2)
        assertEquals(6, inFlightRoll.value)

        // User or system clears pending queue while dice is rolling
        bridge.pendingNumberStore.clear()
        assertEquals(0, bridge.pendingNumberStore.totalPendingCount)

        // In-flight roll safely finishes and confirms without crash
        inFlightRoll.confirmApplied()
        assertTrue(bridge.pendingNumberStore.isCompleted("cmd_inflight_88"))
    }

    // ==========================================
    // 4. Local IP Resolution & NSD Checks
    // ==========================================

    @Test
    fun testLocalIpAddressResolution() {
        val ip = LudoNsdHelper.getLocalIpAddress(context)
        assertNotNull(ip)
        assertFalse(ip.isEmpty())
    }
}
