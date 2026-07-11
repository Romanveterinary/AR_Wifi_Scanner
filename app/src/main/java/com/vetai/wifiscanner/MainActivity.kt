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

data class MainSignalRecord(
    var currentNode: AnchorNode? = null,
    var labelView: ViewRenderable? = null, 
    var lastRssi: Int = -100,
    var maxRssi: Int = -100,
    var isLockedAt100: Boolean = false 
)

class MainActivity : AppCompatActivity() {

    private lateinit var btnExit: Button
    private lateinit var btnSettings: Button
    private lateinit var topControls: RelativeLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var btnCloseMenu: Button
    private lateinit var deviceListContainer: LinearLayout
    
    private lateinit var tvProgressText: TextView
    private lateinit var scanProgressBar: ProgressBar

    // Нові змінні для фільтрації
    private lateinit var tvFilterLabel: TextView
    private lateinit var sbRssiFilter: SeekBar
    private var currentRssiThreshold = -100
    
    private var targetMacAddress: String? = null
    private var scanProgress = 0
    private var lastRecordedPos = null

    private val rssiBuffers = mutableMapOf<String, MutableList<Int>>()
    private lateinit var bluetoothScanner: BluetoothSignalScanner
    private val PERMISSION_REQUEST_CODE = 123
    private lateinit var arFragment: ArFragment
    private val bluetoothRecords = mutableMapOf<String, MainSignalRecord>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable {
        if (menuPanel.visibility != View.VISIBLE) {
            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
        }
    }

