import { useCallback, useEffect, useState, type ChangeEvent } from "react";
import { getSnapshotSummary, importSettings, restorePart } from "../admin";
import { backUpNow, deleteSnapshot, getDevices, getSync, itemSummary, type Device, type Snapshot, type SyncStatus } from "../api";
import { useProfiles, useToast } from "../context";
import type { SnapshotSummary } from "../schema";

/**
 * Backups (PLAN §4.3.18 → 13, §9.3): the snapshots with a summary, Back up now, restoring
 * everything, one profile or one device, deleting, and the settings file.
 */
export default function Backups() {
  const { profile, profiles } = useProfiles();
  const { say } = useToast();
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [open, setOpen] = useState<Snapshot | null>(null);
  const [summary, setSummary] = useState<SnapshotSummary | null>(null);
  const [part, setPart] = useState("all");
  const [importTo, setImportTo] = useState(profile);

  const load = useCallback(() => {
    getSync().then(setStatus).catch((e: Error) => say(e.message, true));
    getDevices().then(setDevices).catch(() => undefined);
  }, [say]);
  useEffect(load, [load]);
  useEffect(() => setImportTo(profile), [profile]);

  useEffect(() => {
    setSummary(null);
    setPart("all");
    if (open) getSnapshotSummary(open.id).then(setSummary).catch((e: Error) => say(e.message, true));
  }, [open, say]);

  async function restore() {
    if (!open) return;
    const [kind, id] = part.split(":") as ["all" | "profile" | "device", string | undefined];
    const what = kind === "all" ? "everything" : kind === "profile" ? `the ${profiles.find((p) => p.id === id)?.name ?? id} profile` : `${devices.find((d) => d.id === id)?.name ?? "that device"}'s settings`;
    if (!window.confirm(`Restore ${what} to ${new Date(open.createdAt).toLocaleString()}? Open TVs get it within seconds. A backup of now is taken first.`)) return;
    try {
      const r = await restorePart(open.id, kind, id);
      say(`Restored: ${r.changes} changes`);
      setOpen(null);
      load();
    } catch (e) { say((e as Error).message, true); }
  }

  async function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const name = profiles.find((p) => p.id === importTo)?.name ?? importTo;
    if (!window.confirm(`Import ${file.name}? Its values win and nothing is removed. Profile settings go to ${name}. A backup is taken first.`)) return;
    try {
      const r = await importSettings(importTo, JSON.parse(await file.text()));
      say(`Imported: ${r.changed} changes`);
      load();
    } catch (e) { say(e instanceof SyntaxError ? "That file isn't JSON." : (e as Error).message, true); }
  }

  const profileName = (id: string) => profiles.find((p) => p.id === id)?.name ?? "A deleted profile";
  const deviceName = (id: string) => devices.find((d) => d.id === id)?.name ?? "A forgotten device";

  return (
    <div className="page">
      <h1>Backups</h1>
      {status && <p className="detail">Synced now: {itemSummary(status.counts)} · revision {status.rev}</p>}
      <div className="row wrap">
        <button className="primary" onClick={async () => { try { const s = await backUpNow(); say(`Backed up ${s.items} items`); load(); } catch (e) { say((e as Error).message, true); } }}>Back up now</button>
        <a className="button" href="/api/sync/export" download>Export settings file</a>
        <label className="button file">Import settings file<input type="file" accept="application/json,.json" onChange={onFile} /></label>
        {profiles.length > 1 && (
          <label className="detail">profile settings into
            <select value={importTo} onChange={(e) => setImportTo(e.target.value)}>{profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
          </label>
        )}
      </div>
      <p className="detail small">Settings files include source passwords. Nightly backups are taken after the guide build; manual ones and those taken before a restore stay until deleted.</p>

      {status && status.snapshots.length === 0 && <p className="detail">No backups yet.</p>}
      {status && status.snapshots.length > 0 && (
        <table className="table">
          <thead><tr><th>Backup</th><th>Taken</th><th>Items</th><th /></tr></thead>
          <tbody>
            {status.snapshots.map((s) => (
              <tr key={s.id} className={open?.id === s.id ? "selected" : ""}>
                <td>{s.reason}</td>
                <td>{new Date(s.createdAt).toLocaleString()}</td>
                <td>{s.items}</td>
                <td className="actions">
                  <button onClick={() => setOpen(open?.id === s.id ? null : s)}>Restore…</button>
                  <button onClick={async () => { if (!window.confirm("Delete this backup?")) return; try { await deleteSnapshot(s.id); say("Deleted"); load(); } catch (e) { say((e as Error).message, true); } }}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {open && (
        <div className="card form section">
          <h2>Restore from {new Date(open.createdAt).toLocaleString()}</h2>
          {summary ? <p>{itemSummary(summary.counts)}</p> : <p className="detail">Reading…</p>}
          <label>What to restore
            <select value={part} onChange={(e) => setPart(e.target.value)}>
              <option value="all">Everything</option>
              {summary?.profiles.map((p) => <option key={p} value={`profile:${p}`}>The {profileName(p)} profile</option>)}
              {summary?.devices.map((d) => <option key={d} value={`device:${d}`}>{deviceName(d)}'s device settings</option>)}
            </select>
          </label>
          <div className="row"><button className="primary" onClick={restore}>Restore</button><button onClick={() => setOpen(null)}>Cancel</button></div>
        </div>
      )}
    </div>
  );
}
