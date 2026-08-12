# Open problems

[简体中文](OPEN_PROBLEMS.zh-CN.md)

## P0: A custom haptic invalidates Google Health's Fitbit Air session

Status: **open protocol blocker**. Reproduced on one Fitbit Air with Google
Health 5.05 and bridge v10018-v10020.

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
