# Analysis: how tvQuickActions intercepts remote keys (rea decompile)

**Target:** `tvQuickActions` 3.5.3-free (`dev.vodik7.tvquickactions.free`, versionCode 262)
**Source:** `C:\Games\Uncharted-windows\tvQuickActions.xapk` (same file also at
`C:\Users\ajink\Downloads\tvQuickActions.xapk`)
**Tooling:** REA project at `C:\Users\ajink\Projects\rea` (jadx 1.5.6 from
`rea\tools\jadx`, apktool output already in `rea\out\tvqa-res`)
**Goal:** find out how a shipping app keeps a gear/MENU button working globally
without breaking volume long-press.

## TL;DR

They do it exactly the way this project does: an accessibility service with
`flagRequestFilterKeyEvents`, returning `true` only for the keys they remap and
`false` for everything else. **They never touch volume keys.** So their
mechanism is not a repeat-safe variant of ours - the volume behaviour comes from
the platform, not from them.

## Where the keys are intercepted

Only one accessibility service exists in the APK:

`dev.vodik7.tvquickactions.KeyAccessibilityService`
(extends `t6.a`, which extends `AccessibilityService` and also implements
`DisplayManager.DisplayListener` for frame-rate switching).

Its config is declarative, in `res/xml/accessibility_service_config.xml`:

```xml
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFlags="flagReportViewIds|flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canRequestTouchExplorationMode="true"
    android:canRequestFilterKeyEvents="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true" />
```

Notes:

* `canRequestFilterKeyEvents="true"` plus `flagRequestFilterKeyEvents` is the
  same combination this project uses.
* `onServiceConnected()` never calls `setServiceInfo()`; the XML is the single
  source of truth. This project did call `setServiceInfo()` and that is what
  silently dropped the filter flag in build `5848d22`.
* No `notificationTimeout` is set (0), while this project sets 100. Not proven to
  matter, but there is no reason to keep a throttle on the key path.

## `onKeyEvent` structure

```java
public final boolean onKeyEvent(KeyEvent keyEvent) {
    int action  = keyEvent.getAction();
    int keyCode = keyEvent.getKeyCode();
    int hash    = keyCode == 0 ? ("unknown:" + keyEvent.getScanCode()).hashCode() : keyCode;
    if (mPrefs.pairMode) hash = ("pair:" + keyCode + "&" + keyEvent.getScanCode()).hashCode();

    Log "isLongPress: ... repeatedCount: ..."
    if (action == ACTION_DOWN) tempArr.clear();

    ... remap engine ...
    return z8 || super.onKeyEvent(keyEvent);
}
```

Key points:

1. **Pair mode.** When enabled they key on `keyCode & scanCode` instead of
   `keyCode`. That is how they distinguish buttons that share a key code - the
   same problem this project solves for scancode 501 by matching the scancode
   directly.
2. **Repeat handling is not special cased.** They log `getRepeatCount()` and
   `isLongPress()` for diagnostics only. There is no branch on repeats, no
   timer, no re-injection of repeats.
3. **Return value.** `true` (consume) only when a remap action matched;
   `false` otherwise, so unremapped keys fall through to the system.

## The one place they consume everything: key-learning mode

`KeyAccessibilityService.c0` is a runtime flag set to `true` while the app is
recording a new button:

```java
Integer[] passthrough = o5.h.f24732a; // {23 DPAD_CENTER, 19 UP, 21 LEFT, 66 ENTER, 20 DOWN, 22 RIGHT, 160 NUM_ENTER}
if (!contains(passthrough, hash) && hash != KEYCODE_BACK && action == ACTION_UP) {
    Intent i = new Intent("dev.vodik7.tvquickactions.free.KEY_RECEIVED");
    i.putExtra("key", hash);
    sendBroadcast(i);
}
z8 = (contains(passthrough, hash) || hash == KEYCODE_BACK) ? false : true;
```

So during learning they swallow everything except D-pad/ENTER/BACK. Volume keys
are swallowed too in that mode - which is why recording a button while holding
volume is not possible. Outside learning mode, volume is untouched.

## Pass-through of consumed keys

