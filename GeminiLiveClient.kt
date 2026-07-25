package com.myra.assistant.gemini

import android.util.Base64
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for the Gemini Live API (BidiGenerateContent). Handles setup,
 * streaming PCM audio in/out, input/output transcription, interruptions,
 * automatic reconnect with backoff, keepalive pings and periodic session renewal.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val onEvent: (GeminiEvent) -> Unit
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var config: GeminiConfig? = null
    private val running = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var renewJob: Job? = null

    fun connect(config: GeminiConfig) {
        this.config = config
        running.set(true)
        reconnectAttempts = 0
        openSocket()
    }

    private fun openSocket() {
        val cfg = config ?: return
        if (cfg.apiKey.isBlank()) {
            onEvent(GeminiEvent.Error("Gemini API key is missing. Add it in Settings."))
            return
        }
        val url = Constants.GEMINI_WS_HOST + "?key=" + cfg.apiKey
        val request = Request.Builder().url(url).build()
        onEvent(GeminiEvent.StateChanged(ConnectionState.CONNECTING))
        webSocket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Logger.i(TAG, "WebSocket open")
            reconnectAttempts = 0
            sendSetup(ws)
            scheduleRenew()
            onEvent(GeminiEvent.Connected)
        }

        override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(ws: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Logger.i(TAG, "WebSocket closed: $code $reason")
            if (running.get()) reconnect() else onEvent(GeminiEvent.Closed)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Logger.e(TAG, "WebSocket failure", t)
            if (running.get()) reconnect() else onEvent(GeminiEvent.Error(t.message ?: "Connection failed"))
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val cfg = config ?: return
        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", cfg.voiceName))
        )
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        val setup = JSONObject()
            .put("model", "models/" + cfg.model)
            .put("generationConfig", generationConfig)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", cfg.systemInstruction))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()))

        val message = JSONObject().put("setup", setup)
        ws.send(message.toString())
        Logger.d(TAG, "Setup sent for model ${cfg.model}")
    }

    /** Stream a chunk of 16kHz mono PCM16 microphone audio to Gemini. */
    fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val chunk = JSONObject()
            .put("mimeType", "audio/pcm;rate=" + Constants.INPUT_SAMPLE_RATE)
            .put("data", b64)
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("mediaChunks", JSONArray().put(chunk))
        )
        ws.send(message.toString())
    }

    /** Send a typed text turn (used by the chat input box). */
    fun sendText(text: String) {
        val ws = webSocket ?: return
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)
        )
        ws.send(message.toString())
    }

    private fun handleMessage(raw: String) {
        try {
            val obj = JSONObject(raw)
            if (obj.has("setupComplete")) {
                onEvent(GeminiEvent.SetupComplete)
                return
            }
            if (obj.has("serverContent")) {
                val sc = obj.getJSONObject("serverContent")
                if (sc.optBoolean("interrupted", false)) onEvent(GeminiEvent.Interrupted)
                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let { onEvent(GeminiEvent.InputTranscript(it)) }
                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let { onEvent(GeminiEvent.OutputTranscript(it)) }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        part.optJSONObject("inlineData")?.let { data ->
                            val mime = data.optString("mimeType", "")
                            if (mime.startsWith("audio")) {
                                val pcm = Base64.decode(data.getString("data"), Base64.NO_WRAP)
                                onEvent(GeminiEvent.AudioChunk(pcm))
                            }
                        }
                        part.optString("text").takeIf { it.isNotEmpty() }
                            ?.let { onEvent(GeminiEvent.OutputTranscript(it)) }
                    }
                }
                if (sc.optBoolean("turnComplete", false)) onEvent(GeminiEvent.TurnComplete)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to parse message", e)
        }
    }

    private fun reconnect() {
        onEvent(GeminiEvent.StateChanged(ConnectionState.RECONNECTING))
        renewJob?.cancel()
        scope.launch {
            val delayMs = (Constants.RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(5)))
                .coerceAtMost(Constants.RECONNECT_MAX_DELAY_MS)
            reconnectAttempts++
            Logger.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            delay(delayMs)
            if (running.get()) openSocket()
        }
    }

    private fun scheduleRenew() {
        renewJob?.cancel()
        renewJob = scope.launch {
            delay(Constants.SESSION_RENEW_MS)
            if (running.get()) {
                Logger.i(TAG, "Renewing session")
                webSocket?.close(NORMAL_CLOSURE, "renew")
            }
        }
    }

    fun close() {
        running.set(false)
        renewJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, "client closed")
        webSocket = null
        onEvent(GeminiEvent.StateChanged(ConnectionState.IDLE))
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val NORMAL_CLOSURE = 1000
    }
}
