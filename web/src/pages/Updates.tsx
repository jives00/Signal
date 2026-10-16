import { useCallback, useEffect, useState } from "react";
import { getReleases, getSettings, markRelease, putSetting } from "../admin";
import { useProfiles, useToast } from "../context";
import type { ReleasesView } from "../schema";

/** The update_channel profile setting (shared/data/Settings.kt): unset is stable only. */
const ALL = "ALL";

/**
 * Devices & Profiles → Updates (PLAN §4.3.15 → Release channels): which app releases each profile's
 * TVs are offered, and marking releases stable. New releases go out as testing; a profile on
 * "Stable only" gets one once it's marked stable here.
 */
export default function Updates() {
  const { profiles } = useProfiles();
  const { say } = useToast();
  const [channels, setChannels] = useState<Record<string, string | null>>({});
  const [releases, setReleases] = useState<ReleasesView | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const loadChannels = useCallback(() => {
    Promise.all(profiles.map((p) => getSettings(p.id).then((s) => [p.id, s.profile["update_channel"] ?? null] as const)))
      .then((pairs) => setChannels(Object.fromEntries(pairs)))
      .catch((e: Error) => say(e.message, true));
  }, [profiles, say]);
  const loadReleases = useCallback(() => {
    getReleases().then(setReleases).catch((e: Error) => setReleases({ releases: [], tokenSet: false, error: e.message }));
  }, []);
  useEffect(loadChannels, [loadChannels]);
  useEffect(loadReleases, [loadReleases]);

  async function setChannel(profile: string, value: string | null) {
    try {
      await putSetting("profile", "update_channel", value, [profile]);
      setChannels((c) => ({ ...c, [profile]: value }));
      say("Saved");
    } catch (e) { say((e as Error).message, true); }
  }

  async function mark(tag: string, stable: boolean) {
    setBusy(tag);
    try {
      await markRelease(tag, stable);
      say(stable ? `${tag.replace("apk-", "")} is stable` : `${tag.replace("apk-", "")} is back to testing`);
      loadReleases();
    } catch (e) { say((e as Error).message, true); }
    setBusy(null);
  }

  const newestStable = releases?.releases.find((r) => r.stable)?.tag;

  return (
    <section className="section">
      <h2 className="section-title">Updates</h2>
      <p className="detail">
        New releases go out as <b>testing</b>. A profile on <b>Stable only</b> is offered a release once it's marked stable below;
        one on <b>All releases</b> gets every release. A TV is only ever offered a newer version than it has.
      </p>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>Profile</th><th>Updates</th></tr></thead>
          <tbody>
            {profiles.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>
                  <select value={channels[p.id] ?? ""} onChange={(e) => setChannel(p.id, e.target.value || null)} aria-label={`Updates for ${p.name}`}>
                    <option value="">Stable only</option>
                    <option value={ALL}>All releases</option>
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3 className="section-title" style={{ marginTop: 24 }}>Releases</h3>
      {releases?.error && <p className="error-text">{releases.error}</p>}
      {releases && !releases.tokenSet && (
        <p className="detail">To mark releases stable, save a GitHub token with write access to the repo's contents in <a href="/settings/companion">Settings → Companion</a>.</p>
      )}
      {releases && releases.releases.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Version</th><th>Published</th><th>Channel</th><th /></tr></thead>
            <tbody>
              {releases.releases.map((r) => (
                <tr key={r.tag} title={r.notes}>
                  <td>{r.tag.replace("apk-v", "")}{r.tag === newestStable && <span className="detail"> · newest stable</span>}</td>
                  <td>{r.publishedAt ? new Date(r.publishedAt).toLocaleString() : ""}</td>
                  <td>{r.stable ? "Stable" : "Testing"}</td>
                  <td className="actions">
                    {releases.tokenSet && (
                      <button type="button" disabled={busy === r.tag} onClick={() => mark(r.tag, !r.stable)}>
                        {r.stable ? "Back to testing" : "Mark stable"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
