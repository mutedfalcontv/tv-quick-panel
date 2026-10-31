# Bug: volume long-press does not repeat (single press only)

**Status:** fixed on device with a scoped filter (2026-10-31); one documented
trade-off remains on the home screen
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

## Fix implemented: scope the filter to our own foreground apps (KeyService)

The filter only has to be on while the gear is the useful key - on the launcher
and on the panel. Anywhere else, volume matters more and the gear does nothing.

* `KeyService` receives `TYPE_WINDOW_STATE_CHANGED`, checks the foreground
  package, and toggles `FLAG_REQUEST_FILTER_KEY_EVENTS` on its own
  `AccessibilityServiceInfo` through `setServiceInfo()`:
  * `com.ajink.tvglass` (launcher) or `com.ajink.tvquick` (panel) -> filter on,
  * anything else -> filter off.
* Window events are not enough on their own: `TYPE_WINDOW_STATE_CHANGED` fires
  when a window is added, not when an existing window is brought back to the
  front. Returning to the launcher, or to a panel that was covered by a settings
  page, produces no event - measured on the TV. Both activities therefore
  broadcast `com.ajink.tvquick.action.OURS_FOREGROUND` from `onResume`, and
  `KeyService` turns the filter on from that broadcast.
* The window that was just covered tears down a moment later and emits its own
  state change (measured: 140 ms after the resume). Events from other packages
  during the 500 ms after an `OURS_FOREGROUND` broadcast are that tail, not a
  real app switch, and are ignored.
* Toggling the flag at runtime is instant and needs no settings write and no
  service restart. The service itself stays enabled, so no reboot-time
  re-approval is needed.
* While the filter is on, `onKeyEvent` consumes only the gear (scancode 501 /
  `KEYCODE_NOTIFICATION`); everything else returns `false` untouched.
* The panel is toggled with the same action SystemUI would use,
  `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL`, on an explicit
  component. `onNewIntent` finishes the single-top instance, so the same button
  opens and closes it.
* The ADB/`getevent` bridge (`GearBridgeService`) is retired: adbd on this build
  re-prompts for USB debugging after every reconnect, which a remote-only TV
  cannot tolerate. See `docs/analysis/adb-gear-bridge.md`.

## Measured on the TV (2026-10-31)

| Configuration | Gear button | Volume held |
| --- | --- | --- |
| Filter off (third-party app foreground) | does nothing | ramps natively (measured 52 -> 100 on one hold) |
| Filter on (launcher/panel foreground) | opens and closes the panel | one step per press |

`adb shell dumpsys input | grep InputFilter` follows the foreground package.
Verified end to end on the TV by driving the activities over ADB:

| Foreground | `InputFilterEnabled` |
| --- | --- |
| launcher | `true` |
| Settings | `false` |
| launcher (returned) | `true` |
| panel | `true` |
| Settings opened from the panel | `false` |
| panel (returned) | `true` |

## Remaining trade-off

On the home screen the filter must be on for the gear to work, so holding volume
there still gives a single step. Any app (including the panel's own targets)
restores the native ramp. There is no app-side way around this - it is the price
of intercepting a key on a build where the policy owns volume.

## Diagnosis recipe

`adb shell dumpsys input | grep InputFilter` shows the current state, and the
service logs every transition:

```sh
adb logcat -s TvQuickKey:I
# key filter on        <- launcher or panel came forward
# key filter off       <- another app came forward
```

## References

* `docs/analysis/tvquickactions-key-interception.md` - what the shipping
  remapper does (same mechanism, same volume limitation).
* `docs/analysis/adb-gear-bridge.md` - the retired `getevent` alternative.
