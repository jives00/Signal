import { useCallback, useEffect, useRef, useState } from "react";
import { getManualMappings, nextUnmapped, resetMapping, searchGuide, setMapping } from "../admin";
import { useProfiles, useToast } from "../context";
import type { GuideChannelInfo, ManualMapping, QueueView } from "../schema";
import { Program } from "./Channels";
import { useSorted } from "../sort";

/**
 * EPG mapping (PLAN §4.3.18 → 7): a work queue of visible channels with no guide data, favorites
 * first, with suggestions from every EPG source. Accepting one from a non-default source sets that
 * channel's EPG source too. Keys: 1–3 accept, S skips, / searches. Mappings are household-wide.
 */
export default function Mapping() {
  const [tab, setTab] = useState<"queue" | "manual">("queue");
  return (
    <div className="page">
      <h1>EPG mapping</h1>
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === "queue"} className={tab === "queue" ? "on" : ""} onClick={() => setTab("queue")}>Unmapped channels</button>
        <button role="tab" aria-selected={tab === "manual"} className={tab === "manual" ? "on" : ""} onClick={() => setTab("manual")}>Manual mappings</button>
      </div>
      {tab === "queue" ? <Queue /> : <Manual />}
    </div>
  );
}

function Queue() {
  const { profile } = useProfiles();
  const { save } = useToast();
  const [queue, setQueue] = useState<QueueView | null>(null);
  const [skipped, setSkipped] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [results, setResults] = useState<GuideChannelInfo[]>([]);
  const searchBox = useRef<HTMLInputElement>(null);

  const load = useCallback((skip: string[]) => {
    nextUnmapped(profile, skip).then((q) => { setQueue(q); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(() => load(skipped), [load]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (search.trim().length < 2) { setResults([]); return; }
    const t = setTimeout(() => searchGuide(search).then(setResults).catch(() => setResults([])), 300);
    return () => clearTimeout(t);
  }, [search]);

  const channel = queue?.channel;
  const accept = useCallback((g: GuideChannelInfo) => {
    if (!channel) return;
    save(`Mapped ${channel.name} to ${g.displayName} · every device`, () => setMapping(channel.streamId, g.epgSourceId, g.id), () => { setSearch(""); load(skipped); });
  }, [channel, save, load, skipped]);
  const skip = useCallback(() => {
    if (!channel) return;
    const next = [...skipped, channel.streamId];
    setSkipped(next);
    setSearch("");
    load(next);
  }, [channel, skipped, load]);

  // Keyboard shortcuts, except while typing in the search box.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement || e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key === "/") { e.preventDefault(); searchBox.current?.focus(); return; }
      if (e.key === "s" || e.key === "S") { e.preventDefault(); skip(); return; }
      const n = Number(e.key);
      const s = queue?.suggestions[n - 1];
      if (n >= 1 && n <= 3 && s) { e.preventDefault(); accept(s); }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [queue, accept, skip]);

  if (error) return <p className="error-text">{error}</p>;
  if (!queue) return <p className="detail">Loading…</p>;
  if (!channel) return <p className="detail">{skipped.length ? `Nothing left but the ${skipped.length} skipped. ` : "Every visible channel has guide data. "}{skipped.length > 0 && <button className="link" onClick={() => { setSkipped([]); load([]); }}>Start over</button>}</p>;

  return (
    <div className="queue">
      <p className="detail">{queue.remaining.toLocaleString()} visible channels without guide data{skipped.length ? ` · ${skipped.length} skipped` : ""}. Favorites and playlist channels come first.</p>
      <div className="card">
        <div className="row">
          {channel.logo && <img className="logo big" src={channel.logo} alt="" />}
          <div>
            <h2 className="channel-name">{channel.name}</h2>
            <div className="detail">{channel.sourceName} · {channel.group}{channel.tvgId ? ` · tvg-id ${channel.tvgId}` : " · no tvg-id"}</div>
          </div>
        </div>
      </div>

      <h3>Suggestions</h3>
      {queue.suggestions.length === 0 && <p className="detail">No likely matches. Search below, or skip.</p>}
      <ol className="suggestions">
        {queue.suggestions.map((g, i) => (
          <li key={`${g.epgSourceId}/${g.id}`}>
            <button onClick={() => accept(g)}>
              <kbd>{i + 1}</kbd>
              <span className="grow"><strong>{g.displayName}</strong> <span className="detail small">{g.epgSourceName} · {g.id}</span><br /><Program p={g.now} label="Now" /> · <Program p={g.next} label="Next" /></span>
            </button>
          </li>
        ))}
      </ol>

      <div className="row wrap">
        <input ref={searchBox} type="search" placeholder="Search all guide channels ( / )" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search guide channels" />
        <button onClick={skip}>Skip <kbd>S</kbd></button>
      </div>
      <ul className="choices">
        {results.map((g) => (
          <li key={`${g.epgSourceId}/${g.id}`}>
            <button onClick={() => accept(g)}><strong>{g.displayName}</strong> <span className="detail small">{g.epgSourceName} · {g.id}</span><br /><Program p={g.now} label="Now" /></button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Manual() {
  const { save } = useToast();
  const [rows, setRows] = useState<ManualMapping[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const table = useSorted(rows, { channel: (m) => m.channelName, epg: (m) => m.epgSourceName, guide: (m) => m.guideChannelName ?? m.guideChannelId });
  const load = useCallback(() => { getManualMappings().then((r) => { setRows(r); setError(null); }).catch((e: Error) => setError(e.message)); }, []);
  useEffect(load, [load]);

  if (error) return <p className="error-text">{error}</p>;
  if (!rows) return null;
  if (rows.length === 0) return <p className="detail">No manual mappings or EPG source overrides yet.</p>;
  return (
    <table className="table">
      <thead><tr>{table.header("channel", "Channel")}{table.header("epg", "EPG source")}{table.header("guide", "Guide channel")}<th /></tr></thead>
      <tbody>
        {table.rows.map((m) => (
          <tr key={m.userChannelId}>
            <td>{m.channelName}</td>
            <td>{m.epgSourceName}</td>
            <td>{m.guideChannelId ? <>{m.guideChannelName ?? m.guideChannelId} <span className="detail small">{m.guideChannelId}</span></> : <span className="detail">Automatic, in this source</span>}</td>
            <td className="actions"><button onClick={() => save("Back to automatic · every device", () => resetMapping(m.userChannelId), load)}>Reset</button></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
