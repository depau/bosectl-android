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

| componentId | Meaning             |
|-------------|---------------------|
| 0           | Single/only battery |
| 1           | Left earbud         |
| 2           | Right earbud        |
| 3           | Charging case       |
| 4           | Overall             |

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

| Button id | Meaning                      |
|-----------|------------------------------|
| 3         | Right shortcut (earbuds)     |
| 4         | Left shortcut (earbuds)      |
| 0x80      | Single shortcut (headphones) |

Event `9` is `long_press` — the official app calls it "touch and hold".
A GET payload (button id, or id+event) does **not** filter the response; the
device always returns all buttons.

**(b) The supported-actions mask is a big-endian u32**, bytes `[3..6]`, where
bit *N* means "action *N* is selectable". pybmap instead walks the bytes
low-index-first and computes `byteIndex * 8 + bit`, which produces garbage.

For mask `000b4002`, the correct decoding is:

| Bit | Action           | Official app label            |
|-----|------------------|-------------------------------|
| 1   | VPA              | Accedi all'assistente vocale  |
| 14  | Disabled         | (the per-side on/off toggle)  |
| 16  | SpotifyGo        | Spotify                       |
| 17  | ModesCarousel    | Passa in rassegna le modalità |
| 19  | SpatialAudioMode | Cambia l'audio immersivo      |

This matches the official app's option list exactly, which is what confirms the
interpretation. pybmap's decoding yields `{8, 9, 11, 22, 25}` — actions the app
never offers.

Writing is unchanged: `SETGET [1.9]` with `[buttonId, event, action]`. Setting
action 14 (Disabled) is how the app's per-side toggle switches a shortcut off.

## 4. Multipoint `[1.10]` — bit 0, and writes must preserve the other bits

pybmap reads bit `0x02`. That bit is a constant capability flag, so multipoint
always reads as enabled. The state is **bit 0**:

| Value | State          |
|-------|----------------|
| `07`  | Multipoint on  |
| `06`  | Multipoint off |

Writing a bare `[0]` / `[1]` does nothing. The write is a read-modify-write of
the whole byte, preserving bits 1-2:

```kotlin
val value = (current and 0xFE) or (if (enabled) 1 else 0)   // SETGET [1.10]
```

Verified round-trip: toggling from this app is reflected in the official app.

## 5. Undocumented settings in block 1

Found by diffing `GetAll [1.1] START` (drained) before and after flipping a
setting in the official app — a reliable way to locate any unknown register.

| Address  | Setting                                                           | Values             |
|----------|-------------------------------------------------------------------|--------------------|
| `[1.20]` | Auto-off on motion inactivity (`SettingsMotionInactivityAutoOff`) | `01` on / `00` off |
| `[1.29]` | Auto transparency (pass-through while only one bud is worn)       | `01` on / `00` off |
| `[1.30]` | `SettingsSourceBargeIn` — reads `00`, refuses writes, see §14     | —                  |
| `[1.34]` | Touch controls master enable                                      | `01` on / `00` off |

`[1.20]` and `[1.30]` were named from the official app's `BmapFunction` enum,
which lists every Settings id and also confirms `[1.29]` = `SettingsAutoAwareMode`
and `[1.34]` = `SettingsDisableCaptouch`. The enum is the cheapest way to name an
unknown register: `com.bose.bmap.messages.enums.spec.BmapFunction`. Only `[1.20]`
has a verified value here; `[1.30]`'s meaning is inferred from its name alone.

`[1.34]` is independent of the per-button actions in `[1.9]`: disabling touch
controls leaves both shortcut assignments intact, mirroring the official app's
separate toggle + radio list.

No unidentified registers remain in this enumeration.

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

| Address           | Value    | Verdict                                          |
|-------------------|----------|--------------------------------------------------|
| `[5.3]`           | `1f`     | Constant — supported-controls mask               |
| `[5.4]`           | `0100xx` | ~1 Hz counter (playback clock); errors when idle |
| `[5.5]`           | `1f0e`   | Never changed with volume; errors when idle      |
| `[5.7]`, `[5.13]` | drifting | Latency/codec values, change on their own        |

