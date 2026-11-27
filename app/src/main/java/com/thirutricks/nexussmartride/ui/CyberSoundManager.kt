package com.thirutricks.nexussmartride.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Synthesizes high-tech cyber-automotive chimes and acoustic UI feedback
 * using low-latency PCM audio generation via AudioTrack.
 * Zero external asset dependencies, zero latency, lightweight.
 */
object CyberSoundManager {
    private const val TAG = "CyberSoundManager"
    private const val SAMPLE_RATE = 44100
    private const val PREFS_NAME = "nexus_sound_prefs"
    private const val KEY_SOUND_ENABLED = "sound_effects_enabled"

    fun isSoundEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    /**
     * Ascending multi-harmonic chime played when Bluetooth Low Energy handshake succeeds.
     * Ascending notes: C5 (523.25 Hz) -> E5 (659.25 Hz) -> G5 (783.99 Hz) -> C6 (1046.50 Hz).
     */
    fun playBleConnectedChime(context: Context) {
        if (!isSoundEnabled(context)) return

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val chordNotes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f)
                val noteDurationSec = 0.08f
                val finalNoteDurationSec = 0.22f
                val totalDurationSec = (chordNotes.size - 1) * noteDurationSec + finalNoteDurationSec

                val totalSamples = (SAMPLE_RATE * totalDurationSec).toInt()
                val audioBuffer = ShortArray(totalSamples)

