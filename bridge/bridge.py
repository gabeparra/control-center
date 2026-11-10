#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "fastapi>=0.110",
#   "uvicorn>=0.29",
# ]
# ///
"""Reference bridge for the Control Center Android app, demo mode only.

    uv run bridge/bridge.py --demo

Everything this file serves is invented. Nothing here runs a command, reads a
file from the host (apart from the bundled test clip in demo_media/), or opens
a network connection. State lives in memory and resets when the process stops.

The bridge has no authentication. Run it on localhost, a private network or a
tailnet, never on the open internet.
"""
from __future__ import annotations

import argparse
import hashlib
import random
import struct
import sys
import time
import zlib
from datetime import date
from pathlib import Path
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, Response

# Feature names the app understands. /capabilities may only list these.
ALL_FEATURES = [
    "services", "containers", "vitals", "gpu", "machines", "terminal",
    "tmux_overview", "claude_sessions", "wake", "alerts", "events", "links",
    "adguard", "home", "zigbee", "jellyfin", "backup", "usage",
    "power_draw", "updates", "wan", "actions", "tailnet", "commands", "updater",
    "extensions",
]
# Everything the demo serves with data, except "updater": a demo server must
# not offer APKs, so the app never tries to update itself from it.
DEFAULT_FEATURES = [f for f in ALL_FEATURES if f != "updater"]

HOST = "atlas"          # the machine this fake bridge pretends to run on
START = time.time()

app = FastAPI(title="Control Center demo bridge", docs_url=None, redoc_url=None)
FEATURES: list[str] = list(DEFAULT_FEATURES)


def now() -> float:
    return time.time()


def fail(code: int, msg: str):
    raise HTTPException(code, msg)


async def body_of(request: Request) -> dict:
    """JSON body as a dict; an empty or malformed body counts as {}."""
    try:
        data = await request.json()
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


# ---------------------------------------------------------------- wobble ----
_walk: dict[str, float] = {}


def walk(key: str, lo: float, hi: float, step: float) -> float:
    """A bounded random walk, so repeated reads drift instead of jumping."""
    v = _walk.get(key, (lo + hi) / 2)
    v = min(hi, max(lo, v + random.uniform(-step, step)))
    _walk[key] = v
    return v


# ----------------------------------------------------------------- events ---
EVENTS: list[dict] = []


def add_event(etype: str, text: str, device: str = HOST, ts: float | None = None):
    EVENTS.append({"ts": ts if ts is not None else now(), "type": etype,
                   "text": text, "device": device})
    del EVENTS[:-500]


def _seed_events():
    t = START
    seed = [
        (-86000, "action", "nightly backup started", HOST),
        (-82000, "action", "nightly backup finished, 1 snapshot added", HOST),
        (-60000, "wake", "vault woke for scheduled sync", "vault"),
        (-52000, "action", "vault went back to sleep", "vault"),
        (-30000, "install-result", "pkg=app.controlcenter status=0 installed", "controlcenter"),
        (-9000, "alert", "nuc: / is 88% full", "nuc"),
        (-3600, "action", "container wiki restarted", HOST),
        (-1500, "alert", "paperless is unhealthy", HOST),
        (-1400, "alert", "backup-sync is down", HOST),
    ]
    for dt, typ, text, dev in seed:
        add_event(typ, text, dev, t + dt)


# --------------------------------------------------------------- services ---
def _svc(name, desc, active=True, state="active"):
    return {"name": name, "desc": desc, "active": active, "state": state,
            "until": 0.0, "since": START - random.randint(86400, 86400 * 9),
            "pid": random.randint(600, 4000)}


SERVICES = [
    _svc("nginx", "reverse proxy"),
    _svc("postgresql", "database"),
    _svc("grafana-server", "dashboards"),
    _svc("prometheus", "metrics"),
    _svc("redis", "cache"),
    _svc("sshd", "remote shell"),
    _svc("backup-sync", "offsite sync job", active=False, state="failed"),
]
SERVICE_MAP = {s["name"]: s for s in SERVICES}

# Extra journal lines written by actions, merged into generated history.
LOG_EXTRA: dict[str, list[tuple[float, str]]] = {}

SERVICE_LINES = {
    "nginx": [
        '192.0.2.{a} - - "GET /{p} HTTP/2.0" 200 {n} "-" "Mozilla/5.0"',
        '192.0.2.{a} - - "GET /{p} HTTP/2.0" 304 0 "-" "Mozilla/5.0"',
        '198.51.100.{a} - - "POST /api/{p} HTTP/1.1" 201 {n} "-" "curl/8.5"',
        "worker process {n} exited with code 0",
    ],
    "postgresql": [
        "checkpoint starting: time",
        "checkpoint complete: wrote {n} buffers (0.{a}%); 0 WAL file(s) added",
        "connection received: host=127.0.0.1 port=5{n}",
        "automatic vacuum of table \"app.public.sessions\": index scans: 1",
    ],
    "grafana-server": [
        "logger=cleanup msg=\"Completed cleanup jobs\" duration={n}ms",
        "logger=context userId=1 orgId=1 uname=admin t=GET path=/api/ds/query status=200",
        "logger=plugins.update.checker msg=\"Update check succeeded\"",
    ],
    "prometheus": [
        "msg=\"Head GC completed\" component=tsdb duration={n}ms",
        "msg=\"Compaction completed\" component=tsdb blocks=1",
        "msg=\"WAL checkpoint complete\" first=1{n} last=1{a}",
    ],
    "redis": [
        "{n} changes in 300 seconds. Saving...",
        "Background saving started by pid {n}",
        "DB saved on disk",
    ],
    "sshd": [
        "Accepted publickey for demo from 192.0.2.{a} port {n} ssh2",
        "pam_unix(sshd:session): session opened for user demo",
        "Received disconnect from 192.0.2.{a} port {n}:11: disconnected by user",
    ],
    "backup-sync": [
        "starting sync to vault:/srv/backup",
        "rsync: connection unexpected closed (0 bytes received so far)",
        "sync failed with exit status 12",
    ],
}
PATHS = ["index.html", "static/app.css", "api/status", "dashboard", "favicon.ico",
         "metrics", "login", "static/app.js"]


def _rng(name: str) -> random.Random:
    return random.Random(f"{name}:{int(now() // 30)}")


def _fill(tpl: str, rng: random.Random) -> str:
    return tpl.format(a=rng.randint(2, 250), n=rng.randint(100, 9999), p=rng.choice(PATHS))


def syslog_ts(t: float) -> str:
    return time.strftime("%b %d %H:%M:%S", time.localtime(t))


def iso_ts(t: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(t)) + f".{int((t % 1) * 1000):03d}Z"


def service_logs(name: str, n: int) -> list[str]:
    s = SERVICE_MAP[name]
    rng = _rng(name)
    tpls = SERVICE_LINES.get(name, ["running"])
    t = now() - rng.randint(1, 20)
    rows: list[tuple[float, str]] = []
    for _ in range(n):
        rows.append((t, f"{syslog_ts(t)} {HOST} {name}[{s['pid']}]: {_fill(rng.choice(tpls), rng)}"))
        t -= rng.uniform(2, 90)
    rows += LOG_EXTRA.get(name, [])
    rows.sort(key=lambda r: r[0])
    return [r[1] for r in rows[-n:]]


def log_service(name: str, msg: str):
    LOG_EXTRA.setdefault(name, []).append(
        (now(), f"{syslog_ts(now())} {HOST} systemd[1]: {msg}"))
    del LOG_EXTRA[name][:-60]


def svc_view(s: dict) -> dict:
    return {"name": s["name"], "active": s["active"], "state": s["state"]}


# ------------------------------------------------------------- containers ---
def _ctr(name, image, health="", days=4):
    return {"name": name, "image": image, "running": True, "state": "running",
            "health": health, "started": START - 86400 * days - random.randint(0, 40000),
            "stopped": 0.0, "until": 0.0, "target_health": health}


CONTAINERS = [
    _ctr("jellyfin", "jellyfin/jellyfin:10.9", "healthy", 6),
    _ctr("photos", "photoprism/photoprism:latest", "healthy", 3),
    _ctr("wiki", "requarks/wiki:2", "", 3),
    _ctr("recipes", "ghcr.io/mealie-recipes/mealie:latest", "", 3),
    _ctr("gitea", "gitea/gitea:1.22", "healthy", 3),
    _ctr("homeassistant", "ghcr.io/home-assistant/home-assistant:stable", "healthy", 9),
    _ctr("mosquitto", "eclipse-mosquitto:2", "", 9),
    _ctr("zigbee2mqtt", "koenkk/zigbee2mqtt:latest", "", 9),
    _ctr("paperless", "ghcr.io/paperless-ngx/paperless-ngx:latest", "unhealthy", 2),
    _ctr("uptime-kuma", "louislam/uptime-kuma:1", "healthy", 12),
]
CTR_MAP = {c["name"]: c for c in CONTAINERS}
CTR_HEALTHY_TARGET = {"paperless": "healthy"}   # restart fixes the unhealthy one

CTR_LINES = {
    "jellyfin": [
        "[INF] Sending ping to client {a}",
        "[INF] WebSocket connection from 192.0.2.{a} closed",
        "[INF] Playback stopped reported by app Jellyfin Web 10.9.{a} playing \"Quiet Harbor\"",
        "[INF] Scheduled task \"Scan Media Library\" completed",
    ],
    "photos": [
        "INFO indexing: found {n} new files in originals",
        "INFO thumbnails: created {a} previews",
        "INFO places: updated {a} locations",
    ],
    "wiki": [
        "info: Page {n} rendered in {a} ms",
        "info: Search index updated",
        "info: Scheduled backup completed",
    ],
    "recipes": [
        "INFO: GET /api/recipes?page=1 200",
        "INFO: Importing recipe batch of {a}",
        "INFO: Meal plan for next week saved",
    ],
    "gitea": [
        "Started GET /explore/repos for 192.0.2.{a}",
        "Completed GET /api/v1/repos/search 200 OK in {a}ms",
        "Mirror sync finished for demo/notes",
    ],
    "homeassistant": [
        "INFO (MainThread) [homeassistant.components.mqtt] Subscribing to zigbee2mqtt/#",
        "INFO (MainThread) [homeassistant.components.automation] Automation 'Porch light at sunset' triggered",
        "WARNING (MainThread) [homeassistant.helpers.entity] Update of sensor.hall_temp is taking over 10 seconds",
    ],
    "mosquitto": [
        "New connection from 172.18.0.{a}:{n} on port 1883.",
        "Client zigbee2mqtt closed its connection.",
        "Saving in-memory database to /mosquitto/data/mosquitto.db.",
    ],
    "zigbee2mqtt": [
        "Zigbee2MQTT:info  MQTT publish: topic 'zigbee2mqtt/hall lamp', payload '{\"state\":\"ON\"}'",
        "Zigbee2MQTT:info  Successfully interviewed 'desk plug'",
        "Zigbee2MQTT:debug Received Zigbee message from 'hall sensor'",
    ],
    "paperless": [
        "[INFO] [paperless.consumer] Consuming scan-{n}.pdf",
        "[ERROR] [paperless.tasks] Redis connection timed out, retrying",
        "[WARNING] [paperless.management] healthcheck: web server not responding",
    ],
    "uptime-kuma": [
        "[MONITOR] INFO: Heartbeat of monitor {a} is up",
        "[MONITOR] INFO: Try to get Cert Info for https://example.com",
        "[MONITOR] INFO: Monitor #{a} 'router': Successful Response: {a} ms",
    ],
}
CTR_EXTRA: dict[str, list[tuple[float, str]]] = {}


