package com.vetai.wifiscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.util.Log

class WifiSignalScanner(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Словник для збереження унікального кольору для кожної MAC-адреси
    private val bssidColors = mutableMapOf<String, Int>()

    // Канал передачі даних: віддає BSSID, назву, силу сигналу та згенерований колір
    var onSignalFound: ((bssid: String, ssid: String, rssi: Int, color: Int) -> Unit)? = null

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
            val ssid = if (result.SSID.isNullOrEmpty()) "Прихована мережа" else result.SSID
            val bssid = result.BSSID ?: continue
            val rssi = result.level  // Сила сигналу

            // Якщо це нове джерело — генеруємо для нього унікальний колір
            if (!bssidColors.containsKey(bssid)) {
                bssidColors[bssid] = generateColorForBssid(bssid)
            }

            val signalColor = bssidColors[bssid]!!

            // Відправляємо дані назовні (в MainActivity)
            onSignalFound?.invoke(bssid, ssid, rssi, signalColor)
            
            Log.d("WIFI_SCANNER", "Сигнал: $ssid ($bssid), RSSI: $rssi dBm, Колір: $signalColor")
        }
        
        // Одразу даємо команду сканувати знову для безперервного потоку даних
        // Примітка: Android обмежує частоту Wi-Fi сканувань (throttling), тому реальна частота оновлень залежатиме від системи
        wifiManager.startScan()
    }

    // Генерація яскравого кольору на основі унікальної MAC-адреси
    private fun generateColorForBssid(bssid: String): Int {
        val hash = Math.abs(bssid.hashCode())
        val hue = (hash % 360).toFloat() // Відтінок по колу (0-360)
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f)) // Насиченість і яскравість на максимум
    }
}