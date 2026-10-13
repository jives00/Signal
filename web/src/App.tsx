import { useEffect, useState } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { backUpNow, getStatus, getSync, health, type Status, type SyncStatus } from "./api";
import { ProfileProvider, ToastProvider, useProfiles, useToast } from "./context";
import Backups from "./pages/Backups";
import Channels from "./pages/Channels";
import Dashboard from "./pages/Dashboard";
import Devices from "./pages/Devices";
import Groups from "./pages/Groups";
import Jobs from "./pages/Jobs";
import Lists from "./pages/Lists";
import Mapping from "./pages/Mapping";
import Reminders from "./pages/Reminders";
import Settings from "./pages/Settings";
import Sources from "./pages/Sources";
import Switch from "./pages/Switch";
import Vod from "./pages/Vod";

/** The sidebar (PLAN §4.3.18). Name cleanup arrives with cleanup rules on the TV. */
const pages: { path: string; title: string; ready: boolean; soon?: string }[] = [
  { path: "/", title: "Dashboard", ready: true },
  { path: "/sources", title: "Sources", ready: true },
  { path: "/channels", title: "Channels", ready: true },
  { path: "/favorites", title: "Favorites & playlists", ready: true },
  { path: "/groups", title: "Groups", ready: true },
  { path: "/cleanup", title: "Name cleanup", ready: false, soon: "Arrives with name cleanup rules on the TV" },
  { path: "/mapping", title: "Guide mapping", ready: true },
  { path: "/switch", title: "Provider switch", ready: true },
  { path: "/vod", title: "Movies & Series", ready: true },
  { path: "/reminders", title: "Reminders", ready: true },
  { path: "/settings", title: "Settings", ready: true },
  { path: "/devices", title: "Devices & profiles", ready: true },
  { path: "/backups", title: "Backups", ready: true },
  { path: "/jobs", title: "Jobs & logs", ready: true },
];

export default function App() {
  return (
    <ProfileProvider>
      <ToastProvider>
        <Shell />
      </ToastProvider>
    </ProfileProvider>
  );
}

function Shell() {
  const [status, setStatus] = useState<Status | null>(null);
  const [sync, setSync] = useState<SyncStatus | null>(null);
  const [failed, setFailed] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const { profiles, profile, setProfile } = useProfiles();
  const { say } = useToast();

  useEffect(() => {
    let live = true;
    const load = () => {
      getStatus()
        .then((s) => { if (live) { setStatus(s); setFailed(false); } })
        .catch(() => { if (live) setFailed(true); });
      getSync().then((s) => { if (live) setSync(s); }).catch(() => undefined);
    };
    load();
    const timer = setInterval(load, 30_000);
    return () => { live = false; clearInterval(timer); };
  }, []);

  // The dot: the companion and its database, then whether the shown profile's devices have synced lately.
  const devicesHere = profiles.find((p) => p.id === profile)?.devices.length ?? 0;
  const dotTitle = failed ? "Companion unreachable" : status ? `Companion ${status.version} · database ${status.database}${sync ? ` · revision ${sync.rev}` : ""}` : "Connecting…";

  return (
    <div className="layout">
      <aside className={menuOpen ? "sidebar open" : "sidebar"}>
        <div className="row brand">
          <div className="wordmark">SIGNAL<span>.</span></div>
          <button className="link menu" onClick={() => setMenuOpen(!menuOpen)} aria-expanded={menuOpen}>Menu</button>
        </div>
        <nav className="nav" onClick={() => setMenuOpen(false)}>
          {pages.map((p) =>
            p.ready ? (
              <NavLink key={p.path} to={p.path} end>{p.title}</NavLink>
            ) : (
              <span key={p.path} className="soon" title={p.soon}>{p.title}</span>
            ),
          )}
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          {profiles.length > 1 && (
            <label className="picker">
              <span className="detail">Profile</span>
              <select value={profile} onChange={(e) => setProfile(e.target.value)}>
                {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}{p.devices.length ? ` (${p.devices.length} device${p.devices.length === 1 ? "" : "s"})` : ""}</option>)}
              </select>
            </label>
          )}
          <span className="spacer" />
          <span className={`dot ${health(status, failed)}`} title={dotTitle} />
          <span className="status-text">{failed ? "Companion unreachable" : status ? `Companion ${status.version}${devicesHere ? ` · ${devicesHere} device${devicesHere === 1 ? "" : "s"}` : ""}` : "Connecting…"}</span>
          <button onClick={async () => { try { const s = await backUpNow(); say(`Backed up ${s.items} items`); } catch (e) { say((e as Error).message, true); } }}>Back up now</button>
        </header>
        <Routes>
          <Route path="/" element={<Dashboard status={status} failed={failed} />} />
          <Route path="/sources" element={<Sources />} />
          <Route path="/channels" element={<Channels />} />
          <Route path="/favorites" element={<Lists />} />
          <Route path="/groups" element={<Groups />} />
          <Route path="/mapping" element={<Mapping />} />
          <Route path="/switch" element={<Switch />} />
          <Route path="/vod" element={<Vod />} />
          <Route path="/reminders" element={<Reminders />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/devices" element={<Devices />} />
          <Route path="/backups" element={<Backups />} />
          <Route path="/jobs" element={<Jobs />} />
          <Route path="*" element={<Dashboard status={status} failed={failed} />} />
        </Routes>
      </div>
    </div>
  );
}