def container_logs(name: str, n: int) -> list[str]:
    rng = _rng("c" + name)
    tpls = CTR_LINES.get(name, ["running"])
    t = now() - rng.randint(1, 20)
    rows = []
    for _ in range(n):
        rows.append((t, f"{iso_ts(t)} {_fill(rng.choice(tpls), rng)}"))
        t -= rng.uniform(2, 120)
    rows += CTR_EXTRA.get(name, [])
    rows.sort(key=lambda r: r[0])
    return [r[1] for r in rows[-n:]]


def human_age(sec: float) -> str:
    sec = max(0, int(sec))
    if sec < 60:
        return "Less than a minute" if sec < 5 else f"{sec} seconds"
    if sec < 3600:
        return f"{sec // 60} minutes"
    if sec < 86400:
        return f"{sec // 3600} hours"
    return f"{sec // 86400} days"


def ctr_view(c: dict) -> dict:
    if c["running"]:
        status = "Up " + human_age(now() - c["started"])
    else:
        status = "Exited (0) " + human_age(now() - c["stopped"]) + " ago"
    return {"name": c["name"], "running": c["running"], "state": c["state"],
            "status": status, "health": c["health"], "image": c["image"]}


# ---------------------------------------------------------------- alerts ----
ALERTS: dict[str, dict] = {}
STATIC_ALERTS = {
    "disk:nuc": {"title": "nuc: / is 88% full", "text": "Root filesystem on nuc is over the 85% warning line.",
                 "critical": False},
}


def desired_alerts() -> dict[str, dict]:
    want = {}
    for s in SERVICES:
        if not s["active"] and s["state"] != "activating":
            want[f"svc:{s['name']}"] = {
                "title": f"{s['name']} is down",
                "text": f"{s['name']}.service is {s['state']} on {HOST}.", "critical": True}
    for c in CONTAINERS:
        if not c["running"]:
            want[f"ctr:{c['name']}"] = {
                "title": f"{c['name']} is not running",
                "text": f"Container {c['name']} has exited.", "critical": True}
        elif c["health"] == "unhealthy":
            want[f"ctr:{c['name']}"] = {
                "title": f"{c['name']} is unhealthy",
                "text": f"Container {c['name']} is failing its health check.", "critical": False}
    return want


def reconcile_alerts(quiet: bool = False):
    want = desired_alerts()
    for k in list(ALERTS):
        if k not in want and k not in STATIC_ALERTS:
            del ALERTS[k]           # cleared, so a later recurrence notifies again
    for k, v in list(want.items()) + list(STATIC_ALERTS.items()):
        if k not in ALERTS:
            ALERTS[k] = dict(v, key=k, ts=now(), acknowledged=False)
            if not quiet:
                add_event("alert", v["title"], k.split(":", 1)[1] if k.startswith("disk:") else HOST)


# --------------------------------------------------------------- machines ---
BOOT = {"atlas": START - 12 * 86400, "nuc": START - 31 * 86400, "pi": START - 64 * 86400}
MACHINE_DEFS = {
    "atlas": dict(role="main server", cores=8, mem_total=32768, disk_total=1800, disk_base=1021,
                  watts=(110, 190), temp=(44, 61), updates=5, updatable=True,
                  kwh=(3.1, 22.4, 96.0), avg=128, mac=""),
    "nuc": dict(role="utility box", cores=4, mem_total=16384, disk_total=476, disk_base=419,
                watts=(10, 22), temp=(40, 55), updates=3, updatable=True,
                kwh=(0.3, 2.1, 9.2), avg=13, mac="02:00:5e:00:53:21"),
    "pi": dict(role="DNS and monitoring", cores=4, mem_total=4096, disk_total=58, disk_base=17,
               watts=(3, 6), temp=(42, 54), updates=0, updatable=True,
               kwh=(0.1, 0.7, 3.1), avg=4, mac="02:00:5e:00:53:22"),
    "vault": dict(role="backup NAS", cores=2, mem_total=8192, disk_total=7400, disk_base=3150,
                  watts=(18, 30), temp=(34, 46), updates=1, updatable=True,
                  kwh=(0.4, 2.8, 12.0), avg=19, mac="02:00:5e:00:53:af"),
}
MACHINE_STATE = {
    "vault": {"awake_at": 0.0, "asleep_at": 0.0},      # offline until woken
}
VAULT_AWAKE_SECONDS = 300
MACHINE_UPDATES = {k: v["updates"] for k, v in MACHINE_DEFS.items()}


def is_online(name: str) -> bool:
    if name != "vault":
        return True
    st = MACHINE_STATE["vault"]
    return st["awake_at"] > 0 and now() >= st["awake_at"] and now() < st["asleep_at"]


def is_waking(name: str) -> bool:
    st = MACHINE_STATE.get(name)
    return bool(st) and st["awake_at"] > now()


def host_metrics(name: str) -> dict:
    d = MACHINE_DEFS[name]
    load = walk(f"{name}.load", 8, 48, 6) if name == "atlas" else walk(f"{name}.load", 2, 22, 3)
    mem_pct = walk(f"{name}.mem", 38, 62, 1.5) if name == "atlas" else walk(f"{name}.mem", 25, 55, 1.5)
    mem_used = int(d["mem_total"] * mem_pct / 100)
    disk_used = int(d["disk_base"] + (now() - START) / 86400 * 0.5)
    return {
        "load_pct": round(load, 1),
        "cores": d["cores"],
        "mem_pct": round(mem_pct, 1),
        "mem_used_mb": mem_used,
        "mem_total_mb": d["mem_total"],
        "disk_pct": round(disk_used / d["disk_total"] * 100, 1),
        "disk_used_gb": disk_used,
        "disk_total_gb": d["disk_total"],
    }


def gpu_view() -> dict:
    util = int(walk("gpu.util", 2, 35, 6))
    return {
        "name": "NVIDIA GeForce RTX 3060",
        "util_pct": util,
        "vram_used_mb": int(walk("gpu.vram", 900, 3400, 150)),
        "vram_total_mb": 12288,
        "temp_c": int(walk("gpu.temp", 38, 58, 2)),
    }


def machine_view(name: str) -> dict:
    d = MACHINE_DEFS[name]
    online = is_online(name)
    out: dict = {"name": name, "role": d["role"], "online": online}
    if not online:
        out.update(on_battery=False, updatable=d["updatable"], waking=is_waking(name))
        return out
    out.update(host_metrics(name))
    out["temp_c"] = round(walk(f"{name}.temp", *d["temp"], 1.0), 1)
    out["watts"] = int(walk(f"{name}.watts", *d["watts"], 5))
    out["on_battery"] = False
    boot = BOOT.get(name, MACHINE_STATE.get(name, {}).get("awake_at", START))
    out["uptime_s"] = int(now() - boot)
    out["kwh_day"], out["kwh_week"], out["kwh_month"] = d["kwh"]
    out["avg_w_day"] = d["avg"]
    out["updates"] = MACHINE_UPDATES[name]
    out["updatable"] = d["updatable"]
    if name == "atlas":
        g = gpu_view()     # /machines names the memory fields differently from /gpu
        out["gpu"] = {"name": g["name"], "util_pct": g["util_pct"], "mem_used_mb": g["vram_used_mb"],
                      "mem_total_mb": g["vram_total_mb"], "temp_c": g["temp_c"]}
    return out


# ------------------------------------------------------------------ power ---
SCAN_HOSTS = [
    {"ip": "192.0.2.31", "mac": "02:00:5e:00:53:31", "hostname": "printer"},
    {"ip": "192.0.2.44", "mac": "02:00:5e:00:53:44", "hostname": "living-room-tv"},
    {"ip": "192.0.2.57", "mac": "02:00:5e:00:53:57", "hostname": ""},
]


# ------------------------------------------------------------ backup/etc ----
BACKUP = {"running": False, "ok": True, "last": START - 9.5 * 3600, "count": 41,
          "bytes": 18_400_000_000, "end": 0.0}
ZIGBEE = {"permit": False, "until": 0.0}
ZIGBEE_DEVICES = [
    {"name": "hall lamp", "vendor": "Acme", "model": "A19 white bulb"},
    {"name": "desk plug", "vendor": "Acme", "model": "Smart plug S1"},
    {"name": "hall sensor", "vendor": "Example", "model": "Temp/humidity T2"},
]
ADGUARD = {"queries": 48210, "blocked": 11874, "protection": True}
OUTAGES = [{"start": START - 3 * 86400 - 7000, "secs": 240},
           {"start": START - 19 * 3600, "secs": 95}]

HOME = [
    {"label": "Living room lamp", "entity_id": "switch.living_room_lamp", "kind": "switch",
     "state": "on", "unit": "", "controllable": True},
    {"label": "Desk fan", "entity_id": "switch.desk_fan", "kind": "switch",
     "state": "off", "unit": "", "controllable": True},
    {"label": "Porch light", "entity_id": "switch.porch_light", "kind": "switch",
     "state": "on", "unit": "", "controllable": True},
    {"label": "Garage door", "entity_id": "cover.garage_door", "kind": "cover",
     "state": "closed", "unit": "", "controllable": True, "until": 0.0, "target": "closed"},
    {"label": "Hall temperature", "entity_id": "sensor.hall_temperature", "kind": "sensor",
     "state": "21.4", "unit": "C", "controllable": False},
    {"label": "Hall humidity", "entity_id": "sensor.hall_humidity", "kind": "sensor",
     "state": "46", "unit": "%", "controllable": False},
    {"label": "Front door", "entity_id": "binary_sensor.front_door", "kind": "binary_sensor",
     "state": "closed", "unit": "", "controllable": False},
]
HOME_MAP = {e["entity_id"]: e for e in HOME}

COMMANDS: list[dict] = []

LINKS = [
    ("Media", [
        ("Jellyfin", "https://example.com/demo/jellyfin", "movies and shows", "ctr:jellyfin"),
    ]),
    ("Self-hosted", [
        ("Photos", "https://example.com/demo/photos", "photo library", "ctr:photos"),
        ("Wiki", "https://example.com/demo/wiki", "notes and docs", "ctr:wiki"),
        ("Recipes", "https://example.com/demo/recipes", "recipe manager", "ctr:recipes"),
        ("Git", "https://example.com/demo/git", "git server", "ctr:gitea"),
    ]),
    ("Monitoring", [
        ("Grafana", "https://example.com/demo/grafana", "dashboards", "svc:grafana-server"),
        ("Uptime Kuma", "https://example.com/demo/kuma", "status checks", "ctr:uptime-kuma"),
    ]),
    ("Home", [
        ("Home Assistant", "https://example.com/demo/ha", "automation", "ctr:homeassistant"),
        ("Router", "https://example.com/demo/router", "admin page", None),
        ("Printer", "https://example.com/demo/printer", "does not answer", "dead"),
    ]),
]

QUICK_ACTIONS = [
    {"key": "disk-usage", "label": "Disk usage", "desc": "Show filesystem usage"},
    {"key": "docker-prune", "label": "Prune images", "desc": "Remove dangling container images"},
    {"key": "flush-dns", "label": "Flush DNS", "desc": "Clear the resolver cache"},
    {"key": "check-updates", "label": "Check updates", "desc": "List pending package updates"},
]
PACKAGES = ["kernel", "openssl", "curl", "systemd", "nginx"]


# --------------------------------------------------------------- terminal ---
def _p(cwd="~"):
    return f"\x1b[1;32mdemo@{HOST}\x1b[0m:\x1b[1;34m{cwd}\x1b[0m$ "


def _new_session(name: str, lines: list[str], windows=1, attached=False, cwd="~") -> dict:
    return {"name": name, "lines": lines, "input": "", "history": [], "cwd": cwd,
            "windows": windows, "attached": attached}


