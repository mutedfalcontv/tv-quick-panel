# Bug: the remote's record/voice button does nothing, and its mic is unusable

**Status:** reproduced (2026-10-31). The Assistant opens and records, but the
built-in mic delivers no audio and the remote has no audio channel at all.
Re-pairing the RC600 did not change that. Workarounds and the remaining options
are at the end.
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

## Regression note: "it used to work before an update"

Reported on 2026-10-31. Timestamps on the device:

| Item | Value |
| --- | --- |
| Firmware build | `PANASONIC/PANASONIC/R4_GTV:14/UKR1.260308/5597`, built 2026-06-03, security patch 2026-04-05 |
| `com.google.android.tv.remote.service` | updated 2026-10-17 (7.00.999235495) |
| `com.google.android.katniss` (Assistant) | updated **2026-10-27** (7.55.87) |
| `com.google.android.gms` | updated **2026-10-30** (26.42.31) |
| Assistant mic permission | `RECORD_AUDIO: granted=true`, appops `allow` |
| Headset (HFP) service | `mActiveDevice: null`, `mVoiceRecognitionStarted: false` - no Bluetooth mic in use |

So the two Google app updates land immediately before the regression window. The
mic permission and the Assistant's AudioRecord path are healthy; what is missing is
the remote's audio/voice channel (it is bonded as HOGP HID only).

Steps worth trying, cheapest first:

1. **Re-pair the RC600** (remove the remote in Settings > Remotes & accessories,
   then pair again). This is the fix TV vendors recommend when the mic button
   stops working after a TV/app update, and it forces the remote's voice service
   to be re-bound.
2. Force-stop the Assistant app and open it again. No data is lost.
3. Check the TV's physical mic mute / "Built-in MIC" switch, if this model has
   one, and the Assistant's microphone permission (already granted here).
4. If the remote still has no mic path, use the phone ATV remote app (confirmed
   working) or a USB microphone.

Rolling the Google app back is not possible without root; re-pairing is the only
non-destructive reset for the remote side.

## Confirmation after re-pairing (2026-10-31)

The RC600 was removed and paired again. Nothing changed:

* Profile policy is still `HID_HOST=100` with every audio profile unset;
  `HeadsetService.mActiveDevice` is `null` and `mVoiceRecognitionStarted=false`.
* The bridge reconnected on its own after the node was recreated (the stream ends
  when the remote unpairs; the worker reconnects in ~2 s after the auto-approval).
* The Assistant listens - `AudioRecord` routes to `AUDIO_DEVICE_IN_BUILTIN_MIC` -
  and then returns an empty result:

  ```
  #onRecognitionStateChanged: state[FINAL_RESULT] recognized_speech: ""
  LIBAS_INTERACTION_NO_SPEECH_DETECTED
  ```

* The mic is not muted in software:
  `mic mute FromSwitch=false FromRestrictions=false FromApi=false`.

So the built-in mic path is open but silent, and the remote has no capture
channel. The two Google app updates (katniss 2026-10-27, GMS 2026-10-30) remain
the likely trigger for the regression, but they cannot be rolled back without
root.

## Remaining options

1. **Phone ATV remote app** - confirmed working; it streams audio over Wi-Fi, so
   it does not depend on either microphone path.
2. **USB microphone** - the audio policy exposes a `usb_device_input` mix port,
   so a USB mic should become an input device the Assistant can use.
3. **Bluetooth headset** - `WH-CH720N` is already paired with HFP/A2DP; if the
   Assistant picks it up when it is the active device, that is another mic path.
4. **Clear the Assistant app data** (`com.google.android.katniss`) as a last
   resort; it resets Assistant setup and requires signing in again.
5. Check the TV body for a physical mic switch (some models have one) before
   concluding the built-in mic is dead.

## Open questions

* Is the record button expected to send `KEY_RECORD` (167), `KEY_VOICECOMMAND`
  (217) or a vendor code? The capture will settle it.
* Is the built-in mic working acoustically? `RecordActivityMonitor` shows katniss
  recording from it; if speech is not recognised, the next checks are the TV's
  mic mute switch/`mute` state and whether the transducer is present at all.
* Could the RC600's mic be exposed at all? Only if the remote advertises a vendor
  GATT voice service or HFP; the current bond shows HOGP HID only, so there is
  nothing for Android to connect to.
