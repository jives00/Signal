import { useCallback, useEffect, useState } from "react";
import { getSports, putSetting } from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { SportsView } from "../schema";

/**
 * Live Sports (live-sports.md): for the profile, which sports the TV's page offers and which groups'
 * channel names it reads for games. When, sport and region are filters on each TV, not here.
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