def _seed_terms() -> dict[str, dict]:
    main = [
        _p("~/projects/webapp") + "git status",
        "On branch \x1b[32mmain\x1b[0m",
        "Your branch is up to date with 'origin/main'.",
        "",
        "Changes not staged for commit:",
        "\t\x1b[31mmodified:   src/routes.py\x1b[0m",
        "\t\x1b[31mmodified:   README.md\x1b[0m",
        "",
        _p("~/projects/webapp") + "ls",
        "\x1b[1;34msrc\x1b[0m  \x1b[1;34mtests\x1b[0m  README.md  pyproject.toml  \x1b[1;32mdeploy.sh\x1b[0m",
    ]
    build = [
        "\x1b[1;32m   Compiling\x1b[0m serde v1.0.203",
        "\x1b[1;32m   Compiling\x1b[0m tokio v1.38.0",
        "\x1b[1;32m   Compiling\x1b[0m webapp v0.4.2 (/home/demo/projects/webapp)",
        "\x1b[1;33mwarning\x1b[0m: unused variable: `retries`",
        "  \x1b[1;34m-->\x1b[0m src/client.rs:42:9",
        "\x1b[1;32m    Finished\x1b[0m dev [unoptimized + debuginfo] target(s) in 12.84s",
        "\x1b[1;32m     Running\x1b[0m `target/debug/webapp`",
        "listening on 127.0.0.1:3000",
    ]
    logs = [
        _p("/var/log") + "tail -f nginx/access.log",
    ] + [
        f'192.0.2.{random.randint(2, 250)} - - "GET /{random.choice(PATHS)} HTTP/2.0" 200 {random.randint(200, 9000)}'
        for _ in range(10)
    ]
    return {
        "main": _new_session("main", main + [""], windows=2, attached=True, cwd="~/projects/webapp"),
        "build": _new_session("build", build + [""], cwd="~/projects/webapp"),
        "logs": _new_session("logs", logs + [""], cwd="/var/log"),
    }


TERMS: dict[str, dict] = {}
ROWS = 46


def clean(s: str) -> str:
    return "".join(ch for ch in s if ch >= " " and ch != "\x7f")


def run_command(sess: dict, line: str) -> list[str]:
    parts = line.strip().split()
    if not parts:
        return []
    cmd, args = parts[0], parts[1:]
    if cmd == "help":
        return ["Demo shell. Nothing here runs on a real machine.",
                "Try: ls, pwd, whoami, hostname, date, uptime, df, free, docker ps, echo hi, clear"]
    if cmd == "ls":
        return ["\x1b[1;34mprojects\x1b[0m  \x1b[1;34mmedia\x1b[0m  \x1b[1;34mbackups\x1b[0m  notes.txt  \x1b[1;32mdeploy.sh\x1b[0m"]
    if cmd == "pwd":
        return [sess["cwd"].replace("~", "/home/demo", 1)]
    if cmd == "whoami":
        return ["demo"]
    if cmd == "hostname":
        return [HOST]
    if cmd == "date":
        return [time.strftime("%a %b %d %H:%M:%S %Z %Y")]
    if cmd == "uptime":
        up = int(now() - BOOT["atlas"])
        m = host_metrics("atlas")
        return [f" {time.strftime('%H:%M:%S')} up {up // 86400} days, {up % 86400 // 3600}:{up % 3600 // 60:02d}, "
                f"1 user, load average: {m['load_pct'] / 100 * m['cores']:.2f}"]
    if cmd == "echo":
        return [" ".join(args).strip("\"'")]
    if cmd == "clear":
        sess["lines"] = []
        return []
    if cmd == "cd":
        target = args[0] if args else "~"
        if target == "..":
            target = "~" if sess["cwd"].count("/") <= 1 else sess["cwd"].rsplit("/", 1)[0]
        elif not target.startswith(("/", "~")):
            target = sess["cwd"].rstrip("/") + "/" + target
        sess["cwd"] = target
        return []
    if cmd == "df":
        m = host_metrics("atlas")
        return ["Filesystem      Size  Used Avail Use% Mounted on",
                f"/dev/nvme0n1p2  {m['disk_total_gb']}G  {m['disk_used_gb']}G  "
                f"{m['disk_total_gb'] - m['disk_used_gb']}G  {int(m['disk_pct'])}% /"]
    if cmd == "free":
        m = host_metrics("atlas")
        return ["               total        used        free",
                f"Mem:       {m['mem_total_mb']:>9}   {m['mem_used_mb']:>9}   "
                f"{m['mem_total_mb'] - m['mem_used_mb']:>9}"]
    if cmd == "docker" and args[:1] == ["ps"]:
        rows = ["\x1b[1mNAME            STATUS\x1b[0m"]
        for c in CONTAINERS:
            v = ctr_view(c)
            color = "\x1b[32m" if c["running"] else "\x1b[31m"
            rows.append(f"{c['name']:<15} {color}{v['status']}\x1b[0m")
        return rows
    if cmd == "exit":
        return ["This is a demo shell, there is nothing to exit."]
    return [f"\x1b[31mbash: {cmd}: command not found\x1b[0m"]


def submit_line(sess: dict):
    line = sess["input"]
    sess["lines"].append(_p(sess["cwd"]) + line)
    sess["input"] = ""
    if line.strip():
        sess["history"].append(line)
        sess["lines"].extend(run_command(sess, line))
    del sess["lines"][:-300]


def press_key(sess: dict, key: str):
    if key == "Enter":
        submit_line(sess)
    elif key == "C-c":
        sess["lines"].append(_p(sess["cwd"]) + sess["input"] + "^C")
        sess["input"] = ""
    elif key == "C-l":
        sess["lines"] = []
    elif key == "C-d":
        if not sess["input"]:
            sess["lines"].append("logout (demo shell stays open)")
    elif key in ("BSpace", "BackSpace"):
        sess["input"] = sess["input"][:-1]
    elif key == "Up":
        if sess["history"]:
            sess["input"] = sess["history"][-1]
    elif key == "C-r" or key == "C-z":
        sess["lines"].append(f"(demo) {key} is not wired in the demo shell")
    # Escape, Tab, Down, Left, Right: accepted and ignored


def render_screen(sess: dict) -> str:
    body = sess["lines"] + [_p(sess["cwd"]) + sess["input"]]
    return "\n".join(body[-ROWS:])


# -------------------------------------------------------------- jellyfin ----
def _jid(title: str) -> str:
    return hashlib.md5(title.encode()).hexdigest()


MOVIES = [
    ("Paper Lanterns", 2019, "Two cousins spend one night restoring an abandoned lantern festival."),
    ("The Long Salt Road", 2021, "A salt trader and a cartographer disagree about where the road ends."),
    ("Quiet Harbor", 2016, "A retired ferry captain takes one last crossing in bad weather."),
    ("Orbit Seven", 2023, "The crew of a small relay station loses contact with home."),
    ("A Map of Small Hours", 2018, "Short stories about people awake between two and four in the morning."),
    ("Copper Sky", 2022, "A weather forecaster in a mining town predicts the one storm nobody wants."),
    ("The Understudy", 2014, "A stage understudy gets one night in the lead."),
    ("Northbound", 2020, "Three strangers share a ride that keeps getting longer."),
]
SHOWS = {
    "Night Shift Radio": (2020, ["Dead Air", "Request Line", "The Caller", "Static", "Sign Off"]),
    "Lowland": (2022, ["Floodplain", "Tide Tables", "The Dyke", "High Ground"]),
    "The Archivists": (2018, ["Box 14", "Cross Reference", "Missing Folio", "Closing Time"]),
}
JF_ITEMS: dict[str, dict] = {}
# Every item plays this one generated test pattern (see demo_media/README.md).
DEMO_CLIP = Path(__file__).resolve().parent / "demo_media" / "demo.mp4"
JF_LIBRARIES = [("movies", "Movies"), ("shows", "Shows")]


def _build_library():
    for title, year, overview in MOVIES:
        i = _jid(title)
        JF_ITEMS[i] = {"id": i, "name": title, "type": "Movie", "year": year, "series": None,
                       "overview": overview, "progress": 0.0, "played": False,
                       "library": "movies", "runtime_min": 82 + int(i[:2], 16) % 40}
    for show, (year, eps) in SHOWS.items():
        for n, ep in enumerate(eps, 1):
            key = f"{show} S01E{n:02d}"
            i = _jid(key)
            JF_ITEMS[i] = {"id": i, "name": ep, "type": "Episode", "year": year, "series": show,
                           "overview": f"Season 1, episode {n} of {show}.",
                           "progress": 0.0, "played": n == 1,
                           "library": "shows", "runtime_min": 24 + int(i[:2], 16) % 25}
    # a few half-watched items for "continue watching"
    for title, pct in (("Quiet Harbor", 34.0), ("Orbit Seven", 71.5)):
        JF_ITEMS[_jid(title)]["progress"] = pct
    JF_ITEMS[_jid("Lowland S01E02")]["progress"] = 12.0


def jf_out(it: dict) -> dict:
    out = {k: it[k] for k in ("id", "name", "type", "year", "overview", "progress", "played",
                              "runtime_min")}
    out["stream"] = f"/jellyfin/stream/{it['id']}"
    if it["series"]:
        out["series"] = it["series"]
    return out


def make_png(seed: str, w: int = 200, h: int = 300) -> bytes:
    """A small gradient PNG. Colour comes from the seed, nothing is read from disk."""
    d = hashlib.md5(seed.encode()).digest()
    top = (40 + d[0] % 120, 40 + d[1] % 120, 60 + d[2] % 140)
    bot = (20 + d[3] % 60, 20 + d[4] % 60, 30 + d[5] % 80)
    rows = bytearray()
    for y in range(h):
        f = y / (h - 1)
        c = bytes(int(top[i] * (1 - f) + bot[i] * f) for i in range(3))
        row = bytearray(c * w)
        if y < 3 or y >= h - 3:
            row = bytearray(b"\xc8\xc8\xc8" * w)
        else:
            row[0:9] = b"\xc8\xc8\xc8" * 3
            row[-9:] = b"\xc8\xc8\xc8" * 3
        rows += b"\x00" + bytes(row)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
            + chunk(b"IEND", b""))


# ------------------------------------------------------------------ tick ----
def tick():
    t = now()
    for s in SERVICES:
        if s["until"] and t >= s["until"]:
            s.update(active=True, state="active", until=0.0, since=t)
    for c in CONTAINERS:
        if c["until"] and t >= c["until"]:
            c.update(until=0.0, health=c["target_health"])
    if BACKUP["running"] and t >= BACKUP["end"]:
        BACKUP.update(running=False, ok=True, last=t, count=BACKUP["count"] + 1,
                      bytes=int(18e9 + random.random() * 1e9))
        add_event("action", "backup finished, 1 snapshot added", HOST)
    if ZIGBEE["permit"] and t >= ZIGBEE["until"]:
        ZIGBEE["permit"] = False
    st = MACHINE_STATE["vault"]
    if st["awake_at"] and t >= st["asleep_at"]:
        st.update(awake_at=0.0, asleep_at=0.0)
        add_event("action", "vault went back to sleep", "vault")
    for e in HOME:
        if e.get("until") and t >= e["until"]:
            e["state"] = e["target"]
            e["until"] = 0.0
    reconcile_alerts()


@app.middleware("http")
async def tick_middleware(request: Request, call_next):
    tick()
    return await call_next(request)


# ================================================================ routes ====
@app.get("/")
async def root():
    return {"name": "control-center demo bridge", "demo": True,
            "hint": "point the app's bridge URL here; see /capabilities"}


@app.get("/capabilities")
async def capabilities():
    return {"version": 1, "demo": True, "features": FEATURES}


# --- stack: services -------------------------------------------------------
@app.get("/stack")
async def stack():
    return {"services": [svc_view(s) for s in SERVICES]}


