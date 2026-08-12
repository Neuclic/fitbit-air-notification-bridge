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
