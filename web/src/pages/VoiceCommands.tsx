import { useEffect, useState } from "react";
import { getVoiceHelp } from "../admin";
import type { VoiceHelp } from "../schema";
import VoiceSetup from "./VoiceSetup";

/**
 * Settings → Voice Commands: what can be said to Signal through the remote's mic button, and what
 * each does (PLAN §4.3.14 → Voice commands, phase 2). The list comes from the companion
 * (shared/voice/VoiceHelp.kt), the same one the TV shows. The setup steps follow, as on Devices & Profiles.
 */
export default function VoiceCommands() {
  const [help, setHelp] = useState<VoiceHelp | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { getVoiceHelp().then(setHelp, (e: Error) => setError(e.message)); }, []);

  return (
    <div className="page">
      <h1>Voice Commands</h1>
      {help && <p className="detail">{help.intro} Voice is turned on once per TV, from a computer: the steps are at the bottom of this page.</p>}
      {error && <p className="error-text">{error}</p>}
      {help?.groups.map((g) => (
        <section className="section" key={g.title}>
          <h2 className="section-title">{g.title}</h2>
          {g.intro && <p><b>{g.intro}</b></p>}
          <ul className="detail">{g.items.map((item) => <li key={item}>{item}</li>)}</ul>
        </section>
      ))}
      {/* The same steps as on Devices & Profiles, here too: a force stop hands the mic back to Google, and the fix is one of them. */}
      <VoiceSetup title="Setting it up" />
    </div>
  );
}
