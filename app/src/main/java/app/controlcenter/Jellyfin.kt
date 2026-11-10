package app.controlcenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Jellyfin library browser with a small player.
 *
 * Browse the libraries, see what is playing right now, search, open an item
 * and play it on the phone. All calls go through the bridge's Jellyfin
 * routes because a Jellyfin server is usually plain HTTP on the LAN and the
 * bridge is the one address this app knows about. The player lives in
 * Player.kt.
 */

object JellyfinApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("${Api.baseUrl}$path").build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            JSONObject(it.body!!.string())
        }
    }

    private fun itemsOf(d: JSONObject): List<Item> {
        val rows = d.getJSONArray("items")
        return (0 until rows.length()).map { parseItem(rows.getJSONObject(it)) }
    }

    suspend fun status(): JSONObject = getJson("/jellyfin/status")

    suspend fun libraries(): List<Library> {
        val rows = getJson("/jellyfin/libraries").getJSONArray("libraries")
        return (0 until rows.length()).map {
            val o = rows.getJSONObject(it)
            Library(o.getString("id"), o.optString("name", "?"), o.optInt("count"))
        }
    }

    suspend fun items(library: String): List<Item> =
        itemsOf(getJson("/jellyfin/items?library=${java.net.URLEncoder.encode(library, "UTF-8")}"))

    suspend fun latest(limit: Int = 12): List<Item> = itemsOf(getJson("/jellyfin/latest?limit=$limit"))

    suspend fun resume(limit: Int = 12): List<Item> = itemsOf(getJson("/jellyfin/resume?limit=$limit"))

    suspend fun search(term: String): List<Item> =
        itemsOf(getJson("/jellyfin/search?term=${java.net.URLEncoder.encode(term, "UTF-8")}"))

    // Posters are small generated PNGs. A handful stay in memory so scrolling
    // back over a grid does not fetch them again.
    private val posters = android.util.LruCache<String, Bitmap>(64)

    suspend fun poster(itemId: String): Bitmap? {
        posters.get(itemId)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("${Api.baseUrl}/jellyfin/thumb/$itemId").build())
                    .execute().use { r ->
                        if (!r.isSuccessful) null
                        else BitmapFactory.decodeStream(r.body!!.byteStream())
                    }
            }.getOrNull()?.also { posters.put(itemId, it) }
        }
    }

    /** One-byte request to see whether a stream answers. Null means it does,
     *  otherwise a short reason. */
    suspend fun probe(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build())
                .execute().use { if (it.isSuccessful) null else "the server answered HTTP ${it.code}" }
        } catch (e: Exception) {
            "the server could not be reached"
        }
    }

    data class Library(val id: String, val name: String, val count: Int)

    data class Item(
        val id: String, val name: String, val type: String,
        val year: Int?, val series: String?, val overview: String,
        val progress: Float, val played: Boolean,
        val runtimeMin: Int?, val stream: String,
    ) {
        /** Where the player should load this item from. */
        val streamUrl: String
            get() = if (stream.startsWith("http")) stream else "${Api.baseUrl}$stream"

        fun toJson(): String = JSONObject().put("id", id).put("name", name).put("type", type)
            .put("year", year ?: JSONObject.NULL).put("series", series ?: JSONObject.NULL)
            .put("overview", overview).put("progress", progress.toDouble())
            .put("played", played).put("runtime_min", runtimeMin ?: JSONObject.NULL)
            .put("stream", stream).toString()
    }

    fun parseItem(o: JSONObject): Item {
        val yr = o.opt("year")
        val rt = o.opt("runtime_min")
        return Item(
            id = o.optString("id"),
            name = o.optString("name", "?"),
            type = o.optString("type", "?"),
            year = if (yr is Number) yr.toInt() else null,
            series = if (o.isNull("series")) null else o.optString("series").takeIf { it.isNotBlank() },
            overview = o.optString("overview"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            played = o.optBoolean("played"),
            runtimeMin = if (rt is Number) rt.toInt() else null,
            stream = o.optString("stream").ifBlank { "/jellyfin/stream/${o.optString("id")}" },
        )
    }
}

/** What is open and playing. Held here rather than in rememberSaveable because
 *  the Media tab is not composed while the capability handshake that follows a
 *  rotation runs, and saved state nobody asks for in time is dropped. */
private object JellyfinSession {
    var openJson by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0)
}

@Composable
fun JellyfinScreen(snackbar: SnackbarHostState) {
    // The open item and the player survive a rotation, which recreates the
    // activity. The item is kept as JSON so it can be rebuilt cheaply.
    var openJson by JellyfinSession::openJson
    var playing by JellyfinSession::playing
    var positionMs by JellyfinSession::positionMs
    val open = remember(openJson) { openJson?.let { JellyfinApi.parseItem(JSONObject(it)) } }

    when {
        open != null && playing -> PlayerScreen(
            title = open.name,
            url = open.streamUrl,
            startMs = positionMs,
            onPosition = { positionMs = it },
            onClose = { playing = false },
        )
        open != null -> ItemDetail(
            item = open,
            onBack = { openJson = null },
            onPlay = { positionMs = 0; playing = true },
        )
        else -> LibraryBrowser(snackbar, onOpen = { openJson = it.toJson() })
    }
}

