# Analysis: the gear button without an accessibility key filter

**Status:** shipped (2026-10-31). `GearBridgeService` is the only way the gear
reaches the panel; there is no accessibility service any more.
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Related:** `docs/bugs/volume-long-press.md`

## What made the first version re-prompt

The first bridge generated its own RSA key and sent it as
`... tvquick@tv`. adbd matches an `adb_keys` line as a whole: the same key
material under a different name is a different entry, so every reconnect showed
*"Allow USB debugging?"* again. (The device also carries one malformed entry in
`adb_keys`, which adbd logs as `Invalid base64 key` and ignores.)

The shipped version reuses the key the PC already authorized. The private key and
`adbkey.pub` are pushed once into the app's external files dir, and the app sends
the exact same key line (material *and* name). The framework then auto-approves a
key it stored with "always allow" - measured several times as a connect in ~3 s
with no UI. This firmware still asks after some reconnects, so a tap on the TV is
occasionally needed; once accepted the connection stays up.

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

`OPEN` with `shell:cat /dev/input/event4\0` streams the device node as the shell
user. Output arrives as `WRTE` packets; each one is acked with `OKAY` for flow
control. The payload is a sequence of `struct input_event`:

| offset (32-bit) | field |
| --- | --- |
| 0 | `timeval.tv_sec` (4 bytes) |
| 4 | `timeval.tv_usec` (4 bytes) |
| 8 | `type` (u16, little endian) |
| 10 | `code` (u16) |
| 12 | `value` (s32) |

A 64-bit process sees 8-byte `timeval` fields instead (24-byte records), so the
bridge picks the record size from `Process.is64Bit()`. The gear is `type=1`
(`EV_KEY`), `code=501`, value `1` (down) / `0` (up). Every other code is ignored,
so volume, D-pad and media keys never touch this path.

Two gotchas cost real debugging time here:

* **`getevent` cannot be used as the stream.** Its stdout is block-buffered when
  it is a pipe, so a handful of key presses never arrive (the app saw nothing for
  minutes). `cat` on the node writes each 16-byte record immediately.
* **The `OKAY` ack must be `(our stream id, adbd's stream id)`.** Sending them
  back in the order they arrived (`arg0, arg1`) is silently ignored by adbd; its
  send window never reopens, and the stream stalls after the first `WRTE` - one
  gear press worked, every press after it did nothing.

### Opening the panel

A foreground service may not start activities from the background on Android
10+, so the bridge runs
`am start -a com.ajink.tvquick.action.TOGGLE -n com.ajink.tvquick/.QuickPanelActivity`
on a second ADB stream (the shell uid has that privilege). The panel handles
`ACTION_TOGGLE` itself: `onCreate` opens it, `onNewIntent` finishes it, so the
same intent both opens and closes.

### Reconnect policy

* Socket/stream error: backoff 2 s -> 30 s.
* Authorization rejected/pending: 60 s -> 2 min -> 4 min -> 5 min, so a key the
  TV no longer knows cannot hammer the authorization path. Opening the panel
  retries immediately (`mRetryNow`), and `START_STICKY` + a `BOOT_COMPLETED`
  receiver keep the service alive.

## Setup (one time)

1. ADB over network must be on: `service.adb.tcp.port=5555` (it is).
2. Push the PC's already-authorized key, both halves:

   ```sh
   adb shell mkdir -p /sdcard/Android/data/com.ajink.tvquick/files
   adb push ~/.android/adbkey     /sdcard/Android/data/com.ajink.tvquick/files/adbkey
   adb push ~/.android/adbkey.pub /sdcard/Android/data/com.ajink.tvquick/files/adbkey.pub
   ```

   The `.pub` file supplies the name; adbd matches the whole key line.
3. Open the panel once (or reboot). It starts `GearBridgeService`, which keeps
   the adb connection alive and restarts it after a reconnect or reboot.
4. Disable every accessibility service that requests key filtering - that is what
   keeps volume long-press native:

   ```sh
   adb shell settings delete secure enabled_accessibility_services
   ```

## Verification

```sh
adb logcat -s TvGearBridge:I
# expect: connecting to adb 127.0.0.1:5555 / adb connected, opening getevent /
#         getevent running
# then press the gear:
#         gear pressed

adb shell dumpsys input | grep InputFilter   # false, always
```

The acceptance criteria live in `docs/bugs/volume-long-press.md`: the gear works
in every app and the platform ramps volume natively, because nothing in the input
path is intercepted.
