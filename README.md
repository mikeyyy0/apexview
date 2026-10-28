# Apexview

An Android app for watching F1 TV the way a foldable deserves: several feeds at once, placed and
sized however you like, with a live track map, broadcast-style timing cards on the onboards and
team radio, all kept in sync with the picture.

Made for the Pixel Fold's inner screen, and works on other Android phones and tablets too.

> **Unofficial.** Apexview isn't made, endorsed or supported by Formula 1, Formula One
> Management or F1 TV. You need your own F1 TV subscription (Pro for live sessions and
> onboards). Video plays through F1 TV's own DRM with your account; nothing is downloaded or
> unlocked. F1 TV's terms don't cover third-party apps, so use it at your own risk.

## What it does

- **Multiview, your way.** Up to four feeds (broadcast, F1 Live, onboards, data and tracker
  channels). Main + strip, grid, or drag and resize freely with magnetic snapping. Save a layout
  as your default.
- **Track map.** Every car on track, as a map you can move, turn and resize, or as a ribbon
  round the edge of the screen. Turn numbers, start and finish lines. Works on replays and live.
- **Flags on the map:** yellow sectors, safety car and VSC, red flags, and a countdown to an
  announced restart.
- **Tap a car, get its onboard.** Tap a feed first to choose which one it replaces.
- **Timing cards** on onboards: position, sectors (yellow, green, purple), running lap time,
  lap time and gap, like the broadcast's.
- **Team radio** as it's published, by lap, transcribed on the phone, tap to listen.
- **Chequered marks** on the scrubber at lights out and the flag, and a scrubber for live
  streams too.
- **Pick up where you left off** across your last four sessions, layout included.
- **Pop-out** picture-in-picture when you leave the app.
- **Foldable aware:** re-lays out on fold and unfold; on the cover screen, double-tap a feed to
  fill the screen. Laptop mode: half-open with the hinge across, the stream goes up top and a
  timing tower and the track map fill the bottom.
- **What to watch next** on home: what's live, what follows a session you watched (post-race
  show, press conference, highlights), the build-up before a weekend, and last year's race there.
- **All of F1 TV's catalog:** shows, documentaries, press conferences and full races from every
  season back to 1981.
- **Season calendar** in your local time, with optional reminders before each session.

Tap the **?** in the viewer's top bar for a guide to every gesture.

## Install

1. Download the latest `apexview-<version>.apk` from [Releases](../../releases).
2. Open it on your phone and allow installing from that source when Android asks.
3. Sign in with your F1 TV account.

You need an F1 TV plan that includes video (Pro or Premium); a free F1 TV account can't play
anything. In the US, F1 TV Premium comes with Apple TV: link your Apple account to your F1 TV
account on F1 TV's website or app (look for Activate), then sign in to Apexview with that F1 TV
account.

Android 10 or newer.

Apexview checks for a new release once a day when it opens and offers to update itself (Android asks you to confirm the install, and the first time, to allow Apexview to install updates). Settings has a Check for updates button too. A fork can point its updates at its own releases with `UPDATE_REPO` in `app/build.gradle.kts`.

## Build it yourself

You need JDK 17 and the Android SDK (Android Studio brings both).

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Release builds are signed with a key
described in a `keystore.properties` file at the project root (not in the repository):

```properties
storeFile=/path/to/your-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`./gradlew testDebugUnitTest` runs the unit tests (layouts, snapping, timing, the grid and start
line, the track ribbon).

## How it works

- **Playback:** F1 TV's own API and Widevine licence server, with your sign-in. All feeds of a
  session share one multiview play session, as on F1 TV's website.
- **Track map and timing:** F1's live timing (the SignalR Core feed, live) and its timing
  archive (replays), with your F1 TV token. The map is lined up with the picture by the stream's
  own clock, with a nudge in the map settings for anything left over.
- **Team radio:** the clips F1 publishes, transcribed by Android's on-device speech recogniser.

## Privacy

Apexview talks only to F1's servers (with your own sign-in), MultiViewer's public circuit data,
and GitHub to check for updates. There are no analytics and nothing is uploaded in the background.
Your F1 TV sign-in stays on your phone: it isn't included in Android backups or phone-to-phone
transfers, and it's stripped from crash reports.
If the app crashes, the next launch offers to open a GitHub issue with the error filled in; it's
only sent if you submit it.

## Credits

- The F1 TV API and login flow were ported from [eXhumer/f1tv-api](https://github.com/eXhumer/f1tv-api)
  and [eXhumer/eXViewer](https://github.com/eXhumer/eXViewer), both MIT licensed.
- How F1's timing archive and live feed are laid out follows [FastF1](https://github.com/theOehrly/Fast-F1).
- Turn numbers, and the circuit outline before a live session has traced a lap, come from
  [MultiViewer](https://multiviewer.app)'s public circuit data.

## License

MIT; see [LICENSE](LICENSE). Fork it, change it, share it.
