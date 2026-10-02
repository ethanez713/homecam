package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.protocols.Protocol
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The signaling server relies on Java-WebSocket refusing an oversized frame when built with
 * Draft_6455's input limit. This pins that behaviour to the library version in the lock.
 */
@RunWith(AndroidJUnit4::class)
class FrameLimitTest {

    private val limit = 256 * 1024

    private class Echo(limit: Int) : WebSocketServer(
        InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0),
        1,
        listOf(Draft_6455(listOf(DefaultExtension()), listOf(Protocol("")), limit)),
    ) {
        val received = java.util.concurrent.atomic.AtomicInteger()
        val ready = CountDownLatch(1)
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) { }
        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {  }
        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onMessage(conn: WebSocket, message: ByteBuffer) { received.addAndGet(message.remaining()) }
        override fun onError(conn: WebSocket?, ex: Exception) {  }
        override fun onStart() { ready.countDown() }
    }

    private class Client(port: Int) : WebSocketClient(URI("ws://127.0.0.1:$port")) {
        val closed = CountDownLatch(1)
        @Volatile var closeCode = 0
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String?) {}
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closeCode = code; closed.countDown() }
        @Volatile var error: Exception? = null
        override fun onError(ex: Exception?) { error = ex }
    }

    @Test
    fun aFrameUnderTheLimitPassesAndOneOverItClosesTheConnection() {
        val server = Echo(limit).also { it.start() }
        try {
            assertTrue(server.ready.await(5, TimeUnit.SECONDS))
            val client = Client(server.port)
            assertTrue("connect failed: ${client.error} port=${server.port}", client.connectBlocking(5, TimeUnit.SECONDS))

            client.send(ByteArray(48 * 1024))
            Thread.sleep(300)
            assertEquals(48 * 1024, server.received.get())

            client.send(ByteArray(limit + 1024))
            assertTrue("the oversized frame must close the connection", client.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1009, client.closeCode)
            assertEquals("the oversized frame must not be delivered", 48 * 1024, server.received.get())
        } finally {
            server.stop(500)
        }
    }
}
