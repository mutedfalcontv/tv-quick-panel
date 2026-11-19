# Bug: the gear button stops working after the TV has been in standby

**Status:** fixed (2026-11-19) - the bridge now verifies its event stream and
self-heals within seconds of waking.
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Area:** `GearBridgeService` / adbd shell stream
**Related:** `docs/analysis/adb-gear-bridge.md`

## Symptom

After the TV has been in standby and comes back, pressing the gear button does
nothing. The panel only comes back after the app is killed and reopened - which
pops the *"Allow USB debugging?"* prompt once more - and then it works again.

## Root cause (measured on the device, 2026-11-19)

A standby kills the `cat /dev/input/event4` shell child on the adbd side, and
this firmware's adbd never reports the death: no `CLSE`, no EOF, the TCP
connection to `127.0.0.1:5555` just stays ESTABLISHED and silent. The bridge
believed it was connected:

* the `gear-bridge` worker blocked in `Stream.read` forever (the stream is never
  marked closed),
* the `adb-reader` blocked in `readFully` (SO_TIMEOUT is 0 after the handshake),
* `ps` showed no `cat` process while `netstat` still showed the connection,
* and logcat had nothing - a gear press never even reaches the app, because
  nothing is reading the node. (`SystemUI` drops the key itself: it logs
  `Not launching notification handler activity: Could not resolve activityInfo`
  for non-system apps, so the adbd stream really is the only path.)

Killing the app forced a fresh transport; this firmware wipes its runtime adb
grants when adbd is restarted by a standby, so the prompt reappeared, and after
accepting it the freshly opened `cat` worked.

The whole state was reproduced live: after one standby/wake cycle the
connection port stayed identical, no `cat` existed, the RC600 node was present,
and the app logged nothing.

## The fix

Three changes in `GearBridgeService`:

1. **Liveness watchdog** (`gear-watchdog` thread). When the event stream has
   delivered no byte for 60 s - or the screen just turned back on - a side
   stream on the same transport runs
   `for p in $(pgrep -x cat); do tr '\0' ' ' < /proc/$p/cmdline; done | grep -F 'cat <node>'`.
   The probe runs as the shell user, the same uid as adbd's children, so their
   cmdline is readable. If the `cat` is gone (or the probe cannot spawn, which
   is what a wedged transport does), the connection is closed and the worker's
   read fails into the normal reconnect path.
2. **Screen-on receiver.** `ACTION_SCREEN_ON` triggers a verification
   immediately: standby is exactly when the shell dies, so the check runs ~1 s
   after wake instead of waiting out the silence window.
3. **The absent-node wait happens on the same transport.** While the RC600 node
   is not there yet (the BLE remote only exposes it after it wakes), the loop
   used to reconnect every 2 s - and every new transport makes this firmware
   raise a fresh authorization prompt. It now re-probes on the authorized
   connection and only reconnects when the transport itself fails.
   `findGearDevice` propagates transport errors instead of swallowing them, so
   a dead connection cannot spin the wait loop.

## What the recovery looks like now

```
21:50:45.825 screen on - verifying the event stream          (+0.8 s after wake)
21:50:51.785 event stream liveness check failed: no OKAY ...  (wedged transport)
21:50:51.785 event stream died silently; forcing a reconnect
21:50:51.845 connecting to adb
21:51:07.735 adb connected, looking for the RC600 node        (after the prompt)
21:51:08.348 RC600 input node not present yet; waiting on this transport
```

After the prompt is accepted the bridge waits for the remote's node on the same
authorized transport; the first remote press wakes the BLE link, the node
appears, `cat` opens within ~2 s, and every press after that toggles the panel.

## What still cannot be fixed from the app

The *"Allow USB debugging?"* prompt after a deep standby comes from adbd: the
firmware wipes its runtime grants when it restarts adbd, and newly accepted
keys are not reliably persisted (see `docs/analysis/adb-gear-bridge.md`). Any
new transport therefore has to be approved once. The difference is that the app
now gets to that prompt by itself seconds after wake - the kill/reopen ritual
is gone.

## Why other ADB apps never ask (found 2026-11-19)