@app.post("/service/{name}/{action}")
async def service_action(name: str, action: str):
    s = SERVICE_MAP.get(name)
    if s is None:
        fail(404, f"unknown service: {name}")
    if action not in ("start", "stop", "restart"):
        fail(400, f"unsupported action: {action}")
    if action == "stop":
        s.update(active=False, state="inactive", until=0.0)
        log_service(name, f"Stopped {name}.service.")
    else:
        s.update(active=False, state="activating", until=now() + 2.0, pid=random.randint(600, 30000))
        if action == "restart":
            log_service(name, f"Stopping {name}.service...")
        log_service(name, f"Started {name}.service.")
    add_event("action", f"service {name} {action}", HOST)
    reconcile_alerts()
    return {"ok": True, "service": name, "action": action}


@app.get("/logs/{name}")
async def logs(name: str, lines: int = 80):
    if name not in SERVICE_MAP:
        fail(404, f"unknown service: {name}")
    return {"lines": service_logs(name, max(1, min(lines, 500)))}


# --- containers ------------------------------------------------------------
@app.get("/containers")
async def containers():
    return {"containers": [ctr_view(c) for c in CONTAINERS]}


@app.post("/container/{name}/{action}")
async def container_action(name: str, action: str):
    c = CTR_MAP.get(name)
    if c is None:
        fail(404, f"unknown container: {name}")
    if action not in ("start", "stop", "restart"):
        fail(400, f"unsupported action: {action}")
    t = now()
    if action == "stop":
        c.update(running=False, state="exited", stopped=t, health="", until=0.0)
        CTR_EXTRA.setdefault(name, []).append((t, f"{iso_ts(t)} received SIGTERM, shutting down"))
    else:
        c.update(running=True, state="running", started=t)
        target = CTR_HEALTHY_TARGET.get(name, c["target_health"])
        c["target_health"] = target
        c["health"] = "starting" if target else ""
        c["until"] = t + 3.0 if target else 0.0
        CTR_EXTRA.setdefault(name, []).append((t, f"{iso_ts(t)} container {action}ed"))
    del CTR_EXTRA[name][:-60]
    add_event("action", f"container {name} {action}", HOST)
    reconcile_alerts()
    return {"ok": True, "container": name, "action": action}


@app.get("/container/{name}/logs")
async def container_log(name: str, lines: int = 80):
    if name not in CTR_MAP:
        fail(404, f"unknown container: {name}")
    return {"lines": container_logs(name, max(1, min(lines, 500)))}


# --- host numbers ------------------------------------------------------------
@app.get("/vitals")
async def vitals():
    m = host_metrics("atlas")
    return {
        "cores": m["cores"],
        "load1": round(m["load_pct"] / 100 * m["cores"], 2),
        "load_pct": int(m["load_pct"]),
        "mem_pct": int(m["mem_pct"]),
        "mem_used_mb": m["mem_used_mb"],
        "mem_total_mb": m["mem_total_mb"],
        "disk_pct": int(m["disk_pct"]),
        "disk_used_gb": m["disk_used_gb"],
        "disk_total_gb": m["disk_total_gb"],
        "disk_free_gb": m["disk_total_gb"] - m["disk_used_gb"],
    }


@app.get("/gpu")
async def gpu():
    return gpu_view()


@app.get("/machines")
async def machines():
    return {"machines": [machine_view(n) for n in MACHINE_DEFS]}


@app.post("/machines/{name}/update")
async def machine_update(name: str):
    if name not in MACHINE_DEFS:
        fail(404, f"unknown machine: {name}")
    if not is_online(name):
        fail(409, f"{name} is offline")
    MACHINE_UPDATES[name] = 0
    add_event("action", f"{name}: updates applied (demo, nothing was installed)", name)
    return {"ok": True, "demo": True}


@app.post("/machines/{name}/sudo")
async def machine_sudo(name: str, request: Request):
    if name not in MACHINE_DEFS:
        fail(404, f"unknown machine: {name}")
    await body_of(request)      # the password is read and thrown away
    return {"ok": True, "demo": True, "stored": False}


@app.get("/tailnet")
async def tailnet():
    def dev(name, os_, ip, online, self_=False, seen=None):
        return {"name": name, "online": online, "os": os_, "ip": ip, "self": self_,
                "last_seen": None if online else seen}
    return {"devices": [
        dev("atlas", "linux", "198.51.100.10", True, True),
        dev("nuc", "linux", "198.51.100.11", True),
        dev("pi", "linux", "198.51.100.12", True),
        dev("vault", "linux", "198.51.100.13", is_online("vault"), seen="4h ago"),
        dev("phone", "android", "198.51.100.20", True),
        dev("laptop", "macOS", "198.51.100.21", False, seen="2d ago"),
    ]}


# --- power -------------------------------------------------------------------
@app.get("/power/draw")
async def power_draw():
    gw = round(walk("gpu.w", 14, 70, 6), 1)
    cpu = round(walk("cpu.w", 12, 55, 5), 1)
    total = gw + cpu + 70
    return {"gpu_w": gw, "rapl_ok": True, "cpu_w": cpu,
            "ups": {"watts": round(total, 1), "model": "Demo UPS 1000VA",
                    "load_pct": round(total / 600 * 100, 1), "rated_w": 600,
                    "resolution_w": 6, "on_battery": False}}


@app.get("/power/history")
async def power_history():
    return {
        "week": {"kwh": 22.4, "hours": 168, "avg_w": 128, "min_w": 91, "max_w": 342},
        "day": {"kwh": 3.1, "hours": 24, "avg_w": 129, "min_w": 94, "max_w": 288},
        "uptime_s": int(now() - BOOT["atlas"]),
    }


@app.get("/power/machines")
async def power_machines():
    out = []
    for name, d in MACHINE_DEFS.items():
        if name == "atlas":
            actions = ["suspend", "reboot", "shutdown"]
        elif name == "vault":
            actions = ["wake"]
        else:
            actions = []
        out.append({"name": name, "self": name == "atlas", "online": is_online(name),
                    "mac": d["mac"], "actions": actions})
    return {"machines": out}


@app.post("/power/wake")
async def power_wake(request: Request):
    name = str((await body_of(request)).get("name", ""))
    if name not in MACHINE_DEFS:
        fail(404, f"unknown machine: {name}")
    if name != "vault":
        fail(400, f"{name} is not wake-capable in this demo (try vault)")
    if is_online(name) or is_waking(name):
        return {"ok": True, "already": True}
    if not MACHINE_DEFS[name]["mac"]:
        fail(400, f"{name} has no MAC address yet")
    st = MACHINE_STATE[name]
    st["awake_at"] = now() + 6.0
    st["asleep_at"] = st["awake_at"] + VAULT_AWAKE_SECONDS
    add_event("wake", f"magic packet sent to {name}", name)
    return {"ok": True, "sent_to": MACHINE_DEFS[name]["mac"], "demo": True}


@app.post("/power/self")
async def power_self(request: Request):
    action = str((await body_of(request)).get("action", ""))
    if action not in ("suspend", "reboot", "shutdown"):
        fail(400, f"unsupported action: {action}")
    if action == "reboot":
        BOOT["atlas"] = now()
    add_event("action", f"{HOST}: {action} requested (demo, nothing happened)", HOST)
    return {"ok": True, "demo": True, "note": "demo mode: the machine was not touched"}


@app.get("/power/scan")
async def power_scan():
    return {"hosts": SCAN_HOSTS}


@app.post("/power/assign")
async def power_assign(request: Request):
    b = await body_of(request)
    name, mac = str(b.get("name", "")), str(b.get("mac", ""))
    if name not in MACHINE_DEFS or not mac:
        fail(400, "name and mac are required")
    MACHINE_DEFS[name]["mac"] = mac
    add_event("action", f"{name}: MAC set to {mac}", name)
    return {"ok": True}


@app.get("/updates")
async def updates():
    n = MACHINE_UPDATES["atlas"]
    return {"count": n, "packages": [{"name": p} for p in PACKAGES[:n]]}


@app.get("/actions")
async def actions():
    return {"actions": QUICK_ACTIONS}


@app.post("/action/{key}")
async def run_action(key: str):
    if key not in {a["key"] for a in QUICK_ACTIONS}:
        fail(404, f"unknown action: {key}")
    m = host_metrics("atlas")
    if key == "disk-usage":
        out = ("Filesystem      Size  Used Avail Use% Mounted on\n"
               f"/dev/nvme0n1p2  {m['disk_total_gb']}G  {m['disk_used_gb']}G  "
               f"{m['disk_total_gb'] - m['disk_used_gb']}G  {int(m['disk_pct'])}% /\n"
               "/dev/sda1       3.6T  2.1T  1.4T  61% /srv/media")
    elif key == "docker-prune":
        out = "Deleted Images:\nuntagged: example/old-image:latest\n\nTotal reclaimed space: 1.2GB"
    elif key == "flush-dns":
        out = "Resolver cache flushed."
    else:
        n = MACHINE_UPDATES["atlas"]
        out = f"{n} packages can be upgraded:\n" + "\n".join(PACKAGES[:n]) if n else "Everything is up to date."
    add_event("action", f"quick action: {key}", HOST)
    return {"output": out + "\n\n(demo output, nothing was run)"}


# --- alerts / events ---------------------------------------------------------
@app.get("/alerts/active")
async def alerts_active():
    items = sorted(ALERTS.values(), key=lambda a: (not a["critical"], -a["ts"]))
    return {"alerts": items, "unacked": sum(1 for a in items if not a["acknowledged"])}


@app.post("/alerts/ack")
async def alerts_ack(request: Request):
    b = await body_of(request)
    key = str(b.get("key", ""))
    a = ALERTS.get(key)
    if a is None:
        fail(404, f"no such alert: {key}")
    a["acknowledged"] = bool(b.get("ack", True))
    add_event("ack", f"{'dismissed' if a['acknowledged'] else 'restored'}: {a['title']}", HOST)
    return {"ok": True, "key": key, "acknowledged": a["acknowledged"]}


@app.get("/events")
async def events(limit: int = 200):
    return {"events": EVENTS[-max(1, min(limit, 500)):]}


@app.post("/event")
async def post_event(request: Request):
    b = await body_of(request)
    add_event(str(b.get("type", "info"))[:40], str(b.get("text", ""))[:500],
              str(b.get("device", "unknown"))[:60])
    return {"ok": True}


@app.get("/todo")
async def todo():
    return {"open": 3}


@app.get("/wan")
async def wan():
    d24 = sum(1 for o in OUTAGES if o["start"] > now() - 86400)
    return {"up": True, "drops_24h": d24, "drops_7d": len(OUTAGES), "outages": OUTAGES}


# --- adguard -------------------------------------------------------------------
@app.get("/adguard")
async def adguard():
    if ADGUARD["protection"]:
        ADGUARD["queries"] += random.randint(0, 6)
        ADGUARD["blocked"] += random.randint(0, 2)
    q, b = ADGUARD["queries"], ADGUARD["blocked"]
    return {
        "queries": q, "blocked": b, "blocked_pct": round(b / q * 100, 1), "rules": 94210,
        "avg_ms": round(walk("ag.ms", 6, 22, 2), 1), "protection": ADGUARD["protection"],
        "top_clients": [{"name": "laptop", "count": 14210}, {"name": "phone", "count": 9120},
                        {"name": "living-room-tv", "count": 7011}, {"name": "atlas", "count": 4404}],
        "top_blocked": [{"name": "ads.example.net", "count": 2410},
                        {"name": "tracker.example.org", "count": 1876},
                        {"name": "telemetry.example.com", "count": 1203},
                        {"name": "metrics.example.net", "count": 642}],
    }


@app.post("/adguard/protection")
async def adguard_protection(request: Request):
    ADGUARD["protection"] = bool((await body_of(request)).get("enabled", True))
    add_event("action", f"adguard protection {'on' if ADGUARD['protection'] else 'off'}", "pi")
    return {"ok": True, "protection": ADGUARD["protection"]}


