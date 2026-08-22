# Google Health 5.05 non-root in-process research

This directory contains the **non-root research path** for the Fitbit Air notification project.
It does not create a second `BluetoothGatt`, `StackPeer`, Gattlink connection or DTLS session.
Instead, it tests whether a research-signed copy of the exact Google Health build can retain normal
account, pairing and synchronization behavior, then loads Frida Gadget inside that same app process
to reuse Google Health's live `GoldenGateCommandHandler`.

Target build:

```text
package:     com.fitbit.FitbitMobile
version:     5.05.health-mobile-1104094185-956655267
versionCode: 1104094185
ABI:         arm64-v8a
```

## Research question

The existing independent Bridge has already proved that Air can be controlled through
Gattlink/BOOTSTRAP-DTLS/CoAP, but its second protocol session displaces Google Health and leaves a
real sync failing with error `3000`.

This branch tests a different path:

```text
Google Health's existing BluetoothGatt / Gattlink / DTLS / CoAP session
                              ↓
                  current GoldenGateCommandHandler
                              ↓
                    explicit /settings request
                              ↓
                         Air haptic
                              ↓
                Google Health still synchronizes normally
```

A positive result removes the protocol-session ownership blocker. It does **not** yet produce a
normal end-user application. The first goal is a clean, reproducible same-session proof on one
stock, non-root phone.

## Why the experiment has two stages

A stock Android device does not allow another ordinary app to inject code into a non-debuggable
Google Health process. Embedding Frida Gadget changes the APK, so the package must be signed with a
research certificate and cannot update over Google's installed copy.

A re-signed package may fail for reasons unrelated to Frida: App Link certificate verification,
Google Account Linking, Play Integrity, a server-side certificate allowlist or another signing
check. The experiment therefore uses two builds signed with the **same research keystore**:

```text
Stage A — re-sign-only control
  exact manifest + exact DEX + no Gadget
  ↓
  validate startup, sign-in, pairing and two real Air syncs

Stage B — Gadget build
  minimal Application/DEX patch + Gadget
  ↓
  install as a same-signer in-place update over Stage A
  ↓
  preserve the research app's login/pairing data
```

Interpretation:

| Result | Meaning |
|---|---|
| Control cannot sign in/pair/sync | The non-root path is blocked before instrumentation by signing, account-linking or integrity enforcement. |
| Control passes, Gadget build fails | The problem is Gadget loading, anti-instrumentation or the wrapper patch—not the research signature alone. |
| Gadget build passes, same-session read passes | Google Health's live handler is reusable without creating a second protocol session. |
| Haptic passes and subsequent sync passes 20 times | The existing error-3000 ownership blocker is cleared for this research build. |

## Data-loss warning

Before uninstalling the official Google-signed app:

1. Open the official Google Health app.
2. Confirm that the latest Fitbit Air data has completed a real sync.
3. Record any locally important alarm or device settings.

Google Health 5.05 declares `allowBackup=false`. Replacing the Google-signed package with a
research-signed package removes its local app data and may require account sign-in and Air
pairing/restoration again.

## Repository files

```text
tools/build_resign_control.py
    Builds the Stage A control. It does not modify the manifest or DEX and does not embed Gadget;
    it only removes old signatures, zipaligns and signs every split with the research key.

tools/patch_google_health_505.py
    Builds Stage B. It validates the exact APKM, builds a tiny wrapper DEX, performs narrowly scoped
    binary patches, embeds Frida Gadget/config, zipaligns and signs every split.

tools/install_google_health_505.py
    Selects arm64, density and language splits for the connected phone. Dry-run by default and
    refuses to uninstall until `--confirm-official-data-synced` is provided.

tools/update_research_google_health_505.py
    Installs Stage B over a validated Stage A control with `adb install-multiple -r`. It never
    uninstalls and stops safely on a signing mismatch.

wrapper-src/com/fitbit/FitbitHook_Application.java
    Loads Gadget only in process `com.fitbit.FitbitMobile`, not in `:primes_lifeboat`.

frida/libfba_bridge.config.so
    Listens only on localhost:27042 and uses `on_load=resume`, so Google Health starts even if a
    Frida client is not attached.

frida/gh505_probe.js
    Observes `sendGenericCommand`, retains a live handler, logs notification package names only and
    exposes explicit read/replay/preview commands. It never opens a BLE connection.

STATIC_VALIDATION.md
    Exact APKM hashes and deterministic binary-patch validation results.
```

