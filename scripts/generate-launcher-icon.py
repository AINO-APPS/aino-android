"""Generate the Android adaptive launcher icon from the shared AINO brand artwork.

Usage: python scripts/generate-launcher-icon.py [path/to/icon-source.png]

The default source is the PNG rendered from the desktop app's master SVG
(`aino-platform/desktop/icons/icon-source.svg`).

Writes per-density `ic_launcher_foreground.png` (full colour) and
`ic_launcher_monochrome.png` (alpha silhouette for Android 13+ themed icons).
Also updates the in-app brand mark in drawable/ and drawable-night/. The
launcher background layer is transparent (see mipmap-anydpi-v26/ic_launcher.xml).
Requires Pillow.
"""
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = ROOT.parent / "aino-platform" / "desktop" / "icons" / "icon-source.png"
RES = ROOT / "app" / "src" / "main" / "res"
DRAWABLES = [RES / "drawable", RES / "drawable-night"]
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
# Adaptive icons guarantee a 66dp-diameter circle stays visible under every mask.
SAFE_RADIUS_DP = 33


def fit_scale(logo: Image.Image) -> float:
    """Largest scale (logo px -> dp) keeping every opaque pixel inside the safe circle."""
    alpha = logo.getchannel("A")
    w, h = logo.size
    cx, cy = w / 2, h / 2
    step = max(1, min(w, h) // 400)
    px = alpha.load()
    far = max(
        math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        for y in range(0, h, step)
        for x in range(0, w, step)
        if px[x, y] > 16
    )
    return SAFE_RADIUS_DP / far


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    art = Image.open(source).convert("RGBA")
    logo = art.crop(art.getchannel("A").getbbox())
    # The tile is opaque, so the themed-icon silhouette comes from the light mark
    # (white ring + yellow dot, green >= ~190) rather than the tile's alpha.
    green = logo.getchannel("G").point(lambda v: max(0, min(255, (v - 140) * 255 // 50)))
    mark = Image.new("RGBA", logo.size, (255, 255, 255, 0))
    mark.putalpha(green)
    mono = mark.crop(green.getbbox())
    layers = (("foreground", logo, fit_scale(logo)), ("monochrome", mono, fit_scale(mono)))
    for name, density in DENSITIES.items():
        canvas_px = round(CANVAS_DP * density)
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        for layer, image, scale_dp in layers:
            size = (max(1, round(image.width * scale_dp * density)), max(1, round(image.height * scale_dp * density)))
            offset = ((canvas_px - size[0]) // 2, (canvas_px - size[1]) // 2)
            canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
            canvas.alpha_composite(image.resize(size, Image.LANCZOS), offset)
            canvas.save(out / f"ic_launcher_{layer}.png", optimize=True)
        print(f"  ok mipmap-{name} ({canvas_px}px)")
    for directory in DRAWABLES:
        logo.resize((192, 192), Image.LANCZOS).save(directory / "aino_icon.png", optimize=True)
        print(f"  ok {directory.name}/aino_icon.png (192px)")


if __name__ == "__main__":
    main()
