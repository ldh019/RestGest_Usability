package com.example.flappybird

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import com.example.flappybird.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding

    // PC 서버 설정
    private var serverIP: String? = null
    private var serverPort: Int = -1
    private var socket: Socket? = null
    private var output: OutputStream? = null

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


    override fun onBackPressed() {
        // 뒤로가기 버튼 누르면 소켓 닫고 ConnectionActivity로 돌아가기
        socket?.close()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.close()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                binding.statusText.text = "Touch Detected"
                Log.d("TouchApp", "Touch Detected at (${event.x}, ${event.y})")

                CoroutineScope(Dispatchers.Main).launch {
                    sendTouchToPC(event.x, event.y, System.currentTimeMillis())
                    binding.statusText.text = "Touch Sent!"
                    Log.d("TouchApp", "Touch event sent")
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private suspend fun sendTouchToPC(x: Float, y: Float, timestamp: Long) = withContext(Dispatchers.IO) {
        try {
            val touchData = "[TOUCH]\n"
            
            output?.write(touchData.toByteArray())
            output?.flush()
            Log.d("TouchApp", "Touch sent: ($x, $y) at $timestamp")
        } catch (e: Exception) {
            Log.e("TouchApp", "Send failed: ${e.message}")
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