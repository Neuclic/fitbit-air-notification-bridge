# On-demand connection and Google Health coexistence

## Observed failure

The original one-shot path required a fresh scan result before opening the saved
Air. In a failed run, Android already reported a bridge-owned GATT client
connection, but the scan still expired. Since no `StackPeer` had been created
yet, closing a peer could not release that connection. A scan timeout alone does
not establish that Google Health owns the device.

Closing a `StackPeer` also does not shut down the process-wide BitGatt server.
An idle bridge can therefore retain GATT registrations after completing a
notification.

## Change

- Resolve the saved Bluetooth address directly. Reuse a matching BitGatt
  connection or register the remote device before creating the stack. Initial
  device selection still uses discovery; address rotation is not solved.
- Run the one-shot service in `:air_haptics`. When the service is destroyed,
  close its peer and schedule BitGatt shutdown after a short settling interval.
  Cancel the pending shutdown when a new worker starts; initialize again after
  a completed shutdown.
- Stop pending GATT setup and unregister the initializer's Bluetooth callback
  before shutting down the client's and server's registrations.
- Pass the selected address in the service intent. Return status through an
  explicit, non-exported receiver in the UI process. Android SharedPreferences
  caches are not used as cross-process communication.

The separate process scopes shutdown to notification work. The legacy scan and
diagnostic activities have their own runtime in the UI process; this change
does not implement their idle lifecycle. Testing normal coexistence should be
done after leaving those activities and restarting the app if they retained
connections.

## Device observations

Collected on one vivo V2408A running Android 16 with one Fitbit Air. Google Health
was enabled during these tests. These observations come from development builds
used to investigate the fix, not from a retest of the exact PR artifact:

| Experiment | Observation |
| --- | --- |
| Direct connection with Google Health running | First successful haptic took about 29 s including a retry; the wearer confirmed feeling it. |
| Three subsequent manual attempts, intermediate build | Successful completion in 7.61, 7.14 and 7.30 s. |
| Idle shutdown build, synthetic notifications with the phone locked | `single`, `double`, `triple` completed in 27.81, 10.49 and 12.48 s; original LOW intensity was restored in each case. |
| Bluetooth state after those synthetic tasks | Bridge client/server registrations disappeared approximately 1.0–1.1 s after success. |
| Official app opened after bridge work | The official UI subsequently reported a fresh sync time without tapping its sync button. This establishes a foreground sync observation, not unattended background sync. |

Synthetic notifications exercise the listener/worker path but do not verify
WeChat, Lark or incoming-call delivery. Device success responses also do not
substitute for wearer confirmation of each physical vibration.

The development build additionally attempted the link-controller `DISCONNECT`
command. All three attempts in the locked synthetic run were unavailable or
timed out. This PR omits that unverified command. The observations do not prove
that any one change alone caused the successful official sync.

## Exact-artifact follow-up (2026-10-21)

Installed commit `45505f181d11696a60f84de0970b54f6d411ab21` and verified the
installed APK SHA-256 against the locally built artifact:
`f261ce21f59cfaf50e2ddba59298a1ce83bf3054200b4bd6a573f6bc711c52db`.
The app build and all eight app tests passed again.

A temporary `com.android.shell=single` rule let local notifications exercise the
unmodified notification listener. No diagnostic receiver or alternate worker
was added. An initial attempt after force-stopping the app for test setup did
not reach a bound listener; toggling the existing notification-listener grant
restored the binding. This setup attempt is excluded from the timings below.

| Exact-artifact test | Completion | No bridge GATT registrations |
| --- | ---: | ---: |
| Notification with Google Health in the foreground; one connection retry | 24.74 s | 26.12 s |
| Notification with the phone locked | 7.16 s | 8.28 s |
| Separate locked-phone notification, wearer confirmed vibration | 7.37 s | 8.52 s |

All three tasks reported the original LOW setting restored. Times are sampled
from notification posting, not the instant the motor first moved. The second
and third tasks also exercise runtime reinitialization after idle shutdown.

There is still a protocol-coexistence concern: the official app began logging
`Received previously received PSN ... != Expected ...` seconds after the first
bridge connection. These warnings continued after the bridge's registrations
were gone. Local GATT cleanup therefore must not be equated with a healthy
official Gattlink session. The exact-artifact haptic result is positive, but
background automatic-sync coexistence has not yet passed validation.

During a subsequent ten-minute observation, Google Health remained in the
background. Three real WeChat notification tasks also completed successfully
and restored LOW; after the queue drained, bridge registrations disappeared
again. These real events add software-path evidence, but were not individually
confirmed by the wearer. Lark and calls were not validated.

The official transport warning count reached 191 by the end-of-window check,
with repeated packet 8 while expecting packet 14. Reopening Google Health
showed "syncing". About one minute later its client disconnected and
reconnected, and the sequence warnings stopped. This recovery followed opening
the official UI, so it is not evidence of unattended background sync. The
screen then locked before a final sync timestamp could be verified. After
unlocking and opening Google Health again, the UI showed a sync time of
"just now", confirming completed foreground recovery. This does not establish
when a background sync completed. The original notification rules were
restored after testing.

Keep this change as a draft until a completed background sync can be shown
after notification delivery. A ten-minute window without a confirmed sync
does not establish that automatic sync can never recover, but the persistent
protocol warnings are a reason not to claim that resource teardown alone
solves coexistence.

## Remaining validation

- Repeat on other phones and firmware versions.
- Check actual configured-app notifications and incoming calls with the phone
  locked. Ongoing call notification filtering is a separate issue.
- Confirm unattended automatic sync over an extended period, including while
  notifications arrive during an official sync.
- Exercise Bluetooth toggling, out-of-range recovery, process death, queued
  notifications and BLE address changes; measure idle power use.

No notification contents, Bluetooth addresses, health records, account details,
or proprietary application files are included in these notes.

## Local checks

With the native Golden Gate core built and the repository's JDK 11 / Android
toolchain available:

```sh
cd platform/android/goldengate
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK built and all eight app tests passed. The three new
`GlobalBluetoothGattLifecycleTest` cases cover callback removal, cancellation of
pending service registration, and registration after restart.

The normal connection-manager test task currently fails during compilation in
unchanged `CoapNodeProviderTest`, `StackPeerBuilderTest`, and `StackPeerTest`.
`--tests` filters test execution, not compilation. The three new cases passed
when compiled independently using this temporary Gradle init script (no source
files or repository build configuration were changed to bypass the old tests):

```groovy
// /tmp/air-focused-tests.gradle
gradle.projectsEvaluated {
    def manager = gradle.rootProject.project(':GoldenGateConnectionManager')
    manager.tasks.named('compileDebugUnitTestKotlin').configure {
        setSource(manager.fileTree('src/test/java') {
            include '**/GlobalBluetoothGattLifecycleTest.kt'
        })
    }
}
```

```sh
./gradlew -I /tmp/air-focused-tests.gradle \
  :GoldenGateConnectionManager:testDebugUnitTest \
  --tests com.fitbit.goldengate.bt.GlobalBluetoothGattLifecycleTest
```

This is a focused regression result, not a passing result for the full
connection-manager suite.