No Google Health APK, Frida binary, credentials, tokens, device keys or research signing key is
committed to the repository.

## Prerequisites on Windows

- JDK 11 or newer: `javac` and `keytool`.
- Android SDK Build-Tools 36.0.0 or newer: `d8`, `zipalign`, `apksigner`.
- Android Platform-Tools: `adb`.
- Python 3.10 or newer.
- Official Frida Gadget for Android arm64.
- Matching Frida host tools. At the time this branch was prepared, Frida 17.17.0 was current.

Download the Gadget binary only from Frida's official release page. The corresponding asset is
normally named:

```text
frida-gadget-17.17.0-android-arm64.so.xz
```

The host-side `frida` package and the embedded Gadget should use the same version.

## 1. Build the Stage A re-sign-only control

Keep the research keystore outside any Git repository and reuse it for both stages:

```powershell
$ResearchKey = "C:\private\fitbit-air\fba-research.jks"
$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
$Apkm = "C:\path\to\com.fitbit.FitbitMobile_5.05....apkm"

py tools\build_resign_control.py `
  --apkm $Apkm `
  --build-tools $BuildTools `
  --keystore $ResearchKey `
  --output ".\out-control"
```

The script creates the keystore when it does not exist. Preserve it; losing or changing it makes an
in-place Stage B update impossible.

Expected output:

```text
out-control/
  build-info.json
  signed/
    base.apk
    split_config.arm64_v8a.apk
    split_config.en.apk
    split_config.zh.apk
    split_config.<density>.apk
    ...
```

## 2. Dry-run the phone-specific control install

Enable USB debugging and connect the phone:

```powershell
py tools\install_google_health_505.py `
  --signed-dir ".\out-control\signed"
```

The script reads the phone ABI, density and locale, then prints the exact split set and
`adb install-multiple` command. It changes nothing in dry-run mode.

## 3. Install Stage A only after official data is synchronized

```powershell
py tools\install_google_health_505.py `
  --signed-dir ".\out-control\signed" `
  --install `
  --confirm-official-data-synced
```

The official and research certificates differ, so this one transition necessarily uninstalls the
Google-signed package before installing Stage A.

## 4. Stage A compatibility gate

Do not build or install Gadget until all of these pass:

1. Google Health starts without a crash loop.
2. Google/Fitbit account sign-in completes.
3. Fitbit Air can be paired or restored to the account.
4. Google Health completes two consecutive real Air syncs without error `3000`.
5. Alarm and haptic-strength pages open and retain a normal user-initiated setting change.
6. Background reconnection still works after the app is swiped away and reopened.

### Account-link callback on a research signature

The exact 5.05 manifest contains these account-link callbacks:

```text
https://oauth-redirect.googleusercontent.com/a/com.fitbit.FitbitMobile
https://oauth-redirect.googleusercontent.com/fitbit_oauth2redirect
```

The production website association contains Google's signing certificate, not the research
certificate, so automatic Android App Link verification is expected to fail. If sign-in finishes in
the browser but does not return to the app:

1. Open **Settings → Apps → Google Health → Open by default**.
2. Enable opening supported links in this app.
3. Repeat sign-in.

Android gives an explicit user link preference precedence over failed automatic verification. This
only routes the callback to the app; it cannot bypass a Google/Fitbit server-side certificate or
Play Integrity check.

If Stage A fails, capture one clean reproduction:

```powershell
adb logcat -c
# reproduce exactly one failure
adb logcat -d > gh505-control-failure.txt
```

At that point stop. Gadget is not relevant until the control build passes.

## 5. Build Stage B with the same research certificate

```powershell
py tools\patch_google_health_505.py `
  --apkm $Apkm `
  --gadget "C:\path\to\frida-gadget-17.17.0-android-arm64.so.xz" `
  --build-tools $BuildTools `
  --keystore $ResearchKey `
  --output ".\out-gadget"
```

Stage B performs only these app-content changes:

1. Equal-length binary manifest replacement:
   `FitbitRoot_Application` → `FitbitHook_Application`.
2. Set `android:extractNativeLibs=true`.
3. Clear the original Application class-level `ACC_FINAL` bit and recalculate DEX integrity fields.
4. Add a small wrapper as the next DEX and add Gadget/config to the arm64 split.

It does not decode/rebuild the resource table and does not modify Google Health business methods.

## 6. Dry-run and install Stage B in place

Dry run:

```powershell
py tools\update_research_google_health_505.py `
  --signed-dir ".\out-gadget\signed"
```

