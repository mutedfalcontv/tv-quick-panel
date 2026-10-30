# Bug: volume long-press does not repeat (single press only)

**Status:** root cause confirmed on device; fix path is now key: **no accessibility service may request key filtering**
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

   * policy returns `-1` (TV routing): event swallowed, volume already adjusted
     by `PhoneWindowManager.dispatchDirectAudioEvent()`.
   * policy returns `0` (no TV routing): event is forwarded to accessibility
     services, and if none handles it the platform **re-injects** it into
     `InputDispatcher`.

3. Re-injection loses the native repeat chain. `InputDispatcher::notifyKey()`
   hardcodes the repeat count:

   ```cpp
   // InputDispatcher tracks and generates key repeats on behalf of
   // whatever notifies it, so repeatCount should always be set to 0
   constexpr int32_t repeatCount = 0;
   ```

   Repeats are generated per `KeyEntry` inside `dispatchKeyLocked()`. A key
   swallowed by the input filter never creates that `KeyEntry`, so the
   re-injected copy is just a fresh press. Result: one step per physical press,
   no ramp.

4. `interceptKeyResult` is cached per `KeyEntry` (`InputDispatcher.cpp`), so the
   filter/policy is consulted once per press and repeats normally bypass it.
   Returning `false` quickly from `onKeyEvent` therefore cannot help - the
   damage is done by the event being routed through the filter at all.

This also explains why the earlier `setServiceInfo()` change made things worse in
a different way: `setServiceInfo()` replaces the entire config, so passing a
fresh `AccessibilityServiceInfo` silently dropped
`FLAG_REQUEST_FILTER_KEY_EVENTS` and disabled gear detection
(build `5848d22`).

## Why tvQuickActions does not have this problem

`tvQuickActions` 3.5.3 uses exactly the same mechanism
(`flagRequestFilterKeyEvents` in its accessibility XML) and does **not** special
case volume anywhere in its code. Its only advantage is that its remap engine
consumes the keys it acts on, so those keys never take the re-injection path.
For unremapped volume keys it is in the same position as this app.

See `docs/analysis/tvquickactions-key-interception.md` for the full decompile
analysis.

## Fix implemented (KeyService)

Take the volume keys over instead of letting them fall through the filter:

* `VOLUME_UP` / `VOLUME_DOWN` `ACTION_DOWN` (`repeatCount == 0`): one
  `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` step with
  `FLAG_SHOW_UI`, then a self-scheduled ramp every 120 ms until `ACTION_UP`
  (first repeat after 400 ms, hard stop after 8 s).
* `VOLUME_MUTE`: toggle once.
* The events are consumed (`return true`), so the platform never re-injects them
  and the ramp no longer depends on framework repeat delivery.
* If the platform ever does deliver real repeats (`repeatCount > 0`, TV-routing
  devices), the local ramp is cancelled so the volume cannot step twice.
* Everything else still returns `false` from a single integer comparison.

Service config stays in `res/xml/key_service.xml`; `onServiceConnected()` no
longer calls `setServiceInfo()`.

## Diagnostics

`DEBUG_KEYS = true` in `KeyService` logs volume and gear keys only (D-pad stays
off the hot path):

```sh
adb logcat -c && adb logcat -s TvQuickKey:D
# press VOL+ once, then hold VOL+ for two seconds
```

Expected when the fix is active:

```
key action=0 code=24 scan=114 repeat=0 long=false   <- press, one step
key action=1 code=24 scan=114 repeat=0 long=false   <- release, ramp stops
```

If the volume key lines never appear, the device uses TV routing: the policy
consumes the keys inside the interceptor and the service is not involved at all.

## On-device confirmation of the fix (2026-10-30)

* `adb shell dumpsys input | grep InputFilter`:
  * no accessibility services enabled -> `InputFilterEnabled: false`
  * Projectivy only -> `true`  <- also kills volume long-press
  * tv-quick-panel only -> `true`
* The tv-quick-panel accessibility service therefore has to stay **off**.
  The gear button is now watched over ADB instead (`docs/analysis/adb-gear-bridge.md`).

## Remaining acceptance criterion

With the accessibility services off, holding `VOL +` / `VOL -` must ramp the
stream volume continuously. The input-filter precondition for that ramp is
verified on the device; a physical hold still needs to be done on the TV.

## Update 2026-10-30 (ADB path)

The volume ramp itself never runs inside `KeyService` on this TV (the policy
eats volume keys before they reach any accessibility service), so the ramp can
only come back by dropping the key filter entirely.

Action taken on the TV:

```sh
adb shell settings put secure enabled_accessibility_services ""
```

`dumpsys input` now reports `InputFilterEnabled: false`, which is the state the
platform needs for the native long-press ramp to be generated.

The gear button is handled by the ADB bridge
(`com.ajink.tvquick.GearBridgeService` + `com.ajink.tvquick.adb.*`), which reads
raw events through `getevent` on the device's adbd. See
`docs/analysis/adb-gear-bridge.md`.
