package app.controlcenter

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/* Links: every web UI you run, in one tappable list.
 *
 * The list is fetched from the bridge rather than compiled in. URLs move (a new
 * tailscale serve port, a relocated service) and republishing an APK to fix a
 * link would be absurd; editing the bridge's link list updates the phone
 * immediately.
 */

data class LinkItem(val name: String, val url: String, val desc: String,
                    val live: Boolean? = null)
data class LinkGroup(val name: String, val links: List<LinkItem>)

@Composable
fun LinksScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf(listOf<LinkGroup>()) }
    var loading by remember { mutableStateOf(true) }

    fun load() = scope.launch {
        loading = true
        runCatching {
            val d = Api.get("/links")
            val ga = d.getJSONArray("groups")
            groups = (0 until ga.length()).map { i ->
                val g = ga.getJSONObject(i)
                val la = g.getJSONArray("links")
                LinkGroup(g.getString("name"), (0 until la.length()).map { j ->
                    val l = la.getJSONObject(j)
                    LinkItem(l.getString("name"), l.getString("url"), l.optString("desc"),
                        if (l.isNull("live")) null else l.optBoolean("live"))
                })
            }
        }.onFailure { snackbar.showSnackbar("links failed: ${it.message}") }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LINKS", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }

        if (loading && groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp))
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        ) {
            groups.forEach { g ->
                item(key = "h-${g.name}") {
                    Text(g.name.uppercase(), color = Muted, fontSize = 10.sp,
                        letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(g.links, key = { it.url }) { l ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface1),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(l.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                scope.launch { snackbar.showSnackbar("no browser for ${l.url}") }
                            }
                        },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // A link that quietly goes nowhere is worse than one
                            // labelled down: dev servers are up only while
                            // something is being worked on.
                            Box(
                                Modifier.padding(end = 10.dp).size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(when (l.live) {
                                        true -> Good
                                        false -> Bad
                                        null -> Muted
                                    })
                            )
                            Column(Modifier.weight(1f)) {
                                Text(l.name, color = Ink, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold)
                                if (l.desc.isNotBlank())
                                    Text(l.desc, color = Muted, fontSize = 11.sp)
                                Text(l.url, color = Teal, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            Icon(Icons.Filled.OpenInNew, "open ${l.name}",
                                tint = Muted, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}
