#!/usr/bin/env python3
"""Vendor Material Symbols as Android vector drawables.

To add an icon: put its Symbols name in ICONS (or FILLED for the filled
variant), run this script, then expose it in `ui/AppIcons.kt`. Never hand-write
path data.

Why the wrapper group: Material Symbols ship with `viewBox="0 -960 960 960"`,
i.e. a negative Y origin, which Android's vector drawable format cannot express
— viewportWidth/Height are sizes, there is no origin. So each glyph goes inside
a `<group>` translated by the negation of the viewBox origin, putting it back
into a plain 0..960 box.

Network: some setups can't complete TLS to fonts.gstatic.com. Point CURL at a
container instead of the system binary:

    CURL="docker run --rm quay.io/curl/curl:latest" \\
        python3 tools/fetch-material-symbols.py
"""

import os
import re
import subprocess
import sys
from pathlib import Path

BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined"
OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
CURL = os.environ.get("CURL", "curl").split()

# Outlined weight-400 symbols -> ic_<name>.xml
ICONS = [
    "add", "arrow_back", "arrow_forward", "battery_std", "bedtime",
    "bluetooth", "bluetooth_disabled", "blur_on", "call", "directions_bike",
    "directions_bus", "directions_car", "directions_run", "directions_walk",
    "earbud_case", "earbud_left", "earbud_right", "earbuds_2", "edit",
    "fitness_center", "flight", "forum", "headphones", "hearing", "hiking",
    "home", "landscape", "lightbulb", "link_off", "local_airport", "lock",
    "menu_book", "movie", "music_note", "noise_aware", "noise_control_off",
    "noise_control_on", "podcasts", "school", "self_improvement", "settings",
    "spa", "spatial_audio", "spatial_audio_off", "spatial_tracking",
    "speaker_group", "star", "surround_sound", "touch_app", "tune",
    "visibility", "volume_down", "volume_off", "volume_up", "work",
]

# Filled variants of the same symbols, under an explicit output name.
FILLED = {"star": "ic_star_filled"}

# Directional glyphs, which have to flip in RTL layouts. The SVG carries no
# hint for this, so it is a manual list — if you add an icon that points
# somewhere, put it here too.
AUTO_MIRRORED = {
    "arrow_back", "arrow_forward", "directions_bike", "directions_run",
    "directions_walk", "menu_book", "volume_down", "volume_off", "volume_up",
}

TEMPLATE = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
{mirrored}    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{w:g}"
    android:viewportHeight="{h:g}">
    <group
        android:translateX="{tx:g}"
        android:translateY="{ty:g}">
{paths}
    </group>
</vector>
"""

PATH = """        <path
            android:fillColor="@android:color/white"
            android:pathData="{d}" />"""


def fetch(name: str, fill: bool) -> str:
    url = "%s/%s/%s/24px.svg" % (BASE, name, "fill1" if fill else "default")
    result = subprocess.run(
        CURL + ["-sSfL", "--max-time", "30", url],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError("fetch %s failed: %s" % (name, result.stderr.strip()))
    return result.stdout


def convert(svg: str, mirrored: bool = False) -> str:
    box = re.search(r'viewBox="([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+)"', svg)
    if not box:
        raise RuntimeError("no viewBox")
    min_x, min_y, width, height = (float(v) for v in box.groups())

    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise RuntimeError("no path data")

    # Cancel the viewBox origin; Android viewports always start at 0,0.
    # `or 0.0` collapses the -0.0 that negating a zero origin would otherwise
    # render as a literal "-0".
    return TEMPLATE.format(
        w=width, h=height,
        tx=-min_x or 0.0, ty=-min_y or 0.0,
        mirrored='    android:autoMirrored="true"\n' if mirrored else "",
        paths="\n".join(PATH.format(d=d) for d in paths),
    )


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    targets = [(n, "ic_%s" % n, False) for n in ICONS]
    targets += [(n, out, True) for n, out in FILLED.items()]

    failures = []
    for name, out_name, fill in sorted(targets, key=lambda t: t[1]):
        try:
            xml = convert(fetch(name, fill), mirrored=name in AUTO_MIRRORED)
        except RuntimeError as e:
            failures.append("%s: %s" % (out_name, e))
            print("FAIL %s" % out_name, file=sys.stderr)
            continue
        target = OUT_DIR / ("%s.xml" % out_name)
        existing = target.read_text() if target.exists() else None
        target.write_text(xml)
        print("%-4s %s" % ("new" if existing is None
                           else ("same" if existing == xml else "upd"), out_name))

    if failures:
        print("\n%d failed:" % len(failures), file=sys.stderr)
        for f in failures:
            print("  " + f, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
