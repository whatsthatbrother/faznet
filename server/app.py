"""
Faznet upload server.

Devices register a random MAC-style ID + 4-digit PIN. Users open the website, enter MAC + PIN and add
playlists (M3U URL, Xtream Codes or an uploaded file). The app syncs them with the same MAC + PIN.
"""
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
from collections import defaultdict
from functools import wraps

from flask import Flask, abort, g, jsonify, render_template, request, send_file, url_for
from werkzeug.middleware.proxy_fix import ProxyFix

BASE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("FAZNET_DATA", os.path.join(BASE, "data"))
DB_PATH = os.path.join(DATA, "faznet.db")
FILES = os.path.join(DATA, "files")
SECRET = os.environ.get("FAZNET_SECRET", "")
MAX_PLAYLISTS = 20
MAX_FAILS = 10          # wrong PINs allowed per MAC ...
FAIL_WINDOW = 15 * 60   # ... in this many seconds

os.makedirs(FILES, exist_ok=True)
if not SECRET:
    secret_file = os.path.join(DATA, "secret.key")
    if not os.path.exists(secret_file):
        with open(secret_file, "w") as f:
            f.write(secrets.token_hex(32))
    SECRET = open(secret_file).read().strip()

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
app.config["MAX_CONTENT_LENGTH"] = 25 * 1024 * 1024
app.config["PREFERRED_URL_SCHEME"] = os.environ.get("FAZNET_SCHEME", "https")

