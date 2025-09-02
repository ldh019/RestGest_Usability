package com.example.flappybird

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import com.example.flappybird.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    // PC 서버 설정
    private val serverIP = "165.194.27.190" // PC의 IP 주소로 변경
    private val serverPort = 9091
    private var socket: Socket? = null
    private var output: OutputStream? = null

    // 제스처 감지 파라미터
    private val windowSize = 400 // MATLAB의 N과 동일
    private val searchWindow = (windowSize * 0.5).toInt()
    private val thresholdWindow = (windowSize * 1.0).toInt()
    private val thresholdParam = 1.01f // 알파값

    private val bufferCapacity = windowSize * 2 // 버퍼 크기

    // 센서 데이터 버퍼
    private val accelBuffer = ArrayList<AccelSample>()
    private val gyroBuffer = ArrayList<GyroSample>()
    private val magnitudeBuffer = ArrayList<Float>() // 가속도 크기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusText.text = "Initializing..."

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // 서버 연결 시도 (백그라운드 스레드)
        thread {
            try {
                socket = Socket(serverIP, serverPort)
                output = socket?.getOutputStream()
                Log.d("WatchApp", "Connected to server")
                runOnUiThread { binding.statusText.text = "Connected" }
            } catch (e: Exception) {
                runOnUiThread { binding.statusText.text = "Connection Failed: ${e.message}" }
                Log.e("WatchApp", "Connection failed: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
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
                accelBuffer.add(AccelSample(event.timestamp, event.values[0], event.values[1], event.values[2] - 9.8f))
                magnitudeBuffer.add(sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + (event.values[2]-9.8f)*(event.values[2]-9.8f)))
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroBuffer.add(GyroSample(event.timestamp, event.values[0], event.values[1], event.values[2]))
            }
        }

        // 두 센서 데이터가 충분히 모였을 때 처리
        if (accelBuffer.size >= bufferCapacity && gyroBuffer.size >= bufferCapacity) {
            val centerIdx = bufferCapacity / 2

            // Energy 및 Noise 계산 (제스처 감지 로직)
            val energyAvg = magnitudeBuffer.subList(centerIdx - searchWindow, centerIdx + 1).average()
            val noiseAvg = magnitudeBuffer.subList(centerIdx - thresholdWindow, centerIdx + 1).average() * thresholdParam

            if (energyAvg > noiseAvg) { // 제스처 감지!
                binding.statusText.text = "Gesture Detected!"
                Log.d("WatchApp", "Gesture Detected!")

                // 피크 정렬 (Peak Alignment)
                val searchHalf = (windowSize * 0.25).toInt()
                val searchStart = (centerIdx - searchHalf).coerceAtLeast(0)
                val searchEnd = (centerIdx + searchHalf).coerceAtMost(magnitudeBuffer.size - 1)
                val localMags = magnitudeBuffer.subList(searchStart, searchEnd)
                val localPeakIdx = localMags.indices.maxByOrNull { localMags[it] } ?: 0
                val peakIdx = searchStart + localPeakIdx

                // 최종 윈도우 추출
                val windowStartIdx = (peakIdx - (0.2 * windowSize).toInt()).coerceAtLeast(0) // MATLAB 코드와 유사하게 윈도우 시작점 조정
                val windowEndIdx = (windowStartIdx + windowSize).coerceAtMost(accelBuffer.size)

                if (windowEndIdx - windowStartIdx == windowSize) {
                    val combinedWindow = mutableListOf<String>()
                    for (i in windowStartIdx until windowEndIdx) {
                        val row = "${accelBuffer[i].timestamp}," +
                                "${accelBuffer[i].ax},${accelBuffer[i].ay},${accelBuffer[i].az}," +
                                "${gyroBuffer[i].timestamp}," +
                                "${gyroBuffer[i].gx},${gyroBuffer[i].gy},${gyroBuffer[i].gz}"
                        combinedWindow.add(row)
                    }

                    // PC로 윈도우 전송 (코루틴 사용)
                    CoroutineScope(Dispatchers.Main).launch {
                        sendWindowToPC(combinedWindow)
                        binding.statusText.text = "Gesture Sent!"
                        Log.d("WatchApp", "Window extracted and sent at peakIdx=$peakIdx")
                    }

                    // 처리된 윈도우만큼 버퍼에서 삭제
                    accelBuffer.subList(0, windowSize).clear()
                    gyroBuffer.subList(0, windowSize).clear()
                    magnitudeBuffer.subList(0, windowSize).clear()
                } else {
                    // 윈도우 크기가 맞지 않으면 가장 오래된 데이터 삭제
                    accelBuffer.removeAt(0)
                    gyroBuffer.removeAt(0)
                    magnitudeBuffer.removeAt(0)
                }
            } else { // 제스처 미감지
                binding.statusText.text = "No Gesture"
                // 가장 오래된 데이터 삭제
                accelBuffer.removeAt(0)
                gyroBuffer.removeAt(0)
                magnitudeBuffer.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // PC로 센서 윈도우 데이터 전송
    private suspend fun sendWindowToPC(window: List<String>) = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            sb.append("[START]\n")
            window.forEach { row -> sb.append(row).append("\n") }
            sb.append("[END]\n")

            output?.write(sb.toString().toByteArray())
            output?.flush()
            Log.d("WatchApp", "Window sent: ${window.size} rows")
        } catch (e: Exception) {
            Log.e("WatchApp", "Send failed: ${e.message}")
            withContext(Dispatchers.Main) { binding.statusText.text = "Send Failed" }
        }
    }
}

// 센서 데이터 모델 클래스
data class AccelSample(val timestamp: Long, val ax: Float, val ay: Float, val az: Float)
data class GyroSample(val timestamp: Long, val gx: Float, val gy: Float, val gz: Float)