`[5.2]` and `[5.6]` answer a GET with error 5, but that is the wrong operator,
not auth: **`GetAll [5.2] START` works** and drains `[5.0] [5.1] [5.3] [5.4]
[5.5] [5.7] [5.13] [5.17]` in one burst. `GET [5.1]` works too and returns the
active source (§14).

`ponytail:` `[5.5]` read `1f0e` while the phone was the active source and `1f0a`
while the laptop was — i.e. it *does* move, just not with the volume of the
phone that the ruling-out experiment drove. Per-source volume is a plausible
reading and would overturn the paragraph above; one observation is not enough to
act on. Re-test by driving the volume of whichever device `[5.1]` currently
names.

## 9. Current mode `[31.3]` can be `0xff`

`0xff` means "no mode active" (observed with the buds in the case). Treat it as
"unknown" rather than mapping it to a slot index.

## 10. Notifications: the device is silent until you subscribe

The earbuds send **no** unsolicited frames by default. Change the mode with the
on-bud shortcuts and a connected client sees nothing — verified twice with the
socket demonstrably alive (GETs before and after showed the mode had changed).

The official app subscribes first, via `NotificationByFblock`:

```
[9.2] SETGET  [bitmask][function-block bitset]
```

| Byte | Meaning                                                              |
|------|----------------------------------------------------------------------|
| 0    | `NotificationBitmask`: 0 = Overwrite, 1 = Enable, 2 = Disable        |
| 1-4  | Function-block bitset, big-endian, **bit index = function block id** |

Bit indexing is by *block id*, not enum ordinal — `FunctionBlocksBitSet.setBit`
uses `getFunctionBlockId()`, so AudioModes (31) is the top bit of a 4-byte mask.
`BitSetUtil` writes bit *i* to byte `len-(i/8)-1`, position `i%8`, which is just
a big-endian integer where bit *i* has value 2^i. (The sibling
`EnumeratedBitSet` used by the per-function variant indexes by `ordinal-1`
instead — do not confuse them.)

Subscribing to Settings, Status, AudioManagement and AudioModes:

```
TX 0902 02 05  01 80000026
RX [9.2] op=3: 80000026        # echoes the active mask
```

After that, on-bud changes arrive immediately:

```
PUSH [31.3]  op=3: 07            # current mode
PUSH [31.10] op=3: 0000020001    # head tracking -> Motion
```

The Notification block functions:

| Address | Name                                                             |
|---------|------------------------------------------------------------------|
| `[9.0]` | FblockInfo                                                       |
| `[9.1]` | **NotificationReset** — clears subscriptions; not a GetAll       |
| `[9.2]` | NotificationByFblock                                             |
| `[9.3]` | NotificationByFunction — `[bitmask][fblock id][function bitset]` |
| `[9.4]` | NotificationPeriodic                                             |

A plain `GET [9.2]` returns the currently subscribed mask (`00000000` when
nothing is subscribed), which makes it easy to confirm registration took.

### Pushes race your own writes

Once subscribed, a write is answered by *both* a push and an ack, and the push
comes first — the value changed before the device got around to acknowledging it:

```
TX 1f0305020100          # [31.3] START, switch to mode 1
RX [31.3]  op=3: 01      # push: the mode changed
RX [31.10] op=3: 0a01000001   # push: audio settings that came with it
RX [31.3]  op=6: 01      # RESULT — the actual ack
```

Treating the first frame after a send as the reply therefore breaks in two ways:
a START looks like it failed (STATUS instead of RESULT), and any request can be
answered by a push for a completely different address — a `[31.3]` push landing
during a battery GET parses as battery data. Match replies on address, and treat
STATUS as a push when the request was a START (`answersRequest` in
`Protocol.kt`). Frames that don't match are pushes: forward them to the
unsolicited stream instead of dropping them.

