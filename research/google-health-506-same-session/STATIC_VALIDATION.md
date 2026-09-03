# Static validation: exact Google Health 5.06.1 APKM

This record covers only deterministic, offline validation against the exact APKM
supplied for the research. It does not claim that the re-signed application has
passed sign-in, pairing, background operation or real Fitbit Air synchronization
on a physical phone.

## Input

```text
package:     com.fitbit.FitbitMobile
version:     5.06.1.health-mobile-1104101009-966217170
versionCode: 1104101009
APKM size:   43,524,064 bytes
```

SHA-256:

```text
APKM
7d7376605ec1221a99b12b46f527b63dd2ca41c7f4cab4da63576fe0981e0b00

base.apk
708d9586413e262a668a9ef69b522fd7495202b99233bf8fe4164deb953e919d

split_config.arm64_v8a.apk
8b8053a3f463a5a9f94f3a4baed4090c71cfba2b95b63f3718b40d28c91eac2d
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
AndroidManifest.xml size before: 159,612 bytes
AndroidManifest.xml size after:  159,612 bytes
bytes changed:                   6
```

SHA-256:

```text
before
a4e052907f06f3a606c7579fe53b5f1db85dc096ca7130db8dcfb1f90d77f39d

after
d1a9039655885403456ae281a4821394f54975b1ff958bbc82c609dfb69be45b
```

The patched binary XML decodes to:

```text
android:name="com.fitbit.FitbitHook_Application"
android:extractNativeLibs="true"
```

The original `extractNativeLibs` value on this build is false. No resource-table
rebuild is required.

## Original Application DEX patch

The exact class definition is:

```text
Lcom/fitbit/FitbitRoot_Application;
access flags: public final (0x0011)
superclass:   Lnpf;
```

The wrapper invokes the original constructor and does not override `onCreate`;
only the class-level `ACC_FINAL` bit is cleared.

Results:

```text
classes.dex size before: 7,663,928 bytes
classes.dex size after:  7,663,928 bytes
bytes changed:           24
```

The changed bytes comprise the access-flag bit and the DEX SHA-1/checksum fields
that must change when any DEX byte changes.

SHA-256:

```text
before
0fbf64270295a60ecc1df39f0dc2df99da8979bb88083d1e13c16faf505723ae

after
4d9ea43f5219f733cbeffc62d4f74b5d23e91de5657bae154099298df605c4a0
```

Recomputed integrity fields:

```text
Adler-32: 0x79108be8 — verified
SHA-1:    verified against bytes 32..EOF
```

## Wrapper and Gadget loading model

The wrapper is compiled into a new final DEX entry rather than merging with or
reprocessing the existing Google Health DEX files. On this APK the next entry is
`classes9.dex`.

The wrapper loads:

```text
lib/arm64-v8a/libfba_bridge.so
```

only in process `com.fitbit.FitbitMobile`.

## Same-session command surface observed in 5.06.1

`classes3.dex` contains these in-process types. They are not exported IPC:

```text
GoldenGateCommandHandler.sendGenericCommand(String, byte[], byte)
GoldenGateCommandHandler.writeMobileData(HashMap, int, Context, ...)
NotificationSenderCommandHandler.send(HashMap)
CoapNotificationRecordCreator.create(byte[])
CoapNotificationRecordRemover.remove(DeleteRecordsCommand)
```

`sendGenericCommand` is the same three-argument overload used by the 5.05 probe.
The 5.06.1 probe therefore reuses that live handler instead of opening a second
BOOTSTRAP DTLS session.
