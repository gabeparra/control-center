#!/usr/bin/env python3
"""Smoke test for the Control Center bridge. Standard library only.

    python3 bridge/smoke_test.py http://127.0.0.1:8790

It starts nothing. Point it at a running bridge. It calls every endpoint in the
README contract, checks the status code, checks that the JSON keys the app
reads exist with the right types, and walks through a few state changes
(restart a service, dismiss an alert, wake a machine, and so on). Exits 1 if
anything fails.

The tests do change the bridge's in-memory state, so restart the demo bridge
before running this if you want to look at the original data.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = ""
FAILS: list[str] = []
CHECKS = 0

NUM = (int, float)


class L:
    """A list whose elements are objects matching spec (an empty list is fine)."""
    def __init__(self, spec, min_len=0):
        self.spec, self.min_len = spec, min_len


class N:
    """Nullable: None or the given type."""
    def __init__(self, t):
        self.t = t


def fail(msg):
    FAILS.append(msg)
    print("  FAIL", msg)


def ok(cond, msg):
    global CHECKS
    CHECKS += 1
    if not cond:
        fail(msg)
    return cond


def request(method, path, body=None, raw=None, headers=None):
    """Returns (status, headers, bytes)."""
    data = None
    hdrs = dict(headers or {})
    if raw is not None:
        data = raw
    elif body is not None or method == "POST":
        data = json.dumps(body if body is not None else {}).encode()
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def call(method, path, body=None, status=200, **kw):
    """Make a call, check the status, and return parsed JSON (or None)."""
    code, hdrs, raw = request(method, path, body, **kw)
    if not ok(code == status, f"{method} {path}: expected {status}, got {code} {raw[:120]!r}"):
        return None
    if "json" not in hdrs.get("Content-Type", ""):
        return raw
    try:
        return json.loads(raw)
    except ValueError:
        fail(f"{method} {path}: body is not JSON")
        return None


def shape(val, spec, where):
    """Check val against spec, recording a failure per problem."""
    if isinstance(spec, dict):
        if not ok(isinstance(val, dict), f"{where}: expected object, got {type(val).__name__}"):
            return
        for k, t in spec.items():
            if not ok(k in val, f"{where}: missing key '{k}'"):
                continue
            shape(val[k], t, f"{where}.{k}")
    elif isinstance(spec, L):
        if not ok(isinstance(val, list), f"{where}: expected array"):
            return
        ok(len(val) >= spec.min_len, f"{where}: expected at least {spec.min_len} item(s)")
        for i, item in enumerate(val[:50]):
            shape(item, spec.spec, f"{where}[{i}]")
    elif isinstance(spec, N):
        if val is not None:
            shape(val, spec.t, where)
    else:
        good = isinstance(val, spec)
        if good and spec in (int, NUM) and isinstance(val, bool):
            good = False
        ok(good, f"{where}: expected {spec}, got {type(val).__name__} ({val!r:.40})")


def get(path, spec, status=200):
    d = call("GET", path, status=status)
    if d is not None:
        shape(d, spec, "GET " + path)
    return d


def post(path, body, spec=None, status=200):
    d = call("POST", path, body, status=status)
    if d is not None and spec is not None:
        shape(d, spec, "POST " + path)
    return d


def find(items, **kw):
    for it in items or []:
        if all(it.get(k) == v for k, v in kw.items()):
            return it
    return None


def wait_for(fn, seconds=15):
    end = time.time() + seconds
    while time.time() < end:
        if fn():
            return True
        time.sleep(0.5)
    return False


# --------------------------------------------------------------------------
def test_capabilities():
    print("capabilities")
    d = get("/capabilities", {"version": int, "demo": bool, "features": L(str)})
    known = {"services", "containers", "vitals", "gpu", "machines", "terminal", "tmux_overview",
             "claude_sessions", "wake", "alerts", "events", "links", "adguard", "home", "zigbee",
             "jellyfin", "backup", "usage", "power_draw", "updates", "wan", "actions",
             "tailnet", "commands", "updater", "extensions"}
    if d:
        ok(d["version"] == 1, "capabilities version should be 1")
        ok(all(f in known for f in d["features"]), "capabilities lists an unknown feature")
        ok("updater" not in d["features"] or not d["demo"], "demo should not advertise updater")


def test_services():
    print("services and logs")
    d = get("/stack", {"services": L({"name": str, "active": bool, "state": str}, 1)})
    names = [s["name"] for s in d["services"]] if d else []
    ok("nginx" in names, "stack should list nginx")
    post("/service/nginx/restart", {})
    s = find(get("/stack", {"services": L({"name": str, "active": bool, "state": str})})["services"], name="nginx")
    ok(s and s["state"] == "activating", f"nginx should be activating right after restart, got {s}")
    ok(wait_for(lambda: find(call("GET", "/stack")["services"], name="nginx")["active"]),
       "nginx should come back active")
    post("/service/redis/stop", {})
    s = find(call("GET", "/stack")["services"], name="redis")
    ok(s and not s["active"] and s["state"] == "inactive", f"redis should be inactive after stop, got {s}")
    alerts = call("GET", "/alerts/active")["alerts"]
    ok(find(alerts, key="svc:redis") is not None, "stopping redis should raise svc:redis alert")
    post("/service/redis/start", {})
    ok(wait_for(lambda: find(call("GET", "/stack")["services"], name="redis")["active"]), "redis should restart")
    ok(find(call("GET", "/alerts/active")["alerts"], key="svc:redis") is None,
       "svc:redis alert should clear when redis is back")
    post("/service/nope/restart", {}, status=404)
    lg = get("/logs/nginx?lines=40", {"lines": L(str, 5)})
    if lg:
        ok(len(lg["lines"]) <= 40, "logs should respect lines=")
        ok(any("Started nginx" in x for x in lg["lines"]), "log should show the restart line")
    get("/logs/nope?lines=5", {}, status=404)


def test_containers():
    print("containers")
    shape_c = {"name": str, "running": bool, "state": str, "status": str, "health": str, "image": str}
    d = get("/containers", {"containers": L(shape_c, 3)})
    ok(find(d["containers"], name="jellyfin") is not None if d else False, "jellyfin container missing")
    post("/container/wiki/stop", {})
    c = find(call("GET", "/containers")["containers"], name="wiki")
    ok(c and not c["running"], "wiki should be stopped")
    ok(find(call("GET", "/alerts/active")["alerts"], key="ctr:wiki") is not None,
       "stopped container should raise ctr:wiki alert")
    post("/container/wiki/start", {})
    c = find(call("GET", "/containers")["containers"], name="wiki")
    ok(c and c["running"], "wiki should be running again")
    # the seeded unhealthy container gets fixed by a restart
    pl = find(call("GET", "/containers")["containers"], name="paperless")
    ok(pl and pl["health"] == "unhealthy", f"paperless should start unhealthy, got {pl}")
    post("/container/paperless/restart", {})
    ok(wait_for(lambda: find(call("GET", "/containers")["containers"], name="paperless")["health"] == "healthy"),
       "paperless should become healthy after restart")
    get("/container/jellyfin/logs?lines=30", {"lines": L(str, 5)})
    get("/container/nope/logs", {}, status=404)
    post("/container/nope/restart", {}, status=404)


def test_host_numbers():
    print("gpu, vitals, power, tailnet")
    gpu_spec = {"name": str, "vram_used_mb": int, "vram_total_mb": int, "util_pct": int, "temp_c": int}
    g1 = get("/gpu", gpu_spec)
    ok(g1 and g1["vram_total_mb"] > 0, "vram_total_mb must be positive (the app divides by it)")
    vit = {"cores": int, "load1": NUM, "load_pct": int, "mem_pct": int, "mem_used_mb": int,
           "mem_total_mb": int, "disk_pct": int, "disk_used_gb": int, "disk_total_gb": int,
           "disk_free_gb": int}
    v1 = get("/vitals", vit)
    time.sleep(0.2)
    seen = {json.dumps(call("GET", "/vitals"), sort_keys=True) for _ in range(6)}
    ok(len(seen) > 1, "vitals should wobble between requests")
    get("/power/draw", {"gpu_w": N(NUM), "rapl_ok": bool, "cpu_w": NUM,
                        "ups": {"watts": NUM, "model": str, "load_pct": NUM, "rated_w": int,
                                "resolution_w": int, "on_battery": bool}})
    hist = {"kwh": NUM, "hours": int, "avg_w": int, "min_w": int, "max_w": int}
    get("/power/history", {"week": hist, "day": hist, "uptime_s": int})
    get("/updates", {"count": int, "packages": L({"name": str})})
    acts = get("/actions", {"actions": L({"key": str, "label": str, "desc": str}, 1)})
    if acts:
        r = post("/action/" + acts["actions"][0]["key"], {}, {"output": str})
    post("/action/nope", {}, status=404)
    tn = get("/tailnet", {"devices": L({"name": str, "online": bool, "os": str, "ip": str,
                                        "self": bool, "last_seen": N(str)}, 1)})
    ok(tn and sum(1 for d in tn["devices"] if d["self"]) == 1, "exactly one tailnet device should be self")


def test_machines_and_wake():
    print("machines, power, wake")
    mshape = {"name": str, "role": str, "online": bool}
    d = get("/machines", {"machines": L(mshape, 3)})
    ms = d["machines"] if d else []
    atlas = find(ms, name="atlas")
    if ok(atlas is not None, "atlas missing"):
        shape(atlas, {"load_pct": NUM, "cores": int, "mem_pct": NUM, "mem_used_mb": int,
                      "mem_total_mb": int, "disk_pct": NUM, "disk_used_gb": int, "disk_total_gb": int,
                      "temp_c": N(NUM), "watts": int, "on_battery": bool, "uptime_s": int,
                      "gpu": {"name": str, "util_pct": int, "mem_used_mb": int,
                              "mem_total_mb": int, "temp_c": int},
                      "kwh_day": N(NUM), "kwh_week": N(NUM), "kwh_month": N(NUM),
                      "avg_w_day": int, "updates": int, "updatable": bool}, "atlas")
    ok(find(ms, name="pi") is not None, "a machine named 'pi' is needed for the AdGuard detail pane")
    vault = find(ms, name="vault")
    ok(vault is not None and not vault["online"], "vault should start offline")
    pm = get("/power/machines", {"machines": L({"name": str, "self": bool, "online": bool,
                                                "mac": str, "actions": L(str)}, 3)})
    v = find(pm["machines"], name="vault") if pm else None
    ok(v and "wake" in v["actions"] and v["mac"], "vault should be wakeable with a MAC")
    post("/power/wake", {"name": "vault"})
    ok(wait_for(lambda: find(call("GET", "/machines")["machines"], name="vault")["online"], 20),
       "vault should be online a few seconds after wake")
    vault = find(call("GET", "/machines")["machines"], name="vault")
    shape(vault, {"load_pct": NUM, "uptime_s": int}, "vault online")
    post("/power/wake", {"name": "nope"}, status=404)
    post("/power/self", {"action": "suspend"}, {"ok": bool})
    post("/power/self", {"action": "explode"}, status=400)
    sc = get("/power/scan", {"hosts": L({"ip": str, "mac": str, "hostname": str}, 1)})
    if sc:
        h = sc["hosts"][0]
        post("/power/assign", {"name": "nuc", "mac": h["mac"], "lan_ip": h["ip"]}, {"ok": bool})
    post("/machines/pi/sudo", {"password": "x"}, {"ok": bool})
    post("/machines/nuc/update", {}, {"ok": bool})
    nuc = find(call("GET", "/machines")["machines"], name="nuc")
    ok(nuc and nuc["updates"] == 0, "nuc updates should drop to 0 after update")


def test_alerts_events():
    print("alerts and events")
    ashape = {"key": str, "title": str, "text": str, "critical": bool, "ts": NUM, "acknowledged": bool}
    d = get("/alerts/active", {"alerts": L(ashape, 1), "unacked": int})
    if not d:
        return
    before = d["unacked"]
    target = find(d["alerts"], key="disk:nuc")
    ok(target is not None and not target["acknowledged"], "seeded disk:nuc alert should be unacknowledged")
    post("/alerts/ack", {"key": "disk:nuc", "ack": True}, {"ok": bool})
    d2 = call("GET", "/alerts/active")
    t2 = find(d2["alerts"], key="disk:nuc")
    ok(t2 and t2["acknowledged"], "alert should be acknowledged")
    ok(d2["unacked"] == before - 1, f"unacked should drop by one ({before} -> {d2['unacked']})")
    post("/alerts/ack", {"key": "disk:nuc", "ack": False})
    t3 = find(call("GET", "/alerts/active")["alerts"], key="disk:nuc")
    ok(t3 and not t3["acknowledged"], "ack=false should restore the alert")
    post("/alerts/ack", {"key": "disk:nuc", "ack": True})
    post("/alerts/ack", {"key": "nope", "ack": True}, status=404)

    evs = get("/events?limit=200", {"events": L({"ts": NUM, "type": str, "text": str, "device": str}, 5)})
    if evs:
        ts = [e["ts"] for e in evs["events"]]
        ok(ts == sorted(ts), "events should be oldest first")
        n = len(evs["events"])
        post("/event", {"device": "smoke", "type": "smoke-test", "text": "hello from the smoke test"})
        evs2 = call("GET", "/events?limit=500")
        ok(len(evs2["events"]) > n or n >= 200, "events list should grow after POST /event")
        ok(find(evs2["events"], type="smoke-test") is not None, "posted event should show up")
        ok(find(evs2["events"], type="action") is not None, "actions should have logged events")
    lim = call("GET", "/events?limit=3")
    ok(lim and len(lim["events"]) == 3, "events limit should be honoured")
    get("/todo", {"open": int})
    get("/wan", {"up": bool, "drops_24h": int, "drops_7d": int,
                 "outages": L({"start": NUM, "secs": int})})


def test_extras():
    print("adguard, backup, links, home, zigbee, usage")
    ag = {"queries": int, "blocked": int, "blocked_pct": NUM, "rules": int, "avg_ms": NUM,
          "protection": bool, "top_clients": L({"name": str, "count": int}),
          "top_blocked": L({"name": str, "count": int})}
    get("/adguard", ag)
    post("/adguard/protection", {"enabled": False}, {"ok": bool})
    ok(call("GET", "/adguard")["protection"] is False, "protection should be off after toggle")
    post("/adguard/protection", {"enabled": True})

    bshape = {"configured": bool, "running": bool, "ok": bool, "age_hours": N(NUM),
              "snapshot_count": int, "snapshot_bytes": int, "dest_avail_bytes": int}
    b = get("/backup", bshape)
    post("/backup/run", {}, {"ok": bool})
    ok(call("GET", "/backup")["running"] is True, "backup should be running after /backup/run")
    post("/backup/run", {}, status=409)

    get("/links", {"groups": L({"name": str, "links": L({"name": str, "url": str, "desc": str,
                                                        "live": N(bool)}, 1)}, 1)})
    hm = get("/home", {"entities": L({"label": str, "entity_id": str, "kind": str, "state": str,
                                      "unit": str, "controllable": bool}, 3)})
    if hm:
        sw = find(hm["entities"], kind="switch", controllable=True)
        new = "off" if sw["state"] == "on" else "on"
        post("/home/act", {"entity_id": sw["entity_id"], "action": new}, {"ok": bool})
        ok(find(call("GET", "/home")["entities"], entity_id=sw["entity_id"])["state"] == new,
           "switch should change state")
        cv = find(hm["entities"], kind="cover")
        post("/home/act", {"entity_id": cv["entity_id"], "action": "open"})
        ro = find(hm["entities"], controllable=False)
        post("/home/act", {"entity_id": ro["entity_id"], "action": "on"}, status=400)
    get("/zigbee", {"permit_join": bool, "devices": L({"name": str, "vendor": str, "model": str})})
    post("/zigbee/permit", {"on": True}, {"ok": bool})
    ok(call("GET", "/zigbee")["permit_join"] is True, "zigbee pairing should open")
    post("/zigbee/permit", {"on": False})

    src = {"week": {"in": int, "out": int, "cache": int}, "today": {"in": int, "out": int},
           "models": {}}
    u = get("/usage", {"sources": {"claude": src, "codex": src, "agent": src}})
    if u:
        for k, s in u["sources"].items():
            for m, v in s["models"].items():
                shape(v, {"out": int}, f"usage {k} model {m}")

    cs = get("/claude/sessions", {"sessions": L({"pid": int, "cwd": str, "age_s": int,
                                                 "tmux": N(str), "yolo": bool})})
    get("/tmux/sessions", {"sessions": L({"name": str, "windows": int, "attached": bool}, 1)})


def test_terminal():
    print("terminal")
    d = get("/term/sessions", {"sessions": L({"name": str}, 2)})
    name = d["sessions"][0]["name"]
    q = urllib.parse.quote(name)
    sc = get(f"/term/screen2?ansi=true&session={q}", {"screen": str})
    ok(sc and "\x1b[" in sc["screen"], "screen should contain ANSI colour codes")
    plain = call("GET", f"/term/screen2?ansi=false&session={q}")
    ok(plain and "\x1b[" not in plain["screen"], "ansi=false should strip colour codes")
    post("/term/keys", {"session": name, "text": "echo smoke-ok", "key": "", "enter": True}, {"ok": bool})
    ok("smoke-ok" in call("GET", f"/term/screen2?ansi=true&session={q}")["screen"],
       "typed text should be echoed into the screen")
    post("/term/keys", {"session": name, "text": "who", "key": "", "enter": False})
    post("/term/keys", {"session": name, "text": "", "key": "Enter", "enter": True})
    post("/term/keys", {"session": name, "text": "", "key": "C-c", "enter": False})
    post("/term/keys", {"session": "nope", "text": "x", "key": "", "enter": True}, status=404)
    post("/term/resize", {"session": name, "cols": 82, "rows": 46}, {"ok": bool})
    post("/term/autosize", {"session": name}, {"ok": bool})
    post("/term/new", {"name": "smoke test", "cwd": "~", "cmd": "echo hi"}, {"ok": bool})
    names = [s["name"] for s in call("GET", "/term/sessions")["sessions"]]
    ok("smoke-test" in names, "new session should appear with spaces turned into dashes")
    code, _, _ = request("DELETE", "/term/smoke-test")
    ok(code == 200, f"DELETE /term/smoke-test gave {code}")
    names = [s["name"] for s in call("GET", "/term/sessions")["sessions"]]
    ok("smoke-test" not in names, "killed session should be gone")
    code, _, _ = request("DELETE", "/term/smoke-test")
    ok(code == 404, "deleting a missing session should be 404")


def test_commands():
    print("commands, versions, phone")
    post("/command", {"action": "notify", "args": {"title": "Hi", "text": "from smoke"}}, {"ok": bool})
    c = get("/commands/controlcenter", {"commands": L({"action": str, "args": dict}, 1)})
    if c:
        n = find(c["commands"], action="notify")
        ok(n and n["args"].get("title") == "Hi", "queued notify command should come back")
    c2 = call("GET", "/commands/controlcenter")
    ok(c2 and c2["commands"] == [], "command queue should drain")
    post("/versions", {"device": "controlcenter", "apps": {"app.controlcenter": {"code": 1, "name": "1"}}},
         {"ok": bool})
    code, _, _ = request("GET", "/phone/version.json")
    ok(code == 404, "demo should not serve /phone/version.json")
    get("/apps/managed", {"apps": L({"name": str})})
    for gone in ("/archive/anything", "/access/pending", "/access/devices"):
        code, _, _ = request("GET", gone)
        ok(code == 404, f"{gone} belonged to a removed screen and should be gone")
    code, _, _ = request("POST", "/access/approve/x", body={})
    ok(code in (404, 405), "POST /access/approve/x should be gone")


def test_jellyfin():
    print("jellyfin")
    item = {"id": str, "name": str, "type": str, "year": int, "overview": str,
            "progress": NUM, "played": bool, "runtime_min": int, "stream": str}
    get("/jellyfin/status", {"movies": int, "series": int,
                             "active": L({"name": str, "device": str, "user": str, "type": str})})
    lt = get("/jellyfin/latest?limit=12", {"items": L(item, 3)})
    rs = get("/jellyfin/resume?limit=8", {"items": L(item, 1)})
    if rs:
        ok(all(0 < i["progress"] <= 100 for i in rs["items"]), "resume progress is a 0 to 100 percentage")
    s = get("/jellyfin/search?term=harbor", {"items": L(item, 1)})
    ok(s and any("Harbor" in i["name"] for i in s["items"]), "search should find Quiet Harbor")
    get("/jellyfin/search?term=zzzzzz", {"items": L(item)})
    if lt:
        code, hdrs, raw = request("GET", "/jellyfin/thumb/" + lt["items"][0]["id"])
        ok(code == 200 and hdrs.get("Content-Type") == "image/png" and raw[:8] == b"\x89PNG\r\n\x1a\n",
           "thumb should be a PNG")
    libs = get("/jellyfin/libraries", {"libraries": L({"id": str, "name": str, "count": int}, 2)})
    for lib in (libs or {"libraries": []})["libraries"]:
        d = get(f"/jellyfin/items?library={lib['id']}", {"items": L(item, 1)})
        ok(d and len(d["items"]) == lib["count"], f"library {lib['id']} count should match its items")
    get("/jellyfin/items?library=nope", {}, status=404)
    test_stream(lt["items"] if lt else [])


def test_stream(items):
    ok(items and all(i["stream"] == f"/jellyfin/stream/{i['id']}" for i in items),
       "item stream should point at /jellyfin/stream/<id>")
    path = items[0]["stream"] if items else "/jellyfin/stream/x"
    code, hdrs, full = request("GET", path)
    ok(code == 200 and hdrs.get("Content-Type") == "video/mp4" and hdrs.get("Accept-Ranges") == "bytes",
       "stream should be video/mp4 and advertise ranges")
    size = len(full)
    ok(size > 10_000 and full[4:8] == b"ftyp", "stream should be an MP4 file")
    ok(hdrs.get("Content-Length") == str(size), "full response needs a Content-Length")
    code, hdrs, part = request("GET", path, headers={"Range": "bytes=0-99"})
    ok(code == 206 and part == full[:100], f"range 0-99 should give 206 and the first 100 bytes, got {code}")
    ok(hdrs.get("Content-Range") == f"bytes 0-99/{size}" and hdrs.get("Content-Length") == "100",
       "206 needs Content-Range and Content-Length")
    code, hdrs, part = request("GET", path, headers={"Range": "bytes=1000-"})
    ok(code == 206 and part == full[1000:] and hdrs.get("Content-Range") == f"bytes 1000-{size - 1}/{size}",
       "open-ended range should run to the end")
    code, hdrs, part = request("GET", path, headers={"Range": "bytes=-50"})
    ok(code == 206 and part == full[-50:], "suffix range should give the last bytes")
    code, hdrs, part = request("GET", path, headers={"Range": f"bytes={size + 5}-"})
    ok(code == 416 and hdrs.get("Content-Range") == f"bytes */{size}", "range past the end should be 416")
    code, hdrs, body = request("HEAD", path)
    ok(code == 200 and body == b"" and hdrs.get("Content-Length") == str(size), "HEAD should give length without a body")
    get("/jellyfin/stream/nope", {}, status=404)


def test_demo_files():
    print("demo files page")
    code, _, raw = request("GET", "/demo/files")
    ok(code == 200 and b"<ul>" in raw, "demo files page should be HTML")


# Block vocabulary the app renders. Every field listed here is read tolerantly by
# the app; the checks below only make sure this bridge sticks to the contract.
TONES = ("ok", "warn", "bad", "muted")
ICONS = {"github", "code", "build", "map", "game", "star", "terminal", "cloud",
         "chart", "bolt", "list", "info", "home"}


def check_actions(acts, where):
    shape(acts, L({"id": str, "label": str}), where)
    for a in acts if isinstance(acts, list) else []:
        ok(a.get("style", "default") in ("primary", "default", "danger"), f"{where}: bad style {a.get('style')!r}")
        ok(isinstance(a.get("confirm", ""), str), f"{where}: confirm must be a string")
        if "input" in a:
            shape(a["input"], {"label": str, "placeholder": str}, where + ".input")


def check_block(b, where):
    if not ok(isinstance(b, dict) and isinstance(b.get("type"), str), f"{where}: block needs a type"):
        return
    t = b["type"]
    if t == "stats":
        shape(b, {"tiles": L({"label": str, "value": str, "tone": str}, 1)}, where)
        for x in b.get("tiles", []):
            ok(x["tone"] in TONES, f"{where}: bad tone {x['tone']!r}")
    elif t == "list":
        shape(b, {"items": L({"id": str, "title": str, "subtitle": str, "meta": str, "tone": str})}, where)
        for i, it in enumerate(b.get("items", [])):
            w = f"{where}.items[{i}]"
            ok(it["tone"] in TONES, f"{w}: bad tone")
            if "chips" in it:
                shape(it["chips"], L({"text": str, "tone": str}), w + ".chips")
            if "progress" in it:
                ok(isinstance(it["progress"], int) and 0 <= it["progress"] <= 100, f"{w}: progress 0 to 100")
            if "actions" in it:
                check_actions(it["actions"], w + ".actions")
    elif t == "progress":
        shape(b, {"label": str, "value": NUM, "tone": str}, where)
        ok(0 <= b.get("value", -1) <= 100, f"{where}: value 0 to 100")
    elif t == "text":
        shape(b, {"text": str}, where)
        ok(isinstance(b.get("mono", False), bool), f"{where}: mono must be a bool")
    elif t == "chips":
        shape(b, {"chips": L({"text": str, "tone": str})}, where)
    elif t == "actions":
        shape(b, {"actions": L({"id": str, "label": str}, 1)}, where)
        check_actions(b.get("actions", []), where + ".actions")
    elif t == "link":
        shape(b, {"label": str, "url": str}, where)
        ok(b.get("url", "").startswith(("/", "http://", "https://")), f"{where}: url must be absolute or start with /")
    else:
        fail(f"{where}: unknown block type {t!r}")


def ext_screen(eid):
    d = get(f"/extensions/{eid}", {"id": str, "name": str, "refresh_s": int,
                                   "sections": L({"title": str, "blocks": L(dict)}, 1)})
    for si, sec in enumerate((d or {}).get("sections", [])):
        for bi, b in enumerate(sec["blocks"]):
            check_block(b, f"{eid}.sections[{si}].blocks[{bi}]")
    return d


def ext_rows(d, section_title):
    """All list rows of the section whose title starts with section_title."""
    out = []
    for sec in (d or {}).get("sections", []):
        if sec["title"].startswith(section_title):
            for b in sec["blocks"]:
                if b["type"] == "list":
                    out += b["items"]
    return out


def ext_do(eid, action, item=None, text=None, expect_ok=True):
    body = {"action": action}
    if item is not None:
        body["item"] = item
    if text is not None:
        body["input"] = text
    r = post(f"/extensions/{eid}/action", body, {"ok": bool, "refresh": bool})
    if r:
        ok(r["ok"] == expect_ok, f"{eid}/{action}: ok should be {expect_ok}, got {r}")
        ok(isinstance(r.get("message", ""), str), f"{eid}/{action}: message must be a string")
    return r


def test_extensions():
    print("extensions")
    d = get("/extensions", {"extensions": L({"id": str, "name": str, "icon": str, "summary": str,
                                             "tone": str}, 1)})
    ids = [e["id"] for e in d["extensions"]] if d else []
    for e in (d or {}).get("extensions", []):
        ok(e["icon"] in ICONS, f"{e['id']}: icon {e['icon']!r} is not in the vocabulary")
        ok(e["tone"] in TONES, f"{e['id']}: bad tone")
        ok(e.get("badge") is None or isinstance(e["badge"], int), f"{e['id']}: badge must be int or null")
    for want in ("github", "vscode", "forge", "pogo", "deadlock", "hello", "uptime", "paperless", "syncthing",
                 "pihole", "wireguard", "raspi", "board", "sessions", "helm", "vibe", "apps"):
        ok(want in ids, f"extension {want} should be served")
        ext_screen(want)
    groups = [e.get("group") for e in d["extensions"]]
    ok(all(isinstance(g, str) and g for g in groups), "every demo extension should have a non-empty group")
    first = list(dict.fromkeys(groups))
    ok(all(groups.index(g) <= len(groups) - groups[::-1].index(g) - 1 for g in first)
       and [g for i, g in enumerate(groups) if i == 0 or g != groups[i - 1]] == first,
       f"groups should be contiguous in the list: {groups}")
    get("/extensions/nope", {}, status=404)
    post("/extensions/nope/action", {"action": "x"}, status=404)
    post("/extensions/hello/action", {}, status=400)
    post("/extensions/hello/action", {"action": "nope"}, status=404)

    ok(ids[3:7] == ["board", "sessions", "helm", "vibe"] and groups[3:7] == ["Agents"] * 4,
       "the Agents group should follow Development")
    test_homelab_apps()     # first, because Paperless and Syncthing have timers that start at the first read
    test_network_and_pi()
    test_agents()

    # hello: text input round trip
    r = ext_do("hello", "greet", text="Ada")
    ok(r and "Ada" in r.get("message", ""), "hello should greet by name")
    ok("1 time" in call("GET", "/extensions")["extensions"][ids.index("hello")]["summary"], "hello counter in summary")

    # github: merge an approved PR (only approved ones offer it), re-run a failed run, a run finishes
    gh = ext_screen("github")
    prs = ext_rows(gh, "Open pull")
    mergeable = [p for p in prs if any(a["id"] == "merge" for a in p.get("actions", []))]
    ok(len(mergeable) >= 1 and all(any(c["text"] == "approved" for c in p["chips"]) for p in mergeable),
       "only approved PRs should offer Merge")
    ext_do("github", "merge", item="pr-215", expect_ok=False)    # not approved
    ext_do("github", "merge", item=mergeable[0]["id"])
    ok(find(ext_rows(ext_screen("github"), "Open pull"), id=mergeable[0]["id"]) is None, "merged PR should disappear")
    runs = ext_rows(gh, "Recent workflow")
    failed = [x for x in runs if any(a["id"] == "rerun" for a in x.get("actions", []))]
    ok(len(failed) == 1, "exactly one failed run should offer Re-run")
    ext_do("github", "rerun", item=failed[0]["id"])
    now_row = find(ext_rows(call("GET", "/extensions/github"), "Recent workflow"), id=failed[0]["id"])
    ok(now_row and "progress" in now_row, "re-run should show as running")
    ext_do("github", "rerun", item="run-3041", expect_ok=False)
    rid = failed[0]["id"]
    ok(wait_for(lambda: "progress" not in find(ext_rows(call("GET", "/extensions/github"), "Recent workflow"), id=rid), 20),
       "the re-run should finish by itself")
    ok(find(ext_rows(call("GET", "/extensions/github"), "Recent workflow"), id=rid)["tone"] == "ok", "finished run should be ok")

    # vscode: tunnels, offline machine refuses, link page served
    vs = ext_screen("vscode")
    ses = ext_rows(vs, "Remote")
    up = [x for x in ses if x["tone"] == "ok"][0]
    ext_do("vscode", "stop_tunnel", item=up["id"])
    ok(find(ext_rows(call("GET", "/extensions/vscode"), "Remote"), id=up["id"])["tone"] == "muted", "tunnel should be down")
    ext_do("vscode", "start_tunnel", item=up["id"])
    ok(find(ext_rows(call("GET", "/extensions/vscode"), "Remote"), id=up["id"])["tone"] == "ok", "tunnel should be up again")
    vault_up = find(call("GET", "/machines").get("machines", []), name="vault")
    if vault_up and not vault_up.get("online"):         # the NAS is asleep unless an earlier test woke it
        ext_do("vscode", "start_tunnel", item="ses-backup", expect_ok=False)
    links = [b for sec in vs["sections"] for b in sec["blocks"] if b["type"] == "link"]
    ok(len(links) == 1, "vscode should have one link block")
    code, hdrs, raw = request("GET", links[0]["url"])
    ok(code == 200 and "text/html" in hdrs.get("Content-Type", ""), "vscode link should serve an HTML page")

    # forge: create from a description, watch the job to completion, open the preview
    ext_do("forge", "create", text="", expect_ok=False)
    ext_do("forge", "create", text="A shopping list that works offline")
    fg = ext_screen("forge")
    blocks = [b for sec in fg["sections"] for b in sec["blocks"]]
    prog = [b for b in blocks if b["type"] == "progress"]
    logs = [b for b in blocks if b["type"] == "text" and b.get("mono") and "[agent]" in b["text"]]
    ok(len(prog) == 1 and len(logs) == 1, "a running build should show a progress block and a mono log")
    ext_do("forge", "create", text="Another one", expect_ok=False)            # one job at a time
    first_log = len(logs[0]["text"]) if logs else 0
    time.sleep(6)
    fg2 = call("GET", "/extensions/forge")
    logs2 = [b for sec in fg2["sections"] for b in sec["blocks"] if b["type"] == "text" and b.get("mono") and "[agent]" in b["text"]]
    ok(logs2 and len(logs2[0]["text"]) > first_log, "the build log should grow while the job runs")
    def forge_done():
        blocks = [b for sec in call("GET", "/extensions/forge")["sections"] for b in sec["blocks"]]
        return not any(b["type"] == "progress" for b in blocks)
    ok(wait_for(forge_done, 25), "the forge job should finish")
    fg3 = call("GET", "/extensions/forge")
    apps = ext_rows(fg3, "Apps")
    ok(len(apps) == 4 and apps[0]["title"] == "Shopping list", f"new app should lead the list: {[a['title'] for a in apps]}")
    ok(apps[0]["chips"][0]["text"] == "ready", "finished app should be ready")
    prev = [b for sec in fg3["sections"] for b in sec["blocks"] if b["type"] == "link" and apps[0]["title"] in b["label"]]
    ok(len(prev) == 1, "finished app should offer a preview link")
    if prev:
        code, hdrs, raw = request("GET", prev[0]["url"])
        ok(code == 200 and b"Shopping" in raw, "preview page should be served")
    ext_do("forge", "retry", item="app-plant-waterer")
    ok(wait_for(forge_done, 25), "the retried job should finish")
    ext_do("forge", "delete", item=apps[0]["id"])
    ok(find(ext_rows(call("GET", "/extensions/forge"), "Apps"), id=apps[0]["id"]) is None, "deleted app should disappear")
    get("/demo/forge/app-nope", {}, status=404)

    # pogo: information only, no actions at all
    pg = ext_screen("pogo")
    blocks = [b for sec in pg["sections"] for b in sec["blocks"]]
    ok(not any(b["type"] == "actions" for b in blocks), "pogo should have no action blocks")
    ok(not any("actions" in r for r in ext_rows(pg, "")), "pogo rows should have no actions")
    ok(len(ext_rows(pg, "Five-star")) >= 3 and len(ext_rows(pg, "Happening now")) >= 1, "pogo should list bosses and live events")
    ok(any("sample" in b.get("text", "").lower() or "made-up" in b.get("text", "").lower() for b in blocks), "pogo should say it is sample data")

    # deadlock: overlay start and stop gate the picks
    dl = ext_screen("deadlock")
    ok(len(ext_rows(dl, "Suggested")) == 0, "no picks while the overlay is stopped")
    ext_do("deadlock", "start")
    dl = ext_screen("deadlock")
    picks = ext_rows(dl, "Suggested")
    ok(len(picks) >= 3 and all(any("win" in c["text"] for c in p["chips"]) for p in picks), "picks need a win-rate chip")
    ok(len(ext_rows(dl, "Recent matches")) >= 3, "deadlock should list matches")
    ext_do("deadlock", "stop")
    ok(len(ext_rows(ext_screen("deadlock"), "Suggested")) == 0, "picks should hide again after stop")



def texts(d, title_start):
    return [b["text"] for sec in (d or {}).get("sections", []) if sec["title"].startswith(title_start)
            for b in sec["blocks"] if b["type"] == "text"]


def tile(d, label):
    return [t for sec in d["sections"] for b in sec["blocks"] if b["type"] == "stats" for t in b["tiles"]
            if t["label"] == label][0]["value"]


def test_agents():
    print("agents and apps extensions")
    summ = {e["id"]: e for e in call("GET", "/extensions")["extensions"]}
    for eid in ("board", "sessions", "helm", "vibe"):
        ok(summ[eid]["group"] == "Agents", f"{eid} belongs to the Agents group")
    ok(summ["apps"]["group"] == "Apps", "apps is in the Apps group")

    # board
    bd = ext_screen("board")
    tasks = ext_rows(bd, "Tasks")
    ok(len(tasks) >= 4 and all(find(tasks, id=t["id"]) for t in tasks), "board lists tasks")
    ok(all(any(c["text"] in ("builder", "reviewer", "docs") for c in t["chips"]) for t in tasks), "each task has an agent chip")
    ok(all(any(c["text"] in ("question", "proposal", "result") for c in t["chips"]) for t in tasks), "each task has a kind chip")
    ok(tile(bd, "Waiting on you") == "2" and tile(bd, "Open") == "3", "board stats at the start")
    ok(any(t["meta"].endswith("new") for t in tasks), "unread counts show as meta")
    prop = find(tasks, id="t-theme")
    ok([a["id"] for a in prop["actions"]] == ["reply", "approve", "done"], "a proposal can be replied to, approved and closed")
    ok("approve" not in [a["id"] for a in find(tasks, id="t-sync")["actions"]], "a question cannot be approved")
    ok(find(tasks, id="t-lint")["tone"] == "muted" and "actions" not in find(tasks, id="t-lint"), "a closed task has no actions")
    ext_do("board", "reply", item="t-sync", text="Skip it and log it")
    ext_do("board", "reply", item="t-sync", text="", expect_ok=False)
    ext_do("board", "reply", item="nope", text="hi", expect_ok=False)
    bd2 = call("GET", "/extensions/board")
    t1 = find(ext_rows(bd2, "Tasks"), id="t-sync")
    ok(t1["meta"] != "1 new" and not t1["meta"].endswith("new"), "reply clears the unread count")
    ok(any("Skip it and log it" in x for x in texts(bd2, "Thread")), "the reply is in the thread")
    ext_do("board", "approve", item="t-theme")
    ext_do("board", "approve", item="t-sync", expect_ok=False)
    ok(find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-theme")["tone"] == "muted"
       and "progress" in find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-theme"), "an approved proposal is in progress")
    ext_do("board", "done", item="t-docs")
    ok(tile(call("GET", "/extensions/board"), "Done today") == "3", "closing a task counts as done today")
    ext_do("board", "done", item="t-docs", expect_ok=False)

    # sessions
    ss = ext_screen("sessions")
    rows = ext_rows(ss, "Sessions")
    ok(len(rows) == 3 and all("progress" in r for r in rows), "sessions carry a context progress")
    ok([find(rows, id=i)["tone"] for i in ("ses-1", "ses-2", "ses-3")] == ["bad", "warn", "ok"], "context tone warms up as it fills")
    p0 = find(rows, id="ses-3")["progress"]
    acts = {a["id"]: a for a in find(rows, id="ses-1")["actions"]}
    ok(acts["handoff"].get("confirm") and acts["stop"].get("confirm"), "hand off and stop ask first")
    ext_do("sessions", "handoff", item="ses-1")
    ext_do("sessions", "handoff", item="ses-1", expect_ok=False)
    ss2 = call("GET", "/extensions/sessions")
    ok(any("Handoff from builder-1" in x for x in texts(ss2, "Handoff note")), "the handoff note is shown")
    ext_do("sessions", "stop", item="ses-2")
    ok(find(ext_rows(call("GET", "/extensions/sessions"), "Sessions"), id="ses-2") is None, "a stopped session disappears")
    ext_do("sessions", "stop", item="ses-2", expect_ok=False)

    # helm
    hm = ext_screen("helm")
    pr = ext_rows(hm, "Projects")
    ok(len(pr) >= 3 and all("progress" in r and r["chips"] for r in pr), "helm lists projects with milestone chips")
    ok({r["tone"] for r in pr} >= {"ok", "warn", "bad"}, "project status dots vary")
    b0 = find(pr, id="p-bridge")
    ext_do("helm", "advance", item="p-bridge")
    b1 = find(ext_rows(call("GET", "/extensions/helm"), "Projects"), id="p-bridge")
    ok(b1["progress"] == 0 and b1["meta"] != b0["meta"], "advancing resets the progress and moves the milestone")
    ok(sum(c["tone"] == "ok" for c in b1["chips"]) == 3, "one more milestone is done")
    ext_do("helm", "note", item="p-bridge", text="Release candidate cut")
    ok(find(ext_rows(call("GET", "/extensions/helm"), "Projects"), id="p-bridge")["subtitle"] == "Release candidate cut", "note replaces the state line")
    ext_do("helm", "note", item="p-bridge", text="", expect_ok=False)
    ext_do("helm", "advance", item="p-bridge")
    ext_do("helm", "advance", item="p-bridge", expect_ok=False)      # all done now
    ext_do("helm", "advance", item="nope", expect_ok=False)

    # vibe: the whole flow, with the board and sessions timers running alongside
    vb = ext_screen("vibe")
    ok(len(ext_rows(vb, "Pull requests")) == 1, "vibe starts with one pull request")
    ok("input" in [a for b in vb["sections"][0]["blocks"] if b["type"] == "actions" for a in b["actions"]][0], "propose takes text")
    ext_do("vibe", "propose", text="", expect_ok=False)
    ext_do("vibe", "propose", text="Add a compact mode to the energy card")
    vb = call("GET", "/extensions/vibe")
    props = [r for sec in vb["sections"] if sec["title"].startswith("Proposal") for b in sec["blocks"] if b["type"] == "list" for r in b["items"]]
    ok(len(props) == 1 and texts(vb, "Proposal")[0].startswith("Plan for"), "a proposal with a plan appears")
    ext_do("vibe", "approve", item="chg-nope", expect_ok=False)
    ext_do("vibe", "approve", item=props[0]["id"])
    vb = call("GET", "/extensions/vibe")
    ok(any(b["type"] == "progress" for sec in vb["sections"] if sec["title"].startswith("Working") for b in sec["blocks"]), "approved work shows a progress bar")
    ext_do("vibe", "propose", text="Another idea")
    ext_do("vibe", "approve", item="chg-2", expect_ok=False)      # one agent at a time
    ext_do("vibe", "discard", item="chg-2")
    ext_do("vibe", "discard", item="chg-2", expect_ok=False)
    # apps: start an update, then find another one
    ap = ext_screen("apps")
    arows = ext_rows(ap, "Apps")
    ok(len(arows) == 3 and sum(1 for r in arows if "update available" in [c["text"] for c in r["chips"]]) == 1, "one app has an update at first")
    ok(find(arows, id="notes")["subtitle"] == "installed 1.4.2, available 1.5.0" and find(arows, id="notes")["meta"].endswith("MB"), "versions and size are shown")
    ext_do("apps", "update", item="notes")
    ext_do("apps", "update", item="notes", expect_ok=False)
    ext_do("apps", "update", item="reps", expect_ok=False)            # already current
    ok("progress" in find(ext_rows(call("GET", "/extensions/apps"), "Apps"), id="notes"), "an update shows progress")
    # board follow-up (4 s) and finished approval (6 s)
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-sync")["meta"] == "1 new", 10),
       "the agent posts a follow-up after a reply")
    ok(find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-sync")["subtitle"].startswith("builder: "),
       "the follow-up is the latest message of the task")
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-theme")["chips"][-1]["text"] == "done", 15),
       "an approved proposal finishes by itself")
    done = find(ext_rows(call("GET", "/extensions/board"), "Tasks"), id="t-theme")
    ok("Done." in done["subtitle"] and "actions" not in done, "the finished proposal shows a result line and no buttons")
    ok(tile(call("GET", "/extensions/board"), "Done today") == "4", "the finished proposal counts as done today")
    # sessions: the fresh session replaces the old one
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/sessions"), "Sessions"), id="ses-1") is None, 15), "the handed off session is replaced")
    fresh = [r for r in ext_rows(call("GET", "/extensions/sessions"), "Sessions") if r["title"] == "builder-2"]
    ok(len(fresh) == 1 and fresh[0]["progress"] <= 10 and fresh[0]["tone"] == "ok", "the fresh session starts with low context")
    p1 = find(ext_rows(call("GET", "/extensions/sessions"), "Sessions"), id="ses-3")["progress"]
    ok(p1 >= p0, "context usage never goes down for a running session")
    # apps update finished
    ok(wait_for(lambda: "progress" not in find(ext_rows(call("GET", "/extensions/apps"), "Apps"), id="notes"), 10), "the app update finishes")
    n = find(ext_rows(call("GET", "/extensions/apps"), "Apps"), id="notes")
    ok(n["subtitle"] == "installed 1.5.0" and n["chips"][0]["text"] == "up to date", "the new version is shown after the update")
    ext_do("apps", "check")
    ap = call("GET", "/extensions/apps")
    ok(sum(1 for r in ext_rows(ap, "Apps") if r["chips"][0]["text"] == "update available") == 1, "checking finds one more update")
    ext_do("apps", "check")
    ext_do("apps", "check")
    ap = call("GET", "/extensions/apps")
    ok(sum(1 for r in ext_rows(ap, "Apps") if r["chips"][0]["text"] == "update available") == 3, "every app can be behind")
    r = ext_do("apps", "check")
    ok(r and "No new" in r["message"], "nothing new once all apps are behind")
    ext_do("apps", "update", item="recipes")
    post("/extensions/apps/action", {"action": "nope"}, status=404)
    for eid in ("board", "sessions", "helm", "vibe"):
        post(f"/extensions/{eid}/action", {"action": "nope"}, status=404)

    # vibe: the work finishes (9 s from approval) and opens a pull request
    ok(wait_for(lambda: len(ext_rows(call("GET", "/extensions/vibe"), "Pull requests")) == 2, 20), "finished work opens a pull request")
    prs = ext_rows(call("GET", "/extensions/vibe"), "Pull requests")
    new = prs[0]
    ok(new["id"] == "pr-53" and new["subtitle"].endswith("#53") and new["actions"][0]["id"] == "build", "the new pull request has Test build")
    ok(new["subtitle"].startswith("fernwood/dashboard-ui"), "the repository is picked from the description")
    ext_do("vibe", "build", item="pr-53")
    ext_do("vibe", "build", item="pr-52", expect_ok=False)            # one build at a time
    ext_do("vibe", "build", item="pr-53", expect_ok=False)
    vb = call("GET", "/extensions/vibe")
    ok(any(b["type"] == "progress" for sec in vb["sections"] if sec["title"].startswith("Test build") for b in sec["blocks"]), "the build shows progress")
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/vibe"), "Pull requests"), id="pr-53")["chips"][0]["text"] == "build installed", 15),
       "the build ends in build installed")
    ok("build installed" in " ".join(texts(call("GET", "/extensions/vibe"), "Test build")), "the build log ends with build installed")
    ext_do("vibe", "build", item="pr-52")
    ext_do("vibe", "build", item="pr-nope", expect_ok=False)
    ext_do("vibe", "approve", item="nope", expect_ok=False)
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/apps"), "Apps"), id="recipes")["chips"][0]["text"] == "up to date", 10), "the last update finishes")


def test_homelab_apps():
    print("homelab extensions")
    summ = {e["id"]: e for e in call("GET", "/extensions")["extensions"]}
    ok(summ["uptime"]["group"] == "Monitoring"
       and summ["paperless"]["group"] == "Files" and summ["pogo"]["group"] == "Games", "groups should be set")

    # paperless and syncthing start their timers at the first read, so look at their first state before the slow checks
    pl = ext_screen("paperless")
    ok(len(ext_rows(pl, "Consume")) == 1 and "progress" in ext_rows(pl, "Consume")[0], "a document is being consumed")
    n_in = len(ext_rows(pl, "Inbox"))
    sy = ext_screen("syncthing")
    fol = ext_rows(sy, "Folders")
    ok(any("progress" in f for f in fol) and any(f["tone"] == "bad" for f in fol), "a syncing and an out of sync folder")
    ok(len(ext_rows(sy, "Connected")) >= 2, "devices are listed")

    # uptime: monitors with a progress block each, one down, pause and resume
    up = ext_screen("uptime")
    mons = ext_rows(up, "Monitors")
    ok(sum(1 for m in mons if m["tone"] == "bad") == 1, "exactly one monitor is down")
    ok(sum(1 for sec in up["sections"] for b in sec["blocks"] if b["type"] == "progress") == len(mons), "one uptime bar per monitor")
    ext_do("uptime", "pause", item=mons[0]["id"])
    ok(find(ext_rows(call("GET", "/extensions/uptime"), "Monitors"), id=mons[0]["id"])["tone"] == "muted", "paused monitor is muted")
    ext_do("uptime", "resume", item=mons[0]["id"])
    ok(find(ext_rows(call("GET", "/extensions/uptime"), "Monitors"), id=mons[0]["id"])["tone"] == "ok", "resumed monitor is up")
    ext_do("uptime", "pause", item="nope", expect_ok=False)

    # paperless: the consume item finishes into the inbox, mark reviewed removes a row
    ok(wait_for(lambda: len(ext_rows(call("GET", "/extensions/paperless"), "Consume")) == 0, 20), "consume should finish")
    inbox = ext_rows(call("GET", "/extensions/paperless"), "Inbox")
    ok(len(inbox) == n_in + 1 and all(r["chips"] for r in inbox), "finished document lands in the inbox with tags")
    ext_do("paperless", "reviewed", item=inbox[0]["id"])
    ok(find(ext_rows(call("GET", "/extensions/paperless"), "Inbox"), id=inbox[0]["id"]) is None, "reviewed document leaves the inbox")
    ext_do("paperless", "reviewed", item=inbox[0]["id"], expect_ok=False)

    # syncthing: one folder syncing at start, rescan makes another one sync, all end up to date
    ext_do("syncthing", "rescan", item="notes")
    ext_do("syncthing", "rescan", item="notes", expect_ok=False)
    ok("progress" in find(ext_rows(call("GET", "/extensions/syncthing"), "Folders"), id="notes"), "rescanned folder syncs")
    ok(wait_for(lambda: all(f["tone"] == "ok" for f in ext_rows(call("GET", "/extensions/syncthing"), "Folders")), 30),
       "all folders end up to date")
    ext_do("syncthing", "rescan", item="nope", expect_ok=False)


def test_network_and_pi():
    print("network and Pi extensions")
    summ = {e["id"]: e for e in call("GET", "/extensions")["extensions"]}
    ok(summ["pihole"]["group"] == "Network" and summ["wireguard"]["group"] == "Network"
       and summ["raspi"]["group"] == "Raspberry Pi", "network and Pi groups should be set")

    # pihole: stats, pause that expires, disable with confirmation, text input
    ph = ext_screen("pihole")
    tiles = [t for sec in ph["sections"] for b in sec["blocks"] if b["type"] == "stats" for t in b["tiles"]]
    ok(len(tiles) == 4, "pihole should show four stat tiles")
    ok(len(ext_rows(ph, "Top blocked")) >= 3 and len(ext_rows(ph, "Top clients")) >= 3, "pihole top lists")
    rq = ext_rows(ph, "Recent queries")
    ok(len(rq) >= 5 and all(r["chips"][0]["text"] in ("allowed", "blocked") for r in rq), "recent queries carry allowed or blocked chips")
    ok(all(r["tone"] == ("bad" if r["chips"][0]["text"] == "blocked" else "ok") for r in rq), "query tones follow the chip")
    acts = [a for sec in ph["sections"] for b in sec["blocks"] if b["type"] == "actions" for a in b["actions"]]
    ok(any(a["id"] == "disable" and a.get("confirm") for a in acts), "disable should ask for confirmation")
    ok(any(a["id"] == "blocklist_add" and "input" in a for a in acts), "blocklist action takes text")
    ext_do("pihole", "pause")
    ok("Paused" in call("GET", "/extensions")["extensions"].__getitem__(
        [e["id"] for e in call("GET", "/extensions")["extensions"]].index("pihole"))["summary"], "pihole summary shows the pause")
    ok(ext_rows(call("GET", "/extensions/pihole"), "Blocking")[0]["tone"] == "warn", "paused state is a warning")
    ok(wait_for(lambda: ext_rows(call("GET", "/extensions/pihole"), "Blocking")[0]["tone"] == "ok", 30), "pause should expire by itself")
    ext_do("pihole", "disable")
    ok(ext_rows(call("GET", "/extensions/pihole"), "Blocking")[0]["tone"] == "bad", "disabled state is bad")
    ext_do("pihole", "pause", expect_ok=False)
    ext_do("pihole", "enable")
    ok(ext_rows(call("GET", "/extensions/pihole"), "Blocking")[0]["tone"] == "ok", "enabled again")
    n0 = int([b for sec in call("GET", "/extensions/pihole")["sections"] if sec["title"] == "Blocklist" for b in sec["blocks"]
              if b["type"] == "text"][0]["text"].split()[0].replace(",", ""))
    ext_do("pihole", "blocklist_add", text="nasty.ads.example")
    txt = [b for sec in call("GET", "/extensions/pihole")["sections"] if sec["title"] == "Blocklist" for b in sec["blocks"] if b["type"] == "text"][0]["text"]
    ok(int(txt.split()[0].replace(",", "")) == n0 + 1 and "1 added" in txt, "blocklist count goes up by one")
    ext_do("pihole", "blocklist_add", text="nasty.ads.example", expect_ok=False)
    ext_do("pihole", "blocklist_add", text="not a domain", expect_ok=False)
    ext_do("pihole", "blocklist_add", text="", expect_ok=False)

    # wireguard: peers, per peer toggle, remove, add that connects by itself
    wg = ext_screen("wireguard")
    peers = ext_rows(wg, "Peers")
    ok(len(peers) >= 4 and all(p["subtitle"].startswith("10.8.0.") for p in peers), "peers have addresses in the example range")
    ok(all(any(c["text"].startswith("demo-key-") for c in p["chips"]) for p in peers), "peers show placeholder keys only")
    ok(any(p["tone"] == "ok" for p in peers) and any(p["tone"] == "warn" for p in peers), "handshake age drives the tone")
    ok(all([a["id"] for a in p["actions"]][1:] == ["remove"] and p["actions"][1].get("confirm") for p in peers), "remove asks first")
    ok("PRIVATE" not in json.dumps(wg).upper().replace("NEVER SEND PRIVATE", ""), "no private key material")
    live = next(p for p in peers if p["tone"] == "ok")
    ext_do("wireguard", "disable", item=live["id"])
    now_p = find(ext_rows(call("GET", "/extensions/wireguard"), "Peers"), id=live["id"])
    ok(now_p["tone"] == "muted" and now_p["actions"][0]["label"] == "Enable", "disabled peer is muted with an Enable action")
    ext_do("wireguard", "enable", item=live["id"])
    ok(find(ext_rows(call("GET", "/extensions/wireguard"), "Peers"), id=live["id"])["tone"] == "ok", "enabled peer connects again")
    ext_do("wireguard", "enable", item="nope", expect_ok=False)
    ext_do("wireguard", "add_peer", text="", expect_ok=False)
    ext_do("wireguard", "add_peer", text="test-phone")
    ext_do("wireguard", "add_peer", text="Test-Phone", expect_ok=False)
    new = find(ext_rows(call("GET", "/extensions/wireguard"), "Peers"), id="peer-test-phone")
    ok(new and "waiting for first handshake" in new["subtitle"] and new["tone"] == "warn", "new peer waits for its first handshake")
    ok(wait_for(lambda: find(ext_rows(call("GET", "/extensions/wireguard"), "Peers"), id="peer-test-phone")["tone"] == "ok", 30),
       "new peer should connect by itself")
    ext_do("wireguard", "remove", item="peer-test-phone")
    ok(find(ext_rows(call("GET", "/extensions/wireguard"), "Peers"), id="peer-test-phone") is None, "removed peer disappears")
    ext_do("wireguard", "remove", item="peer-test-phone", expect_ok=False)

    # raspi: interface toggles, boot settings, firmware update, reboot
    pi = ext_screen("raspi")
    ifs = ext_rows(pi, "Interfaces")
    ok({r["id"] for r in ifs} == {"ssh", "vnc", "i2c", "spi", "serial", "camera"}, "all six interfaces are listed")
    ok(all(r["actions"][0]["label"] == ("Disable" if r["tone"] == "ok" else "Enable") for r in ifs), "toggle label follows the state")
    ssh = find(ifs, id="ssh")
    ok(ssh["actions"][0].get("confirm"), "turning SSH off asks first")
    ext_do("raspi", "enable", item="i2c")
    ok(find(ext_rows(call("GET", "/extensions/raspi"), "Interfaces"), id="i2c")["tone"] == "ok", "I2C is on")
    ext_do("raspi", "disable", item="i2c")
    ok(find(ext_rows(call("GET", "/extensions/raspi"), "Interfaces"), id="i2c")["tone"] == "muted", "I2C is off again")
    ext_do("raspi", "enable", item="nope", expect_ok=False)
    ext_do("raspi", "target", item="desktop")
    ext_do("raspi", "target", item="nope", expect_ok=False)
    ext_do("raspi", "overclock", item="modest")
    bd = ext_rows(call("GET", "/extensions/raspi"), "Boot")
    ok(find(bd, id="target")["chips"][0]["text"] == "desktop" and find(bd, id="oc")["chips"][0]["text"] == "modest"
       and "reboot" in find(bd, id="oc")["subtitle"], "boot settings changed and wait for a reboot")
    ok(find(bd, id="hostname").get("actions") is None, "hostname is read only")
    ext_do("raspi", "update_firmware")
    ext_do("raspi", "update_firmware", expect_ok=False)
    fw = find(ext_rows(call("GET", "/extensions/raspi"), "System"), id="firmware")
    ok("progress" in fw, "firmware row shows progress while updating")
    ok(wait_for(lambda: "progress" not in find(ext_rows(call("GET", "/extensions/raspi"), "System"), id="firmware"), 30), "firmware update finishes")
    ok(find(ext_rows(call("GET", "/extensions/raspi"), "System"), id="firmware")["tone"] == "ok", "firmware is up to date")
    ext_do("raspi", "update_firmware", expect_ok=False)
    pi = call("GET", "/extensions/raspi")
    acts = [a for sec in pi["sections"] for b in sec["blocks"] if b["type"] == "actions" for a in b["actions"]]
    ok(any(a["id"] == "reboot" and a.get("confirm") for a in acts), "reboot asks first")
    ext_do("raspi", "reboot")
    ext_do("raspi", "enable", item="i2c", expect_ok=False)
    rb = call("GET", "/extensions/raspi")
    ok([t for b in rb["sections"][0]["blocks"] for t in b["tiles"] if t["label"] == "Uptime"][0]["value"] == "reboot", "panel shows rebooting")
    ok(any(b["type"] == "progress" for sec in rb["sections"] for b in sec["blocks"]), "reboot shows a progress bar")

    def uptime_value():
        d = call("GET", "/extensions/raspi")
        return [t for b in d["sections"][0]["blocks"] for t in b["tiles"] if t["label"] == "Uptime"][0]["value"]
    ok(wait_for(lambda: uptime_value() != "reboot", 30), "reboot finishes")
    ok(uptime_value().endswith("s"), f"uptime resets after the reboot, got {uptime_value()!r}")
    ok(find(ext_rows(call("GET", "/extensions/raspi"), "Boot"), id="oc")["subtitle"] == "applied", "overclock is applied after the reboot")


def main():
    global BASE
    if len(sys.argv) != 2:
        print("usage: smoke_test.py http://127.0.0.1:8790")
        return 2
    BASE = sys.argv[1].rstrip("/")
    try:
        request("GET", "/capabilities")
    except Exception as e:
        print(f"cannot reach {BASE}: {e}")
        return 2
    for fn in (test_capabilities, test_services, test_containers, test_host_numbers,
               test_machines_and_wake, test_alerts_events, test_extras, test_terminal,
               test_commands, test_jellyfin, test_demo_files, test_extensions):
        try:
            fn()
        except Exception as e:      # a crash inside a test is a failure, keep going
            fail(f"{fn.__name__} raised {e.__class__.__name__}: {e}")
    print()
    print(f"{CHECKS} checks, {len(FAILS)} failed")
    for f in FAILS:
        print("  -", f)
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
