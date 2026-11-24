package com.phoshdroid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.phoshdroid.app.extraction.AssetExtractor
import com.phoshdroid.app.launch.DisplayGeometry
import com.phoshdroid.app.launch.GuestOverlay
import com.phoshdroid.app.launch.GuestOverlayAssets
import com.phoshdroid.app.launch.LaunchAction
import com.phoshdroid.app.launch.LaunchDecision
import com.phoshdroid.app.launch.LaunchReadiness
import com.phoshdroid.app.launch.ReadyDecision
import com.phoshdroid.app.launch.SessionPrep
import com.phoshdroid.app.launch.UiScale
import com.phoshdroid.app.launch.XGeometry
import com.phoshdroid.app.proot.ProotCommandBuilder
import com.phoshdroid.app.proot.ProotDistroManager
import com.phoshdroid.app.proot.ProotService
import com.phoshdroid.app.settings.PhoshdroidPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LauncherActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var errorText: TextView

    /** Shown with the session-ended/did-not-start screens; hidden otherwise. */
    private lateinit var startAgainButton: android.widget.Button

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* proceed regardless */ }

    // The running proot session's XLORIE dimensions are persisted in
    // SharedPreferences. onCreate compares CURRENT display to LAST SAVED
    // dimensions; if they differ (e.g. user folded the phone between launches)
    // we kill the process and relaunch so the new proot run picks up the
    // current metrics. Persisted state survives process death.
    private var restartInFlight = false

    /** Milliseconds since the launcher was created, for the contract's `launch:` lines. */
    private val createdAtMs = SystemClock.elapsedRealtime()

    /** The X root size predicted for this hand-over; every consumer shares it. */
    private var geometry = XGeometry(0, 0, 0)
    private var winWidth = 0
    private var winHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_SETTINGS) {
            setContentView(R.layout.activity_launcher)
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
            return
        }

        setContentView(R.layout.activity_launcher)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)
        startAgainButton = findViewById(R.id.startAgainButton)
        // The button runs exactly what a later icon tap runs: the START path.
        startAgainButton.setOnClickListener {
            if (!bootstrapInProgress) startBootstrap()
        }

        if (checkForDisplayShift("onCreate")) return

        // The service brought us here because the guest exited on its own.
        // Show it; a session must never start from this screen.
        val endedExitCode = consumeSessionEnded(intent)
        if (endedExitCode != null) {
            showSessionEnded(endedExitCode)
            return
        }

        when (LaunchDecision.decide(bootstrapInProgress, bootstrapCompleted, ProotService.isRunning())) {
            LaunchAction.WAIT -> {
                // A previous LauncherActivity instance in this process already
                // kicked off bootstrap — it is extracting or suspended in
                // waitForDesktopReady. Starting a second one would tear down the
                // first's X11 socket mid-setup, so show the splash and let it
                // finish.
                logLaunch("decision wait")
            }

            LaunchAction.FORWARD -> {
                // The desktop is up behind MainActivity from a previous
                // bootstrap in this process. Re-tapping the icon lands here;
                // forward instead of sitting on the loading screen.
                logLaunch("decision forward")
                forwardToMainActivity()
            }

            LaunchAction.START -> {
                logLaunch("decision start")
                startBootstrap()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The launcher icon (or the FGS notification) was tapped while this
        // LauncherActivity was already alive in the background. The same
        // decision as onCreate: forward if the desktop is up, wait if a
        // bootstrap is running, start otherwise (the previous session ended).
        setIntent(intent)
        if (restartInFlight) return
        val endedExitCode = consumeSessionEnded(intent)
        if (endedExitCode != null) {
            showSessionEnded(endedExitCode)
            return
        }
        when (LaunchDecision.decide(bootstrapInProgress, bootstrapCompleted, ProotService.isRunning())) {
            LaunchAction.WAIT -> logLaunch("decision wait")
            LaunchAction.FORWARD -> {
                logLaunch("decision forward")
                forwardToMainActivity()
            }
            LaunchAction.START -> {
                logLaunch("decision start")
                startBootstrap()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (restartInFlight || isFinishing) return
        // Catches the case where bootstrap completed while we were paused
        // (Android backed our startActivity call out as BAL_BLOCK). Only
        // FORWARD acts here: a START from onResume would restart the session
        // every time an error screen is resumed.
        if (LaunchDecision.decide(bootstrapInProgress, bootstrapCompleted, ProotService.isRunning()) ==
            LaunchAction.FORWARD
        ) {
            logLaunch("decision forward")
            forwardToMainActivity()
        }
    }

    /** The START path: one bootstrap at a time, from the splash or an error screen. */
    private fun startBootstrap() {
        if (bootstrapInProgress) return
        bootstrapInProgress = true
        // A Start again or an icon tap clears whatever the error screen showed.
        startAgainButton.visibility = View.GONE
        showProgress(getString(R.string.preparing_desktop))
        requestNotificationPermission()
        lifecycleScope.launch {
            try {
                // bootstrap() returning false means the error screen is up: the
                // flag stays unset, so the next icon tap takes the START path
                // again instead of forwarding to a display nothing draws on.
                if (bootstrap()) bootstrapCompleted = true
            } catch (e: Exception) {
                showError("Startup failed: ${e.message}\n\n${e.stackTraceToString()}", copyable = true)
            } finally {
                bootstrapInProgress = false
            }
        }
    }

    // onConfigurationChanged / onResume / DisplayListener inside this
    // activity intentionally do NOT trigger shift detection — splash-time
    // dimension flicker would otherwise look like a shift and tear down
    // the in-flight bootstrap. The cold-start onCreate check below covers
    // initial-launch shifts; subsequent foreground events (including ones
    // that happen while MainActivity is on top, not LauncherActivity) are
    // handled by DisplayShiftMonitor at the Application level.

    private fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId ?: 0
        }

    /**
     * Compares the activity's current display to whatever the running proot
     * session was started against.
     *
     * A size change is never a reason to die by default: a fresh process has
     * no session to restart, and a live one follows the X root (contract
     * section 3), so a restart would only destroy the user's open apps. The
     * old restart path stays reachable behind [RESTART_SESSION_ON_DISPLAY_SHIFT]
     * for a device round that finds the guest fails to follow. Returns true
     * only when a restart was actually initiated.
     *
     * Uses WindowManager.currentWindowMetrics instead of resources.displayMetrics
     * because the latter can return stale values during onCreate before Android
     * has finished propagating the activity's final configuration — which would
     * otherwise cause false-positive shifts on subsequent lifecycle callbacks.
     */
    private fun checkForDisplayShift(source: String): Boolean {
        if (restartInFlight) return true
        val bounds = windowManager.currentWindowMetrics.bounds
        val currentW = bounds.width()
        val currentH = bounds.height()
        val currentId = currentDisplayId()
        val prefs = getSharedPreferences(DISPLAY_STATE_PREFS, MODE_PRIVATE)
        val lastW = prefs.getInt("last_w", 0)
        val lastH = prefs.getInt("last_h", 0)
        val lastId = prefs.getInt("last_id", -1)

        if (lastW != 0 && (lastW != currentW || lastH != currentH || lastId != currentId)) {
            val freshProcess = !bootstrapCompleted && !bootstrapInProgress && !ProotService.isRunning()
            val restart = RESTART_SESSION_ON_DISPLAY_SHIFT && !freshProcess
            android.util.Log.i(
                "Phoshdroid",
                "[$source] display shift ${lastW}x$lastH@$lastId -> ${currentW}x$currentH@$currentId, " +
                    if (restart) "restarting" else "recording",
            )
            prefs
                .edit()
                .putInt("last_w", currentW)
                .putInt("last_h", currentH)
                .putInt("last_id", currentId)
                .commit() // sync so a restarted process sees the new values
            if (restart) {
                restartInFlight = true
                restartApp()
                return true
            }
            return false
        }
        // First run or same dimensions — record and move on.
        prefs
            .edit()
            .putInt("last_w", currentW)
            .putInt("last_h", currentH)
            .putInt("last_id", currentId)
            .apply()
        return false
    }

    /**
     * Predicts the X root size for this hand-over from the window it will be
     * shown in (contract section 1). One prediction feeds XLORIE_WIDTH/HEIGHT,
     * the guest's `OMARCHY_OUTPUT_*` hint and MainActivity's bottom margin, so
     * all three agree and no resize happens at hand-over.
     *
     * Three sources of dimensions for a 0x0 window, in order of preference:
     *   1. WindowManager.currentWindowMetrics — authoritative once the activity
     *      is attached, but can return 0x0 during very early lifecycle phases
     *      (observed on Pixel 9 Pro Fold coming out of Doze).
     *   2. resources.displayMetrics — activity bounds on Android 11+, same edge
     *      case.
     *   3. The last-known dims checkForDisplayShift already wrote — always
     *      populated, a guaranteed-non-zero fallback.
     *
     * Without (3) an empty XLORIE_WIDTH/HEIGHT propagates into the guest's
     * start.sh, which writes an empty `mode=` line and the compositor falls
     * back to wlroots' default 1024x768.
     */
    private fun computeGeometry(): XGeometry {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        var width = bounds.width()
        var height = bounds.height()
        var insetLeft = 0
        var insetTop = 0
        var insetRight = 0
        var insetBottom = 0
        if (width > 0 && height > 0) {
            val cutout =
                metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            insetLeft = cutout.left
            insetTop = cutout.top
            insetRight = cutout.right
            insetBottom = cutout.bottom
        } else {
            val dm = resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
        }
        if (width <= 0 || height <= 0) {
            val prefs = getSharedPreferences(DISPLAY_STATE_PREFS, MODE_PRIVATE)
            width = prefs.getInt("last_w", 0)
            height = prefs.getInt("last_h", 0)
        }
        winWidth = width
        winHeight = height

        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val extraKeysShown =
            preferences.getBoolean("showAdditionalKbd", false) &&
                preferences.getBoolean("additionalKbdVisible", false)
        // Same accessor Termux:X11 uses for the bar height. It is null before
        // MainActivity has created its Prefs in this process (its static prefs
        // field is set in MainActivity.onCreate), so fall back to the default
        // config's two rows.
        val rows =
            runCatching {
                com.termux.x11.utils.TermuxX11ExtraKeys.getExtraKeysInfo()?.matrix?.size
            }.getOrNull() ?: DisplayGeometry.DEFAULT_EXTRA_KEY_ROWS

        return DisplayGeometry.compute(
            windowWidth = width,
            windowHeight = height,
            density = resources.displayMetrics.density,
            extraKeyRows = rows,
            extraKeysShown = extraKeysShown,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom,
        )
    }

    private fun forwardToMainActivity() {
        // A re-forward in this process (notification tap, onResume) may be the
        // first hand-over this activity instance sees; predict the geometry now
        // so the margins match the running X root.
        if (geometry.width <= 0) geometry = computeGeometry()
        try {
            val waylandIntent =
                Intent(this, Class.forName("com.termux.x11.MainActivity"))
                    .putExtra("phoshdroid_top_padding_px", 0)
                    .putExtra("phoshdroid_bottom_padding_px", geometry.bottomGap)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(waylandIntent)
            logLaunch("handover done")
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            android.util.Log.e("Phoshdroid", "Failed to forward to MainActivity", e)
            statusText.text = "Display failed: ${e.message}"
        }
    }

    private fun restartApp() {
        try {
            com.phoshdroid.app.proot.ProotService
                .stop(this)
        } catch (_: Exception) {
        }
        val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (restartIntent != null) {
            restartIntent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK,
            )
            val pending =
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    restartIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 200, pending)
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Do not open the all-files settings page from the launch path. That
        // system activity stops LauncherActivity, so the Android 14 BAL guard
        // at the end of bootstrap() sees a non-STARTED activity and skips the
        // MainActivity hand-over on every cold start: the phone lands on the
        // Settings screen and the desktop only appears once the user backs out.
        // MANAGE_EXTERNAL_STORAGE is only needed by the "Mount /sdcard"
        // preference, so requestAllFilesAccess() belongs behind that setting,
        // not here.
    }

    private suspend fun bootstrap(): Boolean {
        val filesDir = applicationContext.filesDir
        val extractor = AssetExtractor()

        // A START that finds a live session (a Stop that has not reached the
        // service yet, or a guest exit is still being noticed) stops it first:
        // one X server, one guest, one session. Off the main thread, bounded —
        // a guest that will not die must not hang the launch forever.
        if (ProotService.isRunning()) {
            withContext(Dispatchers.IO) {
                ProotService.stop(applicationContext)
                val deadline = System.currentTimeMillis() + PREVIOUS_SESSION_STOP_TIMEOUT_MS
                while (ProotService.isRunning() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
            }
            logLaunch("stopped previous session")
        }

        // Phase 1: Extract Termux bootstrap
        val bootstrapDir = File(filesDir, "usr")
        // The marker is the whole readiness test: the host no longer ships a
        // compositor, so there is no weston library to check for.
        val prefixReady = File(bootstrapDir, ".extraction_complete").exists()
        if (!prefixReady) {
            showProgress(getString(R.string.extracting_termux))
            progressBar.isIndeterminate = true
            val extracted =
                withContext(Dispatchers.IO) {
                    assets.open("bootstrap.bin").use { input ->
                        extractor.extract(input, -1, bootstrapDir)
                    }
                }
            if (!extracted) {
                showError("Failed to extract Termux environment.")
                return false
            }
        }
        logLaunch("prefix-extracted already=$prefixReady")
        // Create symlinks from prefix/bin to native lib dir binaries
        // (native lib dir is the only place Android allows exec)
        val nativeLibDir = applicationInfo.nativeLibraryDir
        withContext(Dispatchers.IO) {
            val binDir = File(bootstrapDir, "bin")
            binDir.mkdirs()
            mapOf("bash" to "libbash.so", "proot" to "libproot.so").forEach { (name, soName) ->
                val link = File(binDir, name)
                val target = File(nativeLibDir, soName)
                if (target.exists() && !link.exists()) {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", target.absolutePath, link.absolutePath)).waitFor()
                }
            }
        }

        // Phase 2: Extract pmOS rootfs directly into proot's expected location.
        // The previous two-phase "extract to files/rootfs/, then rename-move
        // into files/usr/var/lib/.../postmarketos/" layout was fragile — if
        // the process was killed between extraction and rename (display-shift
        // restart, OOM kill, etc.), subsequent launches found rootfsDir
        // already present, skipped the move, and left a half-populated rootfs
        // that hung phosh at "Preparing your desktop".
        val prefixDir = "$filesDir/usr"
        val rootfsDir = File(prefixDir, "var/lib/proot-distro/installed-rootfs/${ProotService.DISTRO_NAME}")
        if (!File(rootfsDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_rootfs))

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            // The Omarchy guest is ~1.5 GB as a gzipped asset and ~4.1 GB
            // extracted; keep the same headroom the pmOS guest had so the
            // extraction cannot wedge with a half-populated rootfs.
            val requiredBytes = 6_500_000_000L
            if (availableBytes < requiredBytes) {
                showError(
                    getString(
                        R.string.error_no_space,
                        formatSize(requiredBytes),
                        formatSize(availableBytes),
                    ),
                )
                return false
            }

            progressBar.isIndeterminate = true
            rootfsDir.mkdirs()
            // Clean up any leftover staging dir from older APK versions that
            // used the two-phase layout, plus the pmOS guest from the Phosh
            // build, so we reclaim that disk space.
            withContext(Dispatchers.IO) {
                File(filesDir, "rootfs").deleteRecursively()
                File(prefixDir, "var/lib/proot-distro/installed-rootfs/postmarketos").deleteRecursively()
            }
            val extracted =
                withContext(Dispatchers.IO) {
                    assets.open("rootfs.bin").use { input ->
                        extractor.extract(input, -1, rootfsDir)
                    }
                }
            if (!extracted) {
                showError("Failed to extract postmarketOS.")
                return false
            }
        }

        logLaunch("rootfs-extracted")
        // Guest scripts travel with the APK (H13): the packager's overlay files
        // are assets of this build, so rewrite whatever differs under the
        // guest's /etc/phoshdroid before SessionPrep reads the prefix. A failure
        // here is logged, never fatal — a launch that dies here is worse than an
        // old script set.
        if (File(rootfsDir, ".extraction_complete").exists()) {
            try {
                withContext(Dispatchers.IO) {
                    val files = GuestOverlayAssets(assets).read()
                    val changed = GuestOverlay.sync(files, rootfsDir)
                    logLaunch("guest-overlay files=${files.size} changed=${changed.size}")
                    changed.forEach { logLaunch("guest-overlay updated $it") }
                }
            } catch (e: Exception) {
                android.util.Log.w("Phoshdroid", "launch: guest-overlay failed: ${e.message}", e)
            }
        }

        // Phase 3: Set up rootfs for proot (bypass proot-distro, call proot directly)
        statusText.text = getString(R.string.preparing_desktop)
        progressBar.visibility = View.GONE

        // Ensure shared tmp dir exists for the Wayland socket and X11 socket
        File(prefixDir, "tmp").mkdirs()

        // Phase 4 is gone: the guest session runs as uid 1000 (proot -i), so
        // there is no root-side prep script to feed. The launcher prepares the
        // runtime dir (SessionPrep); ProotService writes resolv.conf.

        // Default Termux:X11 to direct-touch input mode — phone users expect finger
        // taps to act as touches, not move a trackpad cursor. "3" = Direct touch.
        // Also hide the additional keyboard toolbar so phosh owns the full surface
        // height — its lockscreen unlock gesture needs to originate from the bottom edge.
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .edit()
            .putString("touchMode", "3")
            // Keep the Termux:X11 shortcut toolbar (ESC, CTRL, arrows, etc.) visible.
            // We carve out a bottom gap below phosh anyway for the edge swipe so the
            // toolbar doesn't overlap phosh's usable area.
            .putBoolean("showAdditionalKbd", true)
            .putBoolean("additionalKbdVisible", true)
            // Immersive sticky fullscreen — keeps Android nav bar hidden so bottom-edge
            // swipes go to phosh instead of being consumed by the Android home gesture.
            .putBoolean("fullscreen", true)
            // hideCutout=false keeps the window clear of a display cutout in every
            // rotation, so the host needs no top gap. adjustHeightForEK stays
            // false: it changes the layout only after the X client connects,
            // which would resize the root on every launch.
            .putBoolean("hideCutout", false)
            .putBoolean("adjustHeightForEK", false)
            .apply()

        // Phase 5: Start X11 server, then proot, then launch display activity
        statusText.text = getString(R.string.starting_desktop)

        // Start the Termux:X11 server
        val tmpDir = File(prefixDir, "tmp")
        tmpDir.mkdirs()
        File(tmpDir, ".X11-unix").mkdirs()
        // One wipe for the whole session, before the X server and the service
        // start so nothing can recreate the paths behind our back: the guest's
        // XDG_RUNTIME_DIR (nothing in it survives a session), stale proot
        // sockets from killed sessions, and the previous run's X/Wayland
        // sockets. Logs stay — they are the only evidence after a failed
        // start. ProotService no longer wipes anything itself.
        // A second bootstrap in this process keeps the live server's socket and
        // lock (xServerStarted): they are not stale, they are in use.
        SessionPrep(tmpDir).prepare(keepXServer = xServerStarted).forEach {
            android.util.Log.i("Phoshdroid", "launch: removed stale $it")
        }
        // The predicted X root size; ProotService needs the same numbers for the
        // guest's OMARCHY_OUTPUT_* env.
        geometry = computeGeometry()
        val launchWidth = geometry.width
        val launchHeight = geometry.height

        if (xServerStarted) {
            logLaunch("restart-in-process")
        } else {
            startXServer(rootfsDir, prefixDir, tmpDir)
        }

        // Wait for the socket on both paths: after a restart the live server's
        // socket must still be there, and this line is the evidence.
        withContext(Dispatchers.IO) {
            val socketFile = File(prefixDir, "tmp/.X11-unix/X0")
            var attempts = 0
            while (!socketFile.exists() && attempts < 20) {
                Thread.sleep(500)
                attempts++
            }
            android.util.Log.e("Phoshdroid", "X11 socket exists: ${socketFile.exists()}")
            logLaunch("x11-socket exists=${socketFile.exists()}")
        }

        // Start the guest while the user still sees this loading screen
        // (logo + "Starting desktop…").
        android.util.Log.e("Phoshdroid", "Starting ProotService...")
        ProotService.start(
            this,
            launchWidth,
            launchHeight,
            // The scale follows the density unless the user chose one
            // (contract section 4); the service receives the integer.
            UiScale.resolve(
                PhoshdroidPreferences(this).uiScale,
                resources.displayMetrics.densityDpi,
                minOf(winWidth, winHeight),
            ),
        )
        logLaunch("session-started")
        statusText.text = getString(R.string.starting_desktop)

        // Hand over only once the guest is actually drawn (contract section 2):
        // the ready marker, or the fallback for a guest image that has no marker
        // writer. The old probe treated any `hypr/<instance>` directory as ready,
        // so a leftover from the previous run made it return at once.
        val decision = waitForDesktopReady(File(prefixDir, "tmp/omarchy-runtime"))
        logLaunch("ready ${decision.reason.name.lowercase()}")
        if (!decision.ready) {
            // A blank display is worse than an error screen: stop the
            // half-started session, say what went wrong and let the user retry.
            // The old path handed over anyway and left the user staring at a
            // display nothing drew on.
            val reason = decision.reason.name.lowercase()
            logLaunch("failed $reason")
            ProotService.stop(this)
            showSessionProblem(getString(R.string.error_desktop_not_started, reason))
            return false
        }

        android.util.Log.e("Phoshdroid", "Launching X11 activity...")
        // Hand over only from RESUMED. A permission dialog pauses the
        // launcher, and starting the display under it buries the dialog; when
        // that happens onResume() completes the hand-over from a foreground
        // context, which is also what Android 14+ BAL allows.
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            logLaunch("handover deferred")
            android.util.Log.i(
                "Phoshdroid",
                "bootstrap finished while not resumed; onResume will hand over",
            )
            return true
        }
        forwardToMainActivity()
        return true
    }

    /**
     * Starts the in-process X server (Xlorie). Runs at most once per process:
     * the server lives here, and a second instance on :0 would fight the first
     * for the socket. The caller checks [xServerStarted] and skips the whole
     * block — env, class load, thread — on a later bootstrap.
     */
    private fun startXServer(
        rootfsDir: File,
        prefixDir: String,
        tmpDir: File,
    ) {
        try {
            Os.setenv("TMPDIR", tmpDir.absolutePath, true)
            Os.setenv("TERMUX_X11_OVERRIDE_PACKAGE", packageName, true)
            // XKB keyboard config. Lorie's JNI exits the process when this is
            // unset. Both build trees ship /usr/share/X11/xkb as an absolute
            // symlink into their own namespace (/usr/share/xkeyboard-config-2),
            // which is a broken link from the app side, so the real directory
            // is passed instead.
            val xkbCandidates =
                listOf(
                    File(rootfsDir, "usr/share/xkeyboard-config-2"),
                    File(prefixDir, "share/xkeyboard-config-2"),
                    File(rootfsDir, "usr/share/X11/xkb"),
                    File(prefixDir, "share/X11/xkb"),
                )
            val xkbDir = xkbCandidates.firstOrNull { it.isDirectory }
            if (xkbDir != null) {
                Os.setenv("XKB_CONFIG_ROOT", xkbDir.absolutePath, true)
                android.util.Log.i("Phoshdroid", "XKB_CONFIG_ROOT=${xkbDir.absolutePath}")
            } else {
                android.util.Log.e(
                    "Phoshdroid",
                    "no XKB config root found: ${xkbCandidates.joinToString { it.absolutePath }}",
                )
            }
            // Tell the X server to boot at the real device size so its initial
            // root matches the surface. One prediction feeds XLORIE_WIDTH/HEIGHT,
            // the guest's OMARCHY_OUTPUT_* hint and MainActivity's bottom margin
            // (contract section 1), so no resize happens at hand-over.
            if (geometry.width > 0 && geometry.height > 0) {
                Os.setenv("XLORIE_WIDTH", geometry.width.toString(), true)
                Os.setenv("XLORIE_HEIGHT", geometry.height.toString(), true)
                android.util.Log.i(
                    "Phoshdroid",
                    "XLORIE geometry: ${geometry.width}x${geometry.height} " +
                        "(window ${winWidth}x$winHeight, bottom gap ${geometry.bottomGap})",
                )
            } else {
                android.util.Log.e(
                    "Phoshdroid",
                    "Could not determine display size; the X root will follow the surface",
                )
            }
            android.util.Log.e("Phoshdroid", "=== Phase 5: About to start X11 server ===")

            // First test: can we even load the CmdEntryPoint class?
            val cls = Class.forName("com.termux.x11.CmdEntryPoint")
            android.util.Log.e("Phoshdroid", "CmdEntryPoint class loaded OK: ${cls.name}")

            // Start X11 server with full constructor + Looper (needed for work queue)
            Thread({
                android.os.Looper.prepare()
                try {
                    val ctor =
                        com.termux.x11.CmdEntryPoint::class.java
                            .getDeclaredConstructor(Array<String>::class.java)
                    ctor.isAccessible = true
                    ctor.newInstance(arrayOf(":0"))
                    android.util.Log.e("Phoshdroid", "X11 server running with Looper")
                    android.os.Looper.loop() // Keep alive for work queue processing
                } catch (e: Throwable) {
                    android.util.Log.e("Phoshdroid", "X11 server error: ${e.message}", e)
                }
            }, "X11Server").start()
            xServerStarted = true
        } catch (e: Throwable) {
            android.util.Log.e("Phoshdroid", "X11 setup failed", e)
        }
    }

    /**
     * Waits for the guest to report the desktop as drawn (contract section 2):
     * the `omarchy-ready` marker, or — for a guest image that has no marker
     * writer — a `hypr/<instance>` directory that has existed for 25 s. Gives
     * up at once when the session dies, and after 120 s regardless. The rule
     * itself is the pure [LaunchReadiness.decide].
     */
    private suspend fun waitForDesktopReady(runtimeDir: File): ReadyDecision.Done =
        withContext(Dispatchers.IO) {
            val marker = File(runtimeDir, "omarchy-ready")
            val hyprDir = File(runtimeDir, "hypr")
            val startedAt = System.currentTimeMillis()
            var instanceFirstSeen: Long? = null
            var decision: ReadyDecision = ReadyDecision.Waiting
            while (decision is ReadyDecision.Waiting) {
                val instance = hyprDir.listFiles()?.firstOrNull { it.isDirectory }
                if (instance != null && instanceFirstSeen == null) {
                    instanceFirstSeen = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                decision =
                    LaunchReadiness.decide(
                        markerPresent = marker.exists(),
                        instanceDirAgeMs = instanceFirstSeen?.let { now - it },
                        elapsedMs = now - startedAt,
                        sessionAlive = ProotService.isRunning(),
                    )
                if (decision is ReadyDecision.Waiting) Thread.sleep(200)
            }
            decision as ReadyDecision.Done
        }

    /** One line per launch phase; the contract's log section reads these. */
    private fun logLaunch(phase: String) {
        android.util.Log.i("Phoshdroid", "launch: $phase ${SystemClock.elapsedRealtime() - createdAtMs}ms")
    }

    /**
     * Reads and clears the `session_ended` extra. One shot: a later icon tap
     * must fall through to the START path, not replay the ended screen.
     */
    private fun consumeSessionEnded(intent: Intent?): Int? {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SESSION_ENDED, false)) return null
        val exitCode = intent.getIntExtra(EXTRA_SESSION_EXIT_CODE, -1)
        intent.removeExtra(EXTRA_SESSION_ENDED)
        intent.removeExtra(EXTRA_SESSION_EXIT_CODE)
        return exitCode
    }

    /**
     * The guest exited on its own: show why, with the session log's tail, and
     * wait for Start again. Never starts a session — the launcher decides that,
     * and the decision for this screen is the user's.
     */
    private fun showSessionEnded(exitCode: Int) {
        logLaunch("ended-screen exit=$exitCode")
        val headline =
            if (exitCode == 0) {
                getString(R.string.error_session_ended)
            } else {
                getString(R.string.error_session_stopped_unexpectedly, exitCode)
            }
        showSessionProblem(headline)
    }

    /** Error view for a failed launch: the headline, the log tail and Start again. */
    private fun showSessionProblem(headline: String) {
        val tail = sessionLogTail(SESSION_LOG_TAIL_LINES)
        showError(if (tail.isEmpty()) headline else "$headline\n\n$tail", copyable = true)
        startAgainButton.visibility = View.VISIBLE
    }

    /**
     * The last [lines] lines of the guest session log. Read streaming so a
     * long-running session's log does not have to fit in memory; missing or
     * unreadable means an empty tail, never a failed error screen.
     */
    private fun sessionLogTail(lines: Int): String =
        try {
            val log = File(applicationContext.filesDir, "usr/tmp/omarchy-session.log")
            if (!log.isFile) {
                ""
            } else {
                val tail = ArrayDeque<String>()
                log.bufferedReader().useLines { sequence ->
                    sequence.forEach { line ->
                        if (tail.size == lines) tail.removeFirst()
                        tail.addLast(line)
                    }
                }
                tail.joinToString("\n")
            }
        } catch (e: Exception) {
            "could not read the session log: ${e.message}"
        }

    private fun showProgress(message: String) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        errorText.visibility = View.GONE
    }

    private fun showError(
        message: String,
        copyable: Boolean = false,
    ) {
        statusText.visibility = View.GONE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message

        if (copyable) {
            errorText.setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Omdroid Error", message))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1024) "${mb / 1024} GB" else "$mb MB"
    }

    companion object {
        const val ACTION_SETTINGS = "com.phoshdroid.app.SETTINGS"

        // One switch shared by LauncherActivity and DisplayShiftMonitor: by
        // default a display size change is only recorded, because the guest
        // follows the X root (contract section 3) and a restart would destroy
        // the user's open apps. Flip this only if a device round shows the
        // guest cannot follow a live size change.
        const val RESTART_SESSION_ON_DISPLAY_SHIFT = false

        private const val DISPLAY_STATE_PREFS = "phoshdroid_display_state"

        /** Extras the service sets when the guest exited on its own. */
        const val EXTRA_SESSION_ENDED = "session_ended"
        const val EXTRA_SESSION_EXIT_CODE = "session_exit_code"

        /** Lines of `<prefix>/tmp/omarchy-session.log` shown under an error. */
        private const val SESSION_LOG_TAIL_LINES = 20

        // Per-process flag: true once bootstrap() has run to completion.
        // Resets when the process is killed (fold/unfold restarts the whole
        // process via AlarmManager).
        @Volatile
        private var bootstrapCompleted = false

        // Per-process flag: set while bootstrap() is actively running so a
        // second onCreate in the same process (e.g. user re-tapping the icon
        // while the first bootstrap is still in waitForPhoshReady) doesn't
        // kick off a concurrent second bootstrap and wipe the first's sockets.
        @Volatile
        private var bootstrapInProgress = false

        // Per-process flag: the X server lives in this process and is started at
        // most once. A later bootstrap reuses it instead of wiping its socket and
        // starting a second server on :0.
        @Volatile
        private var xServerStarted = false

        /** How long a START waits for the previous session to die before carrying on. */
        private const val PREVIOUS_SESSION_STOP_TIMEOUT_MS = 5_000L

        /**
         * Read-only view of bootstrapInProgress for DisplayShiftMonitor.
         * Lives here rather than in the monitor because the same flag also
         * gates the duplicate-bootstrap branch of onCreate.
         */
        fun bootstrapInProgressForMonitor(): Boolean = bootstrapInProgress
    }
}
