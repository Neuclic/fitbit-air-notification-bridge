# Fitbit Air protocol notes

These notes document behavior observed through clean-room interoperability testing. They do not contain Google Health application code, credentials, or device keys.

## Verified transport

```text
Android app
  -> BLE GATT
  -> Golden Gate Gattlink
  -> DTLS session using the device BOOTSTRAP identity
  -> CoAP
  -> Fitbit Air
```

## Verified haptic setting exchange

The current haptic setting can be requested with `PUT /settings` and a request containing `SETTING_TYPE_HAPTICS`.

Observed response for `HIGH`:

```text
0A040A020803
```

Changing the active intensity with `POST /settings` causes a short preview vibration. Repeating the same value does not cause the preview.

The bounded haptic primitive used by this project is:

```text
read original intensity
POST alternate intensity
wait 800 ms
POST original intensity
read and verify original intensity
```

On the tested device, `HIGH -> LOW -> HIGH` produced a weak short vibration followed by a strong short vibration and left the setting at `HIGH`.

## Pattern semantics

One pattern group is one complete toggle/restore primitive. Multiple groups are separated by an additional delay:

| Pattern | Groups | Additional delays between groups |
|---|---:|---|
| `single` | 1 | none |
| `double` | 2 | 650 ms |
| `triple` | 3 | 650 ms, 650 ms |
| `long_gap` | 2 | 1800 ms |
| `urgent` | 4 | 450 ms, 1350 ms, 450 ms |

Actual perceived timing also includes BLE/CoAP response latency. Patterns therefore require device testing and are not real-time waveforms.

## Connection ownership and failed v10014 time-sharing assumption

Interoperability testing indicates that Fitbit Air accepts only one active Gattlink/DTLS owner. A persistent bridge connection therefore prevents Google Health from connecting, and vice versa.

v10014 attempted this notification path:

```text
notification listener (disconnected)
  -> foreground one-shot task
  -> wait for the saved Air address to advertise
  -> Gattlink + BOOTSTRAP DTLS
  -> execute and verify pattern
  -> close the Golden Gate peer and BLE connection
  -> stop the foreground task
```

Device validation disproved the assumption behind this design. Closing the
Google Health activity does not stop its companion-device background service.
Google Health keeps the Air link, the Air does not advertise for the bridge,
and all three bounded attempts can expire before a pattern is executed.

The Android BLE API does not expose another application's `BluetoothGatt` or
Golden Gate/DTLS session, so a normal third-party APK cannot directly reuse the
connection owned by Google Health.

## DNCS coexistence probe (v10015)

The Google Health APK declares a phone-side Fitbit Device Notification Center
Service (DNCS) GATT server. It uses these UUIDs:

| Attribute | UUID |
|---|---|
| Service | `16bcfd00-253f-c348-e831-0db3e334d580` |
| Control Point | `16bcfd01-253f-c348-e831-0db3e334d580` |
| Notification Source | `16bcfd02-253f-c348-e831-0db3e334d580` |
| Data Source | `16bcfd03-253f-c348-e831-0db3e334d580` |
| Location Status | `16bcfd04-253f-c348-e831-0db3e334d580` |

The Notification Source record is eight bytes:

```text
event(1), flags(1), category(1), categoryCount(1), uidLE(4)
```

v10015 adds a diagnostic local GATT server using this layout. It does not scan
for, connect to, disconnect from, or change settings on Air. It records whether
an Air already connected by Google Health discovers or subscribes to the second
DNCS service instance. A test notification can only be sent after a real CCCD
subscription is observed.

This is an experiment, not a claimed notification transport. The decisive
device result is whether Air produces DNCS read/write/CCCD callbacks while
Google Health remains connected.

Initial v10015 device testing confirmed that the probe receives a GATT Server
`CONNECTED` callback for the same Air link owned by Google Health and that the
duplicate DNCS service is accepted with status 0. No CCCD callback was visible
in the first second after registration. v10016 therefore adds a manual,
one-time `BluetoothGattServer.cancelConnection` test: the probe keeps its DNCS
service registered while Google Health reconnects Air, allowing service
discovery to begin with both server instances already present.

