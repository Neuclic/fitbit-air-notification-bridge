# Open problems

[简体中文](OPEN_PROBLEMS.zh-CN.md)

## P0: A custom haptic invalidates Google Health's Fitbit Air session

Status: **research frozen / product blocked**. Reproduced on one Fitbit Air
across Google Health 5.05-5.06.1 and bridge v10018-v10022. There is currently no
accepted no-root follow-up experiment for an independent Bridge session.

### Reproduction evidence

1. Google Health is connected to Air and can sync normally.
2. The bridge joins the existing Android system GATT link by saved address; it
   does not scan first.
3. v10020's first attempt reaches `systemGatt=true`, `localGatt=true`, and
   `source=SYSTEM_IMPORT`, but `Gattlink/DTLS` emits no connection event and
   times out after 18 seconds.
4. After a two-second delay, the second attempt establishes DTLS and executes a
   verified short haptic.
5. The bridge closes its own client. Android still reports the system GATT link
   as connected (`before/after=true/true`).
6. Google Health manual sync then fails with error 3000.
7. Clearing and relaunching only Google Health's Activity task, as tested by
   v10020, does not recover synchronization. A normal user-driven close and
   later reopen of Google Health does recover it on the test phone.

### Interpretation

The evidence rules out slow local GATT initialization as the cause of the first
failure. The first timed-out Gattlink/DTLS attempt appears to invalidate or
displace Google Health's private protocol session; the retry then becomes the
active owner. Closing the bridge client leaves Google Health's background
process holding stale session state, despite the Android physical link remaining
connected. A surviving GATT connection is therefore not proof of protocol
coexistence or successful handoff.

No public Android API lets an ordinary third-party APK reuse Google Health's
`BluetoothGatt`/DTLS session, force its private session to renegotiate, or stop
its background process. The Link Controller disconnect experiment in v10019
also returned locally without producing a real link cycle (`sawDisconnect=false`).

### Google Health 5.06.1 notification-protocol finding

Read-only inspection of the exact 5.06.1 artifact refines the protocol question
without changing this P0 result. Its active tracker-notification path uses Mobile
Data protocol `0x6001` and Switchboard records: create with `POST sb/records`,
then clean up with `POST sb/delete-records`. The legacy
`sendSecureAppNotification` and `sendUnsecuredAppNotification` entry points do
not have a release implementation in this build.

The official test-notification flow selects a device whose local tracker setting
reports `notifications=true`; Switchboard availability is represented separately
by a per-device `SwitchboardSpecification`. Those values are populated from live
device/server state and the APK does not prove what Air receives.

In the v10021 device test, the BOOTSTRAP DTLS session completed but
`GET /.well-known/core` returned `4.04` in 807 ms. The safety gate remained
false and no record was written. A bytecode recheck then confirmed that the
official 5.06.1 sender directly constructs CoAP POSTs for `sb/records` and
`sb/delete-records`; it does not depend on resource discovery. The device result
therefore disproves only the discovery prerequisite, not either known endpoint.

v10022 instead sends one no-payload, no-retry GET directly to each known path.
Only a `2.xx` or exact `4.05 Method Not Allowed` response from both paths unlocks
one manual create attempt. `4.04`, timeout, and all other responses fail closed.
After any create attempt the gate must be run again. The probe still uses a
60-second TTL, deletes after two seconds, never retries creation, does not run
from `NotificationListenerService`, and does not claim to reuse Google Health's
session.

The v10022 read-only device gate passed in 116 ms. Both paths produced accepted
existence evidence, with `GET sb/delete-records -> 4.05`, and the UI enabled the
explicit second step without writing a record. The retained screenshot clips
the create path's exact accepted code.

The one bounded create then returned `POST sb/records -> 4.01 Unauthorized` in
529 ms and produced no haptic. Since creation was not successful, the probe did
not send its delete request. After the bridge disconnected, Google Health manual
sync still returned error 3000.

The 5.06.1 app separately registers `MobileDataTlsKeyResolver`. Its provider
maps `MD-*` identities to primary/secondary AES keys downloaded through Google
Health's authenticated internal device client and stored in its app-private BKS
file. The public Google Health `users.pairedDevices` resource exposes only
`get`/`list` metadata under a read-only settings scope; it has no DTLS-key or
device-command method. Secure session identity is therefore the strongest
explanation for `4.01`, although a firmware feature/entitlement check cannot be
excluded without firmware evidence.

The related GATT characteristic is not a secret-export route. Google Health
reads it as a UTF-8 identity/state, but obtains the secret from its private key
provider before writing key bytes to Air. Supplying a bridge-generated key would
mutate device trust and risk displacing Google Health's key, so it is outside the
accepted boundary.

The independent-session Switchboard path is closed as a product direction: it
neither authorizes notification creation nor preserves Google Health sync. Do
not continue payload retries or attempt to extract/reuse Google Health's private
keys. P0 remains open only for an ordinary-app-callable existing-session route
or an official command API.

### Research freeze decision — 2026-08-25

Within the project's accepted constraints—stock no-root Android, Google Health
remaining usable, no privilege-assisted process control, and no manual ownership
switching—the independent Bridge connection direction has no remaining justified
implementation or device test. v10013-v10022 are retained as protocol and
negative-result evidence; they are not a product-development ladder.

This is a scoped freeze, not a claim that Air notification support is impossible
forever. Resume implementation research only after one of these prerequisites is
established independently:

- an official Google/Fitbit device-command or notification API; or
- an ordinary-app-callable IPC/API that reaches Google Health's already
  authorized Air handler and existing Gattlink/DTLS session.

A proposal to replace Google Health entirely would be a separate companion-app
project with new provisioning, synchronization, recovery, and acceptance work;
it is not a continuation of this Bridge route.

### Product decision

The project will **not** treat either of these as an acceptable solution:

- Shizuku, root, Accessibility automation, or another privilege-assisted force
  stop/relaunch of Google Health.
- A normal product workflow that asks the user to switch manually between a
  Bridge ownership mode and a Google Health synchronization mode.

The notification bridge is therefore blocked for normal daily use until a
non-disruptive protocol interface is found that can trigger a bounded haptic
without taking over or invalidating Google Health's active Air session.

### Acceptable exit criteria

A future approach is acceptable only if one stock, unprivileged APK can prove
all of the following on device:

1. Trigger the requested bounded Air haptic.
2. Leave Google Health running without force-stop, task automation, or manual
   ownership switching.
3. Complete a real Google Health sync afterward without error 3000.
4. Repeat the sequence multiple times without a growing delay or stale session.
