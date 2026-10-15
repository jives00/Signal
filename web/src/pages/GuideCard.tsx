import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ago, getGuide, size, type GuideStatus } from "../api";
import Detail from "../Detail";

/** A job detail like "nightly: 157715 programs, 9113 channels; failed: …" as lines: when and why, then each result. */
function runLines(startedAt: number, detail: string | null): string[] {
  const [trigger, ...rest] = (detail ?? "").split(": ");
  const results = rest.join(": ").split("; ").filter(Boolean);
  return [`${ago(startedAt)}${trigger ? ` (${trigger})` : ""}`, ...results];
}

/**
 * The NAS guide build on the Dashboard (PLAN §4.3.18 → Dashboard, §5.2 → Jobs): the published
 * build and the last run. Refreshing is on Sources, per EPG source; the schedule is in Settings.
 */
export default function GuideCard() {
  const [status, setStatus] = useState<GuideStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

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
          <Detail
            lines={meta ? [
              `${meta.programs.toLocaleString()} programs`,
              `${meta.channels.toLocaleString()} guide channels`,
              size(meta.sizeBytes),
              failures > 0 && `${failures} source${failures > 1 ? "s" : ""} failed`,
            ] : []}
            empty="Builds once a paired TV syncs an EPG source"
          />
        </div>
        <div className="card">
          <h2>EPG sources</h2>
          <div className="value">{status.epgSources.length || "None"}</div>
          <Detail
            lines={status.epgSources.length ? [...status.epgSources, `Last changed by ${status.inputsFrom ?? "a device"}${status.inputsAt ? `, ${ago(status.inputsAt)}` : ""}`] : []}
            empty="Synced from a paired TV"
          />
        </div>
        <div className="card">
          <h2>Last run</h2>
          <div className="value">{status.lastRun ? (status.lastRun.result ?? "Running") : "Never"}</div>
          <Detail lines={status.lastRun ? runLines(status.lastRun.startedAt, status.lastRun.detail) : []} empty="—" />
        </div>
      </div>

      <div className="row schedule">
        <span className="detail">
          Nightly at {status.schedule.time} ({status.schedule.timeZone}){status.nextRunAt ? `, next ${new Date(status.nextRunAt).toLocaleString()}` : ""} · <Link className="inline" to="/settings/companion">change</Link> · <Link className="inline" to="/sources">refresh on Sources</Link>
        </span>
      </div>
    </section>
  );
}
