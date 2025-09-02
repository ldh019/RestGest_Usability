package com.example.flappybird

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.ConnectException

class ConnectionActivity : Activity() {

    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        connectButton = findViewById(R.id.connectButton)
        statusTextView = findViewById(R.id.statusText)

        // 초기 IP와 포트 설정
        ipEditText.setText("192.168.0.1")
        portEditText.setText("9090")

        connectButton.setOnClickListener {
            val ip = ipEditText.text.toString()
            val portStr = portEditText.text.toString()

            Log.d("ConnectionActivity", "Attempting to connect to $ip:$portStr")

            if (ip.isEmpty() || portStr.isEmpty()) {
                statusTextView.text = "IP와 포트를 입력해주세요."
                return@setOnClickListener
            }

            val port = portStr.toInt()
            statusTextView.text = "연결 중..."

            // 백그라운드에서 연결 시도
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val socket = Socket(ip, port)
                    Log.d("ConnectionActivity", "연결 성공")
                    withContext(Dispatchers.Main) {
                        statusTextView.text = "연결 완료!"
                    }
                    // 연결 성공 시 MainActivity로 이동
                    val intent = Intent(this@ConnectionActivity, MainActivity::class.java).apply {
                        putExtra("IP_ADDRESS", ip)
                        putExtra("PORT_NUMBER", port)
                    }
                    startActivity(intent)
                    finish() // 현재 액티비티 종료
                } catch (e: ConnectException) {
                    Log.e("ConnectionActivity", "연결 실패: ${e.message}")
                    withContext(Dispatchers.Main) {
                        statusTextView.text = "연결 실패: ${e.message}"
                    }
                } catch (e: Exception) {
                    Log.e("ConnectionActivity", "오류 발생: ${e.message}")
                    withContext(Dispatchers.Main) {
                        statusTextView.text = "오류: ${e.message}"
                    }
                }
            }
        }
    }
}