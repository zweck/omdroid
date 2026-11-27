package com.phoshdroid.app.settings

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.phoshdroid.app.R
import com.phoshdroid.app.launch.DisplayLaunch
import com.termux.x11.MainActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Event-driven host state and a fixed, rate-limited guest control mailbox. */
class AndroidControls(
    private val context: Context,
    private val prefix: File,
    private val sessionId: String,
    private val sessionDisplayId: Int?,
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("omdroid-android-controls")
    private lateinit var worker: Handler
    @Volatile private var running = false
    private var files: AndroidControlFiles? = null
    private var requests: AndroidControlRequests? = null
    private var observer: FileObserver? = null
    private val refreshQueued = AtomicBoolean(false)
    private val requestQueued = AtomicBoolean(false)
    private val requestDirty = AtomicBoolean(false)
    private var lastSnapshot: String? = null
    private var failureReported = false
    private var lastLaunchAt: Long? = null
    @Volatile private var nextDrainAt = 0L
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "forceOrientation") requestRefresh()
    }
    private val networks = mutableSetOf<Network>()
    private var networkRegistered = false
    private var receiverRegistered = false
    private var settingsRegistered = false
    private val application get() = context.applicationContext as Application
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is MainActivity || !running) return
            worker.post {
                if (!running) return@post
                // The first snapshot can precede MainActivity's preferences; docking can select another store.
                preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                preferences = MainActivity.getPrefs()?.get()?.also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
                requestRefresh()
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) { }
        override fun onActivityStarted(activity: Activity) { }
        override fun onActivityPaused(activity: Activity) { }
        override fun onActivityStopped(activity: Activity) { }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
        override fun onActivityDestroyed(activity: Activity) { }
    }
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestRefresh()
    }
    private lateinit var settingsObserver: ContentObserver
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networks.add(network); requestRefresh() }
        override fun onLost(network: Network) { networks.remove(network); requestRefresh() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = requestRefresh()
    }

    fun start() {
        if (running) return
        running = true
        val startedAt = System.currentTimeMillis()
        thread.start()
        worker = Handler(thread.looper)
        application.registerActivityLifecycleCallbacks(lifecycle)
        worker.post {
            if (!running) return@post
            try {
                files = AndroidControlFiles.open(prefix)
                requests = AndroidControlRequests(files!!.requests, startedAt)
                observer = object : FileObserver(File(prefix, "tmp/omdroid-android/requests"), MOVED_TO or CLOSE_WRITE or CREATE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path?.endsWith(".request") == true) requestDrain()
                    }
                }.also { it.startWatching() }
                registerObservers()
                requestDrain() // Discard stale requests once; subsequent work comes only from events.
                requestRefresh()
            } catch (error: Exception) { reportFailure(error) }
        }
    }

    private fun registerObservers() {
        preferences = MainActivity.getPrefs()?.get()?.also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, null, worker, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        settingsObserver = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) = requestRefresh()
        }
        for (key in listOf(Settings.Global.BLUETOOTH_ON, Settings.Global.AIRPLANE_MODE_ON)) {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver)
            settingsRegistered = true
        }
        try {
            connectivity?.let {
                it.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback, worker)
                networkRegistered = true
            }
        } catch (error: Exception) { Log.w("AndroidControls", "Wi-Fi connection state unavailable", error) }
    }

    private fun requestRefresh() {
        if (!running || !refreshQueued.compareAndSet(false, true)) return
        worker.postDelayed({
            refreshQueued.set(false)
            if (running) try { publish() } catch (error: Exception) { reportFailure(error) }
        }, 50) // Coalesce broadcasts and network callbacks for the same state transition.
    }

    private fun requestDrain(delay: Long = 0) {
        // A directory iterator need not see entries created after it opened. Remember
        // events during a drain so they trigger a fresh pass even if that iterator ends.
        requestDirty.set(true)
        scheduleDrain(delay)
    }

    private fun scheduleDrain(delay: Long = 0) {
        if (!running || !requestQueued.compareAndSet(false, true)) return
        worker.postDelayed({
            // Keep the dirty bit across bounded batches of the same iterator.
            // Only a fresh listing can account for all events received so far.
            if (requests?.hasMore != true) requestDirty.set(false)
            try {
                if (running) requests?.take()?.forEach { action ->
                    main.post {
                        val now = SystemClock.elapsedRealtime()
                        if (running && (lastLaunchAt == null || now - lastLaunchAt!! >= 500)) {
                            lastLaunchAt = now
                            open(action)
                        }
                    }
                }
            } catch (error: Exception) { reportFailure(error) }
            nextDrainAt = SystemClock.uptimeMillis() + 100
            requestQueued.set(false)
            if (running && (requests?.hasMore == true || requestDirty.get())) scheduleDrain(100)
        }, maxOf(delay, nextDrainAt - SystemClock.uptimeMillis(), 0L))
    }

    fun stop() {
        if (!running) return
        running = false
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        // Never join a worker on the Android main thread. Queued UI actions check running.
        worker.post {
            observer?.stopWatching()
            preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            if (settingsRegistered) runCatching { context.contentResolver.unregisterContentObserver(settingsObserver) }
            if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            runCatching { requests?.close() }
            runCatching { files?.close() }
            thread.quitSafely()
        }
    }

    private fun reportFailure(error: Exception) {
        if (!failureReported) Log.w("AndroidControls", "Android controls unavailable", error)
        failureReported = true
    }

    private fun publish() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val resolver = context.contentResolver
        val state = JSONObject().put("schema", 2).put("sessionId", sessionId)
        fun field(name: String, read: () -> Any?) {
            state.put(name, runCatching(read).getOrNull() ?: JSONObject.NULL)
        }
        field("wifiEnabled") { wifi?.isWifiEnabled }
        field("wifiConnected") { if (networkRegistered) networks.isNotEmpty() else null }
        field("bluetoothEnabled") {
            when (Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, -1)) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        field("airplaneMode") {
            when (Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, -1)) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        field("rotationLocked") { AndroidRotationLock.locked(MainActivity.getPrefs()?.forceOrientation?.get()) }
        field("powerSave") { power?.isPowerSaveMode }
        field("batteryPercent") {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        }
        field("charging") { battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 } }
        field("full") { battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it > 0 }?.let { it == BatteryManager.BATTERY_STATUS_FULL } }
        val comparable = state.toString()
        if (comparable == lastSnapshot) return
        state.put("timestamp", System.currentTimeMillis())
        checkNotNull(files).publish(state.toString())
        lastSnapshot = comparable
        failureReported = false
    }

    private fun open(action: String) {
        if (action == "rotation-lock" || action == "rotation-auto") {
            setRotationLock(action == "rotation-lock")
            return
        }
        val intentAction = when (action) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "volume" -> Settings.Panel.ACTION_VOLUME
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "settings" -> Settings.ACTION_SETTINGS
            else -> return
        }
        try {
            DisplayLaunch.start(context, Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                MainActivity.getInstance()?.display?.displayId ?: sessionDisplayId)
        } catch (error: Exception) {
            Log.w("AndroidControls", "Android settings page unavailable: $action", error)
            Toast.makeText(context, R.string.android_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setRotationLock(locked: Boolean) {
        try {
            val activity = checkNotNull(MainActivity.getInstance())
            check(!activity.isFinishing && !activity.isDestroyed)
            val prefs = checkNotNull(MainActivity.getPrefs())
            val preference = if (locked) {
                // Repeated lock requests must keep the first chosen orientation.
                if (AndroidRotationLock.locked(prefs.forceOrientation.get()) == true) return
                val resources = activity.resources
                val reverseId = resources.getIdentifier("config_reverseDefaultRotation", "bool", "android")
                checkNotNull(AndroidRotationLock.currentPreference(
                    resources.configuration.orientation,
                    checkNotNull(activity.display).rotation,
                    reverseId != 0 && resources.getBoolean(reverseId),
                ))
            } else "auto"
            activity.requestedOrientation = AndroidRotationLock.requestedOrientation(preference)
            // MainActivity reapplies this on focus and after a cold start.
            prefs.forceOrientation.put(preference)
        } catch (error: Exception) {
            Log.w("AndroidControls", "Could not change Omdroid rotation", error)
            Toast.makeText(context, R.string.android_rotation_unavailable, Toast.LENGTH_SHORT).show()
        } finally {
            requestRefresh()
        }
    }
}
