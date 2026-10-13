import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { clearVodMatch, getVodItems, getVodStatus, runVod, searchTmdb, setVodMatch, tmdbImage, type VodQuery } from "../admin";
import { ago, size } from "../api";
import { useToast } from "../context";
import type { TmdbCandidate, VodRow, VodRows, VodStatus } from "../schema";

const PAGE = 100;
const statusLabel: Record<VodRow["status"], string> = { matched: "Matched", manual: "Fixed by hand", unmatched: "No match", pending: "Not tried yet" };

/**
 * Movies & Series (PLAN §4.3.12): how far TMDB matching has got, the catalog with each title's match,
 * and Fix match for the ones it got wrong. Matches are household-wide.
 */
export default function Vod() {
  const { say } = useToast();
  const [status, setStatus] = useState<VodStatus | null>(null);
  const [page, setPage] = useState<VodRows | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [filter, setFilter] = useState<VodQuery>({});
  const [offset, setOffset] = useState(0);
  const [fixing, setFixing] = useState<VodRow | null>(null);

  const loadStatus = useCallback(() => { getVodStatus().then(setStatus).catch((e: Error) => setError(e.message)); }, []);
  const load = useCallback(() => {
    getVodItems({ ...filter, offset, limit: PAGE }).then((p) => { setPage(p); setError(null); }).catch((e: Error) => setError(e.message));
  }, [filter, offset]);
  useEffect(load, [load]);
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

  const pending = status ? status.titles - status.matched - status.manual - status.unmatched : 0;
  return (
    <div className="page">
      <h1>Movies &amp; Series</h1>
      {status && !status.keySet && (
        <p className="detail">Matching needs a TMDB API key: add one in <Link to="/settings">Settings → Companion</Link>. Until then the TVs show the provider's own posters and categories.</p>
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
        <select value={filter.kind ?? ""} onChange={(e) => update({ kind: e.target.value as VodQuery["kind"] })} aria-label="Kind">
          <option value="">Movies and series</option>
          <option value="MOVIE">Movies</option>
          <option value="SERIES">Series</option>
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

      {error && <p className="error-text">{error}</p>}
      {page && (
        <>
          <p className="detail">{page.total.toLocaleString()} titles{page.total > PAGE && ` · ${offset + 1}–${Math.min(offset + PAGE, page.total)}`}</p>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Title</th><th>Kind</th><th>Year</th><th>TMDB</th><th /></tr></thead>
              <tbody>
                {page.rows.map((r) => (
                  <tr key={r.itemId}>
                    <td>{r.name}<div className="detail small">{r.sourceId}</div></td>
                    <td>{r.kind === "MOVIE" ? "Movie" : "Series"}</td>
                    <td>{r.year ?? ""}</td>
                    <td>
                      {r.tmdbId ? <>{r.tmdbTitle ?? `#${r.tmdbId}`}{r.tmdbYear ? ` (${r.tmdbYear})` : ""}</> : <span className="detail">{statusLabel[r.status]}</span>}
                      {r.status === "manual" && <span className="tag quiet">Fixed</span>}
                    </td>
                    <td className="actions"><button onClick={() => setFixing(r)}>Fix match</button></td>
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
    </div>
  );
}

/** Search TMDB and pick the right title, or say there's none. */
function FixMatch({ row, onDone, onCancel }: { row: VodRow; onDone: () => void; onCancel: () => void }) {
  const { say } = useToast();
  const [query, setQuery] = useState(row.name);
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
      <h2>Fix match: {row.name}</h2>
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
