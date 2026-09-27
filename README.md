[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Беларуская](README.be.md) · [Français](README.fr.md) · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md)

<p align="center">
  <img src="docs/icon.png" width="180" alt="Volna">
</p>

# Volna

<p align="center">
  <a href="https://github.com/manysuq/Volna/releases/tag/v1.0.4"><img alt="⬇ Download" src="https://img.shields.io/badge/v1.0.4-⬇%20Download-2ea44f?style=for-the-badge"></a>
</p>


**VIBECODED**

A calm music player for Android. It searches **YouTube Music** and streams the
audio directly — no whole-file download, no ads, no account.

## Screenshots

<table>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/01-similar-light.jpg" alt="01-similar" width="100%">
    <p align="center"><sub>Similar tracks</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/02-search-dark.jpg" alt="02-search" width="100%">
    <p align="center"><sub>Search, dark</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/03-player-dark.jpg" alt="03-player" width="100%">
    <p align="center"><sub>Now playing</sub></p>
  </td>
  <td width="50%" valign="top">
    <img src="docs/screenshots/04-artist-dark.jpg" alt="04-artist" width="100%">
    <p align="center"><sub>Artist</sub></p>
  </td>
  </tr>
  <tr>
  <td width="50%" valign="top">
    <img src="docs/screenshots/05-search-empty-dark.jpg" alt="05-empty" width="100%">
    <p align="center"><sub>Empty state</sub></p>
  </td>
  </tr>
</table>

## Features

- **YouTube Music search** — official tracks carry artist and album in the
  subtitle, so hour-long mixes and streams stay out of the results.
- **Video fallback** — if a track is missing from the YouTube Music catalog, the
  app says so and searches regular YouTube instead of silently playing
  something else.
- **Direct streaming** — ExoPlayer reads the audio stream over HTTP with resume;
  nothing is downloaded in full.
- **Auto-reconnect** — a YouTube link is bound to your IP and expires, so on a
  403 the app fetches a fresh one by itself.
- **Queue** — upcoming tracks resolve while you listen, so switching never waits
  on the network.
- **Albums and artists** — catalog from the public iTunes API.
- **Similar tracks** — radio built on YouTube Music recommendations, not the
  generic YouTube one.
- **Your library** — liked tracks and your own playlists, reorderable, stored on
  the device.
- **Play everything by an artist** — one shuffled set across all of their albums.
- **Offline** — download to the shared `Music/Volna` folder, playable without
  network.
- **Dark and light themes**, Material 3 Expressive.
- **Eight languages** — English, Russian, Ukrainian, Belarusian, French,
  Spanish, Chinese, Arabic.

## How it works

Search goes through the internal InnerTube API — the same one the official app
uses, minus the keys and the captcha:

```
POST https://music.youtube.com/youtubei/v1/search   client WEB_REMIX
```

YouTube Music tags every result with a type (`Song`, `Video`, `Album`,
`Playlist`, `Podcast`) and returns the `videoId` right inside
`playlistItemData`. That single type check is what separates official tracks
from everything else — regular YouTube search has no such marking, which is why
clips and remixes get in there.

The best-match picker weights the artist over the title. Without that, a fan
upload titled "Atlantida [CLIP]" outranks the real track, because the title
matches perfectly while the artist does not. Tracks marked as edits
(`slowed`, `reverb`, `remix`, `cover`, `nightcore`) are filtered out unless you
asked for a remix by name.

The audio stream itself is only served to the regular InnerTube clients, so link
resolution is a separate call against `www.youtube.com` with the `ANDROID`,
`ANDROID_VR` and `IOS` clients in turn.

## Build

Needs JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK
./gradlew :app:testDebugUnitTest      # unit tests
```

Each release build is copied to `dist/`, so builds never overwrite each other:

```
dist/volna-1.0.4-release.apk             the normal build, com.volna.player
dist/volna-1.0.4-FOR_ISLAND-<pkg>.apk    the dynamic-island variant, see below
```

Do not use `app/build/outputs/apk/release` — it is a scratch directory that
every build overwrites, so the file sitting there may be a different variant
than the one you meant to install.

### Release signing

The release keystore lives next to the project but never enters git; a
`keystore.properties` file points at it instead:

```properties
storeFile=volna-release.jks
storePassword=<store password>
keyAlias=volna
keyPassword=<key password>
```

Both files are in `.gitignore`. To generate your own key:

```bash
keytool -genkeypair -v -keystore volna-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias volna
```

Without `keystore.properties` the build still succeeds and simply produces an
unsigned APK, so a fresh checkout works with no key at all.

Signing enables v1, v2 and v3: v2 covers Android 7+, v3 is what lets an update
install over an existing copy on Android 11+.

> **The key in this checkout is a test key and its password is public.** Replace
> it with your own and keep it private: a lost key means you can no longer ship
> an update under the same identity, and a leaked one lets someone else sign
> releases as you.

## Project layout

```
app/                      the app (Compose, Material 3)
  search/                 InnerTube: YouTubeMusicSearch + YouTubeSearch fallback
  stream/                 audio link resolution and verification
  player/                 MediaSessionService on ExoPlayer
  catalog/                albums and artists via iTunes
  library/                liked tracks and playlists
  download/               offline downloads into the shared Music folder
ytdl/                     stream resolution library (plain Java, no dependencies)
design/                   icon variants
```

Packages: `com.volna.player` for the app, `com.ytdl.core` for the library.

## Tests

The InnerTube response parser is covered by unit tests run against real API
responses — the fixtures live in `app/src/test/resources`. That matters because
YouTube changes its markup periodically, and without the fixtures a layout
change would look like nothing more than "search stopped finding things".

## The FOR_ISLAND variant

Some Android skins (vivo/OriginOS and other Chinese ROMs) only show their
expanded floating player for packages on a hardcoded allowlist. If your phone
is one of those, building with a different package name makes it work:

```bash
./gradlew assembleRelease -PforIslandPackage=com.tencent.qqmusic
```

Only the `applicationId` changes — the on-device package name. The code, the
`namespace` and everything else stay untouched, so the normal build is not
affected.

Keep in mind:

- This is a workaround for specific skins, not a general solution.
- Such an APK claims someone else's package name, so it cannot be installed
  alongside the original — you will have to remove the original first.
- The `FOR_ISLAND` tag and the reason appear in `versionName` so the variant is
  visible in the package list.
- The normal package is `com.volna.player` and it never changes.

## Disclaimer

The app does not download music: it streams the audio, exactly like any network
player does. Files are written only when you explicitly press Download. Use at
your own risk, in accordance with the
[YouTube Terms of Service](https://www.youtube.com/t/terms).

## License

MIT — see [LICENSE](LICENSE).
