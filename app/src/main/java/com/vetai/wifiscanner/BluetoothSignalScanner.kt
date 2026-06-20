package com.vetai.wifiscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Color
import android.util.Log

@SuppressLint("MissingPermission")
class BluetoothSignalScanner(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner

    // Словник для збереження унікального кольору для кожного Bluetooth-девайса
    private val macColors = mutableMapOf<String, Int>()

    // Канал передачі даних (такий самий, як у Wi-Fi)
    var onSignalFound: ((mac: String, name: String, rssi: Int, color: Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            val mac = device.address
            // Отримуємо ім'я пристрою, якщо воно є
            val name = device.name ?: "Невідомий BT пристрій"

            // Призначаємо унікальний колір
            if (!macColors.containsKey(mac)) {
                macColors[mac] = generateColorForMac(mac)
            }
            val signalColor = macColors[mac]!!

            // Відправляємо дані назовні
            onSignalFound?.invoke(mac, name, rssi, signalColor)
        }
    }

    fun startScanning() {
        if (bleScanner == null) {
            Log.e("BT_SCANNER", "Bluetooth не підтримується або вимкнений")
            return
        }
        // Запускаємо безперервне BLE сканування
        bleScanner.startScan(scanCallback)
    }

    fun stopScanning() {
        bleScanner?.stopScan(scanCallback)
    }

    // Генерація яскравого кольору на основі MAC-адреси
    private fun generateColorForMac(mac: String): Int {
        val hash = Math.abs(mac.hashCode())
        val hue = (hash % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }
}