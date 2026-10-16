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
      <p className="detail">
        Press the remote's mic button and say a channel, a title or a command. A panel shows the words as they're heard, then
        what Signal did. Voice is turned on once per TV, from a computer: the steps are at the bottom of this page.
      </p>
      {error && <p className="error-text">{error}</p>}
      {help?.groups.map((g) => (
        <section className="section" key={g.title}>
          <h2 className="section-title">{g.title}{g.since && <span className="detail"> · {g.since}</span>}</h2>
          {g.intro && <p className="detail">{g.intro}</p>}
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Say</th><th>What it does</th></tr></thead>
              <tbody>
                {g.commands.map((c) => (
                  <tr key={c.say[0]}>
                    <td>{c.say.map((s) => `"${s}"`).join(", ")}</td>
                    <td>{c.does}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
      {help && help.tips.length > 0 && (
        <section className="section">
          <h2 className="section-title">Tips</h2>
          <ul className="detail">{help.tips.map((t) => <li key={t}>{t}</li>)}</ul>
        </section>
      )}
      {/* The same steps as on Devices & Profiles, here too: a force stop hands the mic back to Google, and the fix is one of them. */}
      <VoiceSetup title="Setting it up" />
    </div>
  );
}
