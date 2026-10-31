#!/usr/bin/env python3
"""Decode a raw /dev/input/eventN capture into one line per button press.

Usage:
    adb shell "cat /dev/input/event4 > /data/local/tmp/buttons.bin"
    adb pull /data/local/tmp/buttons.bin
    python tools/decode-remote-events.py buttons.bin

The capture is a sequence of `struct input_event` records. The process ABI
decides the record size: 16 bytes on 32-bit (two 4-byte timeval fields) and 24
bytes on 64-bit (two 8-byte fields). Pass --record-size to override.

Presses are grouped by EV_KEY down events separated by more than --gap seconds.
"""

import argparse
import struct
import sys

# Linux input-event-codes names for the keys this remote is known to send.
KEY_NAMES = {
    1: "KEY_ESC",
    2: "KEY_1", 3: "KEY_2", 4: "KEY_3", 5: "KEY_4", 6: "KEY_5",
    7: "KEY_6", 8: "KEY_7", 9: "KEY_8", 10: "KEY_9", 11: "KEY_0",
    28: "KEY_ENTER",
    103: "KEY_UP", 105: "KEY_LEFT", 106: "KEY_RIGHT", 108: "KEY_DOWN",
    113: "KEY_MUTE", 114: "KEY_VOLUMEUP", 115: "KEY_VOLUMEDOWN",
    116: "KEY_POWER", 119: "KEY_PAUSE", 128: "KEY_STOP",
    139: "KEY_MENU",
    158: "KEY_BACK", 163: "KEY_NEXTSONG", 164: "KEY_PLAYPAUSE",
    165: "KEY_PREVIOUSSONG", 166: "KEY_STOPCD", 167: "KEY_RECORD",
    168: "KEY_REWIND", 172: "KEY_HOME", 207: "KEY_PLAY",
    208: "KEY_FASTFORWARD", 212: "KEY_CAMERA", 217: "KEY_VOICECOMMAND",
    352: "KEY_OK", 353: "KEY_SELECT", 356: "KEY_BACK",
    358: "KEY_INFO", 362: "KEY_PROGRAM", 364: "KEY_FAVORITES",
    365: "KEY_EPG", 367: "KEY_CONFIG", 368: "KEY_HOMEPAGE",
    372: "KEY_SUBTITLE", 373: "KEY_AUDIO", 377: "KEY_TV",
    386: "KEY_RADIO", 387: "KEY_TV", 389: "KEY_AUDIO",
    391: "KEY_RED", 392: "KEY_GREEN", 393: "KEY_YELLOW", 394: "KEY_BLUE",
    402: "KEY_CHANNELUP", 403: "KEY_CHANNELDOWN",
    407: "KEY_NEXT", 412: "KEY_PREVIOUS", 501: "KEY_BRL_DOT5",
}


def android_keycode(code):
    """Best-effort Android keycode for standard Linux codes (AOSP Generic.kl)."""
    standard = {
        103: 19, 108: 20, 105: 21, 106: 22, 353: 23,
        114: 24, 115: 25, 116: 26, 113: 164,
        158: 4, 172: 3, 139: 82, 217: 231,
        # Vendor keylayout on this TV: the gear is KEY_BRL_DOT5 -> NOTIFICATION.
        501: 83,
    }
    return standard.get(code)


def decode(path, record_size, gap):
    data = open(path, "rb").read()
    if record_size not in (16, 24):
        sys.exit("record size must be 16 or 24")
    if len(data) % record_size:
        print("warning: %d trailing bytes ignored" % (len(data) % record_size),
              file=sys.stderr)

    time_offset = 16 if record_size == 24 else 8
    presses = []
    previous = None
    for offset in range(0, len(data) - record_size + 1, record_size):
        sec, usec = struct.unpack_from("<II", data, offset)
        ev_type, code, value = struct.unpack_from("<HHi", data, offset + time_offset)
        if ev_type != 1 or value != 1:
            continue
        stamp = sec + usec / 1_000_000.0
        if previous is None or stamp - previous > gap:
            presses.append((stamp, []))
        presses[-1][1].append(code)
        previous = stamp

    if not presses:
        print("no EV_KEY down events found")
        return

    base = presses[0][0]
    for index, (stamp, codes) in enumerate(presses, 1):
        parts = []
        for code in codes:
            name = KEY_NAMES.get(code, "?")
            keycode = android_keycode(code)
            keycode_text = " -> Android %d" % keycode if keycode else ""
            parts.append("0x%04x (%d) %s%s" % (code, code, name, keycode_text))
        print("press %2d  t=%7.3fs  %s" % (index, stamp - base, "; ".join(parts)))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("capture", help="raw bytes pulled from /dev/input/eventN")
    parser.add_argument("--record-size", type=int, choices=(16, 24), default=16,
                        help="struct input_event size (default 16, the 32-bit layout)")
    parser.add_argument("--gap", type=float, default=0.6,
                        help="seconds between presses (default 0.6)")
    args = parser.parse_args()
    decode(args.capture, args.record_size, args.gap)


if __name__ == "__main__":
    main()
