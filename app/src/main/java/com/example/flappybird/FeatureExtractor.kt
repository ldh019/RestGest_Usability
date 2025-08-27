package com.example.flappybird

object FeatureExtractor {
    fun extract(window: List<FloatArray>): FloatArray {
        val n = window.size
        val mean = FloatArray(3)
        val std = FloatArray(3)

        for (v in window) {
            mean[0] += v[0]; mean[1] += v[1]; mean[2] += v[2]
        }

        mean[0] = mean[0] / n
        mean[1] = mean[1] / n
        mean[2] = mean[2] / n

        for (v in window) {
            std[0] += (v[0] - mean[0]) * (v[0] - mean[0])
            std[1] += (v[1] - mean[1]) * (v[1] - mean[1])
            std[2] += (v[2] - mean[2]) * (v[2] - mean[2])
        }
        std[0] = kotlin.math.sqrt(std[0] / n)
        std[1] = kotlin.math.sqrt(std[1] / n)
        std[2] = kotlin.math.sqrt(std[2] / n)

        return floatArrayOf(mean[0], mean[1], mean[2], std[0], std[1], std[2])
    }
}
