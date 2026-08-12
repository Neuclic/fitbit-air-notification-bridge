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
