package com.vetai.wifiscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment

// Тепер ми зберігаємо лише ОДИН поточний AR-вузол для кожного джерела
data class SignalRecord(
    var currentNode: AnchorNode? = null
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    private var isWifiMode = true 

    private lateinit var wifiScanner: WifiSignalScanner
    private lateinit var bluetoothScanner: BluetoothSignalScanner
    
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment

    // Окремі бази даних для Wi-Fi та Bluetooth
    private val wifiRecords = mutableMapOf<String, SignalRecord>()
    private val bluetoothRecords = mutableMapOf<String, SignalRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnWifi = findViewById(R.id.btn_wifi)
        btnBluetooth = findViewById(R.id.btn_bluetooth)
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

        wifiScanner = WifiSignalScanner(this)
        bluetoothScanner = BluetoothSignalScanner(this)

        wifiScanner.onSignalFound = { mac, _, rssi, color ->
            runOnUiThread { updateSignalInAR(mac, rssi, color, isWifiSignal = true) }
        }

        bluetoothScanner.onSignalFound = { mac, _, rssi, color ->
            runOnUiThread { updateSignalInAR(mac, rssi, color, isWifiSignal = false) }
        }

        btnWifi.setOnClickListener {
            setMode(wifi = true)
            Toast.makeText(this, "Режим Wi-Fi", Toast.LENGTH_SHORT).show()
        }

        btnBluetooth.setOnClickListener {
            setMode(wifi = false)
            Toast.makeText(this, "Режим Bluetooth", Toast.LENGTH_SHORT).show()
        }

        if (checkPermissions()) {
            startCurrentScanner()
        } else {
            requestPermissions()
        }
    }

    private fun updateSignalInAR(mac: String, rssi: Int, colorInt: Int, isWifiSignal: Boolean) {
        // Захист від малювання фонових сигналів іншого режиму
        if (isWifiSignal != isWifiMode) return

        val frame = arFragment.arSceneView.arFrame ?: return
        val records = if (isWifiSignal) wifiRecords else bluetoothRecords
        val record = records[mac] ?: SignalRecord().also { records[mac] = it }

        // 1. МИТТЄВО СТИРАЄМО СТАРУ БУЛЬКУ ЦЬОГО ДЖЕРЕЛА
        record.currentNode?.let { node ->
            node.renderable = null
            node.anchor?.detach()
            node.setParent(null)
        }

        // 2. ОБЧИСЛЮЄМО НОВИЙ РОЗМІР (Залежно від сили сигналу RSSI)
        val sphereRadius = when {
            rssi > -40 -> 0.25f  // Дуже сильний сигнал (впритул) -> 25 см (величезна куля)
            rssi > -60 -> 0.12f  // Близько -> 12 см
            rssi > -80 -> 0.05f  // Середня відстань -> 5 см
            else -> 0.02f        // Далеко (слабкий) -> 2 см (крихітна крапка)
        }

        val cameraPose = frame.camera.pose
        val arColor = com.google.ar.sceneform.rendering.Color(colorInt)

        // 3. МАЛЮЄМО НОВУ БУЛЬКУ ПЕРЕД КАМЕРОЮ
        MaterialFactory.makeOpaqueWithColor(this, arColor)
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(sphereRadius, Vector3.zero(), material)

                // Ставимо маяк на 0.5м рівно перед камерою
                val forward = floatArrayOf(0f, 0f, -0.5f)
                val transformed = FloatArray(3)
                cameraPose.rotateVector(forward, 0, transformed, 0)
                val pos = cameraPose.translation
                
                val anchorPose = Pose.makeTranslation(
                    pos[0] + transformed[0], 
                    pos[1] + transformed[1], 
                    pos[2] + transformed[2]
                )

                val anchor = arFragment.arSceneView.session?.createAnchor(anchorPose)
                anchor?.let {
                    val anchorNode = AnchorNode(it)
                    anchorNode.renderable = sphere
                    anchorNode.setParent(arFragment.arSceneView.scene)
                    
                    // 4. ЗАПАМ'ЯТОВУЄМО ЦЮ БУЛЬКУ, ЩОБ СТЕРТИ ЇЇ ПРИ НАСТУПНОМУ ПАКЕТІ
                    record.currentNode = anchorNode
                }
            }
    }

    private fun setMode(wifi: Boolean) {
        isWifiMode = wifi
        
        // Приховуємо бульби неактивного режиму, показуємо активні
        wifiRecords.values.forEach { it.currentNode?.isEnabled = isWifiMode }
        bluetoothRecords.values.forEach { it.currentNode?.isEnabled = !isWifiMode }

        if (isWifiMode) {
            btnWifi.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnBluetooth.setBackgroundColor(Color.parseColor("#555555"))
            bluetoothScanner.stopScanning()
            wifiScanner.startScanning()
        } else {
            btnWifi.setBackgroundColor(Color.parseColor("#555555"))
            btnBluetooth.setBackgroundColor(Color.parseColor("#2196F3"))
            wifiScanner.stopScanning()
            bluetoothScanner.startScanning()
        }
    }

    private fun startCurrentScanner() {
        if (isWifiMode) {
            wifiScanner.startScanning()
        } else {
            bluetoothScanner.startScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        wifiScanner.stopScanning()
        bluetoothScanner.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            startCurrentScanner()
        }
    }

    private fun checkPermissions(): Boolean {
        val locationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val cameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val btScanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        return locationPerm && cameraPerm && btScanPerm
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCurrentScanner()
        } else {
            Toast.makeText(this, "Надайте всі дозволи для роботи сканера!", Toast.LENGTH_LONG).show()
        }
    }
}