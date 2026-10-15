import { useCallback, useEffect, useMemo, useState } from "react";
import {
  clock, editChannels, getChannel, getChannels, getGroups, getLinks, getLists, getSources, preferStream, resetMapping, revealHidden, searchGuide, setMapping,
  type ChannelAction, type ChannelColumn, type ChannelQuery,
} from "../admin";
import { ApplyTo, profileNames, store, stored, useProfiles, useToast } from "../context";
import type { ChannelPage, ChannelRow, GroupView, GuideChannelInfo, LinkView, ProgramBrief, SourcesView } from "../schema";

const PAGE = 100;
const SORT_KEY = "signal.channels.sort";
const COLUMNS: readonly ChannelColumn[] = ["number", "name", "source", "guide", "lists"];

/** The sort last picked on this browser, as `column:dir`. */
function storedSort(): Pick<ChannelQuery, "sort" | "dir"> {
  const [sort, dir] = (stored(SORT_KEY) ?? "").split(":");
  if (!COLUMNS.includes(sort as ChannelColumn)) return {};
  return { sort: sort as ChannelColumn, dir: dir === "desc" ? "desc" : "asc" };
}

export function Program({ p, label }: { p: ProgramBrief | null | undefined; label?: string }) {
  if (!p) return <span className="detail">—</span>;
  return <span>{label && <span className="detail">{label} </span>}{p.title} <span className="detail small">{clock(p.start)}</span></span>;
}

export function GuideCell({ row }: { row: ChannelRow }) {
  const g = row.guide;
  if (!g) return <span className="tag warn">No guide</span>;
  return (
    <span title={`${g.epgSourceName} · ${g.guideChannelId}`}>
      <span className={g.how === "manual" ? "tag" : "tag quiet"}>{g.how === "manual" ? "Manual" : "Auto"}</span>
      <span className="detail small">{g.override ? `${g.epgSourceName} (override)` : g.fallback ? `${g.epgSourceName} (fallback)` : g.epgSourceName}</span>
    </span>
  );
}

/** A column header that sorts the table: ascending, then descending, then back to the app's order. */
function SortHeader({ column, label, filter, onSort, className }: {
  column: ChannelColumn; label: string; filter: ChannelQuery; onSort: (patch: Partial<ChannelQuery>) => void; className?: string;
}) {
  const active = filter.sort === column;
  const next = !active ? { sort: column, dir: "asc" as const } : filter.dir === "asc" ? { sort: column, dir: "desc" as const } : { sort: undefined, dir: undefined };
  return (
    <th className={className} aria-sort={active ? (filter.dir === "desc" ? "descending" : "ascending") : "none"}>
      <button className="sort" onClick={() => onSort(next)}>{label}<span className="arrow" aria-hidden>{active ? (filter.dir === "desc" ? "▼" : "▲") : ""}</span></button>
    </th>
  );
}

/**
 * Channels (PLAN §4.3.18 → 3): every stream across sources, merged with the profile's own record of
 * each. Filters, bulk actions on selected rows, and a detail drawer. Paged on the companion.
 */
