# tv-quick-panel

Quick settings panel for the Realtek-based Android TV (Codename: Google TV / `com.realtek.tv`),
opened from the remote's otherwise-unused gear button.

## What it does

The RC600 remote's gear button emits scancode `501` (`KEY_BRL_DOT5` on the kernel side) and no
keylayout entry maps it, so stock Android does nothing with it. An accessibility service filters
that key and toggles this panel.

Tiles open the same targets the stock launcher / vendor settings use:

| Tile | Target |
| --- | --- |
| Inputs | `com.realtek.tv/.SelectInputActivity` |
| Display | `...PictureSetupActivity` (`detail_tag=detail_picture`) |
| Sound | `...PictureSetupActivity` (`detail_tag=detail_sound`) — translucent side panel, video keeps playing |
| Wi-Fi | `...connectivity.NetworkActivity` |
| Bluetooth | `android.settings.SLICE_SETTINGS` on `...activity.VendorSliceActivity` with `slice_uri=content://com.android.tv.settings.accessories.sliceprovider/general` |
| Accessibility | `...oemlink.AccessibilitySettingsActivity` |
| Gear | `.MainSettings` |

`VendorSliceActivity` is exported but gated on `android.permission.WRITE_SECURE_SETTINGS`
(protection level `signature|privileged|development|installer|role`), which must be granted once
over ADB.

## Build

Requirements: JDK 21, Android SDK build-tools 36, platform android-36.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
.\build.ps1
```

Pipeline: `aapt2` (link + compile) -> `javac` -> `d8` -> zip-inject -> `zipalign` -> `apksigner`
with the local debug keystore.

## Install

```sh
adb install -r tv-quick-panel.apk
adb shell pm grant com.ajink.tvquick android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  'com.spocky.projengmenu/.services.ProjectivyAccessibilityService:com.ajink.tvquick/.KeyService'
```

Keep any existing accessibility services in that list; setting the key replaces it.

## Notes

- `KeyService` only filters keys. It needs no window-content access.
- The Sound page is not a standalone activity: `PictureSetupActivity` hosts
  `OnePanelSliceFragment` per `detail_tag`, in a translucent window, which is why the
  background app keeps running behind it.
- Targets are device-specific (`com.realtek.tv`, TvSettingsVendor on this firmware).