                var currentOffset = 0
                for (i in chordNotes.indices) {
                    val freq = chordNotes[i]
                    val dur = if (i == chordNotes.lastIndex) finalNoteDurationSec else noteDurationSec
                    val samplesInNote = (SAMPLE_RATE * dur).toInt()

                    for (j in 0 until samplesInNote) {
                        val globalIdx = currentOffset + j
                        if (globalIdx >= totalSamples) break

                        val t = j.toFloat() / SAMPLE_RATE
                        // Envelope: smooth attack and exponential decay
                        val attack = (j.toFloat() / (SAMPLE_RATE * 0.015f)).coerceIn(0f, 1f)
                        val decay = (1f - (j.toFloat() / samplesInNote)).coerceIn(0f, 1f)
                        val envelope = attack * decay * decay

                        // Dual sine harmonics for silky automotive bell tone
                        val waveFundamental = sin(2.0 * Math.PI * freq * t)
                        val waveHarmonic = 0.35 * sin(2.0 * Math.PI * (freq * 2.0) * t)
                        val sample = ((waveFundamental + waveHarmonic) * envelope * 0.38 * Short.MAX_VALUE).toInt()
                        audioBuffer[globalIdx] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    currentOffset += (SAMPLE_RATE * noteDurationSec).toInt()
                }

                playBuffer(audioBuffer)
            } catch (e: Exception) {
                Log.e(TAG, "playBleConnectedChime error: ${e.message}")
            }
        }
    }

    /**
     * Gentle descending two-tone chime played when Bluetooth disconnects.
     * Notes: E5 (659.25 Hz) -> C5 (523.25 Hz).
     */
    fun playBleDisconnectedChime(context: Context) {
        if (!isSoundEnabled(context)) return

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val notes = floatArrayOf(659.25f, 440.00f)
                val dur = 0.14f
                val totalSamples = (SAMPLE_RATE * dur * notes.size).toInt()
                val audioBuffer = ShortArray(totalSamples)

                var currentOffset = 0
                for (freq in notes) {
                    val samplesInNote = (SAMPLE_RATE * dur).toInt()
                    for (j in 0 until samplesInNote) {
                        val globalIdx = currentOffset + j
                        if (globalIdx >= totalSamples) break

                        val t = j.toFloat() / SAMPLE_RATE
                        val attack = (j.toFloat() / (SAMPLE_RATE * 0.012f)).coerceIn(0f, 1f)
                        val decay = (1f - (j.toFloat() / samplesInNote)).coerceIn(0f, 1f)
                        val envelope = attack * decay

                        val wave = sin(2.0 * Math.PI * freq * t)
                        val sample = (wave * envelope * 0.32 * Short.MAX_VALUE).toInt()
                        audioBuffer[globalIdx] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    currentOffset += samplesInNote
                }

                playBuffer(audioBuffer)
            } catch (e: Exception) {
                Log.e(TAG, "playBleDisconnectedChime error: ${e.message}")
            }
        }
    }

    /**
     * Subtle micro-click / tactile audio blip for card touches and button taps.
     */
    fun playClickSound(context: Context) {
        if (!isSoundEnabled(context)) return

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val durSec = 0.018f
                val numSamples = (SAMPLE_RATE * durSec).toInt()
                val buffer = ShortArray(numSamples)
                val freq = 1250f

                for (i in 0 until numSamples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val decay = (1f - (i.toFloat() / numSamples)).coerceIn(0f, 1f)
                    val sample = (sin(2.0 * Math.PI * freq * t) * decay * decay * 0.22 * Short.MAX_VALUE).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                playBuffer(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "playClickSound error: ${e.message}")
            }
        }
    }

    /**
     * Futuristic frequency glide chime for riding mode switch (Eco -> City -> Power).
     */
    fun playModeSwitchChime(context: Context) {
        if (!isSoundEnabled(context)) return

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val durSec = 0.12f
                val numSamples = (SAMPLE_RATE * durSec).toInt()
                val buffer = ShortArray(numSamples)
                val startFreq = 784.0f
                val endFreq = 1174.0f

                for (i in 0 until numSamples) {
                    val progress = i.toFloat() / numSamples
                    val currentFreq = startFreq + (endFreq - startFreq) * progress
                    val t = i.toFloat() / SAMPLE_RATE
                    val envelope = (1f - progress) * (progress / 0.1f).coerceIn(0f, 1f)
                    val sample = (sin(2.0 * Math.PI * currentFreq * t) * envelope * 0.28 * Short.MAX_VALUE).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                playBuffer(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "playModeSwitchChime error: ${e.message}")
            }
        }
    }

    /**
     * Futuristic double low pulse audio alert played on invalid actions (e.g. cluster disconnected).
     */
    fun playErrorAlert(context: Context) {
        if (!isSoundEnabled(context)) return

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val notes = floatArrayOf(261.63f, 220.00f) // C4 -> A3
                val dur = 0.09f
                val totalSamples = (SAMPLE_RATE * dur * notes.size).toInt()
                val audioBuffer = ShortArray(totalSamples)

                var currentOffset = 0
                for (freq in notes) {
                    val samplesInNote = (SAMPLE_RATE * dur).toInt()
                    for (j in 0 until samplesInNote) {
                        val globalIdx = currentOffset + j
                        if (globalIdx >= totalSamples) break

                        val t = j.toFloat() / SAMPLE_RATE
                        val attack = (j.toFloat() / (SAMPLE_RATE * 0.008f)).coerceIn(0f, 1f)
                        val decay = (1f - (j.toFloat() / samplesInNote)).coerceIn(0f, 1f)
                        val envelope = attack * decay

                        val wave = sin(2.0 * Math.PI * freq * t)
                        val sample = (wave * envelope * 0.35 * Short.MAX_VALUE).toInt()
                        audioBuffer[globalIdx] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    currentOffset += samplesInNote
                }

                playBuffer(audioBuffer)
            } catch (e: Exception) {
                Log.e(TAG, "playErrorAlert error: ${e.message}")
            }
        }
    }

    private fun playBuffer(buffer: ShortArray) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufferSize = maxOf(buffer.size * 2, minBufferSize)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                trackBufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                trackBufferSize,
                AudioTrack.MODE_STATIC
            )
        }

        try {
            track.write(buffer, 0, buffer.size)
            track.play()
            // Track automatically finishes in MODE_STATIC
            track.setNotificationMarkerPosition(buffer.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    try {
                        t?.stop()
                        t?.release()
                    } catch (_: Exception) {}
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
        } catch (e: Exception) {
            try { track.release() } catch (_: Exception) {}
        }
    }
}
