"""Explicit first install; keeps Caddy backup on the server and never touches bot code."""
import hashlib
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


def run(*args):
    subprocess.run(args, check=True)


def main():
    if os.geteuid() != 0:
        raise SystemExit("Run as root on the production server")
    source = Path(__file__).resolve().parent.parent
    destination = Path("/opt/photo-mobile-support")
    resume = "--resume-preflight" in sys.argv and not Path("/etc/systemd/system/photo-mobile-support.service").exists()
    if destination.exists() and not resume:
        raise SystemExit("Already installed; review a versioned update rather than overwriting")
    caddy = Path("/etc/caddy/Caddyfile")
    original = caddy.read_bytes()
    headers = list(re.finditer(rb"(?m)^194-55-235-241\.sslip\.io[ \t]*\{\r?\n", original))
    if len(headers) != 1 or b"/mobile-support/" in original or b"/mobile-api/" in original:
        raise SystemExit("Unexpected Caddy layout; no changes made")
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup = Path("/root/photo-mobile-support-backups") / stamp
    backup.mkdir(parents=True, mode=0o700)
    shutil.copy2(caddy, backup / "Caddyfile")
    os.chmod(backup / "Caddyfile", 0o600)
    old_pid = subprocess.check_output(["systemctl", "show", "photo_chat_backend.service", "--property=MainPID", "--value"]).strip()
    destination.mkdir(mode=0o755, exist_ok=resume)
    shutil.copytree(source, destination / "mobile_support", ignore=shutil.ignore_patterns("__pycache__"), dirs_exist_ok=resume)
    os.chdir(destination)
    state = Path("/var/lib/photo-mobile-support")
    state.mkdir(mode=0o700, exist_ok=True)
    secret = state / "secret"
    if not secret.exists():
        fd = os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(secrets.token_bytes(32))
    run(sys.executable, "-m", "venv", str(destination / "venv"))
    python = str(destination / "venv/bin/python")
    run(python, "-m", "pip", "install", "-r", str(source / "requirements-test.txt"))
    run(python, "-m", "unittest", "mobile_support.test_core", "mobile_support.test_http", "-v")
    for filename in ("photo-mobile-support.service", "photo-mobile-support-backup.service", "photo-mobile-support-backup.timer"):
        shutil.copy2(source / "deploy" / filename, Path("/etc/systemd/system") / filename)
    run("systemctl", "daemon-reload")
    run("systemctl", "enable", "--now", "photo-mobile-support.service")
    for attempt in range(20):
        try:
            with urllib.request.urlopen("http://127.0.0.1:8050/health", timeout=2) as response:
                if response.status == 200:
                    break
        except OSError:
            time.sleep(1)
    else:
        raise SystemExit("Support service did not start; Caddy is unchanged")
    candidate = caddy.with_name("Caddyfile.mobile-candidate")
    snippet = (source / "deploy" / "caddy-mobile-routes.txt").read_bytes()
    offset = headers[0].end()
    candidate.write_bytes(original[:offset] + snippet + original[offset:])
    try:
        run("caddy", "validate", "--config", str(candidate), "--adapter", "caddyfile")
        if caddy.read_bytes() != original:
            raise SystemExit("Caddy changed concurrently; refusing to overwrite")
        os.chmod(candidate, caddy.stat().st_mode & 0o777)
        candidate.replace(caddy)
        try:
            run("systemctl", "reload", "caddy")
        except subprocess.CalledProcessError:
            shutil.copy2(backup / "Caddyfile", caddy)
            run("systemctl", "reload", "caddy")
            raise
    finally:
        candidate.unlink(missing_ok=True)
    run("systemctl", "enable", "--now", "photo-mobile-support-backup.timer")
    run("systemctl", "start", "photo-mobile-support-backup.service")
    new_pid = subprocess.check_output(["systemctl", "show", "photo_chat_backend.service", "--property=MainPID", "--value"]).strip()
    print("Bot PID unchanged:", old_pid == new_pid)
    print("Caddy backup retained on server:", str(backup))
    print("Routes checksum:", hashlib.sha256(snippet).hexdigest())


if __name__ == "__main__":
    main()
