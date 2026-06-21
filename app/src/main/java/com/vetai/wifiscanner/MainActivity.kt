package com.vetai.wifiscanner

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
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

data class SignalRecord(
    var currentNode: AnchorNode? = null
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    private lateinit var btnExit: Button
    private lateinit var btnSettings: Button
    private lateinit var topControls: RelativeLayout
    private lateinit var bottomControls: LinearLayout
    private var isWifiMode = true 

    private lateinit var wifiScanner: WifiSignalScanner
    private lateinit var bluetoothScanner: BluetoothSignalScanner
    
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment

    private val wifiRecords = mutableMapOf<String, SignalRecord>()
    private val bluetoothRecords = mutableMapOf<String, SignalRecord>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable {
        topControls.visibility = View.GONE
        bottomControls.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_main)

            btnWifi = findViewById(R.id.btn_wifi)
            btnBluetooth = findViewById(R.id.btn_bluetooth)
            btnExit = findViewById(R.id.btn_exit)
            btnSettings = findViewById(R.id.btn_settings)
            topControls = findViewById(R.id.top_controls)
            bottomControls = findViewById(R.id.bottom_controls)
            
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
                resetUiTimer()
            }

            btnBluetooth.setOnClickListener {
                setMode(wifi = false)
                Toast.makeText(this, "Режим Bluetooth", Toast.LENGTH_SHORT).show()
                resetUiTimer()
            }

            btnExit.setOnClickListener {
                finish()
            }

            btnSettings.setOnClickListener {
                Toast.makeText(this, "Меню в розробці...", Toast.LENGTH_SHORT).show()
                resetUiTimer()
            }

            if (checkPermissions()) {
                startCurrentScanner()
            } else {
                requestPermissions()
            }

            resetUiTimer()

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Критична помилка запуску")
                .setMessage(e.stackTraceToString())
                .setPositiveButton("Закрити") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    // ГОЛОВНИЙ ПЕРЕХОПЛЮВАЧ ДОТИКІВ: працює за будь-яких умов
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        showUi() // Показуємо кнопки при кожному дотику до екрану
        return super.dispatchTouchEvent(ev)
    }

    private fun showUi() {
        // Робимо елементи видимими, якщо вони були приховані
        if (topControls.visibility != View.VISIBLE) {
            topControls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
        }
        resetUiTimer() // Скидаємо 5-секундний таймер
    }

    private fun resetUiTimer() {
        uiHandler.removeCallbacks(hideUiRunnable)
        uiHandler.postDelayed(hideUiRunnable, 5000)
    }

    private fun updateSignalInAR(mac: String, rssi: Int, colorInt: Int, isWifiSignal: Boolean) {
        if (isWifiSignal != isWifiMode) return

        val frame = arFragment.arSceneView.arFrame ?: return
        val records = if (isWifiSignal) wifiRecords else bluetoothRecords
        val record = records[mac] ?: SignalRecord().also { records[mac] = it }

        record.currentNode?.let { node ->
            node.renderable = null
            node.anchor?.detach()
            node.setParent(null)
        }

        val sphereRadius = when {
            rssi > -40 -> 0.25f  
            rssi > -60 -> 0.12f  
            rssi > -80 -> 0.05f  
            else -> 0.02f        
        }

        val cameraPose = frame.camera.pose
        val arColor = com.google.ar.sceneform.rendering.Color(colorInt)

        MaterialFactory.makeOpaqueWithColor(this, arColor)
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(sphereRadius, Vector3.zero(), material)

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
                    
                    record.currentNode = anchorNode
                }
            }
    }

    private fun setMode(wifi: Boolean) {
        isWifiMode = wifi
        
        wifiRecords.values.forEach { record ->
            record.currentNode?.isEnabled = isWifiMode 
        }
        bluetoothRecords.values.forEach { record ->
            record.currentNode?.isEnabled = !isWifiMode 
        }

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
        uiHandler.removeCallbacks(hideUiRunnable)
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
            Toast.makeText(this, "Надайте дозволи!", Toast.LENGTH_LONG).show()
        }
    }
}