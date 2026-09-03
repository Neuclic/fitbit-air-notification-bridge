# Changelog

## Research follow-up - 2026-09-02

- Re-check the two v10022 reopen conditions against Google Health 5.06.1
  (`versionCode` 1104101009) and current public docs. No second DTLS session
  and no additional `POST sb/records`.
- Confirm `users.pairedDevices` is still `get`/`list` metadata only. The
  legacy Fitbit Web API device alarm surface is not Air Switchboard and is
  scheduled to shut down in September 2026.
- Audit the 5.06.1 manifest: no exported component reaches
  `GoldenGateCommandHandler` or Switchboard. `NotificationBroadcastReceiver`
  exists but is `exported=false`. Deeplinks only open UI. There is no exported
  ContentProvider and no `IGoldenGate` AIDL.
- Record that v10022's `GET 4.05` then `POST 4.01` matches Golden Gate's
  authenticated (`GROUP_0`) versus BOOTSTRAP (`GROUP_1`) CoAP filter. A
  different record shape cannot authorize the write, and a successful
  independent-session write would still leave error 3000.

This follow-up does not change the APK. Independent-session 506 remains closed.

- Add `research/google-health-506-same-session/`: Stage A re-sign control, Stage B
  Gadget wrapper, and a Frida probe that reuses Google Health 5.06.1's live
  `GoldenGateCommandHandler` for `/settings` and `sb/records`. Device sign-in,
  sync, haptic, and Switchboard results are not yet recorded.

## 0.0.10022 - 2026-08-25

- Record the v10021 device result: the BOOTSTRAP DTLS session completed, but
  `GET /.well-known/core` returned `4.04` in 807 ms; the create/delete gate
  remained false and no Switchboard record was written.
- Verify from the 5.06.1 bytecode that Mobile Data protocol `0x6001` is
  special-cased into `CoapNotificationRecordCreator` and
  `CoapNotificationRecordRemover`, which directly POST to `sb/records` and
  `sb/delete-records`. The official sender does not depend on resource discovery.
- Replace the unsupported discovery precondition with one no-payload, no-retry
  GET to each known endpoint. Qualify only `2.xx` or exact `4.05 Method Not
  Allowed`; fail closed on `4.04`, timeout, or every other response.
- Permit exactly one create attempt per successful read-only qualification,
  then require the user to run the gate again. Preserve the two-second cleanup,
  60-second TTL fallback, omitted haptic pattern, and no create retry.
- Record the v10022 read-only device pass: both known paths qualified in 116 ms,
  `GET sb/delete-records` returned exact `4.05`, the create/delete gate became
  true, and no record had yet been written. The retained screenshot establishes
  a qualifying create-path response but clips its exact response code.
- Record the single bounded write result: `POST sb/records` returned `4.01
  Unauthorized` in 529 ms, produced no haptic, and therefore did not enter the
  delete step. After disconnecting the bridge, Google Health sync still returned
  error 3000.
- Trace 5.06.1 authorization to `MobileDataTlsKeyResolver`: Google Health fetches
  primary/secondary AES device keys through its authenticated internal device
  client, stores them in its app-private BKS file, and resolves `MD-*` DTLS
  identities by key ID. No key material was read or retained, and the public
  Google Health paired-device API exposes only `get`/`list` metadata.
- Confirm that the Air Mobile Data Key GATT characteristic is a provisioning
  channel, not a secret export: 5.06.1 reads it as a UTF-8 identity/state, while
  key updates fetch bytes from the private provider and write them to Air.
  Writing a replacement key would mutate device trust and is rejected.
- Freeze the independent-session Bridge direction under the current product
  constraints: stock no-root Android, Google Health remaining usable, and no
  manual ownership switching. Do not build another payload/key/rekey probe.
  Reopen implementation research only if an official device-command API or an
  ordinary-app-callable route into Google Health's existing authorized session
  is established first.

This release closes the bridge-owned Gattlink/DTLS direction as a product path.
It remains historical protocol evidence; it does not resolve the Google Health
error-3000 coexistence blocker and must not be used for daily notifications.

## 0.0.10021 - 2026-08-25

- Record the Google Health 5.06.1 Mobile Data/Switchboard notification path and
  its separate notification-setting and Switchboard-specification gates.
- Add a clean-room encoder for one immediate notification record plus its root
  delete command, with independent protobuf-structure unit tests.
- Require a successful read-only `/.well-known/core` response advertising both
  `sb/records` and `sb/delete-records` before enabling the manual write step.
- Add an explicit Setup-screen entry that warns about error 3000, imports the
  saved Air address without scanning, and opens the foreground CoAP probe.
- Submit at most one record per click, omit an explicit haptic pattern, disable
  create retries, delete after two seconds, and retain a 60-second TTL fallback.

This build tests only the Air firmware primitive while Bridge owns the session.
It does not reuse Google Health's DTLS session or resolve error 3000 coexistence.

## 0.0.10020 - 2026-08-12

- Preserve the stage and error from every known-address connection attempt
  instead of replacing the first failure with an eventual retry success.
- Increase the local known-device import settle window and label failures at
  runtime initialization, GATT preparation, Gattlink/DTLS, or haptic execution.
- After releasing the Bridge client, offer a visible user-tapped action that
  clears and relaunches only Google Health's Activity task.

Device testing showed that the Activity relaunch does not recover the stale
Google Health protocol session; real sync still reports error 3000.

## 0.0.10019 - 2026-08-12

- Record that v10018 can join the existing Android GATT link and execute the haptic, but a local-client-only release leaves Google Health unable to sync with error 3000.
- After the haptic, request a tracker-originated physical disconnect through Link Configuration, with General Purpose Disconnect as a fallback.
- Observe the Android system GATT state for a real disconnect/reconnect cycle before reporting the handoff result.
- Never retry the haptic merely because the post-haptic handoff command is unavailable.

This build tests explicit ownership handoff, not simultaneous DTLS coexistence.

## 0.0.10018 - 2026-08-12

- Record the v10017 device result: duplicate DNCS never receives a READ/WRITE/CCCD, and Android-level notification completion produces no Air haptic.
- Remove BLE scanning from the primary one-shot experiment.
- Import the saved Air from Android's connected GATT devices or register its known address directly, then attempt a second GATT client and Gattlink/DTLS session.
- Report system/local GATT state and the failing transport stage, and snapshot system GATT state again after releasing the bridge client.

This build is a coexistence protocol probe. A successful haptic must also be followed by a real Google Health read/sync check.

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
