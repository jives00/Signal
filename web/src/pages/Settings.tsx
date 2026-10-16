import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { getSettings, putCompanionSettings, putSetting } from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { CompanionSettingsView, SettingsView } from "../schema";
import NameCleanup from "./NameCleanup";

/** Languages for audio and subtitles, as the TV offers them (SettingsPlayback.kt). */
export const languages: [string, string][] = [
  ["en", "English"], ["es", "Spanish"], ["fr", "French"], ["de", "German"], ["it", "Italian"], ["pt", "Portuguese"],
  ["nl", "Dutch"], ["pl", "Polish"], ["ru", "Russian"], ["ar", "Arabic"], ["hi", "Hindi"], ["zh", "Chinese"],
  ["ja", "Japanese"], ["ko", "Korean"], ["tr", "Turkish"], ["el", "Greek"], ["sv", "Swedish"],
];

/** A profile setting the TV has, with its choices (PLAN §4.3.15). [null] is the TV's own default. */
const profileSettings: { section: string; key: string; label: string; options: [string | null, string][] }[] = [
  { section: "Live TV & guide", key: "group_sort", label: "Group order", options: [[null, "Provider order"], ["A_Z", "A–Z"], ["Z_A", "Z–A"], ["MANUAL", "Manual"]] },
  { section: "Live TV & guide", key: "channel_sort", label: "Channels in a group", options: [[null, "Provider order"], ["NUMBER", "Number"], ["A_Z", "A–Z"]] },
  { section: "Live TV & guide", key: "favorites_sort", label: "Favorites order", options: [[null, "Manual"], ["A_Z", "A–Z"], ["NUMBER", "Channel number"]] },
  { section: "Live TV & guide", key: "reminder_lead_minutes", label: "Reminder time", options: [["0", "At the start"], [null, "1 minute before"], ["2", "2 minutes before"], ["5", "5 minutes before"]] },
  { section: "Live TV & guide", key: "reminder_auto_switch", label: "When a reminder fires", options: [[null, "Ask"], ["true", "Switch to the channel"]] },
  { section: "Display", key: "start_page", label: "Start page", options: [[null, "Home"], ["LIVE_TV", "Live TV"], ["MOVIES", "Movies"], ["SERIES", "Series"], ["LAST_CHANNEL", "Last channel, full screen"]] },
  { section: "Display", key: "clock_24h", label: "Time format", options: [[null, "The TV's own"], ["false", "12-hour"], ["true", "24-hour"]] },
  { section: "Display", key: "show_numbers", label: "Channel numbers", options: [[null, "Shown"], ["false", "Hidden"]] },
  // Playback (PLAN §4.3.15 → 5). The TV can keep a second audio language; picking one here sets the first only.
  { section: "Playback", key: "audio_languages", label: "Audio language", options: [[null, "The stream's own"], ...languages] },
  { section: "Playback", key: "subtitles", label: "Subtitles", options: [["OFF", "Off"], [null, "Forced only"], ["ALWAYS", "Always"]] },
  { section: "Playback", key: "subtitle_language", label: "Subtitle language", options: [[null, "The TV's language"], ...languages] },
];

/** Loads the settings for the shown profile, and the companion's own. */
function useSettings() {
  const { profile } = useProfiles();
  const [data, setData] = useState<SettingsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    getSettings(profile).then((d) => { setData(d); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);
  return { data, error, load };
}

function SettingsPage({ title, error, children }: { title: string; error: string | null; children?: ReactNode }) {
  return (
    <div className="page">
      <h1>{title}</h1>
      {error ? <p className="error-text">{error}</p> : children}
    </div>
  );
}

/**
 * Settings → Appearance (PLAN §4.3.18 → 11): the TV's settings for the shown profile, in the same
 * sections. Device settings are on each device's page.
 */
export function Appearance() {
  const { applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const { data, error, load } = useSettings();
  const sections = [...new Set(profileSettings.map((s) => s.section))];
  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;

  return (
    <SettingsPage title="Appearance" error={error}>
      {data && <>
        <ApplyTo />
        {sections.map((section) => (
          <section key={section} className="section">
            <h2 className="section-title">{section}</h2>
            <div className="settings">
              {profileSettings.filter((s) => s.section === section).map((s) => (
                <label key={s.key}>
                  <span>{s.label}</span>
                  <select value={(s.key === "audio_languages" ? data.profile[s.key]?.split(",")[0] : data.profile[s.key]) ?? ""} onChange={(e) => save(applied, () => putSetting("profile", s.key, e.target.value || null, applyTo), load)}>
                    {s.options.map(([v, l]) => <option key={l} value={v ?? ""}>{l}</option>)}
                  </select>
                </label>
              ))}
            </div>
          </section>
        ))}
      </>}
    </SettingsPage>
  );
}

/** Settings → Name cleanup (PLAN §4.3.2): channel names and movie and series titles, per profile. */
export function NameCleanupSettings() {
  const { data, error, load } = useSettings();
  return (
    <SettingsPage title="Name cleanup" error={error}>
      {data && <>
        <ApplyTo />
        <NameCleanup saved={data.profile["name_cleanup"]} onSaved={load} />
        <NameCleanup target="VOD" saved={data.profile["vod_name_cleanup"]} onSaved={load} />
      </>}
    </SettingsPage>
  );
}

/** Settings → Companion: the NAS companion's own settings, which only the web admin has. */
export function CompanionSettings() {
  const { say } = useToast();
  const { data, error, load } = useSettings();
  const [companion, setCompanion] = useState<CompanionSettingsView | null>(null);
  useEffect(() => { if (data) setCompanion(data.companion); }, [data]);

  async function saveCompanion(event: FormEvent) {
    event.preventDefault();
    if (!companion) return;
    try { await putCompanionSettings(companion); say("Saved"); load(); } catch (e) { say((e as Error).message, true); }
  }

  return (
    <SettingsPage title="Companion" error={error}>
      {companion && <section className="section">
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
          <label><span>GitHub token</span>
            <input
              type="password"
              autoComplete="off"
              value={companion.githubToken ?? ""}
              placeholder={companion.githubTokenSet ? "Saved: type to replace" : "For marking app releases stable"}
              onChange={(e) => setCompanion({ ...companion, githubToken: e.target.value })}
            />
          </label>
          {companion.githubTokenSet && (
            <div className="row"><button type="button" className="link" onClick={() => setCompanion({ ...companion, githubToken: "" })}>Remove the saved token</button></div>
          )}
          <label><span>Image cache (GB)</span><input type="number" min={1} max={500} value={companion.imageCacheGb} onChange={(e) => setCompanion({ ...companion, imageCacheGb: Number(e.target.value) })} /></label>
          <div className="row"><button className="primary" type="submit">Save</button></div>
        </form>
        <p className="detail small">A device offline longer than the delete retention does a full re-sync when it's back. The TMDB key matches movies and series to TMDB for posters, lists and genres; it can be the same key Trakt uses. The GitHub token marks app releases stable on Devices &amp; Profiles: a fine-grained token for jives00/Signal with Contents read and write.</p>
      </section>}
    </SettingsPage>
  );
}
