import { useState } from "react";

/**
 * How to turn on voice commands on a TV (PLAN §4.3.14 → Voice commands). It's an adb command once
 * per TV, which is easy to forget, so it lives here next to approving a new device. The full steps,
 * with troubleshooting, are in the repo's docs/voice-commands.md.
 */
const DOC = "https://github.com/jives00/Signal/blob/main/docs/voice-commands.md";
const ON = "adb shell cmd role add-role-holder android.app.role.ASSISTANT com.signal.tv";
const OFF = "adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss";
const CHECK = "adb shell settings get secure voice_interaction_service";

function Command({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="command">
      <code>{text}</code>
      <button type="button" onClick={() => navigator.clipboard?.writeText(text).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); })}>
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

export default function VoiceSetup() {
  return (
    <section className="section voice-setup">
      <h2 className="section-title">Voice commands</h2>
      <p className="detail">
        The remote's mic button can talk to Signal from anywhere ("switch to NBC") instead of Google Assistant. Android TV has
        no setting for it, so it's turned on once per TV from a computer with <a href="https://developer.android.com/tools/releases/platform-tools" target="_blank" rel="noreferrer">adb</a>.
        Every step, with fixes for when it doesn't work, is in <a href={DOC} target="_blank" rel="noreferrer">docs/voice-commands.md</a>.
      </p>
      <ol>
        <li><b>Developer options:</b> on a Shield, Settings → Device Preferences → About, and select Build seven times. On a Chromecast with Google TV, Settings → System → About, and select Android TV OS build seven times.</li>
        <li><b>Network debugging:</b> on a Shield, Developer options → Network debugging. On a Chromecast with Google TV, Developer options → USB debugging.</li>
        <li><b>Connect</b> with the TV's address (Settings → Network &amp; Internet), and choose Always allow on the TV: <Command text="adb connect <the TV's address>:5555" /></li>
        <li><b>Make Signal the assistant:</b> <Command text={ON} /> To check, this should print <code>com.signal.tv/.voice.SignalAssistantService</code>: <Command text={CHECK} /></li>
        <li><b>On the TV, open Signal</b> and allow the microphone. It downloads its speech model once (130 MB); Settings → Voice commands says <b>On</b> when it's ready.</li>
      </ol>
      <p className="detail">Signal stays the assistant through updates; a factory reset or a reinstall needs steps 3–5 again. To give the mic button back to Google Assistant:</p>
      <Command text={OFF} />
      <p className="detail">Give hard-to-say channels easier names with <b>Voice names</b> on the Channels page.</p>
    </section>
  );
}
