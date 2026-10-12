import { useEffect, useState } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { getStatus, health, type Status } from "./api";
import Dashboard from "./pages/Dashboard";

/** The sidebar (PLAN §4.3.18). Pages without a link yet arrive with their part of milestone 3. */
const pages: { path: string; title: string; ready: boolean }[] = [
  { path: "/", title: "Dashboard", ready: true },
  { path: "/sources", title: "Sources", ready: false },
  { path: "/channels", title: "Channels", ready: false },
  { path: "/favorites", title: "Favorites & playlists", ready: false },
  { path: "/groups", title: "Groups", ready: false },
  { path: "/cleanup", title: "Name cleanup", ready: false },
  { path: "/mapping", title: "Guide mapping", ready: false },
  { path: "/switch", title: "Provider switch", ready: false },
  { path: "/reminders", title: "Reminders", ready: false },
  { path: "/settings", title: "Settings", ready: false },
  { path: "/devices", title: "Devices & profiles", ready: false },
  { path: "/backups", title: "Backups", ready: false },
  { path: "/jobs", title: "Jobs & logs", ready: false },
];

export default function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    const load = () =>
      getStatus()
        .then((s) => { if (live) { setStatus(s); setFailed(false); } })
        .catch(() => { if (live) setFailed(true); });
    load();
    const timer = setInterval(load, 30_000);
    return () => { live = false; clearInterval(timer); };
  }, []);

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="wordmark">SIGNAL<span>.</span></div>
        <nav className="nav">
          {pages.map((p) =>
            p.ready ? (
              <NavLink key={p.path} to={p.path} end>{p.title}</NavLink>
            ) : (
              <span key={p.path} className="soon" title="Arrives later in milestone 3">{p.title}</span>
            ),
          )}
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <span className="spacer" />
          <span className={`dot ${health(status, failed)}`} />
          <span>{failed ? "Companion unreachable" : status ? `Companion ${status.version}` : "Connecting…"}</span>
        </header>
        <Routes>
          <Route path="/" element={<Dashboard status={status} failed={failed} />} />
          <Route path="*" element={<Dashboard status={status} failed={failed} />} />
        </Routes>
      </div>
    </div>
  );
}
