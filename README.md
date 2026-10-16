# Signal

A personal IPTV player for the Nvidia Shield (Android TV), with an optional companion service on a home NAS. Signal plays the live channels, guide (EPG) and on-demand content from IPTV sources the user supplies (M3U links or Xtream Codes logins). It ships no streams, channel lists or provider data of its own.

- **On the TV:** Home, a Live TV guide, full-screen playback, Movies and Series, Search, reminders, and voice commands. It installs from GitHub Releases and updates itself.
- **The NAS companion** (optional, Docker): builds the guide nightly, syncs settings between TVs, matches movies and series to TMDB, and serves a web admin for managing sources, channels, favorites and guide mappings from a laptop. Without it, the TV does all of this on its own except TMDB matching.

## Voice commands

The remote's mic button can talk to Signal instead of Google Assistant: press it and say a channel, like "switch to NBC". Android TV has no setting for its assistant, so it's turned on once per TV from a computer with adb:

```sh
adb connect <the TV's address>:5555
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.signal.tv
adb shell pm grant com.signal.tv android.permission.WRITE_SECURE_SETTINGS
```

Network debugging has to be on in the TV's Developer options first, and a Chromecast with Google TV pairs over Wireless debugging instead of port 5555. The second command lets Signal take the mic back after it's been force-stopped. To give the mic back to Google Assistant:

```sh
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss
```

Every step, with what to check along the way, is in [docs/voice-commands.md](docs/voice-commands.md). The TV's Settings → Voice commands and the web admin's Devices → Voice commands show the same steps.

## Build

Requirements: JDK 17 and the Android SDK (platform 36). The web admin also needs Node 24 and pnpm.

```sh
./gradlew :androidTvApp:assembleDebug    # debug APK, installs as com.signal.tv.debug
./gradlew :devApp:run                    # desktop dev harness (a dev tool, not a shipped app)
./gradlew check                          # tests, lint and the license check
./gradlew :server:test                   # the NAS companion
pnpm --dir web install && pnpm --dir web test
```

Releases are built by GitHub Actions from `apk-v<version>` tags, and the app updates itself from GitHub Releases.

## License

[CC BY-NC 4.0](LICENSE). Nothing GPL ships in the app: `check` fails on a GPL dependency, and the release workflow checks the APK.

Third-party notices (also in the app, under Settings → About & updates):
- **FFmpeg:** Signal bundles FFmpeg's audio decoders, licensed under the LGPL 2.1 or later and configured without GPL or nonfree components. Its version, source and configure flags are in [ffmpeg/BUILD-INFO.md](ffmpeg/BUILD-INFO.md), and the "FFmpeg (LGPL)" workflow rebuilds it.
- **TMDB:** this product uses the TMDB API but is not endorsed or certified by TMDB.
- **Archivo** typeface: SIL Open Font License ([licenses/OFL-Archivo.txt](licenses/OFL-Archivo.txt)).
- The other bundled libraries (AndroidX Media3, Jetpack Compose, Kotlin, Ktor, OkHttp, SQLDelight, Coil, Vosk, Lucide) are Apache 2.0 or ISC.
