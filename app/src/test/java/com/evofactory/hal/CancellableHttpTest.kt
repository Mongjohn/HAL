package com.evofactory.hal

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    @Test fun readsAndClosesSuccessfulResponses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 5)
            exchange.responseBody.use { it.write("hello".toByteArray()) }
        }
        server.start()
        val client = OkHttpClient()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
            assertEquals("hello", withTimeout(5000) { call.readCancellable { it.body.string() } })
            assertFalse(call.isCanceled())
        } finally { server.stop(0); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun cancellingStopsAnInFlightBodyReadWithoutWaitingForNetworkTimeout() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                bodyStarted.countDown()
                finish.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
            val read = launch(Dispatchers.Default) { call.readCancellable { it.body.string() } }
            assertTrue("response body did not start", bodyStarted.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { read.cancelAndJoin() }
            assertTrue("HTTP call still active after coroutine cancellation", call.isCanceled())
        } finally { finish.countDown(); server.stop(0); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
