package com.pianorain.transcription

import kotlin.math.roundToLong

/** Gapless codec metadata removes encoder frames, never musical leading silence.
 * Codec automatic trimming is disabled so this mapping is applied exactly once.
 */
object PcmTiming {
    fun sourceFrame(presentationUs: Long,rate: Int,encoderDelay: Int): Long =
        (presentationUs * rate / 1e6).roundToLong() - encoderDelay
    fun sourceLength(written: Long,declaredUs: Long,rate: Int,encoderDelay: Int,padding: Int): Long {
        val actual=(written-padding).coerceAtLeast(0)
        return if (declaredUs>0) minOf(actual,((declaredUs*rate/1e6).roundToLong()-encoderDelay-padding).coerceAtLeast(0)) else actual
    }
}
