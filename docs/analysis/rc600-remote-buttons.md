# RC600 remote: button / scancode / keycode map

Reference for the remote that ships with this TV. Two layers are involved:

```
evdev scancode  ->  /vendor/usr/keylayout/RC600.kl  ->  Android keycode
(Linux key code)                                     (what apps and SystemUI see)
```

* Remote: **RC600** (Bluetooth), exposed as `/dev/input/event4`.
* The IR receiver (`/dev/input/event0`, "IR_rca") reports some of the same
  scancodes (including the gear's `0x01f5`), so a capture on `event4` only covers
  the Bluetooth remote.
* The keylayout file is SELinux-blocked for the shell user on a non-rooted build
  (`getenforce` = `Enforcing`, `ro.debuggable` = 0), so the mapping is observed
  rather than read. See "How to extend the table" below.

## Verified on this TV

Captured from the device unless noted. "Android keycode" is the value the
keylayout produces.

| Button | evdev scancode | Linux name | Android keycode | Notes |
| --- | --- | --- | --- | --- |
| Gear (the otherwise-dead button) | `501` / `0x01f5` | `KEY_BRL_DOT5` | `83` `KEYCODE_NOTIFICATION` | SystemUI claims it; `tv-quick-panel` reads the raw event over adbd and toggles the panel |
| Home | `172` / `0x00ac` | `KEY_HOME` | `3` `KEYCODE_HOME` | Returns to the launcher |
| Menu | `139` / `0x008b` | `KEY_MENU` | `82` `KEYCODE_MENU` | On the launcher it opens Settings (see `Keys.isSettingsShortcut`); the gear is excluded |
| OK / Select | `353` / `0x0161` | `KEY_SELECT` | `23` `KEYCODE_DPAD_CENTER` | Standard AOSP mapping for `KEY_SELECT` |
| Volume up | `114` / `0x0072` | `KEY_VOLUMEUP` | `24` `KEYCODE_VOLUME_UP` | Native ramp; the gear bridge never touches it |
| Volume down | `115` / `0x0073` | `KEY_VOLUMEDOWN` | `25` `KEYCODE_VOLUME_DOWN` | Same |

Evidence: raw capture at `2026-10-31 17:04` (`/data/local/tmp/buttons.bin`,
decoded with `tools/decode-remote-events.py`) and the accessibility-service key
log from the earlier investigation (`key code=83 scan=501`).

## Expected, not yet captured

These are the usual Linux/Android values for a Google-TV-style remote. They are
here as a checklist, **not** as verified facts - press each button with a capture
running and fill in the Android keycode:

| Button | Expected scancode | Expected Linux name | Expected Android keycode |
| --- | --- | --- | --- |
| D-pad up | `103` / `0x0067` | `KEY_UP` | `19` `KEYCODE_DPAD_UP` |
| D-pad down | `108` / `0x006c` | `KEY_DOWN` | `20` `KEYCODE_DPAD_DOWN` |
| D-pad left | `105` / `0x0069` | `KEY_LEFT` | `21` `KEYCODE_DPAD_LEFT` |
| D-pad right | `106` / `0x006a` | `KEY_RIGHT` | `22` `KEYCODE_DPAD_RIGHT` |
| Back | `158` / `0x009e` | `KEY_BACK` | `4` `KEYCODE_BACK` |
| Power | `116` / `0x0074` | `KEY_POWER` | `26` `KEYCODE_POWER` |
| Mute | `113` / `0x0071` | `KEY_MUTE` | `164` `KEYCODE_VOLUME_MUTE` |
| Mic / Assistant | `217` / `0x00d9` (often) | `KEY_VOICECOMMAND` | `231` `KEYCODE_VOICE_ASSIST` |
| App shortcut keys (Netflix, YouTube, ...) | vendor-specific | vendor-specific | often `0x02e6`+ range; observe |

## How to extend the table

1. Start a raw capture on the node (unbuffered; `getevent` is not usable because
   its stdout is block-buffered into a pipe):

   ```sh
   adb shell "rm -f /data/local/tmp/buttons.bin; cat /dev/input/event4 > /data/local/tmp/buttons.bin"
   ```

2. Press each button once with about a second between presses.
3. Pull and decode:

   ```sh
   adb pull /data/local/tmp/buttons.bin
   python tools/decode-remote-events.py buttons.bin
   ```

   The decoder prints one line per press (groups of `EV_KEY` downs separated by
   more than 0.6 s) with the scancode in hex and decimal. Add the Android
   keycode to the table by observing the system's reaction, or from the AOSP
   `Generic.kl` mapping for standard Linux codes.

Record layout (for a 32-bit process): `tv_sec` (4), `tv_usec` (4), `type` (u16),
`code` (u16), `value` (s32) - 16 bytes. A 64-bit process reads 24-byte records
because of the larger `timeval`.

## Full set of key codes the RC600 advertises

`adb shell getevent -p /dev/input/event4` reports this KEY capability set. Most
of them are never sent by the physical buttons; it is useful when a new key
appears in a capture and you want to know whether the device can report it:

```
0001 0002 0003 0004 0005 0006 0007 0008 0009 000a 000b 000c 001a 0034
004a 004e 0067 0069 006a 006c 006e 006f 0071 0072 0073 0074 0077 007e
0080 0082 0083 0085 0086 0087 0088 0089 008a 008b 008c 008e 0090 0096
0098 009b 009c 009e 009f 00a1 00a3 00a4 00a5 00a6 00a7 00a8 00a9 00ab
00ac 00ad 00ae 00b0 00b1 00b2 00b5 00b6 00ce 00cf 00d0 00d1 00d2 00d4
00d8 00d9 00db 00df 00e0 00e4 00e5 00e6 00e7 00e8 00e9 00ea 00eb 00f0
00f1 00fc 0100 0161 0166 016a 016d 016e 0172 0174 0176 0178 017a 017b
017c 017d 017f 0180 0182 0183 0184 0185 0188 0189 018d 018e 018f 0190
0191 0192 0193 0195 0197 0198 0199 019a 019c 01a0 01a1 01a2 01a3 01a4
01a5 01a6 01a9 01aa 01ab 01ac 01ad 01ae 01b0 01b1 01b7 01ba 01c7 01f5
01f6 0204 020a 020b 0218 0220 0221 0222 0225 0239 0240 0241 0242 0243
0244 0245 0246 0247 024c 0250 0251 02ab 02ac 02b1 02b3 02bd 02be 02c7
02c9 02d3 02d8 02e0 02e1 02e2 02e5 02e6 02f5 02f6 02f9 02fb 02fc 02fd
02fe 02ff
```

## Related

* `docs/analysis/adb-gear-bridge.md` - how the gear is read and what makes it
  different from the rest.
* `docs/bugs/volume-long-press.md` - why the gear must not be intercepted with an
  accessibility filter.
