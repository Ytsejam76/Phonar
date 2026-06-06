// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

object BatKitNative {
    init {
        System.loadLibrary("phonar_native")
    }

    external fun generateProbe(sampleRate: Int): FloatArray

    /** Returns distance in meters and correlation confidence, or [-1, 0]. */
    external fun analyze(recording: FloatArray, sampleRate: Int): FloatArray
}

// vim: set ts=4 sw=4 et:
