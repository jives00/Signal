import { useEffect, useState } from "react";
import { getJobs } from "../admin";
import { ago } from "../api";
import type { JobRun } from "../schema";
import { useSorted } from "../sort";

const names: Record<string, string> = { guide_build: "Guide build", source_refresh: "Source refresh" };

/** Jobs & logs (PLAN §5.8): each step of the nightly and manual runs, with its result. */
export default function Jobs() {
  const [jobs, setJobs] = useState<JobRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const load = () => getJobs().then((j) => { setJobs(j); setError(null); }).catch((e: Error) => setError(e.message));
    load();
    const timer = setInterval(load, 15_000);
    return () => clearInterval(timer);
  }, []);
  const table = useSorted(jobs, {
    job: (j) => names[j.job] ?? j.job, started: (j) => j.startedAt, took: (j) => (j.finishedAt ? j.finishedAt - j.startedAt : null), result: (j) => j.result, detail: (j) => j.detail,
  });

  return (
    <div className="page">
      <h1>Jobs &amp; logs</h1>
      {error && <p className="error-text">{error}</p>}
      {jobs && jobs.length === 0 && <p className="detail">Nothing has run yet.</p>}
      {jobs && jobs.length > 0 && (
        <table className="table">
          <thead><tr>{table.header("job", "Job")}{table.header("started", "Started")}{table.header("took", "Took")}{table.header("result", "Result")}{table.header("detail", "Detail", "wide")}</tr></thead>
          <tbody>
            {table.rows.map((j) => (
              <tr key={j.id}>
                <td>{names[j.job] ?? j.job}</td>
                <td title={new Date(j.startedAt).toLocaleString()}>{ago(j.startedAt)}</td>
                <td>{j.finishedAt ? `${Math.max(1, Math.round((j.finishedAt - j.startedAt) / 1000))}s` : "Running"}</td>
                <td>{j.result === "failed" ? <span className="tag warn">Failed</span> : j.result === "ok" ? "OK" : "—"}</td>
                <td className="wide detail small">{j.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
