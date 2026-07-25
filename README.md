# Bose Control

An unofficial Android app for **Bose QuietComfort Ultra** earbuds and headphones,
built because the official app is slow, cluttered and buries the two settings you
actually change all day.

It talks BMAP directly over Bluetooth RFCOMM — no Bose account, no cloud, no
authentication. Protocol work is based on
[aaronsb/bosectl](https://github.com/aaronsb/bosectl), rewritten in Kotlin and
corrected in several places against a real device (see
[`docs/PROTOCOL.md`](docs/PROTOCOL.md)).

## Features

**Home** — everything you touch daily on one screen:

- Per-component battery (left, right, case)
- Your starred modes as one-tap cards
- Immersive audio: Off / Still / Motion
- Noise cancellation slider and ANC toggle

**Modes** — the full list, with the same "stars" the official app uses to pick
which modes are reachable by earbud gesture. Create, edit and delete custom
profiles (name, icon, voice prompt, noise cancellation, immersive audio).
Presets open read-only so you can inspect what they do.

**Home-screen widget** — controls without opening the app, with a layout that
adapts to the size you give it (minimum 3x1):

| Height | Shows |
|---|---|
| 1 row | Header and starred modes as chips (with icons when wider than 3 cells) |
| 2 rows | Header, square mode buttons, head tracking |
| 3 rows | Adds ANC and touch-control toggles, plus a noise-cancelling stepper |

It renders from a cache, so it draws instantly and still works while
disconnected; tapping a control then connects on demand.

**Settings** — volume, equalizer, side tone, voice prompts, in-ear detection
(auto play/pause, auto-answer, auto transparency), touch controls with per-side
shortcut actions, multipoint, pairing mode, rename, power off.

Material 3 throughout, with dynamic colour.

## Requirements

- Android 12 (API 31) or newer
- Headphones already paired in system Bluetooth settings
- QC Ultra Earbuds (2nd gen) or QC Ultra Headphones (2nd gen)

Only the QC Ultra family is supported. Older models (QC35, NC700, …) speak a
different dialect of the same protocol and are not implemented.

Developed and tested against QC Ultra Earbuds 2nd gen on firmware
`8.4.8+gbb2cb60`.

## Building

```bash
./gradlew installStandardDebug
```

The project has two product flavors. `standard` is the app; `probe` additionally
contains a protocol probe used for reverse engineering, and is never built
unless you ask for it:

```bash
./gradlew installProbeDebug     # only when investigating the protocol
```

Debug APKs are marked test-only by AGP 9, so a manual install needs `-t`:

```bash
adb install -r -t app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Run the protocol test suite (pure JVM, no device needed):

```bash
./gradlew :bmap:test
```

Icons are Material Symbols, vendored as vector drawables in `res/drawable`
(there is no Compose artifact for Symbols, and the older `material-icons-extended`
lacks glyphs like `noise_control_on` and the per-side earbud icons). To add one,
append it to the list in `tools/fetch-material-symbols.py` and run:

```bash
python3 tools/fetch-material-symbols.py
```

## Project layout

```
bmap/     BMAP protocol: packet codec, parsers, RFCOMM transport, typed API.
          Pure Kotlin, no UI dependencies, unit-tested against real captures.
app/      Compose UI, device repository, connection lifecycle, Glance widget.
docs/     PROTOCOL.md — everything learned about the wire format.
tools/    fetch-material-symbols.py — regenerates the vendored icon drawables.
```

`:bmap` knows nothing about Android UI and `:app` never builds a packet by hand,
so the protocol layer is reusable and testable on its own.

## Notes and limitations

- **Volume** is Android's media volume. These earbuds use AVRCP absolute volume,
  so the system volume *is* the earbud volume — there's no separate BMAP
  register. The slider in Settings drives `AudioManager`.
- **Wind block** is not exposed: the firmware rejects writes to it.
- **Auto transparency**, **touch controls** and the **per-side shortcuts** were
  not in the reference implementation; they were reverse engineered here.
- The app connects on demand and disconnects when the earbuds go away, rather
  than holding a permanent connection.

## License

GNU General Public License, version 3 or later (`GPL-3.0-or-later`).
See [`LICENSE`](LICENSE).

## Credits

Protocol groundwork by [aaronsb/bosectl](https://github.com/aaronsb/bosectl).
Not affiliated with or endorsed by Bose.
