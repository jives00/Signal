import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { addToLibrary, clearVodMatch, editVodCategory, getLibrary, getVodCategories, getVodItems, moved, moveVodCategory, removeFromLibrary, getVodStatus, runVod, searchTmdb, setVodMatch, sortVodCategories, tmdbImage, type VodQuery } from "../admin";
import { ago, size } from "../api";
import { ApplyTo, profileNames, useProfiles, useToast } from "../context";
import type { LibraryRow, TmdbCandidate, VodCategoriesView, VodCategoryView, VodRow, VodRows, VodStatus } from "../schema";
import { SortHeader, useSorted } from "../sort";

const categorySorts = [["PROVIDER", "Provider order"], ["A_Z", "A–Z"], ["Z_A", "Z–A"], ["MANUAL", "Manual"]] as const;

const PAGE = 100;
const statusLabel: Record<VodRow["status"], string> = { matched: "Matched", manual: "Fixed by hand", unmatched: "No match", pending: "Not tried yet" };

/**
 * Video on Demand (PLAN §4.3.11–4.3.12), one page each: the catalog with TMDB matching, the
 * provider's categories, and each profile's Library.
 */
export function VodCatalog() {
  return <div className="page"><h1>Catalog</h1><Catalog /></div>;
}

export function VodCategories() {
  return <div className="page"><h1>Categories</h1><Categories /></div>;
}

export function VodLibrary() {
  return <div className="page"><h1>Library</h1><Library /></div>;
}

/**
 * How far TMDB matching has got, the catalog with each title's match, and Fix match for the ones it
 * got wrong (matches are household-wide). Add to Library goes to the profiles in Apply to.
 */