@Composable
private fun LibraryBrowser(snackbar: SnackbarHostState, onOpen: (JellyfinApi.Item) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var libraries by remember { mutableStateOf(listOf<JellyfinApi.Library>()) }
    var latest by remember { mutableStateOf(listOf<JellyfinApi.Item>()) }
    var resume by remember { mutableStateOf(listOf<JellyfinApi.Item>()) }
    var libraryItems by remember { mutableStateOf<List<JellyfinApi.Item>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var section by rememberSaveable { mutableStateOf("") }   // "" = home, else a library id
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<JellyfinApi.Item>?>(null) }

    fun loadSection() = scope.launch {
        libraryItems = null
        if (section.isNotEmpty()) {
            runCatching { libraryItems = JellyfinApi.items(section) }
                .onFailure { snackbar.showSnackbar("library failed: ${it.message}") }
        }
    }

    fun load() = scope.launch {
        loading = true
        runCatching {
            val s = JellyfinApi.status()
            val libs = JellyfinApi.libraries()
            val l = JellyfinApi.latest(12)
            val r = JellyfinApi.resume(8)
            status = s
            libraries = libs
            latest = l
            resume = r
        }.onFailure { snackbar.showSnackbar("jellyfin failed: ${it.message}") }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(section) { loadSection() }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JELLYFIN", color = Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.width(10.dp))
            status?.let {
                Text("${it.optInt("movies")} movies · ${it.optInt("series")} series",
                    color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { load(); loadSection() }) {
                Icon(Icons.Filled.Refresh, "refresh", tint = Muted)
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                searchResults = null
            },
            placeholder = { Text("search library…", color = Muted, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, "search", tint = Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedBorderColor = Amber, unfocusedBorderColor = Surface1,
                cursorColor = Amber,
            ),
            // Without the Search ime action the keyboard shows a different key
            // and onSearch never fires.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (searchQuery.isNotBlank()) {
                    scope.launch {
                        runCatching {
                            searchResults = JellyfinApi.search(searchQuery.trim())
                        }.onFailure { snackbar.showSnackbar("search failed: ${it.message}") }
                    }
                }
            }),
        )

        if (libraries.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionChip("Home", section.isEmpty()) { section = ""; searchResults = null }
                libraries.forEach { lib ->
                    SectionChip(lib.name, section == lib.id) { section = lib.id; searchResults = null }
                }
            }
        }

        if (loading && status == null) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        ) {
            val results = searchResults
            if (results != null) {
                gridHeader("search-header", "SEARCH RESULTS (${results.size})", Muted)
                if (results.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("no results", color = Muted, fontSize = 12.sp)
                    }
                }
                items(results, key = { "s-${it.id}" }) { ItemTile(it) { onOpen(it) } }
                return@LazyVerticalGrid
            }

            if (section.isNotEmpty()) {
                val shown = libraryItems
                val name = libraries.firstOrNull { it.id == section }?.name ?: section
                gridHeader("lib-header", "${name.uppercase()} (${shown?.size ?: "…"})", Muted)
                items(shown ?: emptyList(), key = { "i-${it.id}" }) { ItemTile(it) { onOpen(it) } }
                return@LazyVerticalGrid
            }

            val active = status?.optJSONArray("active")
            if (active != null && active.length() > 0) {
                gridHeader("active-header", "NOW PLAYING", Amber)
                items((0 until active.length()).toList(), key = { "a-$it" },
                    span = { GridItemSpan(maxLineSpan) }) { i ->
                    val s = active.getJSONObject(i)
                    ActiveStreamCard(
                        name = s.optString("name"),
                        device = s.optString("device"),
                        user = s.optString("user"),
                    )
                }
            }
            if (resume.isNotEmpty()) {
                gridHeader("resume-header", "CONTINUE WATCHING", Muted)
                items(resume, key = { "r-${it.id}" }) { ItemTile(it) { onOpen(it) } }
            }
            if (latest.isNotEmpty()) {
                gridHeader("latest-header", "RECENTLY ADDED", Muted)
                items(latest, key = { "l-${it.id}" }) { ItemTile(it) { onOpen(it) } }
            }
        }
    }
}

private fun LazyGridScope.gridHeader(key: String, text: String, color: Color) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(text, color = color, fontSize = 10.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (color == Amber) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp))
    }

@Composable
private fun SectionChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (on) Amber else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (on) Bg else Ink, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ActiveStreamCard(name: String, device: String, user: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, "playing", tint = Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$device · $user", color = Muted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The generated poster, with a flat placeholder until (or unless) it loads. */
@Composable
private fun Poster(id: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, id) { value = JellyfinApi.poster(id) }
    Box(modifier.background(Surface1)) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ItemTile(item: JellyfinApi.Item, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            Poster(item.id, Modifier.fillMaxSize())
            if (item.played) {
                Text("✓", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Bg.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp))
            }
            if (item.progress > 0f && !item.played) {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Amber, trackColor = Bg.copy(alpha = 0.6f),
                )
            }
        }
        Text(item.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(item.series ?: item.year?.toString() ?: item.type, color = Muted, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ItemDetail(item: JellyfinApi.Item, onBack: () -> Unit, onPlay: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Ink)
            }
            Text("LIBRARY", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp)
        }
        Row(Modifier.padding(top = 8.dp)) {
            Poster(item.id, Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                item.series?.let { Text(it, color = Muted, fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                val facts = listOfNotNull(
                    item.type, item.year?.toString(), item.runtimeMin?.let { "$it min" },
                ).joinToString(" · ")
                Text(facts, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        item.played -> "Played"
                        item.progress > 0f -> "Unplayed, ${item.progress.toInt()}% watched"
                        else -> "Unplayed"
                    },
                    color = if (item.played) Teal else Ink, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (item.progress > 0f && !item.played) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth(), color = Amber, trackColor = Surface1,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPlay, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Amber),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        Spacer(Modifier.height(16.dp))
        Text(item.overview.ifBlank { "No description." }, color = Ink, fontSize = 14.sp,
            lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
    }
}