The v10016 one-time cancellation produced no disconnect callback and the
system still listed Air under both GATT client and GATT server profiles. This
shows that cancellation from the probe does not tear down the physical link
owned by Google Health. v10017 therefore tests two remaining questions: whether
Android will send a Notification Source value on the shared server link before
a CCCD callback, and whether Air subscribes when Bluetooth is restarted and the
probe re-registers DNCS during the initial reconnection/service discovery.

The complete v10017 device run answered both questions negatively. Air made no
DNCS read, write, or CCCD request before or after a full Bluetooth restart.
Android accepted two unsubscribed Notification Source sends and reported
`onNotificationSent status=0`, but Air did not vibrate. Those callbacks prove
only local ATT transport completion; they are not evidence that Air consumed
the record. DNCS is therefore rejected as the bridge transport for this device.

## Known-address dual-client probe (v10018)

v10018 removes the advertising precondition from the one-shot path. It imports
the saved Air address from Android's connected GATT-device list when available,
or registers that address directly with BitGatt, and then attempts the normal
Gattlink/BOOTSTRAP-DTLS setup without a BLE scan.

This does not reuse Google Health's `BluetoothGatt` object or DTLS session. It
creates a second app GATT client over whatever physical BLE link Android can
provide. The decisive device checks are: whether Gattlink/DTLS reaches session,
whether the short haptic executes, and whether Google Health can still perform
a real Air read/sync after the bridge releases its own client. Until all three
are observed, this remains a coexistence probe rather than a supported mode.

The v10018 device result showed that the second client could establish DTLS and
execute the haptic. After the bridge closed only its own GATT client, however,
Google Health could no longer sync and displayed error 3000. The Android system
link surviving is therefore not a valid handoff signal: Air's active protocol
session had changed while Google Health retained stale connection state.

## Explicit physical-link handoff (v10019)

v10019 treats simultaneous DTLS ownership as disproved. After executing and
verifying the haptic, it first publishes Link Configuration's disconnect request
to Air, falling back to the General Purpose Disconnect command. Only after that
request completes or times out does it close the bridge peer. It then polls the
system GATT profile for an actual disconnect/reconnect cycle.

The decisive result is not merely a final `CONNECTED` snapshot. The app must
report `sawDisconnect=true`, and Google Health must subsequently complete a real
manual sync. If Air is not subscribed to this app's duplicate Link Configuration
service, the handoff will report `UNAVAILABLE`; a stock app has no public Android
API that can force another app's GATT client to renegotiate DTLS.

The device result was `PREFERRED_CONFIG_DISCONNECT` with
`systemGatt(before/after)=true/true` and `sawDisconnect=false`. Google Health
still returned error 3000 until its UI was normally closed and reopened. The
command completion was therefore only evidence that this client finished the
request; it was not evidence of a tracker-originated physical disconnect or a
successful ownership handoff.

## User-authorized Health task refresh (v10020)

v10020 removes the disproved disconnect claim. After a verified haptic it closes
the bridge peer, then offers a button and a non-ongoing notification action that
starts Google Health's public launcher with a cleared Activity task. This is a
visible user action, not a background sync API, process force-stop, or privilege
escalation. Device testing must still establish whether the new Activity
lifecycle is enough to discard the stale protocol session and complete a real
sync without error 3000.

The same build preserves every failed connection attempt instead of replacing
the first error with the eventual success. Failures are labelled at
`RUNTIME_INIT`, `GATT_PREPARE`, `Gattlink/DTLS`, or `HAPTIC`. The known-device
import settle window is also increased from 500 ms to 1500 ms. Those results
distinguish a local client-initialization race from a DTLS ownership transition.

### v10020 device result

The first attempt reported `systemGatt=true`, `localGatt=true`,
`source=SYSTEM_IMPORT`, and then timed out after 18 seconds at
`Gattlink/DTLS`. The second attempt succeeded after a two-second retry delay and
executed one verified haptic group. This rules out the known-device import wait
as the primary cause and supports the single-session takeover interpretation.

Afterward, the v10020 recovery action cleared and relaunched Google Health's
Activity task, but a real sync still returned error 3000. UI lifecycle restart
is therefore insufficient; the stale protocol state survives in Google
Health's longer-lived background process. Privilege-assisted force-stop and
manual ownership switching have both been rejected as product workflows. See
`docs/OPEN_PROBLEMS.md` for the current blocker and exit criteria.

