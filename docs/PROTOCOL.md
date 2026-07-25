# BMAP protocol notes — Bose QuietComfort Ultra Earbuds (2nd gen)

Findings from building this app, captured against a real device:

- Product: QC Ultra Earbuds 2nd gen ("Crocchette"), firmware `8.4.8+gbb2cb60`
- Reference implementation: [aaronsb/bosectl](https://github.com/aaronsb/bosectl)
  (`NOTES.md`, `python/pybmap/`), which targets the QC Ultra **Headphones**

Everything below was verified on-device. Several items **correct mistakes in
pybmap** — those are called out explicitly, since they are easy to copy blindly.
Packet framing, operators and the general auth model are unchanged from bosectl's
notes and are not repeated here.

---

## 1. Connecting: use RFCOMM channel 2 directly, not the SDP UUID

The BMAP UUID `00000000-deca-fade-deca-deafdecacaff` **does not** resolve to the
BMAP control channel on Android. `createRfcommSocketToServiceRecord(BMAP_UUID)`
connects successfully — to the wrong channel. The first frame received is:

```
ff 55 02 ...
```

which is the periodic status beacon (bosectl's `NOTES.md` documents it on RFCOMM
channel 14). BMAP requests sent there are silently ignored: every GET times out
and the device looks dead.

The fix is to open channel 2 explicitly, via the hidden
`BluetoothDevice.createRfcommSocket(int)` (and its insecure variant as fallback):

```kotlin
val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
val socket = (m.invoke(device, 2) as BluetoothSocket).apply { connect() }
```

**Connects are flaky right after a socket closes.** The earbuds refuse the
channel for a second or two, failing with
`java.io.IOException: read failed, socket might closed or timeout, read ret: -1`.
Retrying the whole attempt list ~3 times with ~1.2 s gaps makes it reliable.

Timing from bosectl holds: 200 ms after send before reading, and for multi-frame
replies keep reading until ~500 ms of silence.

## 2. Battery `[2.2]` — repeating per-component groups

Single-battery devices report one 4-byte group, so bosectl's "byte 0 is the
percentage" works there but loses the earbuds' per-bud values. The real layout is
a repeating group:

```
[level, ff, ff, componentId] × N
```

| componentId | Meaning |
|---|---|
| 0 | Single/only battery |
| 1 | Left earbud |
| 2 | Right earbud |
| 3 | Charging case |
| 4 | Overall |

Real capture — L 100 %, R 100 %, overall 100 %, case 50 %:

```
64ffff01 64ffff02 64ffff04 32ffff03
```

Headphones capture for comparison (80 %, no aux cells): `50ffff00`.
`0xff` marks an absent cell.

## 3. Buttons `[1.9]` — one frame per button, big-endian mask

Two corrections to pybmap here.

**(a) A GET returns one STATUS frame per configurable button.** Reading only the
first response silently hides the second. The earbuds answer with two frames; you
must drain the reply:

```
03 09 13 000b4002   right shortcut (id 3), touch-and-hold, action 0x13 = 19
04 09 11 000b4002   left  shortcut (id 4), touch-and-hold, action 0x11 = 17
```

| Button id | Meaning |
|---|---|
| 3 | Right shortcut (earbuds) |
| 4 | Left shortcut (earbuds) |
| 0x80 | Single shortcut (headphones) |

Event `9` is `long_press` — the official app calls it "touch and hold".
A GET payload (button id, or id+event) does **not** filter the response; the
device always returns all buttons.

**(b) The supported-actions mask is a big-endian u32**, bytes `[3..6]`, where
bit *N* means "action *N* is selectable". pybmap instead walks the bytes
low-index-first and computes `byteIndex * 8 + bit`, which produces garbage.

For mask `000b4002`, the correct decoding is:

| Bit | Action | Official app label |
|---|---|---|
| 1 | VPA | Accedi all'assistente vocale |
| 14 | Disabled | (the per-side on/off toggle) |
| 16 | SpotifyGo | Spotify |
| 17 | ModesCarousel | Passa in rassegna le modalità |
| 19 | SpatialAudioMode | Cambia l'audio immersivo |

This matches the official app's option list exactly, which is what confirms the
interpretation. pybmap's decoding yields `{8, 9, 11, 22, 25}` — actions the app
never offers.

Writing is unchanged: `SETGET [1.9]` with `[buttonId, event, action]`. Setting
action 14 (Disabled) is how the app's per-side toggle switches a shortcut off.

## 4. Multipoint `[1.10]` — bit 0, and writes must preserve the other bits

pybmap reads bit `0x02`. That bit is a constant capability flag, so multipoint
always reads as enabled. The state is **bit 0**:

| Value | State |
|---|---|
| `07` | Multipoint on |
| `06` | Multipoint off |

Writing a bare `[0]` / `[1]` does nothing. The write is a read-modify-write of
the whole byte, preserving bits 1-2:

```kotlin
val value = (current and 0xFE) or (if (enabled) 1 else 0)   // SETGET [1.10]
```

Verified round-trip: toggling from this app is reflected in the official app.

## 5. Undocumented settings in block 1

Found by diffing `GetAll [1.1] START` (drained) before and after flipping a
setting in the official app — a reliable way to locate any unknown register.

| Address | Setting | Values |
|---|---|---|
| `[1.29]` | Auto transparency (pass-through while only one bud is worn) | `01` on / `00` off |
| `[1.34]` | Touch controls master enable | `01` on / `00` off |

`[1.34]` is independent of the per-button actions in `[1.9]`: disabling touch
controls leaves both shortcut assignments intact, mirroring the official app's
separate toggle + radio list.

Still unidentified, both plain booleans: `[1.20]` (`01`) and `[1.30]` (`00`).

Full settings enumeration returned by `GetAll [1.1]` on this firmware:
`[1.0] [1.2] [1.3] [1.5] [1.7] [1.9]×2 [1.10] [1.11] [1.12] [1.20] [1.24]
[1.27] [1.29] [1.30] [1.34]`.

## 6. Favorites `[31.8]` — the "star" bitmask

Modes starred in the official app (reachable by headset gesture) are a bitmask:

```
[slotCount, maskHi, maskLo]      16-bit big-endian, bit N = mode N is starred
```

Real capture `0b 01 83` → 11 slots, starred = `{0, 1, 7, 8}`.

The same information appears as the **third flag byte** of each ModeConfig
`[31.6]` STATUS frame (offset 5), which bosectl leaves undocumented as `?`.
Writing the mask back with `SETGET [31.8]` works.

## 7. Wind block is not settable

`SETGET [31.10]` with a changed wind-block byte is rejected:

```
[31.10] op=4: 08      Runtime error
```

Writing it as part of a profile via `[31.6]` is accepted but has no effect — the
value never changes. Wind block appears to be unsupported on this hardware
(it is absent from the official app's UI for these earbuds too).

Note the related quirk from bosectl that does hold: `autoCNC = 1` is rejected
with the same Runtime error, so always write `0`.

## 8. There is no BMAP volume register

The earbuds use **AVRCP absolute volume**, so the Android media-stream volume
*is* the earbud volume (`dumpsys media_session` reports
`controlType=ABSOLUTE`). Applications should drive `AudioManager`
`STREAM_MUSIC` rather than look for a BMAP register.

Ruled out experimentally: with music playing, the phone volume was driven
11 → 2 → 11 while every candidate register was polled.

| Address | Value | Verdict |
|---|---|---|
| `[5.3]` | `1f` | Constant — supported-controls mask |
| `[5.4]` | `0100xx` | ~1 Hz counter (playback clock); errors when idle |
| `[5.5]` | `1f0e` | Never changed with volume; errors when idle |
| `[5.7]`, `[5.13]` | drifting | Latency/codec values, change on their own |

`[5.2]` and `[5.6]` are auth-gated (error 5). `GetAll [5.1] START` is also
auth-gated, though plain `GET [5.1]` works and returns the active source.

## 9. Current mode `[31.3]` can be `0xff`

`0xff` means "no mode active" (observed with the buds in the case). Treat it as
"unknown" rather than mapping it to a slot index.

---

## Reproducing: the probe build

The app carries an on-device protocol probe in a dedicated `probe` product
flavor, so it is compiled into **neither** standard debug nor release builds
(`probeRelease` does not exist as a variant).

```bash
./gradlew installProbeDebug        # standard builds never contain the probe
adb shell am start -n eu.depau.bosectl/.ui.MainActivity   # let it connect

# full sweep: GetAll on blocks 1/5/7, buttons, settings, block 5+7 GET scan
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl

# settings GetAll only — the fast path for before/after diffing
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl \
    --es mode settings --es label BEFORE

adb logcat -d -s BmapProbe:I
```

The diff workflow that found `[1.29]` and `[1.34]`:

1. Probe with `--es mode settings --es label BEFORE`
2. Flip exactly one setting in the official Bose app
3. Probe again with `--es label AFTER`
4. Compare — the register that changed is the one you want

All probe requests are GETs. Note that reads are unauthenticated, but a probe
still competes for the control channel: restarting the app drops and re-opens
the RFCOMM socket, which can briefly disturb an active session.
