import { useCallback, useEffect, useState } from "react";
import {
  addToPlaylist, createPlaylist, deletePlaylist, editChannels, getChannels, getLists, moveFavorite, moved, movePlaylist,
  movePlaylistItem, putSetting, removeFavorite, removeFromPlaylist, renamePlaylist, setPlaylistSort,
} from "../admin";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { ChannelRow, ListsView } from "../schema";

const favoriteSorts = [["", "Manual"], ["A_Z", "A–Z"], ["NUMBER", "Number"]] as const;

/**
 * Favorites & playlists (PLAN §4.3.18 → 4): ordered lists with drag to reorder, add by search, and
 * remove. Favorites edits go to every profile in Apply to; a playlist belongs to the shown profile.
 * Favorites and each playlist show in their sort, as on the TV; a move there switches it to Manual.
 */
export default function Lists() {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [lists, setLists] = useState<ListsView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getLists(profile).then((l) => { setLists(l); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  if (error) return <div className="page"><h1>Favorites &amp; playlists</h1><p className="error-text">{error}</p></div>;
  if (!lists) return <div className="page"><h1>Favorites &amp; playlists</h1></div>;
  const applied = `Saved · applied to ${profileNames(applyTo, profiles)}`;

  return (
    <div className="page">
      <h1>Favorites &amp; playlists</h1>
      <ApplyTo />
      <OrderedList
        title="Favorites"
        rows={lists.favorites}
        actions={
          <label className="detail">Sort{" "}
            <select value={lists.favoritesSort ?? ""} onChange={(e) => save(applied, () => putSetting("profile", "favorites_sort", e.target.value || null, applyTo), load)} aria-label="Sort Favorites">
              {favoriteSorts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </label>
        }
        onAdd={(streamIds) => save(applied, () => editChannels(streamIds, applyTo, "favorite"), load)}
        onRemove={(r) => save(applied, () => removeFavorite(r.userChannelId!, applyTo), load)}
        onMove={(r, to) => {
          setLists({ ...lists, favoritesSort: null, favorites: moved(lists.favorites, lists.favorites.indexOf(r), to) });
          save(lists.favoritesSort ? "Moved · Favorites are now in manual order" : "Moved", () => moveFavorite(r.userChannelId!, profile, to), load);
        }}
      />

      {lists.playlists.map((p, i) => (
        <OrderedList
          key={p.id}
          title={p.name}
          rows={p.channels}
          actions={
            <>
              <label className="detail">Sort{" "}
                <select value={p.sort ?? ""} onChange={(e) => save("Saved", () => setPlaylistSort(p.id, profile, e.target.value || null), load)} aria-label={`Sort ${p.name}`}>
                  {favoriteSorts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </label>
              <button className="link" disabled={i === 0} onClick={() => save("Moved", () => movePlaylist(p.id, profile, i - 1), load)} aria-label="Move playlist up">↑</button>
              <button className="link" disabled={i === lists.playlists.length - 1} onClick={() => save("Moved", () => movePlaylist(p.id, profile, i + 1), load)} aria-label="Move playlist down">↓</button>
              <button onClick={() => { const name = window.prompt("Playlist name", p.name)?.trim(); if (name && name !== p.name) save("Renamed", () => renamePlaylist(p.id, profile, name), load); }}>Rename</button>
              <button onClick={() => { if (window.confirm(`Delete the playlist ${p.name}? Its channels stay in their groups.`)) save("Deleted", () => deletePlaylist(p.id, profile), load); }}>Delete</button>
            </>
          }
          onAdd={(streamIds) => save("Added", () => addToPlaylist(p.id, profile, streamIds), load)}
          onRemove={(r) => save("Removed", () => removeFromPlaylist(p.id, profile, [r.userChannelId!]), load)}
          onMove={(r, to) => {
            setLists({ ...lists, playlists: lists.playlists.map((x) => (x.id === p.id ? { ...x, sort: null, channels: moved(x.channels, x.channels.indexOf(r), to) } : x)) });
            save(p.sort ? `Moved · ${p.name} is now in manual order` : "Moved", () => movePlaylistItem(p.id, r.userChannelId!, profile, to), load);
          }}
        />
      ))}

      <div className="row section">
        <button onClick={() => { const name = window.prompt("New playlist name")?.trim(); if (name) save(`Playlist created · ${profileNames(applyTo, profiles)}`, () => createPlaylist(name, applyTo), load); }}>New playlist</button>
      </div>
    </div>
  );
}

function OrderedList({ title, rows, actions, onAdd, onRemove, onMove }: {
  title: string;
  rows: ChannelRow[];
  actions?: React.ReactNode;
  onAdd: (streamIds: string[]) => void;
  onRemove: (row: ChannelRow) => void;
  onMove: (row: ChannelRow, toIndex: number) => void;
}) {
  const { profile } = useProfiles();
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<ChannelRow[]>([]);

  useEffect(() => {
    if (query.trim().length < 2) { setFound([]); return; }
    const t = setTimeout(() => getChannels(profile, { q: query, limit: 8, hidden: "visible" }).then((p) => setFound(p.rows)).catch(() => setFound([])), 300);
    return () => clearTimeout(t);
  }, [query, profile]);

  return (
    <section className="section">
      <div className="row heading"><h2 className="section-title">{title}</h2><span className="detail">{rows.length}</span><span className="spacer" />{actions}</div>
      {rows.length === 0 && <p className="detail">Empty. Add channels below.</p>}
      <ol className="ordered">
        {rows.map((r, i) => (
          <li
            key={r.userChannelId ?? r.streamId}
            draggable
            className={[dragging === i ? "dragging" : "", over === i && dragging !== i ? "over" : "", r.unavailable ? "dim" : ""].join(" ")}
            onDragStart={() => setDragging(i)}
            onDragOver={(e) => { e.preventDefault(); setOver(i); }}
            onDragEnd={() => { setDragging(null); setOver(null); }}
            onDrop={(e) => { e.preventDefault(); if (dragging !== null && dragging !== i) onMove(rows[dragging], i); setDragging(null); setOver(null); }}
          >
            <span className="grip" aria-hidden>⋮⋮</span>
            {r.logo ? <img className="logo" src={r.logo} alt="" loading="lazy" /> : <span className="logo" />}
            <span className="grow">{r.name}{r.unavailable ? <span className="tag warn">Unavailable</span> : <span className="detail small"> {r.sourceName} · {r.group}</span>}</span>
            <button className="link" disabled={i === 0} onClick={() => onMove(r, i - 1)} aria-label={`Move ${r.name} up`}>↑</button>
            <button className="link" disabled={i === rows.length - 1} onClick={() => onMove(r, i + 1)} aria-label={`Move ${r.name} down`}>↓</button>
            <button onClick={() => onRemove(r)}>Remove</button>
          </li>
        ))}
      </ol>
      <div className="adder">
        <input type="search" placeholder={`Add channels to ${title}`} value={query} onChange={(e) => setQuery(e.target.value)} aria-label={`Add channels to ${title}`} />
        {found.length > 0 && (
          <ul className="choices">
            {found.map((c) => (
              <li key={c.streamId}><button onClick={() => { onAdd([c.streamId]); setQuery(""); }}>{c.name} <span className="detail small">{c.sourceName} · {c.group}</span></button></li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
