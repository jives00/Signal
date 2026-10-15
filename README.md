# Signal

A personal IPTV player for the Nvidia Shield (Android TV), with an optional companion service on a home NAS. Signal plays the live channels, guide (EPG) and on-demand content from IPTV sources the user supplies (M3U links or Xtream Codes logins). It ships no streams, channel lists or provider data of its own.

## Voice commands

The remote's mic button can talk to Signal instead of Google Assistant. Turning it on takes one adb
command per TV: see [docs/voice-commands.md](docs/voice-commands.md).

## Build

Requirements: JDK 17 and the Android SDK (platform 36).

```sh
./gradlew :androidTvApp:assembleDebug    # debug APK, installs as com.signal.tv.debug
./gradlew :devApp:run                    # desktop dev harness (a dev tool, not a shipped app)
./gradlew check                          # tests and lint
```

Releases are built by GitHub Actions from `apk-v<version>` tags, and the app updates itself from GitHub Releases.

## License

[CC BY-NC 4.0](LICENSE).

Third-party notices:
- **FFmpeg** (from milestone 1): Signal bundles an FFmpeg build licensed under the LGPL 2.1 or later, configured without GPL or nonfree components. Its version, source and configure flags are recorded in this repo, and the app's About screen links to them.
- **TMDB:** this product uses the TMDB API but is not endorsed or certified by TMDB.