Install without removing Stage A data:

```powershell
py tools\update_research_google_health_505.py `
  --signed-dir ".\out-gadget\signed" `
  --install `
  --confirm-control-passed
```

If ADB reports a signing mismatch, stop. The updater deliberately does not uninstall the package.

After the update, repeat the Stage A startup, sign-in-state, pairing-state and two-sync checks before
attaching Frida. This separates a passive Gadget-load problem from the hook script itself.

## 7. Attach to Gadget without Root

Install matching host tools:

```powershell
py -m pip install --upgrade "frida==17.17.0" frida-tools
adb forward tcp:27042 tcp:27042
```

Start Google Health normally, then attach:

```powershell
frida -H 127.0.0.1:27042 Gadget -l frida\gh505_probe.js
```

Frida 17 no longer bundles language bridges into the bare GumJS runtime, but the official Frida
REPL used by the command above bundles the Java bridge and supports plain JavaScript loaded with
`-l`. A fully autonomous Gadget `script` configuration would instead require bundling
`frida-java-bridge` with `frida-compile`.

The Gadget config uses `on_load=resume`, so Google Health is allowed to boot when no Frida client is
attached.

## 8. Capture Google Health's live Air handler

With the Frida console attached:

1. Open the Air haptic-intensity setting in Google Health.
2. Change the setting once and restore it.
3. The console should log a `PUT /settings` or `POST /settings` call and report that a
   `GoldenGateCommandHandler` was captured.
4. Run in the Frida REPL:

```javascript
fbaStatus()
```

Expected fields:

```json
{
  "handlerCaptured": true,
  "handlerReady": true,
  "handlerSource": "POST /settings"
}
```

If no call is observed:

```javascript
fbaFindHandlers()
```

Then repeat the manual setting change. Do not send any write command until one ready handler is
unambiguously captured from an official `/settings` call.

## 9. Read-only same-session test

```javascript
fbaReadHaptics()
```

This explicitly reuses the retained Google Health handler to send:

```text
PUT /settings
payload: 0A0101
```

It subscribes to the returned RxJava object and does not change the haptic setting.

Immediately perform a real manual Air sync. Any error `3000` at this read-only stage falsifies the
same-session assumption and must be recorded before continuing.

## 10. Explicit bounded haptic test

Precondition: set Air haptic intensity to **HIGH** in Google Health and confirm that this official
setting change has completed.

Run once:

```javascript
fbaPreviewHighLowHigh()
```

The script reuses the captured handler and sends:

```text
POST /settings  LOW   payload 0A040A020802
wait 800 ms
POST /settings  HIGH  payload 0A040A020803
```

It never sets haptics to `OFF`.

Immediately afterward:

1. Confirm Air produced the expected bounded preview vibration.
2. Confirm the final setting is still HIGH.
3. Perform a real manual sync.
4. Confirm no error `3000`.
5. Repeat the complete haptic-plus-sync sequence 20 times, recording latency and result.

## P0 exit criteria

The protocol-session ownership blocker is cleared only when one stock, non-root phone demonstrates
all of the following:

- Stage A passes account sign-in, Air pairing/restoration and two real syncs.
- Stage B preserves that app state and background operation.
- The request is sent by Google Health's retained `GoldenGateCommandHandler`.
- No Bridge `BluetoothGatt`, `StackPeer`, Gattlink or DTLS session is created.
- Air produces the requested bounded haptic.
- Google Health completes a real sync immediately afterward.
- Twenty consecutive repetitions produce no error `3000`, stale session or growing delay.
- Haptic intensity remains unchanged after every repetition.

Only after these criteria pass should the notification callback be connected to an automatic,
rate-limited haptic action. The current probe logs notification **package names only** and never
forwards title, body or other notification content.

## Reverting to the official app

Uninstall the research-signed package and reinstall Google Health from Google Play. Because the
certificates differ, the official app cannot replace the research build in place.

## Safety and scope

This is a local interoperability experiment on the operator's own device. It is not suitable for
medical, emergency, authentication or safety-critical notifications. Never publish APKs, account
tokens, device keys, private notifications, personal log files or the research keystore.
