// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SonarReading(
    val distanceMeters: Float,
    val confidence: Float,
)

class SonarEngine {
    private val sampleRate = 48_000
    private val pingCueMillis = 80
    private val settleMillis = 120L
    private val betweenTrialsMillis = 180L

    @SuppressLint("MissingPermission")
    suspend fun measure(pingCount: Int = 3, volume: Float = 0.8f): SonarReading = withContext(Dispatchers.IO) {
        val trials = pingCount.coerceAtLeast(1)
        val results = ArrayList<SonarReading>(trials)
        val toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        try {
            repeat(trials) { trialIndex ->
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, pingCueMillis)
                delay(settleMillis)
                results += runTrial(volume)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 60)

                if (trialIndex < trials - 1) {
                    delay(betweenTrialsMillis)
                }
            }
        } catch (t: Throwable) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 120)
            throw t
        } finally {
            toneGenerator?.release()
        }

        val distance = results.map { it.distanceMeters }.average().toFloat()
        val confidence = results.map { it.confidence }.average().toFloat()
        SonarReading(distance, confidence)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runTrial(volume: Float): SonarReading {
        val probe = BatKitNative.generateProbe(sampleRate)
        check(probe.isNotEmpty()) { "BatKit returned an empty probe" }

        val pingPcm16 = toPcm16(probe)
        val minPlay = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minPlay > 0) { "Hardware rejected playback config: $sampleRate Hz" }

        val minRec = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minRec > 0) { "Hardware rejected recording config: $sampleRate Hz" }

        val recordSamples = sampleRate * 3 / 4
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minRec, recordSamples * Short.SIZE_BYTES))
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }

        val player = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minPlay, pingPcm16.size * Short.SIZE_BYTES),
            AudioTrack.MODE_STREAM,
        )

        if (player.state != AudioTrack.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Speaker initialization failed")
        }

        try {
            player.setVolume(volume.coerceIn(0f, 1f))

            recorder.startRecording()
            delay(30)
            player.play()

            check(
                player.write(pingPcm16, 0, pingPcm16.size, AudioTrack.WRITE_BLOCKING) == pingPcm16.size
            ) { "Could not load the ping" }

            val recordingPcm16 = ShortArray(recordSamples)
            var offset = 0
            while (offset < recordingPcm16.size) {
                val count = recorder.read(
                    recordingPcm16,
                    offset,
                    recordingPcm16.size - offset,
                    AudioRecord.READ_BLOCKING,
                )
                if (count <= 0) break
                offset += count
            }

            check(offset > 0) { "No audio captured from the microphone" }

            val floatRec = FloatArray(offset) { recordingPcm16[it] / 32768f }
            val result = BatKitNative.analyze(floatRec, sampleRate)
            return SonarReading(result[0], result[1])
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
            try {
                player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
    }

    private fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) {
        (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort()
    }
}

// vim: set ts=4 sw=4 et:
