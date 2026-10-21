package app.papertoslides.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.papertoslides.core.gemini.HttpResponse
import app.papertoslides.core.gemini.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * HTTPS only to generativelanguage.googleapis.com. No logging interceptor: keys and bodies are never logged.
 * The key travels in the `x-goog-api-key` header (set by the core client), never in the URL.
 */
class OkHttpTransport : HttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(420, TimeUnit.SECONDS)
        .callTimeout(420, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, json: String) =
        exec(Request.Builder().url(check(url)).post(json.toRequestBody("application/json".toMediaType())).apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    override suspend fun get(url: String, headers: Map<String, String>) =
        exec(Request.Builder().url(check(url)).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    private fun check(url: String): String {
        require(url.startsWith("https://generativelanguage.googleapis.com/")) { "blocked host" }
        return url
    }

    private suspend fun exec(req: Request): HttpResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { c ->
            val call = client.newCall(req)
            c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resume(HttpResponse(0, "network: ${e.javaClass.simpleName}")) }
                override fun onResponse(call: Call, response: Response) {
                    val r = response.use { HttpResponse(it.code, runCatching { it.body.string() }.getOrDefault("")) }
                    if (c.isActive) c.resume(r)
                }
            })
        }
    }
}

/** Network is needed only for T2: wait for it there (event-driven), not as a job-wide constraint. */
class NetworkGate(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    fun online(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True once online; false after [timeoutMs] offline (the pages then fall to the next tier). */
    suspend fun await(timeoutMs: Long = 120_000, onWaiting: () -> Unit = {}): Boolean {
        if (online()) return true
        onWaiting()
        val back = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { back.complete(Unit) }
        }
        cm.registerDefaultNetworkCallback(cb)
        return try {
            withTimeoutOrNull(timeoutMs) { back.await() } != null || online()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }
}
