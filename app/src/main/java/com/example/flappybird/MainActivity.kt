package com.example.flappybird

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
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
//    private var gyroSensor: Sensor? = null

    // PC의 IP와 포트 (PC에서 Python 서버 실행 중이어야 함)
    private val serverIP = "192.168.0.3"   // 집 Wi-Fi 환경에서 PC IP 입력
    private val serverPort = 9090
    private var socket: Socket? = null
    private var output: OutputStream? = null

    private val debounceMs = 400L
    private var lastTriggerTime = 0L

    // 데이터 윈도우
    private val windowSize = 400
    private val stepSize = windowSize / 2  // 200

    private val accelWindow = ArrayList<FloatArray>()
//    private val gyroWindow = ArrayList<FloatArray>()

    // KNN 분류기
    private lateinit var knn: KNNClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // JSON 로드 → KNN 초기화
        val samples = loadSamplesFromCsv()
        knn = KNNClassifier(1, samples)
        binding.statusText.text = "Loaded ${samples.size} samples"

        binding.statusText.text = "Starting"

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

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
//        gyroSensor?.also {
//            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
//        }
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
                accelWindow.add(floatArrayOf(event.values[0], event.values[1], event.values[2] - 9.8f))
            }
//            Sensor.TYPE_GYROSCOPE -> {
//                gyroWindow.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
//            }
        }

//        // 둘 다 충분히 쌓였을 때 처리
//        if (accelWindow.size >= windowSize && gyroWindow.size >= windowSize) {
//            val accelFeatures = FeatureExtractor.extractFFT(accelWindow.subList(0, windowSize))
//            val gyroFeatures  = FeatureExtractor.extractFFT(gyroWindow.subList(0, windowSize))
//            val combinedFeatures = accelFeatures + gyroFeatures
//
//            val label = knn.classify(combinedFeatures)
//            if (label != "UNKNOWN") sendGesture(label)
//
//            accelWindow.subList(0, stepSize).clear()
//            gyroWindow.subList(0, stepSize).clear()
//        }

        if (accelWindow.size >= windowSize) {
            val accelFeatures = FeatureExtractor.extractFFT(accelWindow.subList(0, windowSize))

            val label = knn.classify(accelFeatures)
            if (label != "UNKNOWN") sendGesture(label)

            accelWindow.subList(0, stepSize).clear()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
            val features = row.drop(1).map { it.toFloat() }.toFloatArray().slice(400 until 600).toFloatArray()

            samples.add(GestureSample(features, label))
        }

        return samples
    }
}
