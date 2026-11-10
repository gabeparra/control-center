package app.controlcenter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* Capability handshake.
 *
 * A bridge may advertise what it can do at GET /capabilities:
 *   {"version": 1, "demo": true|false, "features": ["services", "containers", ...]}
 * The app asks on start and whenever Settings are saved, and hides the tabs,
 * panes and cards a bridge does not offer instead of polling endpoints that
 * will never answer.
 *
 * Known feature names: services, containers, vitals, gpu, machines, terminal,
 * tmux_overview, claude_sessions, wake, alerts, events, links, adguard, home,
 * zigbee, jellyfin, backup, usage, power_draw, updates, wan, actions,
 * tailnet, commands, updater, extensions.
 *
 * A bridge that has no /capabilities (404), answers with something that is not
 * the expected JSON, or an older bridge in general, leaves `info` null. Null
 * means "unknown", and unknown means "assume everything is there", which is
 * exactly how the app behaved before the handshake existed.
 */
object Caps {
    class Info(val version: Int, val demo: Boolean, val features: Set<String>)

    /** The last advertised capabilities; null = unknown, treat everything as present. */
    var info by mutableStateOf<Info?>(null)
        private set

    /** False until the first handshake attempt has finished, so screens do not
     *  start polling before we know what to poll. */
    var ready by mutableStateOf(false)
        private set

    /** Bumped on every re-check after the first. Screens are keyed on it so
     *  they reload against the new answer. */
    var epoch by mutableStateOf(0)
        private set

    /** True when the bridge did not advertise anything (older bridge or no handshake). */
    val legacy: Boolean get() = info == null

    val demo: Boolean get() = info?.demo == true

    fun has(feature: String): Boolean = info?.features?.contains(feature) ?: true

    fun hasAny(vararg features: String): Boolean = features.any { has(it) }

    private fun parse(j: org.json.JSONObject): Info? {
        val rows = j.optJSONArray("features") ?: return null
        val set = HashSet<String>()
        for (i in 0 until rows.length()) rows.optString(i).takeIf { it.isNotBlank() }?.let { set += it }
        return Info(j.optInt("version", 1), j.optBoolean("demo", false), set)
    }

    /** Ask the bridge what it offers. Never throws. */
    suspend fun refresh() {
        val wasReady = ready
        if (!Api.configured) {
            info = null
        } else {
            try {
                info = parse(Api.getQuick("/capabilities"))
            } catch (e: Exception) {
                // A dropped connection says nothing about what the bridge can
                // do, so keep the earlier answer. An HTTP error (the 404 of an
                // older bridge) or a body we cannot read means "no handshake".
                val network = e is java.io.IOException && e.message?.startsWith("HTTP ") != true
                if (!network) info = null
            }
        }
        ready = true
        if (wasReady) epoch++
    }

    /** Tabs and sections that only exist when at least one of these is offered. */
    val machineFeatures = arrayOf(
        "machines", "services", "containers", "vitals", "gpu", "terminal", "tmux_overview",
        "claude_sessions", "wake", "adguard", "backup", "usage", "power_draw",
        "updates", "actions", "tailnet",
    )
}
