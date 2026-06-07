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
import kotlin.math.abs

data class SonarReading(
    val distanceMeters: Float,
    val confidence: Float,
    val directCorrelation: Float,
    val echoCorrelation: Float,
    val autocorrelationBins: FloatArray,
)

enum class ProbeKind {
    Chirp,
    Ping,
}

data class ProbeSpec(
    val kind: ProbeKind = ProbeKind.Chirp,
    val chirpDurationMs: Float = 40f,
    val chirpStartHz: Float = 16_000f,
    val chirpEndHz: Float = 22_000f,
    val pingDurationMs: Float = 120f,
    val pingHz: Float = 18_000f,
    val pingDecay: Float = 6f,
)

class SonarEngine {
    private val sampleRate = 48_000
    private val pingCueMillis = 40
    private val settleMillis = 120L
    private val betweenTrialsMillis = 180L

    @SuppressLint("MissingPermission")
    suspend fun measure(
        probe: ProbeSpec = ProbeSpec(),
        pingCount: Int = 3,
        volume: Float = 0.8f,
        onPing: suspend (Int) -> Unit = {},
    ): SonarReading = withContext(Dispatchers.IO) {
        val trials = pingCount.coerceAtLeast(1)
        val results = ArrayList<SonarReading>(trials)
        val toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        try {
            repeat(trials) { trialIndex ->
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, pingCueMillis)
                delay(settleMillis)
                results += runTrial(probe, volume) {
                    onPing(trialIndex + 1)
                }
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

        val stable = results.filter { it.confidence >= 0.2f }.ifEmpty { results }
        check(stable.isNotEmpty()) { "No stable echo detected" }

        val sortedDistances = stable.map { it.distanceMeters }.sorted()
        val medianDistance = sortedDistances[sortedDistances.size / 2]
        val accepted = stable.filter { reading ->
            abs(reading.distanceMeters - medianDistance) <= maxOf(0.35f, medianDistance * 0.20f)
        }
        check(accepted.isNotEmpty()) { "Echo estimate unstable" }
        if (accepted.size < trials.coerceAtLeast(2) / 2 && stable.size > 1) {
            throw IllegalStateException("Echo estimate unstable")
        }

        val weightSum = accepted.sumOf { it.confidence.toDouble() }.coerceAtLeast(1e-6)
        val weightedDistance = accepted.sumOf { (it.distanceMeters * it.confidence).toDouble() }
        val distance = (weightedDistance / weightSum).toFloat()
        val confidence = accepted.maxOf { it.confidence }
        val directCorrelation = (accepted.sumOf { it.directCorrelation.toDouble() } / accepted.size).toFloat()
        val echoCorrelation = (accepted.sumOf { it.echoCorrelation.toDouble() } / accepted.size).toFloat()
        val autocorrelationBins = FloatArray(accepted.first().autocorrelationBins.size) { index ->
            accepted.sumOf { it.autocorrelationBins[index].toDouble() }.let { total ->
                (total / accepted.size).toFloat()
            }
        }
        SonarReading(distance, confidence, directCorrelation, echoCorrelation, autocorrelationBins)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runTrial(
        probe: ProbeSpec,
        volume: Float,
        onPing: suspend () -> Unit,
    ): SonarReading {
        val probePcm16 = generateProbe(probe)
        check(probePcm16.isNotEmpty()) { "BatKit returned an empty probe" }

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
            maxOf(minPlay, probePcm16.size * Short.SIZE_BYTES),
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
                player.write(probePcm16, 0, probePcm16.size, AudioTrack.WRITE_BLOCKING) == probePcm16.size
            ) { "Could not load the ping" }
            onPing()

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
            val (durationMs, primaryHz, secondaryHz, decay) = probe.nativeArgs()
            val result = BatKitNative.analyze(
                floatRec,
                sampleRate,
                probe.kind.ordinal,
                durationMs,
                primaryHz,
                secondaryHz,
                decay,
            )
            val bins = FloatArray(result.size - 4) { index -> result[index + 4] }
            return SonarReading(result[0], result[1], result[2], result[3], bins)
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

    private fun generateProbe(probe: ProbeSpec): ShortArray {
        val (durationMs, primaryHz, secondaryHz, decay) = probe.nativeArgs()
        val samples = BatKitNative.generateProbe(
            sampleRate,
            probe.kind.ordinal,
            durationMs,
            primaryHz,
            secondaryHz,
            decay,
        )

        return ShortArray(samples.size) {
            (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }

    private fun ProbeSpec.nativeArgs(): Quadruple {
        return when (kind) {
            ProbeKind.Chirp -> Quadruple(chirpDurationMs, chirpStartHz, chirpEndHz, 0f)
            ProbeKind.Ping -> Quadruple(pingDurationMs, pingHz, 0f, pingDecay)
        }
    }

    private data class Quadruple(
        val durationMs: Float,
        val primaryHz: Float,
        val secondaryHz: Float,
        val decay: Float,
    )
}

// vim: set ts=4 sw=4 et:
