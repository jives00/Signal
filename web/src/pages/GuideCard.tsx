import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ago, buildGuide, getGuide, setGuideSchedule, size, type GuideStatus } from "../api";

/**
 * The NAS guide build on the Dashboard (PLAN §4.3.18 → Dashboard, §5.2 → Jobs): the published
 * build, the last run, Run now, and the nightly schedule. The schedule moves to Settings →
 * Companion when that page arrives.
 */
export default function GuideCard() {
  const [status, setStatus] = useState<GuideStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [time, setTime] = useState("");
  const [zone, setZone] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    getGuide()
      .then((s) => {
        setStatus(s);
        setError(null);
        setTime((t) => t || s.schedule.time);
        setZone((z) => z || s.schedule.timeZone);
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

  async function saveSchedule(event: FormEvent) {
    event.preventDefault();
    try {
      await setGuideSchedule({ time, timeZone: zone.trim() });
      setMessage("Schedule saved.");
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
              : "Builds once a paired device sends its EPG sources"}
          </div>
        </div>
        <div className="card">
          <h2>EPG sources</h2>
          <div className="value">{status.epgSources.length || "None"}</div>
          <div className="detail">
            {status.epgSources.length
              ? `${status.epgSources.join(", ")}. From ${status.inputsFrom ?? "a device"}${status.inputsAt ? `, ${ago(status.inputsAt)}` : ""}`
              : "Sent by a paired TV until settings sync arrives"}
          </div>
        </div>
        <div className="card">
          <h2>Last run</h2>
          <div className="value">{status.lastRun ? (status.lastRun.result ?? "Running") : "Never"}</div>
          <div className="detail">{status.lastRun ? `${ago(status.lastRun.startedAt)}: ${status.lastRun.detail ?? ""}` : "—"}</div>
        </div>
      </div>

      <form className="row schedule" onSubmit={saveSchedule}>
        <button type="button" className="primary" onClick={runNow} disabled={status.running || status.epgSources.length === 0}>Run now</button>
        <span className="detail">Nightly at</span>
        <input type="time" value={time} onChange={(e) => setTime(e.target.value)} aria-label="Build time" />
        <input value={zone} onChange={(e) => setZone(e.target.value)} aria-label="Time zone" placeholder="America/Chicago" />
        <button type="submit">Save</button>
        {status.nextRunAt && <span className="detail">Next: {new Date(status.nextRunAt).toLocaleString()}</span>}
      </form>
      {message && <p className="detail">{message}</p>}
    </section>
  );
}
