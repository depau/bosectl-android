# Bose Control — working notes for agents

Unofficial Android app controlling Bose QC Ultra earbuds/headphones over BMAP
(Bluetooth RFCOMM). See `README.md` for the user-facing description and
`docs/PROTOCOL.md` for the wire format.

## Hardware boundary — ask first

**Never connect to, probe, or otherwise command the user's earbuds without
explicit permission in the current conversation.** They are a device the user is
actively listening to; an unannounced RFCOMM connect steals them from their phone
and cuts their audio. This has happened — don't repeat it.

- Fine unprompted: reading files, `adb logcat`, inspecting builds.
- Ask first: running the probe, `bosectl` against the earbuds, `adb` commands
  that change device state (volume, force-stop while connected), installing over
  a live session.
- Permission is per-request, not standing.

## Build

AGP 9 with **built-in Kotlin**. Two consequences that break the usual muscle
memory:

- Do **not** apply `org.jetbrains.kotlin.android` — it hard-fails ("no longer
  required since AGP 9.0"). Only the Compose compiler plugin is applied.
- `kotlinOptions {}` does not exist. The Kotlin JVM target is derived from
  `compileOptions` (currently Java 17).

`gradle.properties` is deliberately minimal. The `android.*` compatibility flags
Android Studio adds on upgrade are opt-outs from AGP 9 defaults — don't
reintroduce them; fix the underlying issue instead.

Two product flavors, so tasks are flavor-qualified:

```bash
./gradlew installStandardDebug    # the app
./gradlew installProbeDebug       # app + protocol probe (see below)
./gradlew :bmap:test              # protocol unit tests, no device needed
```

APKs land in `app/build/outputs/apk/<flavor>/<buildType>/`. Debug APKs are
test-only, so manual installs need `adb install -r -t`.

## Architecture

- **`:bmap`** — protocol only, no UI dependencies. `Protocol.kt` (codec),
  `Parsers.kt` (payload <-> types), `Types.kt`, `RfcommTransport.kt`,
  `BmapConnection.kt` (typed API). Keep it Android-UI-free and testable.
- **`:app`** — `DeviceRepository` is the single owner of the connection and of
  `StateFlow<BoseState>`; UI, widget actions and the ACL receiver all go through
  it. Device work runs on the repository scope (`DeviceRepository.runAsync`), not
  a composition scope, so navigating away can't cancel an in-flight request.

Two interchangeable link layers sit under `BmapTransport`: `RfcommTransport`
(classic, channel 2) and `GattTransport` (BLE, service `febe`). They carry the
same packets — the only difference is BLE's one-byte segmentation header
(`BleFraming.kt`) — so nothing above the interface cares which is in use.
`data/LinkLayer.kt` holds the choice: `linkOrder()` decides, `openTransport()`
builds. **Never let the app initiate a classic connection**: RFCOMM to an idle
device steals the audio from whatever is playing, so `AUTO` only reaches for
classic when the link is already up, and LE otherwise. `linkOrder(automatic =
true)` enforces that for every connect the user did not explicitly ask for.

The pairing is asymmetric on purpose: LE reaches the earbuds when they are
playing to someone else, but the firmware hangs up an idle LE link after ~40 s,
so it needs a keepalive. Classic needs none — the audio connection holds it up.
So `upgradeToClassicIfAvailable()` moves an LE connection over as soon as a
classic link exists (this phone started holding the audio), and presence-driven
LE connects cover the rest.

Presentation stays out of `:bmap`, and `:app` never hand-builds packets.

## Protocol rules

`docs/PROTOCOL.md` is the source of truth — it records what was verified on real
hardware.

**pybmap/bosectl contains parsing bugs that were copied here once already.** Its
`parse_multipoint` and `parse_buttons` are wrong for these earbuds, and its
battery parser only handles single-cell devices. Treat the reference
implementation as a starting point, never as ground truth.

Do not guess at wire formats. Every format in this repo was confirmed against a
capture; if you need a new one, probe for it and record the finding in
`docs/PROTOCOL.md`. Mark unverified assumptions with a `ponytail:` comment.

Writes use SETGET or START only — never SET (auth-gated). Preset modes 0-3 reject
`[31.6]` writes with Runtime error 8.

## Icons

Material Symbols, vendored as vector drawables and exposed through `AppIcons`
(`ui/AppIcons.kt`) and `ModeIcons.kt`. **Do not add `material-icons-extended`
back** — it is Google's frozen predecessor set, it lacks glyphs this app needs
(`noise_control_on`, `earbud_left/right/case`), and it was most of a 63 MB debug
APK (now 31 MB).

New icon: add its Symbols name to `tools/fetch-material-symbols.py`, run the
script, then expose it in `AppIcons`. The script converts the Symbols
`viewBox="0 -960 960 960"` into an Android-legal viewport via a translate group.
Never hand-write path data. Directional glyphs also go in the script's
`AUTO_MIRRORED` set so they flip in RTL.

Re-running regenerates every icon; the existing ones must come back
byte-identical, so `git status` after a run should show only what you added.
If TLS to `fonts.gstatic.com` fails, run it through a container:

```bash
CURL="docker run --rm quay.io/curl/curl:latest" \
    python3 tools/fetch-material-symbols.py
```

## Testing

`bmap/src/test/.../ParsersTest.kt` asserts against **real capture hex**, with the
device state documented in the comment. When you learn a new format, add a vector
from an actual capture rather than a synthetic one — that's what caught the
button-mask and multipoint errors. Run `./gradlew :bmap:test` before claiming a
protocol change works.

UI changes should be verified on-device with a screenshot when practical; several
layout bugs were only visible that way.

## Protocol probe

The `probe` flavor adds a broadcast receiver that runs GET sweeps and logs them.
It is excluded from standard debug and from all release builds.

```bash
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl
adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl \
    --es mode settings --es label BEFORE     # fast Settings GetAll, for diffing
adb logcat -d -s BmapProbe:I
```

The earbuds push nothing until the app subscribes with `[9.2]`
NotificationByFblock — see `docs/PROTOCOL.md` §10. If live updates ever stop,
check that subscription before adding polling.

Unknown registers are found by diffing a Settings GetAll before and after
toggling one setting in the official Bose app. `[1.29]` (auto transparency) and
`[1.34]` (touch controls) were both found this way.

To *name* a register rather than find it, the decompiled official app is faster:
`com.bose.bmap.messages.enums.spec.BmapFunction` enumerates every block/function
id, and `messages/{packets,responses}/` carries the payload codec for most of
them. That is how `[1.20]` and `[1.30]` were resolved. A name from there is a
hypothesis, not a verified format — the app is one firmware's client, and it
ignores fields this firmware sends (see `[4.4]` byte 0 in `docs/PROTOCOL.md`
§14). Confirm against a capture before relying on it.

## Git

Do not create commits, branches, or any other git changes unless the user
explicitly asks.
