package com.faznet.tv.ui

import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faznet.tv.R
import com.faznet.tv.data.NetTest
import com.faznet.tv.data.Repository
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.WideButton
import kotlinx.coroutines.delay

/** Settings → Internet test: is the connection good enough for IPTV right now? */
class NetTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = isTv()
        setContent { FaznetTheme { Screen(tv) } }
    }

    @Composable
    private fun Screen(tv: Boolean) {
        var run by remember { mutableIntStateOf(1) }
        var step by remember { mutableStateOf("") }
        var conn by remember { mutableStateOf<NetTest.Connection?>(null) }
        var ping by remember { mutableStateOf<NetTest.Ping?>(null) }
        var mbps by remember { mutableStateOf<Double?>(null) }
        var liveMbps by remember { mutableStateOf<Double?>(null) }
        var done by remember { mutableStateOf(false) }
        val again = remember { FocusRequester() }

        LaunchedEffect(run) {
            done = false; ping = null; mbps = null; liveMbps = null
            val c = NetTest.connection(this@NetTestActivity)
            conn = c
            if (c.link == NetTest.Link.NONE) { done = true; return@LaunchedEffect }
            val host = NetTest.iptvHost(Repository.get(this@NetTestActivity))
            step = getString(R.string.net_step_ping)
            ping = NetTest.ping(host) { n -> step = getString(R.string.net_step_ping) + " $n/10" }
            step = getString(R.string.net_step_speed)
            mbps = NetTest.speed { liveMbps = it }
            step = ""
            done = true
            delay(100)
            if (tv) runCatching { again.requestFocus() }
        }

        val verdict = if (done) NetTest.verdict(conn ?: NetTest.Connection(NetTest.Link.NONE, null), ping, mbps) else null
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.5f to Color(0xFF0E0E10), 1f to Color.Black))
                .padding(if (tv) PaddingValues(horizontal = 48.dp, vertical = 20.dp) else PaddingValues(12.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                // Header as in Settings: round back button, big title
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(onClick = { finish() }, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(50),
                        color = Fz.surface2, focusedColor = Fz.focusLight, focusedBrush = null, outline = false,
                        focusScale = 1.1f) { focused ->
                        Icon(painterResource(R.drawable.ic_back), null, tint = if (focused) Fz.onLight else Fz.text,
                            modifier = Modifier.align(Alignment.Center).size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(getString(R.string.net_title), color = Fz.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))

                // The verdict: a status mark in the system colours, no coloured frame
                val (vColor, vTitle, vText) = when (verdict) {
                    null -> Triple(Fz.accent, getString(R.string.net_testing), step)
                    NetTest.Verdict.STABLE -> Triple(Green, getString(R.string.net_stable), getString(R.string.net_stable_text))
                    NetTest.Verdict.OK -> Triple(Yellow, getString(R.string.net_ok), getString(R.string.net_ok_text))
                    NetTest.Verdict.UNSTABLE -> Triple(Red, getString(R.string.net_unstable), reasons(conn, ping, mbps))
                    NetTest.Verdict.OFFLINE -> Triple(Red, getString(R.string.net_offline), getString(R.string.net_offline_text))
                }
                Column(
                    Modifier.fillMaxWidth().background(Fz.surface, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            if (verdict == null) CircularProgressIndicator(color = vColor, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                            else Box(Modifier.size(40.dp).background(vColor, CircleShape), contentAlignment = Alignment.Center) {
                                Text(
                                    when (verdict) { NetTest.Verdict.STABLE -> "✓"; NetTest.Verdict.OK -> "!"; else -> "✕" },
                                    color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vTitle, color = Fz.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            if (vText.isNotEmpty()) Text(vText, color = Fz.dim, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    // The speed as one big number, growing while it is measured
                    val speed = mbps ?: liveMbps
                    if (speed != null || (verdict == null && conn?.link != NetTest.Link.NONE)) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(speed?.let { "%.1f".format(it) } ?: "—", color = Fz.text, fontSize = 56.sp,
                                fontWeight = FontWeight.Bold, lineHeight = 56.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Mbps", color = Fz.dim, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }

                // The measurements as one grouped list (iOS), thin lines between the rows
                Text(
                    getString(R.string.net_details).uppercase(), color = Fz.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp),
                )
                val c = conn
                Column(Modifier.fillMaxWidth().background(Fz.surface, RoundedCornerShape(14.dp))) {
                    Metric(getString(R.string.net_connection), when (c?.link) {
                        NetTest.Link.WIFI -> "Wi-Fi" + (c.signalPct?.let { " · ${getString(R.string.net_signal)} $it%" } ?: "")
                        NetTest.Link.ETHERNET -> getString(R.string.net_cable)
                        NetTest.Link.MOBILE -> getString(R.string.net_mobile)
                        NetTest.Link.OTHER -> "—"
                        NetTest.Link.NONE -> getString(R.string.net_none)
                        null -> "…"
                    }, good = c?.let { it.link == NetTest.Link.ETHERNET || (it.signalPct ?: 100) >= 60 })
                    Divider()
                    Metric(getString(R.string.net_ping), ping?.avgMs?.let { "$it ms" } ?: if (done) "—" else "…", good = ping?.avgMs?.let { it < 150 })
                    Divider()
                    Metric(getString(R.string.net_jitter), ping?.jitterMs?.let { "$it ms" } ?: if (done) "—" else "…", good = ping?.jitterMs?.let { it < 30 })
                    Divider()
                    Metric(getString(R.string.net_loss), ping?.let { "${it.lossPct}%" } ?: if (done) "—" else "…", good = ping?.let { it.lossPct == 0 })
                    Divider()
                    Metric(getString(R.string.net_speed), (mbps ?: liveMbps)?.let { "%.1f Mbps".format(it) } ?: if (done) "—" else "…",
                        good = mbps?.let { it >= 15 })
                }
                Text(getString(R.string.net_hint), color = Fz.dim, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Start,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
                Spacer(Modifier.height(18.dp))
                if (done) WideButton(getString(R.string.net_again), primary = true, onClick = { run++ },
                    modifier = Modifier.align(Alignment.CenterHorizontally).focusRequester(again))
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String, good: Boolean?) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // A long label ("Jitter (how much the delay jumps)") wraps itself; the value never breaks in two.
            Text(label, color = Fz.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = Fz.dim, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                if (good != null) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(10.dp).background(if (good) Green else Yellow, CircleShape))
                }
            }
        }
    }

    /** A hairline between two rows of a grouped list, from where the text starts (iOS). */
    @Composable
    private fun Divider() {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(1.dp).background(Fz.line))
    }

    private companion object {
        // iOS system colours, dark mode
        val Green = Color(0xFF30D158)
        val Yellow = Color(0xFFFFD60A)
        val Red = Color(0xFFFF453A)
    }

    /** Why the line is not good enough, in plain words. */
    private fun reasons(c: NetTest.Connection?, p: NetTest.Ping?, mbps: Double?): String {
        val out = ArrayList<String>()
        if (p == null || p.avgMs == null) out.add(getString(R.string.net_r_server))
        else {
            if (p.lossPct > 0) out.add(getString(R.string.net_r_loss, p.lossPct))
            if ((p.jitterMs ?: 0) >= 30) out.add(getString(R.string.net_r_jitter))
            if (p.avgMs >= 150) out.add(getString(R.string.net_r_ping))
        }
        if (mbps == null) out.add(getString(R.string.net_r_nospeed))
        else if (mbps < 15) out.add(getString(R.string.net_r_slow, mbps))
        if (c?.link == NetTest.Link.WIFI && (c?.signalPct ?: 100) < 60) out.add(getString(R.string.net_r_wifi))
        return out.joinToString(" ")
    }
}
