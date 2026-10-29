# Bug: volume long-press does not repeat (single press only)

**Status:** open, unconfirmed cause
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

## What is on the device

Accessibility services enabled at the time of the report:

```
com.spocky.projengmenu/.services.ProjectivyAccessibilityService
com.ajink.tvquick/.KeyService
```

`KeyService` (tv-quick-panel) requests `FLAG_REQUEST_FILTER_KEY_EVENTS` and
returns `false` for every scancode except the gear button's 501. In theory a
service that returns `false` must not consume the event, repeats included, but
the framework does route every key event, including `ACTION_DOWN` with
`repeatCount > 0`, through `onKeyEvent` while a filtering service is enabled.

The launcher (`com.ajink.tvglass`) is not a suspect by code inspection:
`HomeActivity.onKeyDown` only claims MENU/SETTINGS, and `AppsActivity` /
`SettingsActivity` do the same. Volume falls through to `super`.

## Suspects, most likely first

1. **Accessibility key filtering interacting with key repeat.** Two filtering
   services are enabled. Either one returning `true` for a repeat would swallow
   it. Projectivy is the one with unknown behaviour; `KeyService` returns
   `false`, but `onServiceConnected` rewrites the service info with
   `setServiceInfo()`, which is worth verifying did not clear
   `FLAG_REQUEST_FILTER_KEY_EVENTS` handling of repeats.
2. **Projectivy's own remote handling.** It predates the panel, but a Projectivy
   update could have changed repeat handling at the same time the panel was
   installed, which would look like \"the panel broke it\".
3. **Firmware input dispatcher.** The R4G may generate volume repeats in the
   kernel/HAL rather than in the app processor; a change in `dumpsys input`
   repeat settings would show this.
4. **Launcher focus path.** Unlikely, but cheap to rule out: the launcher holds
   focus on Home, and if it consumed repeats the ramp would stop there.

## Fix attempted in KeyService

- `onKeyEvent` fast path is a single integer comparison (`scanCode != GEAR_SCANCODE`)
  followed by `return false`. No object allocation, no logging on the hot path.
- Volume key scan codes (114/115) are documented but not special-cased beyond the
  fast path; they fall through to `return false` like every other non-gear key.
- Diagnostic logging is available behind `DEBUG_VOLUME = true` (off by default) to
  capture `keyCode / scanCode / action / repeatCount` for every volume event.
- Gear key repeats are still consumed (`return true`) but do nothing, so holding
  the gear button does not toggle the panel repeatedly.

The remaining suspected cause is the IPC overhead of routing every key event
(including volume repeats) through the accessibility service's `onKeyEvent`. Even
though the method returns `false` immediately, the synchronous Binder call from
the system server to the service process introduces latency that can break the
repeat timing on some Android versions. This cannot be eliminated while the
service uses `FLAG_REQUEST_FILTER_KEY_EVENTS`; it can only be minimised.

## Diagnostics to run

```sh
# 1. Does the ramp return with the panel's service off (Projectivy kept)?
adb shell settings put secure enabled_accessibility_services \\
  'com.spocky.projengmenu/.services.ProjectivyAccessibilityService'
# hold VOL+, then restore:
adb shell settings put secure enabled_accessibility_services \\
  'com.spocky.projengmenu/.services.ProjectivyAccessibilityService:com.ajink.tvquick/.KeyService'

# 2. Does it return with only the panel's service on?
adb shell settings put secure enabled_accessibility_services \\
  'com.ajink.tvquick/.KeyService'

# 3. Raw events and repeat counts while holding the key
adb shell getevent -l
adb logcat -c && adb logcat -s TvQuickKey:D

# 4. Input dispatcher repeat configuration
adb shell dumpsys input | findstr /i "repeat key"
```

Instrument `KeyService.onKeyEvent` temporarily by setting `DEBUG_VOLUME = true`,
rebuild, and hold `VOL +`. If repeats arrive with `repeatCount > 0` and the
method returns `false`, the panel is not the cause and the next step is disabling
Projectivy. If repeats do NOT arrive, the framework is suppressing them because
of the service's presence, and the fix is to avoid `FLAG_REQUEST_FILTER_KEY_EVENTS`
entirely (e.g. by detecting the gear button via a different mechanism).

## Workaround until fixed

- Tap the volume key repeatedly instead of holding it.
- Or disable the tv-quick-panel accessibility service while not using the panel
  (the panel's gear button stops working).

## Acceptance criteria

- Holding `VOL +`/`VOL -` ramps the stream volume continuously with the panel
  service enabled.
- Gear button still opens/closes the panel.
- No volume key event is consumed by either app: verify with the
  `KeyService` repeat log showing `return false` for every volume event.

1. **Accessibility key filtering interacting with key repeat.** Two filtering
   services are enabled. Either one returning `true` for a repeat would swallow
   it. Projectivy is the one with unknown behaviour; `KeyService` returns
   `false`, but `onServiceConnected` rewrites the service info with
   `setServiceInfo()`, which is worth verifying did not clear
   `FLAG_REQUEST_FILTER_KEY_EVENTS` handling of repeats.
2. **Projectivy's own remote handling.** It predates the panel, but a Projectivy
   update could have changed repeat handling at the same time the panel was
   installed, which would look like "the panel broke it".
3. **Firmware input dispatcher.** The R4G may generate volume repeats in the
   kernel/HAL rather than in the app processor; a change in `dumpsys input`
   repeat settings would show this.
4. **Launcher focus path.** Unlikely, but cheap to rule out: the launcher holds
   focus on Home, and if it consumed repeats the ramp would stop there.

## Diagnostics to run

```sh
# 1. Does the ramp return with the panel's service off (Projectivy kept)?
adb shell settings put secure enabled_accessibility_services \
  'com.spocky.projengmenu/.services.ProjectivyAccessibilityService'
# hold VOL+, then restore:
adb shell settings put secure enabled_accessibility_services \
  'com.spocky.projengmenu/.services.ProjectivyAccessibilityService:com.ajink.tvquick/.KeyService'

# 2. Does it return with only the panel's service on?
adb shell settings put secure enabled_accessibility_services \
  'com.ajink.tvquick/.KeyService'

# 3. Raw events and repeat counts while holding the key
adb shell getevent -l
adb logcat -c && adb logcat | findstr /i "KeyEvent repeat volume"

# 4. Input dispatcher repeat configuration
adb shell dumpsys input | findstr /i "repeat key"
```

Instrument `KeyService.onKeyEvent` temporarily to log
`keyCode / scanCode / action / repeatCount` for every event, then hold `VOL +`.
If repeats arrive with `repeatCount > 0` and the method returns `false`, the
panel is not the cause and the next step is disabling Projectivy.

## Workaround until fixed

- Tap the volume key repeatedly instead of holding it.
- Or disable the tv-quick-panel accessibility service while not using the panel
  (the panel's gear button stops working).

## Acceptance criteria

- Holding `VOL +`/`VOL -` ramps the stream volume continuously with the panel
  service enabled.
- Gear button still opens/closes the panel.
- No volume key event is consumed by either app: verify with the
  `KeyService` repeat log showing `return false` for every volume event.