export default function Channels() {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [filter, setFilter] = useState<ChannelQuery>(() => ({ hidden: "", guide: "", favorites: true, ...storedSort() }));
  const [text, setText] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<ChannelPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sources, setSources] = useState<SourcesView | null>(null);
  const [playlists, setPlaylists] = useState<{ id: string; name: string }[]>([]);
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [open, setOpen] = useState<string | null>(null);

  // Search waits for a pause in typing.
  useEffect(() => {
    const t = setTimeout(() => { setFilter((f) => ({ ...f, q: text })); setOffset(0); }, 300);
    return () => clearTimeout(t);
  }, [text]);

  const load = useCallback(() => {
    getChannels(profile, { ...filter, offset, limit: PAGE }).then((p) => { setPage(p); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile, filter, offset]);
  useEffect(load, [load]);
  useEffect(() => {
    getSources().then(setSources).catch(() => undefined);
    getLists(profile).then((l) => setPlaylists(l.playlists.map(({ id, name }) => ({ id, name })))).catch(() => undefined);
    getGroups(profile).then((g) => setGroups(g.groups)).catch(() => undefined);
  }, [profile]);
  useEffect(() => setSelected(new Set()), [profile, filter]);
  useEffect(() => store(SORT_KEY, filter.sort ? `${filter.sort}:${filter.dir ?? "asc"}` : ""), [filter.sort, filter.dir]);

  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;
  const bulk = (action: ChannelAction, playlistId?: string) =>
    save(applied, () => editChannels([...selected], applyTo, action, { playlistId }), () => { load(); setSelected(new Set()); });

  const update = (patch: Partial<ChannelQuery>) => { setFilter((f) => ({ ...f, ...patch })); setOffset(0); };
  const rows = page?.rows ?? [];
  // The filter matches the provider's group name, so groups sharing a name across sources collapse.
  const groupOptions = useMemo(() => {
    const byName = new Map<string, string>();
    for (const g of groups) if ((!filter.source || g.sourceId === filter.source) && !byName.has(g.name)) byName.set(g.name, g.title);
    return [...byName].sort((a, b) => a[1].localeCompare(b[1]));
  }, [groups, filter.source]);
  const allChecked = rows.length > 0 && rows.every((r) => selected.has(r.streamId));

  return (
    <div className="page">
      <h1>Channels</h1>
      <div className="filters">
        <input type="search" placeholder="Search names and tvg-ids" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search" />
        <select value={filter.source ?? ""} onChange={(e) => update({ source: e.target.value || undefined, group: undefined })} aria-label="Source">
          <option value="">All sources</option>
          {sources?.sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <select value={filter.group ?? ""} onChange={(e) => update({ group: e.target.value || undefined })} aria-label="Group">
          <option value="">All groups</option>
          {groupOptions.map(([name, title]) => <option key={name} value={name}>{title === name ? name : `${title} (${name})`}</option>)}
        </select>
        <select value={filter.playlist ?? ""} onChange={(e) => update({ playlist: e.target.value || undefined })} aria-label="Playlist">
          <option value="">Any playlist</option>
          {playlists.map((p) => <option key={p.id} value={p.id}>In {p.name}</option>)}
        </select>
        <select value={filter.hidden ?? ""} onChange={(e) => update({ hidden: e.target.value as ChannelQuery["hidden"] })} aria-label="Hidden">
          <option value="">Hidden and visible</option>
          <option value="visible">Visible only</option>
          <option value="hidden">Hidden only</option>
        </select>
        <select value={filter.guide ?? ""} onChange={(e) => update({ guide: e.target.value as ChannelQuery["guide"] })} aria-label="Guide">
          <option value="">Any guide</option>
          <option value="mapped">With guide data</option>
          <option value="unmapped">No guide data</option>
        </select>
        <label className="check"><input type="checkbox" checked={!!filter.favorites} onChange={(e) => update({ favorites: e.target.checked })} /> Favorites</label>
        <label className="check"><input type="checkbox" checked={!!filter.customized} onChange={(e) => update({ customized: e.target.checked })} /> Customized</label>
      </div>

      <ApplyTo />

      {selected.size > 0 && (
        <div className="bulk">
          <strong>{selected.size} selected</strong>
          <button onClick={() => bulk("favorite")}>Favorite</button>
          <button onClick={() => bulk("unfavorite")}>Unfavorite</button>
          <button onClick={() => bulk("hide")}>Hide</button>
          <button onClick={() => bulk("unhide")}>Unhide</button>
          {playlists.length > 0 && (
            <select value="" onChange={(e) => { const [op, id] = e.target.value.split(":"); if (id) bulk(op as ChannelAction, id); }} aria-label="Playlist action">
              <option value="">Playlist…</option>
              {playlists.map((p) => <option key={`a${p.id}`} value={`addToPlaylist:${p.id}`}>Add to {p.name}</option>)}
              {playlists.map((p) => <option key={`r${p.id}`} value={`removeFromPlaylist:${p.id}`}>Remove from {p.name}</option>)}
            </select>
          )}
          <button onClick={() => { if (window.confirm(`Reset ${selected.size} channels to the provider's name, logo and number?`)) bulk("reset"); }}>Reset to provider defaults</button>
          <button className="link" onClick={() => setSelected(new Set())}>Clear</button>
        </div>
      )}

      {error && <p className="error-text">{error}</p>}
      {page && (
        <>
          <p className="detail">{page.total.toLocaleString()} channels{page.total > PAGE && ` · ${offset + 1}–${Math.min(offset + PAGE, page.total)}`}</p>
          <div className="table-wrap">
            <table className="table channels">
              <thead>
                <tr>
                  <th><input type="checkbox" checked={allChecked} onChange={() => setSelected(allChecked ? new Set() : new Set(rows.map((r) => r.streamId)))} aria-label="Select page" /></th>
                  <th />
                  <SortHeader column="number" label="No." filter={filter} onSort={update} />
                  <SortHeader column="name" label="Name" filter={filter} onSort={update} />
                  <SortHeader column="source" label="Source · group" filter={filter} onSort={update} className="wide" />
                  <SortHeader column="guide" label="Guide" filter={filter} onSort={update} />
                  <th className="wide">Now · next</th>
                  <SortHeader column="lists" label="Lists" filter={filter} onSort={update} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.streamId} className={r.hidden || r.groupHidden ? "dim" : ""} onClick={() => setOpen(r.streamId)}>
                    <td onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" checked={selected.has(r.streamId)} onChange={() => setSelected((s) => { const n = new Set(s); if (n.has(r.streamId)) n.delete(r.streamId); else n.add(r.streamId); return n; })} aria-label={`Select ${r.name}`} />
                    </td>
                    <td>{r.logo ? <img className="logo" src={r.logo} alt="" loading="lazy" /> : <span className="logo" />}</td>
                    <td>{r.number}</td>
                    <td title={r.rawName}>{r.name}{r.hidden && <span className="tag quiet">Hidden</span>}{r.groupHidden && <span className="tag quiet">Group hidden</span>}</td>
                    <td className="wide detail">{r.sourceName} · {r.group}</td>
                    <td><GuideCell row={r} /></td>
                    <td className="wide"><Program p={r.now} /><br /><Program p={r.next} label="Next" /></td>
                    <td>{r.favorite && <span className="tag">★</span>}{r.playlists.map((p) => <span key={p} className="tag quiet">{p}</span>)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {page.total > PAGE && (
            <div className="row pager">
              <button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
              <button disabled={offset + PAGE >= page.total} onClick={() => setOffset(offset + PAGE)}>Next</button>
            </div>
          )}
        </>
      )}
      {open && <ChannelDrawer streamId={open} onClose={() => setOpen(null)} onChanged={load} sources={sources} />}
    </div>
  );
}

/** The row detail drawer (PLAN §4.3.18 → Channels): rename, logo, number, guide mapping, the raw name. */
function ChannelDrawer({ streamId, onClose, onChanged, sources }: { streamId: string; onClose: () => void; onChanged: () => void; sources: SourcesView | null }) {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [row, setRow] = useState<ChannelRow | null>(null);
  const [name, setName] = useState("");
  const [logo, setLogo] = useState("");
  const [number, setNumber] = useState("");
  const [search, setSearch] = useState("");
  const [results, setResults] = useState<GuideChannelInfo[]>([]);
  const [epgOverride, setEpgOverride] = useState("");

  const load = useCallback(() => {
    getChannel(profile, streamId).then((r) => {
      setRow(r);
      setName(r.customName ?? "");
      setLogo(r.customLogo ?? "");
      setNumber(r.customNumber?.toString() ?? "");
      setEpgOverride(r.guide?.override ? r.guide.epgSourceId : "");
    }).catch(() => setRow(null));
  }, [profile, streamId]);
  useEffect(load, [load]);

  useEffect(() => {
    if (search.trim().length < 2) { setResults([]); return; }
    const t = setTimeout(() => searchGuide(search).then(setResults).catch(() => setResults([])), 300);
    return () => clearTimeout(t);
  }, [search]);

  const after = () => { load(); onChanged(); };
  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;
  const epgSources = useMemo(() => sources?.epgSources.filter((e) => e.enabled) ?? [], [sources]);

  if (!row) return <aside className="drawer"><button className="link close" onClick={onClose}>Close</button><p className="detail">Loading…</p></aside>;

  return (
    <aside className="drawer" aria-label={`${row.name} details`}>
      <button className="link close" onClick={onClose}>Close</button>
      <h2>{row.name}</h2>
      <p className="detail">{row.sourceName} · {row.group} · No. {row.number}</p>
      <ApplyTo />

      <form className="form" onSubmit={(e) => {
        e.preventDefault();
        save(applied, () => editChannels([row.streamId], applyTo, "set", { values: { customName: name || null, customLogo: logo || null, customNumber: number ? Number(number) : null } }), after);
      }}>
        <label>Name<input value={name} placeholder={row.rawName} onChange={(e) => setName(e.target.value)} /></label>
        <label>Logo URL<input value={logo} placeholder={row.customLogo ? "" : "The provider's logo"} onChange={(e) => setLogo(e.target.value)} /></label>
        <label>Number<input type="number" min={1} value={number} placeholder={String(row.number)} onChange={(e) => setNumber(e.target.value)} /></label>
        <div className="row">
          <button className="primary" type="submit">Save</button>
          <button type="button" onClick={() => save(applied, () => editChannels([row.streamId], applyTo, row.favorite ? "unfavorite" : "favorite"), after)}>{row.favorite ? "Unfavorite" : "Favorite"}</button>
          <button type="button" onClick={() => save(applied, () => editChannels([row.streamId], applyTo, row.hidden ? "unhide" : "hide"), after)}>{row.hidden ? "Unhide" : "Hide"}</button>
          <button type="button" onClick={() => save(applied, () => editChannels([row.streamId], applyTo, "reset"), after)}>Reset</button>
        </div>
      </form>

      <h3>Guide</h3>
      {row.guide ? (
        <p>{row.guide.guideChannelName ?? row.guide.guideChannelId} <span className="detail">in {row.guide.epgSourceName} · {row.guide.how === "manual" ? "mapped by hand" : "matched automatically"}{row.guide.fallback ? " · fallback" : ""}</span></p>
      ) : <p className="detail">No guide data. Search below, or use the guide mapping queue.</p>}
      <p><Program p={row.now} label="Now" /><br /><Program p={row.next} label="Next" /></p>
      <div className="row wrap">
        <select value={epgOverride} onChange={(e) => setEpgOverride(e.target.value)} aria-label="EPG source">
          <option value="">Default EPG source</option>
          {epgSources.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
        </select>
        <button onClick={() => save("Saved · every device", () => (epgOverride ? setMapping(row.streamId, epgOverride, null) : row.userChannelId ? resetMapping(row.userChannelId) : Promise.resolve()), after)}>Set EPG source</button>
        {row.guide?.override && row.userChannelId && <button onClick={() => save("Back to automatic", () => resetMapping(row.userChannelId!), after)}>Reset mapping</button>}
      </div>
      <input type="search" placeholder="Search guide channels to map by hand" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search guide channels" />
      <ul className="choices">
        {results.map((g) => (
          <li key={`${g.epgSourceId}/${g.id}`}>
            <button onClick={() => save("Mapped · every device", () => setMapping(row.streamId, g.epgSourceId, g.id), () => { setSearch(""); after(); })}>
              <strong>{g.displayName}</strong> <span className="detail small">{g.epgSourceName} · {g.id}</span><br /><Program p={g.now} label="Now" />
            </button>
          </li>
        ))}
      </ul>

      {row.userChannelId && <Streams uc={row.userChannelId} onChanged={after} />}

      <h3>Raw name</h3>
      <p className="mono">{revealHidden(row.rawName)}</p>
      {row.tvgId && <p className="detail">tvg-id <span className="mono">{row.tvgId}</span></p>}
    </aside>
  );
}

/** Linked streams and the preferred one (PLAN §4.5): the stream every device plays for this channel. */
function Streams({ uc, onChanged }: { uc: string; onChanged: () => void }) {
  const { save } = useToast();
  const [links, setLinks] = useState<LinkView[] | null>(null);
  const load = useCallback(() => { getLinks(uc).then(setLinks).catch(() => setLinks(null)); }, [uc]);
  useEffect(load, [load]);
  if (!links || links.length < 2) return null;
  return (
    <>
      <h3>Streams</h3>
      <ul className="choices">
        {links.map((l) => (
          <li key={l.streamId}>
            <button disabled={l.preferred || !l.playable} onClick={() => save("Preferred stream set · every device", () => preferStream(uc, l.streamId), () => { load(); onChanged(); })}>
              <span className="grow">{l.preferred && <span className="tag">Preferred</span>} {l.sourceName} · {l.name} <span className="detail small">{l.group}{!l.playable && " · source off or stream gone"}</span></span>
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}
