import json
import tempfile
import unittest
import uuid
from pathlib import Path

from mobile_support.core import Denied, Store, release, settings, telemetry


class SupportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.device, self.install = str(uuid.uuid4()), str(uuid.uuid4())
        self.registry = self.root / "devices.json"
        self.registry.write_text(json.dumps([{"device_uuid": self.device, "city_name": "Test City"}]))
        self.now = 100000
        self.store = Store(self.root / "support.db", self.registry, b"test-only-secret" * 4, lambda: self.now)
        self.data = dict(package="com.example.photovchistotu", version_code=20260906, version_name="3.1.0",
                         manufacturer="Test", model="Virtual", sdk=35, page_size=16384, free_mb=500,
                         queued=12, pending=7, issues=1, oldest_queue_seconds=123,
                         last_event="upload_http_503", last_event_at=1000000)
        self.update = dict(package=self.data["package"], version_code=20260907, version_name="3.1.1",
                           min_sdk=24, url="https://194-55-235-241.sslip.io/downloads/test.apk", sha256="a" * 64)

    def tearDown(self):
        self.tmp.cleanup()

    def token(self):
        return self.store.enroll(self.device, self.install)["token"]

    def beat(self):
        return self.store.heartbeat(self.device, self.token(), self.data)

    def test_enrollment_is_idempotent_and_installation_bound(self):
        self.assertEqual(self.token(), self.token())
        with self.assertRaises(Denied):
            self.store.enroll(self.device, str(uuid.uuid4()))

    def test_unknown_device_cannot_enroll(self):
        with self.assertRaises(Denied):
            self.store.enroll(str(uuid.uuid4()), self.install)

    def test_heartbeat_requires_token(self):
        self.token()
        with self.assertRaises(Denied):
            self.store.heartbeat(self.device, "0" * 64, self.data)

    def test_defaults_and_inventory(self):
        self.assertEqual(self.beat()["settings"], {})
        rows = self.store.inventory()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["seen"], self.now)
        self.assertEqual(json.loads(rows[0]["diagnostics"])["queued"], 12)
        self.assertNotIn("installation", rows[0])

    def test_configuration_expires_without_renewal(self):
        self.store.configure(self.device, {"uploads_paused": True}, 120)
        self.assertTrue(self.beat()["settings"]["uploads_paused"])
        self.now += 60
        self.assertEqual(self.beat()["ttl_seconds"], 60)
        self.now += 61
        self.assertEqual(self.beat()["settings"], {})

    def test_no_destructive_or_unbounded_configuration(self):
        for value in ({"take_photo": True}, {"clear_queue": True}, {"backend_url": "http://bad"},
                      {"retry_seconds": 0}, {"retry_seconds": 301}, {"retry_seconds": True},
                      {"volume_capture": "false"}):
            with self.assertRaises(ValueError):
                settings(value)
        with self.assertRaises(ValueError):
            self.store.configure(self.device, {"uploads_paused": True}, 901)

    def test_reset_revokes_previous_token(self):
        token = self.token()
        self.store.reset(self.device)
        self.store.enroll(self.device, str(uuid.uuid4()))
        with self.assertRaises(Denied):
            self.store.heartbeat(self.device, token, self.data)

    def test_removed_registry_device_is_denied(self):
        token = self.token()
        self.registry.write_text("[]")
        with self.assertRaises(Denied):
            self.store.heartbeat(self.device, token, self.data)

    def test_privacy_schema_rejects_extra_logs_and_photos(self):
        for extra in ("photo", "phone", "logs", "fio", "message"):
            with self.assertRaises(ValueError):
                telemetry({**self.data, extra: "private"})
        with self.assertRaises(ValueError):
            telemetry({**self.data, "last_event": "https://secret/?token=abc"})

    def test_release_only_official_https_and_exact_package(self):
        for url in ("http://194-55-235-241.sslip.io/downloads/test.apk", "https://evil.test/test.apk",
                    "https://194-55-235-241.sslip.io@evil.test/downloads/test.apk",
                    "https://194-55-235-241.sslip.io/downloads/../secret.apk",
                    "https://194-55-235-241.sslip.io/downloads/test.apk?token=abc"):
            with self.assertRaises(ValueError):
                release({**self.update, "url": url})
        with self.assertRaises(ValueError):
            release({**self.update, "package": "com.other.app"})

    def test_release_filters_old_versions_and_sdk(self):
        self.store.publish(self.update)
        self.assertEqual(self.beat()["update"], self.update)
        self.data["version_code"] = self.update["version_code"]
        self.assertNotIn("update", self.beat())
        self.data["version_code"] = 1
        self.data["sdk"] = 23
        self.assertNotIn("update", self.beat())

    def test_backup_preserves_authorization(self):
        token = self.token()
        backup = self.root / "backup.db"
        self.store.backup(backup)
        restored = Store(backup, self.registry, self.store.secret, lambda: self.now)
        self.assertEqual(restored.heartbeat(self.device, token, self.data)["schema"], 1)


if __name__ == "__main__":
    unittest.main()
