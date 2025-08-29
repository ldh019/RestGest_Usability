package com.example.flappybird

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import com.example.flappybird.FeatureExtractor.projectPCA
import com.example.flappybird.databinding.ActivityMainBinding
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.sqrt
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opencsv.CSVReader
import java.io.InputStreamReader

class MainActivity : Activity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    // PC의 IP와 포트 (PC에서 Python 서버 실행 중이어야 함)
    private val serverIP = "192.168.0.3"   // 집 Wi-Fi 환경에서 PC IP 입력
    private val serverPort = 9090
    private var socket: Socket? = null
    private var output: OutputStream? = null

    private val debounceMs = 1000L
    private var lastTriggerTime = 0L

    // 데이터 윈도우
    private val windowSize = 400
    private val stepSize = windowSize / 4 // 100
    private val bufferSize = windowSize * 2

    private val accelWindow = ArrayList<DoubleArray>()
    private val gyroWindow = ArrayList<DoubleArray>()

    // KNN 분류기
    private lateinit var knn: KNNClassifier

    private lateinit var pcaMatrix: Array<DoubleArray>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // JSON 로드 → KNN 초기화

        pcaMatrix = loadPCAMatrix()
        val samples = loadSamplesFromCsv()
        val reducedSamples = samples.map {
            val reduced = projectPCA(it.features, pcaMatrix)
            GestureSample(reduced, it.label)
        }
        knn = KNNClassifier(5, reducedSamples)
        binding.statusText.text = "Loaded ${samples.size} samples"

        binding.statusText.text = "Starting"

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // 서버 연결 (별도 스레드에서 실행)
        thread {
            try {
                socket = Socket(serverIP, serverPort)
                output = socket?.getOutputStream()
                Log.d("WatchApp", "Connected to server")
                runOnUiThread { binding.statusText.text = "Connected" }
            } catch (e: Exception) {
                runOnUiThread { binding.statusText.text = "Connection Failed" }
                Log.e("WatchApp", "Connection failed: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelWindow.add(doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble() - 9.8))
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroWindow.add(doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
            }
        }

        if (accelWindow.size >= windowSize) {
            if (!shouldTrigger(accelWindow, gyroWindow)) {
                binding.statusText.text = "IDLE"
                accelWindow.subList(0, stepSize).clear()
                return
            }

            val aligned = alignWindow(accelWindow, windowSize)
            if (aligned != null) {
                val features = FeatureExtractor.extractFFT(aligned.window)
                val reduced = projectPCA(features, pcaMatrix)
                val label = knn.classify(reduced)

                if (label != "UNKNOWN") {
                    sendGesture(label)
                    binding.statusText.text = label
                }

                val cutIndex = (aligned.peakIdx + windowSize / 2)
                    .coerceAtMost(accelWindow.size)
                accelWindow.subList(0, cutIndex).clear()
            } else {
                accelWindow.subList(0, stepSize).clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    data class AlignedWindow(
        val window: List<DoubleArray>,
        val peakIdx: Int
    )

    private fun alignWindow(
        buffer: List<DoubleArray>,
        windowSize: Int
    ): AlignedWindow? {
        // 1. magnitude 계산
        val mags = buffer.map { v ->
            sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        }

        // 2. peak 위치 찾기
        val peakIdx = mags.indices.maxByOrNull { mags[it] } ?: return null
        val half = windowSize / 2

        // 3. peak 중심으로 자르기
        val start = (peakIdx - half).coerceAtLeast(0)
        val end = (start + windowSize).coerceAtMost(buffer.size)

        if (end - start < windowSize) return null

        return AlignedWindow(
            buffer.subList(start, start + windowSize),
            peakIdx
        )
    }

    private fun shouldTrigger(accelWindow: List<DoubleArray>, gyroWindow: List<DoubleArray> = emptyList(), threshold: Double = 0.3): Boolean {
        // window: [ [ax,ay,az], ... ]
        val mags = accelWindow.map { v ->
            sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        }
        val peak = mags.maxOrNull() ?: 0.0
        if (peak > threshold) {
            Log.d("WatchApp", "Peak magnitude: $peak")
        }
        return peak > threshold
    }

    private fun sendGesture(label: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < debounceMs) return
        lastTriggerTime = now
        val msg = if (label == "pinchL") "LEFT" else "RIGHT"

        thread {
            try {
                output?.write("$msg\n".toByteArray())
                output?.flush()
                runOnUiThread { binding.statusText.text = label }
            } catch (e: Exception) {
                runOnUiThread { binding.statusText.text = "Send Failed" }
            }
        }
    }

    private fun loadSamplesFromCsv(fileName: String = "fft_features.csv"): List<GestureSample> {
        val samples = mutableListOf<GestureSample>()
        val inputStream = assets.open(fileName)
        val reader = CSVReader(InputStreamReader(inputStream))

        val allRows = reader.readAll()
        val header = allRows[0]  // 첫 줄은 헤더
        val dataRows = allRows.drop(1)

        for (row in dataRows) {
            val label = row[0]  // class 열
            val features = row.drop(1).map { it.toDouble() }.slice(0 until 600).toDoubleArray()

            samples.add(GestureSample(features, label))
        }

        return samples
    }

    private fun loadPCAMatrix(fileName: String = "pca_accel_xyz.csv"): Array<DoubleArray> {
        val inputStream = assets.open(fileName)
        val reader = CSVReader(InputStreamReader(inputStream))

        val allRows = reader.readAll()
        // CSV는 숫자만 들어있다고 가정 (header 없음)
        return Array(allRows.size) { i ->
            allRows[i].map { it.toDouble() }.toDoubleArray()
        }
    }

}
