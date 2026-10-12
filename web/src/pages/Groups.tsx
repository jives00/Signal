import { useCallback, useEffect, useState } from "react";
import { editGroup, getGroups, moved, moveGroup, putSetting } from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { GroupView, GroupsView } from "../schema";

const groupSorts = [["PROVIDER", "Provider order"], ["A_Z", "A–Z"], ["Z_A", "Z–A"], ["MANUAL", "Manual"]] as const;
const channelSorts = [["DEFAULT", "Profile default"], ["PROVIDER", "Provider order"], ["NUMBER", "Number"], ["A_Z", "A–Z"]] as const;

/**
 * Groups (PLAN §4.3.18 → 5): provider groups with their source and channel count. Hide or unhide,
 * rename, per-group sort, and drag to reorder (which switches the profile to Manual, as on the TV).
 * Group rules arrive with name cleanup.
 */
export default function Groups() {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [data, setData] = useState<GroupsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);
  const [filter, setFilter] = useState("");

  const load = useCallback(() => {
    getGroups(profile).then((d) => { setData(d); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;
  const edit = (g: GroupView, patch: { hidden?: boolean; name?: string; clearName?: boolean; sort?: string }) =>
    save(applied, () => editGroup({ sourceId: g.sourceId, group: g.name, profiles: applyTo, ...patch }), load);

  // Moves count positions among visible groups, the order the TV shows.
  const visible = data?.groups.filter((g) => !g.hidden) ?? [];
  function move(g: GroupView, to: number) {
    if (!data) return;
    const from = visible.indexOf(g);
    if (from < 0 || to < 0 || to >= visible.length) return;
    const order = moved(visible, from, to);
    setData({ ...data, groupSort: "MANUAL", groups: [...order, ...data.groups.filter((x) => x.hidden)] });
    save(data.groupSort === "MANUAL" ? "Moved" : "Moved · group sort is now Manual", () => moveGroup(g.sourceId, g.name, profile, to), load);
  }

  const shown = data?.groups.filter((g) => !filter || g.title.toLowerCase().includes(filter.toLowerCase()) || g.name.toLowerCase().includes(filter.toLowerCase())) ?? [];
  const multiSource = new Set(data?.groups.map((g) => g.sourceId)).size > 1;

  return (
    <div className="page">
      <h1>Groups</h1>
      {error && <p className="error-text">{error}</p>}
      {data && (
        <>
          <div className="filters">
            <label>Group order
              <select value={data.groupSort} onChange={(e) => save(applied, () => putSetting("profile", "group_sort", e.target.value, applyTo), load)}>
                {groupSorts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </label>
            <label>Channels in a group
              <select value={data.channelSort} onChange={(e) => save(applied, () => putSetting("profile", "channel_sort", e.target.value, applyTo), load)}>
                {channelSorts.slice(1).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </label>
            <input type="search" placeholder="Filter groups" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter groups" />
          </div>
          <ApplyTo />
          <table className="table">
            <thead><tr><th /><th>Group</th>{multiSource && <th>Source</th>}<th>Channels</th><th>Sort</th><th /></tr></thead>
            <tbody>
              {shown.map((g) => {
                const i = visible.indexOf(g);
                return (
                  <tr
                    key={`${g.sourceId}|${g.name}`}
                    className={g.hidden ? "dim" : ""}
                    draggable={!g.hidden && !filter}
                    onDragStart={() => setDragging(i)}
                    onDragOver={(e) => e.preventDefault()}
                    onDrop={(e) => { e.preventDefault(); if (dragging !== null && dragging !== i && i >= 0) move(visible[dragging], i); setDragging(null); }}
                  >
                    <td className="nowrap">
                      {!g.hidden && <>
                        <span className="grip" aria-hidden>⋮⋮</span>
                        <button className="link" disabled={i === 0} onClick={() => move(g, i - 1)} aria-label={`Move ${g.title} up`}>↑</button>
                        <button className="link" disabled={i === visible.length - 1} onClick={() => move(g, i + 1)} aria-label={`Move ${g.title} down`}>↓</button>
                      </>}
                    </td>
                    <td>{g.title}{g.customName && <span className="detail small"> ({g.name})</span>}{g.hidden && <span className="tag quiet">Hidden</span>}</td>
                    {multiSource && <td className="detail">{g.sourceName}</td>}
                    <td>{g.count}</td>
                    <td>
                      <select value={g.channelSort ?? "DEFAULT"} onChange={(e) => edit(g, { sort: e.target.value })} aria-label={`Sort ${g.title}`}>
                        {channelSorts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                      </select>
                    </td>
                    <td className="actions">
                      <button onClick={() => edit(g, { hidden: !g.hidden })}>{g.hidden ? "Unhide" : "Hide"}</button>
                      <button onClick={() => { const name = window.prompt("Group name (empty for the provider's)", g.customName ?? g.name); if (name === null) return; edit(g, name.trim() && name.trim() !== g.name ? { name: name.trim() } : { clearName: true }); }}>Rename</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
