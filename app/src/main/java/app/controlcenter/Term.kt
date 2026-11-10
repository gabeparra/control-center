package app.controlcenter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

// Terminal mirror v2: bridge pipes input via `tmux send-keys` and returns
// `tmux capture-pane -e` (ANSI) snapshots rendered with the Ansi parser, so
// colored terminal programs show properly. A normal TextField keeps autocorrect.

/** App-lifetime scope for fire-and-forget cleanup that must outlive the composable. */
object TermRelease {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
}

private val TermKeys = listOf(
    "^C" to "C-c", "^D" to "C-d", "^L" to "C-l", "^R" to "C-r", "^Z" to "C-z",
    "Esc" to "Escape", "Tab" to "Tab",
    "↑" to "Up", "↓" to "Down", "←" to "Left", "→" to "Right", "⏎" to "Enter",
)

@Composable
fun TermScreen(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(listOf<String>()) }
    var session by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var sendEnter by remember { mutableStateOf(true) }
    var showNew by remember { mutableStateOf(false) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun loadSessions() {
        runCatching {
            val rows = Api.get("/term/sessions").getJSONArray("sessions")
            sessions = (0 until rows.length()).map { rows.getJSONObject(it).getString("name") }
            if (session == null || session !in sessions)
                session = sessions.firstOrNull()
        }.onFailure { snackbar.showSnackbar("bridge unreachable: ${it.message}") }
    }

    suspend fun refresh() {
        session?.let { s ->
            runCatching {
                screen = Api.get("/term/screen2?ansi=true&session=${enc(s)}").getString("screen")
            }
        }
    }

    fun send(text: String = "", key: String = "") = scope.launch {
        val s = session ?: return@launch
        try {
            Api.post("/term/keys", JSONObject()
                .put("session", s).put("text", text).put("key", key)
                .put("enter", sendEnter))
            if (text.isNotEmpty()) input = ""
            delay(200); refresh()
        } catch (e: Exception) { snackbar.showSnackbar("send failed: ${e.message}") }
    }

    LaunchedEffect(Unit) { loadSessions() }
    LaunchedEffect(session) {
        // fit tmux to a phone-friendly width once on select
        session?.let {
            runCatching {
                Api.post("/term/resize", JSONObject().put("session", it).put("cols", 82).put("rows", 46))
            }
        }
        while (isActive) { refresh(); delay(1100) }
    }
    // The phone-fit resize puts the tmux window in MANUAL size mode, which
    // outlives the mirror and leaves the desktop clamped at 82x46. Release it
    // (resize-window -A) whenever we stop mirroring this session: on session
    // switch and on leaving the tab. Fired on an app-lifetime scope because
    // this composable's scope is already cancelled when onDispose runs.
    DisposableEffect(session) {
        val s = session
        onDispose {
            if (s != null) TermRelease.scope.launch {
                runCatching { Api.post("/term/autosize", JSONObject().put("session", s)) }
            }
        }
    }
    LaunchedEffect(screen) { vScroll.scrollTo(vScroll.maxValue) }

    if (showNew) NewSessionDialog(
        onDismiss = { showNew = false },
        onCreate = { name, cwd, cmd ->
            scope.launch {
                try {
                    Api.post("/term/new", JSONObject().put("name", name).put("cwd", cwd).put("cmd", cmd))
                    showNew = false; loadSessions(); session = name.replace(" ", "-")
                } catch (e: Exception) { snackbar.showSnackbar("create failed: ${e.message}") }
            }
        },
    )

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.forEach { name ->
                    FilterChip(
                        selected = session == name,
                        onClick = { session = name; screen = "" },
                        label = { Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber, selectedLabelColor = Ink,
                            containerColor = Surface1, labelColor = Muted),
                    )
                }
            }
            IconButton(onClick = { showNew = true }) { Icon(Icons.Filled.Add, "new", tint = Teal) }
            session?.let { s ->
                IconButton(onClick = {
                    scope.launch {
                        try {
                            Api.delete("/term/${enc(s)}")
                            session = null; screen = ""; loadSessions()
                        } catch (e: Exception) { snackbar.showSnackbar("kill failed: ${e.message}") }
                    }
                }) { Icon(Icons.Filled.Close, "kill session", tint = Bad) }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0F)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    if (screen.isEmpty()) androidx.compose.ui.text.AnnotatedString("connecting…")
                    else Ansi.render(screen, Ink),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp, lineHeight = 13.sp, softWrap = false, color = Ink,
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(vScroll).horizontalScroll(hScroll).padding(10.dp),
                )
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TermKeys.forEach { (label, key) ->
                OutlinedButton(
                    onClick = { send(key = key) },
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(label, color = Teal, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            }
            FilterChip(selected = sendEnter, onClick = { sendEnter = !sendEnter },
                label = { Text("+⏎", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber, selectedLabelColor = Ink,
                    containerColor = Surface1, labelColor = Muted))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("type here (autocorrect on)", color = Muted) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrect = true,
                    imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotEmpty()) send(text = input) }),
            )
            FilledIconButton(
                onClick = { send(text = input) }, enabled = input.isNotEmpty(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber),
            ) { Icon(Icons.Filled.Send, "send", tint = Ink) }
        }
    }
}

@Composable
private fun NewSessionDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var startCmd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("New session", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("name") }, singleLine = true)
                OutlinedTextField(cwd, { cwd = it }, label = { Text("working dir") }, singleLine = true)
                OutlinedTextField(startCmd, { startCmd = it },
                    label = { Text("command to run (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), cwd.trim(), startCmd.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}
