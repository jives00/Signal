import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getOverview } from "../admin";
import { ago, getSync, type Status, type SyncStatus } from "../api";
import { useProfiles } from "../context";
import type { Overview } from "../schema";
import GuideCard from "./GuideCard";

/** "0.3.1" vs "0.3.0": a device on an older app version than the companion's (PLAN §4.3.18 → Devices). */
export function older(version: string, than: string): boolean {
  const parse = (v: string) => v.split(/[.-]/).map((n) => Number.parseInt(n, 10) || 0);
  const [a, b] = [parse(version), parse(than)];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) < (b[i] ?? 0);
  }
  return false;
}

/**
 * The landing page (PLAN §4.3.18 → Dashboard): the health of everything, each linking to its page.
 * VOD enrichment and the provider switch join with their milestones.
 */
export default function Dashboard({ status, failed }: { status: Status | null; failed: boolean }) {
  const { profile } = useProfiles();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [sync, setSync] = useState<SyncStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () => {
      getOverview(profile).then((o) => { setOverview(o); setError(null); }).catch((e: Error) => setError(e.message));
      getSync().then(setSync).catch(() => undefined);
    };
    load();
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [profile]);

  const attention: { text: string; to: string }[] = [];
  if (overview) {
    const unmapped = overview.coverage.total - overview.coverage.mapped;
    if (overview.switchReview) attention.push({ text: `${overview.switchReview} channels to review in the provider switch`, to: "/switch" });
    if (overview.unmappedFavorites) attention.push({ text: `${overview.unmappedFavorites} favorites without guide data`, to: "/mapping" });
    if (overview.unavailableFavorites) attention.push({ text: `${overview.unavailableFavorites} favorites unavailable: their streams are gone`, to: "/favorites" });
    if (unmapped && !overview.unmappedFavorites) attention.push({ text: `${unmapped.toLocaleString()} visible channels without guide data`, to: "/mapping" });
    for (const s of overview.sources) if (s.lastError) attention.push({ text: `${s.name}: ${s.lastError}`, to: "/sources" });
    for (const j of overview.failedJobs.slice(0, 3)) attention.push({ text: `${j.job === "guide_build" ? "Guide build" : j.job === "source_refresh" ? "Source refresh" : j.job} failed ${ago(j.startedAt)}`, to: "/jobs" });
  }
  const latest = sync?.snapshots[0];

  return (
    <div className="page">
      <h1>Dashboard</h1>
      {error && <p className="error-text">{error}</p>}
      <div className="cards">
        <div className="card">
          <h2>Companion</h2>
          <div className="value">{failed ? "Unreachable" : status?.version ?? "…"}</div>
          <div className="detail">{status ? `Database ${status.database === "ok" ? "connected" : status.database} · encryption key ${status.secretKey ? "set" : "missing"}` : "Signal's NAS companion"}</div>
        </div>
        {overview && (
          <Link to="/sources" className="card">
            <h2>Sources</h2>
            <div className="value">{overview.sources.length ? `${overview.sources.reduce((n, s) => n + s.streams, 0).toLocaleString()} channels` : "None"}</div>
            <div className="detail">{overview.sources.map((s) => `${s.name}: ${s.refreshing ? "refreshing" : s.lastRefreshedAt ? `refreshed ${ago(s.lastRefreshedAt)}` : "not refreshed"}`).join(" · ") || "Add one here or on a TV"}</div>
          </Link>
        )}
        {overview && (
          <Link to="/mapping" className="card">
            <h2>Guide coverage</h2>
            <div className="value">{overview.coverage.total ? `${Math.round((100 * overview.coverage.mapped) / overview.coverage.total)}%` : "—"}</div>
            <div className="detail">{overview.coverage.mapped.toLocaleString()} of {overview.coverage.total.toLocaleString()} visible channels have guide data</div>
          </Link>
        )}
        {overview && (
          <Link to="/devices" className="card">
            <h2>Devices</h2>
            <div className="value">{overview.devices.length || "None"}</div>
            <div className="detail">
              {overview.devices.map((d) => `${d.name}: ${d.lastSyncAt ? `synced ${ago(d.lastSyncAt)}` : "not synced"}${older(d.appVersion, overview.latestAppVersion) ? ` · app ${d.appVersion}, update available` : ""}`).join(" · ") || "Pair a TV from its setup screen"}
            </div>
          </Link>
        )}
        <Link to="/backups" className="card">
          <h2>Last backup</h2>
          <div className="value">{latest ? ago(latest.createdAt) : "None yet"}</div>
          <div className="detail">{latest ? `${latest.reason} · ${latest.items} items` : "Nightly after the guide build"}</div>
        </Link>
      </div>

      {overview && (
        <section className="section">
          <h2 className="section-title">Needs attention</h2>
          {attention.length === 0 ? <p className="detail">Nothing. Everything's working.</p> : (
            <ul className="attention">{attention.map((a) => <li key={a.text}><Link to={a.to}>{a.text}</Link></li>)}</ul>
          )}
        </section>
      )}
      <GuideCard />
    </div>
  );
}
