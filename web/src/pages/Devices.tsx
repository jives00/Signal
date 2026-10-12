import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  ago, approveDevice, cleanCode, createProfile, deleteProfile, forgetDevice, getDevices, getSync, renameDevice, renameProfile,
  setDeviceProfile, type Device, type ProfileSummary,
} from "../api";

/**
 * Devices & profiles (PLAN §4.3.18, §5.7 → Pairing, §9.1): approve a TV by the 6-digit code it
 * shows, rename or forget it, and choose which profile it shares settings with.
 */
export default function Devices() {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [profiles, setProfiles] = useState<ProfileSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    getDevices().then((d) => { setDevices(d); setError(null); }).catch((e: Error) => setError(e.message));
    getSync().then((s) => setProfiles(s.profiles)).catch(() => setProfiles([]));
  }, []);

  useEffect(() => {
    load();
    // Last seen and last sync move while the page is open.
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    try { await action(); load(); } catch (e) { setError((e as Error).message); }
  }

  async function approve(event: FormEvent) {
    event.preventDefault();
    if (code.length !== 6 || busy) return;
    setBusy(true);
    try {
      const device = await approveDevice(code);
      setMessage(`Approved ${device.name}. It finishes pairing on its own in a moment.`);
      setCode("");
      load();
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function rename(device: Device) {
    const name = window.prompt("Device name", device.name)?.trim();
    if (name && name !== device.name) run(() => renameDevice(device.id, name));
  }

  function forget(device: Device) {
    if (window.confirm(`Forget ${device.name}? It stops syncing at once and has to pair again.`)) run(() => forgetDevice(device.id));
  }

  function move(device: Device, profileId: string) {
    const to = profiles.find((p) => p.id === profileId)?.name ?? "that profile";
    if (window.confirm(`Move ${device.name} to ${to}? Its favorites, hidden channels and other profile settings switch to ${to}'s.`)) {
      run(() => setDeviceProfile(device.id, profileId));
    }
  }

  function addProfile() {
    const name = window.prompt("New profile name, for example Kids or Bedroom")?.trim();
    if (!name) return;
    const copy = window.confirm(`Start ${name} as a copy of Main? Cancel starts it empty.`);
    run(() => createProfile(name, copy ? "main" : null));
  }

  function renameP(p: ProfileSummary) {
    const name = window.prompt("Profile name", p.name)?.trim();
    if (name && name !== p.name) run(() => renameProfile(p.id, name));
  }

  function removeP(p: ProfileSummary) {
    if (window.confirm(`Delete ${p.name}? Its settings go, and its devices move to Main.`)) run(() => deleteProfile(p.id));
  }

  const profileName = (id: string) => profiles.find((p) => p.id === id)?.name ?? id;

  return (
    <div className="page">
      <h1>Devices</h1>

      <form className="card approve" onSubmit={approve}>
        <h2>Approve device</h2>
        <p className="detail">On the TV, choose Connect to NAS (or Settings → NAS &amp; sync → Pair), then enter the code it shows.</p>
        <div className="row">
          <input
            className="code"
            inputMode="numeric"
            autoComplete="off"
            placeholder="000000"
            value={code}
            onChange={(e) => { setCode(cleanCode(e.target.value)); setMessage(null); }}
            aria-label="Pairing code"
          />
          <button className="primary" type="submit" disabled={code.length !== 6 || busy}>Approve</button>
        </div>
        {message && <p className="detail">{message}</p>}
      </form>

      {error && <p className="error-text">{error}</p>}

      {devices && devices.length === 0 && <p className="detail">No devices yet.</p>}
      {devices && devices.length > 0 && (
        <table className="table">
          <thead>
            <tr><th>Name</th><th>Profile</th><th>Model</th><th>App version</th><th>Last seen</th><th>Last sync</th><th /></tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.name}</td>
                <td>
                  {profiles.length > 1 ? (
                    <select value={d.profileId} onChange={(e) => move(d, e.target.value)} aria-label={`Profile for ${d.name}`}>
                      {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select>
                  ) : profileName(d.profileId)}
                </td>
                <td>{d.model}</td>
                <td>{d.appVersion}</td>
                <td>{ago(d.lastSeenAt)}</td>
                <td>{d.lastSyncAt ? ago(d.lastSyncAt) : "Never"}</td>
                <td className="actions">
                  <button onClick={() => rename(d)}>Rename</button>
                  <button onClick={() => forget(d)}>Forget</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <section className="section">
        <h2 className="section-title">Profiles</h2>
        <p className="detail">Devices on the same profile share favorites, hidden channels, playlists and the like. Sources and guide mappings are shared by every device.</p>
        <table className="table">
          <thead>
            <tr><th>Profile</th><th>Devices</th><th>Settings</th><th /></tr>
          </thead>
          <tbody>
            {profiles.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>{p.devices.length ? p.devices.join(", ") : "None"}</td>
                <td>{p.items}</td>
                <td className="actions">
                  <button onClick={() => renameP(p)}>Rename</button>
                  {p.id !== "main" && <button onClick={() => removeP(p)}>Delete</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row"><button onClick={addProfile}>Add profile</button></div>
      </section>
    </div>
  );
}