## Google Health 5.06.1 Switchboard notification path

Read-only inspection of the exact 5.06.1 artifact, followed by an independent
encoder implementation, identifies a different firmware-side notification
primitive from the DNCS and haptic-setting probes:

```text
device notification
  -> Mobile Data protocol 0x6001
  -> SwitchboardNotificationRequest(commandId, record)
  -> CREATE: POST sb/records
  -> DELETE: POST sb/delete-records
```

The older `GoldenGateCommandHandler.sendSecureAppNotification` and
`sendUnsecuredAppNotification` methods delegate to an unimplemented default in
this release. They are not the active path.

The protobuf types bundled in 5.06.1 establish the following record shape
without requiring a captured owner payload:

- record version `0.4.10`;
- root and child IDs in the mobile `10xx...` fixed32 namespace;
- an immediate scheduler configuration containing event `0x30` and the popup
  expression ID as its context;
- a POSIX-timestamp TTL configuration;
- a notification-common expression containing app metadata and text;
- a popup expression referencing that common expression;
- a repeated uint32 root ID in the delete command.

The normal builder leaves `haptic_pattern` absent, so the first interoperability
probe also omits it and lets firmware select its default notification behavior.
No account, device key, owner identifier, or captured notification payload is
embedded in the encoder or tests.

### Device-model gating visible in 5.06.1

Google Health keeps three relevant facts separate:

1. `supportsNotifications` is derived from whether the server tracker settings
   contain `notificationTypes`.
2. The current `notifications` setting is a nullable Boolean. The test-notification
   selector chooses the first device for which this value is true.
3. Switchboard support is the presence of a per-device
   `SwitchboardSpecification`, including its supported Mobile Data protocols and
   message-size limits.

These tables are populated at runtime. Static inspection of the APK therefore
cannot prove Air's current values, and a public paired-device feature list is not
a substitute for the firmware resource result.

### v10021 bounded manual probe

The v10021 research build adds a foreground-only entry on the Setup screen. It
shows the error-3000 warning, gives BitGatt a bounded chance to import Android's
existing connection, and otherwise registers the saved Air address without a
scan before opening the CoAP diagnostics activity. The actual protocol test is
still two-click:

1. `GET /.well-known/core` with no retransmission. The write step remains locked
   unless both `/sb/records` and `/sb/delete-records` are advertised in a
   successful response.
2. A second explicit click creates one minimal official-shaped notification,
   waits two seconds, then deletes its root record. Creation is never retried,
   and a 60-second TTL is the cleanup fallback if the activity or link ends.

### v10021 device result and corrected gate

The device test reached a BOOTSTRAP DTLS session, then returned `4.04` to
`GET /.well-known/core` in 807 ms. No `sb/*` path was parsed, the create/delete
gate remained false, and the build did not write a record. This is a valid safe
negative result for the discovery assumption only. It does **not** show that
`sb/records` or `sb/delete-records` is absent.

A second bytecode pass established the exact lower call chain:

```text
MobileDataCommandsHandler.writeMobileData(..., 0x6001, ...)
  -> NotificationSenderCommandHandler.send(...)
  -> CoapNotificationRecordCreator.create(record)
       -> POST "sb/records"
  -> CoapNotificationRecordRemover.remove(ids)
       -> POST "sb/delete-records"
```

Both helpers build requests directly on the device's mapped CoAP endpoint; the
official path has no preceding `/.well-known/core` request. Mobile Data
`0x6001` is therefore the app-level dispatch ID, while the final device requests
are ordinary CoAP requests on the current Golden Gate session.

v10022 replaces directory discovery with two direct, read-only method probes:

1. send one no-payload, no-retry `GET sb/records`;
2. send one no-payload, no-retry `GET sb/delete-records`;
3. qualify each path only on `2.xx` or exact `4.05 Method Not Allowed`;
4. fail closed on `4.04`, timeout, or any other response;
5. allow exactly one manual create attempt per successful two-path gate, then
   require qualification again.

