package com.evofactory.hal

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Cancellation closes even a response body currently being read, not just the awaiting coroutine. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T = suspendCancellableCoroutine { waiting ->
    waiting.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { waiting.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            try { waiting.resumeWith(Result.success(response.use(read))) }
            catch (e: Exception) { waiting.resumeWith(Result.failure(e)) }
        }
    })
}
