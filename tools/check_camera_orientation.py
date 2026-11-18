"""Signed release, isolated emulator only; run after check_signed_upgrade.py. Requires Pillow."""
import ast
from collections import Counter
from http.server import ThreadingHTTPServer
from io import BytesIO
import mimetypes
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import time
from xml.etree import ElementTree as ET
from PIL import Image
import check_signed_upgrade as e


def until(fn, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = fn()
        if value:
            return value
        time.sleep(.3)
    raise AssertionError("Timed out: " + fn.__name__)


def max_adapter(source, path):
    # Exercise the repository function without importing a production service or making any network calls.
    tree = ast.parse(source.read_text(encoding="utf-8-sig"))
    tree.body = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "send_to_max"]
    assert len(tree.body) == 1
    sent = []

    class Reply:
        ok = True
        def __init__(self, payload):
            self.json = lambda: payload

    def transport(method, url, **kw):
        assert method == "POST"
        if url.endswith("/uploads?type=image"):
            return Reply({"url": "fixture://upload"})
        if url == "fixture://upload":
            sent.append(kw["files"]["data"][1].read())
            return Reply({"token": "fixture-token"})
        assert kw["json"]["attachments"][0]["payload"]["token"] == "fixture-token"
        return Reply({"message": {"body": {"mid": "fixture-mid"}}})

    ns = dict(Path=Path, Any=object, MAX_BOT_TOKEN="fixture", mimetypes=mimetypes, request_with_retry=transport)
    exec(compile(tree, str(source), "exec"), ns)
    assert ns["send_to_max"]({"max_chat_id": "fixture"}, path)["message_id"] == "fixture-mid"
    assert sent == [path.read_bytes()]
    with Image.open(BytesIO(sent[0])) as pixels:
        pixels.load()
        return pixels.size


def main(source, proof):
    e.SERIAL = "emulator-5580"
    a = e.adb
    assert a("emu", "avd", "name").split()[0] == "codex-shift-isolated"
    assert a("shell", "getprop", "ro.kernel.qemu") == "1"
    for tool in ("iptables", "ip6tables"):
        rules = a("shell", tool, "-S", "OUTPUT").splitlines()
        assert rules[1].endswith("-o lo -j ACCEPT") and " -j REJECT" in rules[2]
    pkg = e.PACKAGE
    assert "versionName=3.3.7" in a("shell", "dumpsys", "package", pkg)

    def rows():
        sql = "select id,photoPath,status from photo_records where photoPath like '%/IMG_upright_%' order by capturedAt"
        return [s.split("|") for s in a("shell", 'sqlite3 ' + e.DB + ' "' + sql + '"').splitlines() if s]

    def start():
        a("shell", "am", "start", "-W", "-n", pkg + "/com.example.simplephotouploader.MainActivity")

    with TemporaryDirectory() as temp:
        root = Path(temp)
        a("shell", "am", "force-stop", pkg)
        a("pull", e.PREFS, str(root / "previous"))
        prefs = ET.parse(root / "previous")
        assert prefs.find("string[@name='device_uuid']").text == e.DEVICE
        prefs.find("string[@name='device_uuid']").text = "orientation-fixture"
        prefs.write(root / "fixture", encoding="utf-8", xml_declaration=True)
        uid = a("shell", "stat", "-c", "%u", e.APP_ROOT)
        auto, rot = [a("shell", "settings", "get", "system", s) for s in ("accelerometer_rotation","user_rotation")]
        assert not rows()
        proof.mkdir(parents=True, exist_ok=True)
        e.push_owned(root / "fixture", e.PREFS, uid)
        server = None
        try:
            a("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            a("shell", "settings", "put", "system", "user_rotation", "0")
            a("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            a("shell", "wm", "dismiss-keyguard")
            start()
            time.sleep(3)
            originals = {}
            for i, gravity in enumerate(("0:9.81:0", "9.81:0:0", "0:-9.81:0", "-9.81:0:0")):
                assert "OK" in a("emu", "sensor", "set", "acceleration", gravity)
                time.sleep(1)
                a("shell", "input", "keyevent", "KEYCODE_VOLUME_UP")
                def new_saved():
                    records = rows()
                    return records[-1] if len(records) == i+1 and records[-1][2] in ("queued","retrying","sending") else None
                photo_id, path, _ = until(new_saved)
                local = proof / f"gravity-{i}.jpg"
                a("pull", path, str(local))
                with Image.open(local) as img:
                    assert img.getexif().get(274, 1) == 1 and (img.height > img.width) == (i % 2 == 0), (i, img.size)
                originals[photo_id] = local
            a("shell", "input", "keyevent", "KEYCODE_SLEEP")
            a("shell", "am", "force-stop", pkg)
            assert {r[0] for r in rows()} == set(originals)
            e.Fixture.max_request = 32_000_000
            server = ThreadingHTTPServer(("127.0.0.1",18433), e.Fixture)
            Thread(target=server.serve_forever, daemon=True).start()
            a("reverse", "tcp:18433", "tcp:18433")
            a("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            a("shell", "wm", "dismiss-keyguard")
            start()
            until(lambda: len(e.Fixture.received) >= 4, 180)
            until(lambda: all(r[2] == "sent" for r in rows()), 90)
            assert Counter(x[1] for x in e.Fixture.received) == Counter(originals.keys())
            for device, key, wire, _ in e.Fixture.received:
                assert device == "orientation-fixture" and wire == originals[key].read_bytes()
                received = root / "received.jpg"
                received.write_bytes(wire)
                print(originals[key].name, max_adapter(source, received))
            print("CAMERA_OK: four rotations, volume events, offline queue, sleep/restart, exactly-once delivery; MAX adapter bytes identical")
        finally:
            if server:
                server.shutdown()
                server.server_close()
            a("shell", "am", "force-stop", pkg)
            e.push_owned(root / "previous", e.PREFS, uid)
            a("emu", "sensor", "set", "acceleration", "0:9.81:0")
            a("shell", "settings", "put", "system", "accelerometer_rotation", auto)
            a("shell", "settings", "put", "system", "user_rotation", rot)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
