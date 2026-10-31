# Bug: volume long-press does not repeat (single press only)

**Status:** fixed on device (2026-10-31). The gear is read over adbd, so the gear
button and the native volume long-press ramp both work in every app.
**Reported:** 2026-10-29
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Area:** remote input / accessibility key filtering

## Symptom

Holding `VOL +` or `VOL -` changes the volume by one step and then stops.
A single press still works. Holding the same key used to ramp the volume
continuously.

The user reports this is a regression: "it used to work before".

## Reproduction

1. Play anything with audio, or open the volume overlay.
2. Hold `VOL +` for two seconds.
3. Observe: one step only (actual) vs continuous ramp (expected).
4. Tap `VOL +` repeatedly: each tap steps once, so the key itself is alive.

## Root cause (AOSP 14 source, verified)

The accessibility key filter is not a passive observer: enabling it changes how
the whole input pipeline works.

1. `InputDispatcher::shouldSendKeyToInputFilterLocked()` returns
   `mInputFilterEnabled` for **every** key event. `mInputFilterEnabled` becomes
   true as soon as any accessibility service requests
   `FLAG_REQUEST_FILTER_KEY_EVENTS`. From that moment keys no longer go straight
   from the driver to the focused app.

2. `KeyboardInterceptor` (system_server) is the handler for those keys and has a
   volume special case:

   ```java
   // services/accessibility/java/com/android/server/accessibility/KeyboardInterceptor.java
   private long getEventDelay(KeyEvent event, int policyFlags) {
       int keyCode = event.getKeyCode();
       if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) || (keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
           return mPolicy.interceptKeyBeforeDispatching(null, event, policyFlags);
       }
       return 0;
   }
   ```

   On this TV the policy consumes the key (TV audio routing) and steps the
   stream itself. The event is swallowed inside the interceptor and never
   reaches `InputDispatcher`, never reaches any accessibility service, and never
   creates a `KeyEntry`.

3. Repeats are generated per `KeyEntry` in `dispatchKeyLocked()`, so no
   `KeyEntry` means no repeats. `InputDispatcher::notifyKey()` also hardcodes
   `repeatCount = 0` for anything that enters it, so even the re-injection path
   cannot restore a hold:

   ```cpp
   // InputDispatcher tracks and generates key repeats on behalf of
   // whatever notifies it, so repeatCount should always be set to 0
   constexpr int32_t repeatCount = 0;
   ```

   Result: one step per physical press, no ramp.

4. `interceptKeyResult` is cached per `KeyEntry` (`InputDispatcher.cpp`), so the
   filter/policy is consulted once per press and repeats normally bypass it.
   Returning `false` quickly from `onKeyEvent` therefore cannot help - the
   damage is done by the event being routed through the filter at all.

This also explains why the earlier `setServiceInfo()` change made things worse in
a different way: `setServiceInfo()` replaces the entire config, so passing a
fresh `AccessibilityServiceInfo` silently dropped
`FLAG_REQUEST_FILTER_KEY_EVENTS` and disabled gear detection
(build `5848d22`).

## Attempts that were measured and did not work

All of these were run on the TV on 2026-10-31, not reasoned about:

| Attempt | Result |
| --- | --- |
| Filter on, pass every key through (`return false`) | volume still steps once |
| Filter on, own the ramp with `AudioManager` | never runs: volume keys are consumed before accessibility sees them |
| Watch `android.media.VOLUME_CHANGED_ACTION`, drop the filter for 4 s after the first step | ramp still does not resume - the held key has no `KeyEntry`, and the RC600 sends press/release only (repeats are OS-generated) |
| Disable the vendor `com.realtek.keyinterceptserver` | no change; the framework policy is the consumer |
| Declare `TOGGLE_NOTIFICATION_HANDLER_PANEL` and hope SystemUI routes the gear to us | SystemUI resolves with `MATCH_SYSTEM_ONLY`; a normally installed app can never match |
| Inject the key from the service | `INPUT_INJECTION` is a system permission |

Conclusion: with a normal app on this TV, the gear button and the native volume
ramp cannot both be owned at the same instant.

## Fix implemented: read the gear over adbd (GearBridgeService)

The gear is not intercepted in the input pipeline at all. `GearBridgeService`
connects to the device's own adbd on `127.0.0.1:5555`, runs `getevent`, and
looks for scancode 501 (`EV_KEY`, code `0x01f5`). Nothing in the key path
changes, so the native volume long-press ramp works in every app, and the gear
works in every app too.

* Opening/closing the panel is done with
  `am start -a com.ajink.tvquick.action.TOGGLE -n com.ajink.tvquick/.QuickPanelActivity`
  on the same adb stream, because a foreground service may not start activities
  from the background. `onNewIntent` finishes the single-top instance, so one
  press opens and the next closes.
* No accessibility service is declared or enabled, so
  `InputFilterEnabled` is `false` in every app.
* The bridge authenticates as an ADB host. Push the PC's already-authorized key
  once (both files), so the TV never has to show a debugging dialog:

  ```sh
  adb shell mkdir -p /sdcard/Android/data/com.ajink.tvquick/files
  adb push ~/.android/adbkey     /sdcard/Android/data/com.ajink.tvquick/files/adbkey
  adb push ~/.android/adbkey.pub /sdcard/Android/data/com.ajink.tvquick/files/adbkey.pub
  ```

  The `.pub` file matters: adbd treats the same key material under a different
  name as a different `adb_keys` entry (measured - this is what kept making the
  dialog reappear).
* adbd still asks the framework on first connect, and the framework
  auto-approves a key it stored with "always allow" (auth window is 7 days and
  refreshes on use), so no dialog appears. Measured: connect to `getevent` in
  ~3 s with no UI.
* `BootReceiver` restarts the bridge after a reboot. The service is a
  `specialUse` foreground service with a minimal, silent notification.

## Measured on the TV (2026-10-31)

| Design | Gear | Volume held | Accessibility filter |
| --- | --- | --- | --- |
| Filter always on | every app | one step everywhere | on |
| Filter scoped to our apps | launcher/panel only | native elsewhere | follows foreground |
| **ADB bridge (shipped)** | **every app** | **native everywhere** | **off** |

`dumpsys input | grep InputFilter` is `false` in every app with the service
disabled, and `logcat -s TvGearBridge:I` shows `adb connected` /
`getevent running`.

## Diagnosis recipe

```sh
adb logcat -s TvGearBridge:I
# connecting to adb 127.0.0.1:5555
# adb connected, opening getevent
# getevent running
# gear pressed                      <- on the physical button

adb shell dumpsys input | grep InputFilter    # must be false
```

## References

* `docs/analysis/tvquickactions-key-interception.md` - what the shipping
  remapper does (same mechanism, same volume limitation).
* `docs/analysis/adb-gear-bridge.md` - the retired `getevent` alternative.
