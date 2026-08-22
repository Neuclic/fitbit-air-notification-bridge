# Google Health 5.05 non-root in-process research

This directory contains a **non-root research path** for the Fitbit Air notification project.
It does not create a second `BluetoothGatt`, `StackPeer`, Gattlink connection, or DTLS session.
Instead, it installs a re-signed copy of the exact Google Health build already studied and loads
Frida Gadget inside that app's own main process. The probe can then observe and explicitly reuse
the active `GoldenGateCommandHandler` already owned by Google Health.

Target build:

```text
package:     com.fitbit.FitbitMobile
version:     5.05.health-mobile-1104094185-956655267
versionCode: 1104094185
ABI:         arm64-v8a
```

## What this can and cannot establish

The existing independent Bridge has already proved that Air can be controlled through
Gattlink/BOOTSTRAP-DTLS/CoAP, but its second protocol session displaces Google Health and leaves
sync failing with error `3000`. This experiment tests a different proposition:

```text
Google Health's existing BluetoothGatt / Gattlink / DTLS / CoAP session
                              ↓
                  current GoldenGateCommandHandler
                              ↓
                    explicit /settings request
                              ↓
                         Air haptic
                              ↓
                Google Health can still sync normally
```

A positive result removes the protocol-ownership blocker. It does **not** yet produce a normal
end-user APK. The first goal is a clean, reproducible same-session proof.

## Why the APK must be re-signed

A stock, non-root Android device does not allow an external app to inject code into another
non-debuggable app process. Embedding Frida Gadget changes the APK, so the modified package must be
signed with a research certificate. It cannot update over the Google-signed installation and must
replace it.

Consequences:

- Sync all recent Fitbit data before uninstalling the official app.
- Local app state and the existing local pairing may be lost; `allowBackup=false` is set in 5.05.
- Android App Links are certificate-bound, so the OAuth redirect domain may need to be enabled
  manually under **Open by default / Supported links** after installation.
- Google/Fitbit may enforce Play Integrity or a signing-certificate check in a path not visible from
  static inspection. Therefore sign-in, pairing and two real syncs are a mandatory compatibility
  gate before any haptic test.

## Files

```text
tools/patch_google_health_505.py
    Validates the exact APKM, builds a tiny wrapper DEX, patches the binary manifest and original
    Application final flag, embeds Frida Gadget, zipaligns and signs every split APK.

tools/install_google_health_505.py
    Selects arm64, density and language splits for the connected phone. Dry-run by default and
    refuses to uninstall until an explicit data-synced confirmation is supplied.

wrapper-src/com/fitbit/FitbitHook_Application.java
    Research Application wrapper. It loads Gadget only in process `com.fitbit.FitbitMobile`, not
    in `:primes_lifeboat`.

frida/libfba_bridge.config.so
    Gadget listen configuration on localhost:27042 with `on_load=resume`.

frida/gh505_probe.js
    Observes `sendGenericCommand`, captures a live handler, logs notification package names only,
    and exposes explicit read/replay/preview commands. It never opens a BLE connection.
```

No Google Health APK, Frida binary, credentials, tokens, device keys or signing key is committed.

## Prerequisites on Windows

- JDK 11 or newer (`javac` and `keytool` in `PATH` or `JAVA_HOME`).
- Android SDK Build-Tools 36.0.0 or newer (`d8`, `zipalign`, `apksigner`).
- Android Platform-Tools (`adb`).
- Python 3.10 or newer.
- Official Frida Gadget for Android arm64. For Frida 17.17.0 the release asset is normally named:

```text
frida-gadget-17.17.0-android-arm64.so.xz
```

Use the Gadget binary from Frida's official release only.

## 1. Build the re-signed compatibility-gate bundle

From this directory:

```powershell
py tools\patch_google_health_505.py `
  --apkm "C:\path\to\com.fitbit.FitbitMobile_5.05....apkm" `
  --gadget "C:\path\to\frida-gadget-17.17.0-android-arm64.so.xz" `
  --build-tools "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0" `
  --output ".\out-gh505"
```

The script creates a research keystore at `out-gh505/fba-research.jks` unless `--keystore` is
specified. Keep it private and reuse the same key for every later research build.

Expected output:

```text
out-gh505/
  build-info.json
  fba-research.jks
  signed/
    base.apk
    split_config.arm64_v8a.apk
    split_config.zh.apk
    split_config.en.apk
    split_config.<density>.apk
    ...
```

## 2. Inspect the device-specific install set

