package com.evofactory.hal

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForecastSubscriptionTest {
    @Test fun forecastUnsubscribeStopsCallbacksWithoutReplacingTheEntitySession() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        lateinit var session: HaSession
        val socket = object : WebSocket {
            override fun request(): Request = Request.Builder().url("https://ha.example/api/websocket").build()
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean {
                val command = JSONObject(text)
                sent += command
                session.dispatch(JSONObject().put("type", "result").put("id", command.getInt("id")).put("success", true))
                return true
            }
            override fun send(bytes: ByteString): Boolean = false
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() = Unit
        }
        session = HaSession(socket, "https://ha.example", "token")
        var callbacks = 0
        val subscription = session.forecastSubscription("weather.home", "hourly") { callbacks++ }!!
        val event = JSONObject().put("type", "event").put("id", subscription)
            .put("event", JSONObject().put("forecast", org.json.JSONArray()))
        session.dispatch(event)
        assertEquals(1, callbacks)
        session.unsubscribe(subscription)
        session.dispatch(event)
        assertEquals(1, callbacks)
        assertEquals("weather/subscribe_forecast", sent[0].getString("type"))
        assertEquals("unsubscribe_events", sent[1].getString("type"))
        assertEquals(subscription, sent[1].getInt("subscription"))
        // Ordinary commands continue through the same socket and monotonically assigned command IDs.
        session.command("get_states")
        assertTrue(sent[2].getInt("id") > sent[1].getInt("id"))
    }
}
