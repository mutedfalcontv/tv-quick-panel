# Bug: the remote's record/voice button does nothing, and its mic is unusable

**Status:** mic limitation confirmed; the record key is now mapped to the Assistant
in the bridge (2026-10-31), waiting on a physical press to confirm the scancode
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
| RC600 Bluetooth profile policy | `HID_HOST=100`; `A2DP`, `HEADSET`, `LE_AUDIO`, `HEADSET_CLIENT` all `-1`; the device is bonded as `[ LE ]` (HOGP HID), so it has no audio path |
| TV audio inputs | `Built-In Mic` (`AUDIO_DEVICE_IN_BUILTIN_MIC`, `@:bottom`) exists in `dumpsys media.audio_policy` |
| Assistant recording | active `VOICE_RECOGNITION` client, `AudioRecord` routed to `AUDIO_DEVICE_IN_BUILTIN_MIC`, 2ch 16 kHz, pack `com.google.android.katniss`; `mScoAudioState=INACTIVE` (no Bluetooth mic involved) |
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

1. **The remote's mic cannot work on this stack.** The RC600 is bonded as a BLE
   HOGP HID device (`[ LE ]`, `HID_HOST=100`, every audio profile unset). Android
   TV remotes with a mic normally stream audio through a vendor GATT voice
   channel or HFP; neither is connected here, and no input device exists for the
   remote. The phone remote app works because
   `com.google.android.tv.remote.service` carries audio from the phone over Wi-Fi
   - it does not use the remote's microphone either.
   The TV's own bottom mic *is* used by the Assistant (measured while it was
   listening), so voice input from the TV body is the available path; only the
   physical remote's microphone is out of reach.
2. **The record button is not wired to the Assistant.** Whatever keycode the
   button sends, nothing on the system treats it as the assist action. The
   Assistant itself is fine: it starts and listens when its searchbar activity is
   launched directly.

So the fixable half is the button; the mic half is a hardware/stack limitation.
The TV's own bottom mic is present and is the audio source the Assistant should
use if the button is mapped.

## Research (2026-10-31)

The internet has no RC600-specific notes (the name collides with a Marantz audio
remote), but the general patterns are well established:

* **Raw evdev remapping without root** is exactly what this project already does:
  read `struct input_event` from `/dev/input/eventN` as the shell user and run
  `am start`. `Caldis/tv-keyremap` ships the same mechanism in shell, including
  the 16-byte (32-bit) vs 24-byte (64-bit) record size - the same layout used by
  `GearBridgeService`.
* **OEM remotes do use odd key codes for non-media buttons.** In
  `Gathin23/cleanyourtv` PR #244, a Xiaomi remote's Input button is
  `KEY_RECORD`/`KEYCODE_MEDIA_RECORD`, and nothing handles it once the stock
  launcher is disabled. A commenter confirms the same family as this TV
  (Kalley R10G, "MOKA/Realtek RTD2875P, Android 14, SDK 34") with a different
  button reported as `KEYCODE_TV_INPUT` (178). The fix in both cases is simply
  to handle the code in an app-level listener.
* **The consumer-key path is not spoofable**, so remapping must happen at the raw
  input stage (or with root, by editing `/vendor/usr/keylayout`). The
  `UberStone/google-tv-button-remap` skill documents the accessibility variant
  and its trap: `adb shell input keyevent` never reaches the filter, only a
  physical press does. This project avoids the accessibility filter entirely
  because it breaks the volume ramp (see `volume-long-press.md`).
* **The Assistant itself can use the TV's built-in microphone.** Sony's docs for
  Google TV describe the built-in-mic switch and hands-free voice assisted by the
  TV body mic; the phone remote apps work by streaming audio over the network,
  which is why they are unaffected by the remote's missing audio profile.

## Fix implemented (record key -> Assistant)

`GearBridgeService` now handles `KEY_RECORD` (167) and `KEY_VOICECOMMAND` (217)
from the same evdev stream and runs:

```sh
am start -n com.google.android.katniss/com.google.android.apps.tvsearch.app.launch.trampoline.SearchActivityTrampoline
```

That is the activity that was verified to start the Assistant listening
(`SEARCHBAR_STATE_LISTENING`, `LIBAS_S3_CONNECTION_UP`). One of the two codes is
the physical record key; the other is advertised by the device but may never be
sent. A physical press will confirm which one it is, and the capture below shows
it:

```sh
adb shell "rm -f /data/local/tmp/record.bin; cat /dev/input/event4 > /data/local/tmp/record.bin"
# press the record button once
adb pull /data/local/tmp/record.bin
python tools/decode-remote-events.py record.bin
adb logcat -s TvGearBridge:I     # expect: assist key 167 (or 217)
```

Still to verify by speaking: whether the built-in mic actually picks up sound
(the AudioRecord path is confirmed, the transducer is not).

## Open questions

* Is the record button expected to send `KEY_RECORD` (167), `KEY_VOICECOMMAND`
  (217) or a vendor code? The capture will settle it.
* Is the built-in mic working acoustically? `RecordActivityMonitor` shows katniss
  recording from it; if speech is not recognised, the next checks are the TV's
  mic mute switch/`mute` state and whether the transducer is present at all.
* Could the RC600's mic be exposed at all? Only if the remote advertises a vendor
  GATT voice service or HFP; the current bond shows HOGP HID only, so there is
  nothing for Android to connect to.
