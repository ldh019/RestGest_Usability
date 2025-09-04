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

        ipEditText.setText("192.168.0.7")
        portEditText.setText("9090") // 기본 포트 설정

        connectButton.setOnClickListener {
            val ip = ipEditText.text.toString()
            val portStr = portEditText.text.toString()

            Log.d("ConnectionActivity", "Attempting to connect to $ip:$portStr")

            if (ip.isEmpty() || portStr.isEmpty()) {
                statusTextView.text = "IP와 포트를 입력해주세요."
                return@setOnClickListener
            }

            // ConnectionActivity에서는 연결만 시도하고,
            // 실제 데이터 송수신은 MainActivity에서 담당합니다.
            // 아래의 코드를 삭제하고 IP와 Port만 MainActivity로 전달합니다.
            val intent = Intent(this@ConnectionActivity, MainActivity::class.java).apply {
                putExtra("IP_ADDRESS", ip)
                putExtra("PORT_NUMBER", portStr.toInt())
            }
            startActivity(intent)
            finish()
        }
    }
}