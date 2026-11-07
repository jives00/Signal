import { useCallback, useEffect, useState } from "react";
import { getSports, putSetting } from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { SportsView } from "../schema";

/**
 * Live Sports (live-sports.md): for the profile, which sports the TV's page offers and which groups'
 * channel names it reads for games. When, sport and region are filters on each TV, not here. The
 * images are the household's: one per league, ESPN's logo unless changed (live-sports.md → Round 2).
 */
export default function Sports() {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [data, setData] = useState<SportsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [world, setWorld] = useState(false);

  const load = useCallback(() => {
    getSports(profile).then((d) => { setData(d); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  if (error) return <div className="page"><p className="error">{error}</p></div>;
  if (!data) return <div className="page">Loading…</div>;

  const store = (next: Pick<SportsView, "hiddenSports" | "groupsOn" | "groupsOff">) => {
    const empty = !next.hiddenSports.length && !next.groupsOn.length && !next.groupsOff.length;
    save(`Saved · applied to ${profileNames(applyTo, profiles)}`, () => putSetting("profile", "sports", empty ? null : JSON.stringify(next), applyTo), load);
  };
  const toggleSport = (id: string, shown: boolean) =>
    store({ ...data, hiddenSports: shown ? [...data.hiddenSports, id] : data.hiddenSports.filter((s) => s !== id) });
  const toggleGroup = (name: string, on: boolean) =>
    store({
      ...data,
      groupsOn: on ? data.groupsOn.filter((g) => g !== name) : [...data.groupsOn, name],
      groupsOff: on ? [...data.groupsOff, name] : data.groupsOff.filter((g) => g !== name),
    });

  const storeImages = (next: Record<string, string>) =>
    save("Saved · all profiles", () => putSetting("household", "sport_images", Object.keys(next).length ? JSON.stringify(next) : null, [profile]), load);
  const setImage = (id: string, url: string | null) => {
    const next = { ...data.images };
    if (url === null) delete next[id]; else next[id] = url.trim();
    storeImages(next);
  };

  const needle = filter.trim().toLowerCase();
  const groups = data.groups.filter((g) => (world || g.us) && g.name.toLowerCase().includes(needle));

  return (
    <div className="page">
      <h1>Live Sports</h1>
      <ApplyTo />
      <h2>Sports</h2>
      <p className="muted">Sports turned off here don't appear on this profile's Live Sports page.</p>
      <div className="toolbar">
        {data.sports.map((s) => (
          <label key={s.id} className="check"><input type="checkbox" checked={s.shown} onChange={() => toggleSport(s.id, s.shown)} /> {s.label}</label>
        ))}
      </div>
      <h2>Images</h2>
      <p className="muted">Shown beside each game on the TVs, for every profile. Leagues start with ESPN's logo; paste another image's URL, or clear it for the short name instead.</p>
      <table>
        <thead><tr><th>Image</th><th>League</th><th>Image URL</th><th></th></tr></thead>
        <tbody>
          {data.imageRows.map((r) => <ImageRow key={r.id} row={r} onSave={(url) => setImage(r.id, url)} />)}
        </tbody>
      </table>
      <h2>Groups</h2>
      <p className="muted">Channels in these groups have no guide, so their names are read for games. Guide listings are found on every channel either way.</p>
      <div className="toolbar">
        <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter groups" aria-label="Filter groups" />
        <label className="check"><input type="checkbox" checked={world} onChange={(e) => setWorld(e.target.checked)} /> International groups</label>
      </div>
      <table>
        <thead><tr><th>Read</th><th>Group</th></tr></thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g.name}>
              <td><input type="checkbox" checked={g.on} onChange={() => toggleGroup(g.name, g.on)} aria-label={`Read ${g.name}`} /></td>
              <td>{g.name}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** One league's image: the URL edits in place, Save stores it, Default goes back to ESPN's, None shows the short name. */
function ImageRow({ row, onSave }: { row: SportsView["imageRows"][number]; onSave: (url: string | null) => void }) {
  const [url, setUrl] = useState(row.chosen ?? "");
  useEffect(() => setUrl(row.chosen ?? ""), [row.chosen]);
  const changed = url.trim() !== (row.chosen ?? "");
  return (
    <tr>
      <td>{row.url ? <img className="logo" src={row.url} alt="" loading="lazy" /> : <span className="logo">{row.short}</span>}</td>
      <td>{row.label}</td>
      <td>
        <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder={row.defaultUrl ?? "None: the short name"} aria-label={`Image URL for ${row.label}`} style={{ width: "100%" }} />
      </td>
      <td className="actions">
        <button disabled={!changed} onClick={() => onSave(url.trim() ? url.trim() : row.defaultUrl ? "" : null)}>Save</button>
        {row.chosen !== null && <button onClick={() => onSave(null)}>Default</button>}
        {row.defaultUrl && row.chosen !== "" && <button onClick={() => onSave("")}>None</button>}
      </td>
    </tr>
  );
}