`4.05` is useful here because it distinguishes a known POST-only resource from
an unknown path without changing device state. A successful GET is also
read-only evidence; its body is consumed but never displayed or persisted. The
gate is intentionally conservative: some firmware routers could still answer
`4.04` for a method mismatch, which would produce a false negative rather than
unlock a write without enough evidence.

### v10022 read-only device result

The direct gate passed on the same test Air while the bridge owned a BOOTSTRAP
DTLS session. Both path probes completed in 116 ms and reported path evidence;
the visible cleanup result was `GET sb/delete-records -> 4.05`, and the UI moved
to the explicit `2/2` write step with `direct create+delete gate=true`. The
retained screenshot clips the create path's exact response code, so the supported
claim is only that it was one of the gate's accepted values (`2.xx` or `4.05`).
No record had been written at this point.

This establishes that Air's CoAP router contains both known Switchboard paths on
the bridge-owned session. It does not yet establish that `POST sb/records`
accepts the independently encoded record, that a record produces a haptic, or
that Google Health can continue syncing afterward.

### v10022 write result

The user then invoked the explicit second step exactly once. `POST sb/records`
returned `4.01 Unauthorized` in 529 ms. Air did not vibrate. Because the create
response was not `2.xx`, the probe stopped before its timer and did not send
`POST sb/delete-records`; the displayed TTL text was a generic failure fallback,
not evidence that a record had been created. After the bridge disconnected,
Google Health manual sync returned error 3000.

This result establishes all of the following on the tested BOOTSTRAP session:

- the two Switchboard paths are registered;
- read-only method probing is permitted;
- creating this official-shaped record is not authorized;
- no haptic is produced on rejection;
- merely taking the second Gattlink/DTLS session still invalidates Google
  Health's usable session.

### Google Health 5.06.1 secure-key boundary

The authorization re-audit found a separate production DTLS identity chain in
5.06.1:

```text
TlsKeyResolverRegistry
  -> MobileDataTlsKeyResolver
  -> MD-{key-id}-{expiration} identity
  -> MobileDataKeyProvider
  -> MobileDataKeyManager
  -> primaryKey / secondaryKey AES secrets
```

The manager downloads the two keys and their IDs through Google Health's
authenticated internal device client under the device `keys` route. It Base64
decodes them, stores them as AES `SecretKey` entries in a BKS file opened through
the app's private `Context`, and resolves a requested Mobile Data identity by
key ID. No actual key, account token, device identifier, or private response was
read or retained during this audit.

The Mobile Data Key GATT characteristic does not provide a key-extraction
shortcut. In 5.06.1, its read path converts the returned bytes to a UTF-8 string,
consistent with the current key identity/state. The update path takes a key ID,
loads the corresponding secret bytes from the private provider, and writes those
bytes to the same GATT resource. Directionally, Google Health provisions Air;
Air does not export the server-provided AES secret through this read path.

Provisioning an arbitrary replacement key from the bridge would modify Air's
trust state and could rotate or displace the key used by Google Health. It would
not reuse Google Health's session, and it conflicts with both the no-disruption
boundary and the observed error-3000 blocker. That experiment is explicitly
rejected.

This makes secure DTLS identity the strongest explanation for the contrast
between BOOTSTRAP-accessible settings/read-only paths and the Switchboard
`4.01`. It remains an inference because the firmware authorization table is not
available; a device feature/entitlement gate could also contribute.