    private val colorPalette = arrayOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, Color.parseColor("#FF9800"), Color.parseColor("#9C27B0")  
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_main)

            btnExit = findViewById(R.id.btn_exit)
            btnSettings = findViewById(R.id.btn_settings)
            topControls = findViewById(R.id.top_controls)
            bottomControls = findViewById(R.id.bottom_controls)
            menuPanel = findViewById(R.id.menu_panel)
            btnCloseMenu = findViewById(R.id.btn_close_menu)
            deviceListContainer = findViewById(R.id.device_list_container)
            
            tvProgressText = findViewById(R.id.tv_progress_text)
            scanProgressBar = findViewById(R.id.scan_progress_bar)

            // Ініціалізація елементів фільтра
            tvFilterLabel = findViewById(R.id.tv_filter_label)
            sbRssiFilter = findViewById(R.id.sb_rssi_filter)

            // Завантаження збереженого порогу чутливості
            val prefs = getSharedPreferences("SavedDeviceNames", Context.MODE_PRIVATE)
            currentRssiThreshold = prefs.getInt("rssi_threshold", -100)
            sbRssiFilter.progress = currentRssiThreshold + 100
            updateFilterLabelText()

            sbRssiFilter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentRssiThreshold = progress - 100
                    updateFilterLabelText()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putInt("rssi_threshold", currentRssiThreshold).apply()
                }
            })
            
            arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as ArFragment
            bluetoothScanner = BluetoothSignalScanner(this)

            bluetoothScanner.onSignalFound = { mac, _, rssi, _ ->
                // ГОЛОВНИЙ ФІЛЬТР: ігноруємо пристрої зі слабким сигналом
                if (rssi >= currentRssiThreshold) {
                    runOnUiThread { 
                        val uniqueColor = getColorForMac(mac)
                        updateSignalInAR(mac, rssi, uniqueColor) 
                    }
                }
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
                    arFragment.arSceneView?.planeRenderer?.isVisible = false
                    arFragment.arSceneView?.scene?.addOnUpdateListener { _ ->
                        trackUserMovementIndependent()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)

            if (checkPermissions()) {
                bluetoothScanner.startScanning(targetMacAddress)
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

    private fun updateFilterLabelText() {
        if (currentRssiThreshold == -100) {
            tvFilterLabel.text = "Фільтр чутливості: -100 dBm (Всі пристрої)"
        } else {
            tvFilterLabel.text = "Фільтр чутливості: $currentRssiThreshold dBm (Ближні пристрої)"
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
            val dx = currentCamPos.x - (lastRecordedPos as Vector3).x
            val dy = currentCamPos.y - (lastRecordedPos as Vector3).y
            val dz = currentCamPos.z - (lastRecordedPos as Vector3).z
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
                scanProgress < 100 -> tvProgressText.text = "🚶 Збираю дані простору... Пройдено: $scanProgress%"
                else -> tvProgressText.text = "🎯 ЦІЛЬ ЛОКАЛІЗОВАНО!"
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
                text = "❌ Скасувати режим пошуку"
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
                    
                    bluetoothRecords.values.forEach { 
                        it.isLockedAt100 = false
                        it.maxRssi = -100 
                    }
                    updateProgressUi()
                    
                    bluetoothScanner.startScanning(null)
                    openSettingsMenu()
                }
            }
            deviceListContainer.addView(resetBtn)
        }

        val tv = TextView(this).apply {
            text = "🔵 Bluetooth Пристрої:"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }
        deviceListContainer.addView(tv)

        if (bluetoothRecords.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Поки нічого не знайдено..."
                setTextColor(Color.GRAY)
                textSize = 14f
                setPadding(16, 0, 0, 8)
            }
            deviceListContainer.addView(emptyTv)
        }

        bluetoothRecords.forEach { (mac, record) -> 
            val displayName = getDeviceCustomName(mac)
            val isTarget = (mac == targetMacAddress)
            val uniqueColor = getColorForMac(mac)
            
            val button = Button(this).apply {
                text = if (isTarget) "🎯 $displayName (Ост: ${record.lastRssi})" else "$displayName (Ост: ${record.lastRssi})"
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
    }

    private fun showActionDialog(mac: String, currentName: String) {
        val options = arrayOf("🎯 Почати пошук", "✏️ Перейменувати")
        AlertDialog.Builder(this)
            .setTitle(currentName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startSniperMode(mac)
                    1 -> showRenameDialog(mac, currentName)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startSniperMode(mac: String) {
        targetMacAddress = mac
        scanProgress = 0
        lastRecordedPos = null
        rssiBuffers.clear()
        updateProgressUi()
        
        val targetRecord = bluetoothRecords[mac]
        bluetoothRecords.values.forEach { record ->
            record.currentNode?.let { node ->
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }
        }
        bluetoothRecords.clear()
        
        if (targetRecord != null) {
            targetRecord.currentNode = null
            targetRecord.maxRssi = -100 
            targetRecord.isLockedAt100 = false
            bluetoothRecords[mac] = targetRecord
        }
        
        bluetoothScanner.startScanning(mac)
        
        menuPanel.visibility = View.GONE
        resetUiTimer()
        Toast.makeText(this, "Снайперський режим! Шукаємо абсолютний максимум.", Toast.LENGTH_LONG).show()
    }

    private fun showRenameDialog(mac: String, currentName: String) {
        val input = EditText(this).apply {
            setText(if (currentName == mac) "" else currentName)
            hint = "Нова назва"
        }

        AlertDialog.Builder(this)
            .setTitle("Перейменувати")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    saveDeviceCustomName(mac, newName)
                    openSettingsMenu()
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideUiRunnable)
    }

    private fun updateSignalInAR(mac: String, rssi: Int, androidColorInt: Int) {
        val record = bluetoothRecords[mac] ?: MainSignalRecord().also { bluetoothRecords[mac] = it }
        record.lastRssi = rssi

        val buffer = rssiBuffers[mac] ?: mutableListOf<Int>().also { rssiBuffers[mac] = it }
        buffer.add(rssi)
        if (buffer.size > 3) buffer.removeAt(0)
        val smoothedRssi = buffer.average().toInt()

        val frame = arFragment.arSceneView?.arFrame ?: return
        val posArray = frame.camera.pose.translation
        val isTarget = (targetMacAddress == mac)
        val deviceName = getDeviceCustomName(mac)

        if (targetMacAddress != null && !isTarget) return
        if (record.isLockedAt100) return

        if (smoothedRssi > record.maxRssi || record.currentNode == null) {
            record.maxRssi = smoothedRssi

            record.currentNode?.let { node ->
                node.renderable = null
                node.anchor?.detach()
                node.setParent(null)
            }

            val baseSize = when {
                smoothedRssi > -40 -> 0.15f  
                smoothedRssi > -60 -> 0.08f  
                smoothedRssi > -80 -> 0.04f  
                else -> 0.02f            
            }

            val cameraPose = frame.camera.pose
            
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
                            
                            record.labelView = viewRenderable 
                            val textView = viewRenderable.view.findViewById<TextView>(R.id.tv_ar_label)
                            
                            if (isTarget && scanProgress >= 100) {
                                textView.text = "🎯 ЦІЛЬ ТУТ\n($deviceName)\nМаксимум: $smoothedRssi"
                                textView.setTextColor(Color.YELLOW)
                                record.isLockedAt100 = true 
                            } else {
                                textView.text = "$deviceName\nМаксимум: $smoothedRssi"
                            }

                            val shapeNode = Node()
                            shapeNode.renderable = ShapeFactory.makeSphere(baseSize, Vector3.zero(), material)

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

                                shapeNode.setParent(anchorNode)

                                val labelNode = Node()
                                labelNode.setParent(anchorNode)
                                labelNode.renderable = viewRenderable
                                labelNode.localScale = Vector3(0.6f, 0.6f, 0.6f)
                                labelNode.localPosition = Vector3(0f, baseSize + 0.10f, 0f)

                                record.currentNode = anchorNode
                            }
                        }
                }
        } else {
            record.labelView?.view?.findViewById<TextView>(R.id.tv_ar_label)?.apply {
                if (isTarget && scanProgress >= 100) {
                    text = "🎯 ЦІЛЬ ТУТ\n($deviceName)\nМакс: ${record.maxRssi} | Зараз: $smoothedRssi"
                } else {
                    text = "$deviceName\nМакс: ${record.maxRssi} | Зараз: $smoothedRssi"
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(hideUiRunnable)
        bluetoothScanner.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            bluetoothScanner.startScanning(targetMacAddress)
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
            bluetoothScanner.startScanning(targetMacAddress)
        } else {
            Toast.makeText(this, "Надайте дозволи!", Toast.LENGTH_LONG).show()
        }
    }
}
