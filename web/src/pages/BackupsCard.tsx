import { useCallback, useEffect, useState } from "react";
import { ago, backUpNow, deleteSnapshot, getSync, itemSummary, restoreSnapshot, type SyncStatus } from "../api";

/**
 * Settings sync and backups on the Dashboard (PLAN §9.2, §9.3): what's synced, the snapshots, Back up
 * now, restoring everything from one, and the settings file. Restoring one profile or one device's
 * settings comes with the Backups page.
 */
export default function BackupsCard() {
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    getSync().then((s) => { setStatus(s); setError(null); }).catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [load]);

  async function run(action: () => Promise<string>) {
    try { setMessage(await action()); load(); } catch (e) { setMessage((e as Error).message); }
  }

  if (error) return <p className="error-text">Settings sync: {error}</p>;
  if (!status) return null;
  const latest = status.snapshots[0];

  return (
    <section className="section">
      <h2 className="section-title">Settings</h2>
      <div className="cards">
        <div className="card">
          <h2>Synced</h2>
          <div className="value">{itemSummary(status.counts)}</div>
          <div className="detail">Revision {status.rev} · {status.profiles.length} profile{status.profiles.length === 1 ? "" : "s"}</div>
        </div>
        <div className="card">
          <h2>Last backup</h2>
          <div className="value">{latest ? ago(latest.createdAt) : "None yet"}</div>
          <div className="detail">{latest ? `${latest.reason} · ${latest.items} items` : "Nightly after the guide build"}</div>
        </div>
      </div>
      <div className="row">
        <button className="primary" onClick={() => run(async () => { const s = await backUpNow(); return `Backed up ${s.items} items.`; })}>Back up now</button>
        <a className="button" href="/api/sync/export" download>Export settings file</a>
        <span className="detail">The file includes source passwords.</span>
      </div>
      {message && <p className="detail">{message}</p>}
      {status.snapshots.length > 0 && (
        <table className="table">
          <thead>
            <tr><th>Backup</th><th>Taken</th><th>Items</th><th /></tr>
          </thead>
          <tbody>
            {status.snapshots.slice(0, 10).map((s) => (
              <tr key={s.id}>
                <td>{s.reason}</td>
                <td>{new Date(s.createdAt).toLocaleString()}</td>
                <td>{s.items}</td>
                <td className="actions">
                  <button onClick={() => {
                    if (window.confirm(`Restore everything to ${new Date(s.createdAt).toLocaleString()}? Every TV gets it within seconds. A backup of now is taken first.`)) {
                      run(async () => `Restored: ${(await restoreSnapshot(s.id)).changes} changes.`);
                    }
                  }}>Restore</button>
                  <button onClick={() => { if (window.confirm("Delete this backup?")) run(async () => { await deleteSnapshot(s.id); return "Deleted."; }); }}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
