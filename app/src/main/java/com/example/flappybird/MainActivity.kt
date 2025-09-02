package com.example.flappybird

import android.app.Activity
import android.content.Intent
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
    private var serverIP: String? = null
    private var serverPort: Int = -1
    private var socket: Socket? = null
    private var output: OutputStream? = null

    // detection 파라미터
    private val phoneWindow = 400
    private val phoneSearchWindow = (phoneWindow * 0.5).toInt()
    private val phoneThresholdWindow = (phoneWindow * 1.0).toInt()
    private val thresholdParam = 1.3f

    private val bufferCapacity = phoneWindow * 2

    // 버퍼
    private val accelBuffer = ArrayList<AccelSample>()
    private val gyroBuffer = ArrayList<GyroSample>()
    private val magsBuffer = ArrayList<Float>()
    private val energyBuffer = ArrayList<Double>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusText.text = "Initializing..."

        // Intent에서 IP와 Port 정보 받기
        serverIP = intent.getStringExtra("IP_ADDRESS")
        serverPort = intent.getIntExtra("PORT_NUMBER", -1)

        if (serverIP == null || serverPort == -1) {
            binding.statusText.text = "Connection info missing."
            Log.e("MainActivity", "Connection info missing from intent.")
            return
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // 서버 연결 시도 (백그라운드 스레드)
        thread {
            try {
                socket = Socket(serverIP, serverPort)
                output = socket?.getOutputStream()
                Log.d("WatchApp", "Connected to server at $serverIP:$serverPort")
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

    override fun onBackPressed() {
        // 뒤로가기 버튼 누르면 소켓 닫고 ConnectionActivity로 돌아가기
        socket?.close()
        super.onBackPressed()
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

                accelBuffer.add(AccelSample(at, ax, ay, az))
                magsBuffer.add(sqrt(ax * ax + ay * ay + az * az))

                val currentIdx = magsBuffer.size - 1
                val energyStart = (currentIdx - phoneSearchWindow + 1).coerceAtLeast(0)
                val energyAvg = magsBuffer.subList(energyStart, currentIdx + 1).average()
                energyBuffer.add(energyAvg)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroBuffer.add(GyroSample(event.timestamp, event.values[0], event.values[1], event.values[2]))
            }
        }

        if (accelBuffer.size >= bufferCapacity && gyroBuffer.size >= bufferCapacity) {
            val centerIdx = bufferCapacity / 2

            val energyAvg = energyBuffer[centerIdx]

            val noiseStart = (centerIdx - phoneThresholdWindow + 1).coerceAtLeast(0)
            val noiseAvg = energyBuffer.subList(noiseStart, centerIdx + 1).average() * thresholdParam

            if (energyAvg > noiseAvg) {
                binding.statusText.text = "Gesture Detected"
                Log.d("WatchApp", "Gesture Detected")

                val startIdx = (centerIdx - (0.2 * phoneWindow).toInt()).coerceAtLeast(0)
                val endIdx = (startIdx + phoneWindow).coerceAtMost(accelBuffer.size)

                if (endIdx - startIdx == phoneWindow) {
                    val combinedWindow = mutableListOf<String>()
                    for (i in startIdx until endIdx) {
                        val row = "${accelBuffer[i].timestamp}," +
                                "${accelBuffer[i].ax},${accelBuffer[i].ay},${accelBuffer[i].az}," +
                                "${gyroBuffer[i].timestamp}," +
                                "${gyroBuffer[i].gx},${gyroBuffer[i].gy},${gyroBuffer[i].gz}"
                        combinedWindow.add(row)
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        sendWindowToPC(combinedWindow)
                        binding.statusText.text = "Gesture Sent!"
                        Log.d("WatchApp", "Window extracted at phoneIdx=$centerIdx")
                    }

                    accelBuffer.subList(0, phoneWindow).clear()
                    gyroBuffer.subList(0, phoneWindow).clear()
                    magsBuffer.subList(0, phoneWindow).clear()
                    energyBuffer.subList(0, phoneWindow).clear()
                }
            } else {
                binding.statusText.text = "No Gesture"
                accelBuffer.removeAt(0)
                gyroBuffer.removeAt(0)
                magsBuffer.removeAt(0)
                energyBuffer.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
            // 연결 끊김 발생 시 ConnectionActivity로 돌아가기
            withContext(Dispatchers.Main) {
                binding.statusText.text = "Send Failed. Disconnected."
                // 소켓을 닫고 ConnectionActivity로 돌아갑니다.
                socket?.close()
                val intent = Intent(this@MainActivity, ConnectionActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }
}

data class AccelSample(val timestamp: Long, val ax: Float, val ay: Float, val az: Float)
data class GyroSample(val timestamp: Long, val gx: Float, val gy: Float, val gz: Float)