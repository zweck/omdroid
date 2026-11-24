package com.phoshdroid.app.proot

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.phoshdroid.app.LauncherActivity
import com.phoshdroid.app.PhoshdroidApp
import com.phoshdroid.app.R
import com.phoshdroid.app.launch.UiScale
import com.phoshdroid.app.settings.PhoshdroidPreferences
import java.io.File

/**
 * Owns the host-side process of the desktop: the proot guest running the
 * session script (cage -> Hyprland -> shell). The guest's own cage is the
 * Wayland parent now, so there is no host compositor any more.
 */
class ProotService : Service() {
    private var prootProcess: Process? = null
    private var manager: ProotDistroManager? = null

    private var stopping = false
    private var width = 0
    private var height = 0
    private var scale = 2
    private var refreshMhz = 120_000
    private var bindSdcard = true

    private val prefixDir get() = "${applicationContext.filesDir}/usr"

    override fun onCreate() {
        super.onCreate()
        val nativeLibDir = applicationInfo.nativeLibraryDir
        android.util.Log.e("ProotService", "nativeLibDir=$nativeLibDir")
        val commandBuilder =
            ProotCommandBuilder(
                nativeLibDir = nativeLibDir,
                prefixDir = prefixDir,
                distroName = DISTRO_NAME,
            )
        manager =
            ProotDistroManager(
                commandBuilder = commandBuilder,
                installedRootfsDir = File(prefixDir, "var/lib/proot-distro/installed-rootfs"),
                distroName = DISTRO_NAME,
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // A null intent means Android restarted a sticky service: this process
        // has no X server and no geometry, so a guest started here could never
        // be shown. Do not start one.
        if (intent == null) {
            android.util.Log.w(
                "ProotService",
                "null intent (system restart of a sticky service); stopping instead of starting a session",
            )
            stopSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = PhoshdroidPreferences(this)
        width = intent.getIntExtra(EXTRA_WIDTH, 0)
        height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        // The launcher resolves the scale from the window it predicted (UiScale)
        // and passes the integer; the density fallback covers an older caller.
        scale =
            intent.getIntExtra(EXTRA_SCALE, 0).takeIf { it in 1..2 }
                ?: UiScale.resolve(
                    prefs.uiScale,
                    resources.displayMetrics.densityDpi,
                    minOf(width, height),
                )
        refreshMhz = prefs.refreshMhz
        bindSdcard = prefs.bindSdcard

        startForeground(NOTIFICATION_ID, buildNotification())
        startSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun startSession() {
        if (prootProcess != null) return
        stopping = false
        // The launcher wipes <prefix>/tmp/omarchy-runtime and the stale sockets
        // before it starts the X server and this service (SessionPrep). Wiping
        // again here would run after the guest may already have started writing
        // into XDG_RUNTIME_DIR.
        File(prefixDir, "tmp").mkdirs()
        writeResolvConf()

        startProot()
    }

    private fun startProot() {
        val prefs = PhoshdroidPreferences(this)
        val startupScript = prefs.customStartupScript ?: DEFAULT_STARTUP_SCRIPT
        android.util.Log.i("ProotService", "Starting proot with script: $startupScript")
        active = true
        val startedAtMs = SystemClock.elapsedRealtime()
        prootProcess =
            manager?.login(
                startupScript = startupScript,
                bindSdcard = bindSdcard,
                width = width,
                height = height,
                scale = scale,
                refreshMhz = refreshMhz,
            )

        Thread({
            val (firstCode, _) = runProcess(prootProcess, "proot")

            // stopSession() destroys the process while this thread is blocked
            // on readText(). A Stop is the one exit that leaves the session
            // lifecycle alone: the service is going away on purpose.
            if (stopping) return@Thread

            val ranForMs = SystemClock.elapsedRealtime() - startedAtMs
            val finalCode =
                if (SessionExit.shouldRetry(firstCode, ranForMs) && prootProcess != null) {
                    prootProcess =
                        manager?.login(
                            startupScript = startupScript,
                            bindSdcard = bindSdcard,
                            width = width,
                            height = height,
                            scale = scale,
                            refreshMhz = refreshMhz,
                        )
                    runProcess(prootProcess, "proot retry").first
                } else {
                    firstCode
                }
            // The guest has finally exited: whatever the code, nothing is
            // running any more, and the service must say so (H11).
            finishSession(finalCode)
        }, "ProotMonitor").start()
    }

    /** Reads the guest's output to EOF, waits for it and records the exit code. */
    private fun runProcess(
        proc: Process?,
        label: String,
    ): Pair<Int, String> {
        val output =
            try {
                proc?.inputStream?.bufferedReader()?.readText() ?: ""
            } catch (e: java.io.IOException) {
                "<$label read interrupted: ${e.message}>"
            }
        val exitCode =
            try {
                proc?.waitFor() ?: -1
            } catch (_: InterruptedException) {
                -1
            }
        android.util.Log.i("ProotService", "$label exited ($exitCode): $output")
        try {
            File(prefixDir, "tmp/proot-exit.log").writeText("exit=$exitCode\n$output")
        } catch (_: Exception) {
        }
        return exitCode to output
    }

    /**
     * The session ended on its own. Runs on the main thread so the flag, the
     * notification and the service teardown are ordered: a launcher that reads
     * [isRunning] right after this sees the truth, and the "running"
     * notification never outlives the guest.
     *
     * The user is told, unless Android refuses a background activity start —
     * in that case the next icon tap takes the START path anyway.
     */
    private fun finishSession(exitCode: Int) {
        Handler(Looper.getMainLooper()).post {
            // The Stop action may have arrived between the guest's exit and this
            // runnable: a Stop is the user's decision to go quiet, so it wins.
            if (stopping) return@post
            active = false
            lastExitCode = exitCode
            lastExitAtMs = System.currentTimeMillis()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val shown =
                try {
                    startActivity(
                        Intent(this, LauncherActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(LauncherActivity.EXTRA_SESSION_ENDED, true)
                            putExtra(LauncherActivity.EXTRA_SESSION_EXIT_CODE, exitCode)
                        },
                    )
                    true
                } catch (e: Exception) {
                    android.util.Log.w("Phoshdroid", "could not show the session-ended screen", e)
                    false
                }
            android.util.Log.i(
                "Phoshdroid",
                "launch: session ended exit=$exitCode ui=${if (shown) "shown" else "blocked"}",
            )
            stopSelf()
        }
    }

    private fun stopSession() {
        stopping = true
        active = false
        prootProcess?.let { proc ->
            proc.destroy()
            proc.waitFor()
        }
        prootProcess = null
    }

    /**
     * Android's resolv.conf is not readable from the guest and there is no
     * NetworkManager inside proot. Write public resolvers directly into the
     * rootfs; they honour Android's per-uid VPN routing.
     */
    private fun writeResolvConf() {
        try {
            val resolv =
                File(prefixDir, "var/lib/proot-distro/installed-rootfs/$DISTRO_NAME/etc/resolv.conf")
            resolv.parentFile?.mkdirs()
            resolv.writeText(
                "# Managed by ProotService; overwritten on every launch.\n" +
                    "nameserver 1.1.1.1\n" +
                    "nameserver 1.0.0.1\n" +
                    "nameserver 8.8.8.8\n" +
                    "nameserver 8.8.4.4\n",
            )
        } catch (e: Exception) {
            android.util.Log.w("ProotService", "could not write resolv.conf", e)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification body brings the user back to
        // LauncherActivity, which forwards straight to the display activity once
        // bootstrap is done. A plain startActivity from the service is BAL-blocked
        // on Android 14+, but a notification-tap-initiated launch is exempt.
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, LauncherActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val settingsIntent =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, LauncherActivity::class.java).apply {
                    action = LauncherActivity.ACTION_SETTINGS
                },
                PendingIntent.FLAG_IMMUTABLE,
            )

        val stopIntent =
            PendingIntent.getService(
                this,
                2,
                Intent(this, ProotService::class.java).apply {
                    action = ACTION_STOP
                },
                PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, PhoshdroidApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_settings), settingsIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phoshdroid.app.STOP"
        const val DISTRO_NAME = "omarchy"
        const val DEFAULT_STARTUP_SCRIPT = "/etc/phoshdroid/start.sh"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_SCALE = "scale"

        // True from the moment a session is requested until it is stopped, the
        // guest exits for good or the service dies. The launcher's readiness
        // probe and LaunchDecision both read it, so it must never outlive the
        // guest (finishSession).
        @Volatile
        private var active = false

        /** Exit code of the last guest that ended on its own; [Int.MIN_VALUE] if none yet. */
        @Volatile
        var lastExitCode: Int = Int.MIN_VALUE
            private set

        /** Wall-clock time of that exit, `0` if none yet. */
        @Volatile
        var lastExitAtMs: Long = 0L
            private set

        fun isRunning(): Boolean = active

        fun start(
            context: Context,
            width: Int,
            height: Int,
            scale: Int,
        ) {
            active = true
            val intent =
                Intent(context, ProotService::class.java).apply {
                    putExtra(EXTRA_WIDTH, width)
                    putExtra(EXTRA_HEIGHT, height)
                    putExtra(EXTRA_SCALE, scale)
                }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent =
                Intent(context, ProotService::class.java).apply {
                    action = ACTION_STOP
                }
            context.startService(intent)
        }
    }
}
