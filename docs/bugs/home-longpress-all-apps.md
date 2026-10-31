# Bug: long-pressing HOME has no usable handler (crash reported)

**Status:** cause identified (2026-10-31); fix staged, long-press not tested on
purpose because the TV reportedly reboots
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

## What is still needed

* Remove Projectivy from the `ACTION_ALL_APPS` resolution. ADB cannot disable a
  single component of another app (`SecurityException: Shell cannot change
  component state`), so the choice is:
  * `pm disable-user com.spocky.projengmenu` (whole package, reversible with
    `pm enable com.spocky.projengmenu`), if Projectivy is no longer used; or
  * keep both and pick "Quick Panel - Always" once in the chooser; or
  * a bridge fallback that detects HOME long-press in the raw stream (passive -
    it cannot stop the system's own handler from running).
* Then verify with a **real long-press**, only with the owner's approval.

## Safe verification without a long-press

```sh
adb shell cmd package query-activities -a android.intent.action.ALL_APPS
adb shell am start -a android.intent.action.ALL_APPS   # opens the panel or chooser
```
