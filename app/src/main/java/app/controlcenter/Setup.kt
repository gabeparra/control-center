package app.controlcenter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/* First-run card, shown instead of error snackbars while no bridge address is
 * set. It explains what a bridge is, takes the address, and points at the demo
 * bridge that ships in this repo. */
@Composable
fun SetupScreen(snackbar: SnackbarHostState, onSaveUrl: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CONNECT A BRIDGE", color = Amber, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Control Center is a client for a small HTTP service called a bridge. " +
                    "The bridge runs on your own server or computer and reports on it " +
                    "(services, containers, vitals, terminal sessions and more). " +
                    "Without one there is nothing to show.",
                    color = Ink, fontSize = 13.sp)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("bridge URL") },
                    placeholder = { Text("http://192.168.1.10:8790", color = Muted) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = url.isNotBlank(),
                    onClick = {
                        Api.baseUrl = Api.normalize(url)
                        onSaveUrl(Api.baseUrl)
                        scope.launch {
                            Caps.refresh()
                            snackbar.showSnackbar("bridge saved")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                ) { Text("Connect") }
                Text("The bridge has no login of its own, so use HTTPS or a private " +
                    "network such as a tailnet when you can. Plain http:// works too.",
                    color = Muted, fontSize = 11.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NO BRIDGE YET?", color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("You can try the app against the demo bridge in this repo's bridge/ folder. " +
                    "From the repo root run:", color = Ink, fontSize = 13.sp)
                Text("uv run bridge/bridge.py --demo", color = Ink, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                Text("then enter http://10.0.2.2:8790 above if you are on an Android emulator. " +
                    "On a real phone use your computer's LAN address with port 8790 instead.",
                    color = Muted, fontSize = 12.sp)
            }
        }
    }
}