# --- backup ---------------------------------------------------------------------
@app.get("/backup")
async def backup():
    return {
        "configured": True, "running": BACKUP["running"], "ok": BACKUP["ok"],
        "age_hours": round((now() - BACKUP["last"]) / 3600, 2),
        "snapshot_count": BACKUP["count"], "snapshot_bytes": BACKUP["bytes"],
        "dest_avail_bytes": 3_100_000_000_000,
    }


@app.post("/backup/run")
async def backup_run():
    if BACKUP["running"]:
        fail(409, "a backup is already running")
    BACKUP.update(running=True, end=now() + 12.0)
    add_event("action", "backup started", HOST)
    return {"ok": True, "started": True}


# --- links / home / zigbee -----------------------------------------------------
@app.get("/links")
async def links():
    def live(ref):
        if ref is None:
            return None
        if ref == "dead":
            return False
        kind, name = ref.split(":", 1)
        return SERVICE_MAP[name]["active"] if kind == "svc" else CTR_MAP[name]["running"]
    return {"groups": [
        {"name": g, "links": [{"name": n, "url": u, "desc": d, "live": live(r)} for n, u, d, r in items]}
        for g, items in LINKS]}


@app.get("/home")
async def home():
    return {"entities": [{k: v for k, v in e.items() if k not in ("until", "target")} for e in HOME]}


@app.post("/home/act")
async def home_act(request: Request):
    b = await body_of(request)
    e = HOME_MAP.get(str(b.get("entity_id", "")))
    action = str(b.get("action", ""))
    if e is None:
        fail(404, "unknown entity")
    if not e["controllable"]:
        fail(400, f"{e['label']} is read-only")
    if e["kind"] == "switch" and action in ("on", "off"):
        e["state"] = action
    elif e["kind"] == "cover" and action in ("open", "close"):
        e["state"] = "opening" if action == "open" else "closing"
        e["target"] = "open" if action == "open" else "closed"
        e["until"] = now() + 3.0
    else:
        fail(400, f"cannot {action} a {e['kind']}")
    add_event("action", f"{e['label']}: {action}", "home")
    return {"ok": True}


@app.get("/zigbee")
async def zigbee():
    return {"permit_join": ZIGBEE["permit"], "devices": ZIGBEE_DEVICES}


@app.post("/zigbee/permit")
async def zigbee_permit(request: Request):
    on = bool((await body_of(request)).get("on", False))
    ZIGBEE.update(permit=on, until=now() + 240 if on else 0.0)
    return {"ok": True, "permit_join": on}


# --- usage / sessions ---------------------------------------------------------------
@app.get("/usage")
async def usage():
    return {"sources": {
        "claude": {"week": {"in": 4_820_000, "out": 612_000, "cache": 38_400_000},
                   "today": {"in": 910_000, "out": 104_000},
                   "models": {"large-v2": {"out": 480_000}, "small-v1": {"out": 132_000}}},
        "codex": {"week": {"in": 1_230_000, "out": 188_000, "cache": 6_100_000},
                  "today": {"in": 0, "out": 0},
                  "models": {"code-m": {"out": 188_000}}},
        "agent": {"week": {"in": 260_000, "out": 41_000, "cache": 0},
                  "today": {"in": 22_000, "out": 3_000},
                  "models": {"large-v2": {"out": 41_000}}},
    }}


@app.get("/claude/sessions")
async def claude_sessions():
    t = now() - START
    return {"sessions": [
        {"pid": 2417, "cwd": "/home/demo/projects/webapp", "age_s": int(5400 + t), "tmux": "main", "yolo": False},
        {"pid": 3302, "cwd": "/home/demo/projects/infra", "age_s": int(860 + t), "tmux": None, "yolo": True},
    ]}


@app.get("/tmux/sessions")
async def tmux_sessions():
    return {"sessions": [{"name": s["name"], "windows": s["windows"], "attached": s["attached"]}
                         for s in TERMS.values()]}


# --- terminal -----------------------------------------------------------------------------
def term(name: str) -> dict:
    s = TERMS.get(name)
    if s is None:
        fail(404, f"no such session: {name}")
    return s


@app.get("/term/sessions")
async def term_sessions():
    return {"sessions": [{"name": n} for n in TERMS]}


@app.get("/term/screen2")
async def term_screen(session: str = "", ansi: bool = True):
    s = term(session)
    screen = render_screen(s)
    if not ansi:
        import re
        screen = re.sub(r"\x1b\[[0-9;]*m", "", screen)
    return {"screen": screen}


@app.post("/term/keys")
async def term_keys(request: Request):
    b = await body_of(request)
    s = term(str(b.get("session", "")))
    text = clean(str(b.get("text", "")))
    key = str(b.get("key", ""))
    if text:
        s["input"] += text
        if b.get("enter", False):
            submit_line(s)
    if key:
        press_key(s, key)
    return {"ok": True}


@app.post("/term/resize")
async def term_resize(request: Request):
    term(str((await body_of(request)).get("session", "")))
    return {"ok": True}


@app.post("/term/autosize")
async def term_autosize(request: Request):
    term(str((await body_of(request)).get("session", "")))
    return {"ok": True}


@app.post("/term/new")
async def term_new(request: Request):
    b = await body_of(request)
    name = clean(str(b.get("name", ""))).strip().replace(" ", "-")[:40]
    if not name:
        fail(400, "name is required")
    if name in TERMS:
        fail(409, f"session {name} already exists")
    cwd = clean(str(b.get("cwd", ""))).strip() or "~"
    s = _new_session(name, [], cwd=cwd)
    TERMS[name] = s
    cmd = clean(str(b.get("cmd", ""))).strip()
    if cmd:
        s["input"] = cmd
        submit_line(s)
    return {"ok": True, "session": name}


@app.delete("/term/{session}")
async def term_kill(session: str):
    term(session)
    del TERMS[session]
    return {"ok": True}


# --- commands / versions / phone ------------------------------------------------
@app.post("/command")
async def post_command(request: Request):
    b = await body_of(request)
    action = str(b.get("action", ""))
    if not action:
        fail(400, "action is required")
    args = b.get("args") if isinstance(b.get("args"), dict) else {}
    COMMANDS.append({"device": str(b.get("device", "controlcenter")), "action": action, "args": args})
    del COMMANDS[:-50]
    return {"ok": True, "queued": True}


@app.get("/commands/{device}")
async def get_commands(device: str):
    mine = [{"action": c["action"], "args": c["args"]} for c in COMMANDS if c["device"] == device]
    COMMANDS[:] = [c for c in COMMANDS if c["device"] != device]
    return {"commands": mine}


@app.post("/versions")
async def post_versions(request: Request):
    await body_of(request)
    return {"ok": True}


@app.get("/phone/version.json")
async def phone_version():
    # "updater" is not advertised in demo mode; the app should not be here.
    fail(404, "this demo bridge does not serve app updates")


@app.get("/apps/managed")
async def apps_managed():
    return {"apps": []}


@app.get("/archive/{key}")
async def archive(key: str):
    return {"versions": []}


@app.get("/access/pending")
async def access_pending():
    return {"pending": []}


@app.get("/access/devices")
async def access_devices():
    return {"devices": []}


@app.post("/access/{verb}/{item}")
async def access_act(verb: str, item: str):
    return {"ok": True}


# --- jellyfin proxy ---------------------------------------------------------------
@app.get("/jellyfin/status")
async def jf_status():
    active = []
    if int(now() // 60) % 2 == 0:
        active.append({"name": "Paper Lanterns", "device": "Living room TV",
                       "user": "demo", "type": "Movie"})
    return {"movies": len(MOVIES), "series": len(SHOWS), "active": active}


@app.get("/jellyfin/latest")
async def jf_latest(limit: int = 12):
    items = list(JF_ITEMS.values())[::-1]
    return {"items": [jf_out(i) for i in items[:max(1, min(limit, 50))]]}


@app.get("/jellyfin/resume")
async def jf_resume(limit: int = 12):
    items = [i for i in JF_ITEMS.values() if i["progress"] > 0 and not i["played"]]
    return {"items": [jf_out(i) for i in items[:max(1, min(limit, 50))]]}


@app.get("/jellyfin/search")
async def jf_search(term: str = ""):
    q = term.strip().lower()
    if not q:
        return {"items": []}
    hits = [i for i in JF_ITEMS.values()
            if q in i["name"].lower() or q in (i["series"] or "").lower()]
    return {"items": [jf_out(i) for i in hits[:40]]}


@app.get("/jellyfin/libraries")
async def jf_libraries():
    return {"libraries": [{"id": lid, "name": name,
                           "count": sum(1 for i in JF_ITEMS.values() if i["library"] == lid)}
                          for lid, name in JF_LIBRARIES]}


@app.get("/jellyfin/items")
async def jf_items(library: str = "", limit: int = 100):
    if library not in dict(JF_LIBRARIES):
        fail(404, f"unknown library: {library}")
    items = [i for i in JF_ITEMS.values() if i["library"] == library]
    return {"items": [jf_out(i) for i in items[:max(1, min(limit, 200))]]}


def _byte_range(header: str, size: int):
    """Parse a single 'bytes=a-b' range. None means no usable header (serve the
    whole file); (-1, -1) means it cannot be satisfied."""
    if not header.startswith("bytes=") or "," in header:
        return None
    a, _, b = header[6:].strip().partition("-")
    try:
        if a == "":                       # suffix: the last N bytes
            n = int(b)
            return (-1, -1) if n <= 0 else (max(0, size - n), size - 1)
        start = int(a)
        end = min(int(b), size - 1) if b else size - 1
    except ValueError:
        return None
    return (-1, -1) if start >= size or end < start else (start, end)


@app.api_route("/jellyfin/stream/{item_id}", methods=["GET", "HEAD"])
async def jf_stream(item_id: str, request: Request):
    """The demo clip for any library item, with HTTP Range support so the
    player can seek."""
    if item_id not in JF_ITEMS:
        fail(404, f"unknown item: {item_id}")
    if not DEMO_CLIP.is_file():
        fail(404, "demo clip is missing")
    data = DEMO_CLIP.read_bytes()
    size = len(data)
    base = {"Accept-Ranges": "bytes", "Cache-Control": "no-store"}
    rng = _byte_range(request.headers.get("range", ""), size)
    if rng == (-1, -1):
        return Response(status_code=416, headers={**base, "Content-Range": f"bytes */{size}"})
    if rng is None:
        status, body, headers = 200, data, {**base, "Content-Length": str(size)}
    else:
        start, end = rng
        status, body = 206, data[start:end + 1]
        headers = {**base, "Content-Range": f"bytes {start}-{end}/{size}",
                   "Content-Length": str(len(body))}
    if request.method == "HEAD":
        return Response(status_code=status, headers=headers, media_type="video/mp4")
    return Response(body, status_code=status, headers=headers, media_type="video/mp4")


@app.get("/jellyfin/thumb/{item_id}")
async def jf_thumb(item_id: str):
    return Response(make_png(item_id), media_type="image/png",
                    headers={"Cache-Control": "max-age=3600"})


# --- optional: stand-in page for the Files pane ---------------------------------
# Paste http://<bridge>/demo/files into the app's Settings to try the Files pane.


@app.get("/demo/files", response_class=HTMLResponse)
async def demo_files():
    rows = "".join(f"<li>{n}</li>" for n in
                   ["media/", "backups/", "documents/", "notes.txt", "deploy.sh"])
    return ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            "<body style='font-family:sans-serif;background:#111;color:#ddd;padding:16px'>"
            "<h3>Demo file browser</h3><p>Stand-in page for the Files pane.</p>"
            f"<ul>{rows}</ul></body>")


# ============================================================ extensions ====
# An extension is one small class. The bridge describes what to show as plain
# JSON blocks (stats, list, progress, text, chips, actions, link) and the app
# draws them with one generic renderer, so adding an integration means adding a
# class here and nothing in the app. All of it is invented demo data: no class
# below calls a real service, runs a command or opens a connection.
#
# To add one: subclass Ext, set id/name/icon, fill in card(), sections() and
# act(), and decorate the class with @extension. See Hello for the smallest one.
ICONS = ["github", "code", "build", "map", "game", "star", "terminal", "cloud",
         "chart", "bolt", "list", "info", "home"]
