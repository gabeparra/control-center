package app.controlcenter

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/* A small player built on the platform's VideoView (MediaPlayer underneath),
 * so it adds no library. Play and pause, a seek bar with elapsed and total
 * time, and a close button. The stream comes from the bridge, which answers
 * HTTP Range requests so seeking works.
 *
 * The view is released when the screen leaves composition, and playback is
 * paused when the app goes to the background. The caller keeps the position
 * (onPosition) so a rotation, which recreates the activity, resumes from where
 * it was.
 */

private fun clock(ms: Int): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerScreen(
    title: String,
    url: String,
    startMs: Int,
    onPosition: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = remember(url) { VideoView(ctx).apply { keepScreenOn = true } }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var posMs by remember { mutableStateOf(startMs) }
    var dragging by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(16f / 9f) }

    BackHandler(onBack = onClose)

    DisposableEffect(view) {
        view.setOnPreparedListener { mp ->
            durationMs = mp.duration
            if (mp.videoWidth > 0 && mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
            if (posMs > 0 && posMs < durationMs) view.seekTo(posMs)
            view.start()
            prepared = true
            playing = true
        }
        view.setOnCompletionListener {
            playing = false
            posMs = durationMs
        }
        // Returning true keeps the system from showing its own error dialog.
        view.setOnErrorListener { _, what, extra ->
            prepared = false
            playing = false
            error = "This item could not be played (error $what/$extra). " +
                "The stream may be missing, or the bridge may be unreachable."
            true
        }
        onDispose {
            view.setOnPreparedListener(null)
            view.setOnCompletionListener(null)
            view.setOnErrorListener(null)
            view.stopPlayback()
        }
    }

    // (Re)load whenever the view or the retry counter changes. MediaPlayer
    // retries a failing HTTP source for about 40 seconds before it reports an
    // error, so one quick request up front turns a missing stream or an
    // unreachable bridge into an immediate message instead of a long spinner.
    LaunchedEffect(view, attempt) {
        error = null
        prepared = false
        val problem = JellyfinApi.probe(url)
        if (problem != null) error = "This item could not be played: $problem."
        else view.setVideoURI(Uri.parse(url))
    }

    // Going to the background pauses; the user presses play to carry on.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && prepared) {
                posMs = view.currentPosition
                view.pause()
                playing = false
                onPosition(posMs)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(view) {
        while (true) {
            if (prepared && !dragging) {
                posMs = view.currentPosition
                playing = view.isPlaying
            }
            onPosition(posMs)
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "close player", tint = Ink) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val err = error
            if (err != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Surface1),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cannot play", color = Bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(err, color = Ink, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { attempt++ },
                                colors = ButtonDefaults.buttonColors(containerColor = Amber)) { Text("Retry") }
                            TextButton(onClick = onClose) { Text("Close", color = Ink) }
                        }
                    }
                }
            } else {
                AndroidView(factory = { view }, modifier = Modifier.aspectRatio(aspect))
                if (!prepared) CircularProgressIndicator(color = Amber, strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp))
            }
        }

        if (error == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = prepared, onClick = {
                    if (view.isPlaying) {
                        view.pause(); playing = false
                    } else {
                        if (durationMs > 0 && view.currentPosition >= durationMs - 100) view.seekTo(0)
                        view.start(); playing = true
                    }
                }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "pause" else "play", tint = Ink)
                }
                Text(clock(posMs), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Slider(
                    value = if (durationMs > 0) posMs.toFloat() / durationMs else 0f,
                    onValueChange = { dragging = true; posMs = (it * durationMs).toInt() },
                    onValueChangeFinished = { view.seekTo(posMs); dragging = false },
                    enabled = prepared && durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Surface1),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(clock(durationMs), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
