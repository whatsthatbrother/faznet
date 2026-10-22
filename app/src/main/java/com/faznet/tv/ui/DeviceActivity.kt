package com.faznet.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.faznet.tv.R
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows this device's MAC + PIN and a QR code to the upload website, and keeps syncing while open. */
class DeviceActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private lateinit var tvMac: TextView
    private lateinit var tvPin: TextView
    /** The PIN protects the device's playlists: shown only on request, hidden again after 30 s. */
    private var pinVisible = false
    private val hidePin = Runnable { pinVisible = false; render() }
    private lateinit var tvUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivQr: ImageView
    private var knownCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)
        findViewById<android.view.View>(android.R.id.content).applyTvSafeArea()
        repo = Repository.get(this)
        tvMac = findViewById(R.id.tvMac)
        tvPin = findViewById(R.id.tvPin)
        tvUrl = findViewById(R.id.tvUrl)
        tvStatus = findViewById(R.id.tvStatus)
        ivQr = findViewById(R.id.ivQr)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSync).setOnClickListener { syncOnce(manual = true) }
        findViewById<Button>(R.id.btnShowPin).setOnClickListener {
            pinVisible = !pinVisible
            tvPin.removeCallbacks(hidePin)
            if (pinVisible) tvPin.postDelayed(hidePin, 30_000)
            render()
        }
        // Server address is built in; hidden from users (long-press Sync to change it).
        findViewById<Button>(R.id.btnServer).visibility = View.GONE
        findViewById<Button>(R.id.btnSync).setOnLongClickListener { editServer(); true }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        knownCount = repo.playlists().count { it.isRemote }
        render()
        findViewById<Button>(R.id.btnSync).requestFocus()

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

    private fun render() {
        val server = repo.serverUrl
        tvMac.text = repo.deviceMac
        tvPin.text = if (pinVisible) repo.devicePin else "••••"
        findViewById<Button>(R.id.btnShowPin).setText(if (pinVisible) R.string.hide_pin else R.string.show_pin)
        // The upload website is given to users separately: the app never shows its address (no URL, no QR).
        tvUrl.visibility = View.GONE
        findViewById<View>(R.id.tvOpenSite).visibility = View.GONE
        ivQr.setImageBitmap(null)
        ivQr.visibility = View.GONE
    }

    private fun syncOnce(manual: Boolean) {
        lifecycleScope.launch {
            if (manual) tvStatus.text = getString(R.string.device_syncing)
            when (val r = RemoteSync.sync(repo)) {
                is RemoteSync.Result.NoServer -> tvStatus.text = getString(R.string.device_no_server)
                is RemoteSync.Result.Error -> tvStatus.text = getString(R.string.device_error, r.message)
                is RemoteSync.Result.Ok -> {
                    tvStatus.text = if (r.count == 0) getString(R.string.device_waiting)
                    else getString(R.string.device_synced, r.count)
                    if (r.changed && r.count > knownCount) {
                        Toast.makeText(this@DeviceActivity, R.string.device_new, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    knownCount = r.count
                }
            }
        }
    }

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
                render()
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
                render()
                syncOnce(manual = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
