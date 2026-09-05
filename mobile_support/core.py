"""Small, independently deployable control plane. No photo or bot mutations."""
import contextlib
import hashlib
import hmac
import json
import re
import sqlite3
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

PACKAGES = {"com.example.photovchistotu", "com.example.simplephotouploader"}
DOWNLOAD_HOST = "194-55-235-241.sslip.io"


class Denied(Exception):
    pass


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value.lower():
        raise ValueError("Invalid identifier")
    return value.lower()


def settings(value):
    allowed = {"volume_capture", "retry_seconds", "uploads_paused"}
    if not isinstance(value, dict) or set(value) - allowed:
        raise ValueError("Unknown setting")
    for key in ("volume_capture", "uploads_paused"):
        if key in value and type(value[key]) is not bool:
            raise ValueError("Expected boolean")
    if "retry_seconds" in value and (type(value["retry_seconds"]) is not int or
                                    not 30 <= value["retry_seconds"] <= 300):
        raise ValueError("Retry must be 30..300 seconds")
    return value


def release(value):
    if not isinstance(value, dict) or set(value) != {"package", "version_code", "version_name", "url", "sha256", "min_sdk"}:
        raise ValueError("Invalid release fields")
    url = urlparse(value["url"])
    if (url.scheme != "https" or url.netloc != DOWNLOAD_HOST or url.query or url.fragment or
            not re.fullmatch(r"/downloads/[A-Za-z0-9._-]+\.apk", url.path)):
        raise ValueError("Only official HTTPS downloads are allowed")
    if value["package"] not in PACKAGES or not re.fullmatch(r"[0-9a-f]{64}", value["sha256"]):
        raise ValueError("Invalid package or digest")
    if type(value["version_code"]) is not int or not 1 <= value["version_code"] <= 2100000000:
        raise ValueError("Invalid version")
    if type(value["min_sdk"]) is not int or not 24 <= value["min_sdk"] <= 100:
        raise ValueError("Invalid SDK")
    if not re.fullmatch(r"[0-9A-Za-z.+_-]{1,40}", value["version_name"]):
        raise ValueError("Invalid version name")
    return value


def telemetry(value):
    strings = {"version_name": 40, "manufacturer": 80, "model": 100,
               "last_event": 40}
    numbers = {"version_code": 2100000000, "sdk": 100, "page_size": 65536,
               "free_mb": 10000000, "queued": 1000000, "pending": 1000000,
               "issues": 1000000, "oldest_queue_seconds": 1000000000,
               "last_event_at": 10000000000000}
    if not isinstance(value, dict) or set(value) != set(strings) | set(numbers) | {"package"}:
        raise ValueError("Invalid diagnostics fields")
    if value["package"] not in PACKAGES:
        raise ValueError("Unsupported package")
    for key, limit in strings.items():
        if not isinstance(value[key], str) or len(value[key]) > limit or any(ord(c) < 32 for c in value[key]):
            raise ValueError("Invalid text field")
    if not re.fullmatch(r"[a-z0-9_]{0,40}", value["last_event"]):
        raise ValueError("Event must be a coarse code, not a log message")
    for key, limit in numbers.items():
        if type(value[key]) is not int or not 0 <= value[key] <= limit:
            raise ValueError("Invalid numeric field")
    return value


class Store:
    def __init__(self, database, registry, secret, clock=time.time):
        self.database, self.registry, self.secret, self.clock = str(database), Path(registry), secret, clock
        if len(secret) < 32:
            raise ValueError("A private 256-bit secret is required")
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS devices (
                  device TEXT PRIMARY KEY, installation TEXT NOT NULL,
                  city TEXT NOT NULL, seen INTEGER, diagnostics TEXT);
                CREATE TABLE IF NOT EXISTS config (
                  device TEXT PRIMARY KEY, expires INTEGER NOT NULL, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS releases (package TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS audit (
                  at INTEGER NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, value TEXT NOT NULL);
            """)

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.database, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def city(self, device):
        registry = json.loads(self.registry.read_text())
        for row in registry:
            if row.get("device_uuid", "").lower() == device:
                return str(row.get("city_name") or row.get("city_slug") or "")[:100]
        raise Denied("Unknown device")

    def token(self, device, installation):
        return hmac.new(self.secret, f"v1:{device}:{installation}".encode(), hashlib.sha256).hexdigest()

    def enroll(self, device, installation):
        device, installation = identifier(device), identifier(installation)
        city = self.city(device)
        with self.db() as db:
            db.execute("INSERT OR IGNORE INTO devices(device, installation, city) VALUES (?,?,?)",
                       (device, installation, city))
            row = db.execute("SELECT installation FROM devices WHERE device=?", (device,)).fetchone()
            if row[0] != installation:
                raise Denied("Installation already bound; administrator reset required")
        return {"token": self.token(device, installation)}

    def heartbeat(self, device, token, data):
        device, data = identifier(device), telemetry(data)
        city, now = self.city(device), int(self.clock())
        with self.db() as db:
            row = db.execute("SELECT installation FROM devices WHERE device=?", (device,)).fetchone()
            if not row or not hmac.compare_digest(self.token(device, row[0]), token):
                raise Denied("Unauthorized")
            db.execute("UPDATE devices SET city=?, seen=?, diagnostics=? WHERE device=?",
                       (city, now, json.dumps(data), device))
            config = db.execute("SELECT expires,value FROM config WHERE device=? AND expires>?", (device, now)).fetchone()
            update = db.execute("SELECT value FROM releases WHERE package=?", (data["package"],)).fetchone()
        result = {"schema": 1, "settings": json.loads(config[1]) if config else {},
                  "ttl_seconds": min(config[0] - now, 86400) if config else 0}
        if update:
            info = json.loads(update[0])
            if info["version_code"] > data["version_code"] and info["min_sdk"] <= data["sdk"]:
                result["update"] = info
        return result

    def configure(self, device, value, ttl):
        device, value = identifier(device), settings(value)
        self.city(device)
        # A forgotten emergency pause must never strand the queue indefinitely.
        limit = 900 if value.get("uploads_paused") else 86400
        if type(ttl) is not int or not 1 <= ttl <= limit:
            raise ValueError(f"TTL must be 1..{limit} seconds")
        now = int(self.clock())
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO config VALUES (?,?,?)", (device, now + ttl, json.dumps(value)))
            db.execute("INSERT INTO audit VALUES (?,?,?,?)", (now, "configure", device, json.dumps(value)))

    def publish(self, value):
        value = release(value)
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO releases VALUES (?,?)", (value["package"], json.dumps(value)))
            db.execute("INSERT INTO audit VALUES (?,?,?,?)", (int(self.clock()), "release", value["package"], json.dumps(value)))

    def inventory(self):
        with self.db() as db:
            return [dict(row) for row in db.execute("SELECT device,city,seen,diagnostics FROM devices ORDER BY city,seen DESC")]

    def reset(self, device):
        device = identifier(device)
        with self.db() as db:
            db.execute("DELETE FROM devices WHERE device=?", (device,))
            db.execute("DELETE FROM config WHERE device=?", (device,))
            db.execute("INSERT INTO audit VALUES (?,?,?,?)", (int(self.clock()), "reset", device, "{}"))

    def backup(self, destination):
        with self.db() as source, sqlite3.connect(destination) as target:
            source.backup(target)
