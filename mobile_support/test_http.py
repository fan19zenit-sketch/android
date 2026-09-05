import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from mobile_support.server import create_app


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.device, self.installation = str(uuid.uuid4()), str(uuid.uuid4())
        (root / "registry.json").write_text(json.dumps([{"device_uuid": self.device, "city_name": "QA only"}]))
        (root / "secret").write_bytes(b"test-only" * 8)
        with patch.dict(os.environ, SUPPORT_DB=str(root / "db"), SUPPORT_REGISTRY=str(root / "registry.json"),
                        SUPPORT_SECRET_FILE=str(root / "secret")):
            self.client = TestClient(create_app())

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def test_health_has_no_private_data(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok", "schema": 1})
        self.assertEqual(self.client.get("/docs").status_code, 404)
        self.assertEqual(self.client.get("/admin").status_code, 404)
        self.assertEqual(self.client.post("/clear-queue", json={}).status_code, 404)

    def test_enrollment_and_authorized_heartbeat(self):
        response = self.client.post("/enroll", json={"device": self.device, "installation": self.installation})
        self.assertEqual(response.status_code, 200)
        token = response.json()["token"]
        data = dict(package="com.example.photovchistotu", version_code=20260906, version_name="3.1.0",
                    manufacturer="Test", model="Virtual", sdk=35, page_size=16384, free_mb=500,
                    queued=1, pending=2, issues=0, oldest_queue_seconds=0,
                    last_event="upload_acknowledged", last_event_at=1000000)
        payload = {"device": self.device, "diagnostics": data}
        self.assertEqual(self.client.post("/heartbeat", json=payload).status_code, 403)
        response = self.client.post("/heartbeat", json=payload, headers={"Authorization": f"Bearer {token}"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"schema": 1, "settings": {}, "ttl_seconds": 0})

    def test_invalid_and_large_requests_are_rejected(self):
        self.assertEqual(self.client.post("/enroll", content="not-json").status_code, 400)
        self.assertEqual(self.client.post("/enroll", json=[]).status_code, 400)
        self.assertEqual(self.client.post("/enroll", json={"device": None}).status_code, 400)
        self.assertEqual(self.client.post("/enroll", content="x" * 8193).status_code, 413)

    def test_requests_are_rate_limited(self):
        for _ in range(120):
            self.client.post("/enroll", json={})
        self.assertEqual(self.client.post("/enroll", json={}).status_code, 429)


if __name__ == "__main__":
    unittest.main()
