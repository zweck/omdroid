package com.phoshdroid.app.settings

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.phoshdroid.app.R
import org.json.JSONObject
import java.io.File

/** Android owns the radios and screen. Publish facts and open its user-mediated controls. */
class AndroidControls(private val context: Context, prefix: File) {
    private val directory = File(prefix, "tmp/omdroid-android")
    private val requestsDirectory = File(directory, "requests")
    private val requests = AndroidControlRequests(requestsDirectory)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        requestsDirectory.mkdirs()
        // Do not replay taps from a previous session.
        requestsDirectory.listFiles()?.filter { it.name.endsWith(".request") }?.forEach { it.delete() }
        running = true
        worker = Thread({
            try {
                var nextState = 0L
                while (running) {
                    try {
                        requests.take().forEach { action -> main.post { if (running) open(action) } }
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextState) {
                            publish(System.currentTimeMillis())
                            nextState = now + 2_000
                        }
                    } catch (error: Exception) {
                        Log.w("AndroidControls", "Could not refresh Android controls", error)
                    }
                    Thread.sleep(250)
                }
            } catch (_: InterruptedException) {
                // Session stopped.
            }
        }, "omdroid-android-controls").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(1_000)
        worker = null
        File(directory, "state.json").delete()
    }

    private fun publish(now: Long) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val resolver = context.contentResolver
        val state = JSONObject().put("schema", 1).put("timestamp", now)
        // An unavailable field stays unknown; a failed read never means a radio is off.
        fun field(name: String, read: () -> Any?) {
            state.put(name, runCatching(read).getOrNull() ?: JSONObject.NULL)
        }
        field("wifiEnabled") { wifi?.isWifiEnabled }
        field("wifiConnected") {
            connectivity?.allNetworks?.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        // This is the OS radio state, not a scan or the user's paired-device list.
        field("bluetoothEnabled") {
            when (Settings.Global.getInt(resolver, "bluetooth_on", -1)) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        field("airplaneMode") { Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON) == 1 }
        field("rotationLocked") { Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == 0 }
        field("powerSave") { power?.isPowerSaveMode }
        field("batteryPercent") {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        }
        field("charging") { battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } }
        field("full") { battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { it == BatteryManager.BATTERY_STATUS_FULL } }
        val temporary = File(directory, "state.json.new")
        temporary.writeText(state.toString())
        check(temporary.renameTo(File(directory, "state.json"))) { "Could not replace Android state" }
    }

    private fun open(action: String) {
        val intentAction = when (action) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "rotation" -> Settings.ACTION_AUTO_ROTATE_SETTINGS
            "volume" -> Settings.Panel.ACTION_VOLUME
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "settings" -> Settings.ACTION_SETTINGS
            else -> return
        }
        try {
            context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Log.w("AndroidControls", "Android settings page unavailable: $action", error)
            Toast.makeText(context, R.string.android_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