function Catalog() {
  const { say, save } = useToast();
  const { applyTo, profiles, profile } = useProfiles();
  const [status, setStatus] = useState<VodStatus | null>(null);
  const [page, setPage] = useState<VodRows | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [filter, setFilter] = useState<VodQuery>({});
  const [offset, setOffset] = useState(0);
  const [fixing, setFixing] = useState<VodRow | null>(null);
  const [categories, setCategories] = useState<VodCategoryView[]>([]);

  const loadStatus = useCallback(() => { getVodStatus().then(setStatus).catch((e: Error) => setError(e.message)); }, []);
  const load = useCallback(() => {
    getVodItems({ ...filter, offset, limit: PAGE, profile }).then((p) => { setPage(p); setError(null); }).catch((e: Error) => setError(e.message));
  }, [filter, offset, profile]);
  useEffect(load, [load]);
  useEffect(() => { getVodCategories(profile).then((v) => setCategories(v.categories)).catch(() => setCategories([])); }, [profile]);
  useEffect(() => {
    loadStatus();
    // While matching runs, the counts move.
    const timer = setInterval(loadStatus, 10_000);
    return () => clearInterval(timer);
  }, [loadStatus]);
  useEffect(() => {
    const timer = setTimeout(() => { setFilter((f) => ({ ...f, q: text || undefined })); setOffset(0); }, 300);
    return () => clearTimeout(timer);
  }, [text]);

  const update = (patch: Partial<VodQuery>) => { setFilter((f) => ({ ...f, ...patch })); setOffset(0); };
  const start = async () => {
    try { await runVod(); say("Matching started"); loadStatus(); } catch (e) { say((e as Error).message, true); }
  };
  const done = () => { setFixing(null); load(); loadStatus(); };

  // One entry per name: the filter, like hiding and renaming, covers that name in every source.
  // Hidden categories have no titles in the catalog, so they're left out.
  const categoryOptions = [...new Map(categories.filter((c) => !c.hidden && (!filter.kind || c.kind === filter.kind)).map((c) => [c.name, c.title])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1], undefined, { numeric: true, sensitivity: "base" }));
  const kindChanged = (kind: VodQuery["kind"]) => {
    const keep = !filter.category || categories.some((c) => c.name === filter.category && !c.hidden && (!kind || c.kind === kind));
    update({ kind, ...(keep ? {} : { category: undefined }) });
  };

  const pending = status ? status.titles - status.matched - status.manual - status.unmatched : 0;
  return (
    <>
      {status && !status.keySet && (
        <p className="detail">Matching needs a TMDB API key: add one in <Link to="/settings/companion">Settings → Companion</Link>. Until then the TVs show the provider's own posters and categories.</p>
      )}
      {status && (
        <div className="cards">
          <div className="card">
            <h2>Matched to TMDB</h2>
            <div className="value">{(status.matched + status.manual).toLocaleString()} <span className="detail">of {status.titles.toLocaleString()}</span></div>
            <div className="detail">{status.unmatched.toLocaleString()} with no match{pending > 0 ? ` · ${pending.toLocaleString()} not tried yet` : ""}{status.manual > 0 ? ` · ${status.manual} fixed by hand` : ""}</div>
          </div>
          <div className="card">
            <h2>Matching</h2>
            <div className="value">{status.running ? "Running" : status.lastRun?.finishedAt ? ago(status.lastRun.finishedAt) : "Never run"}</div>
            <div className="detail">{status.running ? status.progress : status.lastRun?.detail ?? "Runs nightly after the guide build. A large catalog takes a few nights the first time."}</div>
            {!status.running && status.keySet && <div className="row"><button onClick={start}>Run now</button></div>}
          </div>
          <div className="card">
            <h2>Image cache</h2>
            <div className="value">{size(status.imageCacheBytes)}</div>
            <div className="detail">Posters and backdrops sized for TV, kept on the NAS. The limit is in Settings → Companion.</div>
          </div>
        </div>
      )}

      <div className="filters">
        <input type="search" placeholder="Search titles" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search" />
        <select value={filter.kind ?? ""} onChange={(e) => kindChanged(e.target.value as VodQuery["kind"])} aria-label="Kind">
          <option value="">Movies and series</option>
          <option value="MOVIE">Movies</option>
          <option value="SERIES">Series</option>
        </select>
        <select value={filter.category ?? ""} onChange={(e) => update({ category: e.target.value || undefined })} aria-label="Category">
          <option value="">Every category</option>
          {categoryOptions.map(([name, title]) => <option key={name} value={name}>{title}</option>)}
        </select>
        <select value={filter.status ?? ""} onChange={(e) => update({ status: e.target.value as VodQuery["status"] })} aria-label="Match">
          <option value="">Any match</option>
          <option value="matched">Matched</option>
          <option value="unmatched">No match</option>
          <option value="manual">Fixed by hand</option>
          <option value="pending">Not tried yet</option>
        </select>
      </div>

      {fixing && <FixMatch row={fixing} onDone={done} onCancel={() => setFixing(null)} />}
      <ApplyTo />

      {error && <p className="error-text">{error}</p>}
      {page && (
        <>
          <p className="detail">{page.total.toLocaleString()} titles{page.total > PAGE && ` · ${offset + 1}–${Math.min(offset + PAGE, page.total)}`}</p>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <SortHeader column="title" label="Title" state={filter} onSort={update} />
                  <SortHeader column="kind" label="Kind" state={filter} onSort={update} />
                  <SortHeader column="year" label="Year" state={filter} onSort={update} />
                  <SortHeader column="category" label="Category" state={filter} onSort={update} className="wide" />
                  <SortHeader column="tmdb" label="TMDB" state={filter} onSort={update} />
                  <th />
                </tr>
              </thead>
              <tbody>
                {page.rows.map((r) => (
                  <tr key={r.itemId}>
                    <td>{r.title}{r.name !== r.title && <div className="detail small">{r.name}</div>}</td>
                    <td>{r.kind === "MOVIE" ? "Movie" : "Series"}</td>
                    <td>{r.year ?? ""}</td>
                    <td className="wide">{r.category}</td>
                    <td>
                      {r.tmdbId ? <>{r.tmdbTitle ?? `#${r.tmdbId}`}{r.tmdbYear ? ` (${r.tmdbYear})` : ""}</> : <span className="detail">{statusLabel[r.status]}</span>}
                      {r.status === "manual" && <span className="tag quiet">Fixed</span>}
                    </td>
                    <td className="actions">
                      <button onClick={() => save(`Added to the Library of ${profileNames(applyTo, profiles)}`, () => addToLibrary([r.itemId], applyTo))}>Add to Library</button>
                      <button onClick={() => setFixing(r)}>Fix match</button>
                    </td>
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
    </>
  );
}

/** Search TMDB and pick the right title, or say there's none. */
function FixMatch({ row, onDone, onCancel }: { row: VodRow; onDone: () => void; onCancel: () => void }) {
  const { say } = useToast();
  const [query, setQuery] = useState(row.title);
  const [year, setYear] = useState(row.year ? String(row.year) : "");
  const [results, setResults] = useState<TmdbCandidate[] | null>(null);
  const [busy, setBusy] = useState(false);

  const search = useCallback(async (event?: FormEvent) => {
    event?.preventDefault();
    setBusy(true);
    try { setResults(await searchTmdb(row.kind, query, year ? Number(year) : null)); } catch (e) { say((e as Error).message, true); } finally { setBusy(false); }
  }, [row.kind, query, year, say]);
  useEffect(() => { void search(); }, [row.itemId]); // eslint-disable-line react-hooks/exhaustive-deps

  const pick = async (tmdbId: number | null, message: string) => {
    try { await setVodMatch(row.itemId, tmdbId); say(message); onDone(); } catch (e) { say((e as Error).message, true); }
  };
  const automatic = async () => {
    try { await clearVodMatch(row.itemId); say("Back to automatic: matched again on the next run"); onDone(); } catch (e) { say((e as Error).message, true); }
  };

  return (
    <div className="card form suggestion">
      <h2>Fix match: {row.title}</h2>
      <form className="row" onSubmit={search}>
        <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search TMDB" />
        <input type="number" placeholder="Year" value={year} onChange={(e) => setYear(e.target.value)} aria-label="Year" style={{ width: 90 }} />
        <button type="submit" disabled={busy}>Search</button>
      </form>
      {results && results.length === 0 && <p className="detail">Nothing on TMDB for that. Try another spelling or no year.</p>}
      {results && results.length > 0 && (
        <ol className="suggestions">
          {results.map((c) => (
            <li key={c.id} className="row">
              {c.poster ? <img className="logo big" src={tmdbImage("w500", c.poster) ?? undefined} alt="" loading="lazy" /> : <span className="logo big" />}
              <div style={{ flex: 1 }}>
                <strong>{c.title}</strong>{c.year ? ` (${c.year})` : ""}{c.originalTitle && c.originalTitle !== c.title ? <span className="detail"> · {c.originalTitle}</span> : null}
                {c.overview && <div className="detail small">{c.overview.length > 200 ? `${c.overview.slice(0, 200)}…` : c.overview}</div>}
              </div>
              <button className={c.id === row.tmdbId ? "" : "primary"} onClick={() => pick(c.id, `Matched to ${c.title}`)}>{c.id === row.tmdbId ? "Current" : "Use this"}</button>
            </li>
          ))}
        </ol>
      )}
      <div className="row">
        <button onClick={() => pick(null, "Marked as not on TMDB")}>Not on TMDB</button>
        {row.status === "manual" && <button onClick={automatic}>Back to automatic</button>}
        <button className="link" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

/**
 * The provider's movie and series categories (PLAN §4.3.6 → Categories), like Groups for live TV:
 * hide or show, rename, and filters. These are household-wide: a hidden category's titles are
 * dropped from every TV's catalog, so they're out of browsing, Search and TMDB matching too. Titles
 * show as the shown profile's cleanup makes them. The column's order is household-wide too: with
 * Movies or Series picked, drag or ↑↓ to reorder, which switches the order to Manual, as for groups.
 */
function Categories() {
  const { profile } = useProfiles();
  const { save } = useToast();
  const [data, setData] = useState<VodCategoriesView | null>(null);
  const rows = data?.categories ?? null;
  const [dragging, setDragging] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [kind, setKind] = useState<"" | "MOVIE" | "SERIES">("");
  const [show, setShow] = useState<"visible" | "hidden" | "">("visible");
  const [source, setSource] = useState("");
  const [renamed, setRenamed] = useState(false);

  const load = useCallback(() => {
    getVodCategories(profile).then((r) => { setData(r); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  // Moves go by name among the kind's visible categories, the column the TVs show.
  const order = kind ? [...new Set((rows ?? []).filter((c) => c.kind === kind && !c.hidden).map((c) => c.name))] : [];
  function move(name: string, to: number) {
    if (!data || !kind) return;
    const from = order.indexOf(name);
    if (from < 0 || to < 0 || to >= order.length || from === to) return;
    const next = moved(order, from, to);
    const rank = (c: VodCategoryView) => (c.kind === kind && !c.hidden ? next.indexOf(c.name) : -1);
    const mine = data.categories.filter((c) => rank(c) >= 0).sort((a, b) => rank(a) - rank(b));
    let i = 0;
    setData({ sort: "MANUAL", categories: data.categories.map((c) => (rank(c) >= 0 ? mine[i++] : c)) });
    save(data.sort === "MANUAL" ? "Moved on every TV" : "Moved · category order is now Manual", () => moveVodCategory(kind, name, to, profile), load);
  }

  const edit = (c: VodCategoryView, patch: { hidden?: boolean; customName?: string; clearName?: boolean }, message: string) =>
    save(message, () => editVodCategory({ name: c.name, ...patch }), load);
  const rename = (c: VodCategoryView) => {
    const name = window.prompt("Category name (empty for the provider's)", c.customName ?? c.title);
    if (name === null) return;
    edit(c, name.trim() && name.trim() !== c.name ? { customName: name.trim() } : { clearName: true }, "Renamed on every TV");
  };

  const sources = [...new Map((rows ?? []).map((c) => [c.sourceId, c.sourceName])).entries()];
  const text = filter.toLowerCase();
  const shown = (rows ?? []).filter((c) =>
    (!show || c.hidden === (show === "hidden")) &&
    (!kind || c.kind === kind) &&
    (!source || c.sourceId === source) &&
    (!renamed || c.customName !== null) &&
    (!text || c.title.toLowerCase().includes(text) || c.name.toLowerCase().includes(text)));
  const hiddenCount = rows?.filter((c) => c.hidden).length ?? 0;
  const table = useSorted(shown, {
    title: (c) => c.title, kind: (c) => c.kind, source: (c) => c.sourceName, count: (c) => (c.hidden ? null : c.count),
  });
  // Reordering only makes sense in the order the TVs show.
  const reorder = !!kind && !table.sorted;

  return (
    <>
      <p className="detail">
        For every profile: titles in a hidden category are left out of the TVs' catalogs, Search and TMDB matching. Showing one again
        refreshes its source to bring the titles back, which takes a few minutes. Hiding and renaming go by name, so they cover the
        movie and series categories of every source with that name.
      </p>
      {error && <p className="error-text">{error}</p>}
      {data && rows && (
        <>
          <div className="filters">
            <label>Category order
              <select value={data.sort} onChange={(e) => save("Saved for every TV", () => sortVodCategories(e.target.value), load)}>
                {categorySorts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </label>
            <input type="search" placeholder="Filter categories" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter categories" />
            <select value={kind} onChange={(e) => setKind(e.target.value as typeof kind)} aria-label="Kind">
              <option value="">Movies and series</option>
              <option value="MOVIE">Movies</option>
              <option value="SERIES">Series</option>
            </select>
            <select value={show} onChange={(e) => setShow(e.target.value as typeof show)} aria-label="Hidden">
              <option value="visible">Visible only</option>
              <option value="hidden">Hidden only</option>
              <option value="">Hidden and visible</option>
            </select>
            {sources.length > 1 && (
              <select value={source} onChange={(e) => setSource(e.target.value)} aria-label="Source">
                <option value="">Every source</option>
                {sources.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
              </select>
            )}
            <label className="check"><input type="checkbox" checked={renamed} onChange={(e) => setRenamed(e.target.checked)} /> Renamed only</label>
          </div>
          <p className="detail">
            {shown.length.toLocaleString()} of {rows.length.toLocaleString()} categories · {hiddenCount.toLocaleString()} hidden
            {!kind && " · pick Movies or Series to reorder"}
            {kind && table.sorted && " · click the sorted column again to reorder"}
          </p>
          <div className="table-wrap">
            <table className="table">
              <thead><tr>{reorder && <th />}{table.header("title", "Category")}{table.header("kind", "Kind")}{sources.length > 1 && table.header("source", "Source")}{table.header("count", "Titles")}<th /></tr></thead>
              <tbody>
                {table.rows.map((c) => {
                  const i = order.indexOf(c.name);
                  const movable = reorder && !c.hidden;
                  return (
                  <tr
                    key={`${c.sourceId}|${c.kind}|${c.categoryId}`}
                    className={c.hidden ? "dim" : ""}
                    draggable={movable && !filter}
                    onDragStart={() => setDragging(c.name)}
                    onDragOver={(e) => e.preventDefault()}
                    onDrop={(e) => { e.preventDefault(); if (dragging !== null && movable) move(dragging, i); setDragging(null); }}
                  >
                    {reorder && (
                      <td className="nowrap">
                        {movable && <>
                          <span className="grip" aria-hidden>⋮⋮</span>
                          <button className="link" disabled={i === 0} onClick={() => move(c.name, i - 1)} aria-label={`Move ${c.title} up`}>↑</button>
                          <button className="link" disabled={i === order.length - 1} onClick={() => move(c.name, i + 1)} aria-label={`Move ${c.title} down`}>↓</button>
                        </>}
                      </td>
                    )}
                    <td>
                      {c.title}{c.hidden && <span className="tag quiet">Hidden</span>}{c.customName && <span className="tag quiet">Renamed</span>}
                      {c.name !== c.title && <div className="detail small">{c.name}</div>}
                    </td>
                    <td>{c.kind === "MOVIE" ? "Movies" : "Series"}</td>
                    {sources.length > 1 && <td className="detail">{c.sourceName}</td>}
                    <td>{c.hidden ? "" : c.count.toLocaleString()}</td>
                    <td className="actions">
                      <button onClick={() => edit(c, { hidden: !c.hidden }, c.hidden ? "Shown on every TV · its source is refreshing" : "Hidden on every TV")}>{c.hidden ? "Unhide" : "Hide"}</button>
                      <button onClick={() => rename(c)}>Rename</button>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}

/**
 * The shown profile's Library (PLAN §4.3.11): what the TVs show first in Movies and Series. Titles no
 * source has any more stay, marked unavailable, until removed.
 */
function Library() {
  const { profile, profiles } = useProfiles();
  const { save } = useToast();
  const [rows, setRows] = useState<LibraryRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    getLibrary(profile).then((r) => { setRows(r); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);
  const table = useSorted(rows, { title: (r) => r.title, kind: (r) => r.kind, year: (r) => r.year, added: (r) => r.addedAt || null });

  return (
    <>
      <p className="detail">Added on the TV, or with Add to Library in the <Link className="inline" to="/vod/catalog">Catalog</Link>. Shown for {profileNames([profile], profiles)}.</p>
      {error && <p className="error-text">{error}</p>}
      {rows && rows.length === 0 && <p className="detail">Nothing in the Library.</p>}
      {rows && rows.length > 0 && (
        <table className="table">
          <thead><tr>{table.header("title", "Title")}{table.header("kind", "Kind")}{table.header("year", "Year")}{table.header("added", "Added")}<th /></tr></thead>
          <tbody>
            {table.rows.map((r) => (
              <tr key={r.key}>
                <td>{r.title}{!r.available && <span className="tag warn">Unavailable</span>}</td>
                <td>{r.kind === "MOVIE" ? "Movie" : "Series"}</td>
                <td>{r.year ?? ""}</td>
                <td>{r.addedAt ? new Date(r.addedAt).toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" }) : ""}</td>
                <td className="actions"><button onClick={() => save("Removed from the Library", () => removeFromLibrary(r.key, profile), load)}>Remove</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
