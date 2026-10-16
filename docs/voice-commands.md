# Voice commands

With voice commands on, the remote's mic button talks to Signal from anywhere on the TV. Press it
once and say a channel, like "switch to NBC" or "ESPN". Signal opens on that channel.

This works by making Signal the TV's **assistant**. The remote only sends its microphone to the
assistant, which is normally Google Assistant. Android TV has no setting to change the assistant,
so it's done once per TV from a computer with **adb**. Everything after that is on the TV.

While Signal is the assistant, Google Assistant doesn't answer the mic button on that TV.
[Turning it off](#turning-it-off) gives it back.

## What you need

- **A computer on the same network as the TV**, with adb. adb is in Google's
  [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools). Unzip it
  and run `adb` from that folder. If Android Studio is installed, it's already in
  `%LOCALAPPDATA%\Android\Sdk\platform-tools` on Windows.
- **Signal 0.2.17 or later** on the TV.
- **The TV's IP address:** Settings → Network & Internet, then the connected network.

## Turning it on

1. **Turn on the TV's developer options** (once per TV).
   - **Nvidia Shield:** Settings → Device Preferences → About. Select **Build** seven times.
   - **Chromecast with Google TV / Google TV Streamer:** Settings → System → About. Select **Android
     TV OS build** seven times.
2. **Turn on debugging over the network.**
   - **Shield:** Settings → Device Preferences → Developer options → **Network debugging**.
   - **Chromecast with Google TV:** Settings → System → Developer options → **USB debugging**. It
     allows network connections too.
   - **Google TV Streamer:** Developer options → **Wireless debugging**. If it asks you to pair,
     choose "Pair device with pairing code", then run `adb pair <address>:<port>` with the address
     and code it shows. Use the port it lists under Wireless debugging in step 3 instead of 5555.
3. **Connect** from the computer:
   ```
   adb connect <the TV's address>:5555
   ```
   The TV asks to allow debugging from this computer: choose **Always allow** and OK. Then run
   `adb devices`: the TV should be listed as `device`, not `unauthorized`.
4. **Make Signal the assistant:**
   ```
   adb shell cmd role add-role-holder android.app.role.ASSISTANT com.signal.tv
   ```
   To check it worked:
   ```
   adb shell settings get secure voice_interaction_service
   ```
   should print `com.signal.tv/.voice.SignalAssistantService`.
5. **On the TV, open Signal** and choose **Allow** when it asks for the microphone.
6. **Wait for the speech model.** Signal downloads it once (about 130 MB) and keeps it.
   **Settings → Voice commands** says **On** when everything's ready. Press the mic button anywhere
   and say a channel.

7. **Let Signal take the mic back after a force stop** (recommended, Signal 0.2.24 or later):
   ```
   adb shell pm grant com.signal.tv android.permission.WRITE_SECURE_SETTINGS
   ```
   Force-stopping Signal (Settings → Apps → Force stop, or an app that closes apps) makes Android
   hand the mic button back to Google Assistant. With this permission, Signal takes it back the
   next time it opens, with no adb needed. **Settings → Voice commands → After a force stop** says
   whether it's allowed.

Network debugging can be turned off again afterwards; Signal stays the assistant.

Signal stays the assistant through its own updates. A factory reset, or uninstalling and
reinstalling Signal, needs steps 3–5 and 7 again.

## Turning it off

```
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss
```

This gives the mic button back to Google Assistant. Signal sees the change and doesn't take the mic
back, even with step 7's permission. Run it while Signal is running (open Signal first), or Signal
can't tell it apart from a force stop.

## Using it

- **Say a channel's name**, alone or with "switch to", "watch", "go to", "change to" or "put on":
  "switch to fox sports one", "nickelodeon", "watch the weather channel".
- **Initials work spelled out:** "E S P N", "C B S", "N B C".
- **Voice names** give a channel other names to say it by. For example, "fox" for your local FOX
  station, or "sports center" for ESPN. Set them on the TV (long-press OK on the channel → Voice
  names) or in the web admin (Channels → the channel → Voice names). Separate names with commas.
  A voice name wins over everything else, so it's also how to choose which channel a short name
  goes to.
- **When a name fits more than one channel,** Signal asks: a panel lists up to three, and OK picks
  one. Back cancels. It goes straight to the channel when there's one clear match: the only channel
  with exactly that name, or the only favorite among them. Channels with the same name count once,
  so streams of one channel in several groups never ask.
- **A panel shows what's going on** while it listens: the words as they're heard, then the channel.
- **Which channels count:** your favorites, your playlists and any channel with a voice name. A
  smaller list is understood much better. With every channel, the recognizer can hear a title or a
  team as channel words. To match every visible channel instead, use Settings → Voice commands →
  Channels to match. With no favorites, playlists or voice names yet, every visible channel counts.
  Hidden channels and groups never do. Games ("watch the Bears game") are found on every channel
  either way.

### Commands

These work on what's showing. Each one says so in the panel when it can't apply.

| Say | What it does |
|---|---|
| "pause", "resume" / "play" | Pauses or resumes a movie or episode. Live TV can't pause, so on live TV Signal says so and keeps playing. |
| "stop" | Stops a movie or episode and goes back. Like "pause", it does nothing on live TV. |
| "back thirty seconds", "skip ahead ten minutes", "rewind", "fast forward" | Skips back or ahead in a movie or episode. On its own, "rewind" or "fast forward" moves 30 seconds. |
| "start over", "start the movie over", "restart this" | Plays a movie or episode from the beginning. |
| "next episode", "next" | The next episode. On live TV, "next" is the next channel. |
| "channel up", "channel down", "next channel" | Changes channel, as the remote's channel buttons do. |
| "last channel", "go to the last channel", "previous channel" | Goes back to the channel before this one. |
| "what's on ESPN", "what's on" | Shows the program on now, without changing channel. With no channel, it's the one playing. |
| "watch Friends", "watch Jeopardy" | Tunes the channel showing that program now. "Play Friends" plays it from Movies & Series instead. |
| "guide", "home", "movies", "series", "search", "settings", "my favorites" | Opens the page. "Go to", "open" and "show me" work in front of them. |
| "captions on", "turn off subtitles", "captions" | Turns captions on or off; "captions" alone switches them. |
| "Spanish audio", "change the audio to French" | Picks the audio track in that language. |

### Games and reminders

| Say | What it does |
|---|---|
| "watch the Steelers game", "put on the Cleveland game" | Tunes the game on now with that team, by nickname or city. When it's on more than one channel, a panel asks which. A game that hasn't started says when and where. |
| "remind me about the Steelers game", "remind me about Jeopardy" | Sets a reminder on the next airing. When several programs match, a panel lists up to three. |

Games need their teams in the guide's title, which the NAS companion's guide adds for most
big-league games. Teams playing in the next four days are recognized best, since Signal learns their
names from the guide.

### Movies and series

| Say | What it does |
|---|---|
| "search Seinfeld", "find the office" | Opens Search with the words filled in. |
| "play Inception", "play the movie Inception" | Plays the title when exactly one has that name: a movie resumes where you left it, a series where you're up to. When several share it, or the words only match part of a title, a panel lists up to three. If nothing matches, Search opens with the words filled in. |
| "play The Office season two episode five" | Plays that episode. |
| "continue Seinfeld", "keep watching The Office" | Picks up from Continue watching, or else where the series is up to. |

**"Watch" or "play":** "watch ___" is live TV: a channel, or the channel showing that program now.
"Play ___" is Movies & Series: "watch Friends" tunes today's airing, "play Friends" plays the series.

Start a title with "play", "search" or "continue": those words are what make Signal listen for
any title, which takes a moment longer. For channels, say "watch" ("watch ESPN") or just the name,
which is faster. A channel's name still wins when it matches exactly. Titles you're likely to say
need no lead-in and are recognized best: what's in Continue watching, your Library, series with new
episodes, and the newest movies and series.

## When it doesn't work

- **The mic button opens Google Assistant again:** Signal was probably force-stopped. With step 7's
  permission, open Signal once and the mic is Signal's again. Without it, check with the
  `settings get` command in step 4, and run the role command again.
- **"Allow the microphone to use voice commands":** open Signal and allow it, or go to Settings →
  Voice commands → Microphone. If Android stopped asking, allow it in the TV's Settings → Apps →
  Signal → Permissions.
- **"Voice isn't ready yet":** the speech model is still downloading or loading. Settings → Voice
  commands shows its progress.
- **It hears the wrong channel:** give the channel a voice name made of ordinary words. Spoken
  letters are the hardest part for the recognizer.
- **`adb connect` fails or says `unauthorized`:** check debugging is on (step 2), that the address
  is right, and accept the prompt on the TV. Turning debugging off and on again resets it.
