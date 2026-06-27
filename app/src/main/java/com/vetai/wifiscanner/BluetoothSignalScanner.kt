package com.vetai.wifiscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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

    // Канал передачі даних
    var onSignalFound: ((mac: String, name: String, rssi: Int, color: Int) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            val mac = device.address
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

    // Оновлено: тепер приймає опціональну MAC-адресу цілі
    fun startScanning(targetMac: String? = null) {
        if (bleScanner == null) {
            Log.e("BT_SCANNER", "Bluetooth не підтримується або вимкнений")
            return
        }

        // Зупиняємо попередній скан перед запуском нового режиму
        try {
            bleScanner.stopScan(scanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Налаштовуємо агресивний режим сканування (без затримок)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (targetMac != null) {
            // СНАЙПЕРСЬКИЙ РЕЖИМ: Апаратний фільтр на одну MAC-адресу
            val filter = ScanFilter.Builder()
                .setDeviceAddress(targetMac)
                .build()
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            Log.d("BT_SCANNER", "Запущено снайперський пошук для: $targetMac")
        } else {
            // РЕЖИМ РАДАРА: Шукаємо всі пристрої
            bleScanner.startScan(null, settings, scanCallback)
            Log.d("BT_SCANNER", "Запущено загальний пошук")
        }
    }

    fun stopScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Генерація яскравого кольору на основі MAC-адреси
    private fun generateColorForMac(mac: String): Int {
        val hash = Math.abs(mac.hashCode())
        val hue = (hash % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }
}
