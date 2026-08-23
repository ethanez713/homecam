package com.homecam.core

import com.google.protobuf.ByteString
import com.homecam.core.rpc.Controls
import com.homecam.core.rpc.RpcException
import com.homecam.core.rpc.RpcPeer
import com.homecam.core.rpc.RpcRemoteException
import com.homecam.proto.Armed
import com.homecam.proto.RpcError
import com.homecam.proto.RpcMessage
import com.homecam.proto.RuntimeStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Wires two peers back to back so a round trip exercises both directions. */
private class Link {
    lateinit var camera: RpcPeer
    lateinit var viewer: RpcPeer
    val cameraInbox = mutableListOf<RpcMessage>()

    init {
        camera = RpcPeer { viewer.onMessage(it) }
        viewer = RpcPeer { cameraInbox += it; camera.onMessage(it) }
    }
}

class RpcPeerTest {

    @Test
    fun `a request round-trips through the registered handler`() = runTest {
        val link = Link()
        link.camera.register(RpcMessage.Service.STATUS, Controls.Status.RUNTIME) {
            RuntimeStatus.newBuilder().setBatteryPercent(42f).setCameraAlias("Front door")
                .build().toByteString()
        }

        val payload = link.viewer.request(RpcMessage.Service.STATUS, Controls.Status.RUNTIME)
        val status = RuntimeStatus.parseFrom(payload)
        assertEquals(42f, status.batteryPercent, 0.001f)
        assertEquals("Front door", status.cameraAlias)
    }

    @Test
    fun `the response echoes service control and id`() = runTest {
        val link = Link()
        var seen: RpcMessage? = null
        link.camera.register(RpcMessage.Service.SETTINGS, Controls.Settings.ARMED) {
            seen = it
            Armed.newBuilder().setArmed(true).build().toByteString()
        }
        link.viewer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.ARMED,
            Armed.newBuilder().setArmed(true).build().toByteString(),
        )
        assertEquals(RpcMessage.Service.SETTINGS, seen!!.service)
        assertEquals(Controls.Settings.ARMED, seen!!.control)
        assertTrue(seen!!.id != 0)
    }

    @Test
    fun `an unknown control id is dropped rather than answered`() = runTest {
        val link = Link()
        try {
            link.viewer.request(RpcMessage.Service.SETTINGS, 9999, timeoutMs = 50)
            fail("expected a timeout, not a response")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A newer viewer talking to an older camera must degrade, not error.
        }
    }

    @Test
    fun `an unknown service number is dropped rather than crashing`() = runTest {
        val link = Link()
        val unknown = RpcMessage.newBuilder()
            .setType(RpcMessage.Type.REQUEST)
            .setServiceValue(77)
            .setControl(0)
            .setId(5)
            .build()
        link.camera.onMessage(unknown)
        assertTrue(link.cameraInbox.isEmpty())
    }

    @Test
    fun `a failing handler answers with an ERROR carrying its code`() = runTest {
        val link = Link()
        link.camera.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_DELETE) {
            throw RpcException(RpcError.Code.NOT_FOUND, "no such clip")
        }
        try {
            link.viewer.request(RpcMessage.Service.MEDIA, Controls.Media.CLIP_DELETE)
            fail("expected an ERROR response")
        } catch (e: RpcRemoteException) {
            assertEquals(RpcError.Code.NOT_FOUND, e.code)
            assertEquals("no such clip", e.message)
        }
    }

    @Test
    fun `an unexpected handler exception becomes INTERNAL rather than a dropped request`() = runTest {
        val link = Link()
        link.camera.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_LIST) {
            throw IllegalStateException("index closed")
        }
        try {
            link.viewer.request(RpcMessage.Service.MEDIA, Controls.Media.CLIP_LIST)
            fail("expected an ERROR response")
        } catch (e: RpcRemoteException) {
            assertEquals(RpcError.Code.INTERNAL, e.code)
        }
    }

    @Test
    fun `a notification is served without a reply`() = runTest {
        val link = Link()
        var got: Boolean? = null
        link.camera.register(RpcMessage.Service.SETTINGS, Controls.Settings.ARMED) {
            got = Armed.parseFrom(it.payload).armed
            null
        }
        link.viewer.notify(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.ARMED,
            Armed.newBuilder().setArmed(true).build().toByteString(),
        )
        assertEquals(true, got)
    }

    @Test
    fun `a stream delivers chunks then completes on the response`() = runTest {
        val link = Link()
        link.camera.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_DATA) { request ->
            repeat(3) { link.camera.sendStreamChunk(request, ByteString.copyFrom(byteArrayOf(it.toByte()))) }
            ByteString.EMPTY
        }
        val chunks = mutableListOf<Int>()
        link.viewer.requestStream(
            RpcMessage.Service.MEDIA, Controls.Media.CLIP_DATA, ByteString.EMPTY,
        ) { chunks += it.byteAt(0).toInt() }
        assertEquals(listOf(0, 1, 2), chunks)
    }

    @Test
    fun `a dropped link fails everything in flight instead of hanging`() = runTest {
        val peer = RpcPeer { /* the far end is gone; nothing is delivered */ }
        val call = async {
            runCatching {
                peer.request(RpcMessage.Service.STATUS, Controls.Status.RUNTIME, timeoutMs = 60_000)
            }
        }
        yield()
        peer.failAllPending("data channel closed")

        val error = call.await().exceptionOrNull()
        assertTrue("expected UNAVAILABLE, got $error", error is RpcRemoteException)
        assertEquals(RpcError.Code.UNAVAILABLE, (error as RpcRemoteException).code)
    }
}
