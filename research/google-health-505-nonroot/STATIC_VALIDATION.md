# Static validation: exact Google Health 5.05 APKM

This record covers only deterministic, offline validation against the exact APKM supplied for the
research. It does not claim that the re-signed application has passed sign-in, pairing, background
operation or real Fitbit Air synchronization on a physical phone.

## Input

```text
package:     com.fitbit.FitbitMobile
version:     5.05.health-mobile-1104094185-956655267
versionCode: 1104094185
APKM size:   46,352,591 bytes
```

SHA-256:

```text
APKM
4704ebb64a680905f8a2b826ae936b6d23f11c94b5959cf3cbfe0bd0b19ec784

base.apk
9046ed5d32ac2edfd4a3c436d6978d9d32132209508f0bca68286ae6c167f63d

split_config.arm64_v8a.apk
b2e77baa0b637f9f5d40dcc2dc3521a8006c0e134bcc449919c5f17d7a0c946c
```

## Binary manifest patch

The original application class is:

```text
com.fitbit.FitbitRoot_Application
```

The research wrapper is deliberately the same UTF-16 string length:

```text
com.fitbit.FitbitHook_Application
```

Results:

```text
AndroidManifest.xml size before: 158,696 bytes
AndroidManifest.xml size after:  158,696 bytes
bytes changed:                  6
```

SHA-256:

```text
before
c2ca96be8c852c6620ba0265798906d2af1c6dc4b7bfcbbda3d874f454e881ca

after
463b0af849381d307a13151aadd313af31124cc64130f31db7cc483bf231a3be
```

The patched binary XML decodes to:

```text
android:name="com.fitbit.FitbitHook_Application"
android:extractNativeLibs="true"
```

No resource-table rebuild is required.

## Original Application DEX patch

The exact class definition is:

```text
Lcom/fitbit/FitbitRoot_Application;
access flags: public final (0x0011)
superclass:   Lnox;
```

The class has a public constructor and a final `onCreate`. The wrapper invokes the original
constructor and does not override `onCreate`; only the class-level `ACC_FINAL` bit is cleared.

Results:

```text
classes.dex size before: 7,667,280 bytes
classes.dex size after:  7,667,280 bytes
bytes changed:           23
```

The 23 changed bytes comprise the access-flag bit and the DEX SHA-1/checksum fields that must change
when any DEX byte changes.

SHA-256:

```text
before
308c38616646a80c284ee442222e79b94d543f05471006ea300d79a2dede1420

after
c3d1da54fb1154ac540da93897d5d9c58266d344c44c96e5987fe4b655e3a2cc
```

Recomputed integrity fields:

```text
Adler-32: 0x4AB43362 — verified
SHA-1:    verified against bytes 32..EOF
```

## Wrapper and Gadget loading model

The wrapper is compiled into a new final DEX entry rather than merging with or reprocessing the
existing Google Health DEX files. On the exact APK, the next entry is expected to be
`classes9.dex`.

The wrapper loads:

```text
lib/arm64-v8a/libfba_bridge.so
```

only when:

```text
Application.getProcessName() == "com.fitbit.FitbitMobile"
```

It does not load Gadget in `:primes_lifeboat`, preventing two app processes from competing for the
same localhost listener port.

## What has not yet been validated

The following require the user's physical, non-root phone and cannot be established by static APK
analysis:

- Whether a research-signed Google Health 5.05 completes Google/Fitbit account sign-in.
- Whether Android App Links for the OAuth redirect need a manual user override.
- Whether Google or Fitbit enforces Play Integrity or the original signing certificate on this
  application path.
- Whether Fitbit Air can be paired/restored under the research signature.
- Whether two real syncs complete without error `3000` before any injected CoAP request.
- Whether the retained `GoldenGateCommandHandler` can execute the same-session `/settings` preview
  and preserve the official protocol session afterward.

Static inspection found bundled Play Integrity/Google Play support code, but that alone does not
prove that core Google Health sign-in, pairing or Air synchronization rejects a re-signed package.
The compatibility gate in `README.md` is therefore mandatory and precedes all haptic testing.
