import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ago, buildGuide, getGuide, size, type GuideStatus } from "../api";

/**
 * The NAS guide build on the Dashboard (PLAN §4.3.18 → Dashboard, §5.2 → Jobs): the published
 * build, the last run and Run now. The schedule is in Settings → Companion.
 */
export default function GuideCard() {
  const [status, setStatus] = useState<GuideStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    getGuide()
      .then((s) => {
        setStatus(s);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    // Faster while a build runs, so the card shows it finishing.
    const timer = setInterval(load, status?.running ? 3_000 : 30_000);
    return () => clearInterval(timer);
  }, [load, status?.running]);

  async function runNow() {
    try {
      await buildGuide();
      setMessage("Building…");
      load();
    } catch (e) {
      setMessage((e as Error).message);
    }
  }

  if (error) return <p className="error-text">Guide: {error}</p>;
  if (!status) return null;
  const meta = status.meta;
  const failures = meta ? Object.values(meta.outcomes).filter((o) => o.error).length : 0;

  return (
    <section className="section">
      <h2 className="section-title">Guide</h2>
      <div className="cards">
        <div className="card">
          <h2>Latest build</h2>
          <div className="value">{status.running ? "Building…" : meta ? ago(meta.builtAt) : "None yet"}</div>
          <div className="detail">
            {meta
              ? `${meta.programs.toLocaleString()} programs · ${meta.channels.toLocaleString()} guide channels · ${size(meta.sizeBytes)}${failures ? ` · ${failures} source${failures > 1 ? "s" : ""} failed` : ""}`
              : "Builds once a paired TV syncs an EPG source"}
          </div>
        </div>
        <div className="card">
          <h2>EPG sources</h2>
          <div className="value">{status.epgSources.length || "None"}</div>
          <div className="detail">
            {status.epgSources.length
              ? `${status.epgSources.join(", ")}. Last changed by ${status.inputsFrom ?? "a device"}${status.inputsAt ? `, ${ago(status.inputsAt)}` : ""}`
              : "Synced from a paired TV"}
          </div>
        </div>
        <div className="card">
          <h2>Last run</h2>
          <div className="value">{status.lastRun ? (status.lastRun.result ?? "Running") : "Never"}</div>
          <div className="detail">{status.lastRun ? `${ago(status.lastRun.startedAt)}: ${status.lastRun.detail ?? ""}` : "—"}</div>
        </div>
      </div>

      <div className="row schedule">
        <button type="button" className="primary" onClick={runNow} disabled={status.running || status.epgSources.length === 0}>Run now</button>
        <span className="detail">
          Nightly at {status.schedule.time} ({status.schedule.timeZone}){status.nextRunAt ? `, next ${new Date(status.nextRunAt).toLocaleString()}` : ""} · <Link className="inline" to="/settings">change</Link>
        </span>
      </div>
      {message && <p className="detail">{message}</p>}
    </section>
  );
}