For keys they consume, they re-inject them into their own dialog/window only:

```java
Window window = dialog.getWindow();
window.setLocalFocus(true, false);
window.injectInputEvent(keyEvent);   // Window.injectInputEvent, API 30+
```

That is `Window.injectInputEvent`, not global `InputManager.injectInputEvent` -
it targets their own window (dialpad, media view, AFR view). They do not run a
global "consume then re-inject" pass-through, which is the pattern that would
have made the volume fix unnecessary.

## Audio

No `AudioManager` usage anywhere in the app:

```
grep -r "adjustStreamVolume|adjustVolume|STREAM_MUSIC" -> no matches
```

They do not take over volume and they do not compensate for lost repeats.

## Consequences for this project

* Copying tvQuickActions verbatim does not fix the volume ramp; the cause is
  the platform routing described in `docs/bugs/volume-long-press.md`.
* What is worth copying:
  * declarative service config only (no `setServiceInfo()`),
  * key identity = keyCode + scanCode (already done for the gear button),
  * log `repeatCount` / `isLongPress` behind a debug flag when diagnosing.
* What is not worth copying:
  * `typeAllMask` event types + `canRetrieveWindowContent` +
    `canTakeScreenshot`: they need window content for their own UI; this project
    only needs key filtering, so the narrower config is cheaper.
* What this project adds beyond their design: the filter is scoped to the
  launcher and the panel instead of being permanently on, so volume keeps its
  native ramp in every other app. Their design has the identical volume
  limitation (their own strings tell the user to wait 10 s with the service
  disabled).

## Reproduction commands

```powershell
# decompile (jadx needs JDK 17, not 21)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
& C:\Users\ajink\Projects\rea\tools\jadx\bin\jadx.bat -d <out> <base.apk>

# the interesting files
sources/dev/vodik7/tvquickactions/KeyAccessibilityService.java   # onKeyEvent
sources/o5/h.java                                               # key code tables
res/xml/accessibility_service_config.xml                        # service config
```
## ADB mode (getevent) — historical

tvQuickActions also ships an **ADB-based remap path** that works **without** the
accessibility service (see AdbLibService in the decompile). It informed
`GearBridgeService`, which was built and then retired (the TV's adbd would not
persist the app's key, so it re-prompted on every reconnect). The shipped design
goes the other way: a scoped accessibility filter. Details:

- AdbLibService opens a localhost connection to adbd (127.0.0.1:5555) using
  an app-owned RSA key (stored in the app's private storage, persisted in
  /data/misc/adb/adb_keys after the user accepts the prompt once).
- It authenticates with the standard ADB host protocol (CNXN → AUTH TOKEN →
  AUTH SIGNATURE → AUTH RSAPUBLICKEY → CNXN).
- Shell commands are sent as \"shell:" + command\ (e.g. \"shell:echo yes"\,
  \"shell:getevent"\); adbd runs them as the shell user.
- The getevent stream is read line by line; lines look like
  \/dev/input/event4: 0001 01f5 00000001\ (EV_KEY, code 501, value 1).
- Activities are started over the same stream with
  \m start -a <action> -n <component>\, because the shell uid may start
  activities from the background while a plain foreground service may not.

### Why we ended up here

On the MOKA R4G (Android 14), **any** accessibility service that requests
\FLAG_REQUEST_FILTER_KEY_EVENTS\ flips \InputDispatcher.mInputFilterEnabled\,
which routes every key through \KeyboardInterceptor\. For volume keys the
interceptor consumes them before a \KeyEntry\ is created, so the platform
never generates key repeats and volume long-press stops ramping. This was
measured on the device: \dumpsys input\ reports \InputFilterEnabled: true\
with either Projectivy's or this app's accessibility service enabled, and
\alse\ with both off. Projectivy's service causes the same regression.

Therefore the gear button (scancode 501, surfaces as \KEYCODE_MENU\) is
watched over ADB/getevent instead, and the accessibility service stays off so
volume keeps its native long-press ramp. See
\docs/analysis/adb-gear-bridge.md\ for the implementation and
\docs/bugs/volume-long-press.md\ for the bug write-up.
