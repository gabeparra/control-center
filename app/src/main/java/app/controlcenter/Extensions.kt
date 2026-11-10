package app.controlcenter

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/* Extensions: integrations the bridge describes and the app draws.
 *
 * The bridge lists its extensions at GET /extensions and serves one screen per
 * extension at GET /extensions/{id} as sections of small typed blocks (stats,
 * list, progress, text, chips, actions, link). This file is the one generic
 * renderer, so a new integration needs a bridge change and no new APK. Buttons
 * post {action, item, input} to /extensions/{id}/action and the screen is then
 * fetched again.
 *
 * Forward compatibility is the point: an unknown block type, an unknown field
 * or a field of the wrong type is skipped, never an exception. Every read goes
 * through the tolerant helpers below.
 */

// ---------- tolerant JSON reading ----------
private fun JSONObject.str(name: String): String =
    if (isNull(name)) "" else optString(name, "")

private fun JSONObject.strOrNull(name: String): String? = str(name).takeIf { it.isNotBlank() }

private fun JSONObject.objs(name: String): List<JSONObject> =
    optJSONArray(name)?.objs() ?: emptyList()

private fun JSONArray.objs(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

// ---------- model ----------
data class ExtCard(val id: String, val name: String, val icon: String, val summary: String,
                   val badge: Int?, val tone: String, val group: String = "")
data class ExtChip(val text: String, val tone: String)
data class ExtTile(val label: String, val value: String, val tone: String)
data class ExtAct(val id: String, val label: String, val style: String, val confirm: String?,
                  val inputLabel: String?, val inputHint: String?, val item: String?)
data class ExtRow(val id: String?, val title: String, val subtitle: String, val meta: String,
                  val tone: String, val chips: List<ExtChip>, val progress: Int?,
                  val actions: List<ExtAct>)

sealed interface ExtBlock {
    data class Stats(val tiles: List<ExtTile>) : ExtBlock
    data class Rows(val items: List<ExtRow>) : ExtBlock
    data class Prog(val label: String, val value: Int, val tone: String) : ExtBlock
    data class Para(val text: String, val mono: Boolean) : ExtBlock
    data class Chips(val chips: List<ExtChip>) : ExtBlock
    data class Acts(val actions: List<ExtAct>) : ExtBlock
    data class Link(val label: String, val url: String) : ExtBlock
}

data class ExtSection(val title: String, val blocks: List<ExtBlock>)
data class ExtScreen(val id: String, val name: String, val refreshS: Int, val sections: List<ExtSection>)

private fun parseChips(a: JSONArray?): List<ExtChip> =
    a?.objs()?.mapNotNull { c -> c.strOrNull("text")?.let { ExtChip(it, c.str("tone")) } } ?: emptyList()

private fun parseActs(a: JSONArray?): List<ExtAct> =
    a?.objs()?.mapNotNull { o ->
        val id = o.strOrNull("id") ?: return@mapNotNull null
        val inp = o.optJSONObject("input")
        ExtAct(id, o.str("label").ifBlank { id }, o.str("style"), o.strOrNull("confirm"),
            inp?.let { it.str("label") }, inp?.let { it.str("placeholder") }, o.strOrNull("item"))
    } ?: emptyList()

private fun parseBlock(o: JSONObject): ExtBlock? = when (o.str("type")) {
    "stats" -> ExtBlock.Stats(o.objs("tiles").map { ExtTile(it.str("label"), it.str("value"), it.str("tone")) })
    "list" -> ExtBlock.Rows(o.objs("items").map { r ->
        ExtRow(r.strOrNull("id"), r.str("title"), r.str("subtitle"), r.str("meta"), r.str("tone"),
            parseChips(r.optJSONArray("chips")),
            if (r.has("progress") && !r.isNull("progress")) r.optInt("progress").coerceIn(0, 100) else null,
            parseActs(r.optJSONArray("actions")))
    })
    "progress" -> ExtBlock.Prog(o.str("label"), o.optInt("value").coerceIn(0, 100), o.str("tone"))
    "text" -> ExtBlock.Para(o.str("text"), o.optBoolean("mono", false))
    "chips" -> ExtBlock.Chips(parseChips(o.optJSONArray("chips")))
    "actions" -> ExtBlock.Acts(parseActs(o.optJSONArray("actions")))
    "link" -> o.strOrNull("url")?.let { ExtBlock.Link(o.str("label").ifBlank { it }, it) }
    else -> null    // a block type from a newer bridge: skip it
}

private fun parseScreen(d: JSONObject, fallbackId: String): ExtScreen = ExtScreen(
    d.strOrNull("id") ?: fallbackId, d.str("name").ifBlank { fallbackId },
    d.optInt("refresh_s", 10).coerceAtLeast(2),
    d.objs("sections").map { s -> ExtSection(s.str("title"), s.objs("blocks").mapNotNull { parseBlock(it) }) })

private fun parseCards(d: JSONObject): List<ExtCard> = d.objs("extensions").mapNotNull { o ->
    val id = o.strOrNull("id") ?: return@mapNotNull null
    ExtCard(id, o.str("name").ifBlank { id }, o.str("icon"), o.str("summary"),
        if (o.isNull("badge") || !o.has("badge")) null else o.optInt("badge"), o.str("tone"), o.str("group").trim())
}

/** Cards in the order their group first appears, ungrouped ones last. Bridges that send no group get one headerless run. */
private fun groupCards(cards: List<ExtCard>): List<Pair<String, List<ExtCard>>> =
    cards.groupBy { it.group }.toList().sortedBy { (g, _) -> g.isEmpty() }

// ---------- look ----------
private val ToneOk = Color(0xFF7BC47F)
private val ToneWarn = Color(0xFFE0A33B)

private fun toneColor(tone: String): Color = when (tone) {
    "ok" -> ToneOk
    "warn" -> ToneWarn
    "bad" -> Bad
    else -> Muted
}

private fun extIcon(name: String): ImageVector = when (name) {
    "github" -> Icons.Filled.AccountTree
    "code" -> Icons.Filled.Code
    "build" -> Icons.Filled.Construction
    "map" -> Icons.Filled.Explore
    "game" -> Icons.Filled.SportsEsports
    "star" -> Icons.Filled.Star
    "terminal" -> Icons.Filled.Terminal
    "cloud" -> Icons.Filled.Cloud
    "chart" -> Icons.Filled.BarChart
    "bolt" -> Icons.Filled.Bolt
    "list" -> Icons.Filled.FormatListBulleted
    "home" -> Icons.Filled.Home
    "info" -> Icons.Filled.Info
    else -> Icons.Filled.Extension
}

/** Absolute http(s) URLs pass through, "/path" is relative to the bridge, anything else is refused. */
private fun resolveUrl(raw: String): String? {
    val u = if (raw.startsWith("/")) Api.baseUrl + raw else raw
    return u.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

// ---------- screens ----------
@Composable
fun ExtensionsScreen(snackbar: SnackbarHostState) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = openId
    BackHandler(enabled = id != null) { openId = null }
    if (id == null) ExtensionList(snackbar) { openId = it }
    else ExtensionDetail(id, snackbar) { openId = null }
}

@Composable
private fun ExtensionList(snackbar: SnackbarHostState, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<ExtCard>?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun load() = scope.launch {
        loading = true
        runCatching { cards = parseCards(Api.get("/extensions")) }
            .onFailure { snackbar.showSnackbar("extensions failed: ${it.message}") }
        loading = false
    }
    LaunchedEffect(Unit) {
        load()
        // The badges and summaries move (a run finishes, a tunnel drops), so
        // keep the list fresh while it is on screen.
        while (true) {
            delay(15_000)
            runCatching { cards = parseCards(Api.get("/extensions")) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EXTENSIONS", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }
        if (loading && cards == null) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
        }
        if (cards?.isEmpty() == true) {
            Text("This bridge has no extensions enabled.", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        ) {
            groupCards(cards ?: emptyList()).forEach { (group, members) ->
              if (group.isNotEmpty()) item(key = "group:$group") {
                Text(group.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
              }
              items(members, key = { it.id }) { c ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(c.id) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(extIcon(c.icon), null, tint = Teal, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (c.summary.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(c.tone)
                                    Text(c.summary, color = Muted, fontSize = 11.sp, maxLines = 2)
                                }
                            }
                        }
                        if (c.badge != null && c.badge > 0) {
                            Badge(containerColor = if (c.tone == "bad") Bad else Amber) {
                                Text("${c.badge}", fontSize = 10.sp)
                            }
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Dot(tone: String) {
    Box(Modifier.padding(end = 7.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(toneColor(tone)))
}

/** An action waiting for the user to confirm it or type its input. */
private data class PendingAct(val act: ExtAct, val item: String?)

@Composable
private fun ExtensionDetail(id: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember(id) { mutableStateOf<ExtScreen?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var busy by remember(id) { mutableStateOf(false) }
    var pending by remember(id) { mutableStateOf<PendingAct?>(null) }

    suspend fun fetch() {
        runCatching { parseScreen(Api.get("/extensions/$id"), id) }
            .onSuccess { screen = it; error = null }
            .onFailure { error = it.message ?: "failed" }
    }
    fun load() = scope.launch { loading = true; fetch(); loading = false }

    // Auto refresh at the pace the bridge asks for, for as long as this screen
    // is the one showing. A failed refresh keeps the last good screen.
    LaunchedEffect(id) {
        load()
        while (true) {
            delay((screen?.refreshS ?: 10) * 1000L)
            if (!busy) fetch()
        }
    }

    fun send(act: ExtAct, item: String?, input: String?) = scope.launch {
        busy = true
        runCatching {
            val body = JSONObject().put("action", act.id)
            if (item != null) body.put("item", item)
            if (input != null) body.put("input", input)
            Api.post("/extensions/$id/action", body)
        }.onSuccess { r ->
            val msg = r.str("message")
            val okay = r.optBoolean("ok", true)
            // showSnackbar suspends until the message goes away, so it must not
            // hold up the refresh or the buttons stay disabled for several seconds.
            if (msg.isNotBlank()) scope.launch { snackbar.showSnackbar(if (okay) msg else "failed: $msg") }
            else if (!okay) scope.launch { snackbar.showSnackbar("${act.label} failed") }
            if (r.optBoolean("refresh", true)) fetch()
        }.onFailure { snackbar.showSnackbar("${act.label} failed: ${it.message}") }
        busy = false
    }

    fun onAct(act: ExtAct, rowItem: String?) {
        val item = act.item ?: rowItem
        if (act.confirm != null || act.inputLabel != null) pending = PendingAct(act, item)
        else send(act, item, null)
    }

    fun openLink(raw: String) {
        val url = resolveUrl(raw)
        if (url == null) { scope.launch { snackbar.showSnackbar("cannot open $raw") }; return }
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { scope.launch { snackbar.showSnackbar("no browser for $url") } }
    }

    pending?.let { p ->
        var text by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = Surface1,
            title = { Text(p.act.label, color = Ink) },
            text = {
                Column {
                    p.act.confirm?.let { Text(it, color = Muted) }
                    if (p.act.inputLabel != null) {
                        if (p.act.confirm != null) Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            label = { Text(p.act.inputLabel) },
                            placeholder = if (!p.act.inputHint.isNullOrBlank()) ({ Text(p.act.inputHint) }) else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Amber,
                                focusedBorderColor = Amber, focusedLabelColor = Amber),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = p.act.inputLabel == null || text.isNotBlank(),
                    onClick = { pending = null; send(p.act, p.item, if (p.act.inputLabel != null) text.trim() else null) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.act.style == "danger") Bad else Amber),
                ) { Text(p.act.label) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", color = Muted) } },
        )
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "back", tint = Ink) }
            Text((screen?.name ?: id).uppercase(), color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1,
                modifier = Modifier.weight(1f))
            if (loading || busy) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "refresh", tint = Muted) }
        }
        val s = screen
        if (s == null) {
            if (error != null) Text("Could not load: $error", color = Bad, fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp))
        } else {
            if (error != null) Text("refresh failed: $error", color = Muted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                s.sections.forEachIndexed { si, sec ->
                    if (sec.title.isNotBlank()) item(key = "h$si") {
                        Text(sec.title.uppercase(), color = Muted, fontSize = 10.sp,
                            letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    sec.blocks.forEachIndexed { bi, b ->
                        // Rows are items of their own so a long list scrolls lazily.
                        if (b is ExtBlock.Rows) {
                            items(b.items.size, key = { "r$si-$bi-$it" }) { i ->
                                RowCard(b.items[i], busy, ::onAct)
                            }
                        } else item(key = "b$si-$bi") { BlockView(b, busy, ::onAct, ::openLink) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockView(b: ExtBlock, busy: Boolean, onAct: (ExtAct, String?) -> Unit, onLink: (String) -> Unit) {
    when (b) {
        is ExtBlock.Stats -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.tiles.forEach { t ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Text(t.value, color = if (t.tone.isBlank() || t.tone == "muted") Ink else toneColor(t.tone),
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(t.label, color = Muted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
        is ExtBlock.Rows -> {}
        is ExtBlock.Prog -> Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text(b.label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${b.value}%", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { b.value / 100f }, modifier = Modifier.fillMaxWidth(),
                    color = toneColor(b.tone).takeIf { b.tone.isNotBlank() && b.tone != "muted" } ?: Amber,
                    trackColor = Bg)
            }
        }
        is ExtBlock.Para ->
            if (b.mono) Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(b.text, color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp, modifier = Modifier.padding(12.dp))
            } else Text(b.text, color = Muted, fontSize = 13.sp)
        is ExtBlock.Chips -> ChipRow(b.chips)
        is ExtBlock.Acts -> ActionRow(b.actions, null, false, busy, onAct)
        is ExtBlock.Link -> Card(
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable { onLink(b.url) },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(b.url, color = Teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Icon(Icons.Filled.OpenInNew, "open ${b.label}", tint = Muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(chips: List<ExtChip>) {
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { c ->
            val col = toneColor(c.tone)
            Text(c.text, color = col, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(acts: List<ExtAct>, rowItem: String?, small: Boolean, busy: Boolean, onAct: (ExtAct, String?) -> Unit) {
    if (acts.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        acts.forEach { a ->
            val pad = if (small) PaddingValues(horizontal = 12.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding
            val mod = if (small) Modifier.height(32.dp) else Modifier
            when (a.style) {
                "primary" -> Button(onClick = { onAct(a, rowItem) }, enabled = !busy, modifier = mod,
                    contentPadding = pad, colors = ButtonDefaults.buttonColors(containerColor = Amber)) {
                    Text(a.label, fontSize = if (small) 12.sp else 14.sp)
                }
                "danger" -> OutlinedButton(onClick = { onAct(a, rowItem) }, enabled = !busy, modifier = mod,
                    contentPadding = pad, colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)) {
                    Text(a.label, fontSize = if (small) 12.sp else 14.sp)
                }
                else -> OutlinedButton(onClick = { onAct(a, rowItem) }, enabled = !busy, modifier = mod,
                    contentPadding = pad, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
                    Text(a.label, fontSize = if (small) 12.sp else 14.sp)
                }
            }
        }
    }
}

@Composable
private fun RowCard(r: ExtRow, busy: Boolean, onAct: (ExtAct, String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp)) { Dot(r.tone) }
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (r.subtitle.isNotBlank()) Text(r.subtitle, color = Muted, fontSize = 11.sp)
                }
                if (r.meta.isNotBlank()) Text(r.meta, color = Muted, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
            ChipRow(r.chips)
            if (r.progress != null) LinearProgressIndicator(
                progress = { r.progress / 100f }, modifier = Modifier.fillMaxWidth(),
                color = toneColor(r.tone).takeIf { r.tone.isNotBlank() && r.tone != "muted" } ?: Amber,
                trackColor = Bg)
            ActionRow(r.actions, r.id, true, busy, onAct)
        }
    }
}
