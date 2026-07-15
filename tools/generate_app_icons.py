"""Generate the iOS and Android launcher icons from the GPT Image logo source."""

from pathlib import Path
import sys

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[1]
IOS_ICON_DIR = REPO_ROOT / "CodeServerApp" / "Assets.xcassets" / "AppIcon.appiconset"
ANDROID_RES_DIR = REPO_ROOT / "android" / "app" / "src" / "main" / "res"
BRANDING_DIR = REPO_ROOT / "branding"


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_app_icons.py <gpt-image-logo.png>")

    source = Image.open(sys.argv[1]).convert("RGB")
    side = min(source.size)
    left = (source.width - side) // 2
    top = (source.height - side) // 2
    square = source.crop((left, top, left + side, top + side))

    # The generated concept includes a rounded presentation canvas. Trim its
    # outer corners so platform launchers can apply their own icon masks cleanly.
    trim = round(side * 96 / 1254)
    square = square.crop((trim, trim, side - trim, side - trim))
    master = square.resize((1024, 1024), Image.Resampling.LANCZOS)

    save_png(master, BRANDING_DIR / "CodeServerApp-logo-gpt.png")

    ios_icons = {
        "AppIcon-20x20@1x-ipad.png": 20,
        "AppIcon-20x20@2x-ipad.png": 40,
        "AppIcon-20x20@2x-iphone.png": 40,
        "AppIcon-20x20@3x-iphone.png": 60,
        "AppIcon-29x29@1x-ipad.png": 29,
        "AppIcon-29x29@2x-ipad.png": 58,
        "AppIcon-29x29@2x-iphone.png": 58,
        "AppIcon-29x29@3x-iphone.png": 87,
        "AppIcon-40x40@1x-ipad.png": 40,
        "AppIcon-40x40@2x-ipad.png": 80,
        "AppIcon-40x40@2x-iphone.png": 80,
        "AppIcon-40x40@3x-iphone.png": 120,
        "AppIcon-60x60@2x-iphone.png": 120,
        "AppIcon-60x60@3x-iphone.png": 180,
        "AppIcon-76x76@1x-ipad.png": 76,
        "AppIcon-76x76@2x-ipad.png": 152,
        "AppIcon-83.5x83.5@2x-ipad.png": 167,
        "AppIcon-1024.png": 1024,
    }
    for filename, size in ios_icons.items():
        icon = master if size == 1024 else master.resize((size, size), Image.Resampling.LANCZOS)
        save_png(icon, IOS_ICON_DIR / filename)

    android_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for directory, size in android_sizes.items():
        icon = master.resize((size, size), Image.Resampling.LANCZOS)
        save_png(icon, ANDROID_RES_DIR / directory / "ic_launcher.png")
        save_png(icon, ANDROID_RES_DIR / directory / "ic_launcher_round.png")


if __name__ == "__main__":
    main()
