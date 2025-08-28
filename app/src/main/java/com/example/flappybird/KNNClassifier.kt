package com.example.flappybird

import android.util.Log

data class GestureSample(val features: FloatArray, val label: String)

class KNNClassifier(
    private val k: Int,
    private val samples: List<GestureSample>
) {
    fun classify(input: FloatArray): String {
        // 클래스별로 그룹화
        val classGroups = samples.groupBy { it.label }

        // 클래스별 평균 거리 계산
        val classDistances = classGroups.mapValues { (_, group) ->
            group.map { euclidean(input, it.features) }.average()
        }

        // 평균 거리가 가장 작은 클래스 선택
        val classified = classDistances.minByOrNull { it.value }?.key ?: "IDLE"

        Log.d("Classifier", "Class distances: $classDistances, Classified as: $classified")
        return classified
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.e("Classifier", "Feature size mismatch: input=${a.size}, sample=${b.size}")
            return Float.MAX_VALUE
        }
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return kotlin.math.sqrt(sum)
    }
}