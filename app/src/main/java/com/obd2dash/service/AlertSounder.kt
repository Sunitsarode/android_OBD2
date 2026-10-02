package com.obd2dash.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.obd2dash.core.AlertMonitor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays alert beeps and spoken alerts the way navigation prompts are played:
 * with transient audio focus, so music ducks under them instead of clashing,
 * and with the navigation-guidance usage, which Android Auto routes to the car
 * speakers next to Google Maps' own prompts.
 *
 * Everything fails soft: an audio problem must never be able to crash polling.
 */
class AlertSounder(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val toneAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(toneAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }

    private val normalTone = Tone.build(toneAttributes, 880.0, 2)
    private val criticalTone = Tone.build(toneAttributes, 1175.0, 3)

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false
    private val speaking = AtomicInteger()

    // Main-thread state: focus is held until the last overlapping sound ends.
    private var focusHeld = false
    private var toneEndsAt = 0L
    private var speechCapAt = 0L
    private val releaseCheck = Runnable { releaseIfIdle() }

    /** Call on the main thread. */
    fun setVoiceEnabled(enabled: Boolean) {
        if (enabled && tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) configureVoice()
            }
        } else if (!enabled) {
            shutdownVoice()
        }
    }

    private fun configureVoice() {
        val engine = tts ?: return
        try {
            engine.setAudioAttributes(speechAttributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishedSpeaking()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishedSpeaking()
            })
        } catch (_: RuntimeException) {
        }
    }

    private fun finishedSpeaking() {
        speaking.decrementAndGet()
        main.post(releaseCheck)
    }

    /** Safe from any thread. */
    fun play(alert: AlertMonitor.Alert, beep: Boolean, voice: Boolean) {
        main.post { playOnMain(alert, beep, voice) }
    }

    private fun playOnMain(alert: AlertMonitor.Alert, beep: Boolean, voice: Boolean) {
        val speak = voice && ttsReady
        if (!beep && !speak) return
        acquireFocus()

        var toneMs = 0L
        if (beep) {
            val tone = if (alert.critical) criticalTone else normalTone
            if (tone != null && tone.play()) toneMs = tone.durationMs
        }
        if (speak) {
            speaking.incrementAndGet()
            // Speak after the beep, not over it.
            main.postDelayed({
                val started = try {
                    tts?.speak(alert.speech, TextToSpeech.QUEUE_ADD, null, alert.name) == TextToSpeech.SUCCESS
                } catch (_: RuntimeException) {
                    false
                }
                if (!started) finishedSpeaking()
            }, toneMs)
        }
        val now = SystemClock.uptimeMillis()
        toneEndsAt = maxOf(toneEndsAt, now + toneMs + RELEASE_SLACK_MS)
        // Speech normally releases focus through the utterance listener; the cap
        // only matters if the engine never reports back.
        if (speak) speechCapAt = maxOf(speechCapAt, now + toneMs + SPEECH_CAP_MS)
        scheduleCheck(toneEndsAt)
    }

    private fun acquireFocus() {
        if (focusHeld) return
        focusHeld = try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun scheduleCheck(atUptime: Long) {
        main.removeCallbacks(releaseCheck)
        main.postAtTime(releaseCheck, atUptime)
    }

    private fun releaseIfIdle() {
        if (!focusHeld) return
        val now = SystemClock.uptimeMillis()
        if (now < toneEndsAt) {
            scheduleCheck(toneEndsAt)
            return
        }
        if (speaking.get() > 0) {
            if (now < speechCapAt) {
                scheduleCheck(speechCapAt)
                return
            }
            // The engine never reported back; do not keep the music ducked forever.
            speaking.set(0)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (_: RuntimeException) {
        }
        focusHeld = false
        toneEndsAt = 0L
        speechCapAt = 0L
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        shutdownVoice()
        normalTone?.release()
        criticalTone?.release()
        if (focusHeld) {
            toneEndsAt = 0L
            speaking.set(0)
            releaseIfIdle()
        }
    }

    private fun shutdownVoice() {
        ttsReady = false
        try {
            tts?.shutdown()
        } catch (_: RuntimeException) {
        }
        tts = null
    }

    /**
     * A short burst of soft-edged sine beeps, generated once and kept in a static
     * AudioTrack. Unlike ToneGenerator, AudioTrack accepts audio attributes, which
     * is what lets the beep duck music and reach the car speakers.
     */
    private class Tone(private val track: AudioTrack, val durationMs: Long) {

        fun play(): Boolean = try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
            true
        } catch (_: RuntimeException) {
            false
        }

        fun release() {
            try {
                track.release()
            } catch (_: RuntimeException) {
            }
        }

        companion object {
            private const val SAMPLE_RATE = 22_050
            private const val BEEP_MS = 150
            private const val GAP_MS = 90
            private const val FADE_MS = 6
            private const val AMPLITUDE = 18_000.0

            fun build(attributes: AudioAttributes, frequencyHz: Double, beeps: Int): Tone? = try {
                val beepSamples = SAMPLE_RATE * BEEP_MS / 1000
                val gapSamples = SAMPLE_RATE * GAP_MS / 1000
                val fadeSamples = SAMPLE_RATE * FADE_MS / 1000
                val total = beeps * beepSamples + (beeps - 1) * gapSamples
                val pcm = ShortArray(total)
                var offset = 0
                for (b in 0 until beeps) {
                    for (n in 0 until beepSamples) {
                        // Fading the edges avoids audible clicks.
                        val edge = min(n, beepSamples - 1 - n)
                        val gain = if (edge < fadeSamples) edge.toDouble() / fadeSamples else 1.0
                        pcm[offset + n] = (sin(2.0 * PI * frequencyHz * n / SAMPLE_RATE) * gain * AMPLITUDE).toInt().toShort()
                    }
                    offset += beepSamples + gapSamples
                }
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack(
                    attributes, format, total * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track.write(pcm, 0, total)
                Tone(track, (total * 1000L) / SAMPLE_RATE)
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        const val RELEASE_SLACK_MS = 150L
        const val SPEECH_CAP_MS = 8_000L
    }
}
