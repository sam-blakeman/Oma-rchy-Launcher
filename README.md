# Oma-rchy Launcher

An Android home-screen launcher in the style of [Omarchy](https://omarchy.org): flat,
monospace, keyboard-first, and themed from Omarchy's own theme files. Built for the
Lenovo Legion Y700 Gen 3, but it runs on any Android 13+ device.

## Features

- **Clear home page** with just the wallpaper, a Waybar-style bar and a row of pinned apps.
  Swipe either way for the app list.
- **Search-first app list.** Type anywhere to filter; Enter opens the top match.
  Search also does sums (`12*7`, Enter copies the answer), jumps to settings pages
  (`wifi`, `bluetooth`, `display`...) and falls back to a web search.
- **Omarchy themes.** Uses each theme's `colors.toml` and backgrounds, and can install
  more themes from a GitHub URL, like `omarchy theme install`.
- **Matching system wallpaper** on the home and lock screens.
- **Omarchy-style menu** from the logo in the bar: apps, search, theme, wallpaper,
  notifications, Wi-Fi and settings in one place.
- **Per-app menu** on long press: pin to home, rename, hide or open app info.
- **Recent apps first** in the list when you're not searching.
- **Touch, keyboard and controller** all work.

| Input | Clear page | App list |
|---|---|---|
| Type | Opens the list and searches | Searches |
| Left / Right, L1 / R1 | Flip page | Move / flip page |
| Swipe down / up | Notifications / app list | |
| Down | Focus pinned apps | Move |
| Enter / A | Open the app list | Open the focused app |
| Esc / B | | Clear search, then back to the clear page |
| Long press, Ctrl+P, Start | App menu | App menu |
| Ctrl+M / Select | Main menu | Main menu |
| Ctrl+T / Y | Theme picker | Theme picker |
| Ctrl+B / R3 | Next wallpaper | Next wallpaper |

## Installing

Download the APK from the [latest release](https://github.com/sam-blakeman/Oma-rchy-Launcher/releases/latest),
install it, then pick Oma-rchy Launcher as your home app in Android's settings. Some ROMs ask
whether it may read your installed apps: it needs that to list them.

Theme wallpapers are downloaded from [Omarchy's repo](https://github.com/omacom/omarchy) the first
time you use each theme, so the APK stays small.

## Building

The stock theme colours are copied from a local Omarchy install:

```sh
./scripts/sync-themes.sh                    # reads /usr/share/omarchy/themes
./scripts/sync-themes.sh --with-wallpapers  # also bundle the wallpapers, for offline use
```

Then build with JDK 17 and the Android SDK (platform 35):

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with a key read from `~/.gradle/gradle.properties`
(`OMA_STORE_FILE`, `OMA_STORE_PASSWORD`, `OMA_KEY_ALIAS`, `OMA_KEY_PASSWORD`); without it,
builds use the standard debug key.

## Credits

Themes and wallpapers come from [Omarchy](https://github.com/omacom/omarchy) (MIT) and their
respective authors. Bundles the [JetBrains Mono Nerd Font](https://www.nerdfonts.com/)
(SIL Open Font License).