The current public [Google Health paired-device resource](https://developers.google.com/health/reference/rest/v4/users.pairedDevices)
provides only `get` and `list`, with output-only device metadata and feature
strings under `googlehealth.settings.readonly`. It exposes neither device keys
nor a command/session operation. Consequently, a stock third-party app cannot
legitimately obtain Google Health's Mobile Data key or attach to its existing
DTLS session through that API.

No further independent-session Switchboard write should be attempted. A next
implementation experiment requires a documented ordinary-app API/IPC that
reaches Google Health's existing authorized handler, or an official device
command API. Neither is currently established.

### 2026-09-02 follow-up: remaining 5.06.1 openings

The freeze left two reopen conditions. Both were re-checked against the same
5.06.1 artifact (`versionCode` 1104101009) and current public docs. This
follow-up did not open a second Gattlink/DTLS session, did not repeat
`POST sb/records`, and did not read key material.

**Official command API.** The public Google Health `users.pairedDevices`
resource is still `get`/`list` only under `googlehealth.settings.readonly`.
Feature strings are output-only. The API has no device-command, notification,
or DTLS-key method. The legacy Fitbit Web API device surface can manage
alarms on some paired devices, not Air Switchboard records, and is scheduled
to shut down in September 2026. Fitbit OS companion messaging is for watch
apps, not Air firmware CoAP.

**Ordinary-app IPC into the live Air handler.** A read-only dump of the 5.06.1
`AndroidManifest.xml` found 185 components. Nothing exported is a Golden Gate
command, Switchboard, haptic, or CoAP entry:

| Candidate | Why it is not a Bridge transport |
|---|---|
| `AllAppNotificationListenerService` | System-bound DNCS observer. Google Health *receives* phone notifications; a third-party app cannot bind it. |
| `NotificationBroadcastReceiver` plus `ACTION_NOTIFICATION_CAME` | The only in-app notification IPC found. It is `exported=false`, so another APK cannot send it. |
| `CompanionService` | Requires `BIND_COMPANION_DEVICE_SERVICE`. The OS binds it, not a third-party app. |
| `fitbit://` deeplinks (`tracker`, `device-management`, `deeplink-notification-click`, …) | Open Health UI. They do not submit a Switchboard record or reuse the DTLS session. |
| `WearService`, Health Connect SDK bind, GPay MSE, navigation consumer | Unrelated surfaces. No exported ContentProvider exists. |
| BitGatt `ScannedDevice` / ACL receivers | BLE scan/link bookkeeping, not a command API. |

`GoldenGateCommandHandler`, `writeMobileData`, `CoapNotificationRecordCreator`,
and `NotificationSenderCommandHandler` remain in-process. There is no
`IGoldenGate` AIDL. `sendGenericCommand` still resolves to an unimplemented
stub string in this build.

**POST `4.01` vs GET `4.05`.** Golden Gate's CoAP group filter returns
`4.01 Unauthorized` when a handler is outside the current identity group.
`GROUP_0` is authenticated; `GROUP_1` is the non-authenticated/BOOTSTRAP
side. The v10022 GET results reached the resources (`4.05` / accepted
existence evidence) while the single POST returned `4.01`. That pattern is
method-and-identity authorization on an existing path, not a missing
protobuf field. Changing the record shape cannot promote a BOOTSTRAP
session into the Mobile Data group.

The phone-side stack only switches its *own* filter to `GROUP_0` when the
peer PSK identity is not `BOOTSTRAP` or `hello`. Air's `GET 4.05` / `POST 4.01`
split is consistent with the same identity mapping on the tracker, but the
firmware table itself was not read. The Mobile Data PSK is still downloaded
by Google Health's authenticated internal client and stored in its private
BKS file.

An independent Bridge session therefore still cannot both authorize
`POST sb/records` and leave Google Health's session intact. Even a future
BOOTSTRAP write success would not clear error 3000.

The remaining implementation experiment is documented in
`research/google-health-506-same-session/`. It re-signs this exact 5.06.1
build, loads Frida Gadget only in the main Google Health process, and reuses
the live `GoldenGateCommandHandler.sendGenericCommand(String, byte[], byte)`
overload. That is not a stock third-party APK. Device results for Stage A
control, Gadget load, `/settings` reuse, and same-session `POST sb/records`
are not yet recorded.

The result must be interpreted narrowly:

| Device result | Supported conclusion |
|---|---|
| Either direct GET is `4.04`, errors, or times out | v10022 has insufficient path evidence and performs no write. |
| Both direct GETs are `2.xx` or `4.05` | Both known paths have read-only existence evidence; no record has yet been written. |
| Create returns `4.01` under BOOTSTRAP | The path exists, but this session is not authorized to create the record. |
| Create/delete succeed, no haptic | The record path alone is insufficient to produce an Air haptic. |
| Create/delete succeed and Air vibrates | Air accepts a Switchboard notification primitive while Bridge owns the session. |

None of these outcomes proves coexistence with Google Health. This probe still
uses the bridge's own Gattlink/DTLS session and must not be wired to the Android
notification listener unless a separate stock, unprivileged same-session route
passes the P0 exit criteria.
