package com.evofactory.hal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaHeartbeatTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<JSONObject>()
        var accept = true
        var cancelled = false
        var onSend: (JSONObject) -> Unit = {}
        override fun request() = Request.Builder().url("https://ha.example/api/websocket").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (!accept) return false
            val message = JSONObject(text)
            sent += message
            onSend(message)
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    @Test fun heartbeatRequiresMatchingPongWhileCommandsKeepTheirOwnReplies() = runBlocking {
        val socket = Socket()
        val session = HaSession(socket, "https://ha.example", "token")
        val ping = async(start = CoroutineStart.UNDISPATCHED) { session.ping() }
        val pingId = socket.sent.last().getInt("id")
        session.dispatch(JSONObject().put("id", pingId + 1).put("type", "pong"))
        session.dispatch(JSONObject().put("id", pingId).put("type", "result").put("success", true))
        assertFalse(ping.isCompleted)
        val command = async(start = CoroutineStart.UNDISPATCHED) { session.command("get_states") }
        val commandId = socket.sent.last().getInt("id")
        assertTrue(commandId > pingId)
        session.dispatch(JSONObject().put("id", commandId).put("type", "pong"))
        assertFalse(command.isCompleted)
        session.dispatch(JSONObject().put("id", pingId).put("type", "pong"))
        withTimeout(1000) { ping.await() }
        session.dispatch(JSONObject().put("id", commandId).put("type", "result").put("success", true).put("result", "states"))
        assertEquals("states", withTimeout(1000) { command.await() })
        assertFalse(socket.cancelled)
    }

    @Test fun missingPongEndsSessionAndLateReplyCannotSatisfyTheNextPing() = runBlocking {
        val socket = Socket()
        val session = HaSession(socket, "https://ha.example", "token")
        try { session.ping(timeoutMs = 20); fail("Expected heartbeat timeout") }
        catch (e: HaException) { assertEquals("Home Assistant heartbeat timed out", e.message) }
        assertTrue(socket.cancelled)
        val oldId = socket.sent.last().getInt("id")
        val ping = async(start = CoroutineStart.UNDISPATCHED) { session.ping() }
        session.dispatch(JSONObject().put("id", oldId).put("type", "pong"))
        assertFalse(ping.isCompleted)
        session.dispatch(JSONObject().put("id", socket.sent.last().getInt("id")).put("type", "pong"))
        withTimeout(1000) { ping.await() }
    }

    @Test fun refusedSendFailsImmediately() = runBlocking {
        val socket = Socket().apply { accept = false }
        val session = HaSession(socket, "https://ha.example", "token")
        try { session.ping(); fail("Expected closed socket") }
        catch (e: HaException) { assertEquals("Home Assistant connection is closed", e.message) }
        assertTrue(socket.cancelled)
    }

    @Test fun healthySessionContinuesAcrossRepeatedApplicationHeartbeats() = runBlocking {
        val socket = Socket()
        val session = HaSession(socket, "https://ha.example", "token")
        val healthy = CompletableDeferred<Unit>()
        socket.onSend = { message ->
            session.dispatch(JSONObject().put("id", message.getInt("id")).put("type", "pong"))
            if (socket.sent.size == 33) healthy.complete(Unit)
        }
        val heartbeat = launch { session.keepAlive(intervalMs = 1) }
        withTimeout(3000) { healthy.await() }
        heartbeat.cancelAndJoin()
        assertTrue(socket.sent.size >= 33)
        assertEquals((1..socket.sent.size).toList(), socket.sent.map { it.getInt("id") })
        assertFalse(socket.cancelled)
    }

    @Test fun sessionCancellationStopsHeartbeatAndDoesNotReportAConnectionFailure() = runBlocking {
        val socket = Socket()
        val session = HaSession(socket, "https://ha.example", "token")
        val sent = CompletableDeferred<Unit>()
        socket.onSend = { sent.complete(Unit) }
        val heartbeat = launch { session.keepAlive(intervalMs = 1) }
        withTimeout(1000) { sent.await() }
        heartbeat.cancelAndJoin()
        assertEquals(1, socket.sent.size)
        assertEquals("ping", socket.sent.single().getString("type"))
        assertFalse(socket.cancelled)
    }
}
