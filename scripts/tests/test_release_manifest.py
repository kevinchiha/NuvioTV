from __future__ import annotations

import contextlib
import io
import json
import os
import unittest
from unittest import mock

from release_manifest import (
    SILENT_DELAY_MS,
    main,
    parse_early_users,
    render_manifest,
    silent_block,
)

ID_A = "11111111-1111-1111-1111-111111111111"
ID_B = "2222222A-2222-2222-2222-222222222222"

RENDER_ARGS = [
    "render",
    "--version-code", "1062",
    "--version-name", "1.2.0-test",
    "--url", "https://tv.kevbox.dev/x.apk",
    "--sha256", "abc",
    "--notes", "Test",
    "--profiles-json", "[]",
    "--now-ms", "1000",
]


def run_cli(argv: list[str], early_users: str | None) -> tuple[int, str, str]:
    env = {k: v for k, v in os.environ.items() if k != "SILENT_EARLY_USERS"}
    if early_users is not None:
        env["SILENT_EARLY_USERS"] = early_users
    out, err = io.StringIO(), io.StringIO()
    with mock.patch.dict(os.environ, env, clear=True), \
            contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = main(argv)
    return code, out.getvalue(), err.getvalue()


def manifest(silent: dict | None, notes: str = "Test") -> dict:
    return json.loads(render_manifest(
        version_code=1062,
        version_name="1.2.0-test",
        url="https://tv.kevbox.dev/x.apk",
        sha256="abc",
        notes=notes,
        profiles=[],
        silent=silent,
    ))


class EarlyUsersTests(unittest.TestCase):
    def test_two_valid_ids_give_a_silent_block_with_both_after_now_plus_one_day(self) -> None:
        users = parse_early_users(f"{ID_A}, {ID_B}")
        self.assertEqual(silent_block(1000, users), {"after": 1000 + 86_400_000, "earlyUsers": [ID_A, ID_B]})
        self.assertEqual(SILENT_DELAY_MS, 86_400_000)

        code, out, _ = run_cli(RENDER_ARGS, f"{ID_A}, {ID_B}")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["silent"], {"after": 86_401_000, "earlyUsers": [ID_A, ID_B]})

    def test_blank_or_missing_early_users_give_an_empty_list(self) -> None:
        for raw in (None, "", "   "):
            with self.subTest(raw=raw):
                self.assertEqual(parse_early_users(raw), [])
                code, out, _ = run_cli(RENDER_ARGS, raw)
                self.assertEqual(code, 0)
                self.assertEqual(json.loads(out)["silent"]["earlyUsers"], [])

    def test_an_email_entry_is_rejected_with_its_position_and_without_its_value(self) -> None:
        email = "kevin@example.com"
        with self.assertRaises(ValueError) as caught:
            parse_early_users(f"{ID_A},{email}")
        self.assertEqual(str(caught.exception), "SILENT_EARLY_USERS entry 2 is not a UUID")

        code, out, err = run_cli(["check"], email)
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("SILENT_EARLY_USERS entry 1 is not a UUID", err)
        self.assertNotIn(email, err)

        code, out, err = run_cli(RENDER_ARGS, email)
        self.assertEqual((code, out), (1, ""))
        self.assertNotIn(email, err)

    def test_an_empty_piece_is_rejected(self) -> None:
        for raw, position in ((f"{ID_A},,{ID_B}", 2), (f"{ID_A},", 2)):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError) as caught:
                    parse_early_users(raw)
                self.assertEqual(str(caught.exception), f"SILENT_EARLY_USERS entry {position} is not a UUID")

    def test_check_accepts_a_valid_list(self) -> None:
        code, out, err = run_cli(["check"], f"{ID_A},{ID_B}")
        self.assertEqual((code, out, err), (0, "", ""))


class ReleaseArgsTests(unittest.TestCase):
    def check_args(self, *args: str) -> tuple[int, str, str]:
        return run_cli(["check-args", *args], None)

    def test_valid_argument_lists_pass(self) -> None:
        for args in ((), ("1.2.3",), ("1.2.3", "Fix", "login"), ("--no-silent", "1.2.3", "notes"),
                     ("1.2.3", "-1 crash on start")):
            with self.subTest(args=args):
                self.assertEqual(self.check_args(*args), (0, "", ""))

    def test_no_silent_after_the_version_stops_the_release(self) -> None:
        code, out, err = self.check_args("1.2.3", "--no-silent", "notes")
        self.assertEqual((code, out), (1, ""))
        self.assertIn("Argument 2: --no-silent must come first", err)

        code, _, err = self.check_args("1.2.3", "notes", "--no-silent")
        self.assertEqual(code, 1)
        self.assertIn("Argument 3", err)

    def test_no_silent_twice_stops_the_release(self) -> None:
        code, _, err = self.check_args("--no-silent", "--no-silent", "1.2.3")
        self.assertEqual(code, 1)
        self.assertIn("Argument 2: --no-silent must come first", err)

    def test_any_other_double_dash_argument_stops_the_release(self) -> None:
        for args, position in ((("--no-silnet", "1.2.3"), 1), (("1.2.3", "--force"), 2),
                               (("--no-silent", "1.2.3", "--dry-run"), 3)):
            with self.subTest(args=args):
                code, out, err = self.check_args(*args)
                self.assertEqual((code, out), (1, ""))
                self.assertIn(f"Argument {position}: unknown option", err)

    def test_a_version_name_starting_with_a_dash_stops_the_release(self) -> None:
        for args, position in ((("-n", "notes"), 1), (("--no-silent", "-1.2.3"), 2)):
            with self.subTest(args=args):
                code, _, err = self.check_args(*args)
                self.assertEqual(code, 1)
                self.assertIn(f"Argument {position}: unknown option", err)


class RenderTests(unittest.TestCase):
    def test_no_silent_writes_no_silent_key(self) -> None:
        self.assertNotIn("silent", manifest(None))

        code, out, _ = run_cli(RENDER_ARGS + ["--no-silent"], "kevin@example.com")
        self.assertEqual(code, 0)
        self.assertNotIn("silent", json.loads(out))

    def test_keys_come_in_the_published_order(self) -> None:
        keys = list(manifest(silent_block(0, [])).keys())
        self.assertEqual(keys, ["versionCode", "versionName", "url", "sha256", "notes", "profiles", "silent"])

    def test_notes_with_newlines_and_tabs_render_as_one_line(self) -> None:
        notes = manifest(None, notes='Line one\nLine\ttwo\r\n"quoted" \\ $HOME `x`')["notes"]
        self.assertEqual(notes, 'Line one Line two  "quoted" \\ $HOME `x`')

    def test_output_is_pretty_printed_and_ends_with_a_newline(self) -> None:
        text = render_manifest(version_code=1, version_name="1.0", url="u", sha256="s", notes="é",
                               profiles=[{"minApi": 28, "maxApi": 30, "url": "p", "sha256": "q"}], silent=None)
        self.assertTrue(text.endswith("}\n"))
        self.assertIn('  "versionCode": 1,', text)
        self.assertIn('"notes": "é"', text)


if __name__ == "__main__":
    unittest.main()
