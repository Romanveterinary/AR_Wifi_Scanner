package com.vetai.wifiscanner

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import kotlin.math.abs
import kotlin.math.sqrt

data class SignalRecord(
    var currentNode: AnchorNode? = null,
    var lastRssi: Int = -100,
    var maxRssi: Int = -100,
    var isLockedAt100: Boolean = false 
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnWifi: Button
    private lateinit var btnBluetooth: Button
    private lateinit var btnExit: Button
    private lateinit var btnSettings: Button
    private lateinit var topControls: RelativeLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var btnCloseMenu: Button
    private lateinit var deviceListContainer: LinearLayout
    
    private lateinit var tvProgressText: TextView
    private lateinit var scanProgressBar: ProgressBar
    
    private var isWifiMode = true 
    private var targetMacAddress: String? = null

    private var scanProgress = 0
    private var lastRecordedPos: Vector3? = null

    private val rssiBuffers = mutableMapOf<String, MutableList<Int>>()

    private lateinit var wifiScanner: WifiSignalScanner
    private lateinit var bluetoothScanner: BluetoothSignalScanner
    
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment

    private val wifiRecords = mutableMapOf<String, SignalRecord>()
    private val bluetoothRecords = mutableMapOf<String, SignalRecord>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable {
        if (menuPanel.visibility != View.VISIBLE) {
            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
        }
    }

    // ЖОРСТКА ПАЛІТРА КОЛЬОРІВ (Щоб 100% відрізнялися)
    private val colorPalette = arrayOf(
        Color.RED,
        Color.GREEN,
        Color.BLUE,
        Color.YELLOW,
        Color.CYAN,
        Color.MAGENTA,
        Color.parseColor("#FF9800"), // Помаранчевий
        Color.parseColor("#9C27B0")  // Фіолетовий
    )

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
            menuPanel = findViewById(R.id.menu_panel)
            btnCloseMenu = findViewById(R.id.btn_close_menu)
            deviceListContainer = findViewById(R.id.device_list_container)
            
            tvProgressText = findViewById(R.id.tv_progress_text)
            scanProgressBar = findViewById(R.id.scan_progress_bar)
            
            arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment

            wifiScanner = WifiSignalScanner(this)
            bluetoothScanner = BluetoothSignalScanner(this)

            wifiScanner.onSignalFound = { mac, _, rssi, _ ->
                runOnUiThread { 
                    val uniqueColor = getColorForMac(mac)
                    updateSignalInAR(mac, rssi, uniqueColor, isWifiSignal = true) 
                }
            }

            bluetoothScanner.onSignalFound = { mac, _, rssi, _ ->
                runOnUiThread { 
                    val uniqueColor = getColorForMac(mac)
                    updateSignalInAR(mac, rssi, uniqueColor, isWifiSignal = false) 
                }
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

            btnExit.setOnClickListener { finish() }

            btnSettings.setOnClickListener {
                if (menuPanel.visibility == View.VISIBLE) {
                    menuPanel.visibility = View.GONE
                    resetUiTimer()
                } else {
                    openSettingsMenu()
                }
            }

            btnCloseMenu.setOnClickListener {
                menuPanel.visibility = View.GONE
                resetUiTimer()
            }

            uiHandler.postDelayed({
                try {
                    arFragment.arSceneView?.scene?.addOnUpdateListener { _ ->
                        trackUserMovementIndependent()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)

            if (checkPermissions()) {
                startCurrentScanner()
            } else {
                requestPermissions()
            }

            resetUiTimer()
            updateProgressUi()

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Критична помилка")
                .setMessage(e.stackTraceToString())
                .setPositiveButton("Закрити") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun getColorForMac(mac: String): Int {
        val index = abs(mac.hashCode()) % colorPalette.size
        return colorPalette[index]
    }

    private fun trackUserMovementIndependent() {
        if (targetMacAddress == null || scanProgress >= 100) return
        
        val frame = arFragment.arSceneView?.arFrame ?: return
        if (frame.camera.trackingState != com.google.ar.core.TrackingState.TRACKING) return
        
        val cameraPose = frame.camera.pose
        val currentCamPos = Vector3(cameraPose.translation[0], cameraPose.translation[1], cameraPose.translation[2])
        
        if (lastRecordedPos == null) {
            lastRecordedPos = currentCamPos
        } else {
            val dx = currentCamPos.x - lastRecordedPos!!.x
            val dy = currentCamPos.y - lastRecordedPos!!.y
            val dz = currentCamPos.z - lastRecordedPos!!.z
            val distanceWalked = sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
            
            if (distanceWalked > 0.6f) {
                scanProgress += 10
                lastRecordedPos = currentCamPos
                runOnUiThread { updateProgressUi() }
            }
        }
    }

    private fun getDeviceCustomName(mac: String): String {
        val prefs = getSharedPreferences("SavedDeviceNames", Context.MODE_PRIVATE)
        return prefs.getString(mac, mac) ?: mac
    }

    private fun saveDeviceCustomName(mac: String, newName: String) {
        val prefs = getSharedPreferences("SavedDeviceNames", Context.MODE_PRIVATE)
        prefs.edit().putString(mac, newName).apply()
    }

    private fun updateProgressUi() {
        scanProgressBar.progress = scanProgress
        if (targetMacAddress == null) {
            tvProgressText.text = "Режим радара (Всі сигнали)"
            scanProgressBar.progress = 0
        } else {
            when {
                scanProgress < 100 -> tvProgressText.text = "🚶 Збираю дані кімнати... Пройдено: $scanProgress%"
                else -> tvProgressText.text = "🎯 ЦІЛЬ ЛОКАЛІЗОВАНО! (Підійдіть до бульки)"
            }
        }
    }

    private fun openSettingsMenu() {
        uiHandler.removeCallbacks(hideUiRunnable)
        topControls.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
        menuPanel.visibility = View.VISIBLE
        
        deviceListContainer.removeAllViews()

        if (targetMacAddress != null) {
            val resetBtn = Button(this).apply {
                text = "❌ Скасувати режим пошуку (Показувати всі)"
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)
                
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 16)
                layoutParams = params

                setOnClickListener {
                    targetMacAddress = null
                    scanProgress = 0
                    lastRecordedPos = null
                    rssiBuffers.clear()
                    
                    wifiRecords.values.forEach { it.isLockedAt100 = false; it.maxRssi = -100 }
                    bluetoothRecords.values.forEach { it.isLockedAt100 = false; it.maxRssi = -100 }
                    
                    updateProgressUi()
                    openSettingsMenu()
                    Toast.makeText(this@MainActivity, "Режим радара", Toast.LENGTH_SHORT).show()
                }
            }
            deviceListContainer.addView(resetBtn)
        }

        addMenuHeader("🌐 Wi-Fi Пристрої:")
        if (wifiRecords.isEmpty()) addMenuEmptyText("Поки нічого не знайдено...")
        wifiRecords.forEach { (mac, record) -> addMenuItem(mac, record.lastRssi, true) }

        addMenuHeader("\n🔵 Bluetooth Пристрої:")
        if (bluetoothRecords.isEmpty()) addMenuEmptyText("Поки нічого не знайдено...")
        bluetoothRecords.forEach { (mac, record) -> addMenuItem(mac, record.lastRssi, false) }
    }

    private fun addMenuHeader(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }
        deviceListContainer.addView(tv)
    }

    private fun addMenuEmptyText(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(16, 0, 0, 8)
        }
        deviceListContainer.addView(tv)
    }

    private fun addMenuItem(mac: String, rssi: Int, isWifi: Boolean) {
        val displayName = getDeviceCustomName(mac)
        val isTarget = (mac == targetMacAddress)
        val uniqueColor = getColorForMac(mac)
        
        val button = Button(this).apply {
            text = if (isTarget) "🎯 $displayName (Ост: $rssi)" else "$displayName (Ост: $rssi)"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(uniqueColor) 
            setPadding(16, 16, 16, 16)
            
            if (isTarget) {
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
            }
            
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 8)
            layoutParams = params

            setOnClickListener { showActionDialog(mac, displayName) }
        }
        deviceListContainer.addView(button)
    }

    private fun showActionDialog(mac: String, currentName: String) {
        val options = arrayOf("🎯 Почати пошук цього пристрою", "✏️ Перейменувати")
        AlertDialog.Builder(this)
            .setTitle(currentName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startSniperMode(mac, currentName)
                    1 -> showRenameDialog(mac, currentName)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startSniperMode(mac: String, name: String) {
        targetMacAddress = mac
        scanProgress = 0
        lastRecordedPos = null
        rssiBuffers.clear()
        updateProgressUi()
        
        val allRecords = wifiRecords.values + bluetoothRecords.values
        allRecords.forEach { record ->
            record.currentNode?.let { node ->
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }
            record.currentNode = null
            record.maxRssi = -100 
            record.isLockedAt100 = false
        }
        
        menuPanel.visibility = View.GONE
        resetUiTimer()
        Toast.makeText(this, "Крокуйте по кімнаті для пошуку!", Toast.LENGTH_LONG).show()
    }

    private fun showRenameDialog(mac: String, currentName: String) {
        val input = EditText(this).apply {
            setText(if (currentName == mac) "" else currentName)
            hint = "Введіть нову назву"
        }

        AlertDialog.Builder(this)
            .setTitle("Перейменувати пристрій")
            .setMessage("MAC: $mac")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    saveDeviceCustomName(mac, newName)
                    openSettingsMenu()
                    Toast.makeText(this, "Збережено!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (menuPanel.visibility != View.VISIBLE) {
            if (topControls.visibility != View.VISIBLE) {
                topControls.visibility = View.VISIBLE
                bottomControls.visibility = View.VISIBLE
            }
            resetUiTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetUiTimer() {
        uiHandler.removeCallbacks(hideUiRunnable)
        uiHandler.postDelayed(hideUiRunnable, 5000)
    }

    private fun updateSignalInAR(mac: String, rssi: Int, androidColorInt: Int, isWifiSignal: Boolean) {
        if (isWifiSignal != isWifiMode) return

        val records = if (isWifiSignal) wifiRecords else bluetoothRecords
        val record = records[mac] ?: SignalRecord().also { records[mac] = it }
        record.lastRssi = rssi

        val buffer = rssiBuffers[mac] ?: mutableListOf<Int>().also { rssiBuffers[mac] = it }
        buffer.add(rssi)
        if (buffer.size > 3) buffer.removeAt(0)
        val smoothedRssi = buffer.average().toInt()

        val frame = arFragment.arSceneView?.arFrame ?: return
        val posArray = frame.camera.pose.translation
        val isTarget = (targetMacAddress == mac)

        if (targetMacAddress != null && !isTarget) return
        if (record.isLockedAt100) return

        if (smoothedRssi >= record.maxRssi || record.currentNode == null) {
            record.maxRssi = smoothedRssi

            record.currentNode?.let { node ->
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }

            val sphereRadius = when {
                smoothedRssi > -40 -> 0.25f  
                smoothedRssi > -60 -> 0.12f  
                smoothedRssi > -80 -> 0.05f  
                else -> 0.02f        
            }

            val cameraPose = frame.camera.pose
            val deviceName = getDeviceCustomName(mac)
            
            val r = Color.red(androidColorInt) / 255f
            val g = Color.green(androidColorInt) / 255f
            val b = Color.blue(androidColorInt) / 255f
            val arColor = com.google.ar.sceneform.rendering.Color(r, g, b)

            MaterialFactory.makeOpaqueWithColor(this, arColor)
                .thenAccept { material ->
                    ViewRenderable.builder()
                        .setView(this@MainActivity, R.layout.ar_label)
                        .build()
                        .thenAccept { viewRenderable ->
                            
                            val textView = viewRenderable.view.findViewById<TextView>(R.id.tv_ar_label)
                            
                            // ПОВЕРНУЛИ СТАРУ СТАБІЛЬНУ ЛОГІКУ: Тільки текст над булькою
                            if (isTarget && scanProgress >= 100) {
                                textView.text = "🎯 ЦІЛЬ ТУТ\n($deviceName)"
                                textView.setTextColor(Color.YELLOW)
                                record.isLockedAt100 = true 
                            } else {
                                textView.text = "$deviceName\nПік: $smoothedRssi"
                            }

                            val sphere = ShapeFactory.makeSphere(sphereRadius, Vector3.zero(), material)
                            val forward = floatArrayOf(0f, 0f, -0.5f)
                            val transformed = FloatArray(3)
                            cameraPose.rotateVector(forward, 0, transformed, 0)
                            
                            val anchorPose = Pose.makeTranslation(
                                posArray[0] + transformed[0], 
                                posArray[1] + transformed[1], 
                                posArray[2] + transformed[2]
                            )

                            val anchor = arFragment.arSceneView?.session?.createAnchor(anchorPose)
                            anchor?.let {
                                val anchorNode = AnchorNode(it)
                                anchorNode.setParent(arFragment.arSceneView?.scene)

                                val sphereNode = Node()
                                sphereNode.setParent(anchorNode)
                                sphereNode.renderable = sphere

                                val labelNode = Node()
                                labelNode.setParent(anchorNode)
                                labelNode.renderable = viewRenderable
                                labelNode.localPosition = Vector3(0f, sphereRadius + 0.15f, 0f)

                                record.currentNode = anchorNode
                            }
                        }
                }
        }
    }

    private fun setMode(wifi: Boolean) {
        isWifiMode = wifi
        targetMacAddress = null 
        scanProgress = 0
        lastRecordedPos = null
        rssiBuffers.clear()
        updateProgressUi()
        
        val inactiveRecords = if (wifi) bluetoothRecords else wifiRecords
        inactiveRecords.values.forEach { record ->
            record.currentNode?.let { node ->
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }
            record.currentNode = null
            record.isLockedAt100 = false
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