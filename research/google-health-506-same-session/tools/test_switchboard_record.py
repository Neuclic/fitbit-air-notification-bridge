#!/usr/bin/env python3
"""Unit tests for the clean-room Switchboard encoder used by the 5.06.1 probe."""

from __future__ import annotations

import unittest

from switchboard_record import encode_default_notification, to_hex


class SwitchboardRecordTest(unittest.TestCase):
    def test_matches_v10022_kotlin_vectors(self) -> None:
        encoded = encode_default_notification(
            id_seed=1,
            app_id="test.app",
            app_name="Air Bridge",
            title="Test",
            content="Notification probe",
            timestamp_epoch_seconds=100,
            expires_at_epoch_seconds=160,
        )
        self.assertEqual(0x80000001, encoded.unsigned_root_id)
        self.assertEqual(160, encoded.expires_at_epoch_seconds)
        self.assertEqual("088180808008", to_hex(encoded.delete_command).lower())
        self.assertTrue(encoded.record)

    def test_omits_haptic_by_default(self) -> None:
        encoded = encode_default_notification(
            id_seed=20,
            app_id="test.app",
            app_name="",
            title="",
            content="",
            timestamp_epoch_seconds=1,
        )
        self.assertNotIn(b"\x50", encoded.record[-12:])

    def test_includes_haptic_when_requested(self) -> None:
        with_haptic = encode_default_notification(
            id_seed=20,
            app_id="test.app",
            app_name="",
            title="",
            content="",
            timestamp_epoch_seconds=1,
            haptic_pattern=7,
        )
        without = encode_default_notification(
            id_seed=20,
            app_id="test.app",
            app_name="",
            title="",
            content="",
            timestamp_epoch_seconds=1,
        )
        self.assertGreater(len(with_haptic.record), len(without.record))


if __name__ == "__main__":
    unittest.main()
