// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SonarReading(
    val distanceMeters: Float,
    val confidence: Float,
)

class SonarEngine {
    private val sampleRate = 48_000

    @SuppressLint("MissingPermission")
    suspend fun measure(pingCount: Int = 3): SonarReading = withContext(Dispatchers.IO) {
        val probe = BatKitNative.generateProbe(sampleRate)
        val pingSpacingMs = 170L
        val settleMs = 250L
        val silenceSamples = (sampleRate * pingSpacingMs / 1_000L).toInt()
        val settleSamples = (sampleRate * settleMs / 1_000L).toInt()
        val train = buildPingTrain(probe, pingCount, silenceSamples, settleSamples)
        val recordSamples = train.size + sampleRate / 4
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )

        check(minBufferBytes > 0) { "48 kHz float recording is not supported" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, recordSamples * Float.SIZE_BYTES))
            .build()

        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(train.size * Float.SIZE_BYTES)
            .build()

        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            check(player.state == AudioTrack.STATE_INITIALIZED) { "Speaker initialization failed" }
            check(player.write(train, 0, train.size, AudioTrack.WRITE_BLOCKING) == train.size) {
                "Could not load the ping train"
            }

            val recording = FloatArray(recordSamples)
            recorder.startRecording()
            delay(40)
            player.play()

            var offset = 0
            while (offset < recording.size) {
                val count = recorder.read(
                    recording,
                    offset,
                    recording.size - offset,
                    AudioRecord.READ_BLOCKING,
                )
                check(count > 0) { "Microphone read failed: $count" }
                offset += count
            }

            val result = BatKitNative.analyze(recording, sampleRate)
            SonarReading(result[0], result[1])
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
            player.release()
        }
    }

    private fun buildPingTrain(
        probe: FloatArray,
        pingCount: Int,
        silenceSamples: Int,
        settleSamples: Int,
    ): FloatArray {
        val count = pingCount.coerceAtLeast(1)
        val totalSize = count * probe.size + (count - 1) * silenceSamples + settleSamples
        val train = FloatArray(totalSize)
        var offset = 0

        for (i in 0 until count) {
            val gain = when (i) {
                0 -> 1.0f
                1 -> 0.85f
                else -> 0.70f
            }

            for (sample in probe) {
                train[offset++] = sample * gain
            }

            if (i < count - 1) {
                offset += silenceSamples
            }
        }

        return train
    }
}

// vim: set ts=4 sw=4 et:
