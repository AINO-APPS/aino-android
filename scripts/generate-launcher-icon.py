"""Regenerate the in-app Android brand mark from the shared brand artwork.

Usage: python scripts/generate-launcher-icon.py [path/to/icon-source.png]

The default source is the PNG rendered from the desktop app's master SVG
(`aino-platform/desktop/icons/icon-source.svg`, via `node generate-icons.js`).

Writes `aino_icon.png` (192px) to drawable/ and drawable-night/. The launcher
icon itself is an adaptive *vector* icon (drawable/ic_launcher_background.xml,
ic_launcher_foreground.xml, ic_launcher_monochrome.xml) and needs no raster
generation; keep those path coordinates in sync with icon-source.svg.
Requires Pillow.
"""
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = ROOT.parent / "aino-platform" / "desktop" / "icons" / "icon-source.png"
RES = ROOT / "app" / "src" / "main" / "res"
DRAWABLES = [RES / "drawable", RES / "drawable-night"]


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    art = Image.open(source).convert("RGBA")
    # Square crop around the opaque mark (no distortion on resize), padded so the
    # bars survive the circular crop applied to notification avatars.
    left, top, right, bottom = art.getchannel("A").getbbox()
    side = round(max(right - left, bottom - top) * 1.45)
    cx, cy = (left + right) // 2, (top + bottom) // 2
    logo = art.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side))
    for directory in DRAWABLES:
        logo.resize((192, 192), Image.LANCZOS).save(directory / "aino_icon.png", optimize=True)
        print(f"  ok {directory.name}/aino_icon.png (192px)")


if __name__ == "__main__":
    main()
