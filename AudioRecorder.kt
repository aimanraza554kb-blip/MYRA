package com.myra.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import kotlin.math.max

/**
 * Captures 16kHz mono PCM16 audio from the microphone and emits ~100ms chunks.
 * Uses the VOICE_COMMUNICATION source plus hardware echo/noise suppression and
 * automatic gain control when the device supports them.
 */
class AudioRecorder(private val onChunk: (ByteArray) -> Unit) {

    @Volatile
    var muted: Boolean = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        val minBuffer = AudioRecord.getMinBufferSize(
            Constants.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val chunkBytes = Constants.INPUT_SAMPLE_RATE / 10 * 2 // 100ms of PCM16
        val bufferSize = max(minBuffer, chunkBytes * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            Constants.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            return
        }
        enableEffects(recorder.audioSessionId)
        record = recorder
        recorder.startRecording()
        recording = true

        thread = Thread {
            val buffer = ByteArray(chunkBytes)
            while (recording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0 && !muted) {
                    onChunk(buffer.copyOf(read))
                }
            }
        }.apply { name = "MyraMic"; start() }
        Logger.i(TAG, "Recording started")
    }

    private fun enableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        }
    }

    fun stop() {
        recording = false
        thread?.join(500)
        thread = null
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        gainControl?.release(); gainControl = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        Logger.i(TAG, "Recording stopped")
    }

    companion object { private const val TAG = "AudioRecorder" }
}
