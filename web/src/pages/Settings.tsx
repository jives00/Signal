import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { getSettings, putCompanionSettings, putSetting } from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { CompanionSettingsView, SettingsView } from "../schema";

/** A profile setting the TV has, with its choices (PLAN §4.3.15). [null] is the TV's own default. */
const profileSettings: { section: string; key: string; label: string; options: [string | null, string][] }[] = [
  { section: "Live TV & guide", key: "group_sort", label: "Group order", options: [[null, "Provider order"], ["A_Z", "A–Z"], ["Z_A", "Z–A"], ["MANUAL", "Manual"]] },
  { section: "Live TV & guide", key: "channel_sort", label: "Channels in a group", options: [[null, "Provider order"], ["NUMBER", "Number"], ["A_Z", "A–Z"]] },
  { section: "Live TV & guide", key: "favorites_sort", label: "Favorites order", options: [[null, "Manual"], ["A_Z", "A–Z"], ["NUMBER", "Channel number"]] },
  { section: "Live TV & guide", key: "reminder_lead_minutes", label: "Reminder time", options: [["0", "At the start"], [null, "1 minute before"], ["2", "2 minutes before"], ["5", "5 minutes before"]] },
  { section: "Live TV & guide", key: "reminder_auto_switch", label: "When a reminder fires", options: [[null, "Ask"], ["true", "Switch to the channel"]] },
  { section: "Appearance", key: "start_page", label: "Start page", options: [[null, "Home"], ["LIVE_TV", "Live TV"], ["MOVIES", "Movies"], ["SERIES", "Series"], ["LAST_CHANNEL", "Last channel, full screen"]] },
  { section: "Appearance", key: "clock_24h", label: "Time format", options: [[null, "The TV's own"], ["false", "12-hour"], ["true", "24-hour"]] },
  { section: "Appearance", key: "show_numbers", label: "Channel numbers", options: [[null, "Shown"], ["false", "Hidden"]] },
];

/**
 * Settings (PLAN §4.3.18 → 11): the TV's settings for the shown profile, in the same sections, and
 * Settings → Companion, which only the web admin has. Device settings are on each device's page.
 */
export default function Settings() {
  const { profile, applyTo, profiles } = useProfiles();
  const { save, say } = useToast();
  const [data, setData] = useState<SettingsView | null>(null);
  const [companion, setCompanion] = useState<CompanionSettingsView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getSettings(profile).then((d) => { setData(d); setCompanion(d.companion); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  async function saveCompanion(event: FormEvent) {
    event.preventDefault();
    if (!companion) return;
    try { await putCompanionSettings(companion); say("Saved"); load(); } catch (e) { say((e as Error).message, true); }
  }

  if (error) return <div className="page"><h1>Settings</h1><p className="error-text">{error}</p></div>;
  if (!data || !companion) return <div className="page"><h1>Settings</h1></div>;
  const sections = [...new Set(profileSettings.map((s) => s.section))];
  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;

  return (
    <div className="page">
      <h1>Settings</h1>
      <ApplyTo />
      {sections.map((section) => (
        <section key={section} className="section">
          <h2 className="section-title">{section}</h2>
          <div className="settings">
            {profileSettings.filter((s) => s.section === section).map((s) => (
              <label key={s.key}>
                <span>{s.label}</span>
                <select value={data.profile[s.key] ?? ""} onChange={(e) => save(applied, () => putSetting("profile", s.key, e.target.value || null, applyTo), load)}>
                  {s.options.map(([v, l]) => <option key={l} value={v ?? ""}>{l}</option>)}
                </select>
              </label>
            ))}
          </div>
        </section>
      ))}

      <section className="section">
        <h2 className="section-title">Guide data</h2>
        <p className="detail">EPG sources, the default one and time shifts are on <Link className="inline" to="/sources">Sources</Link>; channel mappings on <Link className="inline" to="/mapping">Guide mapping</Link>. Shared by every device.</p>
      </section>

      <section className="section">
        <h2 className="section-title">Companion</h2>
        <p className="detail">The NAS companion's own settings, here instead of in its environment, so changing them needs no restart.</p>
        <form className="settings" onSubmit={saveCompanion}>
          <label><span>Nightly run</span>
            <span className="row">
              <input type="time" value={companion.buildTime} onChange={(e) => setCompanion({ ...companion, buildTime: e.target.value })} aria-label="Nightly run time" />
              <input value={companion.timeZone} onChange={(e) => setCompanion({ ...companion, timeZone: e.target.value })} aria-label="Time zone" placeholder="America/Chicago" />
            </span>
          </label>
          <label><span>Nightly backups to keep</span><input type="number" min={1} max={365} value={companion.snapshotsToKeep} onChange={(e) => setCompanion({ ...companion, snapshotsToKeep: Number(e.target.value) })} /></label>
          <label><span>Keep deletes for (days)</span><input type="number" min={7} max={3650} value={companion.tombstoneDays} onChange={(e) => setCompanion({ ...companion, tombstoneDays: Number(e.target.value) })} /></label>
          <label><span>New devices</span>
            <select value={companion.newDeviceProfile} onChange={(e) => setCompanion({ ...companion, newDeviceProfile: e.target.value as "main" | "own" })}>
              <option value="main">Join Main</option>
              <option value="own">Get their own profile, a copy of Main</option>
            </select>
          </label>
          <label><span>TMDB API key</span>
            <input
              type="password"
              autoComplete="off"
              value={companion.tmdbKey ?? ""}
              placeholder={companion.tmdbKeySet ? "Saved: type to replace" : "API key or read access token"}
              onChange={(e) => setCompanion({ ...companion, tmdbKey: e.target.value })}
            />
          </label>
          {companion.tmdbKeySet && (
            <div className="row"><button type="button" className="link" onClick={() => setCompanion({ ...companion, tmdbKey: "" })}>Remove the saved key</button></div>
          )}
          <label><span>Image cache (GB)</span><input type="number" min={1} max={500} value={companion.imageCacheGb} onChange={(e) => setCompanion({ ...companion, imageCacheGb: Number(e.target.value) })} /></label>
          <div className="row"><button className="primary" type="submit">Save</button></div>
        </form>
        <p className="detail small">A device offline longer than the delete retention does a full re-sync when it's back. The TMDB key matches movies and series to TMDB for posters, lists and genres; it can be the same key Trakt uses.</p>
      </section>
    </div>
  );
}
