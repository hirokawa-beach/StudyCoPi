"""Export the supplied artwork without changing its colors or composition."""
import argparse
import shutil
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("--site", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
image = Image.open(args.source).convert("RGBA")
if image.width != image.height:
    raise ValueError("App artwork must be square")
shutil.copyfile(args.source, root / "icons/app-icon.png")
for size in (192, 512):
    rendered = image.resize((size, size), Image.Resampling.LANCZOS)
    for name in (f"app-icon-{size}.png", f"icon-{size}.png"):
        rendered.save(root / "icons" / name, optimize=True)
image.resize((1024, 1024), Image.Resampling.LANCZOS).save(
    root / "android/app/src/main/res/drawable-nodpi/app_icon_art.png", optimize=True)
if args.site:
    image.resize((512, 512), Image.Resampling.LANCZOS).save(
        args.site / "assets/icon.png", optimize=True)
print(f"Exported original {image.width}×{image.height} artwork, Web icons and Android artwork")