---

## 11. Spotify Tap: Shortcut presses are announced outside BMAP, on RFCOMM channel 24

**How "Spotify Tap" actually works: the earbuds never launch anything.** Spotify
opens an RFCOMM socket to a service *on the earbuds*, and a shortcut long-press
pushes one frame down it. The official Bose app has no runtime part in this — its
only role is writing `[1.9]`, and its "Spotify" option is gated purely on
`isPackageInstalled("com.spotify.music")`, which the firmware knows nothing about.

| SDP              | Value                                               |
|------------------|-----------------------------------------------------|
| Service UUID     | `9b26d8c0-a8ed-440b-95b0-c4714a518bcc`              |
| SDP service name | `SRfcomm`                                           |
| RFCOMM channel   | 24 on this unit — read it from SDP, do not hardcode |

The press frame is 82 bytes, NUL-separated strings after a 2-byte header:

```
01 50 | "<32 hex chars>" 00 | "Bose QuietComfort Ultra Earbuds (2nd Gen)" 00 | "Bose" 00
 ^  ^--- 0x50 = 80 bytes follow
 opcode 01
```

The hex string is a stable per-device id (redacted here — it is a persistent
device identifier, don't paste captures of it into public issues). Verified at
RFCOMM level in `btmon`: UIH frame, address `0xc7` → DLCI 49 → server channel 24.

- **Any enabled shortcut notifies, not just Spotify.** With `[1.9]` read back as
  RIGHT `action=16 SpotifyGo` and LEFT `action=17 ModesCarousel`, long-pressing
  the *left* bud emits the identical frame; the configured action still runs
  locally. The payload carries no button id, so a listener cannot tell the buds
  apart. Setting a button to `action=14` (Disabled) does stop the notifications —
  disabled means disabled.
- **It retries.** An unacknowledged frame is re-sent ~3.6 s later, so presses a
  few seconds apart look like pairs in a passive capture. Don't count frames as
  presses.
- Nothing else carries the event: an 11 MB HCI capture across many presses
  contains no AVRCP passthrough and exactly one AT command (`AT+BIEV=2,80`, an
  HFP battery report).

`ponytail:` the frame's semantics beyond "a shortcut was long-pressed" are
unknown — opcode `01` is the only one seen, and nothing was ever sent *to* the
service. That is enough for an app that just wants a programmable button: connect,
wait for bytes, act.

---

## 12. The other channels the earbuds expose

Read from BlueZ's SDP cache (`/var/lib/bluetooth/<adapter>/cache/<device>`, root),
which stores each record as raw hex — no `sdptool` needed:

| Service                                          | Transport                          |
|--------------------------------------------------|------------------------------------|
| `9b26d8c0-…` (`SRfcomm`, shortcut presses — §11) | RFCOMM ch 24                       |
| `df21fe2c-…` (Google Fast Pair message stream)   | RFCOMM ch 22                       |
| `00000000-deca-fade-…` (BMAP)                    | RFCOMM ch 14                       |
| SPP (`0x1101`), six records                      | RFCOMM ch 1 and ch 2               |
| `eb04 / eb06 / eb07-d102-11e1-…` (Bose)          | L2CAP PSM 0xFEFF / 0xFEFD / 0xFEFB |
| `88b0ee3c-… / 346d5c4b-… / 77265d41-…`           | L2CAP PSM 0xFCFF / 0xFCFD / 0xFCFB |

Channel numbers are per-device; resolve them from SDP rather than hardcoding.

**The BMAP UUID advertises channel 14** — the status beacon from §1. More evidence
that record is useless for BMAP. From Linux, BMAP answered on **channel 1** while
channel 2 was refused; from Android, channel 2 works. `ponytail:` unexplained —
possibly one BMAP session per link, or the phone held channel 2 at the time. If a
connect is refused, try the other before concluding the device is busy.

### Google Fast Pair message stream

Publicly specified, unlike everything else here:
[Message Stream](https://developers.google.com/nearby/fast-pair/specifications/extensions/messagestream),
[Device Information](https://developers.google.com/nearby/fast-pair/specifications/extensions/deviceinformation),
[Hearable Controls](https://developers.google.com/nearby/fast-pair/specifications/extensions/hearablecontrols).
Frames are `[group][code][uint16 be length][data]`. Connecting to ch 22 gets an
unprompted burst:

```
03 01 0003 a501e2      # Device information / model id
03 03 0003 5a 5a 64    # battery: left 90%, right 90%, case 100% (bit 7 = charging)
03 0a 0008 <8 bytes>   # session nonce
03 02 0006 <6 bytes>   # current BLE address
```

Battery arrives again on every change, so this is a subscription-free alternative
to `[2.2]`. Group `0x08` (Hearable Controls) carries ANC state both ways: the
device notifies with `0x13`, a client sets with `0x12`, and the payload's four
bytes are version, UI-toggle flags, settable-toggle flags and current state —
Google's model of ANC (transparent / adaptive / off / ANC), not Bose's 0-10 CNC
scale. No reason for this app to prefer any of it over BMAP, but it is a useful
cross-check when a BMAP reading looks wrong.

## 13. Findings from the official app

- **`[2.12]` StatusButton** is a richer sibling of `[1.9]`: bytes 0-2 the same
  (button id, event, configured action), then the supported mask (3-7), an
  *unavailable* mask (7-11), and `(action, reason)` pairs (11-75).
  `ponytail:` never exercised on this device.
- **`[7.10]` ControlClientInteraction** exists with a state machine
  (Idle / LocalActivity / RequestPending / AuthorizationPending /
  MediaResponsePending / Rendering) and a START taking `[event][uint32 timeout]`,
  which reads exactly like "client, go render some media". The app never sends it,
  and `ActionButtonMode.ClientInteraction` (15) is absent from this device's
  supported mask `000b4002` — the app's own label mapper calls 15 unknown.
- The action enum matches `BUTTON_ACTIONS` in `Types.kt`, including the gap at 18.

---

## 14. Multipoint devices `[4.x]` — list, connect, disconnect

Everything below was read off the earbuds. Wire formats were cross-checked
against the official app's
`com.bose.bmap.messages.{packets,responses}.DeviceManagement*`, but the values
are from this unit.

### What the firmware actually implements

GET sweep of block 4:

| Address                                   | Name                                                               | GET result                                             |
|-------------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------|
| `[4.0]`                                   | FblockInfo                                                         | `"1.1.0"`                                              |
| `[4.1]`                                   | Connect                                                            | STATUS `000003`                                        |
| `[4.2]`                                   | Disconnect                                                         | error 5 — GET is not a valid operator, **START works** |
| `[4.3]`                                   | RemoveDevice                                                       | error 5 — GET is the wrong operator, **START works**   |
| `[4.4]`                                   | ListDevices                                                        | STATUS, see below                                      |
| `[4.5]`                                   | Info                                                               | error 6 without a payload; takes a MAC                 |
| `[4.6]`                                   | ExtendedInfo                                                       | error 6 without a payload; takes a MAC                 |
| `[4.7]`                                   | ClearDeviceList                                                    | error 5                                                |
| `[4.8]`                                   | PairingMode                                                        | STATUS `00`                                            |
| `[4.9]`                                   | AppAddress                                                         | STATUS = MAC of the device running the app             |
| `[4.14]`                                  | Features                                                           | STATUS `01` — capability bits, see below               |
| `[4.15]`                                  | BoseProduct                                                        | error 1 (Length) — takes a payload                     |
| `[4.18]`                                  | AvailableToConnect                                                 | STATUS `01`                                            |
| `[4.10-13]`, `[4.16]`, `[4.17]`, `[4.19]` | P2p, Routing, ConnectionPriority, UserCarouselSelect, LeAudioCheck | error 4, not supported                                 |

bosectl's block-4 list (`NOTES.md`) omits `[4.5]` and `[4.6]`, which do exist —
one more reason not to trust it. Its `[4.12]` label "switch active multipoint
device" is moot: `[4.12]` is `FuncNotSupp` here, as is `[0.11]`
ComponentDevices.

### `[4.4]` ListDevices — byte 0 is a connected-bitmask, and the list reorders

```
[4.4] GET  ->  STATUS  [connectedMask, mac(6) × n]
```

Bit *i* of byte 0 is set when list entry *i* is connected. It is **not** a
count — with exactly one device connected at index 1 it reads `02`:

```
03 5cf3709b8cff 088bc851d48d 842f…    # laptop + phone
02 088bc851d48d 5cf3709b8cff 842f…    # phone dropped; laptop is index 1
01 5cf3709b8cff 088bc851d48d 842f…    # same one device, list reordered
```

That third line is the trap: **the order is not stable across reads.** Pair the
mask with the MACs from the same frame and never cache a position. The official
app skips byte 0 entirely and calls `[4.5]` per MAC instead.

A payload of length 1 (mask only, no MACs) means an empty list.

### `[4.5]` Info and `[4.6]` ExtendedInfo — one MAC per query

```
[4.5] GET  [mac(6)]  ->  STATUS  [mac(6), flags, b7, b8, (variant), name…]
```

`flags` (byte 6): bit0 connected · bit1 isLocalDevice · bit2 isBoseProduct ·
bit3 isComponent · bit7 productType.

- isBoseProduct set: bytes 7-8 product id big-endian, byte 9 variant, name from byte 10.
- otherwise: byte 7 major device class, byte 8 minor device class, name from byte 9.

The name is UTF-8 running to the end of the payload — not NUL-terminated, no
length prefix. Every non-Bose device on this firmware reported major/minor
`02 03`, phone and laptop alike, so treat those two bytes as meaningless here.

```
5cf3709b8cff 03 0203 "Frigo"          # connected, isLocalDevice -> this host
088bc851d48d 00 0203 "Pixel 9 Pro"    # known but not connected
```

`isLocalDevice` matches `[4.9]` AppAddress, which is how you tell which entry is
the machine you are talking from.

```
[4.6] GET  [mac(6)]  ->  STATUS  [mac(6), pairedProfiles, connProfiles, 54, 14]
```

Profile bits in both masks: 0 a2dp · 1 hfp · 2 avrcp · 3 spp · 4 iap. A fully
connected phone reads `0f 0f`; a known-but-idle device `0f 00`. The trailing
`5414` was constant across all six devices and is not parsed by the official
app.

### Connect, disconnect and forget are unauthenticated STARTs

```
[4.2] START  [mac(6)]
  -> [4.2] PROCESSING  [reason, mac(6)]      reason 0x21 observed
  -> [4.2] RESULT      [mac(6)]

[4.1] START  [0x00, mac(6)]
  -> [4.1] PROCESSING  [mac(6)]

[4.3] START  [mac(6)]
  -> [4.3] PROCESSING  (empty payload)
  -> [4.3] RESULT      [mac(6)]
```

`[4.1]` returns no RESULT within a few seconds — poll `[4.4]` instead. Verified
end to end: with the phone disconnected from the phone's own side, `[4.1]`
brought it back and the `[4.4]` mask went `01` → `03` in about four seconds.
`[4.2]` on the phone dropped it and the mask went `03` → `02`.

`[4.3]` RemoveDevice was verified against a stale entry (an old CSR dongle MAC
that had been sitting in the list unused): the entry disappeared from `[4.4]`,
the other five devices were untouched, and both live connections kept playing.
Note the asymmetry with `[4.2]` — its PROCESSING frame carries **no payload**,
while the RESULT echoes the MAC. Removal is not undoable from BMAP: getting the
device back means re-pairing from the device itself.

`[4.1]` has two further payload forms in the official app, neither needed here:
`[0x01, utf8 name]` and `[(productType << 7) | 0x10, mac(6), localMac(6)]` for
Bose-to-Bose. `[4.7]` ClearDeviceList takes no payload and is `ponytail:`
**untested — it would wipe every pairing at once.**

### Error 5 on a GET does not mean "auth"

`[4.2]`, `[4.3]` and `[4.7]` all answer a GET with error 5 `OpNotSupp` and yet
accept an unauthenticated START. `OpNotSupp` means *that operator* is not valid
for that function — for an action function, GET never is. Do not read the
block-31 auth pattern into it.

### `[4.14]` Features — the capability bits that close the question

The official app parses `[4.14]`'s single byte as three booleans
(`messages/models/devicemanagement/FeatureInfo`):

| Bit | Capability                | This unit (`01`) |
|-----|---------------------------|------------------|
| 0   | `cTKDSupported`           | yes              |
| 1   | `deviceCarouselSupported` | **no**           |
| 2   | `sourceBargeInSupported`  | **no**           |

Read this register before implementing anything source-related — it is the
firmware telling you up front which of the mechanisms below exist.

### There is no "move audio to this device" command

Four independent confirmations, which is why this is settled rather than
merely un-found:

1. `[4.14]` bit 1 and bit 2 are both clear — the firmware declares device
   carousel and source barge-in unsupported.
2. `[4.17]` UserCarouselSelect is `FuncNotSupp`, as are `[4.12]` Routing and
   `[4.16]` ConnectionPriority.
3. `ActionButtonMode.SwitchSourceDevice` is action **8**, and bit 8 is absent
   from this device's shortcut mask `000b4002` (§3) — the "Switch Devices"
   gesture the product tour describes is not offered on this model.
4. The official app's own "Switch Connections" flow says exactly what it does:
   *"This will disconnect %s from %s and attempt to connect to %s."* Bose has no
   source-select command either.

`[5.1] GET` reports the **active source** (§8) and is read-only in practice: 19
SETGET payload shapes were rejected with error 6, including the empty payload,
which would be error 1 if the firmware were really parsing a shape. START on
`[5.1]` is error 5.

So moving audio means disconnecting whichever device currently holds it —
`[5.1]` names it, `[4.2]` drops it, and **playback moves to the remaining
connected device** (verified: dropping the phone handed audio to the laptop).
Connecting a second device with `[4.1]` does *not* hand it the audio; after the
phone reconnected, `[5.1]` still reported the laptop.

### `[1.30]` SourceBargeIn — a capability gate, not a payload problem

`[1.30]` reads `00` and every SETGET (`01`, `0001`, `0101`) returns error 10
`InvalidState`, retried with only one device connected in case multipoint was
the blocking state. The error code was the clue and `[4.14]` bit 2 is the
answer: **the firmware advertises source barge-in as unsupported**, so the
function exists in the enum and refuses on state rather than on data. Not worth
further payload guessing.

---

## 15. BMAP over BLE/GATT — a second transport, gated on link encryption

BMAP is not RFCOMM-only. The earbuds expose the same protocol over GATT, and the
official SDK carries three transports side by side (`com/bose/bmap/ble/`
`BleConnectionManager`, `service/SppConnectionManager`, `ble/LecocConnectionManager`).
All of them feed **one** parser — `parseBleBmapPacket()` → `parseBmapPacket()` — so
there is no BLE-specific register set. Verified from Linux/BlueZ against the
earbuds while they stayed connected over classic BT (audio undisturbed).

### The GATT database

```
service 0000febe-0000-1000-8000-00805f9b34fb
  d417c028-9818-4354-99d1-2ac09d074591  read,write,write-without-response,notify  RWN unsecure
  c65b8f2f-aee2-4c89-b758-bc4892d6f2d8  read,write,write-without-response,notify  RWN secure
  9edc3c01-6caa-4678-95a7-82f1746e5515  read,write,notify                        unknown
  089ca084-6721-4419-9003-5e14d5ab587d  read                                     LE-CoC PSM
```

The service uses the **standard 16-bit base**; `BleConnectionManager.SERVICE_UUID`
(`0000febe-0000-0000-0000-000000000000`) is dead code — the app looks characteristics
up across all services. `9edc3c01-…` appears nowhere in the decompiled app.
Reading the LE-CoC PSM characteristic times out and takes the link down with it.

Other services present: `1800`, `1801`, `180a`, `fe2c` (Google Fast Pair),
`fd92`, `eb10-d102-11e1-…` (Bose).

### Framing: one segmentation byte in front of the RFCOMM frame

Every ATT write and every notification is `[seg] + chunk`, where
`seg = (lastIndex << 4) | index`, so `0x00` is a single unsegmented frame
(`BleConnectionManager.asBmapWriteData`, `utils/PacketSegmentationUtil`). The
reassembled buffer is one or more back-to-back standard BMAP frames — length is
still `data[3] + 4`. Notifications use the same header and are reassembled until
`(seg >> 4) == (seg & 0x0F)`. Chunk size is `MTU - 4`; max 16 segments.

Verified at the 20-byte ATT payload a Connect IQ watch is limited to:

```
TX 00 00 01 01 00                      [0.1] GET, single segment
RX 00 00 01 03 05 31 2e 32 2e 30       [0.1] STATUS "1.2.0"

TX 10 00 07 01 18 00 01 … 0e           [0.7] GET + 24B junk payload, segment 0/1
TX 11 0f 10 11 12 13 14 15 16 17       segment 1/1
RX 00 00 07 03 11 30 38 36 32 …        [0.7] STATUS <serial> — reassembled
```

### Unbonded, almost everything is error 20 `InsecureTransport`

A 25-address GET sweep on the **unsecure** characteristic without any LE bond:

| Address                     | Result                                 |
|-----------------------------|----------------------------------------|
| `[0.1]` bmap version        | `STATUS "1.2.0"`                       |
| `[0.2]` all fblocks         | `STATUS 8f cc 23 ff`                   |
| `[4.14]` features           | `STATUS 00`                            |
| `[1.1] [2.1] [31.1]` GetAll | error 5 — wrong operator, as on RFCOMM |
| `[2.10]` in-ear             | error 4 `FuncNotSupp`                  |
| everything else             | **error 20 `InsecureTransport`**       |

Reads are unauthenticated over RFCOMM; over BLE the firmware requires an
encrypted link for all of it, including `[9.2]`. So the unsecure characteristic is
only good for the pre-pairing setup the app uses it for (`defpackage/P20`
BLE-connects with `forceUnsecureCharacteristic` purely to read the static MAC).

### What unlocks it is an encrypted link — and the classic bond already provides one

`[4.14]` bit 0 `cTKDSupported` is set on this unit (§14), i.e. the firmware does
cross-transport key derivation: pairing over BR/EDR also yields an LE long-term
key. So a host that is classic-bonded needs **no LE pairing at all** — its LE
link comes up encrypted and the secure characteristic works.

Confirmed from Android, which is the only side that can prove it, because it can
pin the transport:

```kotlin
device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
```

With `bondState = BONDED`, `type = DEVICE_TYPE_DUAL` and no LE pairing, the
secure characteristic `c65b8f2f-…` answered the full register set — `[0.1]`
`"1.2.0"`, `[0.7]` serial, `[2.2]` the repeating battery groups of §2, `[1.10]`
`07`, `[31.3]`, `[31.10]`, `[5.1]` active source, `[4.4]` the device list of §14,
`[9.2]` mask. Only `[1.1]`/`[2.1]`/`[31.1]` (error 5) and `[2.10]` (error 4) fail,
exactly as they do on RFCOMM.

**BlueZ cannot run this experiment.** It has no per-transport connect: `Connect()`
picks BR/EDR for a device that has a classic pairing, and `ConnectProfile()` is
BR/EDR-only (`org.bluez.Error.BREDR.ProfileUnavailable` for a GATT UUID). A
successful sweep from Linux on the device's public address while the classic link
was up therefore proves nothing about LE — it was most likely GATT over BR/EDR.
Its `Connected`/`ServicesResolved` refer to the classic link, and the GATT
objects can be a stale cache; the giveaway is `WriteValue` failing with
`org.bluez.Error.Failed: Not connected` while `Connected` reads true.

Attempting an actual *LE* bond from BlueZ failed
(`org.bluez.Error.AuthenticationFailed`, SMP rejected before any agent prompt)
until the earbuds were in pairing mode — and what succeeded then was very likely
just a classic re-bond, since BlueZ merged it into the existing BR/EDR device
object. Pairing mode is visible in the advertisement: manufacturer data (company
`0x009E`) value byte 2 bit 3, per `SpitfireAdvertisingPacket.getInPairingMode`,
and **in pairing mode the earbuds advertise their identity (public) address**
instead of a random one. While connected to another host they were also seen
advertising the identity address, so a client need not resolve private addresses.

### Advertisement identifies the product

Manufacturer data starts with the Bose company id `0x009E`; then format byte,
then a byte holding `bleProductId` in bits 0-4 and variant in bits 5-7. BMAP
1.2.0 adds 100 to the product id (`BoseProductIdSupport.OFFSET_BLE_120_FORMAT_VSPITFIRE`).
Observed `00 a8 06 …` → format 0, id `100 + 8 = 108` = `Edith` = QC Ultra 2
Earbuds, variant 5. Then prand (3 bytes) and Bose's resolvable MAC (3 bytes).

### BLE control works with no classic link at all — audio can live elsewhere

This is the practical payoff: **a bonded phone can talk BMAP over BLE while the
earbuds are connected to a different host for audio.** Captured with the earbuds
playing to the laptop and the phone holding no classic link whatsoever:

```
[4.4] STATUS 01 5cf3709b8cff 088bc851d48d …   mask 01 -> only the laptop connected
[4.5] (the phone, 088bc851d48d, is in the list but not connected)
[5.1] STATUS 000f01 5cf3709b8cff             active source is the laptop
```

From that state, every register answered over the LE link — `[4.9]`, `[2.2]`,
`[31.3]`, `[4.4]`, `[5.1]` — for the whole 25 s test, with `Connected` on the
classic transport false the entire time. So the app does not need to own the audio
link to read state or issue commands; the LE link is enough, and it is independent
of who is playing.

Two details worth keeping:

- `[4.2] START` with the phone's *own* MAC (from `[4.9]`) is accepted even when
  that device is not connected — `PROCESSING [0x21, mac]` then `RESULT [mac]`.
- `[4.1] START` afterwards reconnected the phone (mask `01` → `03`) and, exactly
  as §14 says, **did not** move the audio: `[5.1]` still named the laptop.

The LE link also survived the classic disconnect it triggered, so a client does
not need to re-establish GATT when the audio link comes and goes.

### Reproducing: the BLE probe
The `probe` flavor carries a GATT client that does the above (`debug/BleProbe.kt`):

```bash
# read-only sweep over the LE link
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl \
    --es mode ble --es label LE --ei mtu 23

# same, but drops this phone's own classic link first ([4.2]) and restores it
# ([4.1]) — proves control without audio. Interrupts playback for ~30s.
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl \
    --es mode bleoffline --es label OFFLINE

adb logcat -d -s BmapProbe
```

`mode ble` is read-only. Both log the whole GATT database, the granted MTU, every
raw notification and the reassembled BMAP frames.

`ponytail:` still untested — whether the firmware segments *notifications* down to
20 bytes at an MTU of 23. **Neither side can force it:** BlueZ negotiates a large
MTU with no override, and Android's stack ignored `requestMtu(23)` and granted

247. Observed behaviour at MTU 247 is that the device does *not* segment — a
     36-byte `[4.4]` reply arrived as one notification with segment header `0x00`. A
     GATT client that cannot negotiate MTU (Connect IQ, for one) may therefore see
     truncated pushes; test that on the client itself.

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
