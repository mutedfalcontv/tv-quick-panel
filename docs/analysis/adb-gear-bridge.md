# Analysis: the gear button without an accessibility key filter

**Status:** retired (2026-10-31). `GearBridgeService` is kept in the source tree
as the documented alternative but is not declared in the manifest.
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Related:** `docs/bugs/volume-long-press.md`

## Why it is retired

The bridge worked on a live connection, but its ADB key was never persisted:
adbd logs `Invalid base64 key` while loading `/data/misc/adb/adb_keys`, so the
key is not stored and *"Allow USB debugging?"* comes back after every reconnect
(force-stop, reboot, backoff). The TV is used entirely from the remote, so a
dialog that needs a manual tap cannot be part of normal operation.

The shipped solution is the other direction: the accessibility key filter is kept
but scoped to the launcher and the panel, so volume keeps its native ramp
everywhere else. See the fix section of `docs/bugs/volume-long-press.md`. The
rest of this document is kept as the analysis of the `getevent` route.

## The constraint

The gear button reports **kernel scancode 501** (`0x01f5`, `KEY_BRL_DOT5`) on
`/dev/input/event4` (`RC600`). No keylayout entry maps it, so the input stack
turns it into `KEYCODE_MENU` with the scancode preserved. An app cannot read
`/dev/input/*` directly (needs group `input`), and the key is not delivered to
any app window.

The only two options are:

1. **Accessibility key filter** (`FLAG_REQUEST_FILTER_KEY_EVENTS`) - what
   tvQuickActions and every other remapper does.
2. **`getevent` as the shell user**, driven from the app's own ADB connection to
   the device's adbd.

## Why option 1 is not acceptable on this TV

Measured on the device (`dumpsys input | grep InputFilter`):

| accessibility services enabled | `InputFilterEnabled` |
| --- | --- |
| none | **false** |
| Projectivy only | **true** |
| tv-quick-panel only | **true** |

With the filter on, every key is routed through `KeyboardInterceptor` in
system_server instead of straight to the focused window. For `VOLUME_UP` /
`VOLUME_DOWN` the interceptor calls
`WindowManagerPolicy.interceptKeyBeforeDispatching(null, event, policyFlags)`
first; on this TV the policy consumes them (TV audio routing) and the event is
swallowed *before* `InputDispatcher` creates a `KeyEntry`. Repeats are generated
per `KeyEntry`, and `InputDispatcher::notifyKey` hardcodes `repeatCount = 0`, so
a held volume key never ramps: one step per press.

That also explains an observation that looked contradictory: injected
`input keyevent 24` produces no `onKeyEvent` callback in our accessibility
service. The service never sees volume keys at all on this device - the policy
eats them inside the interceptor. The `AudioManager` ramp in `KeyService` is
therefore inert here (it is still useful on devices without TV routing).

Projectivy's accessibility service requests the same flag, so the bug survives
removing our service. To get volume long-press back, **no** accessibility
service may request key filtering.

## What tvQuickActions does

See `docs/analysis/tvquickactions-key-interception.md`. In short:

* its accessibility path has the same volume bug (documented in its own strings:
  *"the accessibility service will be disabled for 10 seconds so you can use the
  volume buttons as usual"*),
* it ships an **ADB mode** (`dev.vodik7.tvquickactions.services.AdbLibService`,
  a foreground service that connects to `127.0.0.1:5555`) used for app buttons
  (YouTube / Prime / Netflix / Disney+) that never reach the accessibility path,
* in that mode it runs `getevent`, parses the raw `0001 <code> <value>` lines and
  re-injects keys with `input keyevent`.

So "ADB remap" looked like the escape hatch this device needed. It was built and
worked while connected, then retired for the key-persistence reason above.

## Implementation (retired)

`com.ajink.tvquick.GearBridgeService` (foreground service, `specialUse`) plus
`com.ajink.tvquick.adb.AdbConnection` and `com.ajink.tvquick.adb.AdbKey`.

### Protocol

Minimal ADB *host* client, 24-byte little-endian packet headers
(`command, arg0, arg1, length, checksum, magic`):

| step | packet | payload |
| --- | --- | --- |
| 1 | `CNXN` | `host::\0` |
| 2 | adbd -> `AUTH(TOKEN)` | 20-byte token |
| 3 | `AUTH(SIGNATURE)` | `RSA_sign(NID_sha1, token)` = `Signature("SHA1withRSA")` |
| 4 | adbd -> `AUTH(TOKEN)` (unknown key) | |
| 5 | `AUTH(RSAPUBLICKEY)` | ADB public key, `" name\0"` |
| 6 | adbd -> `CNXN` | authorized (prompt accepted) |

Two details cost real debugging time and are worth recording:

* **The public key packet is mandatory.** Without it adbd has no key to display,
  cannot raise the authorization dialog, and simply sends another `AUTH(TOKEN)`.
  The device log only shows `Calling send_auth_request...` twice.
* **The key encoding must be exactly 524 bytes**: `uint32 words` (64),
  `uint32 n0inv`, `uint32 n[64]`, `uint32 rr[64]`, `uint32 exponent` (65537), all
  little-endian words, then base64 + `" name"`. Anything else and adbd logs
  `Invalid base64 key <...>` while loading `adb_keys`, the key is never
  persisted, and the "Allow USB debugging?" dialog returns after every
  reconnect.

### Streams

`OPEN` with `shell:getevent\0` starts `getevent` as the shell user. Output
arrives as `WRTE` packets; each one is acked with `OKAY` for flow control.
Lines have the form:

```
/dev/input/event4: 0001 01f5 00000001
```

`0001` = `EV_KEY`, `01f5` = 501, value `1` = down. The bridge ignores every
other code, so volume, D-pad and media keys never touch this path.

### Opening the panel

A foreground service may not start activities from the background on Android
10+, so the bridge runs
`am start -a com.ajink.tvquick.action.TOGGLE -n com.ajink.tvquick/.QuickPanelActivity`
on a second ADB stream (the shell uid has that privilege). The panel handles
`ACTION_TOGGLE` itself: `onCreate` opens it, `onNewIntent` finishes it, so the
same intent both opens and closes.

### Reconnect policy

* Socket/stream error: backoff 2 s -> 30 s.
* Authorization rejected/pending: 60 s -> 2 min -> 4 min -> 5 min, because every
  attempt raises a new dialog on screen. Opening the panel retries immediately
  (`mRetryNow`), and `START_STICKY` + a `BOOT_COMPLETED` receiver keep the
  service alive.

## Setup that was required (and why it disqualified the bridge)

1. ADB over network must be on: `service.adb.tcp.port=5555` (it is).
2. Open the panel once. The TV shows *"Allow USB debugging?"* for
   `tvquick@tv`.
3. Accept it. In theory the key is written to `/data/misc/adb/adb_keys` and the
   dialog does not come back. In practice adbd rejected the key
   (`Invalid base64 key`), so step 3 had to be repeated after every reconnect.
4. Every accessibility service that requests key filtering had to stay off,
   which is what made the gear impossible without ADB in the first place.

## Verification (historical)

```sh
adb logcat -s TvGearBridge:D
# expect: connecting to adb 127.0.0.1:5555 / adb connected, opening getevent /
#         getevent running
# then press the gear:
#         ev: /dev/input/event4: 0001 01f5 00000001
#         gear pressed
```

The shipped acceptance criteria live in `docs/bugs/volume-long-press.md`: the
filter follows the foreground package, so volume ramps natively in any app and
the gear works on the launcher and the panel.
