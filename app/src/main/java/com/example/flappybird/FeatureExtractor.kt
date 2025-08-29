package com.example.flappybird

import android.util.Log
import kotlin.math.ln1p
import kotlin.math.log
import kotlin.math.log10

object FeatureExtractor {
    // PCA projection
// input: FloatArray(D) -> raw feature (예: accel_z 200포인트)
// W: Array<FloatArray> (D x K) -> MATLAB에서 저장한 projection matrix
// return: FloatArray(K) -> 축소된 feature
    fun projectPCA(input: FloatArray, W: Array<FloatArray>): FloatArray {
        val D = input.size
        val K = 2
        val result = FloatArray(K) { 0f }

        for (k in 0 until K) {
            var sum = 0f
            for (d in 0 until D) {
                sum += input[d] * W[d][k]
            }
            result[k] = sum
        }
        return result
    }

    fun extractFFT(window: List<FloatArray>, sampleRate: Int = 400): FloatArray {
        val n = window.size
        val dim = window[0].size   // 3축

        val features = mutableListOf<Float>()

//        for (axis in 0 until dim) {
        val axis = 2
            // 1) 신호 추출
            val signal = DoubleArray(n) { i -> window[i][axis].toDouble() }

            // 2) FFT
            val spectrum = fft(signal)

            // 3) Magnitude
            val mags = spectrum.map { (re, im) -> kotlin.math.sqrt(re*re + im*im) }

            val logged = mags.map { m -> ln1p(m) }

            // 4) 1~200 Hz 부분만 추가 (index 1~200)
            val sub = logged.subList(1, n/2 + 1)   // 1..200
            features.addAll(sub.map { it.toFloat() })
//        }

        return features.toFloatArray()
    }

    private fun fft(x: DoubleArray): List<Pair<Double, Double>> {
        val n = x.size
        if (n == 1) return listOf(Pair(x[0], 0.0))

        val even = DoubleArray(n / 2) { x[2 * it] }
        val odd  = DoubleArray(n / 2) { x[2 * it + 1] }

        val fftEven = fft(even)
        val fftOdd = fft(odd)

        val result = MutableList(n) { Pair(0.0, 0.0) }
        for (k in 0 until n / 2) {
            val angle = -2.0 * Math.PI * k / n
            val wk = Pair(kotlin.math.cos(angle), kotlin.math.sin(angle))
            val tRe = wk.first * fftOdd[k].first - wk.second * fftOdd[k].second
            val tIm = wk.first * fftOdd[k].second + wk.second * fftOdd[k].first
            result[k] = Pair(fftEven[k].first + tRe, fftEven[k].second + tIm)
            result[k + n/2] = Pair(fftEven[k].first - tRe, fftEven[k].second - tIm)
        }
        return result
    }
}
