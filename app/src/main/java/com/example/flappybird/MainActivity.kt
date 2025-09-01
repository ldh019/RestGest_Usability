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

    // PC의 IP와 포트 (PC에서 Python 서버 실행 중이어야 함)
    private val serverIP = "192.168.0.7"   // 집 Wi-Fi 환경에서 PC IP 입력
    private val serverPort = 9091
    private var socket: Socket? = null
    private var output: OutputStream? = null

    // detection 파라미터
    private val phoneWindow = 400
    private val phoneSearchWindow = (phoneWindow * 0.5).toInt()      // MATLAB X=0.5
    private val phoneThresholdWindow = (phoneWindow * 1.0).toInt()   // MATLAB Y=1
    private val thresholdParam = 1.1f                               // MATLAB alpha=1.3

    private val bufferSize = phoneWindow * 2

    // 버퍼
    private val accelWindow = ArrayList<AccelSample>()
    private val gyroWindow = ArrayList<GyroSample>()
    private val mags = ArrayList<Float>()

    // energy / noise 계산용
    private val energyBuffer = ArrayDeque<Float>()
    private val noiseBuffer = ArrayDeque<Float>()
    private var energySum = 0f
    private var noiseSum = 0f

    val b = doubleArrayOf(
        0.98895425, -1.97790850, 0.98895425
    )
    val a = doubleArrayOf(
        1.0, -1.97778648, 0.97803051
    )
    val hpFilterX = IIRFilter(b, a)
    val hpFilterY = IIRFilter(b, a)
    val hpFilterZ = IIRFilter(b, a)

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
                val at = event.timestamp
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2] - 9.8f

                accelWindow.add(AccelSample(at, ax, ay, az))
                mags.add(sqrt(ax * ax + ay * ay + az * az))
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroWindow.add(GyroSample(event.timestamp, event.values[0], event.values[1], event.values[2]))
            }
        }

        // 충분히 모였을 때만 계산
        if (accelWindow.size >= bufferSize && gyroWindow.size >= bufferSize) {
            val phoneIdx = bufferSize - (bufferSize / 2)

            val energyStart = (phoneIdx - phoneSearchWindow + 1).coerceAtLeast(0)
            val energyAvg = mags.subList(energyStart, phoneIdx + 1).average()

            val noiseStart = (phoneIdx - phoneThresholdWindow + 1).coerceAtLeast(0)
            val noiseAvg = mags.subList(noiseStart, phoneIdx + 1).average() * thresholdParam
            val noiseAvg2 = mags.subList(noiseStart, phoneIdx + 1).average() * 1.1
            val noiseAvg3 = mags.subList(noiseStart, phoneIdx + 1).average() * 1.05

            if (energyAvg > noiseAvg3) {
                Log.d(
                    "WatchApp",
                    "1.2 : ${energyAvg > noiseAvg}, 1.1 : ${energyAvg > noiseAvg2}, 1.05 : ${energyAvg > noiseAvg3}"
                )
            }
            if (energyAvg > noiseAvg) {
                binding.statusText.text = "Gesture Detected"
                Log.d("WatchApp", "Gesture Detected")

                // --- Peak alignment (phoneIdx 근방 ±window/4 탐색) ---
                val searchHalf = (phoneWindow * 0.25).toInt()
                val searchStart = (phoneIdx - searchHalf).coerceAtLeast(0)
                val searchEnd = (phoneIdx + searchHalf).coerceAtMost(mags.size - 1)

                val localMags = mags.subList(searchStart, searchEnd)
                val localPeakIdx = localMags.indices.maxByOrNull { localMags[it] } ?: 0
                val peakIdx = searchStart + localPeakIdx

                // --- 최종 window 추출 ---
                val startIdx = (peakIdx - (0.2 * phoneWindow).toInt()).coerceAtLeast(0)
                val endIdx = (startIdx + phoneWindow).coerceAtMost(accelWindow.size)

                if (endIdx - startIdx == phoneWindow) {
                    val combinedWindow = mutableListOf<String>()
                    for (i in startIdx until endIdx) {
                        val row = "${accelWindow[i].timestamp}," +
                                "${accelWindow[i].ax},${accelWindow[i].ay},${accelWindow[i].az}," +
                                "${gyroWindow[i].timestamp}," +
                                "${gyroWindow[i].gx},${gyroWindow[i].gy},${gyroWindow[i].gz}"
                        combinedWindow.add(row)
                    }

                    // --- 전송은 IO 쓰레드에서, UI 업데이트는 Main 쓰레드에서 ---
                    CoroutineScope(Dispatchers.Main).launch {
                        sendWindowToPC(combinedWindow)
                        binding.statusText.text = "Gesture Sent!"
                        Log.d("WatchApp", "Window extracted at peakIdx=$peakIdx")
                    }

                    // MATLAB: phoneIdx = phoneIdx + phoneWindow 효과 → windowSize만큼 삭제
                    accelWindow.subList(0, phoneWindow).clear()
                    gyroWindow.subList(0, phoneWindow).clear()
                    mags.subList(0, phoneWindow).clear()
                }
            } else {
                binding.statusText.text = "No Gesture"

                accelWindow.removeAt(0)
                gyroWindow.removeAt(0)
                mags.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private suspend fun sendWindowToPC(window: List<String>) = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            sb.append("[START]\n")
            for (row in window) {
                sb.append(row).append("\n")
            }
            sb.append("[END]\n")

            output?.write(sb.toString().toByteArray())
            output?.flush()

            Log.d("WatchApp", "Window sent: ${window.size} rows")
        } catch (e: Exception) {
            Log.e("WatchApp", "Send failed: ${e.message}")
            withContext(Dispatchers.Main) { binding.statusText.text = "Send Failed" }
        }
    }

    private fun extractWindow(window: List<FloatArray>, size: Int): List<FloatArray>? {
        val mags = window.map { v -> sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) }
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
            sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        }
        val peak = mags.maxOrNull() ?: 0f
        return peak > threshold
    }

    // MATLAB movmean 대응 함수
    private fun updateDetection(ax: Float, ay: Float, az: Float): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)

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

// 가속도 버퍼에 Long timestamp + Float 값들 저장
data class AccelSample(
    val timestamp: Long,
    val ax: Float,
    val ay: Float,
    val az: Float
)

data class GyroSample(
    val timestamp: Long,
    val gx: Float,
    val gy: Float,
    val gz: Float
)

class IIRFilter(
    private val b: DoubleArray,
    private val a: DoubleArray
) {
    private val xHist = DoubleArray(b.size)
    private val yHist = DoubleArray(a.size)

    fun filter(x: Float): Float {
        // 입력 이력 업데이트
        for (i in xHist.size - 1 downTo 1) {
            xHist[i] = xHist[i - 1]
        }
        xHist[0] = x.toDouble()

        // 출력 계산
        var y = 0.0
        for (i in b.indices) {
            y += b[i] * xHist[i]
        }
        for (i in 1 until a.size) {
            y -= a[i] * yHist[i]
        }
        y /= a[0]

        // 출력 이력 업데이트
        for (i in yHist.size - 1 downTo 1) {
            yHist[i] = yHist[i - 1]
        }
        yHist[0] = y

        return y.toFloat()
    }
}
