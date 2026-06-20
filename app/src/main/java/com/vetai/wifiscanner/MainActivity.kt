package com.vetai.wifiscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    
    // Змінна для відстеження поточного режиму (за замовчуванням Wi-Fi)
    private var isWifiMode = true 

    private lateinit var wifiScanner: WifiSignalScanner
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Знаходимо кнопки на нашому екрані
        btnWifi = findViewById(R.id.btn_wifi)
        btnBluetooth = findViewById(R.id.btn_bluetooth)

        wifiScanner = WifiSignalScanner(this)

        // 2. Налаштовуємо реакцію на клік для Wi-Fi
        btnWifi.setOnClickListener {
            setMode(wifi = true)
            Toast.makeText(this, "Режим Wi-Fi", Toast.LENGTH_SHORT).show()
        }

        // 3. Налаштовуємо реакцію на клік для Bluetooth
        btnBluetooth.setOnClickListener {
            setMode(wifi = false)
            Toast.makeText(this, "Режим Bluetooth", Toast.LENGTH_SHORT).show()
        }

        // 4. Запитуємо дозволи при старті (тепер і для камери)
        if (checkPermissions()) {
            startCurrentScanner()
        } else {
            requestPermissions()
        }
    }

    // Функція, яка змінює колір кнопок залежно від вибраного режиму
    private fun setMode(wifi: Boolean) {
        isWifiMode = wifi
        if (isWifiMode) {
            btnWifi.setBackgroundColor(Color.parseColor("#4CAF50")) // Яскраво-зелений
            btnBluetooth.setBackgroundColor(Color.parseColor("#555555")) // Сірий
            wifiScanner.startScanning()
        } else {
            btnWifi.setBackgroundColor(Color.parseColor("#555555")) // Сірий
            btnBluetooth.setBackgroundColor(Color.parseColor("#2196F3")) // Яскраво-синій
            wifiScanner.stopScanning() // Зупиняємо Wi-Fi сканер, поки працює Bluetooth
        }
    }

    private fun startCurrentScanner() {
        if (isWifiMode) {
            wifiScanner.startScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        wifiScanner.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            startCurrentScanner()
        }
    }

    // Перевіряємо локацію та камеру
    private fun checkPermissions(): Boolean {
        val locationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val cameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return locationPerm && cameraPerm
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Якщо всі дозволи надано
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCurrentScanner()
        } else {
            Toast.makeText(this, "Для роботи AR потрібні камера та локація!", Toast.LENGTH_LONG).show()
        }
    }
}