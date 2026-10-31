package com.layers.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records AAC audio into an .m4a container with MediaRecorder. */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    var file: File? = null
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(target: File) {
        stopQuietly()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(128_000)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = target
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Current loudness from 0 to 1, for the live waveform. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amp / 32767f).coerceIn(0f, 1f)
    }

    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Stops and returns the duration in ms. */
    fun stop(): Long {
        val duration = elapsedMs()
        val r = recorder ?: return 0L
        recorder = null
        runCatching { r.stop() }
        r.release()
        return duration
    }

    fun cancel() {
        stopQuietly()
        file?.delete()
        file = null
    }

    private fun stopQuietly() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }
}
