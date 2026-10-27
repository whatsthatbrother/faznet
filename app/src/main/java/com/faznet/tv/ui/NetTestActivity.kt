package com.faznet.tv.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                .background(Brush.linearGradient(listOf(Fz.bgTop, Fz.bg, Color(0xFF070A20))))
                .padding(if (tv) PaddingValues(horizontal = 48.dp, vertical = 20.dp) else PaddingValues(12.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(onClick = { finish() }, modifier = Modifier.size(46.dp), shape = RoundedCornerShape(50), focusScale = 1.1f) {
                        Icon(painterResource(R.drawable.ic_back), null, tint = Fz.text, modifier = Modifier.align(Alignment.Center).size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(getString(R.string.net_title), color = Fz.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                // Verdict
                val (vColor, vTitle, vText) = when (verdict) {
                    null -> Triple(Fz.accent, getString(R.string.net_testing), step)
                    NetTest.Verdict.STABLE -> Triple(Color(0xFF2EC27E), getString(R.string.net_stable), getString(R.string.net_stable_text))
                    NetTest.Verdict.OK -> Triple(Color(0xFFFFC94D), getString(R.string.net_ok), getString(R.string.net_ok_text))
                    NetTest.Verdict.UNSTABLE -> Triple(Color(0xFFFF5D6C), getString(R.string.net_unstable), reasons(conn, ping, mbps))
                    NetTest.Verdict.OFFLINE -> Triple(Color(0xFFFF5D6C), getString(R.string.net_offline), getString(R.string.net_offline_text))
                }
                Row(
                    Modifier.fillMaxWidth().background(Fz.surface, RoundedCornerShape(18.dp))
                        .border(2.dp, vColor.copy(alpha = 0.7f), RoundedCornerShape(18.dp)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).background(vColor, RoundedCornerShape(11.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vTitle, color = Fz.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (vText.isNotEmpty()) Text(vText, color = Fz.dim, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val c = conn
                Metric(getString(R.string.net_connection), when (c?.link) {
                    NetTest.Link.WIFI -> "Wi-Fi" + (c?.signalPct?.let { " · ${getString(R.string.net_signal)} $it%" } ?: "")
                    NetTest.Link.ETHERNET -> getString(R.string.net_cable)
                    NetTest.Link.MOBILE -> getString(R.string.net_mobile)
                    NetTest.Link.OTHER -> "—"
                    NetTest.Link.NONE -> getString(R.string.net_none)
                    null -> "…"
                }, good = c?.let { it.link == NetTest.Link.ETHERNET || (it.signalPct ?: 100) >= 60 })
                Metric(getString(R.string.net_ping), ping?.avgMs?.let { "$it ms" } ?: if (done) "—" else "…", good = ping?.avgMs?.let { it < 150 })
                Metric(getString(R.string.net_jitter), ping?.jitterMs?.let { "$it ms" } ?: if (done) "—" else "…", good = ping?.jitterMs?.let { it < 30 })
                Metric(getString(R.string.net_loss), ping?.let { "${it.lossPct}%" } ?: "…", good = ping?.let { it.lossPct == 0 })
                Metric(getString(R.string.net_speed), (mbps ?: liveMbps)?.let { "%.1f Mbps".format(it) } ?: if (done) "—" else "…",
                    good = mbps?.let { it >= 15 })
                Spacer(Modifier.height(10.dp))
                Text(getString(R.string.net_hint), color = Fz.dim, fontSize = 12.5.sp, lineHeight = 18.sp, textAlign = TextAlign.Start)
                Spacer(Modifier.height(16.dp))
                if (done) WideButton(getString(R.string.net_again), primary = true, onClick = { run++ },
                    modifier = Modifier.align(Alignment.CenterHorizontally).focusRequester(again))
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String, good: Boolean?) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Fz.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Fz.dim, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = Fz.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (good != null) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(10.dp).background(if (good) Color(0xFF2EC27E) else Color(0xFFFFC94D), RoundedCornerShape(5.dp)))
                }
            }
        }
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
