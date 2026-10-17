package com.vinaykpro.ludoking

import com.vinaykpro.ludoking.network.LudoMessageFramer
import com.vinaykpro.ludoking.network.LudoProtocol
import com.vinaykpro.ludoking.network.LudoProtocolMessage
import com.vinaykpro.ludoking.network.LudoTcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LudoProtocolAndServerTest {

    // ==========================================
    // 1. Protocol Models & JSON Validation
    // ==========================================

    @Test
    fun testValidHandshakeParsingAndSerialization() {
        val json = """{"type":"HANDSHAKE","protocolVersion":1,"role":"CONTROLLER","requestId":"hs_test_123","timestamp":1700000000000}"""
        val parsed = LudoProtocolMessage.fromJson(json)

        assertNotNull(parsed)
        assertEquals(LudoProtocol.MessageType.HANDSHAKE, parsed?.type)
        assertEquals("CONTROLLER", parsed?.role)
        assertEquals("hs_test_123", parsed?.requestId)
        assertEquals(1, parsed?.protocolVersion)

        // Test outgoing serialization
        val ack = LudoProtocolMessage.createHandshakeAck(parsed!!.requestId!!, "OK")
        assertEquals(LudoProtocol.MessageType.HANDSHAKE_ACK, ack.type)
        assertEquals("hs_test_123", ack.requestId)
        assertEquals("OK", ack.status)
        assertEquals("LUDO_HOST", ack.role)

        val wireString = ack.toTransmissionString()
        assertTrue(wireString.endsWith("\n"))
        val roundTrip = LudoProtocolMessage.fromJson(wireString)
        assertNotNull(roundTrip)
        assertEquals(LudoProtocol.MessageType.HANDSHAKE_ACK, roundTrip?.type)
        assertEquals("hs_test_123", roundTrip?.requestId)
        assertEquals("OK", roundTrip?.status)
    }

    @Test
    fun testNumberSelectionWithAliasesAndChannelParsing() {
        // Test parsing with alias "SELECT_NUMBER" and fallback key "controllerBoxId"
        val json = """{"type":"SELECT_NUMBER","controllerBoxId":"B3","result":6,"requestId":"cmd_999"}"""
        val msg = LudoProtocolMessage.fromJson(json)

        assertNotNull(msg)
        assertEquals(LudoProtocol.MessageType.NUMBER_SELECTION, msg?.type)
        assertEquals("B3", msg?.boxId)
        assertEquals(3, msg?.channel)
        assertEquals(6, msg?.value)
        assertEquals("cmd_999", msg?.requestId)

        // Test Number Result creation
        val result = LudoProtocolMessage.createNumberResult("cmd_999", "B3", 3, 6)
        assertEquals(LudoProtocol.MessageType.NUMBER_RESULT, result.type)
        assertEquals(6, result.value)
        assertEquals("cmd_999", result.requestId)
        assertEquals("B3", result.boxId)
    }

    @Test
    fun testMalformedJsonRejection() {
        assertNull(LudoProtocolMessage.fromJson(null))
        assertNull(LudoProtocolMessage.fromJson(""))
        assertNull(LudoProtocolMessage.fromJson("   "))
        assertNull(LudoProtocolMessage.fromJson("{invalid-json"))
        assertNull(LudoProtocolMessage.fromJson("plain text string"))
    }

    // ==========================================
    // 2. Message Framing
    // ==========================================

    @Test
    fun testFramingSingleMessage() {
        val framer = LudoMessageFramer()
        val frames = framer.feedChunk("""{"type":"PING"}${'\n'}""")

        assertEquals(1, frames.size)
        assertEquals("""{"type":"PING"}""", frames[0])
        assertEquals("", framer.remainingBuffer)
    }

    @Test
    fun testFramingSplitAcrossMultipleReads() {
        val framer = LudoMessageFramer()

        // Read 1: first half
        val frames1 = framer.feedChunk("""{"type":"HANDSHAKE","req""")
        assertEquals(0, frames1.size)
        assertEquals("""{"type":"HANDSHAKE","req""", framer.remainingBuffer)

        // Read 2: second half + newline
        val frames2 = framer.feedChunk("""uestId":"123"}${'\n'}""")
        assertEquals(1, frames2.size)
        assertEquals("""{"type":"HANDSHAKE","requestId":"123"}""", frames2[0])
        assertEquals("", framer.remainingBuffer)
    }

    @Test
    fun testFramingMultipleMessagesInSingleRead() {
        val framer = LudoMessageFramer()
        val chunk = """{"type":"PING"}${'\n'}{"type":"GET_CONFIG"}${'\r'}${'\n'}"""
        val frames = framer.feedChunk(chunk)

        assertEquals(2, frames.size)
        assertEquals("""{"type":"PING"}""", frames[0])
        assertEquals("""{"type":"GET_CONFIG"}""", frames[1])
    }

    @Test
    fun testFramingOversizedMessageRejection() {
        val framer = LudoMessageFramer(100) // 100 char limit for test

        val hugePayload = "A".repeat(150)
        val frames1 = framer.feedChunk(hugePayload + "\n")
        // Oversized frame must be discarded
        assertEquals(0, frames1.size)

        // Subsequent valid frame should process cleanly
        val frames2 = framer.feedChunk("""{"type":"PING"}${'\n'}""")
        assertEquals(1, frames2.size)
        assertEquals("""{"type":"PING"}""", frames2[0])
    }

    // ==========================================
    // 3. TCP Server Operation
    // ==========================================

    @Test
    fun testTcpServerLifecycleAndClientEcho() {
        val actualPort = AtomicInteger(0)
        val serverStartedLatch = CountDownLatch(1)
        val clientConnectedLatch = CountDownLatch(1)
        val messageReceivedLatch = CountDownLatch(1)
        val clientDisconnectedLatch = CountDownLatch(1)
        val serverStoppedLatch = CountDownLatch(1)

        var receivedMessage: LudoProtocolMessage? = null

        val server = LudoTcpServer(object : LudoTcpServer.ServerListener {
            override fun onServerStarted(port: Int) {
                actualPort.set(port)
                serverStartedLatch.countDown()
            }

            override fun onServerStopped() {
                serverStoppedLatch.countDown()
            }

            override fun onServerError(error: String, cause: Throwable?) {}

            override fun onClientConnected(clientId: String, remoteAddress: String) {
                clientConnectedLatch.countDown()
            }

            override fun onClientDisconnected(clientId: String, reason: String) {
                clientDisconnectedLatch.countDown()
            }

            override fun onMessageReceived(clientId: String, message: LudoProtocolMessage) {
                receivedMessage = message
                messageReceivedLatch.countDown()
            }
        })

        // 1. Start server with ephemeral port 0 (OS assigns free port)
        assertTrue(server.start(0))
        assertTrue(serverStartedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server.isRunning)
        val port = actualPort.get()
        assertTrue("Port must be > 0", port > 0)

        // 2. Connect test client socket
        val clientSocket = Socket()
        clientSocket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        assertTrue(clientConnectedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.activeClientCount)

        // 3. Send protocol message from client
        val writer = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))
        val pingMsg = """{"type":"PING","requestId":"test_ping_1"}${'\n'}"""
        writer.write(pingMsg)
        writer.flush()

        assertTrue(messageReceivedLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        assertEquals(LudoProtocol.MessageType.PING, receivedMessage?.type)
        assertEquals("test_ping_1", receivedMessage?.requestId)

        // 4. Disconnect client
        clientSocket.close()
        assertTrue(clientDisconnectedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(0, server.activeClientCount)

        // 5. Stop server
        server.stop()
        assertTrue(serverStoppedLatch.await(5, TimeUnit.SECONDS))
        assertFalse(server.isRunning)
    }

    @Test
    fun testServerRepeatedStartStopSafety() {
        val server = LudoTcpServer(null)

        assertTrue(server.start(0))
        Thread.sleep(150)
        assertTrue(server.isRunning)

        server.stop()
        Thread.sleep(150)
        assertFalse(server.isRunning)

        // Second clean start
        assertTrue(server.start(0))
        Thread.sleep(150)
        assertTrue(server.isRunning)

        server.stop()
        Thread.sleep(150)
        assertFalse(server.isRunning)
    }

    @Test
    fun testFramingIncompleteFrameAtDisconnect() {
        val framer = LudoMessageFramer()
        // Client sends half a message then socket terminates without newline
        val frames = framer.feedChunk("""{"type":"HANDSHAKE","partial":true""")
        assertEquals(0, frames.size)
        // Incomplete content safely retained in remainingBuffer without crashing or framing
        assertEquals("""{"type":"HANDSHAKE","partial":true""", framer.remainingBuffer)
        framer.reset()
        assertEquals("", framer.remainingBuffer)
    }

    @Test
    fun testDisconnectMessageClosesClientConnection() {
        val server = LudoTcpServer(null)
        assertTrue(server.start(0))
        var attempts = 0
        while (server.boundPort <= 0 && attempts++ < 50) {
            Thread.sleep(20)
        }
        val port = server.boundPort
        assertTrue(port > 0)

        val client = Socket("127.0.0.1", port)
        Thread.sleep(100)
        assertEquals(1, server.activeClientCount)
        val clientId = server.connectedClientIds[0]

        // Trigger disconnect via disconnectClient
        server.disconnectClient(clientId, "Client requested disconnect")
        Thread.sleep(100)
        assertEquals(0, server.activeClientCount)

        server.stop()
        client.close()
    }
}