TONES = ["ok", "warn", "bad", "muted"]


class ExtError(Exception):
    """An action that cannot be done right now; reported as ok=false."""


EXT_CLASSES: dict[str, type] = {}
DEFAULT_EXTENSIONS = ["github", "vscode", "forge",
                      "uptime", "syncthing", "paperless", "pogo", "deadlock", "hello"]
EXTENSIONS: dict[str, "Ext"] = {}       # the ones this process serves, in order


def extension(cls):
    EXT_CLASSES[cls.id] = cls
    return cls


class Ext:
    id = ""
    name = ""
    icon = "info"
    group = ""          # optional heading the app files this extension under
    refresh_s = 10

    def card(self) -> tuple[str, int | None, str]:
        """(one line summary, badge number or None, tone) for the list."""
        return "", None, "muted"

    def sections(self) -> list[dict]:
        return []

    def act(self, action: str, item: str | None, text: str | None) -> str | None:
        """Do one action; return a short message. ValueError = unknown action."""
        raise ValueError(action)


# Small helpers so the extensions below read like data.
def chip(text: str, tone: str = "muted") -> dict:
    return {"text": text, "tone": tone}


def btn(id: str, label: str, style: str = "default", confirm: str | None = None,
        input: dict | None = None, item: str | None = None) -> dict:
    b = {"id": id, "label": label, "style": style}
    if confirm:
        b["confirm"] = confirm
    if input:
        b["input"] = input
    if item:
        b["item"] = item
    return b


def row(id: str, title: str, subtitle: str = "", meta: str = "", tone: str = "muted",
        chips: list | None = None, progress: float | None = None,
        actions: list | None = None) -> dict:
    r = {"id": id, "title": title, "subtitle": subtitle, "meta": meta, "tone": tone}
    if chips:
        r["chips"] = chips
    if progress is not None:
        r["progress"] = int(max(0, min(100, progress)))
    if actions:
        r["actions"] = actions
    return r


def stat(label: str, value, tone: str = "muted") -> dict:
    return {"label": label, "value": str(value), "tone": tone}


def section(title: str, *blocks: dict) -> dict:
    return {"title": title, "blocks": list(blocks)}


def ago(sec: float) -> str:
    sec = max(0, int(sec))
    if sec < 60:
        return f"{sec}s ago"
    if sec < 3600:
        return f"{sec // 60}m ago"
    if sec < 86400:
        return f"{sec // 3600}h ago"
    return f"{sec // 86400}d ago"


def dur(sec: float) -> str:
    sec = max(0, int(sec))
    if sec < 60:
        return f"{sec}s"
    if sec < 3600:
        return f"{sec // 60}m {sec % 60:02d}s"
    return f"{sec // 3600}h {sec % 3600 // 60:02d}m"


# ------------------------------------------------------------------ hello ----
@extension
class Hello(Ext):
    id, name, icon, group = "hello", "Hello", "star", "Examples"

    def __init__(self):
        self.count = 0

    def card(self):
        return f"Greeted {self.count} time{'' if self.count == 1 else 's'}", None, "muted"

    def sections(self):
        return [section("Say hello",
                        {"type": "text", "text": f"The button was pressed {self.count} times."},
                        {"type": "actions", "actions": [
                            btn("greet", "Greet", "primary",
                                input={"label": "Your name", "placeholder": "Ada"})]})]

    def act(self, action, item, text):
        if action != "greet":
            raise ValueError(action)
        self.count += 1
        return f"Hello, {clean(text or '') or 'stranger'}!"


# ----------------------------------------------------------------- github ----
@extension
class GitHub(Ext):
    """Invented org and repositories. Runs started here finish by themselves."""
    id, name, icon, group = "github", "GitHub", "github", "Development"

    def __init__(self):
        t = now()
        # (id, repo, number, title, author, ci, review)
        self.prs = [
            {"id": "pr-212", "repo": "fernwood/homelab-bridge", "n": 212, "title": "Add tailnet device list",
             "author": "mira-k", "ci": "passing", "review": "approved", "age": 5400},
            {"id": "pr-48", "repo": "fernwood/dashboard-ui", "n": 48, "title": "Dark theme for the energy card",
             "author": "tomasz-b", "ci": "failing", "review": "changes requested", "age": 26000},
            {"id": "pr-9", "repo": "fernwood/dotfiles", "n": 9, "title": "Switch shell prompt to two lines",
             "author": "jun-park", "ci": "running", "review": "review required", "age": 900, "ci_end": t + 15},
            {"id": "pr-215", "repo": "fernwood/homelab-bridge", "n": 215, "title": "Fix log tail on empty files",
             "author": "mira-k", "ci": "passing", "review": "review required", "age": 3000},
        ]
        self.runs = [
            {"id": "run-3041", "repo": "fernwood/homelab-bridge", "wf": "tests", "branch": "main",
             "status": "success", "started": t - 1900, "end": t - 1820},
            {"id": "run-3040", "repo": "fernwood/dashboard-ui", "wf": "build", "branch": "dark-energy-card",
             "status": "failure", "started": t - 7200, "end": t - 7110},
            {"id": "run-3039", "repo": "fernwood/dotfiles", "wf": "lint", "branch": "two-line-prompt",
             "status": "running", "started": t - 4, "end": t + 11},
            {"id": "run-3038", "repo": "fernwood/homelab-bridge", "wf": "release", "branch": "main",
             "status": "success", "started": t - 40000, "end": t - 39800},
        ]
        self.next_run = 3042

    def _settle(self):
        t = now()
        for r in self.runs:
            if r["status"] == "running" and t >= r["end"]:
                r["status"] = "success"
        for p in self.prs:
            if p["ci"] == "running" and t >= p.get("ci_end", 0):
                p["ci"] = "passing"

    def card(self):
        self._settle()
        failing = sum(1 for r in self.runs[:3] if r["status"] == "failure")
        s = f"{len(self.prs)} open pull requests"
        if failing:
            s += f", {failing} failing run"
        return s, len(self.prs), "bad" if failing else "ok"

    def sections(self):
        self._settle()
        t = now()
        ci_tone = {"passing": "ok", "failing": "bad", "running": "warn"}
        rv_tone = {"approved": "ok", "changes requested": "bad", "review required": "muted"}
        prs = []
        for p in self.prs:
            acts = []
            if p["review"] == "approved":
                acts.append(btn("merge", "Merge", "primary",
                                confirm=f"Merge #{p['n']} into main?"))
            prs.append(row(p["id"], p["title"], f"{p['repo']}#{p['n']} by @{p['author']}",
                           ago(p["age"]), ci_tone[p["ci"]],
                           [chip("CI " + p["ci"], ci_tone[p["ci"]]), chip(p["review"], rv_tone[p["review"]])],
                           actions=acts))
        runs = []
        for r in self.runs:
            tone = {"success": "ok", "failure": "bad", "running": "warn"}[r["status"]]
            acts = [btn("rerun", "Re-run")] if r["status"] == "failure" else []
            prog = None
            if r["status"] == "running":
                prog = (t - r["started"]) / (r["end"] - r["started"]) * 100
            runs.append(row(r["id"], f"{r['wf']} on {r['branch']}", r["repo"],
                            "running" if prog is not None else dur(r["end"] - r["started"]),
                            tone, [chip(r["status"], tone)], progress=prog, actions=acts))
        running = sum(1 for r in self.runs if r["status"] == "running")
        failing = sum(1 for r in self.runs if r["status"] == "failure")
        return [
            section("Overview", {"type": "stats", "tiles": [
                stat("Open PRs", len(self.prs)),
                stat("Running", running, "warn" if running else "muted"),
                stat("Failing", failing, "bad" if failing else "ok")]}),
            section("Open pull requests", {"type": "list", "items": prs}),
            section("Recent workflow runs", {"type": "list", "items": runs}),
        ]

    def act(self, action, item, text):
        self._settle()
        if action == "merge":
            p = next((p for p in self.prs if p["id"] == item), None)
            if not p:
                raise ExtError("That pull request is already gone.")
            if p["review"] != "approved":
                raise ExtError("Needs an approving review first.")
            self.prs.remove(p)
            add_event("action", f"github: merged {p['repo']}#{p['n']}")
            return f"Merged #{p['n']}"
        if action == "rerun":
            r = next((r for r in self.runs if r["id"] == item), None)
            if not r or r["status"] != "failure":
                raise ExtError("Only a failed run can be re-run.")
            t = now()
            r.update(status="running", started=t, end=t + 9)
            return f"Re-running {r['wf']}"
        raise ValueError(action)


# ----------------------------------------------------------------- vscode ----
@extension
class VSCode(Ext):
    """Remote editor sessions on the demo machines. Tunnels are just flags."""
    id, name, icon, group = "vscode", "VS Code", "code", "Development"

    def __init__(self):
        t = now()
        self.sessions = [
            {"id": "ses-bridge", "ws": "homelab-bridge", "machine": "atlas", "branch": "main",
             "opened": t - 3 * 3600 - 420, "tunnel": True},
            {"id": "ses-dash", "ws": "dashboard-ui", "machine": "nuc", "branch": "dark-energy-card",
             "opened": t - 55 * 60, "tunnel": True},
            {"id": "ses-notes", "ws": "notes", "machine": "pi", "branch": "main",
             "opened": t - 26 * 3600, "tunnel": False},
            {"id": "ses-backup", "ws": "backup-scripts", "machine": "vault", "branch": "retention",
             "opened": t - 2 * 86400, "tunnel": False},
        ]

    def card(self):
        up = sum(1 for s in self.sessions if s["tunnel"])
        return f"{len(self.sessions)} sessions, {up} tunnel{'' if up == 1 else 's'} up", up, "ok" if up else "muted"

    def sections(self):
        t = now()
        rows = []
        for s in self.sessions:
            online = is_online(s["machine"])
            if s["tunnel"]:
                acts, tone, tchip = [btn("stop_tunnel", "Stop tunnel", "danger",
                                         confirm=f"Close the tunnel to {s['machine']}?")], "ok", chip("tunnel up", "ok")
            else:
                acts, tone, tchip = [btn("start_tunnel", "Start tunnel")], "muted", chip("tunnel down", "muted")
            chips = [tchip, chip(s["branch"])]
            if not online:
                chips.append(chip("machine offline", "bad"))
            rows.append(row(s["id"], s["ws"], f"on {s['machine']}", dur(t - s["opened"]),
                            tone, chips, actions=acts))
        return [
            section("Remote sessions", {"type": "list", "items": rows}),
            section("Browser editor",
                    {"type": "text", "text": "A placeholder page served by the demo bridge stands in for the editor."},
                    {"type": "link", "label": "Open in browser", "url": "/demo/vscode"}),
        ]

    def act(self, action, item, text):
        s = next((s for s in self.sessions if s["id"] == item), None)
        if action not in ("start_tunnel", "stop_tunnel"):
            raise ValueError(action)
        if not s:
            raise ExtError("That session is gone.")
        if action == "start_tunnel":
            if not is_online(s["machine"]):
                raise ExtError(f"{s['machine']} is offline. Wake it first.")
            s["tunnel"] = True
            return f"Tunnel to {s['machine']} is up"
        s["tunnel"] = False
        return f"Tunnel to {s['machine']} closed"


# ------------------------------------------------------------------ forge ----
FORGE_STEPS = [
    (0.0, "[agent] reading the description"),
    (1.5, "[expo] scaffolding a blank TypeScript project"),
    (3.0, "[agent] planning screens: Home, Detail, Settings"),
    (5.0, "[agent] writing src/screens/Home.tsx"),
    (7.0, "[agent] writing src/store.ts"),
    (9.0, "[expo] type check passed"),
    (11.0, "[expo] web preview bundled (412 kB)"),
    (12.5, "[done] preview ready"),
]
FORGE_SECONDS = 13.0


