from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import tempfile
import threading
import unittest
import urllib.request

from updater_test_server import build_manifest, main, make_server, prepare_dir

APK_BYTES = b"PK\x03\x04 not a real apk, just bytes to hash"
NOW_MS = 1_700_000_000_000
EARLY = "00000000-0000-0000-0000-000000000000"


class ManifestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.apk = os.path.join(self.work.name, "kevbox-2002.apk")
        with open(self.apk, "wb") as handle:
            handle.write(APK_BYTES)

    def test_manifest_points_at_10_0_2_2_on_the_given_port_with_the_apks_sha256(self) -> None:
        manifest = json.loads(build_manifest(self.apk, 2002, -1, [], 8765, NOW_MS))
        self.assertEqual(manifest["versionCode"], 2002)
        self.assertEqual(manifest["versionName"], "test-2002")
        self.assertEqual(manifest["url"], "http://10.0.2.2:8765/kevbox-test-2002.apk")
        self.assertEqual(manifest["sha256"], hashlib.sha256(APK_BYTES).hexdigest())
        self.assertEqual(manifest["notes"], "Emulator test build 2002")
        self.assertEqual(manifest["profiles"], [])

        other_port = json.loads(build_manifest(self.apk, 2003, 24, [], 9000, NOW_MS))
        self.assertEqual(other_port["url"], "http://10.0.2.2:9000/kevbox-test-2003.apk")

    def test_after_is_now_plus_the_given_hours_and_early_users_pass_through(self) -> None:
        past = json.loads(build_manifest(self.apk, 2002, -1, [], 8765, NOW_MS))
        self.assertEqual(past["silent"], {"after": NOW_MS - 3_600_000, "earlyUsers": []})

        ahead = json.loads(build_manifest(self.apk, 2003, 24, [EARLY], 8765, NOW_MS))
        self.assertEqual(ahead["silent"], {"after": NOW_MS + 86_400_000, "earlyUsers": [EARLY]})

    def test_an_early_user_that_is_not_one_uuid_is_refused_by_position_without_its_value(self) -> None:
        email = "kevin@example.com"
        for users, position in (([EARLY, email], 2), ([f"{EARLY},{EARLY}"], 1), ([" "], 1)):
            with self.subTest(users=users):
                with self.assertRaises(ValueError) as caught:
                    build_manifest(self.apk, 2003, 24, users, 8765, NOW_MS)
                self.assertEqual(str(caught.exception), f"--early-user entry {position} is not a UUID")

        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as exited:
            main(["--apk", self.apk, "--version-code", "2003", "--after-hours", "24",
                  "--early-user", email])
        self.assertEqual(exited.exception.code, 2)
        self.assertIn("--early-user entry 1 is not a UUID", err.getvalue())
        self.assertNotIn(email, err.getvalue())

    def test_the_server_binds_loopback_and_serves_the_manifest_and_the_apk(self) -> None:
        served = tempfile.TemporaryDirectory()
        self.addCleanup(served.cleanup)
        text = prepare_dir(served.name, self.apk, 2002, -1, [], 8765, NOW_MS)

        server = make_server(served.name, 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            host, port = server.server_address[:2]
            self.assertEqual(host, "127.0.0.1")
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/version.json", timeout=5) as reply:
                self.assertEqual(reply.read().decode("utf-8"), text)
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/kevbox-test-2002.apk", timeout=5) as reply:
                body = reply.read()
            self.assertEqual(hashlib.sha256(body).hexdigest(), json.loads(text)["sha256"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
