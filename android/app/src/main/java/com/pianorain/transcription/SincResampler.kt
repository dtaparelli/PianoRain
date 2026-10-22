package com.pianorain.transcription

import kotlin.math.*

/** Windowed-sinc low-pass resampling. Precomputed fractional phases avoid per-sample trig.
 * No trimming, amplitude normalization or accumulated floating-point time step.
 */
class SincResampler(private val inputRate: Int, private val outputRate: Int = 16000) {
    init { require(inputRate in 8000..96000 && outputRate > 0) }
    private val taps = 96
    private val phases = 1024
    private val weights = Array(phases) { phase ->
        val fraction = phase.toDouble() / phases
        val cutoff = min(1.0, outputRate.toDouble()/inputRate) * .94
        DoubleArray(taps) { tap ->
            val x = tap - taps/2 + 1 - fraction
            val sinc = if (abs(x) < 1e-12) cutoff else sin(PI * cutoff * x)/(PI * x)
            val window = .5 + .5 * cos(PI*x/(taps/2))
            if (abs(x) >= taps/2) 0.0 else sinc * window
        }.let { values -> val sum=values.sum(); FloatArray(taps) { (values[it]/sum).toFloat() } }
    }
    companion object { fun pcm16(value: Float)=((value*32768).roundToInt().coerceIn(-32768,32767))/32768f }
    fun outputCount(inputCount: Long) = (inputCount * outputRate + inputRate/2) / inputRate
    fun sample(index: Long, inputCount: Long, read: (Long) -> Float): Float {
        if (inputRate == outputRate) return read(index)
        val numerator = index * inputRate
        val center = numerator / outputRate
        val phase = ((numerator % outputRate) * phases / outputRate).toInt()
        var value = 0.0
        for (tap in 0 until taps) {
            val at = center + tap - taps/2 + 1
            if (at in 0 until inputCount) value += read(at) * weights[phase][tap]
        }
        return value.toFloat()
    }
}
