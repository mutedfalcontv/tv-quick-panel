# Bug: long-pressing HOME has no usable handler (crash reported)

**Status:** fixed by resolving the action to the panel (2026-10-31); the long
press itself was not sent on purpose because the TV reportedly reboots - owner to
verify
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Related:** `docs/analysis/rc600-remote-buttons.md`, `docs/bugs/home-button-reboot.md`

## What long-press HOME actually sends

The framework value is readable over ADB:

```sh
adb shell cmd overlay lookup android android:integer/config_longPressOnHomeBehavior  # -> 1
adb shell cmd overlay lookup android android:integer/config_doubleTapOnHomeBehavior  # -> 2
```

`1` is `LONG_PRESS_HOME_ALL_APPS`, so the policy launches
`android.intent.action.ALL_APPS` (double tap is the recents behavior).

## Why it is broken here

* The stock launcher `com.google.android.apps.tv.launcherx` is disabled
  (`pm disable-user`) because this project replaces the home screen.
* Projectivy (`com.spocky.projengmenu`) currently answers `ACTION_ALL_APPS`
  with `AllAppsSidePanelActivity` - it is the only enabled resolver before this
  change, and it is not the home app here.
* With `tv-quick-panel` also declaring `ACTION_ALL_APPS`, resolution now shows
  the system chooser (verified with `am start -a android.intent.action.ALL_APPS`,
  which produced `ResolverActivity` listing Quick Panel and Projectivy). A
  chooser is not acceptable for a single physical gesture.

The reported crash was **not** reproduced: the long-press was deliberately not
sent, because a reboot was reported and the user asked to be asked first.

## Fix staged in tv-quick-panel

`QuickPanelActivity` declares:

```xml
<intent-filter android:priority="100">
    <action android:name="android.intent.action.ALL_APPS" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

and treats `ACTION_ALL_APPS` as an open gesture (only the toggle actions finish
the activity). With a single resolver, a physical long-press HOME opens the quick
panel and the missing-handler error path disappears.

## What was still needed

Remove Projectivy from the `ACTION_ALL_APPS` resolution. ADB cannot disable a
single component of another app (`SecurityException: Shell cannot change
component state`), so the whole package was disabled (below). Alternatives were
keeping both and choosing "Quick Panel - Always" once in the chooser, or a
passive bridge fallback - which would not stop the system handler from running.

## Decision and verification (2026-10-31)

Projectivy was removed from the resolution:

```sh
adb shell pm disable-user --user 0 com.spocky.projengmenu   # reversible
# to restore: adb shell pm enable com.spocky.projengmenu
```

Resolution now returns exactly one activity, and launching the same intent the
policy uses opens the panel:

```
cmd package query-activities -a android.intent.action.ALL_APPS
  -> com.ajink.tvquick.QuickPanelActivity (only)

am start -a android.intent.action.ALL_APPS
  -> topResumedActivity = com.ajink.tvquick/.QuickPanelActivity
  -> ActivityTaskManager: START ... cmp=com.ajink.tvquick/.QuickPanelActivity
```

No chooser and no error. The remaining step is a physical long-press to confirm
end to end; that is the owner's call because of the reported reboot.

## Safe verification without a long-press

```sh
adb shell cmd package query-activities -a android.intent.action.ALL_APPS
adb shell am start -a android.intent.action.ALL_APPS   # opens the panel or chooser
```