@extension
class Forge(Ext):
    """Describe an app, watch a fake build job. Nothing is generated or built."""
    id, name, icon, group = "forge", "App forge", "build", "Development"
    refresh_s = 3

    def __init__(self):
        t = now()
        self.apps = [
            {"id": "app-habit-tracker", "name": "Habit tracker", "desc": "Daily habits with streaks",
             "status": "ready", "created": t - 3 * 86400, "job": None},
            {"id": "app-pantry-list", "name": "Pantry list", "desc": "What is in the cupboard, with expiry dates",
             "status": "ready", "created": t - 86400, "job": None},
            {"id": "app-plant-waterer", "name": "Plant waterer", "desc": "Reminders for watering the plants",
             "status": "failed", "created": t - 5 * 3600, "job": None},
        ]

    def _settle(self):
        for a in self.apps:
            j = a["job"]
            if j and now() - j >= FORGE_SECONDS:
                a.update(job=None, status="ready")

    def _job_app(self):
        return next((a for a in self.apps if a["job"]), None)

    def card(self):
        self._settle()
        j = self._job_app()
        if j:
            return f"Building {j['name']}", 1, "warn"
        failed = sum(1 for a in self.apps if a["status"] == "failed")
        return f"{len(self.apps)} apps, {failed} failed", failed or None, "bad" if failed else "ok"

    def sections(self):
        self._settle()
        out = [section(
            "New app",
            {"type": "text", "text": "Describe the app. A coding agent scaffolds an Expo (React Native) project and builds it."},
            {"type": "actions", "actions": [btn("create", "Create app", "primary", input={
                "label": "Describe the app", "placeholder": "A shopping list that works offline"})]})]
        j = self._job_app()
        if j:
            el = now() - j["job"]
            log = "\n".join(line for at, line in FORGE_STEPS if el >= at)
            out.append(section(f"Build: {j['name']}",
                               {"type": "progress", "label": "Build job", "value": el / FORGE_SECONDS * 100, "tone": "warn"},
                               {"type": "text", "text": log, "mono": True}))
        rows = []
        for a in self.apps:
            building = bool(a["job"])
            tone = "warn" if building else {"ready": "ok", "failed": "bad"}[a["status"]]
            acts = []
            if a["status"] == "failed" and not building:
                acts.append(btn("retry", "Retry"))
            if not building:
                acts.append(btn("delete", "Delete", "danger", confirm=f"Delete {a['name']}?"))
            rows.append(row(a["id"], a["name"], a["desc"], ago(now() - a["created"]), tone,
                            [chip("building" if building else a["status"], tone), chip("Expo")],
                            progress=(now() - a["job"]) / FORGE_SECONDS * 100 if building else None,
                            actions=acts))
        out.append(section("Apps", {"type": "list", "items": rows}))
        links = [{"type": "link", "label": f"Preview: {a['name']}", "url": f"/demo/forge/{a['id']}"}
                 for a in self.apps if a["status"] == "ready" and not a["job"]]
        if links:
            out.append(section("Previews", *links))
        out.append(section(
            "About", {"type": "text", "mono": True, "text":
                      "Demo mode: no project is created and nothing is built.\n"
                      "The log and the preview page are canned text."}))
        return out

    def act(self, action, item, text):
        self._settle()
        if action == "create":
            desc = clean(text or "")
            if not desc:
                raise ExtError("Describe the app first.")
            if self._job_app():
                raise ExtError("A build is already running.")
            words = "".join(c if c.isalnum() else " " for c in desc.lower()).split()
            while words and words[0] in ("a", "an", "the", "my"):
                words.pop(0)
            for i, w in enumerate(words):       # the name is the noun phrase before "that", "with", ...
                if i and w in ("that", "which", "with", "for", "to", "and", "where"):
                    words = words[:i]
                    break
            words = words[:3]
            slug = "-".join(words) or "app"
            self.apps.insert(0, {"id": "app-" + slug + "-" + str(len(self.apps) + 1),
                                 "name": " ".join(words).capitalize() or "App", "desc": desc[:80],
                                 "status": "building", "created": now(), "job": now()})
            return "Build started"
        a = next((a for a in self.apps if a["id"] == item), None)
        if action not in ("retry", "delete"):
            raise ValueError(action)
        if not a:
            raise ExtError("That app is gone.")
        if a["job"]:
            raise ExtError("It is still building.")
        if action == "retry":
            if self._job_app():
                raise ExtError("A build is already running.")
            a.update(status="building", job=now())
            return f"Rebuilding {a['name']}"
        self.apps.remove(a)
        return f"Deleted {a['name']}"


# ------------------------------------------------------------------- pogo ----
@extension
class Pogo(Ext):
    """Information only: sample raid bosses and events with dates relative to today."""
    id, name, icon, group = "pogo", "Pogo raids", "map", "Games"
    refresh_s = 60

    BOSSES = [
        ("Mewtwo", ["psychic"], "CP 2,200 to 2,300"), ("Rayquaza", ["dragon", "flying"], "CP 2,000 to 2,100"),
        ("Dialga", ["steel", "dragon"], "CP 2,100 to 2,200"),
    ]
    MEGAS = [
        ("Mega Gengar", ["ghost", "poison"], "CP 2,000 to 2,100"),
        ("Mega Lucario", ["fighting", "steel"], "CP 1,900 to 2,000"),
    ]
    # (name, type, start offset in hours from now, length in hours)
    EVENTS = [
        ("Spotlight Hour, Pikachu", "spotlight", -0.5, 1.0),
        ("Community Day, Bulbasaur", "community day", 30, 3.0),
        ("Raid Hour, Dialga", "raid hour", 3 * 24 + 4, 1.0),
        ("Bug Out", "season event", -2 * 24, 6 * 24),
        ("Research Day", "research", 5 * 24 + 2, 4.0),
    ]

    def _when(self, start_h: float, len_h: float) -> str:
        t = now()
        s, e = t + start_h * 3600, t + (start_h + len_h) * 3600
        if s <= t < e:
            if e - t <= 3600:
                return f"{int((e - t) // 60)} min left"
            return "until " + time.strftime("%a %d %b", time.localtime(e))
        s = round(s / 3600) * 3600            # upcoming events start on the hour
        days = (date.fromtimestamp(s) - date.fromtimestamp(t)).days
        rel = "today" if days == 0 else "tomorrow" if days == 1 else f"in {days} days"
        return f"{rel}, {time.strftime('%a %d %b %H:%M', time.localtime(s))}"

    def _live(self):
        return [e for e in self.EVENTS if e[2] <= 0 < e[2] + e[3]]

    def card(self):
        n = len(self._live())
        return f"{len(self.BOSSES)} five-star bosses, {n} events live", n or None, "ok" if n else "muted"

    def sections(self):
        def boss(b):
            return row("boss-" + b[0].lower().replace(" ", "-"), b[0], b[2], "", "muted",
                       [chip(x) for x in b[1]])

        def ev(e, live):
            return row("ev-" + e[0].lower().replace(" ", "-").replace(",", ""), e[0], self._when(e[2], e[3]),
                       "now" if live else "", "ok" if live else "muted", [chip(e[1], "ok" if live else "muted")])
        live = self._live()
        upcoming = sorted([e for e in self.EVENTS if e[2] > 0], key=lambda e: e[2])
        return [
            section("Sample data",
                    {"type": "text", "text": "Made-up dates and a few well-known creatures. A real integration would read a public community event feed."},
                    {"type": "stats", "tiles": [stat("Five-star", len(self.BOSSES)), stat("Mega", len(self.MEGAS)),
                                                stat("Live events", len(live), "ok" if live else "muted")]}),
            section("Five-star raids", {"type": "list", "items": [boss(b) for b in self.BOSSES]}),
            section("Mega raids", {"type": "list", "items": [boss(b) for b in self.MEGAS]}),
            section("Happening now", {"type": "list", "items": [ev(e, True) for e in live]}),
            section("Upcoming", {"type": "list", "items": [ev(e, False) for e in upcoming]}),
        ]


# --------------------------------------------------------------- deadlock ----
@extension
class Deadlock(Ext):
    """A draft advisor overlay on a gaming PC, as a start/stop switch plus sample numbers."""
    id, name, icon, group = "deadlock", "Deadlock", "game", "Games"
    refresh_s = 5

    PICKS = [  # hero, reason, base win rate
        ("Abrams", "Frontline is missing and the enemy team has two squishy carries", 54.2),
        ("Haze", "Strong into slow heroes, good with your current pick", 52.8),
        ("Seven", "Fills the wave-clear gap, enemy has no burst to punish it", 51.9),
    ]
    MATCHES = [  # hero, win, minutes, k/d/a, ago seconds
        ("Abrams", True, 34, "9 / 3 / 11", 2 * 3600), ("Haze", False, 41, "5 / 8 / 7", 5 * 3600),
        ("Seven", True, 29, "12 / 4 / 6", 26 * 3600), ("Haze", True, 37, "8 / 5 / 9", 28 * 3600),
        ("Abrams", False, 45, "4 / 9 / 14", 50 * 3600),
    ]

    def __init__(self):
        self.running_since: float | None = None

    def card(self):
        if self.running_since is None:
            return "Overlay stopped", None, "muted"
        return "Overlay running, 3 suggested picks", 3, "ok"

    def sections(self):
        up = self.running_since is not None
        out = [section(
            "Overlay on the gaming PC",
            {"type": "stats", "tiles": [
                stat("Status", "running" if up else "stopped", "ok" if up else "muted"),
                stat("Uptime", dur(now() - self.running_since) if up else "none"),
                stat("Mode", "draft")]},
            {"type": "actions", "actions": [btn("stop", "Stop overlay", "danger") if up else btn("start", "Start overlay", "primary")]})]
        if up:
            rows = []
            for i, (hero, why, wr) in enumerate(self.PICKS):
                w = walk("dl-" + hero, wr - 0.6, wr + 0.6, 0.1)
                rows.append(row("pick-" + hero.lower(), hero, why, f"#{i + 1}", "ok" if i == 0 else "muted",
                                [chip(f"{w:.1f}% win", "ok" if w >= 53 else "muted")]))
            out.append(section("Suggested picks (sample data)", {"type": "list", "items": rows}))
        else:
            out.append(section("Suggested picks", {"type": "text", "text": "Start the overlay to see picks."}))
        rows = [row(f"match-{i}", f"{h}, {'win' if w else 'loss'}", f"{m} min, K/D/A {kda}", ago(a),
                    "ok" if w else "bad", [chip("win" if w else "loss", "ok" if w else "bad")])
                for i, (h, w, m, kda, a) in enumerate(self.MATCHES)]
        out.append(section("Recent matches (sample data)", {"type": "list", "items": rows}))
        return out

    def act(self, action, item, text):
        if action == "start":
            self.running_since = self.running_since or now()
            return "Overlay started"
        if action == "stop":
            self.running_since = None
            return "Overlay stopped"
        raise ValueError(action)


# ------------------------------------------------------- homelab apps ----
# The next three are views of self-hosted apps' own status APIs. Here they are
# invented numbers and titles; a real bridge would call each app's API instead.
def pct_done(t0: float, secs: float) -> float:
    return min(1.0, (now() - t0) / secs) * 100


