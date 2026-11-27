package com.phoshdroid.app.proot

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.FileObserver
import android.os.Looper
import android.os.SystemClock
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.phoshdroid.app.LauncherActivity
import com.phoshdroid.app.PhoshdroidApp
import com.phoshdroid.app.R
import com.phoshdroid.app.launch.GpuMode
import com.phoshdroid.app.launch.GpuModeRule
import com.phoshdroid.app.launch.MemorySignals
import com.phoshdroid.app.launch.ProcessLimits
import com.phoshdroid.app.launch.SessionAction
import com.phoshdroid.app.launch.SessionRequest
import com.phoshdroid.app.launch.GuestTimezone
import com.phoshdroid.app.launch.UiScale
import com.phoshdroid.app.settings.AndroidControls
import com.phoshdroid.app.settings.PhoshdroidPreferences
import com.phoshdroid.app.storage.ShareRule
import java.io.File
import java.util.TimeZone

/**
 * Owns the host-side process of the desktop: the proot guest running the
 * session script (cage -> Hyprland -> shell). The guest's own cage is the
 * Wayland parent now, so there is no host compositor any more.
 */
class ProotService : Service() {
    private var prootProcess: Process? = null
    private var brokerProcess: Process? = null
    private var commandBuilder: ProotCommandBuilder? = null
    private var manager: ProotDistroManager? = null
    private var androidControls: AndroidControls? = null

    private var stopping = false

    // The guest's own request to end, restart or leave the session (launch contract,
    // section 11). Set before the guest process is destroyed so the monitor thread
    // reports what the user asked for instead of a crash.
    @Volatile
    private var userRequest: SessionAction? = null
    private var requestObserver: FileObserver? = null
    private val requestFinished = java.util.concurrent.atomic.AtomicBoolean(false)
    private var width = 0
    private var height = 0
    private var scale = 2
    private var refreshMhz = 120_000
    private var bindSdcard = true
    private var gpu = GpuMode.KGSL
    private var gpuSource = GpuModeRule.SOURCE_SETTING

    // Set when Android's child-process limit is on and this app cannot lift it: the foreground
    // notification then carries the adb command (ProcessLimits.instruction).
    private var limitsNote: String? = null

    private val prefixDir get() = "${applicationContext.filesDir}/usr"

