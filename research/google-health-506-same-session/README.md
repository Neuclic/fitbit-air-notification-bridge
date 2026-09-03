# Google Health 5.06.1 same-session research

This directory is the **same-process research path** for Fitbit Air notifications
on Google Health 5.06.1. It does not create a second `BluetoothGatt`, `StackPeer`,
Gattlink connection or DTLS session.

Independent Bridge sessions already proved that Air can vibrate over
BOOTSTRAP/CoAP, and that `POST sb/records` returns `4.01 Unauthorized` on that
identity. They also displace Google Health and leave real sync failing with
error `3000`.

This path tests the remaining question:

```text
Google Health's existing BluetoothGatt / Gattlink / DTLS / CoAP session
                              ↓
                  live GoldenGateCommandHandler
                              ↓
              PUT/POST /settings  or  POST sb/records
                              ↓
                         Air haptic
                              ↓
                Google Health still synchronizes normally
```

Target build:

```text
package:     com.fitbit.FitbitMobile
version:     5.06.1.health-mobile-1104101009-966217170
versionCode: 1104101009
ABI:         arm64-v8a
```

A positive result clears the protocol-session ownership blocker for this
research build. It is not a stock third-party APK and is not a product release.

## Why two stages

A stock Android device does not allow another ordinary app to inject code into a
non-debuggable Google Health process. Embedding Frida Gadget changes the APK, so
the package must be signed with a research certificate.

A re-signed package may fail for reasons unrelated to Frida: App Link
certificate verification, account linking, Play Integrity, or a server-side
certificate allowlist. The experiment therefore uses two builds signed with the
**same research keystore**:

```text
Stage A — re-sign-only control
  exact manifest + exact DEX + no Gadget
  ↓
  validate startup, sign-in, pairing and two real Air syncs

Stage B — Gadget build
  minimal Application/DEX patch + Gadget
  ↓
  install as a same-signer in-place update over Stage A
```

| Result | Meaning |
|---|---|
| Control cannot sign in/pair/sync | Signing, account-linking or integrity enforcement blocks the path before instrumentation. |
| Control passes, Gadget build fails | The problem is Gadget loading or the wrapper patch, not the research signature alone. |
| Gadget build passes, `/settings` read and haptic pass, then Health syncs | The live handler is reusable without a second protocol session. |
| `POST sb/records` through that handler returns 2.xx and Air vibrates | Switchboard is authorized on the existing session; v10022's BOOTSTRAP `4.01` was identity, not record shape. |
| Any of those writes is followed by error 3000 | Same-session reuse is falsified even if the command appears to complete. |

## Data-loss warning

Google Health 5.06.1 declares `allowBackup=false`. Replacing the Google-signed
package with a research-signed package removes local app data. Before
uninstalling the official app:

1. Open official Google Health.
2. Confirm the latest Fitbit Air data has completed a real sync.
3. Record any locally important alarm or device settings.

## Repository files

```text
tools/build_resign_control.py
    Stage A. No manifest/DEX/Gadget changes; re-sign every split.

tools/patch_google_health_506.py
    Stage B. Equal-length Application rename, extractNativeLibs=true,
    clear ACC_FINAL, add wrapper DEX and Gadget, then re-sign.

tools/install_google_health_506.py
    Phone-specific split installer. Dry-run by default. Refuses to uninstall
    until --confirm-official-data-synced.

tools/update_research_google_health_506.py
    Same-signer in-place update. Never uninstalls.

tools/switchboard_record.py
    Clean-room encoder for one immediate Switchboard record plus its root
    delete command. Same shape as the v10022 Android probe.

frida/gh506_probe.js
    Observes sendGenericCommand and writeMobileData, retains a live handler,
    logs notification package names only, and exposes explicit read/replay/
    Switchboard commands. It never opens a BLE connection.

STATIC_VALIDATION.md
    Exact APKM hashes and deterministic binary-patch results.
```

No Google Health APK, Frida binary, credentials, tokens, device keys or
research signing key is committed.

## Prerequisites

- JDK 11 or newer: `javac` and `keytool`
- Android SDK Build-Tools 36.0.0 or newer: `d8`, `zipalign`, `apksigner`
- Android Platform-Tools: `adb`
- Python 3.10 or newer
- Official Frida Gadget for Android arm64, matching host `frida` tools

Keep the research keystore outside Git and reuse it for both stages.

## 1. Stage A control

```bash
python3 tools/build_resign_control.py \
  --apkm /path/to/com.fitbit.FitbitMobile_5.06.1....apkm \
  --build-tools "$HOME/Android/Sdk/build-tools/36.0.0" \
  --keystore /private/fba-research.jks \
  --output ./out-control
```

Dry-run install:

