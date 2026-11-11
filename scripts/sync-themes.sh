#!/usr/bin/env bash
# Copies the stock Omarchy themes into the app's assets.
# Each theme keeps its colors.toml; wallpapers are resized to cover the
# tablet's 2560x1600 screen and re-encoded as WebP to keep the APK small.
set -euo pipefail

SRC="${1:-/usr/share/omarchy/themes}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/themes"

rm -rf "$DEST"
mkdir -p "$DEST"

for dir in "$SRC"/*/; do
  name="$(basename "$dir")"
  [ -f "$dir/colors.toml" ] || continue
  mkdir -p "$DEST/$name/backgrounds"
  cp "$dir/colors.toml" "$DEST/$name/colors.toml"
  find "$dir/backgrounds" -maxdepth 1 -type f \
    \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \) -print0 |
    sort -z |
    xargs -0 -P "$(nproc)" -I{} sh -c '
      out="$1/$(basename "${2%.*}").webp"
      magick "$2" -resize "2560x1600^" -quality 80 "$out"
    ' _ "$DEST/$name/backgrounds" {}
done

echo "Themes: $(ls "$DEST" | wc -l), wallpapers: $(find "$DEST" -name '*.webp' | wc -l), size: $(du -sh "$DEST" | cut -f1)"