    override fun onCreate() {
        super.onCreate()
        val nativeLibDir = applicationInfo.nativeLibraryDir
        android.util.Log.e("ProotService", "nativeLibDir=$nativeLibDir")
        val builder =
            ProotCommandBuilder(
                nativeLibDir = nativeLibDir,
                prefixDir = prefixDir,
                distroName = DISTRO_NAME,
            )
        commandBuilder = builder
        manager =
            ProotDistroManager(
                commandBuilder = builder,
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
        // Android storage at ~/Phone (contract section 10): the tri-state rule decides, so an
        // install that was granted all-files access shares by default and one that was not stays
        // off. One log line, so a device round can read the decision.
        val shareMode = prefs.bindSdcardMode
        val allFiles = Environment.isExternalStorageManager()
        bindSdcard = ShareRule.resolve(shareMode, allFiles)
        android.util.Log.i(
            "Phoshdroid",
            "share: mode=$shareMode all-files=$allFiles -> bind=$bindSdcard",
        )
        // The GPU path is decided once per session and handed to the guest as OMDROID_GPU
        // (contract section 9). One log line, so a device round can see why a path was chosen.
        val gpuPreference = prefs.gpuMode
        val kgslUsable = GpuModeRule.isKgslUsable()
        gpu = GpuModeRule.resolve(gpuPreference, kgslUsable)
        gpuSource = GpuModeRule.source(gpuPreference)
        android.util.Log.i(
            "Phoshdroid",
            "gpu: preference=$gpuPreference kgsl-usable=$kgslUsable -> ${gpu.value} source=$gpuSource",
        )

        // Android 12+ kills a session's child processes past a limit; lift it when the grant allows, else
        // say so in the notification. One log line per start, whatever happened.
        val limits = ProcessLimits.apply(this, prefs.phantomLimitFix)
        limitsNote = limits.userNote
        android.util.Log.i("Phoshdroid", "limits: ${limits.log}")

        startForeground(NOTIFICATION_ID, buildNotification())
        startSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Log-only: whether Android ever asks this process to give memory back is a device-round question.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.i("Phoshdroid", "memory: onTrimMemory ${MemorySignals.trimLevelName(level)} ($level)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        android.util.Log.i("Phoshdroid", "memory: onLowMemory")
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun startSession() {
        if (prootProcess != null) return
        stopping = false
        userRequest = null
        requestFinished.set(false)
        // The launcher wipes <prefix>/tmp/omarchy-runtime and the stale sockets
        // before it starts the X server and this service (SessionPrep). Wiping
        // again here would run after the guest may already have started writing
        // into XDG_RUNTIME_DIR.
        File(prefixDir, "tmp").mkdirs()
        writeResolvConf()

        startRequestWatcher()
        try {
            GuestTimezone.sync(
                File(prefixDir, "var/lib/proot-distro/installed-rootfs/$DISTRO_NAME"),
                TimeZone.getDefault().id,
            )
        } catch (e: Exception) {
            android.util.Log.w("ProotService", "Could not sync guest time zone", e)
        }

        androidControls = AndroidControls(applicationContext, File(prefixDir)).also { it.start() }

        startBroker()
        startProot()
    }

    /**
     * Watches `<prefix>/tmp/omdroid-host` for the guest's `request` file (inotify, so an
     * idle session costs nothing). A leftover file from a session that died is removed
     * first; [SessionRequest.parse] also drops a stamp older than 30 s.
     */
    private fun startRequestWatcher() {
        val dir = File(prefixDir, SessionRequest.DIR)
        dir.mkdirs()
        File(dir, SessionRequest.FILE).delete()
        requestObserver?.stopWatching()
        val observer =
            object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path == SessionRequest.FILE) {
                        Handler(Looper.getMainLooper()).post { takeRequest(dir) }
                    }
                }
            }
        observer.startWatching()
        requestObserver = observer
    }

    private fun stopRequestWatcher() {
        requestObserver?.stopWatching()
        requestObserver = null
    }

    /** Reads, acknowledges (deletes) and acts on one guest request. Main thread. */
    private fun takeRequest(dir: File) {
        val file = File(dir, SessionRequest.FILE)
        val text =
            try {
                if (file.isFile) file.readText() else return
            } catch (e: Exception) {
                return
            }
        // The guest waits for the file to go away: that is its acknowledgement.
        file.delete()
        val action = SessionRequest.parse(text, System.currentTimeMillis() / 1000)
        if (action == null) {
            android.util.Log.w("Phoshdroid", "session: request ignored: ${text.trim().take(60)}")
            return
        }
        if (!active || stopping) {
            android.util.Log.i("Phoshdroid", "session: request $action ignored, no session is running")
            return
        }
        android.util.Log.i("Phoshdroid", "session: request $action")
        when (action) {
            SessionAction.HOME -> handBackToAndroid()
            else -> {
                userRequest = action
                // Not stopSession(): the monitor thread must see this exit and report the
                // request instead of retrying a cold-start collapse. Off the main thread: a
                // process that is slow to die must never freeze the looper, which also carries
                // the next request, the notification and the launcher.
                Thread({ endGuestForRequest(action) }, "SessionRequest").start()
            }
        }
    }

    /**
     * Ends the broker and the guest for a Logout, Restart or Shutdown. Off the main thread.
     *
     * The guest ends itself first: `omdroid-session` sends SIGTERM to Hyprland once the request is
     * acknowledged, and proot then exits normally. Signalling proot from here did not end the
     * guest on the device (r11 phase 3: the process survived SIGTERM, then SIGKILL, and the exit
     * never reached the monitor thread), so SIGTERM and SIGKILL are only the fallback, after
     * [GUEST_EXIT_WAIT_MS]. A guest that is still alive after both is reported, never papered over.
     */
    private fun endGuestForRequest(action: SessionAction) {
        stopBroker(wait = true)
        val proc = prootProcess
        if (proc == null) {
            android.util.Log.w("Phoshdroid", "session: request $action: no guest process to end")
            return
        }
        var how = "by itself"
        if (!waitDead(proc, GUEST_EXIT_WAIT_MS)) {
            how = "after SIGTERM"
            proc.destroy()
            if (!waitDead(proc, GUEST_STOP_WAIT_S * 1000)) {
                how = "after SIGKILL"
                proc.destroyForcibly()
                if (!waitDead(proc, GUEST_STOP_WAIT_S * 1000)) {
                    android.util.Log.w("Phoshdroid", "session: request $action: guest process still alive after SIGKILL")
                    return
                }
            }
        }
        android.util.Log.i("Phoshdroid", "session: request $action: guest process ended $how")
        // The monitor thread normally reports the exit; when it cannot (a pipe still held open),
        // this does, once.
        Thread.sleep(500)
        finishRequested(action)
    }

