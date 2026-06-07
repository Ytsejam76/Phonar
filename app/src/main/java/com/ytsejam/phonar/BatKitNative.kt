// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

object BatKitNative {
    init {
        System.loadLibrary("phonar_native")
    }

    external fun generateProbe(
        sampleRate: Int,
        probeKind: Int,
        durationMs: Float,
        primaryHz: Float,
        secondaryHz: Float,
        decay: Float,
    ): FloatArray

    /** Returns distance, confidence, direct peak, echo peak, and 14 correlation bins. */
    external fun analyze(
        recording: FloatArray,
        sampleRate: Int,
        probeKind: Int,
        durationMs: Float,
        primaryHz: Float,
        secondaryHz: Float,
        decay: Float,
    ): FloatArray
}

// vim: set ts=4 sw=4 et:
