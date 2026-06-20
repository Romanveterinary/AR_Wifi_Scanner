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
import kotlin.random.Random

// Клас для запам'ятовування "рекорду" сигналу та його AR-крапок
data class SignalRecord(
    var bestRssi: Int,
    val arNodes: MutableList<AnchorNode> = mutableListOf()
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    private var isWifiMode = true 

    private lateinit var wifiScanner: WifiSignalScanner
    private lateinit var bluetoothScanner: BluetoothSignalScanner
    
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment

    // База даних поточних AR-об'єктів (Ключ - MAC-адреса)
    private val signalRecords = mutableMapOf<String, SignalRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnWifi = findViewById(R.id.btn_wifi)
        btnBluetooth = findViewById(R.id.btn_bluetooth)
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

        wifiScanner = WifiSignalScanner(this)
        bluetoothScanner = BluetoothSignalScanner(this)

        // Тепер ми передаємо MAC-адресу (mac) в нашу функцію малювання
        wifiScanner.onSignalFound = { mac, _, rssi, color ->
            runOnUiThread { updateSignalInAR(mac, rssi, color) }
        }

        bluetoothScanner.onSignalFound = { mac, _, rssi, color ->
            runOnUiThread { updateSignalInAR(mac, rssi, color) }
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

    private fun updateSignalInAR(mac: String, rssi: Int, colorInt: Int) {
        val frame = arFragment.arSceneView.arFrame ?: return
        
        // Знаходимо старий запис або створюємо новий з мінімальним початковим рекордом
        val record = signalRecords[mac] ?: SignalRecord(-100).also { signalRecords[mac] = it }

        // Оновлюємо пляму ТІЛЬКИ якщо ми підійшли ближче (сигнал сильніший за попередній)
        if (rssi > record.bestRssi) {
            record.bestRssi = rssi

            // 1. Стираємо старі крапки з простору
            for (node in record.arNodes) {
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }
            record.arNodes.clear()

            // 2. Визначаємо щільність нової плями (чим ближче, тим більший "вибух" крапок)
            val dotsToDraw = when {
                rssi > -45 -> 30 // Впритул до джерела — величезна пляма
                rssi > -60 -> 15 // Близько
                rssi > -80 -> 5  // Середня відстань
                else -> 1        // Далеко
            }

            val cameraPose = frame.camera.pose
            val arColor = com.google.ar.sceneform.rendering.Color(colorInt)

            // 3. Малюємо нову пляму прямо навколо нашої поточної позиції
            MaterialFactory.makeOpaqueWithColor(this, arColor)
                .thenAccept { material ->
                    for (i in 0 until dotsToDraw) {
                        val sphere = ShapeFactory.makeSphere(0.04f, Vector3.zero(), material)

                        // Розкидаємо крапки хаотично навколо нас
                        val offsetX = Random.nextFloat() * 0.8f - 0.4f 
                        val offsetY = Random.nextFloat() * 0.8f - 0.4f 
                        val offsetZ = Random.nextFloat() * 0.8f - 0.4f - 0.5f // Приблизно 0.5м перед камерою

                        val forward = floatArrayOf(offsetX, offsetY, offsetZ)
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
                            
                            // Запам'ятовуємо об'єкт, щоб стерти його при наступному кроці вперед
                            record.arNodes.add(anchorNode)
                        }
                    }
                }
        }
    }

    // Функція повного очищення AR-сцени
    private fun clearARScene() {
        for (record in signalRecords.values) {
            for (node in record.arNodes) {
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }
        }
        signalRecords.clear()
    }

    private fun setMode(wifi: Boolean) {
        // Очищаємо простір при перемиканні режиму
        clearARScene()

        isWifiMode = wifi
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