```bash
python3 tools/install_google_health_506.py --signed-dir ./out-control/signed
```

Install only after official data is synced:

```bash
python3 tools/install_google_health_506.py \
  --signed-dir ./out-control/signed \
  --install \
  --confirm-official-data-synced
```

Do not continue until all of these pass:

1. Google Health starts without a crash loop.
2. Account sign-in completes. If the browser callback does not return, enable
   **Open by default** for this app's supported links and retry.
3. Fitbit Air can be paired or restored.
4. Two consecutive real Air syncs complete without error `3000`.
5. Haptic-intensity pages open and keep a user-initiated setting change.
6. Background reconnection still works after the app is swiped away.

## 2. Stage B Gadget update

```bash
python3 tools/patch_google_health_506.py \
  --apkm /path/to/com.fitbit.FitbitMobile_5.06.1....apkm \
  --gadget /path/to/frida-gadget-*-android-arm64.so.xz \
  --build-tools "$HOME/Android/Sdk/build-tools/36.0.0" \
  --keystore /private/fba-research.jks \
  --output ./out-gadget
```

```bash
python3 tools/update_research_google_health_506.py \
  --signed-dir ./out-gadget/signed \
  --install \
  --confirm-control-passed
```

If ADB reports a signing mismatch, stop. Repeat the Stage A compatibility gate
before attaching Frida.

## 3. Attach without root

```bash
adb forward tcp:27042 tcp:27042
frida -H 127.0.0.1:27042 Gadget -l frida/gh506_probe.js
```

The Gadget config uses `on_load=resume`, so Google Health can boot when no
Frida client is attached.

## 4. Capture the live handler

In Google Health, change Air haptic intensity once and restore it. Then:

```javascript
fbaStatus()
```

Expected fields include `handlerCaptured: true` and `handlerReady: true`. If no
call is observed, run `fbaFindHandlers()` and repeat the official setting
change. Do not send writes until one ready handler is captured from an official
`/settings` call.

## 5. Read-only same-session test

```javascript
fbaReadHaptics()
```

This sends `PUT /settings` with `0A0101` through the retained handler and does
not change intensity. Immediately perform a real manual Air sync. Error `3000`
at this stage falsifies the same-session assumption.

## 6. Bounded haptic test

Precondition: Air haptic intensity is **HIGH** in Google Health.

```javascript
fbaPreviewHighLowHigh()
```

The script sends `POST /settings` LOW, waits 800 ms, then restores HIGH. It
never sets OFF. Then confirm:

1. Air produced the expected bounded preview.
2. The final setting is still HIGH.
3. A real manual sync completes with no error `3000`.
4. Repeat the haptic-plus-sync sequence 20 times.

## 7. Switchboard test — only after step 6 passes

```javascript
fbaSwitchboardGet()
fbaSwitchboardCreateOnce()
```

The GET is read-only. `4.05` is useful path evidence. The create step encodes
the same official-shaped record used by v10022, POSTs it once through the live
handler, and deletes the root two seconds later. It does not retry.

Interpret narrowly:

| Result | Meaning |
|---|---|
| GET times out or the handler is not ready | Stop. Session reuse is not established. |
| POST returns the in-process equivalent of `4.01` | Same-session CoAP is still not authorized for Switchboard. |
| POST succeeds, Air does not vibrate | Path authorization is not the same as a haptic primitive. |
| POST succeeds, Air vibrates, then Health syncs | Switchboard is usable on the existing session. |

Do not connect `NotificationListenerService` to an automatic Switchboard or
haptic action until the 20-repeat haptic gate and at least one successful
Switchboard-plus-sync sequence have passed.

## P0 exit criteria

The protocol-session ownership blocker is cleared only when one stock, non-root
phone demonstrates all of the following:

- Stage A passes account sign-in, Air pairing/restoration and two real syncs.
- Stage B preserves that app state and background operation.
- The request is sent by Google Health's retained `GoldenGateCommandHandler`.
- No Bridge `BluetoothGatt`, `StackPeer`, Gattlink or DTLS session is created.
- Air produces the requested bounded haptic.
- Google Health completes a real sync immediately afterward.
- Twenty consecutive haptic-plus-sync repetitions produce no error `3000`.
- Optional but required for the 5.06.1 notification claim: one Switchboard
  create/delete through the same handler produces a haptic and a clean sync.

## Reverting

Uninstall the research-signed package and reinstall Google Health from Google
Play. Because the certificates differ, the official app cannot replace the
research build in place.

## Safety and scope

This is a local interoperability experiment on the operator's own device. It is
not suitable for medical, emergency, authentication or safety-critical
notifications. Never publish APKs, account tokens, device keys, private
notifications, personal log files or the research keystore.
