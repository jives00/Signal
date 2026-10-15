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

Network debugging can be turned off again afterwards; Signal stays the assistant.

Signal stays the assistant through its own updates. A factory reset, or uninstalling and
reinstalling Signal, needs steps 3–5 again.

## Turning it off

```
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss
```

This gives the mic button back to Google Assistant.

## Using it

- **Say a channel's name**, alone or with "switch to", "watch", "go to", "change to" or "put on":
  "switch to fox sports one", "nickelodeon", "watch the weather channel".
- **Initials work spelled out:** "E S P N", "C B S", "N B C".
- **Voice names** give a channel other names to say it by. For example, "fox" for your local FOX
  station, or "sports center" for ESPN. Set them on the TV (long-press OK on the channel → Voice
  names) or in the web admin (Channels → the channel → Voice names). Separate names with commas.
  A voice name wins over everything else, so it's also how to choose which channel a short name
  goes to.
- **A panel shows what's going on** while it listens: the words as they're heard, then the channel.
- **Only visible channels count.** Channels in hidden groups, or hidden themselves, aren't matched.

## When it doesn't work

- **The mic button still opens Google Assistant:** Signal isn't the assistant. Check with the
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
