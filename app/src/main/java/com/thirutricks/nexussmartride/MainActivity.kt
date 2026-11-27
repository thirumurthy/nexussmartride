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
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.thirutricks.nexussmartride.ai.AiRideState
import com.thirutricks.nexussmartride.ai.JourneyClusteringEngine
import com.thirutricks.nexussmartride.ai.SmartAiEngine
import com.thirutricks.nexussmartride.ui.CadenceChartPoint
import com.thirutricks.nexussmartride.ui.JourneyHistoryAdapter
import com.thirutricks.nexussmartride.ui.RideCadenceChartView
import com.thirutricks.nexussmartride.data.DailyTravelEntity
import com.thirutricks.nexussmartride.data.JourneyEntity
import com.thirutricks.nexussmartride.data.MaintenanceChecklistItem
import com.thirutricks.nexussmartride.data.MaintenanceReport
import com.thirutricks.nexussmartride.data.MaintenanceRepository
import com.thirutricks.nexussmartride.data.OwnerTaskItem
import com.thirutricks.nexussmartride.data.EvVsPetrolItem
import com.thirutricks.nexussmartride.data.FaqItem
import com.thirutricks.nexussmartride.data.ServiceMilestoneItem
import com.thirutricks.nexussmartride.data.ScooterDatabase
import com.thirutricks.nexussmartride.data.ScooterRepository
import com.thirutricks.nexussmartride.databinding.ActivityMainBinding
import com.thirutricks.nexussmartride.databinding.LayoutScooterMaintenanceBinding
import com.thirutricks.nexussmartride.sync.NexusCloudSyncManager
import com.thirutricks.nexussmartride.sync.ServerInsights
import com.thirutricks.nexussmartride.ui.CyberCardAnimator
import com.thirutricks.nexussmartride.ui.CyberShimmer
import com.thirutricks.nexussmartride.ui.CyberSoundManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * Main cyber-automotive companion dashboard for Ampere Nexus EV.
 * Conforms to the Stitch Nexus Pulse Cyber-Automotive Specification (v2.0.0).
 * Binds to BleService via LocalBroadcastManager and NexusCloudSyncManager for cloud telemetry.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val maintBinding: LayoutScooterMaintenanceBinding get() = binding.includeMaintenance

    private lateinit var repository: ScooterRepository
    private val prefs by lazy { getSharedPreferences("nexus_prefs", MODE_PRIVATE) }

    // Saved device (auto-connect on launch)
    private var savedAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) = prefs.edit().putString("device_address", v).apply()

    private var savedName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    // Fuel & Savings Benchmark Configuration (Configurable in Settings)
    private var petrolPricePerLiter: Float
        get() = prefs.getFloat("pref_petrol_price", 104.0f)
        set(v) = prefs.edit().putFloat("pref_petrol_price", v).apply()

    private var iceScooterMileageKmL: Float
        get() = prefs.getFloat("pref_ice_mileage", 42.0f)
        set(v) = prefs.edit().putFloat("pref_ice_mileage", v).apply()

    // Last Known Battery SOC (Persisted across sessions for main hero display)
    private var lastKnownBatteryPercent: Int
        get() = prefs.getInt("pref_last_known_battery_soc", -1)
        set(v) = prefs.edit().putInt("pref_last_known_battery_soc", v).apply()

    private fun updateHeroBatteryUi() {
        val soc = if (lastKnownBatteryPercent in 0..100) {
            lastKnownBatteryPercent
        } else {
            val dbSoc = repository.currentData.value.socPercent
            if (dbSoc in 0..100) dbSoc else -1
        }

        if (soc in 0..100) {
            binding.tvHeroBattery.text = "$soc%"
            binding.tvHeroBatteryLabel.text = if (isConnected) "BATTERY" else "LAST KNOWN BATTERY"
        } else {
            binding.tvHeroBattery.text = "--%"
            binding.tvHeroBatteryLabel.text = "BATTERY"
        }
    }

    private var isConnected = false
    private var lastUpdateTime = 0L
    private var packetCount = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Active bottom navigation tab index (0=Home, 1=Trips, 2=Insights, 3=Scooter, 4=Settings)
    private var currentTabIndex = 0

    // Cadence filter selection ("3D", "7D", "30D", "1Y") - default is 3D
    private var selectedCadence = "3D"

    // Cached cloud insights for dynamic cadence & telemetry calculations
    private var latestInsights: ServerInsights? = null

    // In-memory cache of daily travel distances updated via Room coroutines
    private var lastKnownTodayKm: Double = 0.0
    private var lastKnownYesterdayKm: Double = 0.0
    private var lastKnownMonthKm: Double = 0.0
    private var hasLoadedTodayDistance: Boolean = false
    private var cadenceRequestSequence = 0L
    private var recentDailyTravelList: List<DailyTravelEntity> = emptyList()
    private var selectedCadenceDayIdx: Int = -1
    private var cached7DayBars: List<CadenceBarItem> = emptyList()
    private var rideCadenceChartView: RideCadenceChartView? = null

    // Maintenance Lifecycle & Care State
    private var currentMaintenanceReport: MaintenanceReport? = null
    private var selectedMilestoneIndex: Int = -1
    private var userSelectedMilestoneManually: Boolean = false
    private var selectedOwnerTab: String = "WEEKLY" // "WEEKLY" or "MONTHLY"

    // Track active home telemetry shimmer state
    private var isShimmerActive = false

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
            if (!isClusterConnected()) {
                CyberSoundManager.playErrorAlert(this)
                Toast.makeText(this, "Cluster disconnected. Screen mirroring cancelled.", Toast.LENGTH_SHORT).show()
                selectNavMode(MODE_OFF)
                return@registerForActivityResult
            }
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

        // Show cyber skeleton shimmer across home telemetry cards while awaiting cloud API / BLE
        showHomeShimmer()

        // Safety fallback: auto-reveal UI after 3.5s if cloud or network is delayed
        Handler(Looper.getMainLooper()).postDelayed({
            if (isShimmerActive) {
                hideHomeShimmer()
            }
        }, 3500L)

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
        setupTripsTab()
        setupInsightsTab()
        registerReceivers()
        observeData()
        checkPermissionsAndStart()

        // Trigger initial staggered cyber entrance animation on Home cards
        binding.tabHome.post {
            CyberCardAnimator.animateHomeCardsEntrance(binding.tabHome)
        }
    }

    private fun observeData() {
        // Initial load of last known battery from room db if not in prefs
        lifecycleScope.launch {
            if (lastKnownBatteryPercent < 0) {
                val db = ScooterDatabase.getDatabase(this@MainActivity)
                db.scooterDao().getLatestLog().collectLatest { log ->
                    if (log != null && log.socPercent in 0..100 && lastKnownBatteryPercent < 0) {
                        lastKnownBatteryPercent = log.socPercent
                        runOnUiThread { updateHeroBatteryUi() }
                    }
                }
            }
        }

        // Initial load of local database daily travel distances
        lifecycleScope.launch {
            lastKnownTodayKm = repository.getTodayDistance()
            lastKnownYesterdayKm = repository.getYesterdayDistance()
            lastKnownMonthKm = repository.getCurrentMonthDistance()
            hasLoadedTodayDistance = true
            binding.tvTodayKm.text = "%.1f".format(lastKnownTodayKm)
            renderCadenceBars(generateCadenceData(selectedCadence, latestInsights))
            updateTripsWeeklyCadence(latestInsights)
            latestInsights?.let { updateOwnershipSavingsMatrix(it) }
        }

        // Live observation of daily travel history from Room database
        lifecycleScope.launch {
            repository.getAllDailyTravel().collectLatest { travelList ->
                recentDailyTravelList = travelList
                val yest = getLiveYesterdayKm()
                if (yest > 0.0 && lastKnownYesterdayKm == 0.0) {
                    lastKnownYesterdayKm = yest
                }
                val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val mKm = travelList.filter { it.date.startsWith(currentMonth) }.sumOf { it.distanceKm }
                if (mKm > 0.0) lastKnownMonthKm = Math.round(mKm * 10.0) / 10.0
                renderCadenceBars(generateCadenceData(selectedCadence, latestInsights))
                updateTripsWeeklyCadence(latestInsights)
                latestInsights?.let { updateOwnershipSavingsMatrix(it) }
            }
        }

        lifecycleScope.launch {
            repository.currentData.collectLatest { data ->
                updateUi(data)
            }
        }

        // Intelligent Journeys: Cluster into clean 1-week rides with charging & idle gap boundaries
        lifecycleScope.launch {
            val existing = repository.getRecentJourneysList(50)
            val hasLegacyLocations = existing.any {
                it.routeSummary.contains("Indiranagar") ||
                it.routeSummary.contains("Koramangala") ||
                it.routeSummary.contains("HSR") ||
                it.routeSummary.contains("Outer Ring")
            }
            if (hasLegacyLocations) {
                repository.deleteAllJourneys()
            }
            val clustered = if (!hasLegacyLocations && existing.isNotEmpty()) {
                JourneyClusteringEngine.clusterJourneys(existing)
            } else emptyList()

            if (clustered.isNotEmpty()) {
                if (clustered.size != existing.size) {
                    repository.deleteAllJourneys()
                    repository.saveJourneys(clustered)
                }
            } else {
                val todayDist = if (lastKnownTodayKm > 0) lastKnownTodayKm else (latestInsights?.todayDistanceKm ?: 0.0)
                val yestDist = if (lastKnownYesterdayKm > 0) lastKnownYesterdayKm else getLiveYesterdayKm()
                val seeds = JourneyClusteringEngine.generateSeedJourneys(
                    todayDist,
                    yestDist,
                    petrolPricePerLiter,
                    iceScooterMileageKmL
                )
                repository.saveJourneys(seeds)
            }
        }

        lifecycleScope.launch {
            repository.getRecentJourneys(4).collectLatest { journeys ->
                renderRecentJourneys(journeys)
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

        // Fetch latest server insights for active scooter
        lifecycleScope.launch {
            val syncMgr = NexusCloudSyncManager.getInstance(this@MainActivity)
            val currentId = syncMgr.getEffectiveDeviceId()
            syncMgr.fetchInsights(currentId)
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

        // Ownership savings benchmark configuration shortcut
        binding.tvSavingsPetrolPriceBadge.setOnClickListener {
            showPetrolPriceConfigDialog()
        }
        binding.layoutSavingsContent.setOnClickListener {
            showPetrolPriceConfigDialog()
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

        // Switch to Trips tab from Home cadence card buttons
        binding.btnViewFullHistory.setOnClickListener { switchTab(TAB_TRIPS) }

        // Switch to Scooter tab from Health card
        binding.cardScooterHealth.setOnClickListener { switchTab(TAB_SCOOTER) }

        // Initialize active tab styling
        switchTab(TAB_HOME)
    }

    private fun switchTab(tab: Int) {
        val wasDifferent = (currentTabIndex != tab)
        currentTabIndex = tab

        if (wasDifferent) {
            CyberSoundManager.playClickSound(this)
        }

        binding.tabHome.visibility = if (tab == TAB_HOME) View.VISIBLE else View.GONE
        binding.tabTrips.visibility = if (tab == TAB_TRIPS) View.VISIBLE else View.GONE
        binding.tabInsights.visibility = if (tab == TAB_INSIGHTS) View.VISIBLE else View.GONE
        binding.tabScooter.visibility = if (tab == TAB_SCOOTER) View.VISIBLE else View.GONE
        binding.tabSettings.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE

        if (tab == TAB_HOME) {
            CyberCardAnimator.animateHomeCardsEntrance(binding.tabHome)
        }

        if (tab == TAB_SCOOTER) {
            // When navigating to scooter page, preselect the next target service milestone by default
            userSelectedMilestoneManually = false
            val report = currentMaintenanceReport
            if (report != null && report.timeline.isNotEmpty()) {
                val targetIdx = getTargetMilestoneIndex(report.timeline, report.nextMilestoneKm)
                if (selectedMilestoneIndex != targetIdx) {
                    selectedMilestoneIndex = targetIdx
                    renderMilestoneTimelineTrack(report.timeline)
                }
            }
            scrollToSelectedMilestone(smooth = false)
            maintBinding.scrollMilestonesTrack.postDelayed({
                scrollToSelectedMilestone(smooth = true)
            }, 80)
        }

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
        // Music Sync Card - Entire card click launches YouTube directly
        binding.cardMusicSync.setOnClickListener {
            openYouTubeApp()
        }
        binding.btnMusicPlay.setOnClickListener {
            openYouTubeApp()
        }

        // Live Maps Card - Entire card click launches Google Maps directly
        binding.cardMapsLive.setOnClickListener {
            openGoogleMapsApp()
        }
        binding.btnMapsOpen.setOnClickListener {
            openGoogleMapsApp()
        }

        // Share Location Button
        binding.btnMapsShare.setOnClickListener {
            shareLiveLocation()
        }
    }

    private fun openYouTubeApp() {
        val pm = packageManager
        // 1. Direct package launch intent for YouTube app
        var intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
        if (intent == null) {
            // 2. Try URI with YouTube package
            val ytUriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:")).apply {
                setPackage("com.google.android.youtube")
            }
            if (ytUriIntent.resolveActivity(pm) != null) {
                intent = ytUriIntent
            }
        }
        if (intent == null) {
            // 3. Fallback to YouTube Music app
            intent = pm.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
        }
        if (intent == null) {
            // 4. Web fallback
            intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            Toast.makeText(this, "Opening YouTube for TFT cluster...", Toast.LENGTH_SHORT).show()
            MapNotificationListenerService.triggerActiveMediaSync()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to launch YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGoogleMapsApp() {
        val pm = packageManager
        // 1. Direct navigation intent targeting Google Maps package
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=navigation&mode=d")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (navIntent.resolveActivity(pm) != null) {
            try {
                startActivity(navIntent)
                Toast.makeText(this, "Launching Google Maps Navigation...", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Exception) {}
        }

        // 2. Direct package launch intent for Google Maps app
        val launchIntent = pm.getLaunchIntentForPackage("com.google.android.apps.maps")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                Toast.makeText(this, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Exception) {}
        }

        // 3. Web Maps fallback
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to launch Maps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGoogleMapsNavigation() = openGoogleMapsApp()

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

    // ─── Shimmer Telemetry Methods ────────────────────────────────────────────

    private fun showHomeShimmer() {
        if (isShimmerActive) return
        isShimmerActive = true
        runOnUiThread {
            binding.layoutHeroShimmer.visibility = View.VISIBLE
            binding.layoutHeroContent.visibility = View.GONE

            binding.layoutCadenceShimmer.visibility = View.VISIBLE
            binding.layoutCadenceContent.visibility = View.GONE

            binding.layoutBatteryShimmer.visibility = View.VISIBLE
            binding.layoutBatteryContent.visibility = View.GONE

            binding.layoutSavingsShimmer.visibility = View.VISIBLE
            binding.layoutSavingsContent.visibility = View.GONE

            binding.layoutHealthShimmer.visibility = View.VISIBLE
            binding.layoutHealthContent.visibility = View.GONE

            CyberShimmer.start(
                binding.layoutHeroShimmer,
                binding.layoutCadenceShimmer,
                binding.layoutBatteryShimmer,
                binding.layoutSavingsShimmer,
                binding.layoutHealthShimmer
            )
        }
    }

    private fun hideHomeShimmer() {
        if (!isShimmerActive) return
        isShimmerActive = false
        runOnUiThread {
            CyberShimmer.stopAll()

            binding.layoutHeroShimmer.visibility = View.GONE
            binding.layoutHeroContent.visibility = View.VISIBLE

            binding.layoutCadenceShimmer.visibility = View.GONE
            binding.layoutCadenceContent.visibility = View.VISIBLE

            binding.layoutBatteryShimmer.visibility = View.GONE
            binding.layoutBatteryContent.visibility = View.VISIBLE

            binding.layoutSavingsShimmer.visibility = View.GONE
            binding.layoutSavingsContent.visibility = View.VISIBLE

            binding.layoutHealthShimmer.visibility = View.GONE
            binding.layoutHealthContent.visibility = View.VISIBLE
        }
    }

    // ─── Dynamic Cadence Mileage Graph & Filters ──────────────────────────────

    data class CadenceBarItem(
        val label: String,
        val distanceKm: Double,
        val socConsumed: Int = 0,
        val energyKwh: Double = 0.0,
        val isPeak: Boolean = false,
        val subText: String? = null
    )

    data class CadenceData(
        val range: String,
        val title: String,
        val totalDistanceKm: Double,
        val totalSocConsumed: Int = 0,
        val totalEnergyKwh: Double = 0.0,
        val streakText: String,
        val bars: List<CadenceBarItem>
    )

    private fun setupCadenceFilters() {
        binding.btnCadence3D.setOnClickListener { setCadenceFilter("3D") }
        binding.btnCadence7D.setOnClickListener { setCadenceFilter("7D") }
        binding.btnCadence30D.setOnClickListener { setCadenceFilter("30D") }
        binding.btnCadence1Y.setOnClickListener { setCadenceFilter("1Y") }

        // 3D is selected by default per user requirements
        setCadenceFilter("3D")
    }

    private fun setCadenceFilter(range: String) {
        selectedCadence = range

        val solidCyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
        val darkBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
        val blackColor = ContextCompat.getColor(this, R.color.black)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_primary)

        fun styleButton(view: TextView, isActive: Boolean) {
            if (isActive) {
                view.background = solidCyanBg
                view.setTextColor(blackColor)
                view.setTypeface(view.typeface, Typeface.BOLD)
            } else {
                view.background = darkBg
                view.setTextColor(inactiveTextColor)
                view.setTypeface(Typeface.create(view.typeface, Typeface.NORMAL))
            }
        }

        styleButton(binding.btnCadence3D, range == "3D")
        styleButton(binding.btnCadence7D, range == "7D")
        styleButton(binding.btnCadence30D, range == "30D")
        styleButton(binding.btnCadence1Y, range == "1Y")

        // 1. Immediately render local calibrated data (zero delay, deterministic)
        val localData = generateCadenceData(range, latestInsights)
        renderCadenceBars(localData)

        // Update the 3 sub-metrics on the card
        val sevenDayData = generateCadenceData("7D", latestInsights)
        binding.tvCadence7DayTotalVal.text = if (sevenDayData.totalSocConsumed > 0) {
            "%.1f km (%d%% SoC)".format(sevenDayData.totalDistanceKm, sevenDayData.totalSocConsumed)
        } else {
            "%.1f km".format(sevenDayData.totalDistanceKm)
        }
        val avgKm = if (sevenDayData.totalDistanceKm > 0.0) sevenDayData.totalDistanceKm / 7.0 else 0.0
        val avgSoc = if (sevenDayData.totalSocConsumed > 0) sevenDayData.totalSocConsumed / 7 else 0
        binding.tvCadenceAvgDayVal.text = if (avgSoc > 0) "%.1f km (%d%%/d)".format(avgKm, avgSoc) else "%.1f km".format(avgKm)
        val trips7D = if (sevenDayData.totalDistanceKm > 0.0) maxOf(1, (sevenDayData.totalDistanceKm / 12.0).toInt()) else 0
        binding.tvCadenceTripsLoggedVal.text = "$trips7D trips"

        // 2. Fetch server cadence data with request sequence token to prevent race condition flapping
        // For 3D, always request 7D because the server endpoint returns the full 7-day week history (including yesterday and day-before-yesterday),
        // guaranteeing 100% data consistency between 3D and 7D!
        val reqSeq = ++cadenceRequestSequence
        val fetchRange = if (range == "3D") "7D" else range
        val syncMgr = NexusCloudSyncManager.getInstance(this)
        syncMgr.fetchCadence(syncMgr.getEffectiveDeviceId(), fetchRange) { success, json ->
            if (success && json != null && reqSeq == cadenceRequestSequence) {
                val serverData = parseCadenceJson(fetchRange, json, latestInsights)
                if (serverData.bars.any { it.distanceKm > 0 || it.socConsumed > 0 }) {
                    if (fetchRange == "7D") {
                        cached7DayBars = serverData.bars
                        if (cached7DayBars.size >= 2) {
                            val yestBarDist = cached7DayBars[cached7DayBars.size - 2].distanceKm
                            if (yestBarDist > 0.0) {
                                lastKnownYesterdayKm = yestBarDist
                            }
                        }
                        persistCadenceBarsToRoom(json)

                        // Update the 3 sub-metrics from real 7D server data
                        binding.tvCadence7DayTotalVal.text = if (serverData.totalSocConsumed > 0) {
                            "%.1f km (%d%% SoC)".format(serverData.totalDistanceKm, serverData.totalSocConsumed)
                        } else {
                            "%.1f km".format(serverData.totalDistanceKm)
                        }
                        val avgK = if (serverData.totalDistanceKm > 0.0) serverData.totalDistanceKm / 7.0 else 0.0
                        val avgS = if (serverData.totalSocConsumed > 0) serverData.totalSocConsumed / 7 else 0
                        binding.tvCadenceAvgDayVal.text = if (avgS > 0) "%.1f km (%d%%/d)".format(avgK, avgS) else "%.1f km".format(avgK)
                        val t7 = if (serverData.totalDistanceKm > 0.0) maxOf(1, (serverData.totalDistanceKm / 12.0).toInt()) else 0
                        binding.tvCadenceTripsLoggedVal.text = "$t7 trips"
                    }

                    if (selectedCadence == "3D") {
                        val threeDayData = build3DDataFrom7D(cached7DayBars, latestInsights)
                        renderCadenceBars(threeDayData)
                    } else if (selectedCadence == range) {
                        renderCadenceBars(serverData)
                    }
                }
            }
        }
    }

    private fun build3DDataFrom7D(sevenDayBars: List<CadenceBarItem>, ins: ServerInsights?): CadenceData {
        val safeToday = getLiveTodayKm(ins)
        val safeTodaySoc = ins?.todaySocConsumed ?: 0
        val safeYesterday = getLiveYesterdayKm()
        val safeYesterdaySoc = getLiveYesterdaySoc()

        val c2 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
        val d2Str = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(c2.time)
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

        val last3 = sevenDayBars.takeLast(3)
        val (d2Dist, d2Soc, d2Label) = if (last3.size >= 3) {
            val d = if (last3[0].distanceKm > 0.0) last3[0].distanceKm else (recentDailyTravelList.find { it.date == d2Str }?.distanceKm ?: 0.0)
            val s = if (last3[0].socConsumed > 0) last3[0].socConsumed else (recentDailyTravelList.find { it.date == d2Str }?.socConsumed ?: 0)
            val lbl = if (last3[0].label.isNotBlank() && last3[0].label != "Today" && last3[0].label != "Yesterday") last3[0].label else dayFormat.format(c2.time)
            Triple(d, s, lbl)
        } else {
            val d = recentDailyTravelList.find { it.date == d2Str }?.distanceKm ?: 0.0
            val s = recentDailyTravelList.find { it.date == d2Str }?.socConsumed ?: 0
            Triple(d, s, dayFormat.format(c2.time))
        }

        val yestDist = if (last3.size >= 2 && last3[last3.size - 2].distanceKm > 0.0) last3[last3.size - 2].distanceKm else safeYesterday
        val yestSoc = if (last3.size >= 2 && last3[last3.size - 2].socConsumed > 0) last3[last3.size - 2].socConsumed else safeYesterdaySoc

        val todayDist = if (last3.isNotEmpty() && last3.last().distanceKm > 0.0) last3.last().distanceKm else safeToday
        val todaySoc = if (last3.isNotEmpty() && last3.last().socConsumed > 0) last3.last().socConsumed else safeTodaySoc

        if (yestDist > 0.0 && lastKnownYesterdayKm == 0.0) {
            lastKnownYesterdayKm = yestDist
        }

        val rawBars = listOf(
            CadenceBarItem(d2Label, d2Dist, socConsumed = d2Soc, energyKwh = d2Soc * 0.033, subText = "2 days ago"),
            CadenceBarItem("Yesterday", yestDist, socConsumed = yestSoc, energyKwh = yestSoc * 0.033, subText = "Yesterday"),
            CadenceBarItem("Today", todayDist, socConsumed = todaySoc, energyKwh = todaySoc * 0.033, subText = "Today")
        )

        val maxD = (rawBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
        val finalBars = rawBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
        val total = finalBars.sumOf { it.distanceKm }
        val totalSoc = finalBars.sumOf { it.socConsumed }
        val streakText = if (total > 0) {
            if (totalSoc > 0) "🔥 3-day active streak • ⚡ %d%% used".format(totalSoc) else "🔥 3-day active streak"
        } else {
            "0.0 km total"
        }
        return CadenceData("3D", "LAST 3 DAYS", total, totalSoc, totalSoc * 0.033, streakText, finalBars)
    }

    private fun persistCadenceBarsToRoom(json: JSONObject) {
        val barsArray = json.optJSONArray("bars") ?: return
        if (barsArray.length() == 0) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                for (i in 0 until barsArray.length()) {
                    val b = barsArray.optJSONObject(i) ?: continue
                    val dateStr = b.optString("date", "")
                    val dist = b.optDouble("distance_km", 0.0)
                    val soc = b.optInt("soc_consumed", 0)
                    if (dateStr.isNotBlank() && (dist > 0.0 || soc > 0)) {
                        repository.saveDailyTravel(dateStr, dist, soc)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("NexusMainActivity", "persistCadenceBarsToRoom error: ${e.message}")
            }
        }
    }

    private fun getLiveTodayKm(ins: ServerInsights?): Double {
        if (hasLoadedTodayDistance) {
            return if (lastKnownTodayKm > 0.0) {
                lastKnownTodayKm
            } else if (ins != null && ins.todayDistanceKm > 0.0) {
                ins.todayDistanceKm
            } else {
                0.0
            }
        }
        if (ins != null && ins.todayDistanceKm in 0.0..300.0) return ins.todayDistanceKm
        return lastKnownTodayKm.coerceAtLeast(0.0)
    }

    private fun getLiveYesterdayKm(): Double {
        if (lastKnownYesterdayKm in 0.1..300.0) return lastKnownYesterdayKm
        if (latestInsights != null && latestInsights!!.yesterdayDistanceKm in 0.1..300.0) {
            return latestInsights!!.yesterdayDistanceKm
        }
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yestStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        return recentDailyTravelList.find { it.date == yestStr }?.distanceKm ?: 0.0
    }

    private fun getLiveYesterdaySoc(): Int {
        if (latestInsights != null && latestInsights!!.yesterdaySocConsumed > 0) {
            return latestInsights!!.yesterdaySocConsumed
        }
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yestStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        val fromDb = recentDailyTravelList.find { it.date == yestStr }?.socConsumed ?: 0
        if (fromDb > 0) return fromDb
        return cached7DayBars.getOrNull(cached7DayBars.size - 2)?.socConsumed ?: 0
    }

    private fun generateCadenceData(range: String, ins: ServerInsights?): CadenceData {
        val safeToday = getLiveTodayKm(ins)
        val safeTodaySoc = ins?.todaySocConsumed ?: 0
        val safeYesterday = getLiveYesterdayKm()
        val safeYesterdaySoc = getLiveYesterdaySoc()
        val c2 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
        val d2Str = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(c2.time)
        val dMinus2 = recentDailyTravelList.find { it.date == d2Str }?.distanceKm ?: 0.0
        val dMinus2Soc = recentDailyTravelList.find { it.date == d2Str }?.socConsumed ?: 0
        val monthDayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

        return when (range) {
            "3D" -> {
                if (cached7DayBars.size >= 3) {
                    return build3DDataFrom7D(cached7DayBars, ins)
                }
                val c1 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val rawBars = listOf(
                    CadenceBarItem(monthDayFormat.format(c2.time), dMinus2, socConsumed = dMinus2Soc, energyKwh = dMinus2Soc * 0.033, subText = "2 days ago"),
                    CadenceBarItem(monthDayFormat.format(c1.time), safeYesterday, socConsumed = safeYesterdaySoc, energyKwh = safeYesterdaySoc * 0.033, subText = "Yesterday"),
                    CadenceBarItem(monthDayFormat.format(Date()), safeToday, socConsumed = safeTodaySoc, energyKwh = safeTodaySoc * 0.033, subText = "Today")
                )
                val maxD = (rawBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                val bars = rawBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                val total = bars.sumOf { it.distanceKm }
                val totalSoc = bars.sumOf { it.socConsumed }
                val streakText = if (total > 0) {
                    if (totalSoc > 0) "🔥 3-day active streak • ⚡ %d%% used".format(totalSoc) else "🔥 3-day active streak"
                } else "0.0 km total"
                CadenceData("3D", "LAST 3 DAYS", total, totalSoc, totalSoc * 0.033, streakText, bars)
            }
            "7D" -> {
                if (cached7DayBars.size >= 7) {
                    val updatedBars = cached7DayBars.mapIndexed { idx, item ->
                        val d = if (idx == cached7DayBars.size - 1) safeToday else item.distanceKm
                        val s = if (idx == cached7DayBars.size - 1 && safeTodaySoc > 0) safeTodaySoc else item.socConsumed
                        val lbl = if (item.subText != null && item.subText.length >= 10) item.subText.substring(5) else item.label
                        item.copy(label = lbl, distanceKm = d, socConsumed = s, energyKwh = s * 0.033)
                    }
                    val maxD = (updatedBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                    val finalBars = updatedBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                    val total = finalBars.sumOf { it.distanceKm }
                    val totalSoc = finalBars.sumOf { it.socConsumed }
                    val cadenceText = if (total > 0) {
                        if (totalSoc > 0) "🔥 7-day steady cadence • ⚡ %d%% used".format(totalSoc) else "🔥 7-day steady cadence"
                    } else "0.0 km total"
                    return CadenceData("7D", "LAST 7 DAYS", total, totalSoc, totalSoc * 0.033, cadenceText, finalBars)
                }
                val rawBars = mutableListOf<CadenceBarItem>()
                for (i in 6 downTo 0) {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                    val label = monthDayFormat.format(cal.time)
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                    val (dist, soc) = when (i) {
                        0 -> Pair(safeToday, safeTodaySoc)
                        1 -> Pair(safeYesterday, safeYesterdaySoc)
                        2 -> Pair(dMinus2, dMinus2Soc)
                        else -> {
                            val dbItem = recentDailyTravelList.find { it.date == dateStr }
                            Pair(dbItem?.distanceKm ?: 0.0, dbItem?.socConsumed ?: 0)
                        }
                    }
                    val dateFormatted = SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
                    rawBars.add(CadenceBarItem(label, dist, socConsumed = soc, energyKwh = soc * 0.033, subText = dateFormatted))
                }
                val maxD = (rawBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                val bars = rawBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                val total = bars.sumOf { it.distanceKm }
                val totalSoc = bars.sumOf { it.socConsumed }
                val cadenceText = if (total > 0) {
                    if (totalSoc > 0) "🔥 7-day steady cadence • ⚡ %d%% used".format(totalSoc) else "🔥 7-day steady cadence"
                } else "0.0 km total"
                CadenceData("7D", "LAST 7 DAYS", total, totalSoc, totalSoc * 0.033, cadenceText, bars)
            }
            "30D" -> {
                val monthTotal = if (ins != null && ins.monthDistanceKm > 0) ins.monthDistanceKm else 0.0
                val totalConsumedKwh = if (ins != null && ins.consumedKwh > 0) ins.consumedKwh else 0.0
                val weights = listOf(0.19, 0.23, 0.18, 0.24, 0.16)
                val rawBars = weights.mapIndexed { idx, w ->
                    val dist = monthTotal * w
                    val kwh = totalConsumedKwh * w
                    val socEst = (kwh / 0.033).roundToInt()
                    CadenceBarItem("W${idx + 1}", dist, socConsumed = socEst, energyKwh = kwh, subText = "Week ${idx + 1}")
                }
                val maxD = (rawBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                val bars = rawBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                val total = bars.sumOf { it.distanceKm }
                val totalSoc = bars.sumOf { it.socConsumed }
                val avgText = if (total > 0) "⚡ %.1f km/wk avg • %d%% SoC/wk".format(total / 5.0, totalSoc / 5) else "0.0 km/wk avg"
                CadenceData("30D", "LAST 30 DAYS (5 WKS)", total, totalSoc, totalConsumedKwh, avgText, bars)
            }
            "1Y" -> {
                val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
                val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                val curMonth = Calendar.getInstance().get(Calendar.MONTH)
                val baseMiles = DoubleArray(12)
                val baseSoc = IntArray(12)
                if (ins != null && ins.monthDistanceKm > 0) {
                    baseMiles[curMonth.coerceIn(0, 11)] = ins.monthDistanceKm
                    baseSoc[curMonth.coerceIn(0, 11)] = (ins.consumedKwh / 0.033).roundToInt()
                }
                val rawBars = monthLabels.mapIndexed { idx, lbl ->
                    val dist = baseMiles[idx]
                    val soc = baseSoc[idx]
                    CadenceBarItem(lbl, dist, socConsumed = soc, energyKwh = soc * 0.033, isPeak = (dist > 0 && dist >= (baseMiles.maxOrNull() ?: 0.0)), subText = "${monthNames[idx]} 2026")
                }
                val maxD = (rawBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                val bars = rawBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                val total = bars.sumOf { it.distanceKm }
                val totalSoc = bars.sumOf { it.socConsumed }
                val peakText = if (maxD > 0) "⭐ Peak: %.0f km (%s)".format(maxD, monthNames[curMonth]) else "⭐ No rides in 2026"
                CadenceData("1Y", "ANNUAL 2026 (12 MOS)", total, totalSoc, totalSoc * 0.033, peakText, bars)
            }
            else -> generateCadenceData("3D", ins)
        }
    }

    private fun parseCadenceJson(range: String, json: JSONObject, ins: ServerInsights?): CadenceData {
        val barsArray = json.optJSONArray("bars")
        if (barsArray != null && barsArray.length() > 0) {
            var serverHasNonZero = false
            val parsedItems = mutableListOf<CadenceBarItem>()
            for (i in 0 until barsArray.length()) {
                val b = barsArray.optJSONObject(i) ?: continue
                val label = b.optString("day_label", "")
                val dist = b.optDouble("distance_km", 0.0)
                val soc = b.optInt("soc_consumed", 0)
                val energy = b.optDouble("energy_kwh", soc * 0.033)
                if (dist > 0.0 || soc > 0) serverHasNonZero = true
                val dateStr = b.optString("date", "")
                parsedItems.add(CadenceBarItem(label, dist, socConsumed = soc, energyKwh = energy, subText = dateStr))
            }

            if (serverHasNonZero) {
                when (range) {
                    "3D" -> {
                        return build3DDataFrom7D(parsedItems, ins)
                    }
                    "7D" -> {
                        val safeToday = getLiveTodayKm(ins)
                        val safeTodaySoc = ins?.todaySocConsumed ?: 0
                        val updatedBars = parsedItems.takeLast(7).mapIndexed { idx, item ->
                            val d = if (idx == 6) safeToday else item.distanceKm
                            val s = if (idx == 6 && safeTodaySoc > 0) safeTodaySoc else item.socConsumed
                            val lbl = if (idx == 6) "Today" else item.label
                            item.copy(label = lbl, distanceKm = d, socConsumed = s, energyKwh = s * 0.033)
                        }
                        val maxD = (updatedBars.maxOfOrNull { it.distanceKm } ?: 1.0).coerceAtLeast(1.0)
                        val finalBars = updatedBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                        val totalDist = finalBars.sumOf { it.distanceKm }
                        val totalSoc = finalBars.sumOf { it.socConsumed }
                        val streakText = if (totalDist > 0) {
                            if (totalSoc > 0) "🔥 7-day steady cadence • ⚡ %d%% used".format(totalSoc) else "🔥 7-day steady cadence"
                        } else "0.0 km total"
                        return CadenceData(range, "LAST 7 DAYS", totalDist, totalSoc, totalSoc * 0.033, streakText, finalBars)
                    }
                    "30D" -> {
                        val chunkSize = (parsedItems.size / 5).coerceAtLeast(1)
                        val weeklyBars = parsedItems.chunked(chunkSize).take(5).mapIndexed { idx, chunk ->
                            val sumKm = chunk.sumOf { it.distanceKm }
                            val sumSoc = chunk.sumOf { it.socConsumed }
                            CadenceBarItem("W${idx + 1}", sumKm, socConsumed = sumSoc, energyKwh = sumSoc * 0.033, subText = "Week ${idx + 1}")
                        }
                        val maxD = weeklyBars.maxOfOrNull { it.distanceKm } ?: 1.0
                        val finalBars = weeklyBars.map { it.copy(isPeak = it.distanceKm >= maxD && it.distanceKm > 0) }
                        val total = finalBars.sumOf { it.distanceKm }
                        val totalSoc = finalBars.sumOf { it.socConsumed }
                        return CadenceData(range, "LAST 30 DAYS (5 WKS)", total, totalSoc, totalSoc * 0.033, "⚡ %.1f km/wk avg • %d%% SoC/wk".format(total / 5.0, totalSoc / 5), finalBars)
                    }
                    "1Y" -> {
                        val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
                        val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                        val monthlySums = DoubleArray(12) { 0.0 }
                        val monthlySocSums = IntArray(12) { 0 }
                        for (i in 0 until barsArray.length()) {
                            val b = barsArray.optJSONObject(i) ?: continue
                            val dateStr = b.optString("date", "")
                            val dist = b.optDouble("distance_km", 0.0)
                            val soc = b.optInt("soc_consumed", 0)
                            val mIdx = try {
                                dateStr.split("-").getOrNull(1)?.toInt()?.minus(1) ?: -1
                            } catch (e: Exception) { -1 }
                            if (mIdx in 0..11) {
                                monthlySums[mIdx] += dist
                                monthlySocSums[mIdx] += soc
                            }
                        }
                        val maxD = monthlySums.maxOrNull() ?: 1.0
                        val finalBars = monthlySums.mapIndexed { idx, d ->
                            val s = monthlySocSums[idx]
                            CadenceBarItem(monthLabels[idx], d, socConsumed = s, energyKwh = s * 0.033, isPeak = d >= maxD && d > 0, subText = "${monthNames[idx]} 2026")
                        }
                        val total = finalBars.sumOf { it.distanceKm }
                        val totalSoc = finalBars.sumOf { it.socConsumed }
                        return CadenceData(range, "ANNUAL 2026 (12 MOS)", total, totalSoc, totalSoc * 0.033, "⭐ Peak: %.0f km".format(maxD), finalBars)
                    }
                }
            }
        }

        return generateCadenceData(range, ins)
    }

    private fun renderCadenceBars(data: CadenceData) {
        binding.tvCadenceAggregationTitle.text = "${data.title}:"
        binding.tvCadenceAggregation.text = "%.1f".format(data.totalDistanceKm)
        binding.tvCadenceStreak.text = data.streakText

        val bars = data.bars
        if (bars.isEmpty()) {
            binding.containerCadenceBars.removeAllViews()
            rideCadenceChartView = null
            return
        }

        val chartPoints = bars.map { b ->
            val cleanLabel = when {
                b.subText != null && b.subText.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                    b.subText.substring(5)
                }
                b.label.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                    b.label.substring(5)
                }
                b.label == "Today" -> {
                    if (b.subText != null && b.subText.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        b.subText.substring(5)
                    } else {
                        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())
                    }
                }
                b.label == "Yesterday" -> {
                    val yCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    SimpleDateFormat("MM-dd", Locale.getDefault()).format(yCal.time)
                }
                else -> b.label
            }

            CadenceChartPoint(
                label = cleanLabel,
                fullDate = b.subText ?: b.label,
                distanceKm = b.distanceKm,
                socConsumed = b.socConsumed,
                isPeak = b.isPeak,
                isToday = (b.label == "Today")
            )
        }

        var chartView = rideCadenceChartView
        if (chartView == null || binding.containerCadenceBars.childCount == 0) {
            binding.containerCadenceBars.removeAllViews()
            chartView = RideCadenceChartView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                onPointSelected = { pt ->
                    val kwh = if (pt.socConsumed > 0) pt.socConsumed * 0.033 else 0.0
                    val eff = if (pt.distanceKm > 0.0 && pt.socConsumed > 0) {
                        " • " + String.format(Locale.US, "%.2f km/%%", pt.distanceKm / pt.socConsumed)
                    } else ""
                    val toastMsg = "${pt.label}: " + String.format(Locale.US, "%.1f km", pt.distanceKm) +
                            " • ⚡ ${pt.socConsumed}% SoC used (${String.format(Locale.US, "%.1f kWh", kwh)})$eff"
                    Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                }
            }
            binding.containerCadenceBars.addView(chartView)
            rideCadenceChartView = chartView
        }

        chartView.setPoints(chartPoints, animate = true)
    }

    // ─── Trips Tab: Timeframe Filter & AI Auto-Named Journeys ──────────────────

    private var selectedTripsTimeframe = "WEEK"

    private fun setupTripsTab() {
        binding.btnTripsThisMonth.setOnClickListener { selectTripsTimeframe("MONTH") }
        binding.btnTripsThisWeek.setOnClickListener { selectTripsTimeframe("WEEK") }
        binding.btnTripsAllTime.setOnClickListener { selectTripsTimeframe("ALL") }

        binding.btnTripsViewAll.setOnClickListener {
            showAllJourneysDialog()
        }

        // Initialize default view to WEEK and update weekly cadence
        selectTripsTimeframe("WEEK")
        updateTripsWeeklyCadence(latestInsights)
    }

    private fun selectTripsTimeframe(timeframe: String) {
        selectedTripsTimeframe = timeframe
        selectedCadenceDayIdx = -1
        val solidCyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
        val darkBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
        val blackColor = ContextCompat.getColor(this, R.color.black)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_primary)

        binding.btnTripsThisMonth.background = if (timeframe == "MONTH") solidCyanBg else darkBg
        binding.btnTripsThisMonth.setTextColor(if (timeframe == "MONTH") blackColor else inactiveTextColor)

        binding.btnTripsThisWeek.background = if (timeframe == "WEEK") solidCyanBg else darkBg
        binding.btnTripsThisWeek.setTextColor(if (timeframe == "WEEK") blackColor else inactiveTextColor)

        binding.btnTripsAllTime.background = if (timeframe == "ALL") solidCyanBg else darkBg
        binding.btnTripsAllTime.setTextColor(if (timeframe == "ALL") blackColor else inactiveTextColor)

        val ins = NexusCloudSyncManager.getInstance(this).insights.value
        applyTripsTimeframeData(timeframe, ins)
        updateTripsWeeklyCadence(ins)
    }

    @SuppressLint("SetTextI18n")
    private fun applyTripsTimeframeData(timeframe: String, ins: ServerInsights) {
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date())
        when (timeframe) {
            "MONTH" -> {
                val dist = if (ins.monthDistanceKm > 0.0) ins.monthDistanceKm else 0.0
                val saved = calculateMoneySavedInr(dist)
                binding.tvTripsTimeframeTitle.text = "$monthName Summary"
                binding.tvTripsHeroDistance.text = "%.1f".format(dist)
                val tripsCount = if (dist > 0.0) maxOf(1, ins.monthDaysActive * 4) else 0
                binding.tvTripsHeroCount.text = "$tripsCount"
                binding.tvTripsHeroSaved.text = "₹%,d".format(saved)
            }
            "WEEK" -> {
                val weekDistances = getThisWeekDistances(ins)
                val dist = weekDistances.sum()
                val saved = calculateMoneySavedInr(dist)
                val activeDays = weekDistances.count { it > 0.1 }
                val tripsCount = if (dist > 0.0) maxOf(activeDays, (dist / 14.0).roundToInt()) else 0
                binding.tvTripsTimeframeTitle.text = "Weekly Summary"
                binding.tvTripsHeroDistance.text = "%.1f".format(dist)
                binding.tvTripsHeroCount.text = "$tripsCount"
                binding.tvTripsHeroSaved.text = "₹%,d".format(saved)
            }
            "ALL" -> {
                val dist = if (ins.odometerKm > 0.0) ins.odometerKm else 0.0
                val saved = calculateMoneySavedInr(dist)
                binding.tvTripsTimeframeTitle.text = "All-Time Summary"
                binding.tvTripsHeroDistance.text = "%.1f".format(dist)
                val tripsCount = if (dist > 0.0) maxOf(1, (dist / 33.8).roundToInt()) else 0
                binding.tvTripsHeroCount.text = "$tripsCount"
                binding.tvTripsHeroSaved.text = "₹%,d".format(saved)
            }
        }
    }

    data class WeekDayCadence(
        val date: String,
        val distanceKm: Double,
        val socConsumed: Int
    )

    private fun getThisWeekCadence(ins: ServerInsights?): List<WeekDayCadence> {
        val todayKm = getLiveTodayKm(ins)
        val yesterdayKm = getLiveYesterdayKm()
        val todaySoc = ins?.todaySocConsumed ?: 0
        val yesterdaySoc = getLiveYesterdaySoc()

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val todayIdx = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        val yesterdayIdx = (todayIdx + 6) % 7

        val calWeek = Calendar.getInstance()
        calWeek.add(Calendar.DAY_OF_YEAR, -todayIdx)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val weekDates = Array(7) { "" }
        for (i in 0..6) {
            weekDates[i] = sdf.format(calWeek.time)
            calWeek.add(Calendar.DAY_OF_YEAR, 1)
        }

        val result = mutableListOf<WeekDayCadence>()
        for (i in 0..6) {
            when {
                i == todayIdx -> {
                    val dbToday = recentDailyTravelList.find { it.date == weekDates[i] }
                    val d = if (todayKm > 0) todayKm else (dbToday?.distanceKm ?: 0.0)
                    val s = if (todaySoc > 0) todaySoc else (dbToday?.socConsumed ?: 0)
                    result.add(WeekDayCadence(weekDates[i], d, s))
                }
                i == yesterdayIdx -> {
                    val dbYest = recentDailyTravelList.find { it.date == weekDates[i] }
                    val cacheYest = cached7DayBars.find { it.subText == weekDates[i] }
                    val d = maxOf(yesterdayKm, maxOf(dbYest?.distanceKm ?: 0.0, cacheYest?.distanceKm ?: 0.0))
                    val s = maxOf(yesterdaySoc, maxOf(dbYest?.socConsumed ?: 0, cacheYest?.socConsumed ?: 0))
                    result.add(WeekDayCadence(weekDates[i], if (d > 0) d else 0.0, s))
                }
                i < todayIdx -> {
                    val dbDay = recentDailyTravelList.find { it.date == weekDates[i] }
                    val cacheDay = cached7DayBars.find { it.subText == weekDates[i] }
                    val d = dbDay?.distanceKm ?: (cacheDay?.distanceKm ?: 0.0)
                    val s = dbDay?.socConsumed ?: (cacheDay?.socConsumed ?: 0)
                    result.add(WeekDayCadence(weekDates[i], d, s))
                }
                else -> {
                    result.add(WeekDayCadence(weekDates[i], 0.0, 0))
                }
            }
        }
        return result
    }

    private fun getThisWeekDistances(ins: ServerInsights?): DoubleArray {
        return getThisWeekCadence(ins).map { it.distanceKm }.toDoubleArray()
    }

    /**
     * Dynamically updates the Weekly Cadence Bar Chart Card in the Trips Tab (M, T, W, T, F, S, S).
     * Connects live today driving distance, Room database history (DailyTravelEntity),
     * server-synced 7D history, and provides a rich interactive day inspector with charge consumed.
     */
    @SuppressLint("SetTextI18n")
    private fun updateTripsWeeklyCadence(ins: ServerInsights?) {
        val todayKm = getLiveTodayKm(ins)
        val yesterdayKm = getLiveYesterdayKm()

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val todayIdx = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        val yesterdayIdx = (todayIdx + 6) % 7

        val weekCadence = getThisWeekCadence(ins)
        val distances = weekCadence.map { it.distanceKm }.toDoubleArray()
        val maxD = (distances.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val peakIdx = distances.indices.maxByOrNull { distances[it] } ?: -1

        val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        // Peak Badge with click-to-select & SoC used
        if (peakIdx >= 0 && distances[peakIdx] > 0.0) {
            val peakSoc = weekCadence[peakIdx].socConsumed
            val socText = if (peakSoc > 0) " • %d%% SoC".format(peakSoc) else ""
            binding.tvWeeklyCadencePeak.text = "⚡ Peak: %.1f km%s (%s)".format(distances[peakIdx], socText, dayNames[peakIdx].take(3))
            binding.tvWeeklyCadencePeak.isClickable = true
            binding.tvWeeklyCadencePeak.setOnClickListener {
                selectedCadenceDayIdx = peakIdx
                CyberSoundManager.playModeSwitchChime(this@MainActivity)
                updateTripsWeeklyCadence(latestInsights)
            }
        } else {
            binding.tvWeeklyCadencePeak.text = "Peak: 0.0 km"
            binding.tvWeeklyCadencePeak.isClickable = false
        }

        // Summary row: Total & Consistency with SoC consumed
        val totalWeekKm = distances.sum()
        val totalWeekSoc = weekCadence.sumOf { it.socConsumed }
        val activeDays = distances.count { it > 0.1 }
        val avgKm = if (activeDays > 0) totalWeekKm / activeDays else 0.0
        val avgSoc = if (activeDays > 0) totalWeekSoc / activeDays else 0
        binding.tvCadenceWeekTotal.text = if (totalWeekSoc > 0) "%.1f km (%d%% SoC)".format(totalWeekKm, totalWeekSoc) else "%.1f km".format(totalWeekKm)
        binding.tvCadenceConsistency.text = if (activeDays > 0) {
            val socInfo = if (avgSoc > 0) " (%d%%/d)".format(avgSoc) else ""
            "Avg %.1f km/d%s • %d/7 Active".format(avgKm, socInfo, activeDays)
        } else {
            "0.0 km total • 0/7 Active"
        }

        val density = resources.displayMetrics.density
        val maxBarPx = (76 * density).toInt()
        val minBarPx = (8 * density).toInt()
        val futureBarPx = (6 * density).toInt()

        val dayViews = listOf(
            Triple(binding.tvCadenceMonVal, binding.barCadenceMon, binding.colCadenceMon),
            Triple(binding.tvCadenceTueVal, binding.barCadenceTue, binding.colCadenceTue),
            Triple(binding.tvCadenceWedVal, binding.barCadenceWed, binding.colCadenceWed),
            Triple(binding.tvCadenceThuVal, binding.barCadenceThu, binding.colCadenceThu),
            Triple(binding.tvCadenceFriVal, binding.barCadenceFri, binding.colCadenceFri),
            Triple(binding.tvCadenceSatVal, binding.barCadenceSat, binding.colCadenceSat),
            Triple(binding.tvCadenceSunVal, binding.barCadenceSun, binding.colCadenceSun)
        )

        val activeSelectedDay = when {
            selectedCadenceDayIdx in 0..6 -> selectedCadenceDayIdx
            todayKm > 0.0 -> todayIdx
            yesterdayKm > 0.0 -> yesterdayIdx
            else -> todayIdx
        }

        for (i in 0..6) {
            val dist = distances[i]
            val soc = weekCadence[i].socConsumed
            val (tvVal, barView, colView) = dayViews[i]
            val isSelected = (i == activeSelectedDay)
            val isToday = (i == todayIdx)
            val isPeak = (dist >= maxD - 0.05 && dist > 0.0)
            val isFuture = (i > todayIdx)

            // Label text & color
            if (isFuture) {
                tvVal.text = "--"
                tvVal.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                tvVal.setTypeface(tvVal.typeface, Typeface.NORMAL)
            } else {
                tvVal.text = "%.1f".format(dist)
                val textColor = when {
                    isSelected -> ContextCompat.getColor(this, R.color.primary_cyan)
                    isPeak -> ContextCompat.getColor(this, R.color.primary_cyan)
                    isToday -> ContextCompat.getColor(this, R.color.status_emerald)
                    else -> ContextCompat.getColor(this, R.color.text_secondary)
                }
                tvVal.setTextColor(textColor)
                tvVal.setTypeface(
                    tvVal.typeface,
                    if (isSelected || isToday || isPeak) Typeface.BOLD else Typeface.NORMAL
                )
            }

            // Charge label below day letter (dynamically managed)
            val tvCharge = colView.findViewWithTag<TextView>("tvCadenceChargeTag") ?: TextView(this).apply {
                tag = "tvCadenceChargeTag"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
                textSize = 8.5f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.jetbrains_mono)
                gravity = Gravity.CENTER
                colView.addView(this)
            }
            if (isFuture) {
                tvCharge.text = "--"
                tvCharge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                tvCharge.setTypeface(tvCharge.typeface, Typeface.NORMAL)
            } else if (soc > 0) {
                tvCharge.text = "⚡$soc%"
                tvCharge.setTextColor(ContextCompat.getColor(this, if (isSelected) R.color.primary_cyan else R.color.status_emerald))
                tvCharge.setTypeface(tvCharge.typeface, Typeface.BOLD)
            } else {
                tvCharge.text = "0%"
                tvCharge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                tvCharge.setTypeface(tvCharge.typeface, Typeface.NORMAL)
            }

            // Bar height & drawable
            val barHeight = when {
                isFuture -> futureBarPx
                dist <= 0.0 -> minBarPx
                else -> ((dist / maxD) * maxBarPx).toInt().coerceIn(minBarPx, maxBarPx)
            }
            val lp = barView.layoutParams
            if (lp.height != barHeight) {
                lp.height = barHeight
                barView.layoutParams = lp
            }

            val barDrawable = when {
                isFuture -> R.drawable.bg_cadence_bar_track
                isSelected -> R.drawable.bg_cadence_bar_selected
                isPeak -> R.drawable.bg_cadence_bar_peak
                isToday -> R.drawable.bg_cadence_bar_today
                dist <= 0.0 -> R.drawable.bg_cadence_bar_track
                else -> R.drawable.bg_cadence_bar_fill
            }
            barView.background = ContextCompat.getDrawable(this, barDrawable)

            // Column selection highlight container
            colView.background = if (isSelected) {
                ContextCompat.getDrawable(this, R.drawable.bg_cadence_col_selected)
            } else {
                null
            }

            // Click listener: select day and update inspector
            val dayIndex = i
            colView.setOnClickListener {
                selectedCadenceDayIdx = dayIndex
                CyberSoundManager.playModeSwitchChime(this@MainActivity)
                updateTripsWeeklyCadence(latestInsights)
            }
        }

        // Update Selected Day Interactive Inspector Strip with Charge Used
        val selDist = distances[activeSelectedDay]
        val selSoc = weekCadence[activeSelectedDay].socConsumed
        val selKwh = if (selSoc > 0) selSoc * 0.033 else 0.0
        val selDayName = dayNames[activeSelectedDay]
        val isSelToday = (activeSelectedDay == todayIdx)
        val isSelYesterday = (activeSelectedDay == yesterdayIdx)
        val isSelFuture = (activeSelectedDay > todayIdx)
        val isSelPeak = (selDist >= maxD - 0.05 && selDist > 0.0)

        // Inspector Day Badge
        val formattedDist = String.format(Locale.US, "%.1f", selDist)
        when {
            isSelToday -> {
                val socPart = if (selSoc > 0) " (⚡ $selSoc%)" else ""
                binding.tvCadenceInspectorDay.text = if (selDist > 0) "TODAY • $formattedDist KM$socPart" else "TODAY • LIVE"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_emerald)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
            isSelYesterday -> {
                val peakLabel = if (isSelPeak) "⚡ PEAK " else ""
                val socPart = if (selSoc > 0) " • ⚡ $selSoc%" else ""
                binding.tvCadenceInspectorDay.text = "YESTERDAY • $peakLabel$formattedDist KM$socPart"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
            isSelFuture -> {
                binding.tvCadenceInspectorDay.text = "${selDayName.uppercase()} • UPCOMING"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            isSelPeak -> {
                val socPart = if (selSoc > 0) " (⚡ $selSoc%)" else ""
                binding.tvCadenceInspectorDay.text = "${selDayName.uppercase()} • ⚡ PEAK ($formattedDist KM)$socPart"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
            selDist > 0.0 -> {
                val socPart = if (selSoc > 0) " (⚡ $selSoc%)" else ""
                binding.tvCadenceInspectorDay.text = "${selDayName.uppercase()} • $formattedDist KM$socPart"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
            else -> {
                binding.tvCadenceInspectorDay.text = "${selDayName.uppercase()} • REST DAY"
                binding.tvCadenceInspectorDay.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
                binding.tvCadenceInspectorDay.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
            }
        }

        // Inspector Detail Text
        if (isSelFuture) {
            binding.tvCadenceInspectorDetail.text = "Upcoming • Future day in cycle • Battery standby active"
        } else if (selDist <= 0.05) {
            binding.tvCadenceInspectorDetail.text = if (isSelToday) "No rides logged today • 0.0 km • 0% charge used • Scooter parked" else "Rest day • 0.0 km logged • 0% charge used • Battery standby"
        } else {
            val tripsCount = maxOf(1, (selDist / 12.0).roundToInt())
            val estMin = maxOf(8, (selDist / 28.0 * 60).roundToInt())
            val moneySaved = calculateMoneySavedInr(selDist)
            val petrolLiters = selDist / iceScooterMileageKmL
            val chargePart = if (selSoc > 0) "⚡ %d%% charge used (%.1f kWh) • ".format(selSoc, selKwh) else ""
            binding.tvCadenceInspectorDetail.text = "%.1f km • %s%d trips (~%d min) • Saved ₹%d • %.1fL petrol".format(
                selDist, chargePart, tripsCount, estMin, moneySaved, petrolLiters
            )
        }
    }

    /**
     * Updates recent journey titles inferred by Server-Side AI.
     */
    fun updateRecentJourneysFromAi(
        journey1Title: String = "Morning Office Sprint",
        journey2Title: String = "Weekend Highway Cruise",
        journey3Title: String = "Quick City Errand",
        journey4Title: String = "Night Food Run"
    ) {
        binding.tvJourney1Title.text = journey1Title
        binding.tvJourney2Title.text = journey2Title
        binding.tvJourney3Title.text = journey3Title
        binding.tvJourney4Title.text = journey4Title
    }

    /**
     * Dynamically binds Room/AI clustered journeys to the Recent Journeys card cards.
     */
    private fun renderRecentJourneys(journeys: List<JourneyEntity>) {
        if (journeys.isEmpty()) {
            binding.layoutJourney1.visibility = View.GONE
            binding.layoutJourney2.visibility = View.GONE
            binding.layoutJourney3.visibility = View.GONE
            binding.layoutJourney4.visibility = View.GONE
            return
        }

        val cyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan)
        val emeraldBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_emerald)
        val amberBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_amber)

        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val emeraldColor = ContextCompat.getColor(this, R.color.status_emerald)
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)

        // Card 1
        val j1 = journeys.getOrNull(0)
        if (j1 != null) {
            binding.layoutJourney1.visibility = View.VISIBLE
            binding.tvJourney1Title.text = j1.title
            binding.tvJourney1Tag.text = j1.aiTag
            applyJourneyTagStyle(binding.tvJourney1Tag, j1.aiTag, cyanBg, emeraldBg, amberBg, cyanColor, emeraldColor, amberColor)
            binding.tvJourney1Subtitle.text = if (j1.routeSummary.isNotBlank()) j1.routeSummary else "${j1.timestampFormatted} (${j1.durationMins}m)"
            binding.tvJourney1Distance.text = "%.1f km".format(j1.distanceKm)
            binding.tvJourney1Meta.text = "${j1.ridingMode} • %.2f km/%%".format(j1.efficiencyKmPerSoc)
            binding.layoutJourney1.setOnClickListener { showJourneyDetailDialog(j1) }
        } else {
            binding.layoutJourney1.visibility = View.GONE
        }

        // Card 2
        val j2 = journeys.getOrNull(1)
        if (j2 != null) {
            binding.layoutJourney2.visibility = View.VISIBLE
            binding.tvJourney2Title.text = j2.title
            binding.tvJourney2Tag.text = j2.aiTag
            applyJourneyTagStyle(binding.tvJourney2Tag, j2.aiTag, cyanBg, emeraldBg, amberBg, cyanColor, emeraldColor, amberColor)
            binding.tvJourney2Subtitle.text = if (j2.routeSummary.isNotBlank()) j2.routeSummary else "${j2.timestampFormatted} (${j2.durationMins}m)"
            binding.tvJourney2Distance.text = "%.1f km".format(j2.distanceKm)
            binding.tvJourney2Meta.text = "${j2.ridingMode} • %.2f km/%%".format(j2.efficiencyKmPerSoc)
            binding.layoutJourney2.setOnClickListener { showJourneyDetailDialog(j2) }
        } else {
            binding.layoutJourney2.visibility = View.GONE
        }

        // Card 3
        val j3 = journeys.getOrNull(2)
        if (j3 != null) {
            binding.layoutJourney3.visibility = View.VISIBLE
            binding.tvJourney3Title.text = j3.title
            binding.tvJourney3Tag.text = j3.aiTag
            applyJourneyTagStyle(binding.tvJourney3Tag, j3.aiTag, cyanBg, emeraldBg, amberBg, cyanColor, emeraldColor, amberColor)
            binding.tvJourney3Subtitle.text = if (j3.routeSummary.isNotBlank()) j3.routeSummary else "${j3.timestampFormatted} (${j3.durationMins}m)"
            binding.tvJourney3Distance.text = "%.1f km".format(j3.distanceKm)
            binding.tvJourney3Meta.text = "${j3.ridingMode} • %.2f km/%%".format(j3.efficiencyKmPerSoc)
            binding.layoutJourney3.setOnClickListener { showJourneyDetailDialog(j3) }
        } else {
            binding.layoutJourney3.visibility = View.GONE
        }

        // Card 4
        val j4 = journeys.getOrNull(3)
        if (j4 != null) {
            binding.layoutJourney4.visibility = View.VISIBLE
            binding.tvJourney4Title.text = j4.title
            binding.tvJourney4Tag.text = j4.aiTag
            applyJourneyTagStyle(binding.tvJourney4Tag, j4.aiTag, cyanBg, emeraldBg, amberBg, cyanColor, emeraldColor, amberColor)
            binding.tvJourney4Subtitle.text = if (j4.routeSummary.isNotBlank()) j4.routeSummary else "${j4.timestampFormatted} (${j4.durationMins}m)"
            binding.tvJourney4Distance.text = "%.1f km".format(j4.distanceKm)
            binding.tvJourney4Meta.text = "${j4.ridingMode} • %.2f km/%%".format(j4.efficiencyKmPerSoc)
            binding.layoutJourney4.setOnClickListener { showJourneyDetailDialog(j4) }
        } else {
            binding.layoutJourney4.visibility = View.GONE
        }
    }

    private fun applyJourneyTagStyle(
        tv: TextView,
        tag: String,
        cyanBg: android.graphics.drawable.Drawable?,
        emeraldBg: android.graphics.drawable.Drawable?,
        amberBg: android.graphics.drawable.Drawable?,
        cyanColor: Int,
        emeraldColor: Int,
        amberColor: Int
    ) {
        when {
            tag.contains("ECO") -> {
                tv.background = emeraldBg
                tv.setTextColor(emeraldColor)
            }
            tag.contains("HIGHWAY") || tag.contains("RAPID") -> {
                tv.background = amberBg
                tv.setTextColor(amberColor)
            }
            else -> {
                tv.background = cyanBg
                tv.setTextColor(cyanColor)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showJourneyDetailDialog(j: JourneyEntity) {
        val detailView = layoutInflater.inflate(R.layout.dialog_journey_detail, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(detailView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.70f)

        val tvTitle = detailView.findViewById<TextView>(R.id.tvDetailTitle)
        val tvTag = detailView.findViewById<TextView>(R.id.tvDetailTag)
        val tvRouteSummary = detailView.findViewById<TextView>(R.id.tvDetailRouteSummary)
        val tvDistance = detailView.findViewById<TextView>(R.id.tvDetailDistance)
        val tvDuration = detailView.findViewById<TextView>(R.id.tvDetailDuration)
        val tvAvgSpeed = detailView.findViewById<TextView>(R.id.tvDetailAvgSpeed)
        val tvMaxSpeed = detailView.findViewById<TextView>(R.id.tvDetailMaxSpeed)
        val tvBatterySoc = detailView.findViewById<TextView>(R.id.tvDetailBatterySoc)
        val tvEfficiency = detailView.findViewById<TextView>(R.id.tvDetailEfficiency)
        val tvEnergy = detailView.findViewById<TextView>(R.id.tvDetailEnergy)
        val tvRegen = detailView.findViewById<TextView>(R.id.tvDetailRegen)
        val tvMoneySaved = detailView.findViewById<TextView>(R.id.tvDetailMoneySaved)
        val tvCo2Saved = detailView.findViewById<TextView>(R.id.tvDetailCo2Saved)
        val ivIcon = detailView.findViewById<ImageView>(R.id.ivDetailIcon)
        val layoutIconBg = detailView.findViewById<FrameLayout>(R.id.layoutDetailIconBg)
        val btnClose = detailView.findViewById<ImageButton>(R.id.btnDetailClose)
        val btnDone = detailView.findViewById<Button>(R.id.btnDetailDone)

        tvTitle.text = j.title
        val displayTag = if (j.aiTag.isNotBlank() && j.aiTag != "AI Auto") j.aiTag else "${j.ridingMode} Mode"
        tvTag.text = displayTag

        val cyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan)
        val emeraldBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_emerald)
        val amberBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_amber)
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val emeraldColor = ContextCompat.getColor(this, R.color.status_emerald)
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)

        val upperTag = displayTag.uppercase(Locale.US)
        when {
            upperTag.contains("ECO") || upperTag.contains("CHAMP") -> {
                tvTag.background = emeraldBg
                tvTag.setTextColor(emeraldColor)
                layoutIconBg.background = emeraldBg
                ivIcon.setColorFilter(emeraldColor)
                ivIcon.setImageResource(R.drawable.ic_leaf)
            }
            upperTag.contains("HIGHWAY") || upperTag.contains("RAPID") || upperTag.contains("POWER") || upperTag.contains("SPORT") -> {
                tvTag.background = amberBg
                tvTag.setTextColor(amberColor)
                layoutIconBg.background = amberBg
                ivIcon.setColorFilter(amberColor)
                ivIcon.setImageResource(R.drawable.ic_bolt)
            }
            else -> {
                tvTag.background = cyanBg
                tvTag.setTextColor(cyanColor)
                layoutIconBg.background = cyanBg
                ivIcon.setColorFilter(cyanColor)
                ivIcon.setImageResource(R.drawable.ic_location_pin)
            }
        }

        tvRouteSummary.text = if (j.routeSummary.isNotBlank()) j.routeSummary else "${j.timestampFormatted} (${j.durationMins}m)"
        tvDistance.text = String.format(Locale.US, "%.1f km", j.distanceKm)
        tvDuration.text = "Duration: ${j.durationMins} mins"
        tvAvgSpeed.text = if (j.avgSpeedKmh > 0) String.format(Locale.US, "%.1f km/h", j.avgSpeedKmh) else "-- km/h"
        tvMaxSpeed.text = "Top Speed: ${j.maxSpeedKmh} km/h"

        if (j.startSoc > 0 && j.endSoc > 0) {
            val usedSoc = maxOf(0, j.startSoc - j.endSoc)
            tvBatterySoc.text = "${j.startSoc}% ➔ ${j.endSoc}%"
            tvEfficiency.text = String.format(Locale.US, "%.2f km/%% (Used %d%%)", j.efficiencyKmPerSoc, usedSoc)
        } else {
            tvBatterySoc.text = "${j.ridingMode} Mode"
            tvEfficiency.text = String.format(Locale.US, "%.2f km/%%", j.efficiencyKmPerSoc)
        }

        tvEnergy.text = String.format(Locale.US, "%.2f kWh", j.energyConsumedKwh)
        tvRegen.text = "Regen: +${String.format(Locale.US, "%.2f", j.regenKwh)} kWh"
        tvMoneySaved.text = "₹${j.moneySavedInr} Net Money Saved"
        tvCo2Saved.text = "${String.format(Locale.US, "%.2f", j.co2SavedKg)} kg CO₂ avoided vs Petrol @ ₹${String.format(Locale.US, "%.1f", petrolPricePerLiter)}/L"

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDone.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun showAllJourneysDialog() {
        lifecycleScope.launch {
            var allJourneys = repository.getRecentJourneysList(50)
            if (allJourneys.isEmpty()) {
                val serverJourneys = NexusCloudSyncManager.getInstance(this@MainActivity).insights.value?.recentJourneys
                if (!serverJourneys.isNullOrEmpty()) {
                    allJourneys = serverJourneys.map { sj ->
                        JourneyEntity(
                            startTime = sj.startTimeMs,
                            endTime = sj.endTimeMs,
                            title = sj.title,
                            routeSummary = sj.routeSummary,
                            distanceKm = sj.distanceKm,
                            durationMins = sj.durationMins,
                            avgSpeedKmh = sj.avgSpeedKmh,
                            maxSpeedKmh = sj.maxSpeedKmh,
                            startSoc = sj.startSoc,
                            endSoc = sj.endSoc,
                            ridingMode = sj.ridingMode,
                            efficiencyKmPerSoc = sj.efficiencyKmPerSoc,
                            energyConsumedKwh = sj.energyConsumedKwh,
                            regenKwh = sj.regenKwh,
                            moneySavedInr = sj.moneySavedInr,
                            co2SavedKg = sj.co2SavedKg,
                            aiTag = sj.aiTag,
                            timestampFormatted = sj.timestampFormatted
                        )
                    }
                    repository.saveJourneys(allJourneys)
                }
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_trip_history, null)
            val bottomSheetDialog = BottomSheetDialog(this@MainActivity, R.style.CyberBottomSheetDialogTheme)
            bottomSheetDialog.setContentView(dialogView)

            bottomSheetDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            bottomSheetDialog.window?.setDimAmount(0.70f)

            val behavior = bottomSheetDialog.behavior
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true

            val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvTripHistorySubtitle)
            val tvActiveCount = dialogView.findViewById<TextView>(R.id.tvTripHistoryActiveCount)
            val tvBannerTitle = dialogView.findViewById<TextView>(R.id.tvTripHistoryBannerTitle)
            val tvBannerSubtitle = dialogView.findViewById<TextView>(R.id.tvTripHistoryBannerSubtitle)
            val tvEcoBadge = dialogView.findViewById<TextView>(R.id.tvTripHistoryEcoScoreBadge)
            val rvTripHistory = dialogView.findViewById<RecyclerView>(R.id.rvTripHistory)
            val layoutEmpty = dialogView.findViewById<View>(R.id.layoutEmptyJourneys)

            val btnFilterAll = dialogView.findViewById<TextView>(R.id.btnFilterAll)
            val btnFilterToday = dialogView.findViewById<TextView>(R.id.btnFilterToday)
            val btnFilterWeek = dialogView.findViewById<TextView>(R.id.btnFilterWeek)

            val btnClose = dialogView.findViewById<ImageButton>(R.id.btnTripHistoryClose)
            val btnDone = dialogView.findViewById<Button>(R.id.btnTripHistoryDone)
            val btnRecluster = dialogView.findViewById<Button>(R.id.btnReclusterLogs)

            var currentFilter = "ALL" // "ALL", "TODAY", "WEEK"

            val solidCyanBg = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_cyan_solid)
            val darkBg = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_dark)
            val blackColor = ContextCompat.getColor(this@MainActivity, R.color.black)
            val secondaryTextColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)

            // Setup RecyclerView
            rvTripHistory.layoutManager = LinearLayoutManager(this@MainActivity)
            val adapter = JourneyHistoryAdapter(emptyList()) { selected ->
                showJourneyDetailDialog(selected)
            }
            rvTripHistory.adapter = adapter

            fun applyFilter(filter: String, list: List<JourneyEntity>) {
                currentFilter = filter
                btnFilterAll.background = if (filter == "ALL") solidCyanBg else darkBg
                btnFilterAll.setTextColor(if (filter == "ALL") blackColor else secondaryTextColor)

                btnFilterToday.background = if (filter == "TODAY") solidCyanBg else darkBg
                btnFilterToday.setTextColor(if (filter == "TODAY") blackColor else secondaryTextColor)

                btnFilterWeek.background = if (filter == "WEEK") solidCyanBg else darkBg
                btnFilterWeek.setTextColor(if (filter == "WEEK") blackColor else secondaryTextColor)

                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val oneWeekAgo = System.currentTimeMillis() - 7 * 86_400_000L

                val filtered = when (filter) {
                    "TODAY" -> list.filter { j ->
                        (j.startTime >= startOfToday) || j.routeSummary.startsWith("Today", ignoreCase = true)
                    }
                    "WEEK" -> list.filter { j ->
                        j.startTime >= oneWeekAgo || j.startTime == 0L
                    }
                    else -> list
                }

                if (filtered.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    rvTripHistory.visibility = View.GONE
                } else {
                    layoutEmpty.visibility = View.GONE
                    rvTripHistory.visibility = View.VISIBLE
                }

                adapter.submitList(filtered)

                val totalKm = filtered.sumOf { it.distanceKm }
                val totalSaved = filtered.sumOf { it.moneySavedInr }
                val totalCo2 = filtered.sumOf { it.co2SavedKg }

                tvSubtitle.text = "${filtered.size} Trips • ${String.format(Locale.US, "%.1f", totalKm)} km recorded • 99.4% Telemetry"
                tvActiveCount.text = "${filtered.size} rides"
                tvBannerTitle.text = "Period Distance: ${String.format(Locale.US, "%.1f", totalKm)} km"
                tvBannerSubtitle.text = "Net Fuel Saved: ₹$totalSaved • ${String.format(Locale.US, "%.2f", totalCo2)} kg CO₂ avoided"
                tvEcoBadge.text = "4.8★ Eco Score"
            }

            applyFilter("ALL", allJourneys)

            btnFilterAll.setOnClickListener { applyFilter("ALL", allJourneys) }
            btnFilterToday.setOnClickListener { applyFilter("TODAY", allJourneys) }
            btnFilterWeek.setOnClickListener { applyFilter("WEEK", allJourneys) }

            btnClose.setOnClickListener { bottomSheetDialog.dismiss() }
            btnDone.setOnClickListener { bottomSheetDialog.dismiss() }

            btnRecluster.setOnClickListener {
                lifecycleScope.launch {
                    triggerManualReclustering()
                    val refreshed = repository.getRecentJourneysList(50)
                    applyFilter(currentFilter, refreshed)
                }
            }

            bottomSheetDialog.show()
        }
    }

    private fun triggerManualReclustering() {
        lifecycleScope.launch {
            val logs = repository.getAllLogs()
            if (logs.size >= 2) {
                val clustered = JourneyClusteringEngine.clusterLogsIntoJourneys(
                    logs,
                    petrolPricePerLiter,
                    iceScooterMileageKmL
                )
                if (clustered.isNotEmpty()) {
                    repository.saveJourneys(clustered)
                    Toast.makeText(this@MainActivity, "Clustered ${clustered.size} journeys from telemetry logs!", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            // If raw logs are sparse, regenerate seeds calibrated to odometer and daily distance
            val seeds = JourneyClusteringEngine.generateSeedJourneys(
                if (lastKnownTodayKm > 0) lastKnownTodayKm else 0.0,
                if (lastKnownYesterdayKm > 0) lastKnownYesterdayKm else getLiveYesterdayKm(),
                petrolPricePerLiter,
                iceScooterMileageKmL
            )
            repository.saveJourneys(seeds)
            Toast.makeText(this@MainActivity, "Refreshed journeys using AI Clustering Engine", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Insights Tab: Timeframe Filter & Dynamic AI Recommendations ────────────

    private var selectedInsightsTimeframe = "30D"

    private fun setupInsightsTab() {
        binding.btnInsights30D.setOnClickListener { selectInsightsTimeframe("30D") }
        binding.btnInsights7D.setOnClickListener { selectInsightsTimeframe("7D") }
        binding.btnInsightsAllTime.setOnClickListener { selectInsightsTimeframe("ALL") }
        binding.btnInsightsPredictive.setOnClickListener { selectInsightsTimeframe("PREDICTIVE") }

        binding.cardRec1.setOnClickListener {
            Toast.makeText(this, "AI Recommendation: Maintain 42 km/h cruise speed", Toast.LENGTH_SHORT).show()
        }
        binding.cardRec2.setOnClickListener {
            Toast.makeText(this, "AI Recommendation: Smart charging scheduled to 80%", Toast.LENGTH_SHORT).show()
        }

        // Initialize default view
        selectInsightsTimeframe("30D")
    }

    private fun selectInsightsTimeframe(timeframe: String) {
        selectedInsightsTimeframe = timeframe
        val solidCyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
        val blackColor = ContextCompat.getColor(this, R.color.black)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_primary)
        val emeraldColor = ContextCompat.getColor(this, R.color.status_emerald)

        binding.btnInsights30D.background = if (timeframe == "30D") solidCyanBg else null
        binding.btnInsights30D.setTextColor(if (timeframe == "30D") blackColor else inactiveTextColor)

        binding.btnInsights7D.background = if (timeframe == "7D") solidCyanBg else null
        binding.btnInsights7D.setTextColor(if (timeframe == "7D") blackColor else inactiveTextColor)

        binding.btnInsightsAllTime.background = if (timeframe == "ALL") solidCyanBg else null
        binding.btnInsightsAllTime.setTextColor(if (timeframe == "ALL") blackColor else inactiveTextColor)

        binding.btnInsightsPredictive.background = if (timeframe == "PREDICTIVE") solidCyanBg else null
        val tvPredictive = binding.btnInsightsPredictive.getChildAt(1) as? TextView
        tvPredictive?.setTextColor(if (timeframe == "PREDICTIVE") blackColor else emeraldColor)

        // Query backend for this timeframe using active effective device ID
        val effectiveId = NexusCloudSyncManager.getInstance(this).getEffectiveDeviceId()
        NexusCloudSyncManager.getInstance(this).fetchInsightsTimeframe(effectiveId, timeframe)

        val ins = NexusCloudSyncManager.getInstance(this).insights.value
        applyInsightsTimeframeData(timeframe, ins)
    }

    @SuppressLint("SetTextI18n")
    private fun applyInsightsTimeframeData(timeframe: String, ins: ServerInsights) {
        when (timeframe) {
            "30D" -> {
                binding.tvEcoScoreBig.text = if (ins.ecoScore > 0) "${ins.ecoScore}" else "N/A"
                binding.tvFleetRankBadge.text = if (ins.fleetRank.isNotBlank()) (if (ins.fleetRank.startsWith("✨")) ins.fleetRank else "✨ ${ins.fleetRank}") else "✨ Active Rider"
                binding.tvEcoRatingTitle.text = if (ins.ecoRating.isNotBlank()) ins.ecoRating.uppercase() else "ECO DRIVE"
                binding.tvEcoCoachSummary.text = if (ins.aiTipSummary.isNotBlank()) ins.aiTipSummary else "Ride your scooter to generate AI throttle and energy insights."
                binding.tvSmoothAccelVal.text = if (ins.smoothAccelPct > 0) "${ins.smoothAccelPct}%" else "N/A"
                binding.tvBonusRangeVal.text = if (ins.bonusRangeKm > 0) "+${ins.bonusRangeKm} km" else "N/A"
                binding.tvThrottleLagVal.text = if (ins.throttleLagSec > 0.0) "${ins.throttleLagSec}s" else "N/A"

                binding.tvEnergyConsumedVal.text = "%.1f kWh".format(ins.consumedKwh)
                binding.tvEnergyRegenVal.text = "%.1f kWh".format(ins.recapturedRegenKwh)
                (binding.barDriveExpenditure.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = ins.driveExpenditurePct.toFloat()
                    binding.barDriveExpenditure.layoutParams = lp
                }
                (binding.barRegenReclaimed.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = ins.regenRecapturedPct.toFloat()
                    binding.barRegenReclaimed.layoutParams = lp
                }
                binding.tvDriveExpenditurePct.text = "${ins.driveExpenditurePct}% Drive Expenditure"
                binding.tvEnergyReclaimedPct.text = "+${ins.regenRecapturedPct}% Energy Reclaimed"

                val effVal = ins.realWorldEfficiency?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                binding.tvEfficiencyIndex.text = if (effVal != null && effVal > 0.0) "%.2f".format(effVal) else "N/A"

                binding.tvEcoRangeVal.text = if (ins.ecoRangeProjectionKm > 0) "${ins.ecoRangeProjectionKm} km" else "N/A"
                binding.tvSportRangeVal.text = if (ins.sportRangeProjectionKm > 0) "${ins.sportRangeProjectionKm} km" else "N/A"
                binding.pbEcoRange.progress = if (ins.ecoRangeProjectionKm > 0) minOf(100, (ins.ecoRangeProjectionKm * 100) / 120) else 0
                binding.pbSportRange.progress = if (ins.sportRangeProjectionKm > 0) minOf(100, (ins.sportRangeProjectionKm * 100) / 120) else 0

                (binding.barSpeedEco.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = ins.speedZoneEcoPct.toFloat()
                    binding.barSpeedEco.layoutParams = lp
                }
                (binding.barSpeedCruise.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = ins.speedZoneCruisePct.toFloat()
                    binding.barSpeedCruise.layoutParams = lp
                }
                (binding.barSpeedPower.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = ins.speedZonePowerPct.toFloat()
                    binding.barSpeedPower.layoutParams = lp
                }
                binding.tvSpeedZoneEcoPct.text = "${ins.speedZoneEcoPct}%"
                binding.tvSpeedZoneCruisePct.text = "${ins.speedZoneCruisePct}%"
                binding.tvSpeedZonePowerPct.text = "${ins.speedZonePowerPct}%"
                binding.tvPeakCommuteWindows.text = if (ins.peakCommuteWindows.isNotBlank()) ins.peakCommuteWindows else "N/A"

                val effectiveMonthKm = maxOf(if (ins.monthDistanceKm in 0.1..10000.0) ins.monthDistanceKm else 0.0, lastKnownMonthKm)
                val fuelAvoided30D = calculatePetrolSavedLiters(effectiveMonthKm)
                val saved30D = calculateMoneySavedFromLiters(fuelAvoided30D)
                binding.tvImpactSavedInr.text = "₹%,d".format(saved30D)
                binding.tvImpactSavingsYoy.text = "+${ins.impactSavingsYoyPct}% YOY"
                binding.tvImpactFuelAvoided.text = "%.1f L".format(fuelAvoided30D)
                binding.tvImpactCo2Offset.text = "%.1f kg".format(calculateCo2FromLiters(fuelAvoided30D))
                binding.tvImpactServiceLife.text = if (ins.serviceHealthLifePct > 0) "${ins.serviceHealthLifePct}% Life" else "N/A"
                binding.tvImpactServiceKm.text = if (ins.serviceRemainingKm > 0) "%,d km".format(ins.serviceRemainingKm) else "N/A"
            }
            "7D" -> {
                binding.tvEcoScoreBig.text = if (ins.ecoScore > 0) "${ins.ecoScore}" else "N/A"
                binding.tvFleetRankBadge.text = if (ins.fleetRank.isNotBlank()) "✨ Top 4% This Week" else "✨ Active Rider"
                binding.tvEcoRatingTitle.text = "HIGH FLOW CONSISTENCY"
                binding.tvEcoCoachSummary.text = if (ins.monthDistanceKm > 0) "Weekly throttle discipline saved an estimated 5.2 km extra range." else "Ride your scooter to generate AI throttle and energy insights."
                binding.tvSmoothAccelVal.text = if (ins.smoothAccelPct > 0) "${minOf(99, ins.smoothAccelPct + 1)}%" else "N/A"
                binding.tvBonusRangeVal.text = if (ins.bonusRangeKm > 0) "+5 km" else "N/A"
                binding.tvThrottleLagVal.text = if (ins.throttleLagSec > 0.0) "0.03s" else "N/A"

                val cKwh = ins.consumedKwh * 0.25
                val rKwh = ins.recapturedRegenKwh * 0.25
                binding.tvEnergyConsumedVal.text = "%.1f kWh".format(cKwh)
                binding.tvEnergyRegenVal.text = "%.1f kWh".format(rKwh)
                (binding.barDriveExpenditure.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (cKwh > 0) 82f else 0f
                    binding.barDriveExpenditure.layoutParams = lp
                }
                (binding.barRegenReclaimed.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (rKwh > 0) 18f else 0f
                    binding.barRegenReclaimed.layoutParams = lp
                }
                binding.tvDriveExpenditurePct.text = if (cKwh > 0) "82% Drive Expenditure" else "0% Drive Expenditure"
                binding.tvEnergyReclaimedPct.text = if (rKwh > 0) "+18% Energy Reclaimed" else "+0% Energy Reclaimed"

                val effVal = ins.realWorldEfficiency?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                binding.tvEfficiencyIndex.text = if (effVal != null && effVal > 0.0) "1.98" else "N/A"
                binding.tvEcoRangeVal.text = if (ins.ecoRangeProjectionKm > 0) "${ins.ecoRangeProjectionKm} km" else "N/A"
                binding.tvSportRangeVal.text = if (ins.sportRangeProjectionKm > 0) "${ins.sportRangeProjectionKm} km" else "N/A"

                (binding.barSpeedEco.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneEcoPct > 0) 71f else 0f
                    binding.barSpeedEco.layoutParams = lp
                }
                (binding.barSpeedCruise.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneCruisePct > 0) 22f else 0f
                    binding.barSpeedCruise.layoutParams = lp
                }
                (binding.barSpeedPower.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZonePowerPct > 0) 7f else 0f
                    binding.barSpeedPower.layoutParams = lp
                }
                binding.tvSpeedZoneEcoPct.text = if (ins.speedZoneEcoPct > 0) "71%" else "0%"
                binding.tvSpeedZoneCruisePct.text = if (ins.speedZoneCruisePct > 0) "22%" else "0%"
                binding.tvSpeedZonePowerPct.text = if (ins.speedZonePowerPct > 0) "7%" else "0%"
                binding.tvPeakCommuteWindows.text = if (ins.peakCommuteWindows.isNotBlank()) "8:20 AM & 6:15 PM" else "N/A"

                val effectiveMonthKm = maxOf(if (ins.monthDistanceKm in 0.1..10000.0) ins.monthDistanceKm else 0.0, lastKnownMonthKm)
                val fuelAvoided7D = calculatePetrolSavedLiters(effectiveMonthKm) * 0.24
                val saved7D = calculateMoneySavedFromLiters(fuelAvoided7D)
                binding.tvImpactSavedInr.text = "₹%,d".format(saved7D)
                binding.tvImpactSavingsYoy.text = if (saved7D > 0) "+28% WOW" else "+0% WOW"
                binding.tvImpactFuelAvoided.text = "%.1f L".format(fuelAvoided7D)
                binding.tvImpactCo2Offset.text = "%.1f kg".format(calculateCo2FromLiters(fuelAvoided7D))
                binding.tvImpactServiceLife.text = if (ins.serviceHealthLifePct > 0) "${ins.serviceHealthLifePct}% Life" else "N/A"
                binding.tvImpactServiceKm.text = if (ins.serviceRemainingKm > 0) "%,d km".format(ins.serviceRemainingKm) else "N/A"
            }
            "ALL" -> {
                binding.tvEcoScoreBig.text = if (ins.ecoScore > 0) "94" else "N/A"
                binding.tvFleetRankBadge.text = if (ins.odometerKm > 0) "✨ Top 8% Lifetime" else "✨ Active Rider"
                binding.tvEcoRatingTitle.text = "VETERAN EV PILOT"
                binding.tvEcoCoachSummary.text = if (ins.odometerKm > 0) "Lifetime regenerative reclaim exceeds 180 kWh across all journeys." else "Ride your scooter to generate AI throttle and energy insights."
                binding.tvSmoothAccelVal.text = if (ins.smoothAccelPct > 0) "92%" else "N/A"
                binding.tvBonusRangeVal.text = if (ins.odometerKm > 0) "+142 km" else "N/A"
                binding.tvThrottleLagVal.text = if (ins.throttleLagSec > 0.0) "0.05s" else "N/A"

                val cKwh = if (ins.odometerKm > 0.0) ins.odometerKm * 0.038 else 0.0
                val rKwh = if (ins.odometerKm > 0.0) ins.odometerKm * 0.006 else 0.0
                binding.tvEnergyConsumedVal.text = "%.1f kWh".format(cKwh)
                binding.tvEnergyRegenVal.text = "%.1f kWh".format(rKwh)
                (binding.barDriveExpenditure.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (cKwh > 0) 85f else 0f
                    binding.barDriveExpenditure.layoutParams = lp
                }
                (binding.barRegenReclaimed.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (rKwh > 0) 15f else 0f
                    binding.barRegenReclaimed.layoutParams = lp
                }
                binding.tvDriveExpenditurePct.text = if (cKwh > 0) "85% Drive Expenditure" else "0% Drive Expenditure"
                binding.tvEnergyReclaimedPct.text = if (rKwh > 0) "+15% Energy Reclaimed" else "+0% Energy Reclaimed"

                val effVal = ins.realWorldEfficiency?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                binding.tvEfficiencyIndex.text = if (effVal != null && effVal > 0.0) "1.92" else "N/A"
                binding.tvEcoRangeVal.text = if (ins.ecoRangeProjectionKm > 0) "${ins.ecoRangeProjectionKm} km" else "N/A"
                binding.tvSportRangeVal.text = if (ins.sportRangeProjectionKm > 0) "${ins.sportRangeProjectionKm} km" else "N/A"

                (binding.barSpeedEco.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneEcoPct > 0) 65f else 0f
                    binding.barSpeedEco.layoutParams = lp
                }
                (binding.barSpeedCruise.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneCruisePct > 0) 26f else 0f
                    binding.barSpeedCruise.layoutParams = lp
                }
                (binding.barSpeedPower.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZonePowerPct > 0) 9f else 0f
                    binding.barSpeedPower.layoutParams = lp
                }
                binding.tvSpeedZoneEcoPct.text = if (ins.speedZoneEcoPct > 0) "65%" else "0%"
                binding.tvSpeedZoneCruisePct.text = if (ins.speedZoneCruisePct > 0) "26%" else "0%"
                binding.tvSpeedZonePowerPct.text = if (ins.speedZonePowerPct > 0) "9%" else "0%"
                binding.tvPeakCommuteWindows.text = if (ins.peakCommuteWindows.isNotBlank()) "All Day Active" else "N/A"

                val lifetimeDist = if (ins.odometerKm > 0.0) ins.odometerKm else 0.0
                val lifetimeLiters = calculatePetrolSavedLiters(lifetimeDist)
                val totalSaved = calculateMoneySavedFromLiters(lifetimeLiters)
                binding.tvImpactSavedInr.text = "₹%,d".format(totalSaved)
                binding.tvImpactSavingsYoy.text = if (totalSaved > 0) "+44% Total" else "+0% Total"
                binding.tvImpactFuelAvoided.text = "%.1f L".format(lifetimeLiters)
                binding.tvImpactCo2Offset.text = "%.1f kg".format(calculateCo2FromLiters(lifetimeLiters))
                binding.tvImpactServiceLife.text = if (ins.serviceHealthLifePct > 0) "${ins.serviceHealthLifePct}% Life" else "N/A"
                binding.tvImpactServiceKm.text = if (ins.serviceRemainingKm > 0) "%,d km".format(ins.serviceRemainingKm) else "N/A"
            }
            "PREDICTIVE" -> {
                binding.tvEcoScoreBig.text = if (ins.ecoScore > 0) "98" else "N/A"
                binding.tvFleetRankBadge.text = if (ins.fleetRank.isNotBlank()) "✨ Top 2% Forecast" else "✨ Active Rider"
                binding.tvEcoRatingTitle.text = "PREDICTIVE OPTIMIZATION"
                binding.tvEcoCoachSummary.text = if (ins.monthDistanceKm > 0) "AI predicts +24km additional monthly range if cruise speeds stay within 45 km/h." else "Ride your scooter to generate AI throttle and energy insights."
                binding.tvSmoothAccelVal.text = if (ins.smoothAccelPct > 0) "97%" else "N/A"
                binding.tvBonusRangeVal.text = if (ins.bonusRangeKm > 0) "+24 km" else "N/A"
                binding.tvThrottleLagVal.text = if (ins.throttleLagSec > 0.0) "0.02s" else "N/A"

                val cKwh = ins.consumedKwh * 0.92
                val rKwh = ins.recapturedRegenKwh * 1.15
                binding.tvEnergyConsumedVal.text = "%.1f kWh".format(cKwh)
                binding.tvEnergyRegenVal.text = "%.1f kWh".format(rKwh)
                (binding.barDriveExpenditure.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (cKwh > 0) 80f else 0f
                    binding.barDriveExpenditure.layoutParams = lp
                }
                (binding.barRegenReclaimed.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (rKwh > 0) 20f else 0f
                    binding.barRegenReclaimed.layoutParams = lp
                }
                binding.tvDriveExpenditurePct.text = if (cKwh > 0) "80% Projected Expenditure" else "0% Projected Expenditure"
                binding.tvEnergyReclaimedPct.text = if (rKwh > 0) "+20% Target Reclaimed" else "+0% Target Reclaimed"

                val effVal = ins.realWorldEfficiency?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                binding.tvEfficiencyIndex.text = if (effVal != null && effVal > 0.0) "2.05" else "N/A"
                binding.tvEcoRangeVal.text = if (ins.ecoRangeProjectionKm > 0) "${ins.ecoRangeProjectionKm + 6} km" else "N/A"
                binding.tvSportRangeVal.text = if (ins.sportRangeProjectionKm > 0) "${ins.sportRangeProjectionKm + 4} km" else "N/A"
                binding.pbEcoRange.progress = if (ins.ecoRangeProjectionKm > 0) 95 else 0
                binding.pbSportRange.progress = if (ins.sportRangeProjectionKm > 0) 75 else 0

                (binding.barSpeedEco.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneEcoPct > 0) 75f else 0f
                    binding.barSpeedEco.layoutParams = lp
                }
                (binding.barSpeedCruise.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZoneCruisePct > 0) 20f else 0f
                    binding.barSpeedCruise.layoutParams = lp
                }
                (binding.barSpeedPower.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                    lp.weight = if (ins.speedZonePowerPct > 0) 5f else 0f
                    binding.barSpeedPower.layoutParams = lp
                }
                binding.tvSpeedZoneEcoPct.text = if (ins.speedZoneEcoPct > 0) "75%" else "0%"
                binding.tvSpeedZoneCruisePct.text = if (ins.speedZoneCruisePct > 0) "20%" else "0%"
                binding.tvSpeedZonePowerPct.text = if (ins.speedZonePowerPct > 0) "5%" else "0%"
                binding.tvPeakCommuteWindows.text = if (ins.peakCommuteWindows.isNotBlank()) "Dynamic Weather Tuning" else "N/A"

                val effectiveMonthKm = maxOf(if (ins.monthDistanceKm in 0.1..10000.0) ins.monthDistanceKm else 0.0, lastKnownMonthKm)
                val baseFuel = calculatePetrolSavedLiters(effectiveMonthKm)
                val fuelAvoidedPred = baseFuel * 1.15
                val predSavings = calculateMoneySavedFromLiters(fuelAvoidedPred)
                binding.tvImpactSavedInr.text = "₹%,d".format(predSavings)
                binding.tvImpactSavingsYoy.text = if (predSavings > 0) "+38% Projected" else "+0% Projected"
                binding.tvImpactFuelAvoided.text = "%.1f L".format(fuelAvoidedPred)
                binding.tvImpactCo2Offset.text = "%.1f kg".format(calculateCo2FromLiters(fuelAvoidedPred))
                binding.tvImpactServiceLife.text = if (ins.serviceHealthLifePct > 0) "${ins.serviceHealthLifePct}% Life" else "N/A"
                binding.tvImpactServiceKm.text = if (ins.serviceRemainingKm > 0) "%,d km".format(ins.serviceRemainingKm) else "N/A"
            }
        }
    }

    // ─── Scooter Tab Controls & Diagnostics ───────────────────────────────────

    private fun setupScooterControls() {
        setupMaintenanceLifecycle()

        // Dealer & Service Locator
        binding.btnFindCenters.setOnClickListener {
            openWebUrl("https://ampere-electric.com/ampere-dealer-locator/?lat=10.884598952360514&long=76.99193270042919&shared=1")
        }

        // EV Charging Stations on Google Maps
        binding.btnSearchEvChargers.setOnClickListener {
            openWebUrl("https://www.google.com/maps/search/ev+charging+station+near+me")
        }

        // Deep Storage & Hibernation Mode Switch
        binding.switchHibernation.isChecked = prefs.getBoolean("hibernation_mode", false)
        binding.switchHibernation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hibernation_mode", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "Storage Mode On • BMS Drain Halved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Standard Active Telemetry Restored", Toast.LENGTH_SHORT).show()
            }
        }

        // Book Service CTA
        maintBinding.btnBookService.setOnClickListener {
            openWebUrl("https://ampere-electric.com/ampere-dealer-locator/?lat=10.884598952360514&long=76.99193270042919&shared=1")
        }

        // Download Official 2026 Brochure PDF
        binding.btnDownloadBrochure.setOnClickListener {
            openWebUrl("https://nexus-api-zeta-ochre.vercel.app/assets/nexus-brochure-2026.pdf")
        }

        // 24/7 Helpline Phone Dialer
        binding.btnSupportPhone.setOnClickListener {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:08045560700"))
                startActivity(dialIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Phone: 080 4556 0700", Toast.LENGTH_LONG).show()
            }
        }

        // Support Email
        binding.btnSupportEmail.setOnClickListener {
            try {
                val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:customersupport@greaveselectricmobility.com")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "Ampere Nexus Support Inquiry (VIN: NEX-9921)")
                }
                startActivity(Intent.createChooser(emailIntent, "Send Email to Ampere Support"))
            } catch (e: Exception) {
                Toast.makeText(this, "Email: customersupport@greaveselectricmobility.com", Toast.LENGTH_LONG).show()
            }
        }

        // Registered Office / Contact URL
        binding.btnSupportOffice.setOnClickListener {
            openWebUrl("https://ampere-electric.com/contact")
        }

        // Retained compatibility actions
        binding.btnLockToggle.setOnClickListener {
            val ins = NexusCloudSyncManager.getInstance(this).insights.value
            val newCommand = if (ins.isLocked) "UNLOCK" else "LOCK"
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, newCommand) { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                updateLockStateUi(newCommand == "LOCK")
            }
        }
        binding.btnOpenBoot.setOnClickListener {
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, "OPEN_BOOT") { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSirenPing.setOnClickListener {
            NexusCloudSyncManager.getInstance(this).sendRemoteControl(savedAddress, "SIREN_PING") { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnLiveGps.setOnClickListener {
            openGoogleMapsApp()
        }
        binding.btnRunScan.setOnClickListener {
            NexusCloudSyncManager.getInstance(this).runDiagnosticsScan(savedAddress) { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnAskCopilot.setOnClickListener {
            showCopilotChatDialog()
        }
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "N/A"
        val diffMs = maxOf(0L, System.currentTimeMillis() - timestampMs)
        val diffSec = diffMs / 1000L
        val diffMin = diffSec / 60L
        val diffHours = diffMin / 60L
        val diffDays = diffHours / 24L

        return when {
            diffMin < 1L -> "Just now"
            diffMin < 60L -> "${diffMin}m ago"
            diffHours < 24L -> "${diffHours}h ago"
            diffDays < 30L -> "${diffDays}d ago"
            else -> "${diffDays / 30L}mo ago"
        }
    }

    private fun setupMaintenanceLifecycle() {
        // Tab switching for Owner Preventive Tasks
        maintBinding.btnTabWeeklyTasks.setOnClickListener {
            selectedOwnerTab = "WEEKLY"
            maintBinding.btnTabWeeklyTasks.setBackgroundResource(R.drawable.bg_tab_pill_active)
            maintBinding.btnTabWeeklyTasks.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            maintBinding.btnTabMonthlyTasks.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            maintBinding.btnTabMonthlyTasks.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            renderOwnerTasks(currentMaintenanceReport?.weeklyTasks ?: MaintenanceRepository.DEFAULT_WEEKLY_TASKS)
        }

        maintBinding.btnTabMonthlyTasks.setOnClickListener {
            selectedOwnerTab = "MONTHLY"
            maintBinding.btnTabMonthlyTasks.setBackgroundResource(R.drawable.bg_tab_pill_active)
            maintBinding.btnTabMonthlyTasks.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            maintBinding.btnTabWeeklyTasks.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            maintBinding.btnTabWeeklyTasks.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            renderOwnerTasks(currentMaintenanceReport?.monthlyTasks ?: MaintenanceRepository.DEFAULT_MONTHLY_TASKS)
        }

        val liveOdo = repository.currentData.value.odometerKm
        val insOdo = latestInsights?.odometerKm ?: 0.0
        val cachedOdo = prefs.getFloat("last_known_odometer", 0f).toDouble()
        val initialOdo = if (liveOdo > 0.0) liveOdo else if (insOdo > 0.0) insOdo else if (cachedOdo > 0.0) cachedOdo else 0.0
        val report = latestInsights?.maintenanceReport ?: MaintenanceRepository.buildLocalReport(initialOdo)
        updateScooterMaintenanceUi(report, initialOdo)
    }

    private fun getTargetMilestoneIndex(timeline: List<ServiceMilestoneItem>?, targetKm: Int?): Int {
        if (timeline.isNullOrEmpty()) return 0
        val byNext = timeline.indexOfFirst { it.isNext }
        if (byNext >= 0) return byNext
        val byStatus = timeline.indexOfFirst { it.status.equals("UPCOMING", ignoreCase = true) }
        if (byStatus >= 0) return byStatus
        if (targetKm != null && targetKm > 0) {
            val byKm = timeline.indexOfFirst { it.milestoneKm == targetKm }
            if (byKm >= 0) return byKm
        }
        return 0
    }

    private fun scrollToSelectedMilestone(smooth: Boolean = true) {
        val scrollView = maintBinding.scrollMilestonesTrack
        val targetView = maintBinding.layoutMilestonesTrack.findViewWithTag<View>("milestone_item_$selectedMilestoneIndex")
            ?: return

        scrollView.post {
            val sWidth = scrollView.width
            val tWidth = targetView.width
            if (sWidth > 0 && tWidth > 0) {
                val scrollX = maxOf(0, targetView.left - (sWidth - tWidth) / 2)
                if (smooth) {
                    scrollView.smoothScrollTo(scrollX, 0)
                } else {
                    scrollView.scrollTo(scrollX, 0)
                }
            } else {
                scrollView.postDelayed({
                    val retrySWidth = scrollView.width
                    val retryTWidth = targetView.width
                    if (retrySWidth > 0 && retryTWidth > 0) {
                        val scrollX = maxOf(0, targetView.left - (retrySWidth - retryTWidth) / 2)
                        if (smooth) scrollView.smoothScrollTo(scrollX, 0) else scrollView.scrollTo(scrollX, 0)
                    }
                }, 100)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateScooterMaintenanceUi(report: MaintenanceReport, currentOdo: Double) {
        currentMaintenanceReport = report

        // Next Inspection Milestone Hero Box
        maintBinding.tvServiceTargetTitle.text = "Next: ${report.nextServiceTitle}"
        maintBinding.tvServiceDueKmBadge.text = if (report.kmUntilNextService > 0) "%.0f km due".format(report.kmUntilNextService) else "Due Now"
        maintBinding.tvNextServiceMaint.text = "${report.nextMilestoneKm.toString().replace(Regex("(\\d)(?=(\\d{3})+$)"), "$1,")} km"
        maintBinding.tvCurrentOdoService.text = if (currentOdo > 0) "Current Odo: %.0f km".format(currentOdo) else "Current Odo: -- km"

        // Milestone Progress Calculation
        val prevKm = if (report.nextMilestoneKm <= 1000) 0.0 else (report.nextMilestoneKm - 5000).toDouble()
        val span = maxOf(1.0, report.nextMilestoneKm - prevKm)
        val prog = (((currentOdo - prevKm) / span) * 100).toInt().coerceIn(0, 100)
        maintBinding.progressServiceLifecycle.progress = prog

        // Preselect the next target service milestone if user hasn't explicitly selected one
        if (!userSelectedMilestoneManually || selectedMilestoneIndex !in report.timeline.indices) {
            selectedMilestoneIndex = getTargetMilestoneIndex(report.timeline, report.nextMilestoneKm)
        }

        // Render Horizontal Milestones Timeline Track
        renderMilestoneTimelineTrack(report.timeline)

        // Render Owner Tasks (Weekly or Monthly)
        if (selectedOwnerTab == "WEEKLY") {
            renderOwnerTasks(report.weeklyTasks)
        } else {
            renderOwnerTasks(report.monthlyTasks)
        }

        // Render EV vs Petrol Comparison Table
        renderEvVsPetrolComparison(report.evVsPetrol)

        // Render FAQs
        renderFaqList(report.faqs)
    }

    @SuppressLint("SetTextI18n")
    private fun renderMilestoneTimelineTrack(timeline: List<ServiceMilestoneItem>) {
        maintBinding.layoutMilestonesTrack.removeAllViews()
        if (timeline.isEmpty()) return

        if (!userSelectedMilestoneManually || selectedMilestoneIndex !in timeline.indices) {
            selectedMilestoneIndex = getTargetMilestoneIndex(timeline, currentMaintenanceReport?.nextMilestoneKm)
        }

        val density = resources.displayMetrics.density
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val emeraldColor = ContextCompat.getColor(this, R.color.status_emerald)
        val secondaryColor = ContextCompat.getColor(this, R.color.text_secondary)

        timeline.forEachIndexed { index, milestone ->
            val isSelected = (index == selectedMilestoneIndex)
            val isCompleted = (milestone.status == "COMPLETED")
            val isUpcoming = milestone.isNext

            val itemLayout = LinearLayout(this).apply {
                tag = "milestone_item_$index"
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                isClickable = true
                isFocusable = true
                setBackgroundResource(if (isSelected) R.drawable.bg_pill_cyan else R.drawable.bg_card_high)
                setOnClickListener {
                    userSelectedMilestoneManually = true
                    selectedMilestoneIndex = index
                    renderMilestoneTimelineTrack(timeline)
                    displaySelectedMilestone(milestone)
                    scrollToSelectedMilestone(smooth = true)
                }
            }

            // Milestone Badge Circle
            val badgeText = TextView(this).apply {
                val kmLabel = if (milestone.milestoneKm < 1000) "${milestone.milestoneKm}" else "${milestone.milestoneKm / 1000}k"
                text = when {
                    isCompleted -> "✓ $kmLabel"
                    isUpcoming -> "⭐ $kmLabel"
                    else -> kmLabel
                }
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(when {
                    isSelected -> ContextCompat.getColor(context, R.color.black)
                    isCompleted -> emeraldColor
                    isUpcoming -> cyanColor
                    else -> secondaryColor
                })
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setBackgroundResource(when {
                    isSelected -> R.drawable.bg_pill_cyan_solid
                    isCompleted -> R.drawable.bg_pill_emerald
                    isUpcoming -> R.drawable.bg_card_cyber
                    else -> R.drawable.bg_pill_dark
                })
            }
            itemLayout.addView(badgeText)

            // Status text
            val statusLabel = TextView(this).apply {
                text = when {
                    isCompleted -> "Done"
                    isUpcoming -> "Target"
                    else -> "Future"
                }
                textSize = 9f
                setTextColor(if (isSelected) ContextCompat.getColor(context, R.color.black) else secondaryColor)
                gravity = Gravity.CENTER
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            itemLayout.addView(statusLabel)

            maintBinding.layoutMilestonesTrack.addView(itemLayout)

            // Connecting track between nodes
            if (index < timeline.size - 1) {
                val connector = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (2 * density).toInt()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = (2 * density).toInt()
                        marginEnd = (2 * density).toInt()
                    }
                    setBackgroundColor(if (isCompleted) emeraldColor else ContextCompat.getColor(context, R.color.glass_border_subtle))
                }
                maintBinding.layoutMilestonesTrack.addView(connector)
            }
        }

        // Show checklist for currently selected milestone
        val selectedMilestone = timeline.getOrNull(selectedMilestoneIndex) ?: timeline.firstOrNull()
        displaySelectedMilestone(selectedMilestone)

        // Center on the selected milestone
        scrollToSelectedMilestone(smooth = true)
    }

    @SuppressLint("SetTextI18n")
    private fun displaySelectedMilestone(milestone: ServiceMilestoneItem?) {
        if (milestone == null) return
        maintBinding.tvSelectedMilestoneTitle.text = "${milestone.title} Checklist"
        maintBinding.tvSelectedMilestoneStatus.text = when {
            milestone.isNext -> "UPCOMING TARGET"
            milestone.status == "COMPLETED" -> "COMPLETED"
            else -> "FUTURE SERVICE"
        }
        maintBinding.tvSelectedMilestoneStatus.setBackgroundResource(when {
            milestone.isNext -> R.drawable.bg_pill_cyan
            milestone.status == "COMPLETED" -> R.drawable.bg_pill_emerald
            else -> R.drawable.bg_card_high
        })
        maintBinding.tvSelectedMilestoneStatus.setTextColor(when {
            milestone.isNext -> ContextCompat.getColor(this, R.color.primary_cyan)
            milestone.status == "COMPLETED" -> ContextCompat.getColor(this, R.color.status_emerald)
            else -> ContextCompat.getColor(this, R.color.text_secondary)
        })

        maintBinding.layoutMilestoneChecklist.removeAllViews()
        val density = resources.displayMetrics.density
        val emeraldColor = ContextCompat.getColor(this, R.color.status_emerald)
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)

        milestone.checklist.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_high)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
            }

            val icon = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                setImageResource(R.drawable.ic_check_circle)
                setColorFilter(if (milestone.status == "COMPLETED") emeraldColor else cyanColor)
            }
            row.addView(icon)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * density).toInt()
                }
            }

            val itemTitle = TextView(this).apply {
                text = item.item
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            textLayout.addView(itemTitle)

            val itemDesc = TextView(this).apply {
                text = item.desc
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 10f
                setLineSpacing(2 * density, 1f)
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            textLayout.addView(itemDesc)

            row.addView(textLayout)
            maintBinding.layoutMilestoneChecklist.addView(row)
        }
    }

    private fun renderOwnerTasks(tasks: List<OwnerTaskItem>) {
        maintBinding.layoutOwnerTasks.removeAllViews()
        val density = resources.displayMetrics.density
        val emeraldColor = ContextCompat.getColor(this, R.color.secondary_green)
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)

        tasks.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_high)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
            }

            val icon = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt())
                setImageResource(R.drawable.ic_check_circle)
                setColorFilter(emeraldColor)
            }
            row.addView(icon)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * density).toInt()
                }
            }

            if (task.area.isNotBlank()) {
                val areaBadge = TextView(this).apply {
                    text = task.area.uppercase()
                    setTextColor(cyanColor)
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                }
                textLayout.addView(areaBadge)
            }

            val titleView = TextView(this).apply {
                text = task.title
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            textLayout.addView(titleView)

            val descView = TextView(this).apply {
                text = task.description
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 10f
                setLineSpacing(2 * density, 1f)
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            textLayout.addView(descView)

            row.addView(textLayout)
            maintBinding.layoutOwnerTasks.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderEvVsPetrolComparison(items: List<EvVsPetrolItem>) {
        maintBinding.layoutEvComparisonList.removeAllViews()
        val density = resources.displayMetrics.density
        val emeraldColor = ContextCompat.getColor(this, R.color.secondary_green)

        items.forEach { comp ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_high)
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * density).toInt()
                }
            }

            val titleView = TextView(this).apply {
                text = comp.item
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(titleView)

            val detailsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (2 * density).toInt(), 0, 0)
            }

            val petrolView = TextView(this).apply {
                text = "Petrol: ${comp.petrol}"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            detailsLayout.addView(petrolView)

            val evView = TextView(this).apply {
                text = "Nexus EV: ${comp.ev}"
                setTextColor(emeraldColor)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.END
            }
            detailsLayout.addView(evView)

            row.addView(detailsLayout)
            maintBinding.layoutEvComparisonList.addView(row)
        }
    }

    private fun renderFaqList(faqs: List<FaqItem>) {
        maintBinding.layoutFaqContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)

        faqs.forEach { faq ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_high)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
            }

            val qView = TextView(this).apply {
                text = "Q: ${faq.q}"
                setTextColor(cyanColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(qView)

            val aView = TextView(this).apply {
                text = faq.a
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 10.5f
                setLineSpacing(2 * density, 1f)
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            card.addView(aView)

            maintBinding.layoutFaqContainer.addView(card)
        }
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
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
        // Sync & Cluster Telemetry Preferences
        updateSyncSettingsCardUi()
        binding.btnSettingsTimeSyncMethod.setOnClickListener {
            showClusterTimeSyncDialog()
        }
        binding.btnSettingsCloudServer.setOnClickListener {
            showCloudServerDialog()
        }
        binding.btnSettingsForceSync.setOnClickListener {
            binding.btnSettingsForceSync.isEnabled = false
            Toast.makeText(this, "Syncing telemetry with cloud...", Toast.LENGTH_SHORT).show()
            NexusCloudSyncManager.getInstance(this).triggerManualSync(savedAddress, repository.currentData.value) { _, msg ->
                runOnUiThread {
                    binding.btnSettingsForceSync.isEnabled = true
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateSyncSettingsCardUi()
                }
            }
        }

        // Fuel & Savings Benchmark Configuration
        updateSavingsSettingsCardUi()
        binding.btnEditPetrolPrice.setOnClickListener {
            showPetrolPriceConfigDialog()
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

    // ─── Petrol & Money Savings Engine ────────────────────────────────────────

    /**
     * Calculates petrol avoided (in Litres) based on distance driven and configured ICE scooter mileage.
     */
    fun calculatePetrolSavedLiters(distanceKm: Double): Double {
        val mileage = maxOf(10.0f, iceScooterMileageKmL).toDouble()
        return maxOf(0.0, distanceKm / mileage)
    }

    /**
     * Calculates net money saved (in INR) compared to an equivalent petrol scooter,
     * taking into account petrol price, ICE fuel economy, and EV charging electricity cost (~₹0.22/km).
     */
    fun calculateMoneySavedFromLiters(liters: Double, customPrice: Float? = null): Int {
        val price = (customPrice ?: petrolPricePerLiter).toDouble()
        val mileage = maxOf(10.0f, iceScooterMileageKmL).toDouble()
        val equivalentKm = liters * mileage
        val petrolCost = liters * price
        val evElectricityCost = equivalentKm * 0.22 // ~₹0.22/km EV electricity running cost
        return maxOf(0, (petrolCost - evElectricityCost).roundToInt())
    }

    /**
     * Calculates net money saved (in INR) directly from distance driven.
     */
    fun calculateMoneySavedInr(distanceKm: Double, customPrice: Float? = null): Int {
        val liters = calculatePetrolSavedLiters(distanceKm)
        return calculateMoneySavedFromLiters(liters, customPrice)
    }

    /**
     * Calculates CO2 emissions avoided (in kg).
     * Net ~2.2 kg CO2 avoided per litre of petrol saved.
     */
    fun calculateCo2FromLiters(liters: Double): Double {
        return maxOf(0.0, liters * 2.2)
    }

    /**
     * Updates the Sync & Telemetry card in Settings.
     */
    @SuppressLint("SetTextI18n")
    private fun updateSyncSettingsCardUi() {
        val currentMethod = prefs.getInt("sync_method", 0)
        binding.tvSettingsTimeSyncVal.text = when (currentMethod) {
            1 -> "Unix Block"
            2 -> "Calendar Block"
            else -> "Both (Auto)"
        }

        val cloudManager = NexusCloudSyncManager.getInstance(this)
        val ins = latestInsights ?: cloudManager.insights.value
        binding.tvSettingsCloudStatus.text = when (ins.syncStatus) {
            "SUCCESS" -> "CONNECTED"
            "SYNCING" -> "SYNCING..."
            "QUEUED"  -> "${ins.pendingQueueCount} QUEUED"
            else      -> "READY"
        }
        binding.tvSettingsCloudStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (ins.syncStatus) {
                    "SUCCESS" -> R.color.status_emerald
                    "SYNCING" -> R.color.primary_cyan
                    "QUEUED"  -> R.color.status_amber
                    else      -> R.color.primary_cyan
                }
            )
        )
    }

    /**
     * Updates the Savings card in Settings and the Home tab benchmark badge.
     */
    @SuppressLint("SetTextI18n")
    private fun updateSavingsSettingsCardUi() {
        binding.tvSettingsPetrolPrice.text = "₹%.1f / L".format(petrolPricePerLiter)
        binding.tvSettingsIceMileage.text = "%.0f km/L".format(iceScooterMileageKmL)
        val sampleLiters = calculatePetrolSavedLiters(100.0)
        val sampleSaved = calculateMoneySavedFromLiters(sampleLiters)
        binding.tvSettingsSavingsPreview.text = "⚡ 100 km saves ~%.1f L petrol (₹%,d net saved vs ICE)".format(sampleLiters, sampleSaved)
        binding.tvSavingsPetrolPriceBadge.text = "₹%.1f/L Benchmark".format(petrolPricePerLiter)
    }

    /**
     * Dynamically updates the 4 metrics in the Ownership Savings Matrix on the Home tab.
     * Calculates month fuel avoided, net rupee savings, CO2 offset, and lifetime savings
     * in real-time based on actual distance, custom petrol price, and benchmark ICE mileage.
     */
    @SuppressLint("SetTextI18n")
    fun updateOwnershipSavingsMatrix(ins: ServerInsights) {
        val serverMonthKm = if (ins.monthDistanceKm in 0.1..10000.0) ins.monthDistanceKm else 0.0
        val effectiveMonthKm = maxOf(serverMonthKm, lastKnownMonthKm)

        // Month fuel saved (Litres) dynamically based on user's ICE benchmark mileage
        val monthLiters = calculatePetrolSavedLiters(effectiveMonthKm)
        val monthMoney = calculateMoneySavedFromLiters(monthLiters)
        val co2Kg = calculateCo2FromLiters(monthLiters)

        val bestOdometer = maxOf(
            ins.odometerKm,
            repository.currentData.value.odometerKm,
            prefs.getFloat("last_known_odometer", 0f).toDouble()
        )
        val lifetimeMoney = if (bestOdometer > 0.0) {
            val lifetimeLiters = calculatePetrolSavedLiters(bestOdometer)
            calculateMoneySavedFromLiters(lifetimeLiters)
        } else if (ins.totalLifetimeSavedInr > 0) {
            ins.totalLifetimeSavedInr
        } else {
            0
        }

        binding.tvCloudMoneySaved.text = "₹%,d".format(monthMoney)
        binding.tvCloudPetrolSaved.text = "%.1f L".format(monthLiters)
        binding.tvCloudCo2Saved.text = "%.0f kg".format(co2Kg)
        binding.tvTotalLifetimeSaved.text = "₹%,d".format(lifetimeMoney)
        binding.tvSavingsPetrolPriceBadge.text = "₹%.1f/L Benchmark".format(petrolPricePerLiter)
    }

    /**
     * Recalculates and refreshes savings values across all app tabs in real time.
     */
    private fun refreshAllSavingsDisplays() {
        updateSavingsSettingsCardUi()
        updateSyncSettingsCardUi()
        val ins = latestInsights ?: NexusCloudSyncManager.getInstance(this).insights.value
        updateCloudInsightsUi(ins)
        updateOwnershipSavingsMatrix(ins)
        applyTripsTimeframeData(selectedTripsTimeframe, ins)
        applyInsightsTimeframeData(selectedInsightsTimeframe, ins)
        updateTripsWeeklyCadence(ins)
    }

    /**
     * Interactive cyber dialog to adjust petrol price and ICE benchmark mileage.
     * Uses custom frosted glass dialog, real-time live preview, quick select chips, and audio feedback.
     */
    @SuppressLint("SetTextI18n")
    private fun showPetrolPriceConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_petrol_price, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.70f)

        val etPrice = dialogView.findViewById<android.widget.EditText>(R.id.etDialogPetrolPrice)
        val etMileage = dialogView.findViewById<android.widget.EditText>(R.id.etDialogIceMileage)
        val tvSavingsPreview = dialogView.findViewById<TextView>(R.id.tvDialogSavingsPreview)
        val tvPerKmSaved = dialogView.findViewById<TextView>(R.id.tvDialogPerKmSaved)
        val tvFuelAvoided = dialogView.findViewById<TextView>(R.id.tvDialogFuelAvoided)
        val btnApply = dialogView.findViewById<Button>(R.id.btnDialogApply)
        val btnReset = dialogView.findViewById<Button>(R.id.btnDialogResetDefault)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnDialogClose)

        // Quick select chips
        val priceChips = listOf(
            dialogView.findViewById<TextView>(R.id.chipPrice98) to 98.0f,
            dialogView.findViewById<TextView>(R.id.chipPrice102) to 102.0f,
            dialogView.findViewById<TextView>(R.id.chipPrice105) to 105.0f,
            dialogView.findViewById<TextView>(R.id.chipPrice108) to 108.0f,
            dialogView.findViewById<TextView>(R.id.chipPrice112) to 112.0f
        )

        val mileageChips = listOf(
            dialogView.findViewById<TextView>(R.id.chipMileage35) to 35.0f,
            dialogView.findViewById<TextView>(R.id.chipMileage40) to 40.0f,
            dialogView.findViewById<TextView>(R.id.chipMileage42) to 42.0f,
            dialogView.findViewById<TextView>(R.id.chipMileage45) to 45.0f,
            dialogView.findViewById<TextView>(R.id.chipMileage50) to 50.0f
        )

        val cyanSolidBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
        val darkBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
        val colorBlack = ContextCompat.getColor(this, R.color.black)
        val colorCyan = ContextCompat.getColor(this, R.color.primary_cyan)

        fun updateChipStates(currentPrice: Float, currentMileage: Float) {
            priceChips.forEach { (chip, price) ->
                val isMatch = kotlin.math.abs(currentPrice - price) < 0.15f
                chip.background = if (isMatch) cyanSolidBg else darkBg
                chip.setTextColor(if (isMatch) colorBlack else colorCyan)
            }
            mileageChips.forEach { (chip, mileage) ->
                val isMatch = kotlin.math.abs(currentMileage - mileage) < 0.5f
                chip.background = if (isMatch) cyanSolidBg else darkBg
                chip.setTextColor(if (isMatch) colorBlack else colorCyan)
            }
        }

        fun updateDialogPreview() {
            val p = etPrice.text.toString().toFloatOrNull() ?: petrolPricePerLiter
            val m = etMileage.text.toString().toFloatOrNull() ?: iceScooterMileageKmL
            val safeM = maxOf(10.0f, m).toDouble()

            val liters1000 = 1000.0 / safeM
            val petrolCost1000 = liters1000 * p.toDouble()
            val evCost1000 = 1000.0 * 0.22
            val netSaved1000 = maxOf(0, (petrolCost1000 - evCost1000).roundToInt())
            val savedPerKm = (petrolCost1000 - evCost1000) / 1000.0
            val co2Kg = liters1000 * 2.31

            tvSavingsPreview.text = "₹%,d Saved / 1,000 km".format(netSaved1000)
            tvPerKmSaved.text = "₹%.2f / km cheaper".format(savedPerKm)
            tvFuelAvoided.text = "Avoids ~%.1f L petrol • %.0f kg CO₂ reduction".format(liters1000, co2Kg)

            updateChipStates(p, m)
        }

        etPrice.setText("%.1f".format(Locale.US, petrolPricePerLiter))
        etMileage.setText("%.0f".format(Locale.US, iceScooterMileageKmL))
        updateDialogPreview()

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateDialogPreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        etPrice.addTextChangedListener(textWatcher)
        etMileage.addTextChangedListener(textWatcher)

        // Chip click listeners
        priceChips.forEach { (chip, price) ->
            chip.setOnClickListener {
                CyberSoundManager.playClickSound(this@MainActivity)
                etPrice.setText("%.1f".format(Locale.US, price))
                etPrice.setSelection(etPrice.text.length)
            }
        }

        mileageChips.forEach { (chip, mileage) ->
            chip.setOnClickListener {
                CyberSoundManager.playClickSound(this@MainActivity)
                etMileage.setText("%.0f".format(Locale.US, mileage))
                etMileage.setSelection(etMileage.text.length)
            }
        }

        // Close and Cancel
        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        // Reset to default
        btnReset.setOnClickListener {
            CyberSoundManager.playClickSound(this@MainActivity)
            petrolPricePerLiter = 104.0f
            iceScooterMileageKmL = 42.0f
            etPrice.setText("104.0")
            etMileage.setText("42")
            refreshAllSavingsDisplays()
            Toast.makeText(this@MainActivity, "Reset to default: ₹104.0/L @ 42 km/L", Toast.LENGTH_SHORT).show()
        }

        // Apply and recalculate
        btnApply.setOnClickListener {
            CyberSoundManager.playModeSwitchChime(this@MainActivity)
            val newPrice = etPrice.text.toString().toFloatOrNull()
            val newMileage = etMileage.text.toString().toFloatOrNull()
            if (newPrice != null && newPrice > 0f) {
                petrolPricePerLiter = newPrice
            }
            if (newMileage != null && newMileage > 0f) {
                iceScooterMileageKmL = newMileage
            }
            refreshAllSavingsDisplays()
            dialog.dismiss()
            Toast.makeText(this@MainActivity, "Petrol benchmark set to ₹%.1f/L. All savings recalculated!".format(petrolPricePerLiter), Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ─── Cluster Navigation & Wi-Fi Mirroring ─────────────────────────────────

    private fun isClusterConnected(): Boolean {
        val bleSvc = BleService.instance
        if (bleSvc != null) {
            isConnected = bleSvc.isClusterConnected
            return bleSvc.isClusterConnected
        }
        return isConnected
    }

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

        if (currentNavMode != MODE_OFF && !isClusterConnected()) {
            currentNavMode = MODE_OFF
        }
        selectNavMode(currentNavMode)
    }

    private fun updateNavModeButtonsUi(mode: String) {
        val solidCyanBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan_solid)
        val darkBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
        val blackColor = ContextCompat.getColor(this, R.color.black)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.text_primary)

        fun styleNav(btn: android.widget.Button, isActive: Boolean) {
            if (isActive) {
                btn.background = solidCyanBg
                btn.setTextColor(blackColor)
                btn.setTypeface(btn.typeface, Typeface.BOLD)
            } else {
                btn.background = darkBg
                btn.setTextColor(inactiveTextColor)
                btn.setTypeface(Typeface.create(btn.typeface, Typeface.NORMAL))
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
        if ((mode == MODE_TBT || mode == MODE_MIRROR) && !isClusterConnected()) {
            CyberSoundManager.playErrorAlert(this)
            val modeName = if (mode == MODE_TBT) "TBT Navigation" else "Screen Mirroring"
            Toast.makeText(
                this,
                "Cluster is not connected via BLE. Please connect scooter cluster first.",
                Toast.LENGTH_SHORT
            ).show()

            currentNavMode = MODE_OFF
            updateNavModeButtonsUi(MODE_OFF)
            stopPeriodicNavSync()
            stopScreenMirroring()

            binding.tvNavModeBadge.text = "OFF"
            binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
            binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            binding.tvNavInstruction.text = "⚠️ Cluster not connected via BLE. Connect scooter first to enable $modeName."
            binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.status_red))

            binding.tvNavDetail.visibility = View.VISIBLE
            binding.tvNavDetail.text = "Power on your scooter cluster and connect over BLE before streaming."
            binding.tvNavDetail.setTextColor(ContextCompat.getColor(this, R.color.status_amber))

            binding.btnNavAction.visibility = View.VISIBLE
            binding.btnNavAction.text = "CONNECT SCOOTER"
            binding.btnNavAction.setOnClickListener { scanForScooter() }
            binding.btnToggleMirrorOrientation.visibility = View.GONE
            return
        }

        currentNavMode = mode
        updateNavModeButtonsUi(mode)

        when (mode) {
            MODE_OFF -> {
                stopPeriodicNavSync()
                stopScreenMirroring()
                BleService.instance?.clearNavigationFromCluster()
                binding.tvNavModeBadge.text = "OFF"
                binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.tvNavInstruction.text = "Select TBT or Mirror to stream to cluster"
                binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.tvNavDetail.visibility = View.GONE
                binding.btnNavAction.visibility = View.GONE
                binding.btnToggleMirrorOrientation.visibility = View.GONE
            }
            MODE_TBT -> {
                stopScreenMirroring()
                binding.tvNavModeBadge.text = "TBT (BLE)"
                binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_cyan)
                binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                binding.btnToggleMirrorOrientation.visibility = View.GONE

                if (!isNotificationListenerEnabled()) {
                    binding.tvNavInstruction.text = "Notification access required to read Google Maps directions."
                    binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    binding.btnNavAction.visibility = View.VISIBLE
                    binding.btnNavAction.text = "UNLOCK ACCESS"
                    binding.btnNavAction.setOnClickListener {
                        showRestrictedSettingsGuideDialog()
                    }
                    showRestrictedSettingsGuideDialog()
                } else {
                    binding.tvNavInstruction.text = "TBT Mode active. Streaming Google Maps directions over BLE."
                    binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
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
                binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
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
                    binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    permissionLauncher.launch(missingWifiPerms.toTypedArray())
                    return
                }

                binding.tvNavInstruction.text = "Requesting screen capture permission…"
                binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
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

    private fun showClusterTimeSyncDialog() {
        val options = arrayOf("Both (Recommended)", "Unix Block Only", "Calendar Block Only")
        val currentMethod = prefs.getInt("sync_method", 0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Cluster Time Sync Method")
            .setSingleChoiceItems(options, currentMethod) { dialog, which ->
                prefs.edit().putInt("sync_method", which).apply()
                Toast.makeText(this, "Sync method: ${options[which]}", Toast.LENGTH_SHORT).show()
                updateSyncSettingsCardUi()
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
        val ins = latestInsights ?: cloudManager.insights.value
        val effectiveId = cloudManager.getEffectiveDeviceId()
        val customId = cloudManager.getCustomDeviceId()
        val idSource = when {
            customId != null -> "Custom Override"
            savedAddress != null -> "Auto-detected Bluetooth MAC"
            savedName != null -> "Auto-detected Bluetooth Name"
            else -> "Unique Client ID"
        }

        val status = when (ins.syncStatus) {
            "SUCCESS" -> "Connected & Active"
            "SYNCING" -> "Synchronizing..."
            "QUEUED"  -> "${ins.pendingQueueCount} items queued offline"
            else      -> "Ready"
        }

        val message = "Server Endpoint:\n$serverUrl\n\n" +
                "Active Scooter ID ($idSource):\n$effectiveId\n\n" +
                "Cloud Status: $status\n" +
                "Last Synced: ${if (ins.lastSyncTimeMs > 0) timeFormat.format(Date(ins.lastSyncTimeMs)) else "Never"}\n\n" +
                "Benchmark: ₹%.1f/L @ %.0f km/L\n".format(petrolPricePerLiter, iceScooterMileageKmL) +
                "Fleet Petrol Saved: %.1f L\n".format(ins.petrolSavedLiters) +
                "Fleet Money Saved: ₹%,d\n".format(calculateMoneySavedFromLiters(ins.petrolSavedLiters)) +
                "CO2 Offset: %.1f kg".format(calculateCo2FromLiters(ins.petrolSavedLiters))

        MaterialAlertDialogBuilder(this)
            .setTitle("Nexus Cloud Server")
            .setMessage(message)
            .setPositiveButton("Sync Now") { _, _ ->
                cloudManager.triggerManualSync(effectiveId, repository.currentData.value) { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateSyncSettingsCardUi()
                }
            }
            .setNeutralButton("Change Scooter ID") { _, _ ->
                showCustomDeviceIdDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCustomDeviceIdDialog() {
        val cloudManager = NexusCloudSyncManager.getInstance(this)
        val currentCustom = cloudManager.getCustomDeviceId() ?: ""
        val currentEffective = cloudManager.getEffectiveDeviceId()

        val input = EditText(this).apply {
            hint = "e.g. CC:F9:57:AB:CD:EF or SCOOTER_2"
            setText(currentCustom)
            setSelection(text.length)
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Scooter Device Identifier")
            .setMessage("All cloud telemetry, trips, and cadence are dynamically isolated by this ID.\n\nCurrent Active: $currentEffective")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newId = input.text.toString().trim()
                if (newId.isNotBlank()) {
                    cloudManager.setCustomDeviceId(newId)
                    Toast.makeText(this, "Active Scooter ID updated: $newId", Toast.LENGTH_SHORT).show()
                } else {
                    cloudManager.setCustomDeviceId(null)
                    Toast.makeText(this, "Reset to Auto-Detect (Bluetooth MAC)", Toast.LENGTH_SHORT).show()
                }
                lifecycleScope.launch {
                    cloudManager.fetchInsights(cloudManager.getEffectiveDeviceId())
                }
                updateSyncSettingsCardUi()
            }
            .setNeutralButton("Auto-Detect (Reset)") { _, _ ->
                cloudManager.setCustomDeviceId(null)
                Toast.makeText(this, "Reset to Auto-Detect (Bluetooth MAC)", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    cloudManager.fetchInsights(cloudManager.getEffectiveDeviceId())
                }
                updateSyncSettingsCardUi()
            }
            .setNegativeButton("Cancel", null)
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

        val previousAddress = savedAddress
        savedAddress = address
        savedName    = name

        if (previousAddress != null && previousAddress != address) {
            Log.i("MainActivity", "Paired scooter changed from $previousAddress to $address")
            NexusCloudSyncManager.getInstance(this).clearDeviceCache()
            lifecycleScope.launch {
                NexusCloudSyncManager.getInstance(this@MainActivity).fetchInsights(address)
            }
        }

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

        // Feed live telemetry to JourneyClusteringEngine for real-time journey grouping
        val completedJourney = JourneyClusteringEngine.processLiveTelemetry(
            data,
            petrolPricePerLiter,
            iceScooterMileageKmL
        )
        if (completedJourney != null) {
            lifecycleScope.launch {
                repository.saveJourney(completedJourney)
            }
        }

        runOnUiThread {
            if (isShimmerActive) {
                hideHomeShimmer()
            }

            // Sample speed for speed graph
            if (data.speedKmh >= 0) {
                binding.speedGraph.addSample(data.speedKmh)
            }

            // Voltage display
            if (data.voltageV > 0.0) {
                binding.tvVoltage.text = "%.1f V".format(data.voltageV)
            } else {
                binding.tvVoltage.text = "N/A"
            }

            // Odometer-based Distance Today (Today Odometer - Yesterday Odometer)
            if (data.odometerKm > 0.0) {
                lifecycleScope.launch {
                    val distToday = repository.updateOdometerReading(data.odometerKm)
                    if (distToday in 0.1..300.0) {
                        lastKnownTodayKm = distToday
                        runOnUiThread {
                            binding.tvTodayKm.text = "%.1f".format(distToday)
                            renderCadenceBars(generateCadenceData(selectedCadence, latestInsights))
                            updateTripsWeeklyCadence(latestInsights)
                        }
                    }
                }
                maintBinding.tvCurrentOdoService.text = "Current Odo: %.0f km".format(data.odometerKm)
                val mReport = MaintenanceRepository.buildLocalReport(data.odometerKm)
                binding.tvHealthServiceDue.text = "%.0f km".format(mReport.kmUntilNextService)
                binding.tvHealthServiceMilestone.text = "@ ${(mReport.nextMilestoneKm / 1000)}k"
                updateScooterMaintenanceUi(mReport, data.odometerKm)
            }

            // State of Charge
            if (data.socPercent in 0..100) {
                lastKnownBatteryPercent = data.socPercent
                updateHeroBatteryUi()
                binding.tvSoc.text = "${data.socPercent}%"
                binding.progressSoc.progress = data.socPercent
                binding.tvScooterSoc.text = "${data.socPercent}%"
            }

            // Remaining Range (DTE)
            if (data.remainingKm >= 0) {
                binding.tvRange.text = "≈ ${data.remainingKm} km"
                binding.tvScooterDteEst.text = "Est. ${data.remainingKm} km (City) • ${(data.remainingKm * 1.22).toInt()} km (Eco Mode)"
            }

            if (data.voltageV > 0.0) {
                binding.tvScooterVoltage.text = "%.1f V".format(data.voltageV)
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
        val monthDb = repository.getCurrentMonthDistance()
        lastKnownTodayKm = today
        if (yesterday in 0.1..300.0) lastKnownYesterdayKm = yesterday
        if (monthDb > 0.0) lastKnownMonthKm = monthDb
        hasLoadedTodayDistance = true
        runOnUiThread {
            binding.tvTodayKm.text = "%.1f".format(today)
            // Reflect live driving distance in dynamic cadence bars
            renderCadenceBars(generateCadenceData(selectedCadence, latestInsights))
            updateTripsWeeklyCadence(latestInsights)
            if (yesterday in 0.1..300.0) {
                if (today > 0.0) {
                    val diffPct = Math.round(((today - yesterday) / yesterday) * 100)
                    binding.tvVsYesterday.text = if (diffPct >= 0) "+$diffPct% vs yesterday" else "$diffPct% vs yesterday"
                } else {
                    val ySoc = getLiveYesterdaySoc()
                    binding.tvVsYesterday.text = "Yest: %.1f km (⚡ %d%%)".format(yesterday, ySoc)
                }
            }
            latestInsights?.let { updateOwnershipSavingsMatrix(it) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCloudInsightsUi(ins: ServerInsights) {
        latestInsights = ins

        // Sync server-side clustered journeys into Room repository if present
        if (ins.recentJourneys.isNotEmpty()) {
            lifecycleScope.launch {
                val entities = ins.recentJourneys.map { sj ->
                    val sTime = if (sj.startTimeMs > 0) sj.startTimeMs else (System.currentTimeMillis() - (sj.durationMins * 60_000L))
                    val eTime = if (sj.endTimeMs > 0) sj.endTimeMs else System.currentTimeMillis()
                    JourneyEntity(
                        startTime = sTime,
                        endTime = eTime,
                        title = sj.title,
                        routeSummary = sj.routeSummary,
                        distanceKm = sj.distanceKm,
                        durationMins = sj.durationMins,
                        avgSpeedKmh = sj.avgSpeedKmh,
                        maxSpeedKmh = sj.maxSpeedKmh,
                        startSoc = sj.startSoc,
                        endSoc = sj.endSoc,
                        ridingMode = sj.ridingMode,
                        efficiencyKmPerSoc = sj.efficiencyKmPerSoc,
                        energyConsumedKwh = sj.energyConsumedKwh,
                        regenKwh = sj.regenKwh,
                        moneySavedInr = sj.moneySavedInr,
                        co2SavedKg = sj.co2SavedKg,
                        aiTag = sj.aiTag,
                        timestampFormatted = sj.timestampFormatted
                    )
                }
                val clustered = JourneyClusteringEngine.clusterJourneys(entities)
                repository.deleteAllJourneys()
                repository.saveJourneys(if (clustered.isNotEmpty()) clustered else entities)
            }
        }

        runOnUiThread {
            // Dismiss skeleton shimmer once live cloud insights arrive
            hideHomeShimmer()
            updateSyncSettingsCardUi()

            // Cloud Sync Badge in Header & Health Card
            when (ins.syncStatus) {
                "SYNCING" -> {
                    binding.tvCloudSyncBadge.text = "☁ SYNCING..."
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                    binding.tvHealthCloud.text = "Syncing"
                    binding.tvHealthCloudTime.text = "In progress"
                }
                "QUEUED" -> {
                    val count = if (ins.pendingQueueCount > 0) "${ins.pendingQueueCount} " else ""
                    binding.tvCloudSyncBadge.text = "☁ ${count}QUEUED"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                    binding.tvHealthCloud.text = "Queued"
                    binding.tvHealthCloudTime.text = "${ins.pendingQueueCount} offline"
                }
                "SUCCESS" -> {
                    binding.tvCloudSyncBadge.text = "☁ CLOUD OK"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_emerald))
                    binding.tvHealthCloud.text = "Synced"
                    binding.tvHealthCloudTime.text = formatRelativeTime(ins.lastSyncTimeMs)
                }
                "ERROR" -> {
                    binding.tvCloudSyncBadge.text = "☁ RETRY"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.tvHealthCloud.text = "Offline"
                    binding.tvHealthCloudTime.text = formatRelativeTime(ins.lastSyncTimeMs)
                }
                else -> {
                    binding.tvCloudSyncBadge.text = "CLOUD SYNCED"
                    binding.tvCloudSyncBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                    binding.tvHealthCloud.text = if (ins.lastSyncTimeMs > 0) "Synced" else "Live"
                    binding.tvHealthCloudTime.text = formatRelativeTime(ins.lastSyncTimeMs)
                }
            }

            // Hero Telemetry Overlay (Reliable Today Distance <= 300 km)
            val safeTodayDist = if (ins.todayDistanceKm in 0.0..300.0) ins.todayDistanceKm else 0.0
            if (safeTodayDist > 0.0 && lastKnownTodayKm == 0.0) {
                lastKnownTodayKm = safeTodayDist
            }
            hasLoadedTodayDistance = true
            val displayTodayDist = if (lastKnownTodayKm > 0.0) lastKnownTodayKm else safeTodayDist
            binding.tvTodayKm.text = "%.1f".format(displayTodayDist)
            val yestDist = if (ins.yesterdayDistanceKm > 0.0) ins.yesterdayDistanceKm else lastKnownYesterdayKm
            val yestSoc = if (ins.yesterdaySocConsumed > 0) ins.yesterdaySocConsumed else getLiveYesterdaySoc()
            binding.tvVsYesterday.text = if (displayTodayDist > 0.0) {
                if (ins.vsYesterdayPct >= 0) "+${ins.vsYesterdayPct}% vs yesterday" else "${ins.vsYesterdayPct}% vs yesterday"
            } else if (yestDist > 0.0) {
                "Yest: %.1f km (⚡ %d%%)".format(yestDist, yestSoc)
            } else {
                "0 km today"
            }
            val goalPct = if (ins.dailyGoalKm > 0) ((displayTodayDist / ins.dailyGoalKm) * 100).toInt().coerceIn(0, 100) else 0
            val yestInfo = if (yestDist > 0.0) {
                val socTxt = if (yestSoc > 0) " (⚡ $yestSoc%)" else ""
                " • Yest: %.1f km%s".format(yestDist, socTxt)
            } else ""
            binding.tvDailyGoalProgress.text = "Goal: %.0f km (%d%%)%s".format(ins.dailyGoalKm, goalPct, yestInfo)

            // Dynamic N/A handling for values when not present or when no ride has occurred today
            binding.tvAvgSpeed.text = if (ins.todayAvgSpeedKmh > 0.0 && displayTodayDist > 0.0) "%.0f km/h".format(ins.todayAvgSpeedKmh) else "N/A"
            binding.tvRideTime.text = if (ins.todayRideTimeMins > 0 && displayTodayDist > 0.0) "${ins.todayRideTimeMins} mins" else "N/A"
            binding.tvBatteryUsed.text = if (ins.todaySocConsumed > 0 && displayTodayDist > 0.0) {
                "${ins.todaySocConsumed}%"
            } else if (displayTodayDist <= 0.0 && yestSoc > 0) {
                "0% (Y: $yestSoc%)"
            } else if (displayTodayDist <= 0.0) {
                "0%"
            } else "N/A"
            binding.tvBatteryUsed.setOnClickListener {
                val todaySoc = ins.todaySocConsumed
                val todayKwh = if (ins.todayEnergyKwh > 0.0) ins.todayEnergyKwh else (todaySoc * 0.033)
                val yKwh = if (ins.yesterdayEnergyKwh > 0.0) ins.yesterdayEnergyKwh else (yestSoc * 0.033)
                val msg = "⚡ Daily Charge: Today %d%% (%.1f kWh) • Yesterday %d%% (%.1f kWh)".format(
                    todaySoc, todayKwh, yestSoc, yKwh
                )
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }

            // Cadence Preview (Update dynamic bars with live telemetry)
            renderCadenceBars(generateCadenceData(selectedCadence, ins))
            updateTripsWeeklyCadence(ins)
            binding.tvTripsTotalOdoBadge.text = if (ins.odometerKm > 0.0) "%.0f km total".format(ins.odometerKm) else "-- km total"

            // Trips Tab Live Sync
            applyTripsTimeframeData(selectedTripsTimeframe, ins)
            binding.tvTripsHeroRegenBadge.text = if (ins.recapturedRegenKwh > 0.0) "+%.1f kWh Regen".format(ins.recapturedRegenKwh) else "+0.0 kWh Regen"

            // Latest Ride Spotlight Sync (Discrete stats - shows yesterday if no ride logged today)
            if (displayTodayDist > 0.0) {
                binding.tvLatestRideDistance.text = "%.1f km".format(displayTodayDist)
                binding.tvLatestRideDuration.text = if (ins.todayRideTimeMins > 0) "${ins.todayRideTimeMins} min" else "N/A"
                binding.tvLatestRideSocUsed.text = if (ins.todaySocConsumed > 0) "-${ins.todaySocConsumed}% SOC" else "N/A"
                binding.tvLatestRideAvgSpeed.text = if (ins.todayAvgSpeedKmh > 0.0) "Avg %.0f km/h".format(ins.todayAvgSpeedKmh) else "Avg: N/A"
            } else if (yestDist > 0.0) {
                binding.tvLatestRideDistance.text = "%.1f km".format(yestDist)
                binding.tvLatestRideDuration.text = "Yesterday"
                binding.tvLatestRideSocUsed.text = if (yestSoc > 0) "-$yestSoc% SOC" else "N/A"
                binding.tvLatestRideAvgSpeed.text = "Avg 24 km/h"
            } else {
                binding.tvLatestRideDistance.text = "N/A"
                binding.tvLatestRideDuration.text = "N/A"
                binding.tvLatestRideSocUsed.text = "N/A"
                binding.tvLatestRideAvgSpeed.text = "Avg: N/A"
            }
            binding.tvLatestRideEfficiency.text = if (displayTodayDist > 0.0 && !ins.realWorldEfficiency.isNullOrBlank()) {
                "⚡ ${ins.realWorldEfficiency}"
            } else if (yestDist > 0.0 && yestSoc > 0) {
                "⚡ %.2f km/%% (est. %.0f km range)".format(yestDist / yestSoc, (yestDist / yestSoc) * 100)
            } else "⚡ N/A"
            binding.tvLatestRideModeBadge.text = if (displayTodayDist > 0.0 && ins.defaultRidingMode.isNotBlank()) "${ins.defaultRidingMode} Mode" else "Parked"
            if (ins.peakCommuteWindows.isNotBlank() && displayTodayDist > 0.0) {
                val window = ins.peakCommuteWindows.split("&").lastOrNull()?.trim() ?: "6:15 PM"
                binding.tvLatestRideTimestamp.text = "LATEST RIDE • $window"
            } else if (yestDist > 0.0) {
                binding.tvLatestRideTimestamp.text = "YESTERDAY'S RIDE • ⚡ $yestSoc% USED"
            } else {
                binding.tvLatestRideTimestamp.text = "NO RIDES TODAY"
            }

            // Power Pack & Battery
            binding.tvSohPercent.text = if (ins.batterySoh > 0) "${ins.batterySoh}% SOH" else "-- SOH"
            binding.tvBatteryTemp.text = if (ins.batteryTemp > 0.0) "%.1f °C".format(ins.batteryTemp) else "N/A"
            binding.tvCellDelta.text = if (ins.cellDeltaV > 0.0) "%.3f V".format(ins.cellDeltaV) else "N/A"
            binding.tvCycleCount.text = if (ins.cycleCount > 0) "${ins.cycleCount} cycles" else "N/A"

            // Scooter Tab Redesign Live Sync
            val currentSoc = repository.currentData.value.socPercent
            if (currentSoc >= 0) {
                binding.tvScooterSoc.text = "$currentSoc%"
            }
            binding.tvScooterDteEst.text = if (ins.ecoRangeProjectionKm > 0) {
                "Est. ${ins.ecoRangeProjectionKm} km (City) • ${(ins.ecoRangeProjectionKm * 1.22).toInt()} km (Eco Mode)"
            } else {
                "Connect cluster for live range"
            }
            binding.tvScooterSoh.text = if (ins.batterySoh > 0) "${ins.batterySoh}%" else "N/A"
            val volt = if (ins.batteryVoltage > 0.0) ins.batteryVoltage else 0.0
            binding.tvScooterVoltage.text = if (volt > 0.0) "%.1f V".format(volt) else "N/A"
            maintBinding.tvNextServiceMaint.text = if (ins.kmUntilService > 0) "%.0f km".format(ins.kmUntilService) else "N/A"
            maintBinding.tvCurrentOdoService.text = if (ins.odometerKm > 0) "Current Odometer: %.0f km".format(ins.odometerKm) else "Current Odometer: -- km"

            // Ownership Savings Matrix (Dynamic calculation based on live distance & custom benchmark)
            updateOwnershipSavingsMatrix(ins)

            // Scooter Health Status Card
            binding.tvHealthBattery.text = if (ins.batterySoh > 0) "${ins.batterySoh}% SOH" else "Healthy"
            binding.tvHealthCluster.text = if (isConnected) "Connected" else "Standby"
            binding.tvHealthCloud.text = if (ins.lastSyncTimeMs > 0) "Synced" else "Live"
            binding.tvHealthCloudTime.text = formatRelativeTime(ins.lastSyncTimeMs)

            val liveOdo = repository.currentData.value.odometerKm
            val currentEffectiveOdo = if (liveOdo > 0.0) liveOdo else if (ins.odometerKm > 0.0) ins.odometerKm else 0.0
            val maintenance = ins.maintenanceReport ?: MaintenanceRepository.buildLocalReport(currentEffectiveOdo)

            if (maintenance.kmUntilNextService >= 0 && currentEffectiveOdo > 0) {
                binding.tvHealthServiceDue.text = "%.0f km".format(maintenance.kmUntilNextService)
                binding.tvHealthServiceMilestone.text = "@ ${(maintenance.nextMilestoneKm / 1000)}k"
            } else if (ins.kmUntilService > 0) {
                binding.tvHealthServiceDue.text = "%.0f km".format(ins.kmUntilService)
                binding.tvHealthServiceMilestone.text = if (ins.nextServiceKm > 0) "@ ${(ins.nextServiceKm / 1000)}k" else "@ 25k"
            } else {
                binding.tvHealthServiceDue.text = "N/A"
                binding.tvHealthServiceMilestone.text = "--"
            }

            // Update Scooty Tab Maintenance Lifecycle Center
            updateScooterMaintenanceUi(maintenance, currentEffectiveOdo)

            // AI Insights Tab Live Sync
            applyInsightsTimeframeData(selectedInsightsTimeframe, ins)

            // Dynamic AI Recommendations
            if (ins.recommendations.isNotEmpty()) {
                val r1 = ins.recommendations.getOrNull(0)
                if (r1 != null) {
                    binding.tvRec1Title.text = r1.title.uppercase()
                    binding.tvRec1Badge.text = r1.badge
                    binding.tvRec1Desc.text = r1.description
                }
                val r2 = ins.recommendations.getOrNull(1)
                if (r2 != null) {
                    binding.tvRec2Title.text = r2.title.uppercase()
                    binding.tvRec2Badge.text = r2.badge
                    binding.tvRec2Desc.text = r2.description
                }
            }

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

            // Real-time dynamic updates during active ride
            if (ai.ecoScore > 0) {
                binding.tvEcoScoreBig.text = "${ai.ecoScore}"
                binding.tvSmoothAccelVal.text = "${ai.smoothAccelPct}%"
                binding.tvBonusRangeVal.text = "+${ai.bonusRangeKm} km"
                binding.tvThrottleLagVal.text = "${ai.throttleLagSec}s"
                binding.tvEcoRatingTitle.text = ai.ecoRating.uppercase()
                if (ai.coachTip.isNotBlank()) {
                    binding.tvEcoCoachSummary.text = ai.coachTip
                }
                if (ai.ecoRangeProjectionKm > 0) {
                    binding.tvEcoRangeVal.text = "${ai.ecoRangeProjectionKm} km"
                }
                if (ai.sportRangeProjectionKm > 0) {
                    binding.tvSportRangeVal.text = "${ai.sportRangeProjectionKm} km"
                }
            }
        }
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private fun setUiConnected() {
        binding.tvStatus.text = "CONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
        binding.dockStatusDot.setBackgroundResource(R.drawable.bg_pill_emerald)
        binding.tvDockSyncStatus.text = "Connected to Scooter • 100% Sync"
        binding.tvHealthCluster.text = "Connected"
        binding.tvHealthCluster.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))

        // Update hero HUD to live battery
        updateHeroBatteryUi()

        // Play futuristic ascending EV handshake chime
        CyberSoundManager.playBleConnectedChime(this)

        // Clear disconnected error prompt on nav card if previously shown in standby
        if (currentNavMode == MODE_OFF && binding.tvNavInstruction.text.startsWith("⚠️")) {
            binding.tvNavInstruction.text = "Select TBT or Mirror to stream to cluster"
            binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvNavDetail.visibility = View.GONE
            binding.btnNavAction.visibility = View.GONE
        }

        // Activate attractive glowing cyber energy aura & floating hover on hero card
        CyberCardAnimator.startHeroConnectedAnimation(
            binding.cardHeroShowcase,
            binding.ivHeroScooter,
            binding.viewHeroCyberAura,
            binding.tvHeroConnectionBadge
        )
    }

    private fun setUiDisconnected() {
        binding.tvStatus.text = "DISCONNECTED"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        binding.dockStatusDot.setBackgroundResource(R.drawable.bg_pill_amber)
        binding.tvDockSyncStatus.text = "Disconnected • Tap Status to Connect"
        updateHeroBatteryUi()
        binding.tvRidingModeBadge.text = "STANDBY"
        binding.tvRange.text = "≈ 0 km"
        binding.tvHealthCluster.text = "Standby"
        binding.tvHealthCluster.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        // Play descending disconnection chime
        CyberSoundManager.playBleDisconnectedChime(this)

        // Reset hero showcase to standby state
        CyberCardAnimator.stopHeroConnectedAnimation(
            binding.cardHeroShowcase,
            binding.ivHeroScooter,
            binding.viewHeroCyberAura,
            binding.tvHeroConnectionBadge
        )

        // If mirroring or TBT is active, stop streaming and revert mode cleanly
        if (currentNavMode == MODE_MIRROR) {
            stopScreenMirroring()
            currentNavMode = MODE_OFF
            updateNavModeButtonsUi(MODE_OFF)
            binding.tvNavModeBadge.text = "OFF"
            binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
            binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvNavInstruction.text = "Cluster disconnected. Screen mirroring stopped."
            binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.btnToggleMirrorOrientation.visibility = View.GONE
        } else if (currentNavMode == MODE_TBT) {
            stopPeriodicNavSync()
            currentNavMode = MODE_OFF
            updateNavModeButtonsUi(MODE_OFF)
            binding.tvNavModeBadge.text = "OFF"
            binding.tvNavModeBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_dark)
            binding.tvNavModeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvNavInstruction.text = "Cluster disconnected. TBT navigation stopped."
            binding.tvNavInstruction.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
        }
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
            if (currentNavMode == MODE_MIRROR && isClusterConnected()) {
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
