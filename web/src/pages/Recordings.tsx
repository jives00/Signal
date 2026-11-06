import { useCallback, useEffect, useState } from "react";
import { clock, deleteRecording, getChannels, getRecordings, startRecording, stopRecording } from "../admin";
import { useProfiles, useToast } from "../context";
import type { ChannelRow, Recording, RecordingsView } from "../schema";
import { useSorted } from "../sort";

const LENGTHS = [15, 30, 60, 90, 120, 180, 240];

const gigabytes = (bytes: number) => `${(bytes / 2 ** 30).toFixed(bytes < 2 ** 33 ? 2 : 0)} GB`;
const minutes = (ms: number) => `${Math.round(ms / 60_000)} min`;

function status(r: Recording): string {
  switch (r.state) {
    case "WAITING": return "Waiting for a free source";
    case "RECORDING": return `Recording until ${clock(r.stopMs)}`;
    case "DONE": return `Done: ${minutes(r.durationMs)}`;
    case "PARTIAL": return `Partial: ${minutes(r.gapMs)} missing`;
    case "FAILED": return "Failed";
  }
}

/** Recording (dvr.md): start one on a channel now, and see, stop or delete what's there. Scheduling comes with the guide. */
export default function Recordings() {
  const { profile } = useProfiles();
  const { save } = useToast();
  const [view, setView] = useState<RecordingsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [results, setResults] = useState<ChannelRow[]>([]);
  const [channel, setChannel] = useState<ChannelRow | null>(null);
  const [length, setLength] = useState(60);

  const load = useCallback(() => {
    getRecordings(profile).then((v) => { setView(v); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(() => {
    load();
    const timer = setInterval(load, 5_000);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => {
    if (search.trim().length < 2) { setResults([]); return; }
    const timer = setTimeout(() => {
      getChannels(profile, { q: search.trim(), hidden: "visible", limit: 8 }).then((p) => setResults(p.rows)).catch(() => setResults([]));
    }, 300);
    return () => clearTimeout(timer);
  }, [search, profile]);

  const table = useSorted(view?.recordings ?? null, {
    when: (r) => r.startMs, title: (r) => r.title, channel: (r) => r.channelName, status: (r) => r.state, size: (r) => r.bytes, expires: (r) => r.expiresAt,
  });

  const start = () => {
    if (!channel) return;
    void save("Recording started", () => startRecording(profile, channel.streamId, length).then(() => undefined), () => {
      setChannel(null);
      setSearch("");
      load();
    });
  };

  return (
    <div className="page">
      <h1>Recordings</h1>
      <p className="detail">Records on any free source, and never over a TV that's watching. {view && !view.problem && `${gigabytes(view.freeBytes)} free.`}</p>
      {error && <p className="error-text">{error}</p>}
      {view?.problem && <p className="error-text">{view.problem}</p>}

      {view && !view.problem && (
        <div className="toolbar">
          {channel ? (
            <button onClick={() => setChannel(null)}>{channel.name} ✕</button>
          ) : (
            <input type="search" placeholder="Find a channel" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Find a channel" />
          )}
          <label>
            <span>For </span>
            <select value={length} onChange={(e) => setLength(Number(e.target.value))} aria-label="How long">
              {LENGTHS.map((m) => <option key={m} value={m}>{m < 90 ? `${m} minutes` : `${m / 60} hours`}</option>)}
            </select>
          </label>
          <button disabled={!channel} onClick={start}>Record</button>
        </div>
      )}
      {!channel && results.length > 0 && (
        <table className="table">
          <tbody>
            {results.map((c) => (
              <tr key={c.streamId}>
                <td>{c.name}</td>
                <td>{c.now?.title}</td>
                <td className="actions"><button onClick={() => setChannel(c)}>Choose</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {view && view.recordings.length === 0 && <p className="detail">No recordings.</p>}
      {view && view.recordings.length > 0 && (
        <table className="table">
          <thead><tr>{table.header("when", "Started")}{table.header("title", "Program")}{table.header("channel", "Channel")}{table.header("status", "Status")}{table.header("size", "Size")}{table.header("expires", "Expires")}<th /></tr></thead>
          <tbody>
            {table.rows.map((r) => {
              const running = r.state === "WAITING" || r.state === "RECORDING";
              return (
                <tr key={r.id}>
                  <td>{new Date(r.startMs).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })} {clock(r.startMs)}</td>
                  <td>{r.title}</td>
                  <td>{r.channelName}</td>
                  <td>{status(r)}{r.loginName && running ? ` (${r.loginName})` : ""}{r.reason ? <div className="detail">{r.reason}</div> : null}</td>
                  <td>{r.bytes > 0 ? gigabytes(r.bytes) : ""}</td>
                  <td>{r.expiresAt ? new Date(r.expiresAt).toLocaleDateString([], { month: "short", day: "numeric" }) : ""}</td>
                  <td className="actions">
                    {running
                      ? <button onClick={() => void save("Stopped", () => stopRecording(r.id), load)}>Stop</button>
                      : <button onClick={() => void save("Deleted", () => deleteRecording(r.id), load)}>Delete</button>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
