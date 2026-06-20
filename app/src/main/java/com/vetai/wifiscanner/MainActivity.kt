package com.vetai.wifiscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Створюємо змінну для нашого сканера
    private lateinit var wifiScanner: WifiSignalScanner
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Підключаємо файл дизайну (який ми створимо наступним кроком)
        setContentView(R.layout.activity_main)

        // Ініціалізуємо сканер
        wifiScanner = WifiSignalScanner(this)

        // Перевіряємо дозволи і запускаємо сканування
        if (checkPermissions()) {
            wifiScanner.startScanning()
        } else {
            requestPermissions()
        }
    }

    // Коли ви згортаєте додаток, сканер ставиться на паузу, щоб не жерти батарею
    override fun onPause() {
        super.onPause()
        wifiScanner.stopScanning()
    }

    // Коли розгортаєте знову — сканер продовжує роботу
    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            wifiScanner.startScanning()
        }
    }

    // Системна функція перевірки дозволів
    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Виклик віконця "Дозволити додатку доступ до місцезнаходження?"
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    // Обробка вашої відповіді у віконці дозволів
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Якщо дозволили — поїхали сканувати
            wifiScanner.startScanning()
        } else {
            // Якщо відмовили — показуємо повідомлення
            Toast.makeText(this, "Потрібен дозвіл для пошуку сигналів!", Toast.LENGTH_LONG).show()
        }
    }
}