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

        // Direction Opcodes matching Ampere Nexus cluster firmware:
        const val MANEUVER_RIGHT = 0x01
        const val MANEUVER_LEFT = 0x02
        const val MANEUVER_STRAIGHT = 0x03
        const val MANEUVER_SLIGHT_LEFT = 0x04
        const val MANEUVER_SLIGHT_RIGHT = 0x05
        const val MANEUVER_UTURN = 0x06
        const val MANEUVER_ROUNDABOUT = 0x07
        const val MANEUVER_CLEAR = 0xFF

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

        /**
         * Triggers immediate synchronization of currently playing song metadata.
         */
        fun triggerActiveMediaSync() {
            instance?.syncActiveMediaNow()
        }
    }

    private var isNavigating = false
    private var lastRoadName = ""
    private var lastDistanceMeters = -1
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
        Log.i(TAG, "NotificationListener connected! Setting up MediaSessionManager...")
        setupMediaSessions()
        syncActiveMediaNow()
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

        if (sbn.packageName != GOOGLE_MAPS_PACKAGE) {
            checkMediaNotification(sbn)
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        // Filter out non-navigation notifications from Google Maps (e.g. sharing location, reviews)
        if (!isNavigationNotification(title, text, subText, notification)) return

        isNavigating = true
        parseAndDispatchNavigation(title, text, subText)
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
        if (sbn == null || sbn.packageName != GOOGLE_MAPS_PACKAGE) return

        if (isNavigating) {
            isNavigating = false
            Log.d(TAG, "Google Maps navigation ended. Clearing cluster navigation.")
            clearClusterNavigation()
        }
    }

    private fun isNavigationNotification(
        title: String,
        text: String,
        subText: String,
        notification: Notification
    ): Boolean {
        // Navigation notifications are ongoing foreground services with distance/direction indicators
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) == 0) return false

        val combined = "$title $text $subText".lowercase()
        return combined.contains("min") ||
               combined.contains("km") ||
               combined.contains(" m") ||
               combined.contains("turn") ||
               combined.contains("exit") ||
               combined.contains("head") ||
               combined.contains("continue")
    }

    private fun parseAndDispatchNavigation(title: String, text: String, subText: String) {
        val maneuver = extractManeuver(title, text)
        val turnDistMeters = extractDistanceMeters(title).takeIf { it > 0 }
            ?: extractDistanceMeters(text)

        val roadName = extractRoadName(title, text)
        val totalDistMeters = extractDistanceMeters(subText)

        // Only send if something meaningful changed to avoid spamming BLE
        if (maneuver != lastManeuver || turnDistMeters != lastDistanceMeters || roadName != lastRoadName) {
            lastManeuver = maneuver
            lastDistanceMeters = turnDistMeters
            lastRoadName = roadName

            Log.d(TAG, "Nav Update -> Maneuver: 0x%02X, TurnDist: %dm, Road: %s, TotalDist: %dm".format(
                maneuver, turnDistMeters, roadName, totalDistMeters
            ))

            // Dispatch to scooter over BLE
            BleService.instance?.sendNavigationToCluster(
                maneuverDirection = maneuver,
                turnDistanceMeters = turnDistMeters,
                totalDistanceMeters = totalDistMeters,
                roadName = roadName,
                isGpsFixed = true
            )

            // Broadcast to MainActivity for live dashboard status display
            val intent = Intent(ACTION_NAV_UPDATE).apply {
                putExtra(EXTRA_IS_NAVIGATING, true)
                putExtra(EXTRA_MANEUVER, maneuver)
                putExtra(EXTRA_DISTANCE_TEXT, if (turnDistMeters > 0) formatDistanceText(turnDistMeters) else "")
                putExtra(EXTRA_ROAD_NAME, roadName)
                putExtra(EXTRA_REMAINING_TEXT, subText)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun clearClusterNavigation() {
        lastManeuver = MANEUVER_CLEAR
        lastDistanceMeters = 0
        lastRoadName = ""

        BleService.instance?.sendNavigationToCluster(
            maneuverDirection = MANEUVER_CLEAR,
            turnDistanceMeters = 0,
            totalDistanceMeters = 0,
            roadName = "",
            isGpsFixed = false
        )

        val intent = Intent(ACTION_NAV_UPDATE).apply {
            putExtra(EXTRA_IS_NAVIGATING, false)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun extractManeuver(title: String, text: String): Int {
        val combined = "$title $text".lowercase()
        return when {
            combined.contains("u-turn") || combined.contains("uturn") -> MANEUVER_UTURN
            combined.contains("roundabout") || combined.contains("rotary") -> MANEUVER_ROUNDABOUT
            combined.contains("slight right") || combined.contains("bear right") || combined.contains("keep right") -> MANEUVER_SLIGHT_RIGHT
            combined.contains("slight left") || combined.contains("bear left") || combined.contains("keep left") -> MANEUVER_SLIGHT_LEFT
            combined.contains("turn right") || combined.contains("take exit right") || combined.contains("right") -> MANEUVER_RIGHT
            combined.contains("turn left") || combined.contains("take exit left") || combined.contains("left") -> MANEUVER_LEFT
            combined.contains("straight") || combined.contains("continue") || combined.contains("head") -> MANEUVER_STRAIGHT
            else -> MANEUVER_STRAIGHT
        }
    }

    private fun extractDistanceMeters(raw: String): Int {
        val pattern = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(m|meter|meters|km|kilometer|kilometers|ft|feet|mi|mile|miles)")
        val matcher = pattern.matcher(raw)
        if (matcher.find()) {
            val num = matcher.group(1)?.toDoubleOrNull() ?: return 0
            val unit = matcher.group(2)?.lowercase() ?: ""
            return when {
                unit.startsWith("km") -> (num * 1000).toInt()
                unit.startsWith("m") -> num.toInt()
                unit.startsWith("mi") -> (num * 1609.34).toInt()
                unit.startsWith("ft") -> (num * 0.3048).toInt()
                else -> num.toInt()
            }
        }
        return 0
    }

    private fun extractRoadName(title: String, text: String): String {
        // Strip out instructions to keep clean street name
        var clean = text
        if (clean.isBlank() || clean.contains("min") || clean.contains("km")) {
            clean = title
        }
        clean = clean.replace(Regex("(?i)^(in\\s+\\d+(?:\\.\\d+)?\\s*(?:m|km|ft|mi)\\s*,?\\s*)"), "")
        clean = clean.replace(Regex("(?i)^(turn\\s+(?:right|left|slight right|slight left)\\s+(?:onto|on)\\s+)"), "")
        clean = clean.replace(Regex("(?i)^(head\\s+(?:north|south|east|west)\\s+(?:on|onto)?\\s*)"), "")
        clean = clean.replace(Regex("(?i)^(continue\\s+(?:onto|on)?\\s*)"), "")
        clean = clean.trim()
        return clean.take(24)
    }

    private fun formatDistanceText(meters: Int): String {
        return if (meters >= 1000) {
            "%.1f km".format(meters / 1000.0)
        } else {
            "$meters m"
        }
    }
}
