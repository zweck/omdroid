package com.phoshdroid.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.phoshdroid.app.proot.ProotService
import com.phoshdroid.app.storage.ShareRule
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "phoshdroid_prefs"
        setPreferencesFromResource(R.xml.omdroid_preferences, rootKey)

        findPreference<Preference>("reset_factory")?.setOnPreferenceClickListener {
            showResetConfirmation()
            true
        }

        // Storage sharing is a tri-state (contract section 10): auto follows the all-files grant.
        // MANAGE_EXTERNAL_STORAGE is only needed for it, so the system page is opened from here and
        // never from the launch path: a Settings activity started during bootstrap pauses the
        // launcher and the Android 14 BAL guard then skips the hand-over.
        findPreference<ListPreference>(ShareRule.KEY)?.let { pref ->
            pref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { changed, newValue ->
                    (changed as ListPreference).summary = storageSummary(newValue as String)
                    if (newValue != ShareRule.OFF) requestAllFilesAccessIfNeeded()
                    true
                }
            pref.summary = storageSummary(pref.value)
        }
    }

    /** What the mode means right now, including the grant that decides `auto`. */
    private fun storageSummary(mode: String?): String {
        val granted = Environment.isExternalStorageManager()
        val effective = ShareRule.resolve(mode, granted)
        return when {
            effective && granted -> getString(R.string.share_summary_on_granted)
            effective -> getString(R.string.share_summary_on_no_grant)
            ShareRule.normalise(mode) == ShareRule.OFF -> getString(R.string.share_summary_off)
            else -> getString(R.string.share_summary_auto_no_grant)
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
