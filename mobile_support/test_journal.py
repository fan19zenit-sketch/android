import json
import unittest
import uuid

from mobile_support.core import Denied, connection_events, network_state
from mobile_support import test_core as fixtures


class JournalTest(fixtures.SupportTest):
    def setUp(self):
        super().setUp()
        self.network = dict(connected=True, validated=True, captive_portal=False, metered=False,
                            power_save=False, idle=False, battery_exempt=False,
                            background_restricted=False, transport="wifi")
        self.event = dict(id=str(uuid.uuid4()), at=123456789, operation="registration", phase="tls",
                          endpoint="primary", trace="01234567", elapsed_ms=15000, http=0, error="tls_timeout")

    def test_v1_heartbeat_still_works(self):
        self.assertNotIn("ack_events", self.beat())

    def test_acknowledgement_is_idempotent_and_authenticated(self):
        token = self.token()
        for _ in range(2):
            response = self.store.heartbeat(self.device, token, self.data, [self.event], self.network)
            self.assertEqual(response["ack_events"], [self.event["id"]])
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM diagnostic_events").fetchone()[0], 1)
            self.assertEqual(json.loads(db.execute("SELECT value FROM network_state").fetchone()[0]), self.network)
        with self.assertRaises(Denied):
            self.store.heartbeat(self.device, "bad", self.data, [self.event], self.network)

    def test_retention_uses_receipt_not_phone_time(self):
        token = self.token()
        self.store.heartbeat(self.device, token, self.data, [self.event], self.network)
        self.now += 15 * 86400
        self.store.heartbeat(self.device, token, self.data, [], self.network)
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM diagnostic_events").fetchone()[0], 0)

    def test_arbitrary_logs_secrets_and_unbounded_batches_are_rejected(self):
        for patch in ({"url": "secret"}, {"error": "https://private/?token=x"}, {"http": True},
                      {"trace": "secret"}, {"operation": "take_photo"}):
            with self.assertRaises(ValueError):
                connection_events([{**self.event, **patch}])
        with self.assertRaises(ValueError):
            connection_events([self.event] * 13)
        with self.assertRaises(ValueError):
            network_state({**self.network, "ssid": "private"})
        with self.assertRaises(ValueError):
            network_state({**self.network, "validated": 1})


if __name__ == "__main__":
    unittest.main()
