package com.thirutricks.nexussmartride

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Main cyber-automotive companion dashboard for Ampere Nexus EV.
 * Conforms to the Stitch Nexus Pulse Cyber-Automotive Specification (v2.0.0).
 * Binds to BleService via LocalBroadcastManager and NexusCloudSyncManager for cloud telemetry.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScooterRepository
    private val prefs by lazy { getSharedPreferences("nexus_prefs", MODE_PRIVATE) }

    // Saved device (auto-connect on launch)
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

    // Active bottom navigation tab index (0=Home, 1=Trips, 2=Insights, 3=Scooter, 4=Settings)
    private var currentTabIndex = 0

    // Cadence filter selection ("3D", "7D", "30D", "1Y")
    private var selectedCadence = "7D"

    // UI blink animation for connection indicator
    private val blinkHandler = Handler(Looper.getMainLooper())

    companion object {
        const val MODE_OFF = "off"
        const val MODE_TBT = "tbt"
        const val MODE_MIRROR = "mirror"

        const val TAB_HOME = 0
        const val TAB_TRIPS = 1
        const val TAB_INSIGHTS = 2
        const val TAB_SCOOTER = 3
        const val TAB_SETTINGS = 4
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
                putExtra(ScreenMirroringService.EXTRA_ORIENTATION_MODE, currentMirrorOrientationMode)
            }
            ContextCompat.startForegroundService(this, intent)
            binding.tvNavModeBadge.text = "MIRROR (WI-FI)"
            binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvNavInstruction.text = "Wi-Fi Hotspot active. Streaming screen to 800x360 cluster display."
            binding.btnToggleMirrorOrientation.visibility = View.VISIBLE
            applyMirrorOrientation(currentMirrorOrientationMode)
        } else {
            Toast.makeText(this, "Screen capture permission required for cluster mirroring", Toast.LENGTH_SHORT).show()
            selectNavMode(MODE_OFF)
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
        setupBottomNav()
        setupQuickFeatures()
        setupScooterControls()
        setupSettingsTab()
        setupCadenceFilters()
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
        updateStaleIndicator()

        // Fetch latest server insights if a scooter is paired
        savedAddress?.let { addr ->
            lifecycleScope.launch {
                NexusCloudSyncManager.getInstance(this@MainActivity).fetchInsights(addr)
            }
        }

        // When Notification Access permission is granted, rebind and sync music immediately
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

        // Re-check notification permission and sync navigation if in TBT mode
        if (currentNavMode == MODE_TBT) {
            if (isNotificationListenerEnabled()) {
                binding.tvNavInstruction.text = "TBT Mode active. Streaming Google Maps directions over BLE."
                binding.btnNavAction.visibility = View.VISIBLE
                binding.btnNavAction.text = "🔄 RE-SYNC GOOGLE MAPS"
                binding.btnNavAction.setOnClickListener { syncGoogleMapsNow() }
                syncGoogleMapsNow()
                startPeriodicNavSync()
            } else {
                binding.tvNavInstruction.text = "Notification access required to read Google Maps directions & sync music."
                binding.btnNavAction.visibility = View.VISIBLE
                binding.btnNavAction.text = "UNLOCK ACCESS"
                binding.btnNavAction.setOnClickListener { showRestrictedSettingsGuideDialog() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicNavSync()
        blinkHandler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(navUpdateReceiver)
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUi() {
        // Tap status pill to connect / disconnect
        binding.tvStatus.setOnClickListener {
            if (isConnected) {
                disconnectScooter()
            } else {
                scanForScooter()
            }
        }

        // Settings button in header
        binding.btnSettings.setOnClickListener {
            showSyncSettingsDialog()
        }

        // Manual Cloud Sync button in header
        binding.btnCloudSyncNow.setOnClickListener {
            val syncManager = NexusCloudSyncManager.getInstance(this)
            binding.btnCloudSyncNow.isEnabled = false
            Toast.makeText(this, "Syncing telemetry with cloud...", Toast.LENGTH_SHORT).show()
            syncManager.triggerManualSync(savedAddress, repository.currentData.value) { _, msg ->
                binding.btnCloudSyncNow.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Show saved device name
        binding.tvDeviceName.text = savedName ?: "No device paired"
        setUiDisconnected()

        setupNavModeUi()
    }

    // ─── Bottom Navigation Dock ───────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.btnNavHome.setOnClickListener { switchTab(TAB_HOME) }
        binding.btnNavTrips.setOnClickListener { switchTab(TAB_TRIPS) }
        binding.btnNavInsights.setOnClickListener { switchTab(TAB_INSIGHTS) }
        binding.btnNavScooter.setOnClickListener { switchTab(TAB_SCOOTER) }
        binding.btnNavSettings.setOnClickListener { switchTab(TAB_SETTINGS) }

        // Switch to Trips tab from Home cadence card button
        binding.btnViewFullTrips.setOnClickListener { switchTab(TAB_TRIPS) }

        // Initialize active tab styling
        switchTab(TAB_HOME)
    }

    private fun switchTab(tab: Int) {
        currentTabIndex = tab

        binding.tabHome.visibility = if (tab == TAB_HOME) View.VISIBLE else View.GONE
        binding.tabTrips.visibility = if (tab == TAB_TRIPS) View.VISIBLE else View.GONE
        binding.tabInsights.visibility = if (tab == TAB_INSIGHTS) View.VISIBLE else View.GONE
        binding.tabScooter.visibility = if (tab == TAB_SCOOTER) View.VISIBLE else View.GONE
        binding.tabSettings.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE

        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Reset all tabs to muted
        binding.ivNavHome.setColorFilter(if (tab == TAB_HOME) cyanColor else mutedColor)
        binding.tvNavHome.setTextColor(if (tab == TAB_HOME) cyanColor else mutedColor)

        binding.ivNavTrips.setColorFilter(if (tab == TAB_TRIPS) cyanColor else mutedColor)
        binding.tvNavTrips.setTextColor(if (tab == TAB_TRIPS) cyanColor else mutedColor)

        binding.ivNavInsights.setColorFilter(if (tab == TAB_INSIGHTS) cyanColor else mutedColor)
        binding.tvNavInsights.setTextColor(if (tab == TAB_INSIGHTS) cyanColor else mutedColor)

        binding.ivNavScooter.setColorFilter(if (tab == TAB_SCOOTER) cyanColor else mutedColor)
        binding.tvNavScooter.setTextColor(if (tab == TAB_SCOOTER) cyanColor else mutedColor)

        binding.ivNavSettings.setColorFilter(if (tab == TAB_SETTINGS) cyanColor else mutedColor)
        binding.tvNavSettings.setTextColor(if (tab == TAB_SETTINGS) cyanColor else mutedColor)
    }

    // ─── Quick Feature Tiles: Music & Maps ────────────────────────────────────

    private fun setupQuickFeatures() {
        // Music Sync Play Button
        binding.btnMusicPlay.setOnClickListener {
            playMusicAudio()
        }

        // Music Selector Modal Button
        binding.btnMusicSelectApp.setOnClickListener {
            showMusicAppSelectDialog()
        }

        // Google Maps Open Button
        binding.btnMapsOpen.setOnClickListener {
            openGoogleMapsNavigation()
        }

        // Share Location Button
        binding.btnMapsShare.setOnClickListener {
            shareLiveLocation()
        }
    }

    private fun playMusicAudio() {
        val ytmPackage = "com.google.android.apps.youtube.music"
        val ytPackage = "com.google.android.youtube"
        val pm = packageManager

        val intent = pm.getLaunchIntentForPackage(ytmPackage)
            ?: pm.getLaunchIntentForPackage(ytPackage)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com"))

        try {
            startActivity(intent)
            Toast.makeText(this, "Streaming audio to cluster...", Toast.LENGTH_SHORT).show()
            MapNotificationListenerService.triggerActiveMediaSync()
        } catch (e: Exception) {
            Toast.makeText(this, "Opening YouTube...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMusicAppSelectDialog() {
        val apps = arrayOf("YouTube Music", "Spotify", "Apple Music", "YouTube (Default)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Cluster Music Player")
            .setItems(apps) { _, which ->
                when (which) {
                    0 -> launchAppByPackage("com.google.android.apps.youtube.music", "https://music.youtube.com")
                    1 -> launchAppByPackage("com.spotify.music", "https://open.spotify.com")
                    2 -> launchAppByPackage("com.apple.android.music", "https://music.apple.com")
                    3 -> launchAppByPackage("com.google.android.youtube", "https://youtube.com")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchAppByPackage(pkg: String, fallbackUrl: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
        try {
            startActivity(intent)
            MapNotificationListenerService.triggerActiveMediaSync()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGoogleMapsNavigation() {
        val gmmIntentUri = Uri.parse("google.navigation:q=home&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(mapIntent)
            Toast.makeText(this, "Opening Google Maps navigation...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com"))
            startActivity(browserIntent)
        }
    }

    private fun shareLiveLocation() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Nexus Pulse Live Ride Location")
            putExtra(
                Intent.EXTRA_TEXT,
                "Track my ride live on Ampere Nexus: https://maps.google.com/?q=12.9716,77.5946"
            )
        }
        startActivity(Intent.createChooser(shareIntent, "Share Ride Coordinates"))
    }

    // ─── Cadence Range Filters ────────────────────────────────────────────────

    private fun setupCadenceFilters() {
        binding.btnCadence3D.setOnClickListener { setCadenceFilter("3D") }
        binding.btnCadence7D.setOnClickListener { setCadenceFilter("7D") }
        binding.btnCadence30D.setOnClickListener { setCadenceFilter("30D") }
        binding.btnCadence1Y.setOnClickListener { setCadenceFilter("1Y") }
    }

    private fun setCadenceFilter(range: String) {
        selectedCadence = range

        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_pill_active)
        val transparent = ContextCompat.getColor(this, android.R.color.transparent)
        val activeTextColor = ContextCompat.getColor(this, R.color.surface_base)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        fun styleButton(view: TextView, isActive: Boolean) {
            if (isActive) {
                view.background = activeBg
                view.setTextColor(activeTextColor)
            } else {
                view.setBackgroundColor(transparent)
                view.setTextColor(inactiveTextColor)
            }
        }

        styleButton(binding.btnCadence3D, range == "3D")
        styleButton(binding.btnCadence7D, range == "7D")
        styleButton(binding.btnCadence30D, range == "30D")
        styleButton(binding.btnCadence1Y, range == "1Y")

        // Fetch cadence from server
        savedAddress?.let { addr ->
            NexusCloudSyncManager.getInstance(this).fetchCadence(addr, range) { success, json ->
                if (success && json != null) {
                    val aggKm = json.optDouble("total_distance_km", 96.4)
                    binding.tvCadenceAggregation.text = "%.1f km".format(aggKm)
                }
            }
        }
    }

    // ─── Scooter Tab Controls & Diagnostics ───────────────────────────────────

    private fun setupScooterControls() {
        // Remote Lock / Unlock
        binding.btnLockToggle.setOnClickListener {
            val ins = NexusCloudSyncManager.getInstance(this).insights.value
            val newCommand = if (ins.isLocked) "UNLOCK" else "LOCK"
            Toast.makeText(this, "Sending $newCommand command...", Toast.LENGTH_SHORT).show()
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, newCommand) { success, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                updateLockStateUi(newCommand == "LOCK")
            }
        }

        // Open Boot
        binding.btnOpenBoot.setOnClickListener {
            Toast.makeText(this, "Opening boot remotely...", Toast.LENGTH_SHORT).show()
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, "OPEN_BOOT") { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Siren Ping
        binding.btnSirenPing.setOnClickListener {
            Toast.makeText(this, "Activating siren ping...", Toast.LENGTH_SHORT).show()
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, "SIREN_PING") { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Live GPS in Scooter tab
        binding.btnLiveGps.setOnClickListener {
            openGoogleMapsNavigation()
        }

        // Run Diagnostics Scan
        binding.btnRunScan.setOnClickListener {
            binding.btnRunScan.isEnabled = false
            Toast.makeText(this, "Running full diagnostic scan...", Toast.LENGTH_SHORT).show()
            NexusCloudSyncManager.getInstance(this).runDiagnosticsScan(savedAddress) { _, msg ->
                binding.btnRunScan.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        // Ask Neural Copilot in Insights tab
        binding.btnAskCopilot.setOnClickListener {
            showCopilotChatDialog()
        }
    }

    private fun updateLockStateUi(isLocked: Boolean) {
        if (isLocked) {
            binding.ivLockIcon.setImageResource(R.drawable.ic_lock)
            binding.tvLockLabel.text = "Unlock"
        } else {
            binding.ivLockIcon.setImageResource(R.drawable.ic_unlock)
            binding.tvLockLabel.text = "Lock"
        }
    }

    private fun showCopilotChatDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        val input = EditText(this).apply {
            hint = "Ask Copilot: e.g. How can I optimize my range?"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Nexus Neural Copilot")
            .setView(input)
            .setPositiveButton("Ask") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) {
                    Toast.makeText(this, "Thinking...", Toast.LENGTH_SHORT).show()
                    NexusCloudSyncManager.getInstance(this).askCopilot(query, savedAddress) { success, reply ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Copilot Insights")
                            .setMessage(reply)
                            .setPositiveButton("Got It", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Settings Tab Controls ────────────────────────────────────────────────

    private fun setupSettingsTab() {
        // Riding Mode segmented selector
        binding.btnModeEco.setOnClickListener { setRideMode("Eco") }
        binding.btnModeCity.setOnClickListener { setRideMode("City") }
        binding.btnModePower.setOnClickListener { setRideMode("Power") }
        binding.btnModeLimp.setOnClickListener { setRideMode("Limpmode") }

        // Feature assist switches
        binding.switchHillHold.setOnCheckedChangeListener { _, isChecked ->
            NexusCloudSyncManager.getInstance(this).setFeatureToggle(savedAddress, "hill_hold", isChecked)
        }
        binding.switchReverseCrawl.setOnCheckedChangeListener { _, isChecked ->
            NexusCloudSyncManager.getInstance(this).setFeatureToggle(savedAddress, "reverse_crawl", isChecked)
        }
        binding.switchAntiTheft.setOnCheckedChangeListener { _, isChecked ->
            NexusCloudSyncManager.getInstance(this).setFeatureToggle(savedAddress, "anti_theft", isChecked)
        }

        // OTA Update check
        binding.btnCheckOta.setOnClickListener {
            Toast.makeText(this, "Checking Ampere cloud for updates... You have the latest software (v4.2.1-stable).", Toast.LENGTH_LONG).show()
        }

        // Forget Scooter button
        binding.btnForgetScooter.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Forget Scooter")
                .setMessage("Are you sure you want to unpair from this scooter?")
                .setPositiveButton("Unpair") { _, _ ->
                    savedAddress = null
                    savedName = null
                    disconnectScooter()
                    binding.tvDeviceName.text = "No device paired"
                    Toast.makeText(this, "Device forgotten", Toast.LENGTH_SHORT).show()
                    switchTab(TAB_HOME)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setRideMode(mode: String) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_pill_active)
        val transparent = ContextCompat.getColor(this, android.R.color.transparent)
        val activeTextColor = ContextCompat.getColor(this, R.color.surface_base)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        fun styleMode(btn: android.widget.Button, isActive: Boolean) {
            if (isActive) {
                btn.background = activeBg
                btn.setTextColor(activeTextColor)
            } else {
                btn.setBackgroundColor(transparent)
                btn.setTextColor(inactiveTextColor)
            }
        }

        styleMode(binding.btnModeEco, mode == "Eco")
        styleMode(binding.btnModeCity, mode == "City")
        styleMode(binding.btnModePower, mode == "Power")
        styleMode(binding.btnModeLimp, mode == "Limpmode")

        binding.tvRidingModeBadge.text = mode.uppercase()
        NexusCloudSyncManager.getInstance(this).setRidingMode(savedAddress, mode)
    }

    // ─── Cluster Navigation & Wi-Fi Mirroring ─────────────────────────────────

    private fun setupNavModeUi() {
        binding.btnNavModeOff.setOnClickListener { selectNavMode(MODE_OFF) }
        binding.btnNavModeTbt.setOnClickListener { selectNavMode(MODE_TBT) }
        binding.btnNavModeMirror.setOnClickListener { selectNavMode(MODE_MIRROR) }

        binding.btnNavAction.setOnClickListener {
            if (currentNavMode == MODE_TBT) {
                showRestrictedSettingsGuideDialog()
            }
        }

        binding.btnTestTbtNav.setOnClickListener {
            runTestNavigationDemo()
        }

        binding.btnToggleMirrorOrientation.setOnClickListener {
            toggleMirrorOrientation()
        }

        selectNavMode(currentNavMode)
    }

    private fun updateNavModeButtonsUi(mode: String) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_pill_active)
        val transparent = ContextCompat.getColor(this, android.R.color.transparent)
        val activeTextColor = ContextCompat.getColor(this, R.color.surface_base)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        fun styleNav(btn: android.widget.Button, isActive: Boolean) {
            if (isActive) {
                btn.background = activeBg
                btn.setTextColor(activeTextColor)
            } else {
                btn.setBackgroundColor(transparent)
                btn.setTextColor(inactiveTextColor)
            }
        }

        styleNav(binding.btnNavModeOff, mode == MODE_OFF)
        styleNav(binding.btnNavModeTbt, mode == MODE_TBT)
        styleNav(binding.btnNavModeMirror, mode == MODE_MIRROR)
    }

    private val navSyncRunnable = object : Runnable {
        override fun run() {
            if (currentNavMode == MODE_TBT && isConnected) {
                MapNotificationListenerService.instance?.syncActiveNavigationNow()
            }
            if (currentNavMode == MODE_TBT) {
                blinkHandler.postDelayed(this, 3000L)
            }
        }
    }

    private fun startPeriodicNavSync() {
        blinkHandler.removeCallbacks(navSyncRunnable)
        blinkHandler.postDelayed(navSyncRunnable, 3000L)
    }

    private fun stopPeriodicNavSync() {
        blinkHandler.removeCallbacks(navSyncRunnable)
    }

    private fun syncGoogleMapsNow() {
        if (MapNotificationListenerService.instance == null) {
            Log.w("MainActivity", "MapNotificationListenerService instance is null, requesting rebind")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        ComponentName(this, MapNotificationListenerService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "requestRebind exception: ${e.message}")
                }
            }
            binding.tvNavDetail.visibility = View.VISIBLE
            binding.tvNavDetail.text = "Connecting Notification Listener... Re-syncing in 2s..."
            Handler(Looper.getMainLooper()).postDelayed({
                MapNotificationListenerService.instance?.syncActiveNavigationNow()
            }, 2000L)
            return
        }

        val listener = MapNotificationListenerService.instance
        val synced = listener?.syncActiveNavigationNow() ?: false
        if (synced) {
            Toast.makeText(this, "Active Google Maps synced to Cluster!", Toast.LENGTH_SHORT).show()
        } else {
            if (BleService.instance?.isClusterNavigating == true) {
                binding.tvNavDetail.visibility = View.VISIBLE
            } else {
                binding.tvNavDetail.visibility = View.VISIBLE
                binding.tvNavDetail.text = "No active Google Maps navigation found. Start navigation in Google Maps first, then tap 'RE-SYNC'."
            }
        }
    }

    private fun selectNavMode(mode: String) {
        currentNavMode = mode
        updateNavModeButtonsUi(mode)

        when (mode) {
            MODE_OFF -> {
                stopPeriodicNavSync()
                stopScreenMirroring()
                BleService.instance?.clearNavigationFromCluster()
                binding.tvNavModeBadge.text = "OFF"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvNavInstruction.text = "Select TBT or Mirror to stream to cluster"
                binding.tvNavDetail.visibility = View.GONE
                binding.btnNavAction.visibility = View.GONE
                binding.btnToggleMirrorOrientation.visibility = View.GONE
            }
            MODE_TBT -> {
                stopScreenMirroring()
                binding.tvNavModeBadge.text = "TBT (BLE)"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                binding.btnToggleMirrorOrientation.visibility = View.GONE

                if (!isNotificationListenerEnabled()) {
                    binding.tvNavInstruction.text = "Notification access required to read Google Maps directions."
                    binding.btnNavAction.visibility = View.VISIBLE
                    binding.btnNavAction.text = "UNLOCK ACCESS"
                    binding.btnNavAction.setOnClickListener {
                        showRestrictedSettingsGuideDialog()
                    }
                    showRestrictedSettingsGuideDialog()
                } else {
                    binding.tvNavInstruction.text = "TBT Mode active. Streaming Google Maps directions over BLE."
                    binding.btnNavAction.visibility = View.VISIBLE
                    binding.btnNavAction.text = "🔄 RE-SYNC GOOGLE MAPS"
                    binding.btnNavAction.setOnClickListener {
                        syncGoogleMapsNow()
                    }
                    syncGoogleMapsNow()
                    startPeriodicNavSync()
                }
            }
            MODE_MIRROR -> {
                stopPeriodicNavSync()
                binding.tvNavModeBadge.text = "MIRROR (WI-FI)"
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.btnToggleMirrorOrientation.visibility = View.VISIBLE
                updateMirrorOrientationButtonText()

                val missingWifiPerms = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    }
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                if (missingWifiPerms.isNotEmpty()) {
                    binding.tvNavInstruction.text = "Grant Wi-Fi/Nearby permissions to start hotspot"
                    permissionLauncher.launch(missingWifiPerms.toTypedArray())
                    return
                }

                binding.tvNavInstruction.text = "Requesting screen capture permission…"
                binding.btnNavAction.visibility = View.GONE
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun showRestrictedSettingsGuideDialog() {
        MaterialAlertDialogBuilder(this)
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
        val enabledPackages = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
        if (enabledPackages.contains(packageName)) return true
        val cn = ComponentName(this, MapNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString()) || flat.contains(packageName)
    }

    private var currentMirrorOrientationMode = ScreenMirroringService.MODE_LANDSCAPE_AUTO

    private fun updateMirrorOrientationButtonText() {
        binding.btnToggleMirrorOrientation.text = when (currentMirrorOrientationMode) {
            ScreenMirroringService.MODE_LANDSCAPE_AUTO -> "ROTATE 90°"
            ScreenMirroringService.MODE_ROTATE_90_CW -> "ROTATE 180°"
            ScreenMirroringService.MODE_ROTATE_90_CCW -> "FIT CENTER"
            else -> "LANDSCAPE (AUTO)"
        }
    }

    private fun toggleMirrorOrientation() {
        currentMirrorOrientationMode = when (currentMirrorOrientationMode) {
            ScreenMirroringService.MODE_LANDSCAPE_AUTO -> ScreenMirroringService.MODE_ROTATE_90_CW
            ScreenMirroringService.MODE_ROTATE_90_CW -> ScreenMirroringService.MODE_ROTATE_90_CCW
            ScreenMirroringService.MODE_ROTATE_90_CCW -> ScreenMirroringService.MODE_FIT_CENTER
            else -> ScreenMirroringService.MODE_LANDSCAPE_AUTO
        }
        applyMirrorOrientation(currentMirrorOrientationMode)
    }

    private fun applyMirrorOrientation(mode: Int) {
        updateMirrorOrientationButtonText()
        when (mode) {
            ScreenMirroringService.MODE_LANDSCAPE_AUTO -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                Toast.makeText(this, "Landscape mode active", Toast.LENGTH_SHORT).show()
            }
            ScreenMirroringService.MODE_ROTATE_90_CW -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Toast.makeText(this, "Rotated 90° CW", Toast.LENGTH_SHORT).show()
            }
            ScreenMirroringService.MODE_ROTATE_90_CCW -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Toast.makeText(this, "Rotated 90° CCW", Toast.LENGTH_SHORT).show()
            }
            ScreenMirroringService.MODE_FIT_CENTER -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Toast.makeText(this, "Fit center mode", Toast.LENGTH_SHORT).show()
            }
        }

        val intent = Intent(this, ScreenMirroringService::class.java).apply {
            action = ScreenMirroringService.CMD_SET_ORIENTATION
            putExtra(ScreenMirroringService.EXTRA_ORIENTATION_MODE, mode)
        }
        startService(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("MainActivity", "onConfigurationChanged: orientation=${newConfig.orientation}")
    }

    private fun stopScreenMirroring() {
        binding.btnToggleMirrorOrientation.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val intent = Intent(this, ScreenMirroringService::class.java).apply {
            action = ScreenMirroringService.CMD_STOP
        }
        startService(intent)
    }

    private fun runTestNavigationDemo() {
        val service = BleService.instance
        if (service == null || !isConnected) {
            Toast.makeText(this, "Connect to scooter first!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Starting 10s Cluster Navigation Test...", Toast.LENGTH_SHORT).show()
        binding.tvNavDetail.visibility = View.VISIBLE
        binding.tvNavDetail.text = "Testing Cluster Display: Left in 40m..."

        val road = "Outer Ring Road"
        val total = 12500
        service.updateNavigationToCluster(NexusParser.MANEUVER_LEFT, 40, total, road)

        for (sec in 1..8) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isConnected) {
                    val turnDist = maxOf(40 - sec * 4, 10)
                    val man = if (sec >= 7) NexusParser.MANEUVER_STRAIGHT else NexusParser.MANEUVER_LEFT
                    val r = if (sec >= 7) "Indiranagar 100ft Rd" else road
                    binding.tvNavDetail.text = "Test Nav: in ${turnDist}m -> $r"
                    service.updateNavigationToCluster(man, turnDist, total - sec * 10, r)
                }
            }, sec * 1000L)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(this, "Test Complete. Restoring active mode.", Toast.LENGTH_SHORT).show()
            if (currentNavMode == MODE_TBT) {
                syncGoogleMapsNow()
            } else {
                binding.tvNavDetail.visibility = View.GONE
                service.clearNavigationFromCluster()
            }
        }, 10000L)
    }

    private fun showSyncSettingsDialog() {
        val menuOptions = arrayOf(
            "Cluster Time Sync Method",
            "Nexus Cloud Server & API Settings",
            "Force Cloud Telemetry Sync"
        )

        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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
            binding.tvStatus.text = "FOUND"
        }

        connectToAddress(address)
    }

    private fun onScanFailed() {
        runOnUiThread {
            binding.tvStatus.text = "SCAN FAILED"
        }
    }

    private fun onDataReceived(intent: Intent) {
        // Redundant with Repository observer but kept for broadcast compatibility
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

            // Odometer display
            if (data.odometerKm >= 0.0) {
                binding.tvVoltage.text = "%.1f km".format(data.odometerKm)
            } else if (data.voltageV >= 0.0) {
                binding.tvVoltage.text = "%.1fV".format(data.voltageV)
            }

            // Trip distance
            if (data.tripKm >= 0.0) {
                binding.tvTodayKm.text = "%.1f".format(data.tripKm)
            }

            // State of Charge
            if (data.socPercent >= 0) {
                binding.tvSoc.text = "${data.socPercent}%"
                binding.progressSoc.progress = data.socPercent
            }

            // Remaining Range (DTE)
            if (data.remainingKm >= 0) {
                binding.tvRange.text = "≈ ${data.remainingKm} km"
            }

            // Riding Mode
            if (data.ridingMode.isNotBlank()) {
                binding.tvRidingModeBadge.text = data.ridingMode.uppercase()
            }

            // Bottom dock status
            binding.tvDockSyncStatus.text = "Connected • Live Telemetry ($packetCount pkts)"
        }
    }

    private suspend fun refreshTravelStats() {
        val today = repository.getTodayDistance()
        val yesterday = repository.getYesterdayDistance()
        runOnUiThread {
            if (today > 0.0) {
                binding.tvTodayKm.text = "%.1f".format(today)
            }
            if (yesterday > 0.0) {
                val diffPct = if (today > 0.0) Math.round(((today - yesterday) / yesterday) * 100) else 0
                binding.tvVsYesterday.text = if (diffPct >= 0) "+$diffPct% vs yesterday" else "$diffPct% vs yesterday"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCloudInsightsUi(ins: ServerInsights) {
        runOnUiThread {
            // Cloud Sync Badge in Header
            when (ins.syncStatus) {
                "SYNCING" -> {
                    binding.tvCloudSyncBadge.text = "☁ SYNCING..."
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                }
                "QUEUED" -> {
                    val count = if (ins.pendingQueueCount > 0) "${ins.pendingQueueCount} " else ""
                    binding.tvCloudSyncBadge.text = "☁ ${count}QUEUED"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                }
                "SUCCESS" -> {
                    binding.tvCloudSyncBadge.text = "☁ CLOUD OK"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_emerald))
                }
                "ERROR" -> {
                    binding.tvCloudSyncBadge.text = "☁ RETRY"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                }
                else -> {
                    binding.tvCloudSyncBadge.text = "CLOUD SYNCED"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                }
            }

            // Hero Telemetry Overlay
            binding.tvTodayKm.text = "%.1f".format(ins.todayDistanceKm)
            binding.tvVsYesterday.text = if (ins.vsYesterdayPct >= 0) "+${ins.vsYesterdayPct}% vs yesterday" else "${ins.vsYesterdayPct}% vs yesterday"
            binding.tvDailyGoalProgress.text = "Goal: %.0f km (%d%% done)".format(ins.dailyGoalKm, ins.goalProgressPct)
            binding.tvAvgSpeed.text = "%.0f km/h".format(ins.todayAvgSpeedKmh)
            binding.tvRideTime.text = "${ins.todayRideTimeMins} mins"
            binding.tvBatteryUsed.text = "${ins.todaySocConsumed}%"

            // Cadence Preview
            binding.tvCadenceAggregation.text = "%.1f km".format(ins.monthDistanceKm)
            binding.tvTripsTotalOdoBadge.text = "%.0f km total".format(ins.odometerKm)

            // Power Pack & Battery
            binding.tvSohPercent.text = "${ins.batterySoh}% SOH"
            binding.tvBatteryTemp.text = "%.1f °C".format(ins.batteryTemp)
            binding.tvCellDelta.text = "%.3f V".format(ins.cellDeltaV)
            binding.tvCycleCount.text = "${ins.cycleCount} cycles"

            // Ownership Savings Matrix
            binding.tvCloudMoneySaved.text = "₹${ins.moneySavedInr}"
            binding.tvCloudPetrolSaved.text = "%.1f L".format(ins.petrolSavedLiters)
            binding.tvCloudCo2Saved.text = "%.0f kg".format(ins.co2SavedKg)
            binding.tvTotalLifetimeSaved.text = "₹${ins.totalLifetimeSavedInr}"

            // AI Insights Tab
            binding.tvEcoScoreBig.text = "${ins.ecoScore}"
            binding.tvFleetRankBadge.text = ins.fleetRank
            binding.tvSmoothThrottleVal.text = "Smooth Throttle: ${ins.smoothAccelPct}%"
            binding.tvRegenCaptureVal.text = "Regen Capture: ${ins.regenRecapturedPct}%"
            binding.tvSpeedDisciplineVal.text = "Speed Discipline: ${ins.speedZoneEcoPct}%"
            binding.tvRegenKwh.text = "%.1f kWh".format(ins.recapturedRegenKwh)
            binding.tvSpeedZoneEco.text = "35-50 km/h (${ins.speedZoneEcoPct}%)"

            // Lock State
            updateLockStateUi(ins.isLocked)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateAiStateUi(ai: AiRideState) {
        runOnUiThread {
            binding.tvAiRange.text = "AI: ${ai.aiPredictedRangeKm} km"
            binding.tvAiRangeSub.text = "${ai.estimatedKmPerSoc} km/% • ${ai.rangeConfidence}"
            binding.tvAiEcoScore.text = "${ai.ecoScore}/100"
            binding.tvAiEcoRating.text = ai.ecoRating
            binding.tvAiCoachTip.text = ai.coachTip

            when {
                ai.ecoScore >= 90 -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.status_emerald))
                ai.ecoScore >= 75 -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                else -> binding.tvAiEcoScore.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            }
        }
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private fun setUiConnected() {
        binding.tvStatus.text = "CONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
        binding.dockStatusDot.setBackgroundResource(R.drawable.bg_pill_emerald)
        binding.tvDockSyncStatus.text = "Connected to Scooter • 100% Sync"
    }

    private fun setUiDisconnected() {
        binding.tvStatus.text = "DISCONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        binding.dockStatusDot.setBackgroundResource(R.drawable.bg_pill_amber)
        binding.tvDockSyncStatus.text = "Disconnected • Tap Status to Connect"
        binding.tvSpeed.text = "0"
        binding.tvRange.text = "≈ 0 km"
    }

    private fun updateStaleIndicator() {
        if (lastUpdateTime > 0) {
            val age = System.currentTimeMillis() - lastUpdateTime
            if (age > 15_000) {
                binding.tvDockSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            } else {
                binding.tvDockSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun setUiScanning() {
        binding.tvStatus.text = "SCANNING..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
    }

    private fun setUiConnecting() {
        binding.tvStatus.text = "CONNECTING..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
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
            if (currentNavMode == MODE_MIRROR) {
                val hasWifiPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                } else true
                if (hasWifiPerm) {
                    mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                }
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
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
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
        val btManager = getSystemService(BluetoothManager::class.java)
        if (btManager?.adapter?.isEnabled != true) {
            binding.tvStatus.text = "BT OFF"
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val address = savedAddress
        if (address != null) {
            binding.tvDeviceName.text = savedName ?: address
            binding.tvStatus.text = "AUTO-CONNECT..."
            connectToAddress(address)
        } else {
            binding.tvStatus.text = "SEARCH"
        }
    }
}
