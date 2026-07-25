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

Unknown registers are found by diffing a Settings GetAll before and after
toggling one setting in the official Bose app. `[1.29]` (auto transparency) and
`[1.34]` (touch controls) were both found this way; `[1.20]` and `[1.30]` are
still unidentified.

## Git

Do not create commits, branches, or any other git changes unless the user
explicitly asks.
