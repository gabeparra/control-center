# Bridge

Control Center is a thin client. Everything it shows comes from a small HTTP
server, the bridge, that runs on your own machine. This folder holds a
reference bridge in a single file, `bridge.py`.

**Only demo mode exists so far.** `--demo` serves an invented homelab (machines,
services, containers, logs, a fake terminal, a small made-up Jellyfin library) so you can
try the app without any server of your own. It never runs a command or touches
the network, and the only file it reads is the bundled test clip described
below. A real mode that talks to systemd,
Docker, tmux and so on is not written yet.

## Start it

You need [uv](https://docs.astral.sh/uv/). Nothing else is installed by hand;
uv reads the dependency list at the top of the script.

```
uv run bridge/bridge.py --demo
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--demo` | required | Serve invented data. Without it the script prints a message and exits with an error. |
| `--host` | `127.0.0.1` | Address to listen on. Use `0.0.0.0` to reach it from another device. |
| `--port` | `8790` | Port to listen on. |
| `--extensions a,b,c` | all nine | Which [extensions](#extensions) to serve, for example `--extensions github,hello` or `--extensions github,uptime`. Known ids: `github, vscode, forge, uptime, syncthing, paperless, pogo, deadlock, hello`. Unknown names are rejected. |
| `--features a,b,c` | all demo features | Override the list advertised by `/capabilities`, so you can see tabs hide. Unknown names are rejected. |

State is kept in memory. Restarting the process puts everything back.

## Point the app at it

On first launch the app shows a setup card; enter the bridge URL there. It can
be changed later in Settings.

- **Android emulator:** the emulator reaches the host at `http://10.0.2.2:8790`.
- **Phone on the same network:** start the bridge with `--host 0.0.0.0` and use
  `http://<your machine's LAN address>:8790`.
- **Phone over USB:** `adb reverse tcp:8790 tcp:8790`, then use
  `http://127.0.0.1:8790`. This also works if you would rather not listen on
  the network at all.

Plain HTTP is allowed by the app, so none of these needs HTTPS. If the bridge is
reachable from anything but a private network, put it behind HTTPS (for example
with `tailscale serve`).

**The bridge has no authentication.** Anyone who can reach the port can use it.
Run it on localhost, a private network or a tailnet, never on the open internet.

### Things worth setting in the app

Everything works with just the bridge URL, but a few panes only appear or only
show detail when a Settings field matches the demo data:

- *Bridge host machine name*: `atlas`. Shows the tmux, coding-session, service
  and AdGuard sections on Machines > Detail for that machine only.
- *Wakeable machine name*: `vault`. The demo NAS starts offline. Wake it from
  Overview or from Machines > System > Power, it comes online after about six
  seconds and goes back to sleep after five minutes. The pane refreshes on the
  next poll or when you tap refresh.
- *Files URL*: `http://<bridge>/demo/files`, where `<bridge>` is the host and
  port you entered as the bridge URL. The demo serves a placeholder page there,
  and the Files pane appears in the Media tab next to the library. Without it
  the Media tab shows only the Jellyfin library.

## What demo mode does

- Restarting, stopping or starting a service or container changes its status.
  A restart goes through "activating" for two seconds.
- Alerts are derived from state: stop a service and a critical alert appears,
  start it and it clears. Dismissing an alert keeps it in the list as
  acknowledged (the Audit tab can restore it) and drops it from Overview.
  Seeded on startup: a failed `backup-sync` service, an unhealthy `paperless`
  container, a disk warning on `nuc`. Restarting the first two fixes them.
- Vitals, GPU, power and AdGuard numbers drift a little on every request.
- Logs have current timestamps and include the start and stop lines your
  actions produced.
- Every action adds to `/events`.
- The terminal has three fake tmux sessions (`main`, `build`, `logs`) with
  ANSI colour. Typed text and keys land in a tiny canned shell (`help`, `ls`,
  `date`, `uptime`, `df`, `free`, `docker ps`, `echo`, `clear`, `cd`). Nothing
  is executed.
- The Jellyfin proxy serves an invented library (made-up titles) and generated
  placeholder posters. Every item streams the same clip,
  `demo_media/demo.mp4`: a 12 second 640x360 H.264 baseline and AAC test
  pattern (SMPTE colour bars, a running seconds counter, a sine tone) made with
  ffmpeg's own synthetic sources by `demo_media/make_demo.py`. It is about
  200 KB, and nothing is fetched from the network. The runtime shown on an
  item is invented and does not match the clip's length.

## Capabilities

`GET /capabilities` returns `{"version": 1, "demo": true, "features": [...]}`.
Feature names: `services, containers, vitals, gpu, machines, terminal,
tmux_overview, claude_sessions, wake, alerts, events, links, adguard, home,
zigbee, jellyfin, backup, usage, power_draw, updates, wan, actions,
tailnet, commands, updater, extensions`.

Demo mode advertises every one of them except `updater`. The demo serves no
APKs, so an app that trusted `updater` would only get a 404. Use `--features`
to advertise less, for example `--features services,containers,vitals`. The
endpoints keep answering either way; the flag only changes what is advertised.

## Extensions

Extensions are integrations the bridge describes and the app draws. The bridge
sends each extension's screen as JSON, a list of sections made of a few block
types, and the app has one generic renderer for them. Adding an integration
means adding code to the bridge. The app is not rebuilt.

The app shows the **Extras** tab only when `/capabilities` lists `extensions`.
A bridge with no `/capabilities` at all never gets the tab, because an older
bridge cannot have `/extensions`.

The demo serves twelve, all with invented data and in-memory state. None of them
calls a real service, runs a command or touches the network:

| Id | Shows | Actions |
|---|---|---|
| `github` | Open pull requests with CI and review chips, recent workflow runs | Merge an approved PR (asks first), re-run a failed run. A running run finishes after a few seconds. |
| `vscode` | Remote editor sessions on the demo machines, tunnel up or down | Start or stop a tunnel. Starting one on an offline machine is refused. A link opens a placeholder editor page served by the bridge. |
| `forge` | Small apps with status chips, and a build job with a progress bar and a growing log | "Describe the app" text input starts a build that runs about 13 seconds, then the app is ready and gets a link to a static preview page. Retry and delete. |
| `pogo` | Sample raid bosses and events for a mobile game, events live now and upcoming | None. Information only. |
| `deadlock` | Status of a draft-advisor overlay on a gaming PC, suggested picks with win-rate chips, last matches | Start and stop the overlay. |
| `uptime` | Uptime monitors with up or down tone, response time and a 24 hour uptime bar; one is down | Pause and resume a monitor. |
| `paperless` | Document counts, inbox with tag chips, a consume queue item that finishes after about 12 seconds | "Mark reviewed" removes an inbox item. |
| `syncthing` | Folders with state (up to date, syncing with a moving bar, out of sync) and connected devices | "Rescan" starts a sync of about 12 seconds that ends up to date. |
| `pihole` | Group Network. DNS filtering: queries and blocked counts, blocking state, top blocked domains (reserved example names), top clients, recent queries with allowed or blocked tone | "Pause 5 min" (a 20 second pause in the demo that ends by itself), Enable, Disable (asks first), and "Add to blocklist" with a text input. |
| `wireguard` | Group Network. VPN server: interface and port, peers with address, handshake age, transfer and a tone from how recent the handshake is. Keys are placeholders; no private keys or configs are shown | Enable or disable a peer, Remove (asks first), "Add peer" with a text input; the new peer waits for a first handshake and connects after about 8 seconds. |
| `raspi` | Group Raspberry Pi. The demo Pi's health and settings: model, CPU temperature, uptime, firmware state, power and throttling flags, SD card usage and wear, interface toggles (SSH, VNC, I2C, SPI, serial, camera), boot target, hostname (read only), overclock preset | Enable or disable each interface, change boot target and overclock (applied after a reboot), "Update firmware" (about 10 seconds with a progress bar), Reboot (asks first; shows "rebooting" for about 8 seconds, then uptime restarts). |
| `hello` | A counter | Greet, with a text input. The example used below. |

Honest limits: in `forge` nothing is created or built. The scaffold-an-Expo-project-and-build-it-with-a-coding-agent flow is
only described, and the log and preview page are canned text. `pogo` uses made-up dates and
a few well-known creature names; a real version would read a public community event feed. `deadlock`
numbers are invented too. The homelab apps (`uptime`, `paperless`, `syncthing`) are demo views with invented
titles, users and numbers; a real bridge would call each app's own status API and fill the same blocks.
`pihole`, `wireguard` and `raspi` are the same kind of demo view. A real bridge would call Pi-hole's own API,
the `wg` tool and the Pi's configuration tool, and would keep WireGuard private keys out of the answer.

### Endpoints

| Call | Answer |
|---|---|
| `GET /extensions` | `{"extensions": [{"id", "name", "icon", "summary", "badge", "tone", "group"?}]}`. `group` is an optional heading: the app shows one section header per group, in the order groups first appear, and items with no group come last without a header. A bridge that sends no `group` gets the plain flat list. `badge` is a number or `null`. `tone` is one of `ok, warn, bad, muted`. `icon` is one of `github, code, build, map, game, star, terminal, cloud, chart, bolt, list, info, home`; anything else draws a generic puzzle piece. |
| `GET /extensions/{id}` | `{"id", "name", "refresh_s", "sections": [{"title", "blocks": [...]}]}`. The app fetches it again every `refresh_s` seconds while the screen is open. 404 for an unknown id. |
| `POST /extensions/{id}/action` | Body `{"action", "item"?, "input"?}`. Answer `{"ok": true, "message"?, "refresh": true}`. `message` goes in a snackbar, `ok: false` means "could not do that" and is shown as a failure, and `refresh` makes the app fetch the screen again. 404 for an unknown extension or action. |

### Blocks

Every block is an object with a `type`. The app ignores block types and fields
it does not know, so a newer bridge can add some without breaking an older app.

| `type` | Fields | Drawn as |
|---|---|---|
| `stats` | `tiles: [{label, value, tone}]` | A row of tiles. |
| `list` | `items: [{id, title, subtitle, meta, tone, chips?, progress?, actions?}]` | One card per row: status dot from `tone`, `meta` small and right aligned, `chips: [{text, tone}]`, a progress bar when `progress` is 0 to 100, and small buttons for `actions`. |
| `progress` | `label, value (0 to 100), tone` | A labelled bar. |
| `text` | `text, mono?` | A paragraph, or a log style box when `mono` is true. |
| `chips` | `chips: [{text, tone}]` | A wrapping row of chips. |
| `actions` | `actions: [{id, label, style?, confirm?, input?, item?}]` | Buttons. |
| `link` | `label, url` | A card that opens `url` in the system browser. `url` is absolute `http(s)` or starts with `/` (relative to the bridge). Other schemes are refused. |

An action is `{"id", "label", "style": "primary|default|danger", "confirm": "Question?", "input": {"label", "placeholder"}, "item": "row id"}`.
With `confirm` the app asks before sending. With `input` it asks for one line of text and sends it
as `input`. An action inside a list row sends that row's `id` as `item`, unless the action names
its own.

### Action round trip

1. The user taps a button. If it has `confirm` or `input`, a dialog asks first.
2. The app posts `{"action": "merge", "item": "pr-212"}` to `/extensions/github/action`.
3. The bridge changes its state and answers `{"ok": true, "message": "Merged #212", "refresh": true}`.
4. The app shows the message and fetches `/extensions/github` again.

### Write your own extension

An extension is one class in `bridge.py`. This is the whole `hello` example:

```python
@extension
class Hello(Ext):
    id, name, icon = "hello", "Hello", "star"

    def __init__(self):
        self.count = 0

    def card(self):                      # one line and a badge for the list
        return f"Greeted {self.count} time{'' if self.count == 1 else 's'}", None, "muted"

    def sections(self):                  # the screen, as blocks
        return [section("Say hello",
                        {"type": "text", "text": f"The button was pressed {self.count} times."},
                        {"type": "actions", "actions": [
                            btn("greet", "Greet", "primary",
                                input={"label": "Your name", "placeholder": "Ada"})]})]

    def act(self, action, item, text):   # what a button does; return the snackbar text
        if action != "greet":
            raise ValueError(action)     # unknown action, answered with 404
        self.count += 1
        return f"Hello, {clean(text or '') or 'stranger'}!"
```

Steps: write the class, add its id to `DEFAULT_EXTENSIONS`, restart the bridge. The new card shows
up in the app's Extras tab. Raise `ExtError("reason")` from `act` for an action that cannot be done right
now. Helpers `row`, `chip`, `stat`, `btn` and `section` build the common blocks. In a real bridge, `sections()` would
read from the service instead of from memory. Add a case to `smoke_test.py` while you are there.

## API contract

This is every call the app makes, checked against the Kotlin sources. "Required"
means the pane misbehaves without it (an error, an empty pane, or in two cases
an aborted refresh). "Optional" means the app swallows the failure or hides the
card. All request and response bodies are JSON objects unless stated. A non-2xx
response should carry `{"detail": "..."}`; the app shows that text.

Where it says "Machines > System", that is the System pane of the Machines tab.

### Services, containers, host

| Method | Path | Used by | Request / response fields the app reads | Treated as |
|---|---|---|---|---|
| GET | `/stack` | Overview, Machines, System, widget | `services[]` of `name` (string), `active` (bool), `state` (string, `activating` shows as starting). System reads all three strictly. | Required for System |
| POST | `/service/{name}/{start,stop,restart}` | System, alert cards | No body. Response ignored (send an object). | Required for service buttons |
| GET | `/logs/{name}?lines=80` | System log dialog | `lines[]` of strings | Optional |
| GET | `/containers` | System, widget | `containers[]` of `name` (string), `running` (bool) required; `state`, `status`, `health` (`healthy`, `unhealthy` or empty), `image` optional | Optional |
| POST | `/container/{name}/{start,stop,restart}` | System, alert cards | No body. Response ignored. | Optional |
| GET | `/container/{name}/logs?lines=80` | System log dialog | `lines[]` of strings | Optional |
| GET | `/vitals` | System, widget | `cores`, `load_pct`, `mem_pct`, `mem_used_mb`, `mem_total_mb`, `disk_pct`, `disk_used_gb`, `disk_total_gb`, `disk_free_gb`: all integers, all read with a strict getter while drawing, so one missing field crashes the app. `load1` is a number, optional. | Optional, but all-or-nothing |
| GET | `/gpu` | System | `name` (string), `vram_used_mb`, `vram_total_mb`, `util_pct`, `temp_c` (integers, `vram_total_mb` must not be 0). Strict getters while drawing. | Required: System aborts its refresh without it |
| GET | `/tailnet` | System | `devices[]` of `name`, `os`, `ip` (strings), `online`, `self` (bools), `last_seen` (string or null) | Required: System aborts its refresh without it |
| GET | `/power/draw` | System | `gpu_w` (number or null), `rapl_ok` (bool), `cpu_w`, optional `ups{watts, model, load_pct, rated_w, resolution_w, on_battery}`. Any 200 shows the card, so do not answer `{}`. | Optional |
| GET | `/power/history` | System | `week` and `day`, each `{kwh, hours, avg_w, min_w, max_w}`, and `uptime_s` | Optional |
| GET | `/updates` | System | `count`, `packages[]` of `name` | Optional |
| GET | `/actions` | System | `actions[]` of `key`, `label`, `desc` (all strings, all required) | Optional |
| POST | `/action/{key}` | System | No body. Reads `output` (string). | Optional |
| GET | `/backup` | System | `configured`, `running`, `ok` (bools), `age_hours` (number or null), `snapshot_count`, `snapshot_bytes`, `dest_avail_bytes` (integers). Polled every 10 s while `running`. | Optional |
| POST | `/backup/run` | System | No body | Optional |
| GET | `/usage` | System | `sources.{claude,codex,agent}` each `{week{in,out,cache}, today{in,out}, models{name:{out}}}`. Section hides if empty. | Optional |

### Machines, power, wake

| Method | Path | Used by | Fields | Treated as |
|---|---|---|---|---|
| GET | `/machines` | Overview, Machines > Detail | `machines[]`. `name` required. Everything else optional: `role`, `online`, `load_pct`, `cores`, `mem_pct`, `mem_used_mb`, `mem_total_mb`, `disk_pct`, `disk_used_gb`, `disk_total_gb`, `temp_c` (nullable), `watts`, `on_battery`, `uptime_s`, `gpu{name, util_pct, mem_used_mb, mem_total_mb, temp_c}` (note: not `vram_*`), `kwh_day`, `kwh_week`, `kwh_month`, `avg_w_day`, `updates`, `updatable`. The AdGuard section shows on the machine set as the bridge host machine name in Settings. | Required for Overview and Detail |
| POST | `/machines/{name}/update` | Overview | No body. Response ignored. | Optional |
| POST | `/machines/{name}/sudo` | Overview | Sends `{password}`. Response ignored. The demo throws the password away. | Optional |
| GET | `/power/machines` | System > Power | `machines[]` of `name` (required), `self`, `online`, `mac`, `actions[]` (`wake`, `suspend`, `reboot`, `shutdown`) | Optional |
| POST | `/power/wake` | Overview, Power | Sends `{name}`. Response ignored. | Optional |
| POST | `/power/self` | Power | Sends `{action}`: `suspend`, `reboot`, `shutdown`. | Optional |
| GET | `/power/scan` | Power (set MAC) | `hosts[]` of `ip`, `mac` (required), `hostname` | Optional |
| POST | `/power/assign` | Power (set MAC) | Sends `{name, mac, lan_ip}` | Optional |
| GET | `/tmux/sessions` | Machines > Detail | `sessions[]` of `name` (required), `windows`, `attached` | Optional |
| GET | `/claude/sessions` | Machines > Detail | `sessions[]` of `pid` (required integer), `cwd`, `age_s`, `tmux` (string or null), `yolo` | Optional |

### Alerts, events, status

| Method | Path | Used by | Fields | Treated as |
|---|---|---|---|---|
| GET | `/alerts/active` | Overview, Audit, badge poll every 60 s | `alerts[]` of `key` (required), `title`, `text`, `critical`, `ts` (epoch seconds), `acknowledged`; top level `unacked` (integer). Keys starting `svc:` or `ctr:` get a restart button that calls the matching restart endpoint. | Optional |
| POST | `/alerts/ack` | Overview, Audit | Sends `{key, ack}`. Response ignored. | Optional |
| GET | `/events?limit=200` | Audit | `events[]` of `ts` (epoch seconds), `type`, `text`, `device`. Oldest first, the app reverses. `type` `alert` is drawn red. | Required for Audit |
| POST | `/event` | App background tasks | Sends `{device, type, text}` | Optional |
| GET | `/wan` | Overview | `up`, `drops_24h`, `drops_7d`, `outages[]` of `start` (epoch seconds), `secs` | Optional |
| GET | `/todo` | Overview | `open` (integer). Read but not shown. | Optional |
| GET | `/adguard` | Overview, Machines > Detail | `queries`, `blocked`, `blocked_pct`, `rules`, `avg_ms`, `protection`, `top_clients[]` and `top_blocked[]` of `name`, `count` | Optional |
| POST | `/adguard/protection` | Overview | Sends `{enabled}` | Optional |

### Links, home, terminal

| Method | Path | Used by | Fields | Treated as |
|---|---|---|---|---|
| GET | `/links` | Overview > Links | `groups[]` of `name`, `links[]` of `name`, `url` (required), `desc`, `live` (bool or null) | Required for Links |
| GET | `/home` | Home | `entities[]` of `label`, `entity_id`, `kind` (required), `state`, `unit`, `controllable`. `kind` `switch` gets a toggle, `cover` gets Open and Close, anything else is read-only. | Required for Home |
| POST | `/home/act` | Home | Sends `{entity_id, action}`: `on`, `off`, `open`, `close` | Optional |
| GET | `/zigbee` | Home | `permit_join` (bool), `devices[]` of `name`, `vendor`, `model`. Polled every 5 s while pairing is open. | Optional |
| POST | `/zigbee/permit` | Home | Sends `{on}` | Optional |
| GET | `/term/sessions` | Machines > Term | `sessions[]` of `name` | Required for Term |
| GET | `/term/screen2?ansi=true&session=` | Term, polled every 1.1 s | `screen` (string). Colour is read from SGR sequences only (`ESC[...m`); other escape sequences confuse the parser, so strip cursor movement before sending. | Required for Term |
| POST | `/term/keys` | Term | Sends `{session, text, key, enter}`; `key` is a tmux key name such as `Enter`, `C-c`, `Up`, `Escape`, `Tab` | Required for Term |
| POST | `/term/resize` | Term | Sends `{session, cols, rows}` | Optional |
| POST | `/term/autosize` | Term | Sends `{session}` | Optional |
| POST | `/term/new` | Term | Sends `{name, cwd, cmd}`. The app expects spaces in `name` to become `-`. | Optional |
| DELETE | `/term/{session}` | Term | None | Optional |

### Jellyfin proxy

The Library pane calls these on the bridge, not on Jellyfin, and plays video
from the stream route. A non-2xx answer shows as a "jellyfin failed" message.

| Method | Path | Fields | Treated as |
|---|---|---|---|
| GET | `/jellyfin/status` | `movies`, `series` (integers), `active[]` of `name`, `device`, `user`, `type` | Required for Library |
| GET | `/jellyfin/libraries` | `libraries[]` of `id`, `name`, `count`. Each becomes a chip. | Required for Library |
| GET | `/jellyfin/items?library=` | `items[]` (shape below) of one library. 404 for an unknown id. | Required for Library |
| GET | `/jellyfin/latest?limit=` | `items[]` of `id`, `name`, `type`, `year` (integer or absent), `series` (episodes), `overview`, `progress` (a percentage from 0 to 100, not a fraction), `played`, `runtime_min` (integer), `stream` | Required for Library |
| GET | `/jellyfin/resume?limit=` | same `items[]` | Required for Library |
| GET | `/jellyfin/search?term=` | same `items[]` | Optional |
| GET | `/jellyfin/thumb/{id}` | A PNG or JPEG poster, drawn in the grid and on the item page. A missing one leaves a flat placeholder. | Optional |
| GET, HEAD | `/jellyfin/stream/{id}` | `video/mp4` bytes with `Accept-Ranges: bytes`. A `Range: bytes=a-b` request gets `206` with `Content-Range` and `Content-Length`, an unsatisfiable one gets `416`, an unknown id gets `404`. | Required for Play |

Item JSON tells the app where the stream is in its `stream` field, a path on
the bridge (`/jellyfin/stream/{id}`) or a full URL. The player makes one
`Range: bytes=0-0` request first, so a missing stream shows an error at once,
then hands the URL to the platform `VideoView`.

### Commands, versions, updates, meta

| Method | Path | Used by | Fields | Treated as |
|---|---|---|---|---|
| GET | `/capabilities` | App handshake at start and after Settings are saved | `version` (1), `demo` (bool), `features[]` | Optional (when missing, the app assumes every feature is present) |
| POST | `/command` | Overview > Actions | Sends `{action: "notify", args: {title, text}}`. The demo queues it for `controlcenter`. | Optional |
| GET | `/commands/controlcenter` | Background worker, every 15 min | `commands[]` of `action`, `args`. The app handles `notify` and `update-apps`. The demo drains the queue on read. | Optional |
| POST | `/versions` | App after start or update | Sends `{device, apps}` | Optional |
| GET | `/phone/version.json` | Self-update check | `versionCode`, `versionName`, `url`, `notes`, `sha256`, `size`. The demo answers 404 on purpose. | Optional (feature `updater`) |
| GET | `/apps/managed` | Background worker | `apps[]` of `name`, `packageName`, `manifestUrl`, `archivePrefix`. The demo returns an empty list. | Optional |
| GET | `/archive/{key}`, `/access/pending`, `/access/devices`, POST `/access/{approve,deny,revoke}/{id}` | Screens that are not reachable from the navigation today | The demo returns empty lists | Unused |

### Direct third-party calls (not the bridge)

| Service | Calls | Notes |
|---|---|---|
| Wake relay | `POST {relayUrl}/wake/{host}` | Not emulated. The bridge's own `/power/wake` covers the demo. |
| Files | A WebView on the Files URL | The demo serves a placeholder page at `/demo/files`. |

## Next: a real mode

Only `--demo` exists. If you want to take this further, these endpoints are the
natural first ones to back with real data, in this order:

1. `/stack`, `/service/{name}/{action}`, `/logs/{name}` (systemd units from a
   configured list, `systemctl` and `journalctl`).
2. `/containers`, `/container/{name}/{action}`, `/container/{name}/logs`
   (`docker ps`, `docker start|stop|restart`, `docker logs`).
3. `/vitals` and a one-row `/machines` for the local host (read `/proc`).
4. `/term/*` and `/tmux/sessions` (`tmux list-sessions`, `capture-pane -e`,
   `send-keys`, `resize-window`, `new-session`, `kill-session`).
5. `/power/machines`, `/power/wake` (a UDP magic packet to a configured MAC).
6. `/capabilities` listing only what the real mode serves, plus `/events`,
   `/alerts/*` fed by a simple "service down, disk over 90%" check.

Endpoints such as `/gpu`, `/tailnet`, `/adguard`, `/zigbee` and `/backup` depend
on software that not every machine has, so leave them out of `features` instead
of faking them.

## Smoke test

```
uv run bridge/bridge.py --demo --port 8791 &
python3 bridge/smoke_test.py http://127.0.0.1:8791
```

The script uses only the standard library. It calls every endpoint above,
checks status codes and the JSON types the app reads, and tries a few state
changes (restart a service, dismiss an alert, wake a machine, queue a
command). It exits with 1 if anything fails. The tests change state, so restart
the demo bridge afterwards if you want the starting data back.
