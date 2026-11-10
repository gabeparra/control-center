package app.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/* Usage section of the Stack tab: weekly token spend per AI source, so idle
   subscriptions to a coding-agent CLI are visible at a glance. Data comes
   from the bridge's /usage aggregator: Claude Code transcripts, Codex session
   rollouts, and whatever else your bridge chooses to track. */

data class UsageSource(
    val name: String, val weekIn: Long, val weekOut: Long, val weekCache: Long,
    val todayIn: Long, val todayOut: Long, val models: List<Pair<String, Long>>,
)

private fun humanTok(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}

@Composable
fun UsageSection(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(listOf<UsageSource>()) }

    fun load() = scope.launch {
        runCatching {
            val d = Api.get("/usage").getJSONObject("sources")
            val out = mutableListOf<UsageSource>()
            for (key in listOf("claude", "codex", "agent")) {
                val s = d.optJSONObject(key) ?: continue
                val w = s.getJSONObject("week"); val t = s.getJSONObject("today")
                val models = mutableListOf<Pair<String, Long>>()
                s.optJSONObject("models")?.let { mo ->
                    for (m in mo.keys()) models += m to mo.getJSONObject(m).optLong("out")
                }
                models.sortByDescending { it.second }
                out += UsageSource(key, w.optLong("in"), w.optLong("out"), w.optLong("cache"),
                    t.optLong("in"), t.optLong("out"), models.take(3))
            }
            sources = out
        }.onFailure { snackbar.showSnackbar("usage failed: ${it.message}") }
    }

    LaunchedEffect(Unit) { load() }

    if (sources.isEmpty()) return
    val maxOut = sources.maxOf { it.weekOut }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TOKENS · 7 DAYS", color = Amber, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sources.forEach { s ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, color = Ink, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (s.weekOut == 0L && s.weekIn == 0L) "idle all week"
                                else "↑${humanTok(s.weekIn)} ↓${humanTok(s.weekOut)}" +
                                    if (s.todayOut > 0) " · today ↓${humanTok(s.todayOut)}" else "",
                                color = if (s.weekOut == 0L) Bad else Muted,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(4.dp))
                        // relative bar: output tokens vs the busiest source
                        Box(Modifier.fillMaxWidth().height(4.dp)
                            .background(Bg, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(s.weekOut.toFloat() / maxOut)
                                .height(4.dp)
                                .background(if (s.weekOut == 0L) Bad else Teal,
                                    RoundedCornerShape(2.dp)))
                        }
                        if (s.models.isNotEmpty()) {
                            Text(
                                s.models.joinToString("  ·  ") { (m, o) -> "$m ↓${humanTok(o)}" },
                                color = Muted, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}
