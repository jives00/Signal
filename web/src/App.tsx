import { useEffect, useState } from "react";
import { Navigate, NavLink, Route, Routes } from "react-router-dom";
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
import { Appearance, CompanionSettings, NameCleanupSettings } from "./pages/Settings";
import Sources from "./pages/Sources";
import Switch from "./pages/Switch";
import VoiceCommands from "./pages/VoiceCommands";
import { VodCatalog, VodCategories, VodLibrary } from "./pages/Vod";

type Page = { path: string; title: string };

/** The sidebar (PLAN §4.3.18): Dashboard, then sections with a header and their pages. */
const nav: { header?: string; pages: Page[] }[] = [
  { pages: [{ path: "/", title: "Dashboard" }] },
  {
    header: "Live TV",
    pages: [
      { path: "/channels", title: "Channels" },
      { path: "/favorites", title: "Favorites & Playlists" },
      { path: "/groups", title: "Groups" },
      { path: "/mapping", title: "EPG Mapping" },
      { path: "/reminders", title: "Reminders" },
    ],
  },
  {
    header: "Video on Demand",
    pages: [
      { path: "/vod/catalog", title: "Catalog" },
      { path: "/vod/categories", title: "Categories" },
      { path: "/vod/library", title: "Library" },
    ],
  },
  {
    header: "Administration",
    pages: [
      { path: "/sources", title: "Sources" },
      { path: "/switch", title: "Provider Switch" },
      { path: "/devices", title: "Devices & Profiles" },
      { path: "/settings/appearance", title: "Appearance" },
      { path: "/settings/name-cleanup", title: "Name Cleanup" },
      { path: "/settings/companion", title: "Companion" },
      { path: "/settings/voice", title: "Voice Commands" },
      { path: "/backups", title: "Backups" },
      { path: "/jobs", title: "Jobs & Logs" },
    ],
  },
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
          {nav.map((group, i) => (
            <div key={group.header ?? i} className="nav-group">
              {group.header && <div className="nav-header">{group.header}</div>}
              {group.pages.map((p) => <NavLink key={p.path} to={p.path} end>{p.title}</NavLink>)}
            </div>
          ))}
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
          <Route path="/vod" element={<Navigate to="/vod/catalog" replace />} />
          <Route path="/vod/catalog" element={<VodCatalog />} />
          <Route path="/vod/categories" element={<VodCategories />} />
          <Route path="/vod/library" element={<VodLibrary />} />
          <Route path="/reminders" element={<Reminders />} />
          <Route path="/settings" element={<Navigate to="/settings/appearance" replace />} />
          <Route path="/settings/appearance" element={<Appearance />} />
          <Route path="/settings/name-cleanup" element={<NameCleanupSettings />} />
          <Route path="/settings/companion" element={<CompanionSettings />} />
          <Route path="/settings/voice" element={<VoiceCommands />} />
          <Route path="/devices" element={<Devices />} />
          <Route path="/backups" element={<Backups />} />
          <Route path="/jobs" element={<Jobs />} />
          <Route path="*" element={<Dashboard status={status} failed={failed} />} />
        </Routes>
      </div>
    </div>
  );
}