    private fun waitDead(
        proc: Process,
        ms: Long,
    ): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (proc.isAlive) {
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(100)
        }
        return true
    }

    /** Suspend and Lock: the Android home screen comes up, the session keeps running. */
    private fun handBackToAndroid() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            android.util.Log.i("Phoshdroid", "session: handed back to Android (home)")
        } catch (e: Exception) {
            android.util.Log.w("Phoshdroid", "session: could not open the Android home screen: ${e.message}")
        }
    }

    /**
     * Starts the fake-root broker: a second proot of the same rootfs with `-0`
     * instead of the session's `-i 1000:1000`, running
     * /etc/phoshdroid/omdroid-rootd.py. The guest's `sudo` client talks to it
     * over a Unix socket under the shared /tmp, and it is the only way anything
     * in the desktop can become root (the image has no sudo, and setuid does
     * not work under proot).
     *
     * It is deliberately decoupled from the desktop: a broker that fails to
     * start, or dies later, is logged and nothing else. The session runs either
     * way, and `sudo` answers with its one-line error while it is gone.
     */
    private fun startBroker() {
        val builder = commandBuilder ?: return
        if (brokerProcess != null) return
        val env =
            builder.buildEnvironment(
                width = width,
                height = height,
                scale = scale,
                refreshMhz = refreshMhz,
                bindSdcard = bindSdcard,
                gpu = gpu,
                gpuSource = gpuSource,
            )
        val process =
            try {
                ProcessBuilder(builder.buildRootBrokerCommand(bindSdcard))
                    .redirectErrorStream(true)
                    .apply { environment().putAll(env) }
                    .start()
            } catch (e: Exception) {
                android.util.Log.w("ProotService", "session: root-broker failed to start: ${e.message}")
                return
            }
        brokerProcess = process
        android.util.Log.i("ProotService", "session: root-broker started")
        Thread({
            val output =
                try {
                    process.inputStream.bufferedReader().readText()
                } catch (e: java.io.IOException) {
                    "<root-broker read interrupted: ${e.message}>"
                }
            val code =
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    -1
                }
            android.util.Log.i("ProotService", "session: root-broker exited $code: $output")
            try {
                File(prefixDir, "tmp/root-broker.log").writeText("exit=$code\n$output")
            } catch (_: Exception) {
            }
        }, "RootBrokerMonitor").start()
    }

    /**
     * Ends the fake-root broker. Never waits on the calling thread: this runs from `onDestroy`
     * on the main thread, and the broker proot has outlived SIGTERM and SIGKILL on the device.
     * The R10 build waited here with an unbounded `waitFor()`, which froze the main thread when a
     * session ended from inside (no ended screen, then an ANR). [wait] is for the request thread,
     * which is off the main thread and wants the outcome in its log before it goes on.
     */
    private fun stopBroker(wait: Boolean = false) {
        val process = brokerProcess ?: return
        brokerProcess = null
        val done = java.util.concurrent.CountDownLatch(1)
        val stoppable = JavaProcessStoppable(process)
        ProcessStop.stopAsync("BrokerStop", stoppable, GUEST_STOP_WAIT_S * 1000, GUEST_STOP_WAIT_S * 1000) {
            when (it) {
                StopOutcome.SURVIVED ->
                    android.util.Log.w("ProotService", "session: root-broker still alive after SIGKILL: ${stoppable.survivorStatus()}")
                else -> android.util.Log.i("ProotService", "session: root-broker stopped ($it)")
            }
            done.countDown()
        }
        if (wait) done.await(GUEST_STOP_WAIT_S * 2 + 1, java.util.concurrent.TimeUnit.SECONDS)
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
                gpu = gpu,
                gpuSource = gpuSource,
            )

        Thread({
            val (firstCode, _) = runProcess(prootProcess, "proot")

            // stopSession() destroys the process while this thread is blocked
            // on readText(). A Stop is the one exit that leaves the session
            // lifecycle alone: the service is going away on purpose.
            if (stopping) return@Thread

            // The guest asked for this end (Logout, Restart, Shutdown): no retry.
            userRequest?.let { requested ->
                finishRequested(requested)
                return@Thread
            }

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
                            gpu = gpu,
                            gpuSource = gpuSource,
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
     * The guest asked to end (Logout), restart (Reboot) or close the app (Shutdown).
     * Same ordering as [finishSession]: the flag, the notification and the service
     * teardown happen on the main thread, in that order.
     */
    private fun finishRequested(action: SessionAction) {
        if (!requestFinished.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            if (stopping) return@post
            lastExitCode = 0
            lastExitAtMs = System.currentTimeMillis()
            active = false
            stopRequestWatcher()
            stopForeground(STOP_FOREGROUND_REMOVE)
            when (action) {
                SessionAction.RESTART -> showLauncher(restart = true)
                SessionAction.SHUTDOWN -> closeApp()
                else -> showLauncher(restart = false)
            }
            android.util.Log.i("Phoshdroid", "launch: session ended by request $action")
            stopSelf()
        }
    }

    private fun showLauncher(restart: Boolean) {
        try {
            startActivity(
                Intent(this, LauncherActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (restart) {
                        putExtra(LauncherActivity.EXTRA_SESSION_RESTART, true)
                    } else {
                        putExtra(LauncherActivity.EXTRA_SESSION_ENDED, true)
                        putExtra(LauncherActivity.EXTRA_SESSION_EXIT_CODE, 0)
                    }
                },
            )
        } catch (e: Exception) {
            // Android 14+ may refuse a background activity start; the next icon tap takes START.
            android.util.Log.w("Phoshdroid", "could not show the launcher after a request", e)
        }
    }

    /** Shutdown: remove the app's tasks, then end the process (the X server lives in it). */
    private fun closeApp() {
        try {
            getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .forEach { it.finishAndRemoveTask() }
        } catch (e: Exception) {
            android.util.Log.w("Phoshdroid", "could not remove the app tasks: ${e.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
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
            // The exit is recorded before `active` drops: a launcher thread that sees the session
            // dead must find this exit, not the previous one.
            lastExitCode = exitCode
            lastExitAtMs = System.currentTimeMillis()
            active = false
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
        stopRequestWatcher()
        // Main thread (the notification Stop and onDestroy): no waiting here. A guest that already
        // exited is a no-op; a live one is signalled on its own thread, and the monitor thread returns
        // on `stopping` when it goes.
        androidControls?.stop()
        androidControls = null
        prootProcess?.let { proc ->
            ProcessStop.stopAsync("GuestStop", JavaProcessStoppable(proc), GUEST_STOP_WAIT_S * 1000, GUEST_STOP_WAIT_S * 1000) {
                android.util.Log.i("ProotService", "session: guest process stopped ($it)")
            }
        }
        prootProcess = null
        stopBroker()
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

        val builder =
            NotificationCompat
                .Builder(this, PhoshdroidApp.CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(0, getString(R.string.action_settings), settingsIntent)
                .addAction(0, getString(R.string.action_stop), stopIntent)
        limitsNote?.let { note ->
            builder
                .setContentText(getString(R.string.notification_limits_short))
                .setStyle(NotificationCompat.BigTextStyle().bigText(note))
        }
        return builder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phoshdroid.app.STOP"
        const val DISTRO_NAME = "omarchy"
        const val DEFAULT_STARTUP_SCRIPT = "/etc/phoshdroid/start.sh"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_SCALE = "scale"

        /** How long a process may take to die after SIGTERM before it is killed. */
        private const val GUEST_STOP_WAIT_S = 3L

        /** How long a guest that was asked to end itself may take before the host signals proot. */
        private const val GUEST_EXIT_WAIT_MS = 10_000L

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
