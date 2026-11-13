package com.faznet.tv.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.faznet.tv.R
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.faznet.tv.ui.compose.BackButton
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.PillButton
import com.faznet.tv.ui.compose.ScreenTitle
import com.faznet.tv.ui.compose.screen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows this device's MAC + PIN for the upload website, and keeps syncing while open. The website's address is
 * given to users separately: the app never shows it.
 */
class DeviceActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private var mac by mutableStateOf("")
    /** The PIN protects the device's playlists: shown only on request, hidden again after 30 s. */
    private var pinVisible by mutableStateOf(false)
    private var hidePin: Job? = null
    private var status by mutableStateOf("")
    private var knownCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        mac = repo.deviceMac
        knownCount = repo.playlists().count { it.isRemote }
        val tv = isTv()
        window.setBackgroundDrawable(null)
        setContent { FaznetTheme { Screen(tv) } }

        // Poll while visible so a playlist added on the website shows up by itself.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    syncOnce(manual = false)
                    delay(8_000)
                }
            }
        }
    }

    private fun togglePin() {
        pinVisible = !pinVisible
        hidePin?.cancel()
        if (pinVisible) hidePin = lifecycleScope.launch { delay(30_000); pinVisible = false }
    }

    private fun syncOnce(manual: Boolean) {
        lifecycleScope.launch {
            if (manual) status = getString(R.string.device_syncing)
            status = when (val r = RemoteSync.sync(repo)) {
                is RemoteSync.Result.NoServer -> getString(R.string.device_no_server)
                is RemoteSync.Result.Error -> getString(R.string.device_error, r.message)
                is RemoteSync.Result.Ok -> {
                    if (r.changed && r.count > knownCount) {
                        Toast.makeText(this@DeviceActivity, R.string.device_new, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    knownCount = r.count
                    if (r.count == 0) getString(R.string.device_waiting) else getString(R.string.device_synced, r.count)
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen(tv: Boolean) {
        val sync = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().screen(tv)) {
            Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(::finish)
                ScreenTitle(stringResource(R.string.device_title))
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(min = 0.dp, max = 640.dp).background(Fz.surface, RoundedCornerShape(24.dp))
                        .padding(horizontal = 40.dp, vertical = 34.dp),
                ) {
                    Label(stringResource(R.string.device_mac))
                    Value(mac, 0.06f)
                    Spacer(Modifier.height(22.dp))
                    Label(stringResource(R.string.device_pin))
                    Value(if (pinVisible) repo.devicePin else "••••", 0.35f)
                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(22.dp))
                        Text(status, color = Fz.dim, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(26.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(if (pinVisible) R.string.hide_pin else R.string.show_pin), primary = false, ::togglePin)
                        // The server address is built in; long-press Sync to change it (hidden from users).
                        PillButton(stringResource(R.string.device_sync), primary = true, { syncOnce(manual = true) },
                            onLongClick = ::editServer, modifier = Modifier.focusRequester(sync))
                        PillButton(stringResource(R.string.device_reset), primary = false, ::confirmReset)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { delay(80); runCatching { sync.requestFocus() } }
    }

    @Composable
    private fun Label(text: String) =
        Text(text.uppercase(), color = Fz.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)

    @Composable
    private fun Value(text: String, spacing: Float) =
        Text(text, color = Fz.text, fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = TextUnit(spacing, androidx.compose.ui.unit.TextUnitType.Em),
            modifier = Modifier.padding(top = 2.dp))

    private fun editServer() {
        val input = EditText(this).apply {
            setText(repo.customServer.ifEmpty { repo.serverUrl })
            hint = getString(R.string.server_hint_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_server)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                var v = input.text.toString().trim()
                if (v.isNotEmpty() && !v.contains("://")) v = "https://$v"
                repo.customServer = v
                syncOnce(manual = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.device_reset_confirm)
            .setPositiveButton(R.string.device_reset) { _, _ ->
                repo.newDeviceIdentity()
                mac = repo.deviceMac
                syncOnce(manual = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
