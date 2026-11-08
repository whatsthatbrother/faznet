package com.faznet.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.data.DbErrors
import com.faznet.tv.data.Parental
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.Reports
import com.faznet.tv.data.Repository
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings as a full screen made for the remote: every change is saved at once (no Save button). */
@dagger.hilt.android.AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @javax.inject.Inject lateinit var channelRepo: com.faznet.tv.data.ChannelRepository

    private sealed class Item {
        class Header(val title: String) : Item()
        class Toggle(val title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item()
        class Action(val title: String, val sub: () -> String, val icon: Int, val run: () -> Unit) : Item()
        class Info(val text: String) : Item()
    }

    private lateinit var repo: Repository
    /** Increased after a dialog changes something, so the screen shows the new values. */
    private val refresh = mutableIntStateOf(0)

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        val tv = isTv()
        setContent {
            FaznetTheme {
                if (tv) androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.faznet.tv.ui.compose.PivotScroll
                ) { Screen(buildItems(), tv) }
                else Screen(buildItems(), tv)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateUi.resume(this)
        refresh.intValue++
    }

    private fun buildItems(): List<Item> = listOf(
        Item.Header(getString(R.string.section_player)),
        Item.Toggle(getString(R.string.use_ffmpeg), { repo.useFfmpeg }, { repo.useFfmpeg = it }),
        Item.Toggle(getString(R.string.software_sd), { repo.softwareSd }, { repo.softwareSd = it }),
        Item.Toggle(getString(R.string.show_stats), { repo.showStats }, { repo.showStats = it }),
        Item.Header(getString(R.string.section_start)),
        Item.Toggle(getString(R.string.start_with_live), { repo.startWithLive }, { repo.startWithLive = it }),
        Item.Toggle(getString(R.string.autoplay_last), { repo.autoPlayLast }, { repo.autoPlayLast = it }),
        Item.Header(getString(R.string.section_reports)),
        Item.Toggle(getString(R.string.auto_reports), { repo.autoReports }, { repo.autoReports = it }),
        Item.Action(getString(R.string.problem_log), {
            ProblemLog.read(this).size.let { if (it == 0) getString(R.string.problem_log_empty) else "$it" }
        }, R.drawable.ic_stats) { showProblemLog() },
        Item.Action(getString(R.string.report_send), { getString(R.string.report_explain) }, R.drawable.ic_check) { sendReport() },
        Item.Header(getString(R.string.section_updates)),
        Item.Toggle(getString(R.string.auto_update), { repo.autoUpdate }, { repo.autoUpdate = it }),
        Item.Action(getString(R.string.check_updates), { "Faznet ${BuildConfig.VERSION_NAME}" }, R.drawable.ic_refresh) {
            UpdateUi.run(this, interactive = true)
        },
        Item.Header(getString(R.string.section_other)),
        Item.Action(getString(R.string.net_title), { getString(R.string.net_sub) }, R.drawable.ic_stats) {
            startActivity(Intent(this, NetTestActivity::class.java))
        },
        Item.Action(getString(R.string.playlists), { repo.activePlaylist()?.name ?: "" }, R.drawable.ic_playlist) {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        },
        Item.Action(getString(R.string.device_title), { "MAC ${repo.deviceMac}" }, R.drawable.ic_device) {
            startActivity(Intent(this, DeviceActivity::class.java))
        },
        Item.Action(getString(R.string.parental_title), {
            if (Parental.enabled(this)) "🔒 ${Parental.lockedGroups(this).size}" else ""
        }, R.drawable.ic_settings) { setupParental() },
        Item.Action(getString(R.string.user_agent), { repo.customUserAgent.ifEmpty { getString(R.string.user_agent_hint) } },
            R.drawable.ic_engine) { editUserAgent() },
        Item.Info(getString(R.string.about, BuildConfig.VERSION_NAME)),
    )

    @Composable
    private fun Screen(list: List<Item>, tv: Boolean) {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue // re-read the values after dialogs
        val first = remember { FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.5f to Color(0xFF0E0E10), 1f to Color.Black))
                .padding(if (tv) PaddingValues(horizontal = 48.dp, vertical = 20.dp) else PaddingValues(8.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(onClick = { finish() }, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(50),
                        color = Fz.surface2, focusedColor = Fz.focusLight, focusedBrush = null, outline = false,
                        focusScale = 1.1f) { focused ->
                        Icon(painterResource(R.drawable.ic_back), null, tint = if (focused) Fz.onLight else Fz.text,
                            modifier = Modifier.align(Alignment.Center).size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(getString(R.string.settings), color = Fz.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("Faznet ${BuildConfig.VERSION_NAME}", color = Fz.dim, fontSize = 14.sp)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list) { item ->
                        when (item) {
                            is Item.Header -> Text(
                                item.title.uppercase(), color = Fz.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 4.dp),
                            )
                            is Item.Toggle -> ToggleRow(item, if (item === list.firstOrNull { it is Item.Toggle }) first else null)
                            is Item.Action -> ActionRow(item)
                            is Item.Info -> Text(item.text, color = Fz.dim, fontSize = 13.sp, lineHeight = 19.sp,
                                modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 24.dp))
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            if (tv) { delay(80); runCatching { first.requestFocus() } }
        }
    }

    @Composable
    private fun ToggleRow(item: Item.Toggle, requester: FocusRequester?) {
        var on by remember { mutableStateOf(item.get()) }
        FocusBox(
            onClick = { on = !on; item.set(on) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
            shape = RowShape,
            color = Fz.surface,
            focusedColor = Fz.focusLight,
            focusedBrush = null,
            outline = false,
            focusScale = 1.02f,
        ) { focused ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = if (focused) Fz.onLight else Fz.text, fontSize = 16.sp,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                // iOS switch: green when on
                Box(
                    Modifier.size(width = 50.dp, height = 30.dp)
                        .background(if (on) SwitchOn else if (focused) Color(0xFFD1D1D6) else Color(0xFF3A3A3C), RoundedCornerShape(15.dp)),
                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(Modifier.padding(2.dp).size(26.dp).background(Color.White, RoundedCornerShape(13.dp)))
                }
            }
        }
    }

    @Composable
    private fun ActionRow(item: Item.Action) {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue
        FocusBox(
            onClick = item.run, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RowShape,
            color = Fz.surface, focusedColor = Fz.focusLight, focusedBrush = null, outline = false, focusScale = 1.02f,
        ) { focused ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(item.icon), null, tint = if (focused) Fz.accent else Fz.dim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = if (focused) Fz.onLight else Fz.text, fontSize = 16.sp,
                        fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal)
                    val sub = item.sub()
                    if (sub.isNotEmpty()) Text(sub, color = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim,
                        fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = if (focused) Fz.onLight.copy(alpha = 0.5f) else Fz.dim,
                    modifier = Modifier.size(20.dp))
            }
        }
    }

    private companion object {
        val RowShape = RoundedCornerShape(14.dp)
        val SwitchOn = Color(0xFF30D158) // iOS green
    }

    // ---------- dialogs ----------
    private fun padded(view: View): View = android.widget.FrameLayout(this).apply {
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }

    private fun showProblemLog() {
        val lines = ProblemLog.read(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.problem_log)
            .setMessage(if (lines.isEmpty()) getString(R.string.problem_log_empty) else lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear) { _, _ -> ProblemLog.clear(this); refresh.intValue++ }
            .setNegativeButton(R.string.report_send) { _, _ -> sendReport() }
            .show()
    }

    /** Sends the problem log + device info to the developer at once (no questions). */
    private fun sendReport() {
        lifecycleScope.launch {
            val ok = Reports.sendManual(this@SettingsActivity, repo)
            Toast.makeText(this@SettingsActivity, if (ok) R.string.report_sent else R.string.report_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun editUserAgent() {
        val et = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.user_agent_hint)
            setText(repo.customUserAgent)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.user_agent)
            .setView(padded(et))
            .setPositiveButton(R.string.save) { _, _ -> repo.customUserAgent = et.text.toString(); refresh.intValue++ }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pinField(): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(4))
        hint = "••••"
    }

    /**
     * The PIN only opens the lock's settings. It does not unlock the locked groups (before, pressing Cancel after it
     * left them open until the app was closed).
     */
    private fun askParentalPin(onOk: () -> Unit) {
        val et = pinField()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_enter_pin)
            .setView(padded(et))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (et.text.toString() == Parental.pin(this)) onOk()
                else Toast.makeText(this, R.string.parental_wrong_pin, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        et.requestFocus()
    }

    private fun setupParental(pinChecked: Boolean = false) {
        val current = Parental.pin(this)
        val hasPin = current.length == 4
        if (hasPin && !pinChecked && !Parental.unlocked) {
            askParentalPin { setupParental(pinChecked = true) }
            return
        }
        // The current PIN is never shown: an empty field keeps it, "Turn off" removes the lock.
        val et = pinField()
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_title)
            .setMessage(if (hasPin) R.string.parental_new_pin else R.string.parental_set_pin)
            .setView(padded(et))
            .setPositiveButton(R.string.next) { _, _ ->
                val pin = et.text.toString()
                when {
                    pin.isEmpty() && hasPin -> chooseLockedGroups(current)
                    pin.isEmpty() -> Unit
                    pin.length != 4 -> Toast.makeText(this, R.string.parental_pin_4, Toast.LENGTH_SHORT).show()
                    else -> chooseLockedGroups(pin)
                }
            }
            .setNegativeButton(R.string.cancel, null)
        if (hasPin) b.setNeutralButton(R.string.parental_turn_off) { _, _ ->
            Parental.setPin(this, ""); Parental.setLockedGroups(this, emptySet())
            Toast.makeText(this, R.string.parental_off, Toast.LENGTH_SHORT).show()
            refresh.intValue++
        }
        b.show()
        et.requestFocus()
    }

    private fun chooseLockedGroups(pin: String) {
        lifecycleScope.launch(DbErrors) {
            val listId = repo.activePlaylist()?.let { channelRepo.activeList(it.id) }
            val groups = listId?.let { channelRepo.listView(it).groups.map { g -> g.name } }.orEmpty()
            if (groups.isEmpty()) Toast.makeText(this@SettingsActivity, R.string.no_channels, Toast.LENGTH_SHORT).show()
            else chooseLockedGroups(pin, groups)
        }
    }

    private fun chooseLockedGroups(pin: String, groups: List<String>) {
        val locked = Parental.lockedGroups(this)
        val checked = BooleanArray(groups.size) { groups[it] in locked }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_groups)
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                Parental.setPin(this, pin)
                Parental.setLockedGroups(this, groups.filterIndexed { i, _ -> checked[i] }.toSet())
                Parental.unlocked = false
                Toast.makeText(this, R.string.parental_saved, Toast.LENGTH_SHORT).show()
                refresh.intValue++
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
