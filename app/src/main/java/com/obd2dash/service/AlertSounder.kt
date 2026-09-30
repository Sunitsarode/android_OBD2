package com.obd2dash.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.obd2dash.core.AlertMonitor

/**
 * Beeps and, optionally, speaks alerts. Everything here fails soft: an alert
 * must never be able to crash the polling loop.
 */
class AlertSounder(private val context: Context) {

    // The music stream is the one a head unit reliably routes to the car speakers.
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    } catch (_: RuntimeException) {
        null
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    fun setVoiceEnabled(enabled: Boolean) {
        if (enabled && tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
        } else if (!enabled) {
            shutdownVoice()
        }
    }

    fun play(alert: AlertMonitor.Alert, beep: Boolean, voice: Boolean) {
        if (beep) {
            try {
                tone?.startTone(
                    if (alert.critical) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2,
                    TONE_MS
                )
            } catch (_: RuntimeException) {
            }
        }
        if (voice && ttsReady) {
            try {
                tts?.speak(alert.speech, TextToSpeech.QUEUE_ADD, null, alert.name)
            } catch (_: RuntimeException) {
            }
        }
    }

    fun release() {
        shutdownVoice()
        try {
            tone?.release()
        } catch (_: RuntimeException) {
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

    private companion object {
        const val VOLUME = 90
        const val TONE_MS = 600
    }
}
