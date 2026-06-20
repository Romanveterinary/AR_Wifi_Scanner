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
    private val PERMISSION_REQUEST_CODE = 123

    // Змінна для нашої AR-камери
    private lateinit var arFragment: ArFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnWifi = findViewById(R.id.btn_wifi)
        btnBluetooth = findViewById(R.id.btn_bluetooth)
        
        // Знаходимо AR-фрагмент
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

        wifiScanner = WifiSignalScanner(this)

        // Слухаємо дані від сканера і малюємо крапки
        wifiScanner.onSignalFound = { bssid, ssid, rssi, color ->
            runOnUiThread {
                drawSignalInAR(rssi, color)
            }
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

    // Функція малювання кольорових плям (сфер) у просторі
    private fun drawSignalInAR(rssi: Int, colorInt: Int) {
        val frame = arFragment.arSceneView.arFrame ?: return
        val cameraPose = frame.camera.pose

        // Визначаємо щільність крапок залежно від сили сигналу (RSSI зазвичай від -30 до -90)
        val dotsToDraw = when {
            rssi > -50 -> 5 // Дуже сильний сигнал — щільна хмара
            rssi > -70 -> 3 // Середній сигнал
            else -> 1       // Слабкий сигнал — поодинокі крапки
        }

        // Створюємо 3D-матеріал з нашим унікальним кольором
        val arColor = com.google.ar.sceneform.rendering.Color(colorInt)
        MaterialFactory.makeOpaqueWithColor(this, arColor)
            .thenAccept { material ->
                // Малюємо задану кількість крапок
                for (i in 0 until dotsToDraw) {
                    // Створюємо сферу радіусом 5 см
                    val sphereRenderable = ShapeFactory.makeSphere(0.05f, Vector3.zero(), material)

                    // Розкидаємо їх трохи хаотично на відстані 1 метр перед камерою
                    val offsetX = Random.nextFloat() * 0.5f - 0.25f // Відхилення по X
                    val offsetY = Random.nextFloat() * 0.5f - 0.25f // Відхилення по Y
                    
                    // Обчислюємо позицію в просторі (1 метр вперед по осі Z)
                    val forward = floatArrayOf(offsetX, offsetY, -1f)
                    val transformed = FloatArray(3)
                    cameraPose.rotateVector(forward, 0, transformed, 0)
                    val pos = cameraPose.translation
                    
                    val anchorPose = Pose.makeTranslation(
                        pos[0] + transformed[0], 
                        pos[1] + transformed[1], 
                        pos[2] + transformed[2]
                    )

                    // Прив'язуємо об'єкт до простору
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
            wifiScanner.startScanning()
        } else {
            btnWifi.setBackgroundColor(Color.parseColor("#555555"))
            btnBluetooth.setBackgroundColor(Color.parseColor("#2196F3"))
            wifiScanner.stopScanning()
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
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCurrentScanner()
        } else {
            Toast.makeText(this, "Для роботи AR потрібні камера та локація!", Toast.LENGTH_LONG).show()
        }
    }
}