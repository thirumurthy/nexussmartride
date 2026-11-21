package com.thirutricks.nexussmartride

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.lifecycle.lifecycleScope
import com.thirutricks.nexussmartride.ai.AiRideState
import com.thirutricks.nexussmartride.ai.SmartAiEngine
import com.thirutricks.nexussmartride.data.ScooterDatabase
import com.thirutricks.nexussmartride.data.ScooterRepository
import com.thirutricks.nexussmartride.databinding.ActivityMainBinding
import com.thirutricks.nexussmartride.sync.NexusCloudSyncManager
import com.thirutricks.nexussmartride.sync.ServerInsights
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main dashboard for Ampere Nexus telemetry.
 * Binds to BleService via LocalBroadcastManager.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScooterRepository
    private val prefs by lazy { getSharedPreferences("nexus_prefs", MODE_PRIVATE) }

    // Saved device (so we auto-connect on next launch)
    private var savedAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) = prefs.edit().putString("device_address", v).apply()

    private var savedName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    private var isConnected = false
    private var lastUpdateTime = 0L
    private var packetCount = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // UI blink animation for connection indicator
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkState = false

    companion object {
        const val MODE_OFF = "off"
        const val MODE_TBT = "tbt"
        const val MODE_MIRROR = "mirror"
    }

    private var currentNavMode: String
        get() = prefs.getString("nav_mode", MODE_OFF) ?: MODE_OFF
        set(v) = prefs.edit().putString("nav_mode", v).apply()

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenMirroringService::class.java).apply {
                action = ScreenMirroringService.CMD_START
                putExtra(ScreenMirroringService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirroringService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            binding.tvNavModeBadge.text = "OPTION B (WI-FI)"
            binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.tvNavInstruction.text = "Wi-Fi Hotspot active. Mount phone in landscape for 800x360 cluster display."
        } else {
            Toast.makeText(this, "Screen capture permission required for cluster mirroring", Toast.LENGTH_SHORT).show()
            selectNavMode(MODE_OFF)
            binding.toggleNavMode.check(R.id.btnModeOff)
        }
    }

    private val navUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MapNotificationListenerService.ACTION_NAV_UPDATE -> {
                    val isNavigating = intent.getBooleanExtra(MapNotificationListenerService.EXTRA_IS_NAVIGATING, false)
                    if (isNavigating && currentNavMode == MODE_TBT) {
                        val road = intent.getStringExtra(MapNotificationListenerService.EXTRA_ROAD_NAME) ?: ""
                        val dist = intent.getStringExtra(MapNotificationListenerService.EXTRA_DISTANCE_TEXT) ?: ""
                        val rem = intent.getStringExtra(MapNotificationListenerService.EXTRA_REMAINING_TEXT) ?: ""
                        val maneuver = intent.getIntExtra(MapNotificationListenerService.EXTRA_MANEUVER, 0)
                        val mText = when (maneuver) {
                            MapNotificationListenerService.MANEUVER_RIGHT -> "Turn Right"
                            MapNotificationListenerService.MANEUVER_LEFT -> "Turn Left"
                            MapNotificationListenerService.MANEUVER_SLIGHT_RIGHT -> "Slight Right"
                            MapNotificationListenerService.MANEUVER_SLIGHT_LEFT -> "Slight Left"
                            MapNotificationListenerService.MANEUVER_UTURN -> "U-Turn"
                            MapNotificationListenerService.MANEUVER_ROUNDABOUT -> "Roundabout"
                            else -> "Straight"
                        }
                        binding.tvNavDetail.visibility = View.VISIBLE
                        binding.tvNavDetail.text = "[$mText] in $dist — $road ($rem)"
                    } else if (currentNavMode == MODE_TBT) {
                        binding.tvNavDetail.visibility = View.GONE
                    }
                }
                ScreenMirroringService.ACTION_MIRROR_STATUS -> {
                    val isMirroring = intent.getBooleanExtra(ScreenMirroringService.EXTRA_IS_MIRRORING, false)
                    val ssid = intent.getStringExtra(ScreenMirroringService.EXTRA_HOTSPOT_SSID)
                    if (isMirroring && currentNavMode == MODE_MIRROR) {
                        binding.tvNavDetail.visibility = View.VISIBLE
                        binding.tvNavDetail.text = "Hotspot: ${ssid ?: "Active"} • Port 1880 streaming"
                    } else if (currentNavMode == MODE_MIRROR) {
                        binding.tvNavDetail.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = ScooterDatabase.getDatabase(this)
        repository = ScooterRepository.getInstance(db.scooterDao())

        // Keep screen on while connected
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUi()
        registerReceivers()
        observeData()
        checkPermissionsAndStart()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.currentData.collectLatest { data ->
                updateUi(data)
            }
        }

        // Live Cloud Telemetry Insights (Fleet savings, service countdown, sync status)
        lifecycleScope.launch {
            NexusCloudSyncManager.getInstance(this@MainActivity).insights.collectLatest { ins ->
                updateCloudInsightsUi(ins)
            }
        }

        // Live Edge AI Telemetry State (Dynamic range, eco score, coach tips)
        lifecycleScope.launch {
            SmartAiEngine.aiState.collectLatest { ai ->
                updateAiStateUi(ai)
            }
        }
        
        // Periodic travel stats refresh
        lifecycleScope.launch {
            while (true) {
                refreshTravelStats()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Show stale indicator if data is old
        updateStaleIndicator()

        // Fetch latest server insights if a scooter is paired
        savedAddress?.let { addr ->
            lifecycleScope.launch {
                NexusCloudSyncManager.getInstance(this@MainActivity).fetchInsights(addr)
            }
        }

        // When Notification Access permission is granted, rebind and sync music immediately!
        if (isNotificationListenerEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        ComponentName(this, MapNotificationListenerService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "requestRebind error: ${e.message}")
                }
            }
            MapNotificationListenerService.triggerActiveMediaSync()
        }

        // Re-check notification permission if in TBT mode
        if (currentNavMode == MODE_TBT) {
            if (isNotificationListenerEnabled()) {
                binding.tvNavInstruction.text = "Option A active. Start navigation in Google Maps; turn cues will stream over BLE automatically."
                binding.btnNavAction.visibility = View.GONE
            } else {
                binding.tvNavInstruction.text = "Notification access required to read Google Maps directions & sync music."
                binding.btnNavAction.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        blinkHandler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(navUpdateReceiver)
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUi() {
        // Scan / Connect button
        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                disconnectScooter()
            } else {
                scanForScooter()
            }
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            showSyncSettingsDialog()
        }

        // Forget saved device
        binding.btnForget.setOnClickListener {
            savedAddress = null
            savedName = null
            disconnectScooter()
            setUiDisconnected()
            binding.tvDeviceName.text = "No device saved"
            Toast.makeText(this, "Device forgotten", Toast.LENGTH_SHORT).show()
        }

        // Show saved device name
        binding.tvDeviceName.text = savedName ?: "No device paired"
        setUiDisconnected()

        // Tap Last Update to manually trigger multi-address time sync and media sync
        binding.tvLastUpdate.setOnClickListener {
            if (isConnected) {
                Toast.makeText(this, "Triggering time & media sync…", Toast.LENGTH_SHORT).show()
                BleService.instance?.triggerManualSync()
                MapNotificationListenerService.triggerActiveMediaSync()
            } else {
                Toast.makeText(this, "Connect to scooter first to sync time", Toast.LENGTH_SHORT).show()
            }
        }

        // Manual Cloud Sync button
        binding.btnCloudSyncNow.setOnClickListener {
            val syncManager = NexusCloudSyncManager.getInstance(this)
            binding.btnCloudSyncNow.isEnabled = false
            binding.btnCloudSyncNow.text = "SYNCING WITH CLOUD..."
            syncManager.triggerManualSync(savedAddress, repository.currentData.value) { _, msg ->
                binding.btnCloudSyncNow.isEnabled = true
                binding.btnCloudSyncNow.text = "SYNC WITH CLOUD SERVER"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        setupNavModeUi()
    }

    private fun setupNavModeUi() {
        binding.toggleNavMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeOff -> selectNavMode(MODE_OFF)
                R.id.btnModeTbt -> selectNavMode(MODE_TBT)
                R.id.btnModeMirror -> selectNavMode(MODE_MIRROR)
            }
        }

        binding.btnNavAction.setOnClickListener {
            if (currentNavMode == MODE_TBT) {
                showRestrictedSettingsGuideDialog()
            }
        }

        // Initialize state from saved preference
        when (currentNavMode) {
            MODE_TBT -> binding.toggleNavMode.check(R.id.btnModeTbt)
            MODE_MIRROR -> binding.toggleNavMode.check(R.id.btnModeMirror)
            else -> binding.toggleNavMode.check(R.id.btnModeOff)
        }
    }

    private fun selectNavMode(mode: String) {
        currentNavMode = mode
        when (mode) {
            MODE_OFF -> {
                stopScreenMirroring()
                // Clear cluster navigation
                BleService.instance?.sendNavigationToCluster(
                    maneuverDirection = MapNotificationListenerService.MANEUVER_CLEAR,
                    turnDistanceMeters = 0,
                    totalDistanceMeters = 0,
                    roadName = "",
                    isGpsFixed = false
                )
                binding.tvNavModeBadge.text = "OFF"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvNavInstruction.text = "Select an option above to stream Google Maps"
                binding.tvNavDetail.visibility = View.GONE
                binding.btnNavAction.visibility = View.GONE
            }
            MODE_TBT -> {
                stopScreenMirroring()
                binding.tvNavModeBadge.text = "OPTION A (BLE)"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))

                if (!isNotificationListenerEnabled()) {
                    binding.tvNavInstruction.text = "Notification access required to read Google Maps directions."
                    binding.btnNavAction.visibility = View.VISIBLE
                    binding.btnNavAction.text = "UNLOCK RESTRICTED SETTINGS & GRANT ACCESS"
                    showRestrictedSettingsGuideDialog()
                } else {
                    binding.tvNavInstruction.text = "Option A active. Start navigation in Google Maps; turn cues will stream over BLE automatically."
                    binding.btnNavAction.visibility = View.GONE
                }
            }
            MODE_MIRROR -> {
                binding.tvNavModeBadge.text = "OPTION B (WI-FI)"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
                binding.tvNavInstruction.text = "Requesting screen capture permission…"
                binding.btnNavAction.visibility = View.GONE
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun showRestrictedSettingsGuideDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Unlock Notification Access")
            .setMessage(
                "Because this app is sideloaded, Android 13/14/15 restricts Notification Access by default.\n\n" +
                "Follow these 2 quick steps to unlock it:\n\n" +
                "Step 1: Tap '1. Go to App Info' below. Tap the 3 dots (⋮) in the top-right corner, select 'Allow restricted settings', and enter your PIN / fingerprint.\n\n" +
                "Step 2: Return here and tap '2. Grant Notification Access' to turn on the toggle."
            )
            .setPositiveButton("1. Go to App Info") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNeutralButton("2. Grant Access") { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, MapNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun stopScreenMirroring() {
        val intent = Intent(this, ScreenMirroringService::class.java).apply {
            action = ScreenMirroringService.CMD_STOP
        }
        startService(intent)
    }

    private fun showSyncSettingsDialog() {
        val menuOptions = arrayOf(
            "Cluster Time Sync Method",
            "Nexus Cloud Server & API Settings",
            "Force Cloud Telemetry Sync"
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Sync & Telemetry Settings")
            .setItems(menuOptions) { _, which ->
                when (which) {
                    0 -> showClusterTimeSyncDialog()
                    1 -> showCloudServerDialog()
                    2 -> {
                        Toast.makeText(this, "Syncing telemetry with cloud...", Toast.LENGTH_SHORT).show()
                        NexusCloudSyncManager.getInstance(this).triggerManualSync(savedAddress, repository.currentData.value) { _, msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showClusterTimeSyncDialog() {
        val options = arrayOf("Both (Recommended)", "Unix Block Only", "Calendar Block Only")
        val currentMethod = prefs.getInt("sync_method", 0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Cluster Time Sync Method")
            .setSingleChoiceItems(options, currentMethod) { dialog, which ->
                prefs.edit().putInt("sync_method", which).apply()
                Toast.makeText(this, "Sync method: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (isConnected) {
                    BleService.instance?.triggerManualSync()
                }
            }
            .setPositiveButton("Sync Now") { _, _ ->
                if (isConnected) {
                    Toast.makeText(this, "Syncing time across candidate characteristics…", Toast.LENGTH_SHORT).show()
                    BleService.instance?.triggerManualSync()
                    MapNotificationListenerService.triggerActiveMediaSync()
                } else {
                    Toast.makeText(this, "Connect to scooter first to sync time", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCloudServerDialog() {
        val cloudManager = NexusCloudSyncManager.getInstance(this)
        val serverUrl = cloudManager.getServerUrl()
        val ins = cloudManager.insights.value

        val status = when (ins.syncStatus) {
            "SUCCESS" -> "Connected & Active"
            "SYNCING" -> "Synchronizing..."
            "QUEUED"  -> "${ins.pendingQueueCount} items queued offline"
            else      -> "Ready"
        }

        val message = "Server Endpoint:\n$serverUrl\n\n" +
                "Device Identifier:\n${savedAddress ?: "No paired scooter (Default ID used)"}\n\n" +
                "Cloud Status: $status\n" +
                "Last Synced: ${if (ins.lastSyncTimeMs > 0) timeFormat.format(Date(ins.lastSyncTimeMs)) else "Never"}\n\n" +
                "Fleet Petrol Saved: %.1f L\n".format(ins.petrolSavedLiters) +
                "Fleet Money Saved: ₹${ins.moneySavedInr}\n" +
                "CO2 Offset: %.1f kg".format(ins.co2SavedKg)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Nexus Cloud Server")
            .setMessage(message)
            .setPositiveButton("Sync Now") { _, _ ->
                cloudManager.triggerManualSync(savedAddress, repository.currentData.value) { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ─── BLE Broadcast Receiver ───────────────────────────────────────────────

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.ACTION_CONNECTED    -> onConnected()
                BleService.ACTION_DISCONNECTED -> onDisconnected()
                BleService.ACTION_SCAN_RESULT  -> onScanResult(intent)
                BleService.ACTION_SCAN_FAILED  -> onScanFailed()
                BleService.ACTION_DATA         -> onDataReceived(intent)
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_CONNECTED)
            addAction(BleService.ACTION_DISCONNECTED)
            addAction(BleService.ACTION_SCAN_RESULT)
            addAction(BleService.ACTION_SCAN_FAILED)
            addAction(BleService.ACTION_DATA)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter)

        val navFilter = IntentFilter().apply {
            addAction(MapNotificationListenerService.ACTION_NAV_UPDATE)
            addAction(ScreenMirroringService.ACTION_MIRROR_STATUS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(navUpdateReceiver, navFilter)
    }

    // ─── Event handlers ───────────────────────────────────────────────────────

    private fun onConnected() {
        isConnected = true
        runOnUiThread {
            setUiConnected()
        }
    }

    private fun onDisconnected() {
        isConnected = false
        packetCount = 0
        runOnUiThread { setUiDisconnected() }
    }

    private fun onScanResult(intent: Intent) {
        val address = intent.getStringExtra(BleService.EXTRA_DEVICE_ADDRESS) ?: return
        val name    = intent.getStringExtra(BleService.EXTRA_DEVICE_NAME) ?: "NEX_?"

        savedAddress = address
        savedName    = name

        runOnUiThread {
            binding.tvDeviceName.text = "$name found — connecting…"
            binding.tvStatus.text = "Found $name"
        }

        // Auto-connect to found device
        connectToAddress(address)
    }

    private fun onScanFailed() {
        runOnUiThread {
            binding.tvStatus.text = "Scan failed. Is Bluetooth on?"
            binding.btnConnect.isEnabled = true
            binding.btnConnect.text = "Scan & Connect"
        }
    }

    private fun onDataReceived(intent: Intent) {
        // Redundant with Repository observer but kept for broadcast compatibility
        // The Repository observer (observeData) is now the primary driver
    }

    @SuppressLint("SetTextI18n")
    private fun updateUi(data: ScooterData) {
        if (!data.isValid) return

        lastUpdateTime = System.currentTimeMillis()
        packetCount++

        runOnUiThread {
            // Speed — big central display
            val spd = if (data.speedKmh >= 0) data.speedKmh else 0
            binding.tvSpeed.text = spd.toString()
            if (data.speedKmh >= 0) {
                binding.speedGraph.addSample(data.speedKmh)
            }

            // Odometer display (from field 51)
            if (data.odometerKm >= 0.0) {
                binding.tvVoltage.text = "%.1f km".format(data.odometerKm)
            } else if (data.voltageV >= 0.0) {
                binding.tvVoltage.text = "%.1fV".format(data.voltageV)
            }

            // Trip distance (from field 52)
            if (data.tripKm >= 0.0) {
                binding.tvTodayKm.text = "%.1f km".format(data.tripKm)
            }

            // State of Charge
            if (data.socPercent >= 0) {
                binding.tvSoc.text = "${data.socPercent}%"
                binding.progressSoc.progress = data.socPercent
            }
            
            // Remaining Range (DTE)
            if (data.remainingKm >= 0) {
                binding.tvRange.text = "${data.remainingKm} km"
            }

            // Riding Mode / Status (Park, ECO, City, Power)
            binding.tvStatus.text = data.statusText.uppercase()
            
            // Last updated time
            binding.tvLastUpdate.text = "Live Sync: ${timeFormat.format(Date())}"
        }
    }

    private suspend fun refreshTravelStats() {
        val today = repository.getTodayDistance()
        val yesterday = repository.getYesterdayDistance()
        runOnUiThread {
            binding.tvTodayKm.text = "%.1f km".format(today)
            binding.tvYesterdayKm.text = "%.1f km".format(yesterday)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCloudInsightsUi(ins: ServerInsights) {
        runOnUiThread {
            // Cloud Sync Badge in Header
            when (ins.syncStatus) {
                "SYNCING" -> {
                    binding.tvCloudSyncBadge.text = "☁ SYNCING..."
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                }
                "QUEUED" -> {
                    val count = if (ins.pendingQueueCount > 0) "${ins.pendingQueueCount} " else ""
                    binding.tvCloudSyncBadge.text = "☁ ${count}QUEUED"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
                }
                "SUCCESS" -> {
                    binding.tvCloudSyncBadge.text = "☁ CLOUD OK"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                }
                "ERROR" -> {
                    binding.tvCloudSyncBadge.text = "☁ SYNC RETRY"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                }
                else -> {
                    binding.tvCloudSyncBadge.text = "☁ CLOUD IDLE"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }

            // Fleet Impact & Cloud Savings
            binding.tvCloudMoneySaved.text = "₹${ins.moneySavedInr}"
            binding.tvCloudPetrolSaved.text = "%.1f L".format(ins.petrolSavedLiters)
            binding.tvCloudCo2Saved.text = "%.1f kg".format(ins.co2SavedKg)
            binding.tvCloudNextService.text = if (ins.kmUntilService > 0) "%.0f km".format(ins.kmUntilService) else "Due Now"

            if (ins.lastSyncTimeMs > 0) {
                val diffMin = (System.currentTimeMillis() - ins.lastSyncTimeMs) / 60000
                binding.tvCloudSyncTime.text = if (diffMin <= 0) "Synced just now" else "Synced ${diffMin}m ago"
            } else {
                binding.tvCloudSyncTime.text = "Not synced"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateAiStateUi(ai: AiRideState) {
        runOnUiThread {
            binding.tvAiRange.text = "${ai.aiPredictedRangeKm} km"
            binding.tvAiRangeSub.text = "${ai.estimatedKmPerSoc} km/% • ${ai.rangeConfidence}"
            binding.tvAiEcoScore.text = "${ai.ecoScore}/100"
            binding.tvAiEcoRating.text = ai.ecoRating
            binding.tvAiCoachTip.text = ai.coachTip

            when {
                ai.ecoScore >= 90 -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                ai.ecoScore >= 75 -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                else -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            }
        }
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private fun setUiConnected() {
        binding.tvStatus.text = "CONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        binding.btnConnect.text = "STOP"
    }

    private fun setUiDisconnected() {
        binding.tvStatus.text = "DISCONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.btnConnect.text = "SEARCH"
        binding.tvSpeed.text = "0"
        binding.tvRange.text = "0 km"
        binding.tvLastUpdate.text = "Not Synced"
    }

    private fun updateStaleIndicator() {
        if (lastUpdateTime > 0) {
            val age = System.currentTimeMillis() - lastUpdateTime
            if (age > 15_000) {
                binding.tvLastUpdate.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            } else {
                binding.tvLastUpdate.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun setUiScanning() {
        binding.tvStatus.text = "SCANNIG..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        binding.btnConnect.text = "SCAN..."
    }

    private fun setUiConnecting() {
        binding.tvStatus.text = "CONNECTING..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        binding.btnConnect.text = "CONNECT..."
    }

    // ─── BLE Operations ───────────────────────────────────────────────────────

    private fun scanForScooter() {
        setUiScanning()
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_SCAN
        }
        startBleService(intent)
    }

    private fun connectToAddress(address: String) {
        setUiConnecting()
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_CONNECT
            putExtra(BleService.EXTRA_ADDRESS, address)
        }
        startBleService(intent)
    }

    private fun disconnectScooter() {
        isConnected = false
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.CMD_DISCONNECT
        }
        startBleService(intent)
        setUiDisconnected()
    }

    private fun startBleService(intent: Intent) {
        ContextCompat.startForegroundService(this, intent)
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_CONNECT] == true && results[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            results[Manifest.permission.BLUETOOTH] != false && results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }

        if (btGranted) {
            startAutoConnect()
            if (!isNotificationListenerEnabled()) {
                showRestrictedSettingsGuideDialog()
            }
        } else {
            Toast.makeText(
                this,
                "Bluetooth permissions required for scooter connectivity",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Telephony & contacts permissions for caller name and handlebar call controls
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        val missing = required.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAutoConnect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAutoConnect() {
        // Check Bluetooth is on
        val btManager = getSystemService(BluetoothManager::class.java)
        if (btManager?.adapter?.isEnabled != true) {
            binding.tvStatus.text = "Bluetooth is off"
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // Auto-connect to last known device
        val address = savedAddress
        if (address != null) {
            binding.tvDeviceName.text = savedName ?: address
            binding.tvStatus.text = "Auto-connecting…"
            connectToAddress(address)
        } else {
            binding.tvStatus.text = "Tap to scan for scooter"
        }
    }
}
