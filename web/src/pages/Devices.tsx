import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ago, approveDevice, cleanCode, forgetDevice, getDevices, renameDevice, type Device } from "../api";

/**
 * Devices (PLAN §4.3.18 → Devices & profiles, §5.7 → Pairing): approve a TV by the 6-digit code it
 * shows, then rename or forget it. Profiles and device settings join with settings sync.
 */
export default function Devices() {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    getDevices().then((d) => { setDevices(d); setError(null); }).catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    // Last seen moves while the page is open.
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [load]);

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

  async function rename(device: Device) {
    const name = window.prompt("Device name", device.name)?.trim();
    if (!name || name === device.name) return;
    try { await renameDevice(device.id, name); load(); } catch (e) { setError((e as Error).message); }
  }

  async function forget(device: Device) {
    if (!window.confirm(`Forget ${device.name}? It stops syncing at once and has to pair again.`)) return;
    try { await forgetDevice(device.id); load(); } catch (e) { setError((e as Error).message); }
  }

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
            <tr><th>Name</th><th>Model</th><th>App version</th><th>Last seen</th><th>Paired</th><th /></tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.name}</td>
                <td>{d.model}</td>
                <td>{d.appVersion}</td>
                <td>{ago(d.lastSeenAt)}</td>
                <td>{new Date(d.pairedAt).toLocaleDateString()}</td>
                <td className="actions">
                  <button onClick={() => rename(d)}>Rename</button>
                  <button onClick={() => forget(d)}>Forget</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
