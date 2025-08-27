package com.example.flappybird

data class GestureSample(val features: FloatArray, val label: String)

class KNNClassifier(
    private val k: Int,
    private val samples: List<GestureSample>,
    private val rejectThreshold: Float? = null
) {
    fun classify(input: FloatArray): String {
        val neighbors = samples.map {
            val dist = euclidean(input, it.features)
            it.label to dist
        }.sortedBy { it.second }
            .take(k)

        // 최근접 거리 확인
        val bestDist = neighbors.first().second
        if (rejectThreshold != null && bestDist > rejectThreshold) {
            return "UNKNOWN"
        }

        return neighbors.groupBy { it.first }
            .maxByOrNull { it.value.size }?.key ?: "UNKNOWN"
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return kotlin.math.sqrt(sum)
    }
}