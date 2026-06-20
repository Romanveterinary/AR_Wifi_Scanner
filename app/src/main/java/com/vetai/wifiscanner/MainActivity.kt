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

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    private var isWifiMode = true 

    private lateinit var wifiScanner: WifiSignalScanner
    private lateinit var bluetoothScanner: BluetoothSignalScanner // Додано новий сканер
    
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnWifi = findViewById(R.id.btn_wifi)
        btnBluetooth = findViewById(R.id.btn_bluetooth)
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

        // Ініціалізація обох сканерів
        wifiScanner = WifiSignalScanner(this)
        bluetoothScanner = BluetoothSignalScanner(this)

        // Слухаємо дані від Wi-Fi
        wifiScanner.onSignalFound = { _, _, rssi, color ->
            runOnUiThread { drawSignalInAR(rssi, color) }
        }

        // Слухаємо дані від Bluetooth (працює набагато швидше)
        bluetoothScanner.onSignalFound = { _, _, rssi, color ->
            runOnUiThread { drawSignalInAR(rssi, color) }
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

    private fun drawSignalInAR(rssi: Int, colorInt: Int) {
        val frame = arFragment.arSceneView.arFrame ?: return
        val cameraPose = frame.camera.pose

        // Робимо ефект "рою": чим сильніший сигнал, тим густіша хмара (до 10 крапок за раз)
        val dotsToDraw = when {
            rssi > -60 -> 10 // Сильний сигнал (близько) — щільний рій
            rssi > -80 -> 4  // Середній сигнал
            else -> 1        // Слабкий сигнал — поодинокі крапки
        }

        val arColor = com.google.ar.sceneform.rendering.Color(colorInt)
        MaterialFactory.makeOpaqueWithColor(this, arColor)
            .thenAccept { material ->
                for (i in 0 until dotsToDraw) {
                    val sphereRenderable = ShapeFactory.makeSphere(0.03f, Vector3.zero(), material) // Трохи зменшили розмір крапок

                    // Розкидаємо їх у радіусі 1 метра (ширший рій)
                    val offsetX = Random.nextFloat() * 1.0f - 0.5f 
                    val offsetY = Random.nextFloat() * 1.0f - 0.5f 
                    
                    val forward = floatArrayOf(offsetX, offsetY, -1.5f) // Малюємо на 1.5м перед нами
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
                        anchorNode.renderable = sphereRenderable
                        anchorNode.setParent(arFragment.arSceneView.scene)
                    }
                }
            }
    }

    private fun setMode(wifi: Boolean) {
        isWifiMode = wifi
        if (isWifiMode) {
            btnWifi.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnBluetooth.setBackgroundColor(Color.parseColor("#555555"))
            bluetoothScanner.stopScanning() // Зупиняємо BT
            wifiScanner.startScanning()     // Запускаємо Wi-Fi
        } else {
            btnWifi.setBackgroundColor(Color.parseColor("#555555"))
            btnBluetooth.setBackgroundColor(Color.parseColor("#2196F3"))
            wifiScanner.stopScanning()        // Зупиняємо Wi-Fi
            bluetoothScanner.startScanning()  // Запускаємо BT
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

    // Оновлена перевірка дозволів з урахуванням сучасних вимог Bluetooth
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