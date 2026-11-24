package com.phoshdroid.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.phoshdroid.app.proot.ProotService
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "phoshdroid_prefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("reset_factory")?.setOnPreferenceClickListener {
            showResetConfirmation()
            true
        }

        // MANAGE_EXTERNAL_STORAGE is only needed by "Mount /sdcard", so the
        // system page is opened from that switch and never from the launch
        // path: a Settings activity started during bootstrap pauses the
        // launcher and the Android 14 BAL guard then skips the hand-over.
        findPreference<SwitchPreferenceCompat>("bind_sdcard")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) requestAllFilesAccessIfNeeded()
            true
        }
    }

    private fun requestAllFilesAccessIfNeeded() {
        if (Environment.isExternalStorageManager()) return
        val packageName = requireContext().packageName
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            // Fallback: generic Settings entry for MANAGE_EXTERNAL_STORAGE
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset to Factory")
            .setMessage("This will stop the desktop and re-extract the rootfs. Your changes inside Omarchy will be lost. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                ProotService.stop(requireContext())
                val filesDir = requireContext().filesDir
                File(filesDir, "rootfs/.extraction_complete").delete()
                File(
                    filesDir,
                    "usr/var/lib/proot-distro/installed-rootfs/${ProotService.DISTRO_NAME}",
                ).deleteRecursively()
                requireActivity().recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
