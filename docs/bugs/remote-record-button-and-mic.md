# Bug: the remote's record/voice button does nothing, and its mic is unusable

**Status:** input side confirmed (2026-10-31); record-button scancode still to capture
**Device:** MOKA R4G, Android 14 (SDK 34), RC600 remote
**Area:** remote input / Google Assistant / audio input
**Related:** `docs/analysis/rc600-remote-buttons.md`

## Symptom

* Pressing the record (voice) button on the RC600 does not open the Assistant or
  start listening.
* The microphone on the remote does not work at all.
* The phone-side **ATV remote app** does work for voice, which is what a previous
  investigation meant by "it uses the TV mic".

## What was measured

| Check | Result |
| --- | --- |
| RC600 Bluetooth profiles | `HID_HOST=100`; `A2DP`, `HEADSET`, `LE_AUDIO` all `-1` - the remote is paired as a **HID device only**, so it has no audio path |
| TV audio inputs | `Built-In Mic` (`AUDIO_DEVICE_IN_BUILTIN_MIC`, `@:bottom`) exists in `dumpsys media.audio_policy` |
| Assistant package | `com.google.android.katniss` 7.55.87, `enabled=1` |
| `voice_interaction_service` | `com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService` |
| `android.intent.action.ASSIST` | resolves to `com.google.android.katniss/...launch.trampoline.SearchActivityTrampoline` |
| `adb shell input keyevent 219` (`KEYCODE_ASSIST`) | launcher stays on top; no Assistant |
| `am start -a android.intent.action.VOICE_COMMAND` | no resolver |
| Launching the trampoline directly | **Assistant starts and listens**: `SEARCHBAR_STATE_LISTENING`, `LIBAS_S3_CONNECTION_UP` in `katniss_interactor_*` |
| ATV remote app | `com.google.android.tv.remote.service` installed; it streams audio from the phone over the network, which is why voice works there |

The record button's evdev scancode has not been captured yet (a capture is
running; see below).

## Interpretation

Two independent problems:

1. **The remote's mic cannot work on this stack.** The RC600 is connected as a
   HID keyboard-ish device only. Android TV remotes with a mic normally stream
   audio through a proprietary/HOGP voice channel or HFP; neither is enabled
   here, and the audio policy has no input device for it. The phone remote app
   works because `com.google.android.tv.remote.service` carries audio from the
   phone over Wi-Fi - it does not use the remote's microphone either.
2. **The record button is not wired to the Assistant.** Whatever keycode the
   button sends, nothing on the system treats it as the assist action. The
   Assistant itself is fine: it starts and listens when its searchbar activity is
   launched directly.

So the fixable half is the button; the mic half is a hardware/stack limitation.
The TV's own bottom mic is present and is the audio source the Assistant should
use if the button is mapped.

## Possible fix (button only)

The gear bridge already reads raw scancodes, so the record key can be mapped the
same way the gear is:

```sh
am start -n com.google.android.katniss/com.google.android.apps.tvsearch.app.launch.trampoline.SearchActivityTrampoline
```

Before doing that, capture the record button's scancode:

```sh
adb shell "rm -f /data/local/tmp/record.bin; cat /dev/input/event4 > /data/local/tmp/record.bin"
# press the record button once
adb pull /data/local/tmp/record.bin
python tools/decode-remote-events.py record.bin
```

If the Assistant then listens, the on-screen volume/echo behaviour with the TV's
built-in mic still needs a real speaking test.

## Open questions

* Does the Assistant use the built-in mic when launched this way? Check
  `dumpsys media.audio_policy` / `dumpsys audio` while it is listening, or simply
  speak into the TV.
* Is the record button expected to send `KEY_RECORD` (167), `KEY_VOICECOMMAND`
  (217) or a vendor code? The capture will settle it.
* Does the phone app's voice path also work through the TV's built-in mic, or is
  the audio entirely from the phone? (Almost certainly the latter.)