MAC_RE = re.compile(r"^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
PIN_RE = re.compile(r"^\d{4}$")
_fails = defaultdict(list)


# ---------------------------------------------------------------- db
def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(_):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


def init_db():
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS devices(
            mac TEXT PRIMARY KEY, pin_hash TEXT NOT NULL, model TEXT, app_version TEXT,
            created INTEGER NOT NULL, last_seen INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS playlists(
            id INTEGER PRIMARY KEY AUTOINCREMENT, mac TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
            url TEXT, xserver TEXT, xuser TEXT, xpass TEXT, file_token TEXT,
            created INTEGER NOT NULL, updated INTEGER NOT NULL,
            FOREIGN KEY(mac) REFERENCES devices(mac) ON DELETE CASCADE);
        CREATE INDEX IF NOT EXISTS idx_pl_mac ON playlists(mac);
        """
    )
    conn.commit()
    conn.close()


init_db()


# ---------------------------------------------------------------- helpers
def norm_mac(mac):
    mac = (mac or "").strip().upper().replace("-", ":")
    if re.fullmatch(r"[0-9A-F]{12}", mac):
        mac = ":".join(mac[i:i + 2] for i in range(0, 12, 2))
    return mac


def pin_hash(mac, pin):
    return hmac.new(SECRET.encode(), f"{mac}|{pin}".encode(), hashlib.sha256).hexdigest()


def rate_limited(mac):
    now = time.time()
    _fails[mac] = [t for t in _fails[mac] if now - t < FAIL_WINDOW]
    return len(_fails[mac]) >= MAX_FAILS


def check_device(mac, pin):
    """Returns (device_row, error_key)."""
    mac = norm_mac(mac)
    if not MAC_RE.match(mac) or not PIN_RE.match(pin or ""):
        return None, "invalid"
    if rate_limited(mac):
        return None, "locked"
    row = db().execute("SELECT * FROM devices WHERE mac=?", (mac,)).fetchone()
    if row is None:
        _fails[mac].append(time.time())
        return None, "unknown"
    if not hmac.compare_digest(row["pin_hash"], pin_hash(mac, pin)):
        _fails[mac].append(time.time())
        return None, "wrong"
    return row, None


def device_auth(fn):
    @wraps(fn)
    def wrapper(*a, **kw):
        mac = request.headers.get("X-Faznet-Mac", "")
        pin = request.headers.get("X-Faznet-Pin", "")
        dev, err = check_device(mac, pin)
        if err:
            return jsonify(error=err), 429 if err == "locked" else 403
        db().execute("UPDATE devices SET last_seen=? WHERE mac=?", (int(time.time()), dev["mac"]))
        db().commit()
        return fn(dev, *a, **kw)
    return wrapper


def playlist_json(row):
    d = {"id": row["id"], "name": row["name"], "type": row["type"], "updated": row["updated"]}
    if row["type"] == "url":
        d["url"] = row["url"]
    elif row["type"] == "xtream":
        d.update(server=row["xserver"], username=row["xuser"], password=row["xpass"])
    elif row["type"] == "file":
        d["type"] = "url"
        d["url"] = url_for("playlist_file", token=row["file_token"], _external=True)
    return d


# ---------------------------------------------------------------- device API (used by the app)
@app.post("/api/device/register")
def register():
    data = request.get_json(silent=True) or {}
    mac, pin = norm_mac(data.get("mac")), str(data.get("pin", ""))
    if not MAC_RE.match(mac) or not PIN_RE.match(pin):
        return jsonify(error="invalid"), 400
    now = int(time.time())
    row = db().execute("SELECT pin_hash FROM devices WHERE mac=?", (mac,)).fetchone()
    if row is None:
        db().execute(
            "INSERT INTO devices(mac,pin_hash,model,app_version,created,last_seen) VALUES(?,?,?,?,?,?)",
            (mac, pin_hash(mac, pin), str(data.get("model", ""))[:80], str(data.get("version", ""))[:20], now, now),
        )
        db().commit()
        return jsonify(ok=True, created=True)
    if not hmac.compare_digest(row["pin_hash"], pin_hash(mac, pin)):
        return jsonify(error="mac_taken"), 409
    db().execute(
        "UPDATE devices SET last_seen=?, model=?, app_version=? WHERE mac=?",
        (now, str(data.get("model", ""))[:80], str(data.get("version", ""))[:20], mac),
    )
    db().commit()
    return jsonify(ok=True, created=False)


@app.get("/api/device/playlists")
@device_auth
def device_playlists(dev):
    rows = db().execute("SELECT * FROM playlists WHERE mac=? ORDER BY id", (dev["mac"],)).fetchall()
    return jsonify(playlists=[playlist_json(r) for r in rows])


@app.get("/p/<token>.m3u")
def playlist_file(token):
    if not re.fullmatch(r"[A-Za-z0-9_-]{20,64}", token):
        abort(404)
    row = db().execute("SELECT id FROM playlists WHERE file_token=?", (token,)).fetchone()
    path = os.path.join(FILES, token + ".m3u")
    if row is None or not os.path.exists(path):
        abort(404)
    return send_file(path, mimetype="audio/x-mpegurl", download_name="playlist.m3u")


# ---------------------------------------------------------------- website
MESSAGES = {
    "invalid": "MAC ose PIN nuk është në formatin e duhur.",
    "unknown": "Ky MAC nuk është regjistruar. Hape Faznet në pajisje me internet dhe provo sërish.",
    "wrong": "PIN-i është gabim. Shiko PIN-in në ekranin e Faznet.",
    "locked": "Shumë tentime të gabuara. Provo sërish pas 15 minutash.",
    "limit": f"Maksimumi është {MAX_PLAYLISTS} playlist-a për pajisje.",
    "fields": "Plotëso fushat e nevojshme.",
    "badurl": "URL-ja duhet të fillojë me http:// ose https://",
    "badfile": "File-i nuk duket si playlist M3U.",
}


@app.get("/")
def index():
    return render_template("index.html", mac=norm_mac(request.args.get("mac", "")), error=None, step="login")


@app.post("/device")
def device_page():
    mac, pin = norm_mac(request.form.get("mac")), request.form.get("pin", "").strip()
    dev, err = check_device(mac, pin)
    if err:
        return render_template("index.html", mac=mac, error=MESSAGES[err], step="login"), 403
    return render_device(dev, pin)


def render_device(dev, pin, error=None, notice=None):
    rows = db().execute("SELECT * FROM playlists WHERE mac=? ORDER BY id", (dev["mac"],)).fetchall()
    return render_template(
        "index.html", step="device", mac=dev["mac"], pin=pin, playlists=rows, error=error, notice=notice,
        model=dev["model"], max_playlists=MAX_PLAYLISTS,
    )


@app.post("/device/add")
def add_playlist():
    mac, pin = norm_mac(request.form.get("mac")), request.form.get("pin", "").strip()
    dev, err = check_device(mac, pin)
    if err:
        return render_template("index.html", mac=mac, error=MESSAGES[err], step="login"), 403
    count = db().execute("SELECT COUNT(*) FROM playlists WHERE mac=?", (dev["mac"],)).fetchone()[0]
    if count >= MAX_PLAYLISTS:
        return render_device(dev, pin, error=MESSAGES["limit"])

    kind = request.form.get("type", "url")
    name = (request.form.get("name") or "").strip()[:60]
    now = int(time.time())
    url = xserver = xuser = xpass = token = None

    if kind == "url":
        url = (request.form.get("url") or "").strip()
        if not url:
            return render_device(dev, pin, error=MESSAGES["fields"])
        if not re.match(r"^https?://", url, re.I):
            return render_device(dev, pin, error=MESSAGES["badurl"])
        name = name or (re.sub(r"^https?://", "", url).split("/")[0])
    elif kind == "xtream":
        xserver = (request.form.get("xserver") or "").strip().rstrip("/")
        xuser = (request.form.get("xuser") or "").strip()
        xpass = request.form.get("xpass") or ""
        if not (xserver and xuser and xpass):
            return render_device(dev, pin, error=MESSAGES["fields"])
        if not re.match(r"^https?://", xserver, re.I):
            xserver = "http://" + xserver
        name = name or re.sub(r"^https?://", "", xserver).split("/")[0]
    elif kind == "file":
        f = request.files.get("file")
        if not f or not f.filename:
            return render_device(dev, pin, error=MESSAGES["fields"])
        content = f.read()
        head = content[:2048].decode("utf-8", "ignore")
        if "#EXT" not in head and "://" not in head:
            return render_device(dev, pin, error=MESSAGES["badfile"])
        token = secrets.token_urlsafe(24)
        with open(os.path.join(FILES, token + ".m3u"), "wb") as out:
            out.write(content)
        name = name or os.path.splitext(f.filename)[0][:60]
    else:
        return render_device(dev, pin, error=MESSAGES["fields"])

    db().execute(
        "INSERT INTO playlists(mac,name,type,url,xserver,xuser,xpass,file_token,created,updated) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (dev["mac"], name, kind, url, xserver, xuser, xpass, token, now, now),
    )
    db().commit()
    return render_device(dev, pin, notice=f"“{name}” u shtua. Në Faznet shtyp Rifresko ose rinise aplikacionin.")


@app.post("/device/delete")
def delete_playlist():
    mac, pin = norm_mac(request.form.get("mac")), request.form.get("pin", "").strip()
    dev, err = check_device(mac, pin)
    if err:
        return render_template("index.html", mac=mac, error=MESSAGES[err], step="login"), 403
    pid = request.form.get("id", type=int)
    row = db().execute("SELECT * FROM playlists WHERE id=? AND mac=?", (pid, dev["mac"])).fetchone()
    if row:
        if row["file_token"]:
            try:
                os.remove(os.path.join(FILES, row["file_token"] + ".m3u"))
            except OSError:
                pass
        db().execute("DELETE FROM playlists WHERE id=?", (pid,))
        db().commit()
    return render_device(dev, pin, notice="Playlist-a u fshi.")


@app.get("/health")
def health():
    return jsonify(ok=True)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8080)), debug=False)
