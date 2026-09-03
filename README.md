# Fitbit Air Notification Bridge

[简体中文](README.zh-CN.md)

An experimental, unofficial Android bridge that turns selected phone notifications into short, recognizable haptic patterns on Fitbit Air.

The project is based on Fitbit's Apache-2.0-licensed [Golden Gate](https://github.com/Fitbit/golden-gate) transport stack. It uses BLE, Gattlink, DTLS/CoAP and a locally processed Android `NotificationListenerService`. No cloud relay is required.

> **Alpha software.** The protocol has been validated on one Fitbit Air. It is not an official Fitbit or Google product. Do not use it for medical, emergency, or safety-critical alerts.

> **Research status — independent Bridge session frozen on 2026-08-25.** Under
> the accepted stock/no-root product boundary, an independent Bridge session
> cannot both send an authorized Air notification and preserve Google Health
> synchronization. The next research experiment is the explicitly non-product
> same-process path in [`research/google-health-506-same-session/`](research/google-health-506-same-session/README.md).

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
- Use a scan-free known-address GATT client while Google Health keeps the system BLE link.
- In v10020, retain the exact stage/error of every connection attempt and offer a user-tapped Google Health task restart after the haptic.
- In the v10022 research build, expose a foreground-only Switchboard probe only after direct no-payload GETs provide fail-closed evidence for both known create and cleanup endpoints.

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
- v10018 confirmed that a second GATT client can join and execute a haptic, but it is not transparent coexistence.
- v10018 executed the haptic, but releasing only the bridge client left Google Health sync failing with error 3000; transparent dual-DTLS coexistence is therefore rejected.
- v10019's Link Controller request returned locally, but device testing observed `systemGatt=true/true` and `sawDisconnect=false`; Google Health still could not sync until its UI task was closed and reopened. Automatic handoff from a stock unprivileged APK is therefore rejected.
- v10020's Activity-task relaunch was also device-tested and Google Health still returned error 3000. The stale session therefore survives a UI task restart and belongs to longer-lived background process state.
- The first v10020 attempt timed out specifically at `Gattlink/DTLS` while both system and local GATT were connected; the second attempt succeeded. This is consistent with protocol-session takeover, not a local Bluetooth initialization delay.
- Static analysis of Google Health 5.06.1 identifies its active notification transport as Mobile Data/Switchboard (`POST sb/records`, with `POST sb/delete-records` cleanup), while its older `sendSecureAppNotification` and `sendUnsecuredAppNotification` methods have no release implementation. The APK alone does not establish whether Air's live device model or firmware exposes that transport.
- v10021 device testing reached the BOOTSTRAP DTLS session, then received `4.04` from `GET /.well-known/core`; its safety gate remained false and no record was written. This disproves only the directory-discovery prerequisite, not the two known endpoints: 5.06.1 directly calls them without discovery.
- v10022 proved that both Switchboard paths exist, but its one bounded `POST sb/records` returned `4.01 Unauthorized` under `PSK=BOOTSTRAP`, produced no haptic, and Google Health still reported error 3000 after disconnect. The create step failed, so no delete request ran.
- Google Health 5.06.1 registers a Mobile Data DTLS resolver backed by primary/secondary AES keys downloaded through its authenticated internal device client and stored in its app-private keystore. The public Google Health paired-device resource exposes only `get`/`list` metadata, not command sessions or DTLS keys. The independent bridge path therefore has no accepted no-root next write experiment. A 2026-09-02 follow-up found no 5.06.1 exported IPC and no public command API that could enter Google Health's live Air session.
- Privilege-assisted force-stop/relaunch (Shizuku/root/Accessibility) and manual Bridge/Health ownership switching are explicitly outside the accepted product design.
- Only `arm64-v8a` is built at present.
- A pattern is composed of verified **toggle/restore haptic groups**. It is not arbitrary motor waveform control.
- Fitbit Air cannot be actively owned by this app and Google Health at the same time; the normal scan/connect transport cannot coexist.
- The current saved Bluetooth address may need to be selected again if the device rotates its BLE address.

## Basic use

1. Install the app and grant Nearby devices/Bluetooth access.
2. Leave Google Health running and confirm that it has connected Air.
3. Use v10020-v10022 only as historical diagnostic builds. The v10022 Switchboard experiment is complete; do not repeat its second step. Its sole create attempt returned `4.01` and did not vibrate.
4. Do not enable notification rules for normal daily use: establishing the bridge's competing DTLS session alone can leave Google Health sync failing with error 3000.
5. The DNCS page remains only as a reproducible negative-control probe.

The full blocker, evidence, rejected workarounds, and acceptance criteria are recorded in [Open problems](docs/OPEN_PROBLEMS.md).

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

1. Find a stock, unprivileged protocol path that can trigger a haptic without taking over or invalidating Google Health's active session.
2. Do not pursue Shizuku/root/Accessibility force-stop automation or manual ownership switching as product solutions.
3. Replace package-name text rules with an installed-app picker for 3–5 rules.
4. Add a visual pattern editor, cooldown controls and a safe preview button.
5. Publish reproducible GitHub Actions builds and signed release APKs.

## License

Apache License 2.0. The original Golden Gate copyright and license notices are retained. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the [upstream README](docs/UPSTREAM_GOLDEN_GATE_README.md).
