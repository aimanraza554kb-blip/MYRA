package com.myra.assistant.voice

import android.content.Context
import com.myra.assistant.audio.AudioPlayer
import com.myra.assistant.audio.AudioRecorder
import com.myra.assistant.audio.VoiceActivityDetector
import com.myra.assistant.data.model.ChatMessage
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.data.repository.ConversationRepository
import com.myra.assistant.data.repository.MemoryRepository
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.gemini.GeminiConfig
import com.myra.assistant.gemini.GeminiEvent
import com.myra.assistant.gemini.GeminiLiveClient
import com.myra.assistant.phone.PhoneController
import com.myra.assistant.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The heart of MYRA. Wires the microphone, Gemini Live client, speaker and VAD
 * together and exposes reactive state for the UI. One shared instance lives in
 * the [com.myra.assistant.data.ServiceLocator] so the Activity, the floating
 * bubble and the foreground service all control the same conversation.
 */
class VoiceSessionManager(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val phoneController: PhoneController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _inputTranscript = MutableStateFlow("")
    val inputTranscript: StateFlow<String> = _inputTranscript.asStateFlow()

    private val _outputTranscript = MutableStateFlow("")
    val outputTranscript: StateFlow<String> = _outputTranscript.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _micMuted = MutableStateFlow(settings.micMuted())
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _playbackMuted = MutableStateFlow(settings.playbackMuted())
    val playbackMuted: StateFlow<Boolean> = _playbackMuted.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val vad = VoiceActivityDetector()
    private var client: GeminiLiveClient? = null
    private var recorder: AudioRecorder? = null
    private var player: AudioPlayer? = null

    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    fun start() {
        if (_active.value) return
        _active.value = true
        scope.launch {
            val personality = settings.personality()
            val memoryBlock = memory.contextBlock()
            val profile = buildString {
                append(settings.userProfile())
                if (memoryBlock.isNotBlank()) append("\nRemembered: \n").append(memoryBlock)
            }
            val systemPrompt = personality.systemPrompt(
                userName = settings.userName(),
                userProfile = profile,
                customAddon = settings.customPersonality()
            )
            val config = GeminiConfig(
                apiKey = settings.apiKey(),
                model = settings.model().id,
                voiceName = settings.voice().voiceName,
                systemInstruction = systemPrompt,
                language = settings.language()
            )

            val audioPlayer = AudioPlayer().apply { muted = _playbackMuted.value; start() }
            player = audioPlayer

            val geminiClient = GeminiLiveClient(scope, ::onEvent)
            client = geminiClient

            val micRecorder = AudioRecorder { pcm ->
                _amplitude.value = vad.amplitude(pcm)
                if (!_micMuted.value) geminiClient.sendAudio(pcm)
            }.apply { muted = _micMuted.value }
            recorder = micRecorder

            geminiClient.connect(config)
        }
    }

    private fun onEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.Connected -> _connectionState.value = ConnectionState.CONNECTED
            is GeminiEvent.SetupComplete -> {
                _connectionState.value = ConnectionState.LISTENING
                recorder?.start()
            }
            is GeminiEvent.AudioChunk -> {
                _connectionState.value = ConnectionState.SPEAKING
                player?.enqueue(event.pcm)
            }
            is GeminiEvent.Interrupted -> {
                player?.flush()
                _connectionState.value = ConnectionState.LISTENING
            }
            is GeminiEvent.InputTranscript -> {
                inputBuffer.append(event.text)
                _inputTranscript.value = inputBuffer.toString()
            }
            is GeminiEvent.OutputTranscript -> {
                outputBuffer.append(event.text)
                _outputTranscript.value = outputBuffer.toString()
            }
            is GeminiEvent.TurnComplete -> onTurnComplete()
            is GeminiEvent.StateChanged -> _connectionState.value = event.state
            is GeminiEvent.Error -> {
                _connectionState.value = ConnectionState.ERROR
                Logger.e(TAG, "Session error: ${event.message}")
            }
            is GeminiEvent.Closed -> _connectionState.value = ConnectionState.IDLE
        }
    }

    private fun onTurnComplete() {
        val userText = inputBuffer.toString().trim()
        val assistantText = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        _inputTranscript.value = ""
        _outputTranscript.value = ""
        _connectionState.value = ConnectionState.LISTENING
        scope.launch {
            if (userText.isNotEmpty()) conversation.add(ChatMessage.Role.USER, userText)
            if (assistantText.isNotEmpty()) {
                conversation.add(ChatMessage.Role.ASSISTANT, assistantText)
                phoneController.handleAssistantText(userText, assistantText)
            }
            if (settings.learningMode() && userText.isNotEmpty()) {
                phoneController.maybeLearn(userText)?.let { memory.remember(it) }
            }
        }
    }

    fun sendText(text: String) {
        client?.sendText(text)
        scope.launch { conversation.add(ChatMessage.Role.USER, text) }
    }

    fun toggleMic() {
        val muted = !_micMuted.value
        _micMuted.value = muted
        recorder?.muted = muted
        settings.setMicMuted(muted)
    }

    fun togglePlayback() {
        val muted = !_playbackMuted.value
        _playbackMuted.value = muted
        player?.muted = muted
        settings.setPlaybackMuted(muted)
    }

    /** Interrupt MYRA while she is speaking. */
    fun interrupt() = player?.flush()

    fun stop() {
        _active.value = false
        recorder?.stop(); recorder = null
        player?.stop(); player = null
        client?.close(); client = null
        _connectionState.value = ConnectionState.IDLE
        _amplitude.value = 0f
    }

    companion object { private const val TAG = "VoiceSessionManager" }
}
