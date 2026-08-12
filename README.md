# Fitbit Air Notification Bridge

[简体中文](README.zh-CN.md)

An experimental, unofficial Android bridge that turns selected phone notifications into short, recognizable haptic patterns on Fitbit Air.

The project is based on Fitbit's Apache-2.0-licensed [Golden Gate](https://github.com/Fitbit/golden-gate) transport stack. It uses BLE, Gattlink, DTLS/CoAP and a locally processed Android `NotificationListenerService`. No cloud relay is required.

> **Alpha software.** The protocol has been validated on one Fitbit Air. It is not an official Fitbit or Google product. Do not use it for medical, emergency, or safety-critical alerts.

## Current capabilities

- Connect to Fitbit Air over Golden Gate/Gattlink.
- Establish the device's BOOTSTRAP DTLS session.
- Read the current haptic intensity through `PUT /settings`.
- Produce a bounded short-vibration group by changing the intensity and restoring it after 800 ms.
- Verify that the final device setting matches the original setting.
- Monitor up to five Android application packages.
- Assign one of five patterns to each application: `single`, `double`, `triple`, `long_gap`, or `urgent`.
- Filter group summaries, ongoing notifications, self-notifications and recent duplicate content.
- Persist the selected Air address and retain a device-verified exclusive-connection alert mode.
- Retain a DNCS negative-control probe that reproduces successful duplicate-service registration without an Air subscription or haptic.
- In v10018, test a scan-free known-address GATT client while Google Health keeps the system BLE link.

Example rules:

```text
com.tencent.mm=single
org.telegram.messenger=double
com.whatsapp=long_gap
```

## Important limitations

- The v10013 notification path and patterns are device-verified, but require the bridge to own the Air connection.
- Device testing disproved v10014 time-sharing: closing the Google Health UI does not stop its companion-device background service, so Air does not advertise and the bridge's bounded scans expire.
- Android exposes no public API for reusing another app's `BluetoothGatt` or DTLS session.
- Device testing disproved DNCS coexistence in v10017: no READ/WRITE/CCCD occurred even across a full Bluetooth restart, and an unsubscribed send completed at Android transport level without an Air haptic.
- The v10018 known-address, dual-GATT-client path is still a device-test probe, not a confirmed coexistence solution.
- Only `arm64-v8a` is built at present.
- A pattern is composed of verified **toggle/restore haptic groups**. It is not arbitrary motor waveform control.
- Fitbit Air cannot be actively owned by this app and Google Health at the same time; the normal scan/connect transport cannot coexist.
- The current saved Bluetooth address may need to be selected again if the device rotates its BLE address.

## Basic use

1. Install the app and grant Nearby devices/Bluetooth access.
2. Leave Google Health running and confirm that it has connected Air.
3. Tap **join existing link and test single pattern**. v10018 does not scan first.
4. Record `systemGatt(before/after)`, the reported failure stage, and whether Air vibrates.
5. Open Google Health after the test and confirm a real Air read/sync; do not rely only on the bridge's Android connection snapshot.
6. The DNCS page remains only as a reproducible negative-control probe.

## Privacy and security

- Notification content is inspected only in memory for duplicate suppression.
- Rules are stored locally with Android `SharedPreferences`.
- Notification bodies, device keys and user data are not sent to a server by this project.
- This repository intentionally contains no Google Health APK, extracted proprietary code, credentials or device keys.

## Build

The Android app is under `platform/android/goldengate`. The inherited Golden Gate build currently uses Gradle 6.9.1, Android Gradle Plugin 4.1.0, JDK 11, Android SDK 30, NDK 23.0.7599858 and CMake 3.10.2.

Build the Golden Gate Android core first, then run:

```bash
cd platform/android/goldengate
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `platform/android/goldengate/app/build/outputs/apk/debug/app-debug.apk`.

See [Golden Gate's Android build documentation](docs/src/platforms/android.md) for native toolchain setup.

## Roadmap

1. Device-test whether v10018 can establish a second GATT client, Gattlink and DTLS while Google Health stays connected, then release only the bridge client.
2. If v10018 fails, mark stock unprivileged APK coexistence as unavailable and reassess system integration or explicit ownership switching.
3. Replace package-name text rules with an installed-app picker for 3–5 rules.
4. Add a visual pattern editor, cooldown controls and a safe preview button.
5. Publish reproducible GitHub Actions builds and signed release APKs.

## License

Apache License 2.0. The original Golden Gate copyright and license notices are retained. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the [upstream README](docs/UPSTREAM_GOLDEN_GATE_README.md).