Connect the phone with USB debugging enabled:

```powershell
py tools\install_google_health_505.py --signed-dir ".\out-gh505\signed"
```

This is a dry run. It reads ABI, density and locale and prints the exact `adb install-multiple`
command without changing the phone.

## 3. Install only after the official app has synced

First confirm in the official Google Health app that the latest Air data has completed a real sync.
Then:

```powershell
py tools\install_google_health_505.py `
  --signed-dir ".\out-gh505\signed" `
  --install `
  --confirm-official-data-synced
```

The signing certificate differs, so the installer removes the existing package before installing
the research build.

## 4. Mandatory compatibility gate

Do not proceed to CoAP replay until all of the following pass:

1. Google Health starts without a crash loop.
2. Browser/account sign-in completes.
3. If the OAuth callback stays in the browser, manually enable the app's supported links for
   `oauth-redirect.googleusercontent.com`, then repeat sign-in.
4. Fitbit Air can be paired or restored to the account.
5. Google Health performs two consecutive real Air syncs without error `3000`.
6. Alarm and haptic-strength pages open and retain a normal setting change.
7. Background reconnection still works after the app is swiped away and reopened.

If this gate fails, capture:

```powershell
adb logcat -c
# reproduce exactly one failure
adb logcat -d > gh505-compatibility-failure.txt
```

Do not add haptic logic until the certificate/integrity/login failure is separated from protocol
behavior.

## 5. Attach to Gadget without root

Install matching host tools:

```powershell
py -m pip install --upgrade "frida==17.17.0" frida-tools
adb forward tcp:27042 tcp:27042
```

Start Google Health normally, then attach:

```powershell
frida -H 127.0.0.1:27042 Gadget -l frida\gh505_probe.js
```

The Gadget config uses `on_load=resume`, so Google Health is allowed to start even when no Frida
client is attached.

## 6. Capture Google Health's live Air handler

With the Frida console attached:

1. In Google Health, open the Air haptic-intensity setting.
2. Change the setting once and restore it.
3. The console should log a `PUT /settings` or `POST /settings` call and report that a
   `GoldenGateCommandHandler` was captured.
4. In the Frida REPL, run:

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

If no call is observed, run:

```javascript
fbaFindHandlers()
```

and repeat the manual setting change.

## 7. Read-only same-session test

```javascript
fbaReadHaptics()
```

This sends the already-observed request:

```text
PUT /settings
payload: 0A0101
```

It uses the retained Google Health handler and explicitly subscribes to the returned RxJava object.
It does not change the haptic setting.

After this call, perform a real manual Air sync. Any error `3000` at this stage is a failure of the
same-session assumption and should be recorded before continuing.

## 8. Explicit bounded haptic test

Precondition: set Air haptic intensity to **HIGH** in Google Health and confirm the setting has
finished syncing.

Then run once:

```javascript
fbaPreviewHighLowHigh()
```

The script explicitly reuses the captured handler to send:

```text
POST /settings  LOW   payload 0A040A020802
wait 800 ms
POST /settings  HIGH  payload 0A040A020803
```

This is the same bounded preview primitive already verified by the independent Bridge, but now sent
inside Google Health's current session. It never sets haptics to `OFF`.

Immediately afterward:

1. Confirm the final setting is still HIGH.
2. Perform a real manual sync.
3. Confirm no error `3000`.
4. Repeat the sequence 20 times, recording haptic latency and sync result.

## P0 exit criteria

The protocol-ownership blocker is cleared only when one unrooted phone demonstrates all of these:

- The request is sent by Google Health's retained `GoldenGateCommandHandler`.
- No Bridge `BluetoothGatt`, `StackPeer`, Gattlink or DTLS session is created.
- Air produces the requested bounded haptic.
- Google Health completes a real sync immediately afterward.
- Twenty consecutive repetitions produce no error `3000`, stale session or growing delay.
- Haptic intensity is verified unchanged after every repetition.

Only after these criteria pass should the notification callback be connected to an automatic,
rate-limited haptic action. `gh505_probe.js` currently logs notification **package names only** and
never forwards notification content.

## Reverting to the official app

Uninstall the research-signed package and reinstall Google Health from Google Play. Because signing
certificates differ, in-place replacement is not possible.

## Safety and scope

This is a local interoperability experiment on the operator's own device. It is not suitable for
medical, emergency, authentication or safety-critical notifications. Never publish APKs, account
tokens, device keys, private notifications, log files containing personal data, or the research
keystore.
