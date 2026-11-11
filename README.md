# Oma-rchy Launcher

An Android home-screen launcher in the style of [Omarchy](https://omarchy.org): flat,
monospace, keyboard-first, and themed from Omarchy's own theme files. Built for the
Lenovo Legion Y700 Gen 3, but it runs on any Android 13+ device.

## Features

- **Clear home page** with just the wallpaper, a Waybar-style bar and a row of pinned apps.
  Swipe either way for the app list.
- **Search-first app list.** Type anywhere to filter; Enter opens the top match.
- **Omarchy themes.** Uses each theme's `colors.toml` and backgrounds, and can install
  more themes from a GitHub URL, like `omarchy theme install`.
- **Matching system wallpaper** on the home and lock screens.
- **Touch, keyboard and controller** all work.

| Input | Clear page | App list |
|---|---|---|
| Type | Opens the list and searches | Searches |
| Left / Right, L1 / R1 | Flip page | Move / flip page |
| Down | Focus pinned apps | Move |
| Enter / A | Open the app list | Open the focused app |
| Esc / B | | Clear search, then back to the clear page |
| Long press, Ctrl+P, Start | Unpin | Pin or unpin |
| Ctrl+T / Y | Theme picker | Theme picker |
| Ctrl+B / R3 | Next wallpaper | Next wallpaper |

## Building

The stock themes aren't stored in this repo. They're copied from a local Omarchy install
at build time, with wallpapers resized for a 2560×1600 screen:

```sh
./scripts/sync-themes.sh            # reads /usr/share/omarchy/themes
./scripts/sync-themes.sh <dir>      # or any folder of Omarchy themes
```

Then build with JDK 17 and the Android SDK (platform 35):

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without the sync step the app still builds and runs, with only themes installed from GitHub.

## Credits

Themes and wallpapers belong to [Omarchy](https://github.com/basecamp/omarchy) and their
respective authors. Bundles the [JetBrains Mono Nerd Font](https://www.nerdfonts.com/)
(SIL Open Font License).