@extension
class UptimeKuma(Ext):
    """Uptime monitors. One is down, and a row can be paused and resumed."""
    id, name, icon, group = "uptime", "Uptime Kuma", "chart", "Monitoring"
    refresh_s = 5
    MONITORS = [("atlas-web", "https://atlas.lan", 99.98, 42), ("vault-share", "tcp vault:445", 99.71, 18),
                ("dns-pi", "udp pi:53", 100.0, 6), ("media-ui", "https://atlas.lan:8096", 97.4, 0)]

    def __init__(self):
        self.paused: set[str] = set()

    def _down(self, name):
        return name == "media-ui"

    def card(self):
        down = sum(1 for m in self.MONITORS if self._down(m[0]) and m[0] not in self.paused)
        return f"{len(self.MONITORS)} monitors, {down} down", down or None, "bad" if down else "ok"

    def sections(self):
        down = [m for m in self.MONITORS if self._down(m[0]) and m[0] not in self.paused]
        items = []
        for n, target, up, ms in self.MONITORS:
            p = n in self.paused
            tone = "muted" if p else "bad" if self._down(n) else "ok"
            state = "paused" if p else "down" if self._down(n) else "up"
            ms_now = int(walk("ut-" + n, ms * 0.8, ms * 1.2 + 1, 2)) if not (p or self._down(n)) else 0
            items.append(row(n, n, target, "" if tone != "ok" else f"{ms_now} ms", tone, [chip(state, tone)],
                             actions=[btn("resume" if p else "pause", "Resume" if p else "Pause", item=n)]))
        out = [section("Overview", {"type": "stats", "tiles": [
            stat("Up", len(self.MONITORS) - len(down) - len(self.paused), "ok"),
            stat("Down", len(down), "bad" if down else "ok"), stat("Paused", len(self.paused))]}),
            section("Monitors", {"type": "list", "items": items})]
        out.append(section("Uptime, last 24 h", *[
            {"type": "progress", "label": n, "value": up, "tone": "ok" if up >= 99 else "warn" if up >= 97 else "bad"}
            for n, _, up, _ in self.MONITORS]))
        return out

    def act(self, action, item, text):
        if action not in ("pause", "resume"):
            raise ValueError(action)
        if item not in [m[0] for m in self.MONITORS]:
            raise ExtError("No such monitor.")
        (self.paused.add if action == "pause" else self.paused.discard)(item)
        return f"{item} {'paused' if action == 'pause' else 'resumed'}"


@extension
class Paperless(Ext):
    """Document inbox. A consume queue item finishes after a few seconds and lands in the inbox."""
    id, name, icon, group = "paperless", "Paperless", "info", "Files"
    refresh_s = 4

    def __init__(self):
        self.total = 1284
        self.inbox = [("d1", "Electricity bill, October", ["bills", "atlas-home"], 3600),
                      ("d2", "Router warranty card", ["manuals"], 9000),
                      ("d3", "Bike service receipt", ["receipts", "bike"], 40000)]
        self.consuming = {"name": "scan-0412.pdf", "t0": None, "secs": 12}   # starts at the first read
        self.next_d = 4

    def _settle(self):
        c = self.consuming
        if c and c["t0"] is None:
            c["t0"] = now()
        if c and pct_done(c["t0"], c["secs"]) >= 100:
            self.inbox.insert(0, (f"d{self.next_d}", "Scan 0412, letter from the council", ["inbox"], 0))
            self.next_d += 1
            self.total += 1
            self.consuming = None

    def card(self):
        self._settle()
        return f"{len(self.inbox)} in the inbox, {self.total} documents", len(self.inbox) or None, "warn" if self.inbox else "ok"

    def sections(self):
        self._settle()
        inbox = [row(i, t, "", ago(a) if a else "just now", "warn", [chip(x) for x in tags],
                     actions=[btn("reviewed", "Mark reviewed", item=i)]) for i, t, tags, a in self.inbox]
        c = self.consuming
        queue = ([row("consume", c["name"], "being read and indexed", "", "warn", [chip("consuming", "warn")],
                      progress=pct_done(c["t0"], c["secs"]))] if c else [])
        return [
            section("Overview", {"type": "stats", "tiles": [
                stat("Documents", self.total), stat("Inbox", len(self.inbox), "warn" if self.inbox else "ok"),
                stat("Consuming", 1 if c else 0, "warn" if c else "muted")]}),
            section("Inbox", {"type": "list", "items": inbox}),
            section("Consume queue", {"type": "list", "items": queue}),
        ]

    def act(self, action, item, text):
        self._settle()
        if action != "reviewed":
            raise ValueError(action)
        d = next((d for d in self.inbox if d[0] == item), None)
        if not d:
            raise ExtError("Already reviewed.")
        self.inbox.remove(d)
        return f"Marked reviewed: {d[1]}"


@extension
class Syncthing(Ext):
    """Folder sync. Rescan starts a sync that fills a progress bar, then the folder is up to date."""
    id, name, icon, group = "syncthing", "Syncthing", "cloud", "Files"
    refresh_s = 3

    def __init__(self):
        # id -> [label, path, size, scan start or None, scan length]
        self.folders = {"docs": ["Documents", "~/Documents", "4.1 GB", None, 0],
                        "photos": ["Camera roll", "~/Pictures/camera", "38 GB", 0, 20],
                        "notes": ["Notes", "~/notes", "120 MB", None, 0]}
        self.stale = {"notes"}       # out of sync until rescanned

    def _state(self, fid):
        f = self.folders[fid]
        if f[3] == 0:                # the camera roll starts mid-sync when first looked at
            f[3] = now() - 4
        if f[3] is not None:
            p = pct_done(f[3], f[4])
            if p < 100:
                return "syncing", p
            f[3] = None
            self.stale.discard(fid)
        return ("out of sync", None) if fid in self.stale else ("up to date", None)

    def card(self):
        st = [self._state(f)[0] for f in self.folders]
        n = sum(1 for s in st if s != "up to date")
        return f"{len(self.folders)} folders, {n} need attention", n or None, "warn" if n else "ok"

    def sections(self):
        tone = {"up to date": "ok", "syncing": "warn", "out of sync": "bad"}
        items = []
        for fid, f in self.folders.items():
            s, p = self._state(fid)
            items.append(row(fid, f[0], f"{f[1]}, {f[2]}", f"{int(p)}%" if p is not None else "", tone[s],
                             [chip(s, tone[s])], progress=p, actions=[btn("rescan", "Rescan", item=fid)]))
        devs = [row("dev-" + n, n, addr, "connected", "ok", [chip(c)]) for n, addr, c in
                [("laptop", "tailnet", "1.2 MB/s"), ("phone", "tailnet", "idle")]]
        return [section("Folders", {"type": "list", "items": items}),
                section("Connected devices", {"type": "list", "items": devs})]

    def act(self, action, item, text):
        if action != "rescan":
            raise ValueError(action)
        f = self.folders.get(item or "")
        if not f:
            raise ExtError("No such folder.")
        if self._state(item)[0] == "syncing":
            raise ExtError("Already syncing.")
        f[3], f[4] = now(), 12
        return f"Rescanning {f[0]}"


# ------------------------------------------------------------ extra pages ----
_PAGE = ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
         "<body style='font-family:sans-serif;background:#111;color:#ddd;padding:16px'>{}</body>")


@app.get("/demo/vscode", response_class=HTMLResponse)
async def demo_vscode():
    return _PAGE.format("<h3>Demo editor</h3><p>Stand-in for a browser based code editor. "
                        "The demo bridge serves no real editor.</p>"
                        "<pre style='background:#1b1b1f;padding:12px'>def main():\n    print('hello')</pre>")


@app.get("/demo/forge/{app_id}", response_class=HTMLResponse)
async def demo_forge_preview(app_id: str):
    ext = EXTENSIONS.get("forge")
    a = next((a for a in getattr(ext, "apps", []) if a["id"] == app_id), None)
    if not a:
        fail(404, "no such app")
    esc = lambda s: s.replace("&", "&amp;").replace("<", "&lt;")
    return _PAGE.format(f"<h3>{esc(a['name'])}</h3><p>{esc(a['desc'])}</p>"
                        "<p style='color:#888'>Static preview page. Demo mode builds nothing; "
                        "a real build would show the app's web export here.</p>")


# ------------------------------------------------------------ extension api ---
def _ext(ext_id: str) -> Ext:
    ext = EXTENSIONS.get(ext_id)
    if ext is None:
        fail(404, f"no such extension: {ext_id}")
    return ext


@app.get("/extensions")
async def extensions_list():
    out = []
    for e in EXTENSIONS.values():
        summary, badge, tone = e.card()
        item = {"id": e.id, "name": e.name, "icon": e.icon, "summary": summary,
                "badge": badge, "tone": tone}
        if e.group:
            item["group"] = e.group
        out.append(item)
    return {"extensions": out}


@app.get("/extensions/{ext_id}")
async def extension_screen(ext_id: str):
    e = _ext(ext_id)
    return {"id": e.id, "name": e.name, "refresh_s": e.refresh_s, "sections": e.sections()}


@app.post("/extensions/{ext_id}/action")
async def extension_action(ext_id: str, request: Request):
    e = _ext(ext_id)
    body = await body_of(request)
    action = body.get("action")
    if not isinstance(action, str) or not action:
        fail(400, "missing action")
    item = body.get("item") if isinstance(body.get("item"), str) else None
    text = body.get("input") if isinstance(body.get("input"), str) else None
    try:
        msg = e.act(action, item, text)
    except ValueError:
        fail(404, f"unknown action: {action}")
    except ExtError as err:
        return {"ok": False, "message": str(err), "refresh": True}
    return {"ok": True, "message": msg, "refresh": True}


@app.exception_handler(Exception)
async def anything_else(request: Request, exc: Exception):
    # Keep the app's error parser happy even if the demo itself has a bug.
    return JSONResponse({"detail": f"demo bridge error: {exc.__class__.__name__}"}, status_code=500)


# ================================================================ startup ====
def reset_state():
    TERMS.clear()
    TERMS.update(_seed_terms())
    if not JF_ITEMS:
        _build_library()
    if not EVENTS:
        _seed_events()
    reconcile_alerts(quiet=True)
    for a in ALERTS.values():           # the seeded alerts predate this process
        a["ts"] = START - 1500


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Control Center reference bridge (demo mode only for now)")
    ap.add_argument("--demo", action="store_true", help="serve invented data, touch nothing real")
    ap.add_argument("--host", default="127.0.0.1", help="address to bind (default 127.0.0.1)")
    ap.add_argument("--port", type=int, default=8790, help="port to listen on (default 8790)")
    ap.add_argument("--extensions", default=None,
                    help="comma separated extension ids to serve (default: all of "
                         + ", ".join(EXT_CLASSES) + ")")
    ap.add_argument("--features", default=None,
                    help="comma separated list to advertise in /capabilities instead of the default")
    args = ap.parse_args(argv)

    if not args.demo:
        print("Only demo mode is implemented so far. Start the bridge with --demo.", file=sys.stderr)
        return 2

    global FEATURES
    if args.features is not None:
        wanted = [f.strip() for f in args.features.split(",") if f.strip()]
        bad = [f for f in wanted if f not in ALL_FEATURES]
        if bad:
            print(f"Unknown feature(s): {', '.join(bad)}\nKnown: {', '.join(ALL_FEATURES)}", file=sys.stderr)
            return 2
        FEATURES = wanted

    chosen = DEFAULT_EXTENSIONS if args.extensions is None else \
        [x.strip() for x in args.extensions.split(",") if x.strip()]
    bad = [x for x in chosen if x not in EXT_CLASSES]
    if bad:
        print(f"Unknown extension(s): {', '.join(bad)}\nKnown: {', '.join(EXT_CLASSES)}", file=sys.stderr)
        return 2
    EXTENSIONS.clear()
    EXTENSIONS.update({x: EXT_CLASSES[x]() for x in chosen})

    reset_state()
    import uvicorn
    print(f"Demo bridge on http://{args.host}:{args.port}  (no authentication, invented data)")
    print("Advertising: " + (", ".join(FEATURES) or "(nothing)"))
    print("Extensions: " + (", ".join(EXTENSIONS) or "(none)"))
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
