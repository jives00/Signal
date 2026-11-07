import { useCallback, useEffect, useMemo, useState } from "react";
import type { Recording } from "../schema";
import RecordingPlayer, { canPlay, recordingLine, resumeMs } from "../watch/RecordingPlayer";
import type { RecordingsApi } from "./api";

const when = (ms: number) => new Date(ms).toLocaleString([], { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
const at = (r: Recording) => r.programStart ?? r.startMs;

/** What's on, what's coming, then what's saved, newest first: the TV's order. */
export function ordered(all: Recording[]): Recording[] {
  const running = (r: Recording) => r.state === "RECORDING" || r.state === "WAITING";
  return [
    ...all.filter(running).sort((a, b) => at(a) - at(b)),
    ...all.filter((r) => r.state === "SCHEDULED").sort((a, b) => at(a) - at(b)),
    ...all.filter((r) => !running(r) && r.state !== "SCHEDULED").sort((a, b) => at(b) - at(a)),
  ];
}

/** The viewer's recordings (dvr.md): the profile's list, played from the NAS. */
export default function Recordings({ api }: { api: RecordingsApi }) {
  const [rows, setRows] = useState<Recording[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState<Recording | null>(null);

  const load = useCallback(() => {
    api.list().then((v) => { setRows(ordered(v.recordings)); setError(v.problem ?? null); }).catch((e: Error) => setError(e.message));
  }, [api]);
  useEffect(() => {
    load();
    const timer = setInterval(load, 15_000);
    return () => clearInterval(timer);
  }, [load]);

  const id = playing?.id;
  const url = useMemo(() => (id ? api.playlist(id) : null), [api, id]);
  const onPosition = useCallback((ms: number) => { if (id) api.progress(id, ms); }, [api, id]);

  return (
    <div className="page watch">
      <div className="watch-list">
        {error && <p className="error-text">{error}</p>}
        {rows && rows.length === 0 && <p className="detail">No recordings yet.</p>}
        <ul className="watch-rows">
          {rows?.map((r) => (
            <li key={r.id}>
              <button className={id === r.id ? "selected" : ""} disabled={!canPlay(r)} onClick={() => setPlaying(r)}>
                <span className="grow">
                  <span className="name">{r.title}</span>
                  <span className="detail small now">{r.channelName} · {when(at(r))}</span>
                  <span className="detail small now">{recordingLine(r)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="watch-player">
        {playing && url ? (
          <>
            <RecordingPlayer key={playing.id} url={url} startMs={resumeMs(playing)} headers={api.headers} onPosition={onPosition} />
            <div className="watch-info"><span className="section-title">{playing.title}</span></div>
          </>
        ) : <div className="watch-video"><div className="watch-overlay detail">Pick a recording.</div></div>}
      </div>
    </div>
  );
}
