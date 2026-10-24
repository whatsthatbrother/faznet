package com.faznet.tv.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.data.Updater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The update flow shared by the home screen (automatic) and Settings (button). */
object UpdateUi {

    /** APK waiting for the "install unknown apps" permission (granted in system settings, then we come back). */
    private var waiting: File? = null

    /**
     * Looks for a newer Faznet, downloads it quietly and offers to install it.
     * [interactive] = started from Settings: say what is happening (checking, newest already, no connection).
     */
    fun run(a: AppCompatActivity, interactive: Boolean) {
        a.lifecycleScope.launch {
            if (interactive) toast(a, R.string.update_checking)
            val info = Updater.check(a)
            if (info == null) {
                if (interactive) toast(a, a.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                return@launch
            }
            if (interactive) toast(a, R.string.update_downloading)
            val apk = Updater.download(a, info)
            if (apk == null) {
                if (interactive) toast(a, R.string.update_download_failed)
                return@launch
            }
            if (a.isFinishing || a.isDestroyed) return@launch
            val msg = a.getString(R.string.update_ready_msg, info.version, BuildConfig.VERSION_NAME) +
                if (info.notes.isNotBlank()) "\n\n" + info.notes else ""
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.update_ready_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_install) { _, _ -> install(a, apk) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    private fun install(a: AppCompatActivity, apk: File) {
        if (!Updater.canInstall(a)) {
            waiting = apk
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.update_ready_title)
                .setMessage(R.string.update_permission)
                .setPositiveButton(android.R.string.ok) { _, _ -> Updater.openInstallPermission(a) }
                .setNegativeButton(R.string.update_later) { _, _ -> waiting = null }
                .show()
            return
        }
        toast(a, R.string.update_installing)
        a.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Updater.install(a.applicationContext, apk) }.isSuccess }
            if (!ok) toast(a, a.getString(R.string.update_failed, ""))
        }
    }

    /** Back from the system permission screen: continue the update that was waiting for it. */
    fun resume(a: AppCompatActivity) {
        val apk = waiting ?: return
        if (!Updater.canInstall(a)) return
        waiting = null
        if (apk.exists()) install(a, apk)
    }

    private fun toast(a: AppCompatActivity, res: Int) = Toast.makeText(a, res, Toast.LENGTH_SHORT).show()
    private fun toast(a: AppCompatActivity, text: String) = Toast.makeText(a, text, Toast.LENGTH_LONG).show()
}
