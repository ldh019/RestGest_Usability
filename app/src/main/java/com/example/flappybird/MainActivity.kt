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

class MainActivity : Activity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    // PC의 IP와 포트 (PC에서 Python 서버 실행 중이어야 함)
    private val serverIP = "10.0.2.2"   // 집 Wi-Fi 환경에서 PC IP 입력
    private val serverPort = 9090
    private var socket: Socket? = null
    private var output: OutputStream? = null

    private val debounceMs = 300L
    private var lastTriggerTime = 0L

    // 데이터 윈도우
    private val windowSize = 25
    private val windowData = ArrayList<FloatArray>()

    // KNN 분류기
    private lateinit var knn: KNNClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // JSON 로드 → KNN 초기화
        val samples = loadSamplesFromAssets()
        knn = KNNClassifier(2, samples, rejectThreshold = 2.0f)
        binding.statusText.text = "Loaded ${samples.size} samples"

        binding.statusText.text = "Starting"

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val values = floatArrayOf(event.values[0], event.values[1], event.values[2])
            windowData.add(values)
            if (windowData.size >= windowSize) {
                val features = FeatureExtractor.extract(windowData)
                val label = knn.classify(features)
                if (label != "UNKNOWN") {
                    sendGesture(label)
                }
                windowData.clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendGesture(label: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < debounceMs) return
        lastTriggerTime = now
        val msg = if (label == "PinchL") "LEFT" else "RIGHT"

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

    private fun loadSamplesFromAssets(): List<GestureSample> {
        val json = assets.open("gestures.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<GestureSample>>() {}.type
        return Gson().fromJson(json, type)
    }
}
