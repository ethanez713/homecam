package com.homecam.core.rpc

import com.google.protobuf.ByteString
import com.homecam.proto.RpcError
import com.homecam.proto.RpcMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles one method. Returning null means "no response" — correct for a fire-and-forget
 * setter; throwing [RpcException] produces an ERROR response.
 */
fun interface RpcHandler {
    suspend fun handle(request: RpcMessage): ByteString?
}

class RpcException(val code: RpcError.Code, override val message: String) : Exception(message)

/** An ERROR response arrived for a request this peer made. */
class RpcRemoteException(val code: RpcError.Code, override val message: String) : Exception(message)

/**
 * Both directions of the DataChannel protocol: serves registered methods and correlates
 * replies to requests it made.
 *
 * Forward compatibility is the point of the two serving rules. An **unknown service or
 * control id is dropped silently**, so a newer viewer talking to an older camera degrades
 * instead of erroring. A **known method that fails answers with ERROR** — Alfred drops
 * those too, which makes debugging miserable, so we don't (§4.2).
 */
class RpcPeer(private val send: suspend (RpcMessage) -> Unit) {

    private val handlers = HashMap<Long, RpcHandler>()
    private val pending = HashMap<Int, CompletableDeferred<RpcMessage>>()
    private val streams = HashMap<Int, suspend (RpcMessage) -> Unit>()
    private val lock = Mutex()
    private val nextId = AtomicInteger(1)

    fun register(service: RpcMessage.Service, control: Int, handler: RpcHandler) {
        handlers[key(service.number, control)] = handler
    }

    suspend fun onMessage(message: RpcMessage) {
        when (message.type) {
            RpcMessage.Type.RESPONSE, RpcMessage.Type.ERROR -> complete(message)
            RpcMessage.Type.STREAM -> lock.withLock { streams[message.id] }?.invoke(message)
            RpcMessage.Type.REQUEST, RpcMessage.Type.NOTIFICATION -> serve(message)
            else -> Unit
        }
    }

    /** Fire-and-forget. Nothing is correlated and no reply is expected. */
    suspend fun notify(service: RpcMessage.Service, control: Int, payload: ByteString) {
        send(envelope(RpcMessage.Type.NOTIFICATION, service.number, control, 0, payload))
    }

    suspend fun request(
        service: RpcMessage.Service,
        control: Int,
        payload: ByteString = ByteString.EMPTY,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ByteString {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<RpcMessage>()
        lock.withLock { pending[id] = reply }
        try {
            send(envelope(RpcMessage.Type.REQUEST, service.number, control, id, payload))
            val response = withTimeout(timeoutMs) { reply.await() }
            if (response.type == RpcMessage.Type.ERROR) {
                val error = RpcError.parseFrom(response.payload)
                throw RpcRemoteException(error.code, error.message)
            }
            return response.payload
        } finally {
            lock.withLock { pending.remove(id); streams.remove(id) }
        }
    }

    /**
     * A request whose reply arrives as a series of STREAM messages (clip download).
     * [onChunk] runs for each; the call returns when the terminating RESPONSE lands.
     */
    suspend fun requestStream(
        service: RpcMessage.Service,
        control: Int,
        payload: ByteString,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onChunk: suspend (ByteString) -> Unit,
    ) {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<RpcMessage>()
        lock.withLock {
            pending[id] = reply
            streams[id] = { onChunk(it.payload) }
        }
        try {
            send(envelope(RpcMessage.Type.REQUEST, service.number, control, id, payload))
            val response = withTimeout(timeoutMs) { reply.await() }
            if (response.type == RpcMessage.Type.ERROR) {
                val error = RpcError.parseFrom(response.payload)
                throw RpcRemoteException(error.code, error.message)
            }
        } finally {
            lock.withLock { pending.remove(id); streams.remove(id) }
        }
    }

    suspend fun sendStreamChunk(request: RpcMessage, chunk: ByteString) {
        send(envelope(RpcMessage.Type.STREAM, request.serviceValue, request.control, request.id, chunk))
    }

    suspend fun failAllPending(reason: String) = lock.withLock {
        pending.values.forEach {
            it.complete(
                envelope(
                    RpcMessage.Type.ERROR, 0, 0, 0,
                    RpcError.newBuilder().setCode(RpcError.Code.UNAVAILABLE).setMessage(reason)
                        .build().toByteString()
                )
            )
        }
        pending.clear()
        streams.clear()
    }

    private suspend fun complete(message: RpcMessage) {
        lock.withLock { pending.remove(message.id) }?.complete(message)
    }

    private suspend fun serve(request: RpcMessage) {
        val handler = handlers[key(request.serviceValue, request.control)] ?: return
        val wantsReply = request.type == RpcMessage.Type.REQUEST
        try {
            val payload = handler.handle(request)
            if (wantsReply) send(respond(request, payload ?: ByteString.EMPTY))
        } catch (e: RpcException) {
            if (wantsReply) send(fail(request, e.code, e.message))
        } catch (e: Exception) {
            if (wantsReply) send(fail(request, RpcError.Code.INTERNAL, e.message ?: e::class.java.simpleName))
        }
    }

    private fun respond(request: RpcMessage, payload: ByteString) =
        envelope(RpcMessage.Type.RESPONSE, request.serviceValue, request.control, request.id, payload)

    private fun fail(request: RpcMessage, code: RpcError.Code, message: String) = envelope(
        RpcMessage.Type.ERROR, request.serviceValue, request.control, request.id,
        RpcError.newBuilder().setCode(code).setMessage(message).build().toByteString(),
    )

    private fun envelope(
        type: RpcMessage.Type,
        service: Int,
        control: Int,
        id: Int,
        payload: ByteString,
    ): RpcMessage = RpcMessage.newBuilder()
        .setType(type)
        .setServiceValue(service)
        .setControl(control)
        .setId(id)
        .setPayload(payload)
        .build()

    private fun key(service: Int, control: Int): Long =
        (service.toLong() shl 32) or (control.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