`com.cybercat.adbappcontrol.tv` (ADB AppControl) reconnects without any prompt
because its key line lives in the permanent, root-owned
`/data/misc/adb/adb_keys`: its transports authorize in ~1 ms straight from the
file load, no framework involved. Our key was not reliably in that file - adbd
even logged a corrupt `Invalid base64 key` line flapping in it, and the
firmware's always-allow *store* had our key marked `alwaysAllow=true` while its
auto-approve path still prompted.

The durable fix is the dialog's checkbox, which the first UI dump missed:
**tick "Always allow from this computer" (`android:id/alwaysUse`) before
tapping Allow.** Verified live: an always-allow acceptance at 22:04:29 was
followed 15 s later by a brand-new transport authorizing silently from
`adb_keys` - no prompt, no framework round trip.

## The boot-time race and the prompt queue (found 2026-11-19, later)

Pulling the TV's adbd binary showed `Invalid base64 key` lives next to
`Failed to encode RSA public key` - it is logged while parsing key material
during an auth round, and the framework rewrites `adb_keys` right after every
authorization and every disconnect. Our reconnects always land inside that
rewrite window (boot, app install, watchdog teardown), read a half-written
file, and pop the prompt; the PC's rare reconnects land outside it and sail
through. adbd also only ever shows **one** pending prompt: every failed
connect queues another one behind it, so after a reboot several stale dialogs
have to be accepted before the live transport's turn arrives.

Two more mitigations, on top of the transport reuse:

1. **The first connect after boot waits 15 s** (`EXTRA_FROM_BOOT` from the boot
   receiver) for the framework's boot-time key-file rewrite to finish.
2. **Auth failures retry fast** (3 s, 6 s) before the old 60 s+ ladder - a
   raced rewrite settles in milliseconds, and adbd skips duplicate prompts
   while one is pending, so fast retries do not flood the screen.

Combined with the transport-reuse fix above, new transports are now rare:
standby reuses the authorized connection, and only a reboot, an app reinstall
or a wedged transport needs one prompt - which, ticked with always-allow, does
not come back immediately afterwards.

## The real root cause: Android's 7-day ADB authorization timeout (found 2026-11-19)

AOSP's `AdbDebuggingManager` (frameworks/base, services/core/java/com/android/
server/adb/) tracks every "always allow" grant with a `lastConnectionTime` and
an `authWindow` from `Settings.Global.ADB_ALLOWED_CONNECTION_TIME`
(default 7 days). A daily job (`filterOutOldKeys`) deletes grants older than
that window **and rewrites `adb_keys` without them**; the same prune runs when
adb starts at boot. That is the boot-time drop: the key had gone unused for
more than 7 days, so the boot prune deleted it before our first connect. The
rewrite is also what produces the empty `Invalid base64 key` line - a load
landing mid-rewrite (or a re-serialized empty entry) reads garbage.

The framework itself exposes the escape hatch in `isKeyAuthorized`:

```java
// if the allowed connection time is 0 then revert to the previous behavior of
// always allowing previously granted adb grants.
return allowedConnectionTime == 0 || (now < lastConnectionTime + allowedConnectionTime);
```

**Fix applied on the TV (2026-11-19):**

```sh
adb shell settings put global adb_allowed_connection_time 0
```

With 0, `isKeyAuthorized` approves any previously-granted key without looking
at the clock (the framework path no longer depends on the flappy file), the
prune job becomes a no-op (no more rewrites, no more boot-time drops), and the
"Disable adb authorization timeout" developer option on phones writes exactly
this 0. This TV's trimmed developer options lack the toggle, but the framework
still honors the setting.

With the timeout disabled plus the transport-reuse and boot-delay changes
above, a granted key should survive reboots indefinitely. If a prompt ever
appears (firmware rewrite quirks), tick "Always allow from this computer" and
Allow once - the grant then can no longer expire.

## Verification

```sh
adb logcat -s TvGearBridge
# standby, wake, and expect: screen on - verifying the event stream /
#   event stream died silently; forcing a reconnect / adb connected
# press the gear: gear pressed

adb shell ps -A | grep ' cat'      # exactly one cat reading the node
adb shell netstat -tn | grep 5555  # one loopback connection, ESTABLISHED
```
