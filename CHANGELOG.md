# Changelog

## 0.0.10017 - 2026-08-12

- Record that `BluetoothGattServer.cancelConnection` did not disconnect Google Health's physical Air link.
- Add an explicit one-packet DNCS send over the shared server link, even before a CCCD callback, and record `onNotificationSent`.
- Listen for Bluetooth adapter state changes and automatically re-register the probe DNCS service when Bluetooth is turned back on.
- Add a shortcut to Bluetooth settings for an initial-service-discovery test while Google Health remains enabled.

## 0.0.10016 - 2026-08-12

- Confirm that a second app-side GATT server joins the physical Air connection already owned by Google Health.
- Add an explicit one-time server-link disconnect while retaining the probe's DNCS service registration.
- Let Google Health reconnect Air so the device can perform service discovery with the probe already present.

The reconnect action is manual and only intended when no CCCD subscription appears after waiting 30 seconds.

## 0.0.10015 - 2026-08-12

- Record the device-verified failure of v10014 time-sharing: Google Health keeps its companion-device connection after its UI is closed.
- Add a DNCS coexistence probe that opens a phone-side GATT server without scanning for or connecting to Air.
- Show the system GATT and GATT-server connection lists and all service/read/write/CCCD callbacks.
- Gate the DNCS test send button on an observed Notification Source subscription.
- Add a unit-tested encoder for the eight-byte Fitbit DNCS Notification Source record.

This build is a protocol probe, not a completed coexistence solution.

## 0.0.10014 - 2026-08-12

- Replace the visible, persistent notification connection with a foreground one-shot service.
- Save a selected Fitbit Air address for notification-triggered tasks.
- Scan, connect, establish DTLS, execute and verify a pattern, then disconnect.
- Add a serialized task queue and two bounded retries when Air is temporarily unavailable.
- Replace the inherited Golden Gate setup screen with a dedicated Bridge setup/status screen.
- Add a manual `single` one-shot test.

Device validation of the Google Health time-sharing behavior is still required.

## 0.0.10013 - 2026-08-12

- Add Android notification listener rules for up to five application packages.
- Add `single`, `double`, `triple`, `long_gap`, and `urgent` patterns.
- Verify notification-to-pattern triggering while the connection screen is open.
