# Bug: pressing HOME reboots the TV

**Status:** observed once (2026-10-31), not yet attributed or reproduced on purpose
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Area:** system / HOME key handling
**Related:** `docs/analysis/adb-gear-bridge.md`

## Symptom

Pressing HOME on the remote while the gear bridge was running did not return to
the launcher: the TV dropped off the network and rebooted. ADB disappeared
mid-command (`device offline`), and the device came back with a fresh uptime.

## What was measured

| Check | Result |
| --- | --- |
| `adb shell uptime` before | `up 4:05` (15:42 local) |
| after reconnect | `up 1 min` (16:52 local) |
| `getprop ro.boot.bootreason` | `reboot` (generic, no `watchdog` / `kernel_panic` marker) |
| `getprop sys.boot.reason` | `reboot` |
| `dumpsys dropbox` after boot | `Drop box contents: 0 entries` - this firmware records nothing |
| `ls /proc/last_kmsg` | no such file |
| `ls /sys/fs/pstore`, `/data/system/dropbox` | permission denied for the shell user |
| logcat ring buffers | cleared by the reboot |

So the usual post-mortem sources (dropbox, pstore, last_kmsg) are unavailable
without root, and the pre-reboot log was lost. The only hard facts are the
reboot itself and the uptime reset.

Context at the time: the gear bridge had been connected for a while (foreground
service holding a `cat /dev/input/event4` stream over adbd), and several
`adb logcat` / `getevent` captures were running from the PC. The post-boot load
average was `37.59` (2 GB RAM device, still settling).

## Hypotheses (unproven)

1. **Firmware/vendor HOME handling.** The reboot may be a pre-existing vendor
   bug in the HOME-key handoff (SystemUI / the TV's home intent), unrelated to
   either app.
2. **Resource pressure.** The load average was extremely high around the reboot;
   a watchdog can reboot the device when the system stalls. The HOME press may
   have been coincidental.
3. **The gear bridge.** Unlikely: it only reads an evdev node and, on a gear
   press, runs `am start` for the panel. It never sends HOME and never touches
   the launcher.

## How to capture it next time

Over USB (so logging survives the network drop) or with the network log
streamed to the PC:

```sh
adb logcat -v time > home-reboot.log        # start before pressing HOME
adb shell input keyevent KEYCODE_HOME       # or press HOME on the remote
```

After the device returns:

```sh
adb shell uptime
adb shell getprop ro.boot.bootreason
adb shell dumpsys dropbox --print
```

To rule the bridge in or out, stop it first and repeat:

```sh
adb shell am force-stop com.ajink.tvquick
adb shell am force-stop com.ajink.tvglass
adb shell input keyevent KEYCODE_HOME
```

If the reboot needs a kernel trace, the only route on a non-rooted build is a
serial/UART console or enabling `pstore` on a rooted build.

## Acceptance

Pressing HOME from any app returns to the launcher, every time, with the gear
bridge running.
