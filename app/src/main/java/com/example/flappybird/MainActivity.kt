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

class MainActivity : Activity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    // PC의 IP와 포트 (PC에서 Python 서버 실행 중이어야 함)
    private val serverIP = "192.168.0.7"   // 집 Wi-Fi 환경에서 PC IP 입력
    private val serverPort = 9090
    private var socket: Socket? = null
    private var output: OutputStream? = null

    // detection 파라미터
    private val phoneWindow = 400
    private val phoneSearchWindow = (phoneWindow * 0.5).toInt()      // MATLAB X=0.5
    private val phoneThresholdWindow = (phoneWindow * 1.0).toInt()   // MATLAB Y=1
    private val thresholdParam = 1.3f                               // MATLAB alpha=1.3

    private val bufferSize = phoneWindow * 2

    // 버퍼
    private val accelWindow = ArrayList<FloatArray>()
    private val gyroWindow = ArrayList<FloatArray>()
    private val mags = ArrayList<Float>()

    // energy / noise 계산용
    private val energyBuffer = ArrayDeque<Float>()
    private val noiseBuffer = ArrayDeque<Float>()
    private var energySum = 0f
    private var noiseSum = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2] - 9.8f
                accelWindow.add(floatArrayOf(ax, ay, az))

                val mag = sqrt(ax*ax + ay*ay + az*az)
                mags.add(mag)
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroWindow.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
            }
        }

        // 충분히 모였을 때만 계산
        if (accelWindow.size >= phoneWindow && gyroWindow.size >= phoneWindow) {
            // MATLAB: phoneEnergy = movmean(..., searchWindow)
            val energyAvg = mags.takeLast(phoneSearchWindow).average()

            // MATLAB: phoneNoise = movmean(..., thresholdWindow) * alpha
            val noiseAvg = mags.takeLast(phoneThresholdWindow).average() * thresholdParam

            if (energyAvg <= noiseAvg) {
                // MATLAB: phoneIdx = phoneIdx + 1
                // → 여기서는 그냥 한 스텝씩 버퍼를 슬라이드
                accelWindow.removeAt(0)
                gyroWindow.removeAt(0)
                mags.removeAt(0)
                binding.statusText.text = "No Gesture"
                return
            }

            // MATLAB: detection 발생
            binding.statusText.text = "Gesture Detected"
            Log.d("WatchApp", "Gesture Detected")

            // peak 중심 잡기 (phoneStartIdx = phoneIdx - 0.2*window)
            val peakIdx = mags.indices.maxByOrNull { mags[it] } ?: 0
            val startIdx = (peakIdx - (0.2 * phoneWindow).toInt()).coerceAtLeast(0)
            val endIdx = (startIdx + phoneWindow).coerceAtMost(accelWindow.size)

            if (endIdx - startIdx == phoneWindow) {
                val combinedWindow = mutableListOf<DoubleArray>()
                for (i in startIdx until endIdx) {
                    val row = doubleArrayOf(
                        accelWindow[i][0].toDouble(), accelWindow[i][1].toDouble(), accelWindow[i][2].toDouble(),
                        gyroWindow[i][0].toDouble(),  gyroWindow[i][1].toDouble(),  gyroWindow[i][2].toDouble()
                    )
                    combinedWindow.add(row)
                }

                sendWindowToPC(combinedWindow)
                binding.statusText.text = "Gesture Sent!"
                Log.d("WatchApp", "Window extracted")

                // MATLAB: phoneIdx = phoneIdx + phoneWindow
                // → 여기서는 windowSize 만큼 버퍼에서 제거
                accelWindow.subList(0, endIdx).clear()
                gyroWindow.subList(0, endIdx).clear()
                mags.subList(0, endIdx).clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendWindowToPC(window: List<DoubleArray>) {
        thread {
            try {
                val sb = StringBuilder()
                sb.append("[START]\n")
                for (row in window) {
                    sb.append(row.joinToString(","))
                    sb.append("\n")
                }
                sb.append("[END]\n")

                output?.write(sb.toString().toByteArray())
                output?.flush()

                Log.d("WatchApp", "Window sent: ${window.size} rows")
            } catch (e: Exception) {
                Log.e("WatchApp", "Send failed: ${e.message}")
                runOnUiThread { binding.statusText.text = "Send Failed" }
            }
        }
    }

    private fun extractWindow(window: List<FloatArray>, size: Int): List<FloatArray>? {
        val mags = window.map { v -> sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]) }
        val peakIdx = mags.indices.maxByOrNull { mags[it] } ?: return null
        val half = size / 2
        val start = (peakIdx - half).coerceAtLeast(0)
        val end = (start + size).coerceAtMost(window.size)
        if (end - start < size) return null
        return window.subList(start, start + size)
    }

    private fun shouldTrigger(
        accelWindow: List<FloatArray>,
        threshold: Float = 0.5f
    ): Boolean {
        val mags = accelWindow.map { v ->
            sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        }
        val peak = mags.maxOrNull() ?: 0f
        return peak > threshold
    }

    // MATLAB movmean 대응 함수
    private fun updateDetection(ax: Float, ay: Float, az: Float): Boolean {
        val mag = sqrt(ax*ax + ay*ay + az*az)

        // energy (short window average)
        energyBuffer.addLast(mag)
        energySum += mag
        if (energyBuffer.size > phoneSearchWindow) {
            energySum -= energyBuffer.removeFirst()
        }
        val energyAvg = energySum / energyBuffer.size

        // noise (long window average)
        noiseBuffer.addLast(mag)
        noiseSum += mag
        if (noiseBuffer.size > phoneThresholdWindow) {
            noiseSum -= noiseBuffer.removeFirst()
        }
        val noiseAvg = noiseSum / noiseBuffer.size

        return (energyAvg > noiseAvg * thresholdParam)
    }
}
