package com.example.flappybird

import android.util.Log
import kotlin.math.sqrt

data class GestureSample(val features: DoubleArray, val label: String)

class KNNClassifier(
    private val k: Int,
    private val samples: List<GestureSample>
) {

    fun classify(input: DoubleArray): String {
        // 1. 각 학습 샘플과 거리 계산
        val neighbors = samples.map {
            val dist = euclidean(input, it.features)
            it.label to dist
        }.sortedBy { it.second }  // 거리 오름차순 정렬
            .take(k)                 // k개만 선택

        // 2. 다수결 (가장 많이 나온 라벨)
        val classified = neighbors.groupBy { it.first }
            .maxByOrNull { it.value.size }?.key ?: "UNKNOWN"

        // 로그 출력
        Log.d("KNN", "Neighbors: $neighbors, Classified as: $classified")
        return classified
    }

    fun classifyN(input: DoubleArray): String {
        // 클래스별 그룹핑
        val classGroups = samples.groupBy { it.label }

        // 클래스별 평균 거리 계산
        val classDistances = classGroups.mapValues { (_, group) ->
            group.map { euclidean(input, it.features) }.average()
        }

        // 최소 평균 거리 클래스 선택
        val bestClass = classDistances.minByOrNull { it.value }?.key ?: "UNKNOWN"

        Log.d("Classifier", "Distances: $classDistances, Classified as: $bestClass")
        return bestClass
    }


    private fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "Feature size mismatch: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}