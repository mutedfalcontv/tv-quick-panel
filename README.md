# tv-quick-panel

Quick settings panel for the Realtek-based Android TV (Codename: Google TV / `com.realtek.tv`),
opened from the remote's otherwise-unused gear button.

## What it does

The RC600 remote's gear button emits scancode `501` (`KEY_BRL_DOT5` on the kernel side), which
the TV's keylayout maps to `KEYCODE_NOTIFICATION`; SystemUI claims that key and does nothing
useful with it. `GearBridgeService` reads the raw event over the device's own adbd (`getevent`)
and toggles this panel, so the gear works in every app and nothing in the input path changes -
the native volume long-press ramp keeps working (see `docs/bugs/volume-long-press.md`).

Tiles open the same targets the stock launcher / vendor settings use:

| Tile | Target |
| --- | --- |
| Inputs | `com.realtek.tv/.SelectInputActivity` |
| Display | `...PictureSetupActivity` (`detail_tag=detail_picture`) |
| Sound | `...PictureSetupActivity` (`detail_tag=detail_sound`) — translucent side panel, video keeps playing |
| Wi-Fi | `...connectivity.NetworkActivity` |
| Bluetooth | `android.settings.SLICE_SETTINGS` on `...activity.VendorSliceActivity` with `slice_uri=content://com.android.tv.settings.accessories.sliceprovider/general` |
| Accessibility | `...oemlink.AccessibilitySettingsActivity` |
| Home | `com.ajink.tvglass/.HomeActivity`, falling back to the system home intent |
| Gear | `.MainSettings` |

`VendorSliceActivity` is exported but gated on `android.permission.WRITE_SECURE_SETTINGS`
(protection level `signature|privileged|development|installer|role`), which must be granted once
over ADB.

## Design

The panel matches `tv-glass-launcher`'s glass theme (mockups in `docs/mockups/`):
translucent white slab with a 1dp hairline, 16dp left corners, 10dp tiles with a
2dp white focus ring, light display clock, no accent colour. There is no live
blur - the fill plus the dim is the whole effect, which keeps the panel cheap on
the TV's GPU.

The Home button exists so the panel is not a one-way door: the launcher lists
this app under All apps, and this app hands the screen back to the launcher.

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
adb shell pm grant com.ajink.tvquick android.permission.POST_NOTIFICATIONS

# Gear bridge: reuse the key this PC already authorized (no debugging dialog)
adb shell mkdir -p /sdcard/Android/data/com.ajink.tvquick/files
adb push %USERPROFILE%\.android\adbkey     /sdcard/Android/data/com.ajink.tvquick/files/adbkey
adb push %USERPROFILE%\.android\adbkey.pub /sdcard/Android/data/com.ajink.tvquick/files/adbkey.pub

# Any accessibility key filter breaks volume long-press: leave none enabled
adb shell settings delete secure enabled_accessibility_services
```

Then open the panel once (`adb shell am start -n com.ajink.tvquick/.QuickPanelActivity`); it
starts the bridge, which survives reconnects and reboots on its own.

## Notes

- `GearBridgeService` is a foreground service holding a local adb connection to `127.0.0.1:5555`;
  ADB over network must stay enabled.
- There is no accessibility service. `adb shell dumpsys input | grep InputFilter` must read
  `false` - that is what keeps the volume long-press ramp native.
- The Sound page is not a standalone activity: `PictureSetupActivity` hosts
  `OnePanelSliceFragment` per `detail_tag`, in a translucent window, which is why the
  background app keeps running behind it.
- Targets are device-specific (`com.realtek.tv`, TvSettingsVendor on this firmware).
