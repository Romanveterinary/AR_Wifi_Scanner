package com.vetai.wifiscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log

class WifiSignalScanner(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Змінна для зберігання "рекорду" (найсильнішого сигналу), щоб відсіювати слабкі відбиття
    private var peakRssi = -100
    private var targetBssid = ""

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                analyzeResults()
            }
        }
    }

    fun startScanning() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)

        // Запускаємо перше сканування ефіру
        wifiManager.startScan()
    }

    fun stopScanning() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            Log.e("WIFI_SCANNER", "Ресівер вже зупинено")
        }
    }

    private fun analyzeResults() {
        val results = wifiManager.scanResults
        for (result in results) {
            val ssid = result.SSID
            val bssid = result.BSSID // Унікальна MAC-адреса роутера
            val rssi = result.level  // Сила сигналу

            // Логіка фіксації максимуму: реагуємо тільки якщо сигнал сильніший за попередній
            if (rssi > peakRssi) {
                peakRssi = rssi
                targetBssid = bssid
                Log.d("WIFI_SCANNER", "Новий рекорд! Мережа: $ssid ($bssid), Сигнал: $rssi dBm")
            }
        }
        
        // Одразу даємо команду сканувати знову для безперервного потоку даних
        wifiManager.startScan()
    }
}