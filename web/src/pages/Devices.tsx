import { useCallback, useEffect, useState, type FormEvent } from "react";
import { copySections, getSettings, ownProfile } from "../admin";
import { useProfiles, useToast } from "../context";
import DeviceSettings from "./DeviceSettings";
import VoiceSetup from "./VoiceSetup";
import { useSorted } from "../sort";
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
  const [settingsFor, setSettingsFor] = useState<Device | null>(null);
  const { reloadProfiles, profile } = useProfiles();
  // Where Approve puts the device (PLAN §9.1): "" follows Settings → Companion, else a profile ID,
  // "own", or "new" with a name.
  const [placement, setPlacement] = useState("");
  const [newName, setNewName] = useState("");
  const [copyMain, setCopyMain] = useState(true);
  const [ownByDefault, setOwnByDefault] = useState(false);
  useEffect(() => { getSettings(profile).then((s) => setOwnByDefault(s.companion.newDeviceProfile === "own")).catch(() => setOwnByDefault(false)); }, [profile]);
  const { save } = useToast();

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
    try { await action(); load(); reloadProfiles(); } catch (e) { setError((e as Error).message); }
  }

  function giveOwn(device: Device) {
    if (window.confirm(`Give ${device.name} its own profile? It starts as a copy of its current one, and from then on changes there don't reach other devices.`)) run(() => ownProfile(device.id));
  }

  const [copyFrom, setCopyFrom] = useState<ProfileSummary | null>(null);

  async function approve(event: FormEvent) {
    event.preventDefault();
    if (code.length !== 6 || busy) return;
    setBusy(true);
    try {
      const device = await approveDevice(code,
        placement === "" ? {}
          : placement === "new" ? { newProfile: newName.trim(), copyFrom: copyMain ? "main" : null }
          : { profile: placement });
      const into = placement === "new" ? newName.trim() : profiles.find((p) => p.id === device.profileId)?.name ?? (placement === "own" ? device.name : "its profile");
      setMessage(`Approved ${device.name} into ${into}. It finishes pairing on its own in a moment. For voice commands on it, see Voice commands below.`);
      setCode("");
      setPlacement("");
      setNewName("");
      load();
      reloadProfiles();
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
  const deviceTable = useSorted(devices, {
    name: (d) => d.name, profile: (d) => profileName(d.profileId), model: (d) => d.model, version: (d) => d.appVersion, seen: (d) => d.lastSeenAt, sync: (d) => d.lastSyncAt,
  });
  const profileTable = useSorted(profiles, { name: (p) => p.name, devices: (p) => p.devices.length, items: (p) => p.items });

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
          <button className="primary" type="submit" disabled={code.length !== 6 || busy || (placement === "new" && !newName.trim())}>Approve</button>
        </div>
        <div className="row placement">
          <label>Profile
            <select value={placement} onChange={(e) => setPlacement(e.target.value)}>
              <option value="">{ownByDefault ? "Its own profile (the default)" : "Main (the default)"}</option>
              {profiles.filter((p) => p.id !== "main" || ownByDefault).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              {!ownByDefault && <option value="own">Its own profile, named after it</option>}
              <option value="new">New profile…</option>
            </select>
          </label>
          {placement === "new" && (
            <>
              <label>Name<input value={newName} placeholder="Kids, Bedroom…" onChange={(e) => setNewName(e.target.value)} /></label>
              <label className="check"><input type="checkbox" checked={copyMain} onChange={(e) => setCopyMain(e.target.checked)} /> Start as a copy of Main</label>
            </>
          )}
        </div>
        <p className="detail">Devices on the same profile share favorites, hidden channels and playlists. The default is set in Settings → Companion.</p>
        {message && <p className="detail">{message}</p>}
      </form>

      {error && <p className="error-text">{error}</p>}

      {devices && devices.length === 0 && <p className="detail">No devices yet.</p>}
      {devices && devices.length > 0 && (
        <table className="table">
          <thead>
            <tr>{deviceTable.header("name", "Name")}{deviceTable.header("profile", "Profile")}{deviceTable.header("model", "Model")}{deviceTable.header("version", "App version")}{deviceTable.header("seen", "Last seen")}{deviceTable.header("sync", "Last sync")}<th /></tr>
          </thead>
          <tbody>
            {deviceTable.rows.map((d) => (
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
                  <button onClick={() => setSettingsFor(settingsFor?.id === d.id ? null : d)}>Settings</button>
                  <button onClick={() => giveOwn(d)}>Own profile</button>
                  <button onClick={() => rename(d)}>Rename</button>
                  <button onClick={() => forget(d)}>Forget</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {settingsFor && <DeviceSettings device={settingsFor} onClose={() => setSettingsFor(null)} />}

      <VoiceSetup />

      <section className="section">
        <h2 className="section-title">Profiles</h2>
        <p className="detail">Devices on the same profile share favorites, hidden channels, playlists and the like. Sources and guide mappings are shared by every device.</p>
        <table className="table">
          <thead>
            <tr>{profileTable.header("name", "Profile")}{profileTable.header("devices", "Devices")}{profileTable.header("items", "Settings")}<th /></tr>
          </thead>
          <tbody>
            {profileTable.rows.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>{p.devices.length ? p.devices.join(", ") : "None"}</td>
                <td>{p.items}</td>
                <td className="actions">
                  {profiles.length > 1 && <button onClick={() => setCopyFrom(p)}>Copy to…</button>}
                  <button onClick={() => renameP(p)}>Rename</button>
                  {p.id !== "main" && <button onClick={() => removeP(p)}>Delete</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row"><button onClick={addProfile}>Add profile</button></div>
        {copyFrom && <CopyPanel from={copyFrom} profiles={profiles} onClose={() => setCopyFrom(null)} onCopy={(to, sections, replace) => save(`Copied to ${to.length} profile${to.length === 1 ? "" : "s"}`, () => copySections(copyFrom.id, to, sections, replace), () => { setCopyFrom(null); load(); })} />}
      </section>
    </div>
  );
}

const sections: [string, string][] = [["favorites", "Favorites"], ["playlists", "Playlists"], ["channels", "Renamed and hidden channels"], ["groups", "Group settings"], ["reminders", "Reminders"], ["settings", "Settings"]];

/** Copy to… (PLAN §9.1): whole sections from one profile into others, replacing or merging. */
function CopyPanel({ from, profiles, onClose, onCopy }: {
  from: ProfileSummary;
  profiles: ProfileSummary[];
  onClose: () => void;
  onCopy: (to: string[], sections: string[], replace: boolean) => void;
}) {
  const others = profiles.filter((p) => p.id !== from.id);
  const [to, setTo] = useState<string[]>(others.slice(0, 1).map((p) => p.id));
  const [chosen, setChosen] = useState<string[]>(["favorites", "playlists"]);
  const [replace, setReplace] = useState(false);
  const flip = (list: string[], id: string) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);
  return (
    <div className="card form section">
      <h2>Copy from {from.name}</h2>
      <fieldset><legend>To</legend>{others.map((p) => <label key={p.id} className="check"><input type="checkbox" checked={to.includes(p.id)} onChange={() => setTo(flip(to, p.id))} /> {p.name}</label>)}</fieldset>
      <fieldset><legend>Sections</legend>{sections.map(([id, label]) => <label key={id} className="check"><input type="checkbox" checked={chosen.includes(id)} onChange={() => setChosen(flip(chosen, id))} /> {label}</label>)}</fieldset>
      <label className="check"><input type="checkbox" checked={replace} onChange={(e) => setReplace(e.target.checked)} /> Replace what's there (otherwise merge, keeping the targets' own items)</label>
      <div className="row">
        <button className="primary" disabled={!to.length || !chosen.length} onClick={() => { if (!replace || window.confirm("Replace removes the targets' other items in those sections. Continue?")) onCopy(to, chosen, replace); }}>Copy</button>
        <button onClick={onClose}>Cancel</button>
      </div>
    </div>
  );
}
