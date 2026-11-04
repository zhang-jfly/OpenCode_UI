package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.*
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE (Server-Sent Events) listener for OpenCode server.
 */
class SseEventListener(
    private val baseUrl: String,
    private val projectPath: String,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val username: String? = null,
    private val password: String? = null,
    private val eventPath: String = "/event",
    private val appendDirectory: Boolean = true,
    deserializer: JsonDeserializer<OpenCodeEvent> = OpenCodeEventDeserializer()
) {
    private val logger = Logger.getInstance(SseEventListener::class.java)

    companion object {
        private const val SSE_RECONNECT_DELAY_MS = 3000L
    }

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (username != null && password != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, deserializer)
        .create()

    private var call: Call? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectAttemptCount = 0

    fun connect() {
        if (isConnected.get()) return
        shouldReconnect.set(true)
        reconnectAttemptCount = 0
        doConnect()
    }

    private fun doConnect() {
        reconnectAttemptCount++
        val url = if (appendDirectory) {
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            "$baseUrl$eventPath?directory=$encodedPath"
        } else {
            "$baseUrl$eventPath"
        }

        logger.info("[SSE] Connecting to $url (attempt #$reconnectAttemptCount)...")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        call = client.newCall(request)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isConnected.set(false)
                logger.warn("[SSE] Connection failure: ${e.message} (attempt #$reconnectAttemptCount)")
                onError(e)
                onDisconnected()
                if (shouldReconnect.get()) scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isConnected.set(false)
                    val body = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.error("[SSE] Handshake failed: HTTP ${response.code} (attempt #$reconnectAttemptCount). Response: $body")
                    onError(IOException("SSE failed: ${response.code}"))
                    onDisconnected()
                    if (shouldReconnect.get()) scheduleReconnect()
                    return
                }

                isConnected.set(true)
                reconnectAttemptCount = 0
                onConnected()
                logger.info("[SSE] Connection established")

                response.body?.source()?.let { source ->
                    try {
                        var currentEvent: String? = null
                        val dataBuffer = StringBuilder()

                        fun dispatchBuffered() {
                            if (dataBuffer.isNotEmpty()) {
                                parseAndDispatchEvent(dataBuffer.toString(), currentEvent)
                                dataBuffer.setLength(0)
                            }
                            currentEvent = null
                        }

                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> {
                                    currentEvent = line.substring(6).trim()
                                }
                                line.startsWith("data:") -> {
                                    val chunk = line.substring(5)
                                    val value = if (chunk.startsWith(" ")) chunk.substring(1) else chunk
                                    if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                                    dataBuffer.append(value)
                                }
                                line.isEmpty() -> dispatchBuffered()
                            }
                        }
                        dispatchBuffered()
                    } catch (e: Exception) {
                        if (!call.isCanceled()) logger.warn("[SSE] Stream interrupted: ${e.message}")
                    } finally {
                        isConnected.set(false)
                        onDisconnected()
                        if (shouldReconnect.get() && !call.isCanceled()) {
                            logger.info("[SSE] Connection lost. Scheduling reconnect.")
                            scheduleReconnect()
                        }
                    }
                }
            }
        })
    }

    private fun parseAndDispatchEvent(json: String, eventName: String?) {
        val event = try {
            logger.debug("[SSE-Raw] $json")
            val element = JsonParser.parseString(json)
            if (element.isJsonObject && !eventName.isNullOrBlank()) {
                val obj = element.asJsonObject
                if (!obj.has("type")) obj.addProperty("type", eventName)
            }
            gson.fromJson(element, OpenCodeEvent::class.java)
        } catch (e: Exception) {
            logger.debug("[SSE] Parse error: ${e.message}. JSON: $json")
            return
        }

        if (event == null) return
        try {
            onEvent(event)
        } catch (e: Exception) {
            // A failing handler must not break the SSE loop: log and keep listening.
            logger.warn("[SSE] Event handler failed for ${event.type}: ${e.message}", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectTask?.cancel(false)
        reconnectTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (shouldReconnect.get()) doConnect()
        }, SSE_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun disconnect() {
        shouldReconnect.set(false)
        reconnectTask?.cancel(true)
        call?.cancel()
        isConnected.set(false)
    }

    fun isConnected(): Boolean = isConnected.get()
}
