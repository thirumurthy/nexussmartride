package com.thirutricks.nexussmartride

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.regex.Pattern

/**
 * Listens for active Google Maps turn-by-turn navigation notifications and converts
 * navigation instructions (direction maneuver, distance, road name) into Ampere Nexus
 * cluster BLE packets (Option A).
 *
 * Also monitors global media sessions (YouTube, Spotify, Apple Music, etc.) to stream
 * currently playing song titles and artist names to the scooter cluster display and handle
 * handlebar next/previous track controls.
 */
class MapNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MapNavListener"
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        const val ACTION_NAV_UPDATE = "com.thirutricks.nexussmartride.NAV_UPDATE"
        const val EXTRA_IS_NAVIGATING = "is_navigating"
        const val EXTRA_MANEUVER = "maneuver"
        const val EXTRA_DISTANCE_TEXT = "distance_text"
        const val EXTRA_ROAD_NAME = "road_name"
        const val EXTRA_REMAINING_TEXT = "remaining_text"

        // Direction Opcodes matching Ampere Nexus cluster firmware (Function 16921):
        const val MANEUVER_SLIGHT_RIGHT = 0x01
        const val MANEUVER_SLIGHT_LEFT  = 0x02
        const val MANEUVER_STRAIGHT     = 0x03
        const val MANEUVER_LEFT         = 0x04
        const val MANEUVER_RIGHT        = 0x05
        const val MANEUVER_UTURN_LEFT   = 0x06
        const val MANEUVER_UTURN_RIGHT  = 0x07 // Also Roundabout
        const val MANEUVER_UTURN        = MANEUVER_UTURN_LEFT
        const val MANEUVER_ROUNDABOUT   = MANEUVER_UTURN_RIGHT
        const val MANEUVER_CLEAR        = 0xFF

        val SUPPORTED_NAV_PACKAGES = setOf(
            GOOGLE_MAPS_PACKAGE,
            "com.google.android.apps.mapslite",
            "com.mmi.maps",
            "com.mappls.mmi",
            "com.waze",
            "net.osmand",
            "net.osmand.plus"
        )

        @Volatile
        var instance: MapNotificationListenerService? = null
            private set

        @Volatile
        var activeMediaController: MediaController? = null

        @Volatile
        var cachedLastSongTitle: String? = null
        @Volatile
        var cachedLastArtist: String? = null
        @Volatile
        var cachedLastIsPlaying: Boolean = false

        @Volatile
        var cachedLastManeuver: Int = MANEUVER_CLEAR
        @Volatile
        var cachedLastTurnDistMeters: Int = 0
        @Volatile
        var cachedLastTotalDistMeters: Int = 0
        @Volatile
        var cachedLastRoadName: String = ""
        @Volatile
        var cachedLastNavEta: String = ""

        /**
         * Triggers immediate synchronization of currently playing song metadata.
         */
        fun triggerActiveMediaSync() {
            instance?.syncActiveMediaNow()
        }

        /**
         * Triggers immediate synchronization of active turn-by-turn navigation cues.
         */
        fun triggerActiveNavigationSync(): Boolean {
            return instance?.syncActiveNavigationNow() ?: false
        }
    }

    private var isNavigating = false
    private var lastRoadName = ""
    private var lastDistanceMeters = -1
    private var lastTotalDistMeters = -1
    private var lastManeuver = MANEUVER_CLEAR

    private var mediaSessionManager: MediaSessionManager? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handleControllersChanged(controllers)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeMediaController?.let { dispatchMediaUpdate(it, metadata, it.playbackState) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            activeMediaController?.let { dispatchMediaUpdate(it, it.metadata, state) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListener connected! Setting up MediaSessionManager and syncing active navigation...")
        setupMediaSessions()
        syncActiveMediaNow()
        syncActiveNavigationNow()
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            activeMediaController?.unregisterCallback(mediaCallback)
            activeMediaController = null
        } catch (_: Exception) {}
    }

    fun syncActiveMediaNow() {
        try {
            val mm = mediaSessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
            if (mm != null) {
                mediaSessionManager = mm
                val component = ComponentName(this, MapNotificationListenerService::class.java)
                val controllers = mm.getActiveSessions(component)
                handleControllersChanged(controllers)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncActiveMediaNow exception: ${e.message}")
        }
        activeMediaController?.let {
            dispatchMediaUpdate(it, it.metadata, it.playbackState)
        }
    }

    private fun setupMediaSessions() {
        try {
            val mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            mediaSessionManager = mm
            val component = ComponentName(this, MapNotificationListenerService::class.java)
            mm.addOnActiveSessionsChangedListener(sessionListener, component)
            handleControllersChanged(mm.getActiveSessions(component))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaSessionManager: ${e.message}")
        }
    }

    private fun handleControllersChanged(controllers: List<MediaController>?) {
        // Prioritize actively playing controller (Spotify, YouTube Music, Apple Music, etc.)
        val controller = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (controller != null) {
            if (controller != activeMediaController) {
                activeMediaController?.unregisterCallback(mediaCallback)
                activeMediaController = controller
                controller.registerCallback(mediaCallback)
            }
            dispatchMediaUpdate(controller, controller.metadata, controller.playbackState)
        }
    }

    private fun dispatchMediaUpdate(
        controller: MediaController,
        metadata: MediaMetadata?,
        state: PlaybackState?
    ) {
        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)

        // Fallback to queueTitle if metadata tag is empty
        if (title.isNullOrBlank()) {
            title = controller.queueTitle?.toString()
        }

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val progressSec = ((state?.position ?: 0L) / 1000L).toInt()
        val durationSec = ((metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000L).toInt()

        Log.i(TAG, "Active Media [${controller.packageName}]: title='$title', artist='$artist', isPlaying=$isPlaying")
        if (!title.isNullOrBlank()) {
            cachedLastSongTitle = title
            cachedLastArtist = artist
            cachedLastIsPlaying = isPlaying
            BleService.instance?.sendMediaDetailsToCluster(title, artist, isPlaying, progressSec, durationSec)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (SUPPORTED_NAV_PACKAGES.contains(sbn.packageName)) {
            val handled = processNavigationNotification(sbn)
            if (handled) return
        }

        checkMediaNotification(sbn)
    }

    private fun checkMediaNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
                extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                sbn.packageName.contains("youtube") ||
                sbn.packageName.contains("spotify") ||
                sbn.packageName.contains("music") ||
                sbn.packageName.contains("audio") ||
                sbn.packageName.contains("jiosaavn") ||
                sbn.packageName.contains("wynk") ||
                sbn.packageName.contains("gaana")

        if (isMedia) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!title.isNullOrBlank()) {
                val isPlaying = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                cachedLastSongTitle = title
                cachedLastArtist = text
                cachedLastIsPlaying = isPlaying
                BleService.instance?.sendMediaDetailsToCluster(title, text, isPlaying)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !SUPPORTED_NAV_PACKAGES.contains(sbn.packageName)) return

        if (isNavigating) {
            // Check if another active navigation notification exists before clearing
            if (!syncActiveNavigationNow()) {
                isNavigating = false
                Log.d(TAG, "Navigation notification removed and no other active nav found. Clearing cluster navigation.")
                clearClusterNavigation()
            }
        }
    }

    /**
     * Actively queries the system for ongoing navigation notifications from Google Maps, Mappls, etc.
     * Dispatches current turn and distance immediately to the scooter cluster display over BLE.
     * Returns true if active navigation was found and dispatched.
     */
    fun syncActiveNavigationNow(): Boolean {
        try {
            val activeNotifs = activeNotifications
            if (activeNotifs == null || activeNotifs.isEmpty()) {
                Log.d(TAG, "syncActiveNavigationNow: activeNotifications is empty")
                return false
            }
            for (sbn in activeNotifs) {
                val pkg = sbn.packageName ?: continue
                if (SUPPORTED_NAV_PACKAGES.contains(pkg)) {
                    if (processNavigationNotification(sbn)) {
                        Log.i(TAG, "syncActiveNavigationNow: successfully synced ongoing navigation from $pkg")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncActiveNavigationNow error: ${e.message}", e)
        }
        return false
    }

    private fun processNavigationNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: android.os.Bundle()

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ") ?: ""
        val ticker = notification.tickerText?.toString()?.trim() ?: ""

        val effectiveTitle = title.ifBlank { titleBig }
        val effectiveText = text.ifBlank { bigText.ifBlank { textLines } }
        val effectiveSubText = subText.ifBlank { infoText.ifBlank { summaryText } }

        if (!isNavigationNotification(effectiveTitle, effectiveText, effectiveSubText, bigText, notification, sbn.packageName)) {
            return false
        }

        isNavigating = true
        parseAndDispatchNavigation(effectiveTitle, effectiveText, effectiveSubText, bigText, ticker)
        return true
    }

    private fun isNavigationNotification(
        title: String,
        text: String,
        subText: String,
        bigText: String,
        notification: Notification,
        pkg: String
    ): Boolean {
        val combined = "$title $text $subText $bigText".lowercase()

        // Reject explicit location sharing notifications
        if (combined.contains("sharing your location") || combined.contains("sharing your real-time location")) {
            return false
        }

        // Standard Android category for navigation
        if (notification.category == Notification.CATEGORY_NAVIGATION ||
            notification.category == Notification.CATEGORY_TRANSPORT) {
            return true
        }

        // Check if notification has navigation action buttons (e.g. Exit navigation)
        val hasNavAction = notification.actions?.any { action ->
            val at = action.title?.toString()?.lowercase() ?: ""
            at.contains("exit") || at.contains("stop") || at.contains("mute") || at.contains("resume")
        } ?: false
        if (hasNavAction) return true

        // Check ongoing/foreground status
        val isOngoing = (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR)) != 0

        // Keyword cues common in Google Maps navigation
        val hasNavKeywords = combined.contains("min") ||
               combined.contains("km") ||
               combined.contains(" m") ||
               combined.contains("mi") ||
               combined.contains("ft") ||
               combined.contains("turn") ||
               combined.contains("exit") ||
               combined.contains("head") ||
               combined.contains("continue") ||
               combined.contains("onto") ||
               combined.contains("toward") ||
               combined.contains("roundabout") ||
               combined.contains("u-turn") ||
               combined.contains("uturn") ||
               combined.contains("keep") ||
               combined.contains("destination")

        return isOngoing && hasNavKeywords
    }

    private fun parseAndDispatchNavigation(
        title: String,
        text: String,
        subText: String,
        bigText: String,
        ticker: String
    ) {
        val maneuver = extractManeuver(title, text, bigText, ticker)
        
        var turnDistMeters = extractDistanceMeters(title)
        if (turnDistMeters <= 0) {
            turnDistMeters = extractDistanceMeters(text)
        }
        if (turnDistMeters <= 0) {
            turnDistMeters = extractDistanceMeters(bigText)
        }

        val roadName = extractRoadName(title, text, bigText)

        var totalDistMeters = extractDistanceMeters(subText)
        if (totalDistMeters <= 0) {
            totalDistMeters = extractTotalDistance(subText, text, bigText)
        }
        if (totalDistMeters < turnDistMeters || totalDistMeters <= 0) {
            totalDistMeters = maxOf(turnDistMeters + 1000, 5000)
        }

        lastManeuver = maneuver
        lastDistanceMeters = turnDistMeters
        lastRoadName = roadName
        lastTotalDistMeters = totalDistMeters

        cachedLastManeuver = maneuver
        cachedLastTurnDistMeters = turnDistMeters
        cachedLastTotalDistMeters = totalDistMeters
        cachedLastRoadName = roadName
        cachedLastNavEta = subText

        Log.i(TAG, "Nav Update -> Maneuver: 0x%02X, TurnDist: %dm, Road: '%s', TotalDist: %dm".format(
            maneuver, turnDistMeters, roadName, totalDistMeters
        ))

        // Update live navigation state in BleService (drives continuous 1000ms cluster update loop)
        BleService.instance?.updateNavigationToCluster(
            maneuverDirection = maneuver,
            turnDistanceMeters = turnDistMeters,
            totalDistanceMeters = totalDistMeters,
            roadName = roadName
        )

        // Broadcast to MainActivity for live dashboard status display
        val intent = Intent(ACTION_NAV_UPDATE).apply {
            putExtra(EXTRA_IS_NAVIGATING, true)
            putExtra(EXTRA_MANEUVER, maneuver)
            putExtra(EXTRA_DISTANCE_TEXT, if (turnDistMeters > 0) formatDistanceText(turnDistMeters) else "0 m")
            putExtra(EXTRA_ROAD_NAME, roadName)
            putExtra(EXTRA_REMAINING_TEXT, subText.ifBlank { formatDistanceText(totalDistMeters) })
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun clearClusterNavigation() {
        lastManeuver = MANEUVER_CLEAR
        lastDistanceMeters = 0
        lastRoadName = ""
        lastTotalDistMeters = 0

        cachedLastManeuver = MANEUVER_CLEAR
        cachedLastTurnDistMeters = 0
        cachedLastTotalDistMeters = 0
        cachedLastRoadName = ""
        cachedLastNavEta = ""

        // Stops 1 Hz loop and dispatches Idle/Trip Completed packet 3 times to cluster
        BleService.instance?.clearNavigationFromCluster()

        val intent = Intent(ACTION_NAV_UPDATE).apply {
            putExtra(EXTRA_IS_NAVIGATING, false)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun extractManeuver(vararg texts: String): Int {
        val combined = texts.filter { it.isNotBlank() }.joinToString(" ").lowercase()
        return when {
            combined.contains("u-turn left") || combined.contains("uturn left") -> MANEUVER_UTURN_LEFT
            combined.contains("u-turn right") || combined.contains("uturn right") -> MANEUVER_UTURN_RIGHT
            combined.contains("u-turn") || combined.contains("uturn") -> MANEUVER_UTURN_LEFT
            combined.contains("roundabout") || combined.contains("rotary") || combined.contains("traffic circle") -> MANEUVER_ROUNDABOUT
            combined.contains("slight right") || combined.contains("bear right") || combined.contains("fork right") -> MANEUVER_SLIGHT_RIGHT
            combined.contains("slight left") || combined.contains("bear left") || combined.contains("fork left") -> MANEUVER_SLIGHT_LEFT
            combined.contains("sharp right") -> MANEUVER_RIGHT
            combined.contains("sharp left") -> MANEUVER_LEFT
            Regex("(?i)\\bkeep right\\b").containsMatchIn(combined) -> MANEUVER_SLIGHT_RIGHT
            Regex("(?i)\\bkeep left\\b").containsMatchIn(combined) -> MANEUVER_SLIGHT_LEFT
            Regex("(?i)\\b(turn right|take exit right|exit right|right)\\b").containsMatchIn(combined) -> MANEUVER_RIGHT
            Regex("(?i)\\b(turn left|take exit left|exit left|left)\\b").containsMatchIn(combined) -> MANEUVER_LEFT
            combined.contains("straight") || combined.contains("continue") || combined.contains("head") || combined.contains("stay on") || combined.contains("proceed") -> MANEUVER_STRAIGHT
            else -> MANEUVER_STRAIGHT
        }
    }

    private fun extractDistanceMeters(raw: String): Int {
        if (raw.isBlank()) return 0
        var clean = raw.replace(Regex("(\\d+),(\\d{3})"), "$1$2").replace(',', '.')
        val pattern = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(km|kilometers?|meters?|miles?|mi\\b|feet|ft|m\\b(?!in))")
        val matcher = pattern.matcher(clean)
        if (matcher.find()) {
            val num = matcher.group(1)?.toDoubleOrNull() ?: return 0
            val unit = matcher.group(2)?.lowercase() ?: ""
            return when {
                unit.startsWith("km") -> (num * 1000).toInt()
                unit.startsWith("mi") -> (num * 1609.34).toInt()
                unit.startsWith("ft") -> (num * 0.3048).toInt()
                unit.startsWith("m") -> num.toInt()
                else -> num.toInt()
            }
        }
        return 0
    }

    private fun extractTotalDistance(vararg texts: String): Int {
        for (raw in texts) {
            if (raw.isBlank()) continue
            val clean = raw.replace(Regex("(\\d+),(\\d{3})"), "$1$2").replace(',', '.')
            val pattern = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(km|kilometers?|miles?|mi\\b)")
            val matcher = pattern.matcher(clean)
            var lastDist = 0
            while (matcher.find()) {
                val num = matcher.group(1)?.toDoubleOrNull() ?: continue
                val unit = matcher.group(2)?.lowercase() ?: ""
                lastDist = if (unit.startsWith("km")) (num * 1000).toInt() else (num * 1609.34).toInt()
            }
            if (lastDist > 0) return lastDist
        }
        return 0
    }

    private fun extractRoadName(title: String, text: String, bigText: String): String {
        val candidates = listOf(bigText, text, title)
        val instrPatterns = listOf(
            "turn left", "turn right", "slight right", "slight left",
            "u-turn", "head north", "head south", "head east", "head west",
            "continue straight", "continue", "in", "towards", "onto"
        )
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            var s = candidate
            s = s.replace(Regex("(?i)\\b(?:in\\s+)?\\d+(?:\\.\\d+)?\\s*(?:km|m|mi|ft)\\b"), "")
            s = s.replace(Regex("[·•\\-\\|]"), " ")
            s = s.replace(
                Regex("(?i)^\\s*(?:in\\s+)?(?:turn\\s+(?:left|right|slight left|slight right)|sharp\\s+(?:left|right)|head\\s+(?:north|south|east|west)|continue|keep\\s+(?:left|right)|take\\s+(?:exit|the))(?:\\s+(?:onto|on|towards|to))?\\s*"),
                ""
            )
            s = s.trim()
            if (s.length > 1 && instrPatterns.none { s.equals(it, ignoreCase = true) }) {
                return s.take(24)
            }
        }
        val fallback = text.ifBlank { title.ifBlank { bigText } }
        return fallback.take(24)
    }

    private fun formatDistanceText(meters: Int): String {
        return if (meters >= 1000) {
            "%.1f km".format(meters / 1000.0)
        } else {
            "$meters m"
        }
    }
}
