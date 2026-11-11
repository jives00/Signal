import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getOverview, getRecordings } from "../admin";
import { ago, getSync, type Status, type SyncStatus } from "../api";
import { useProfiles } from "../context";
import type { Overview, Recording } from "../schema";
import Detail from "../Detail";
import GuideCard from "./GuideCard";
import SourceCards from "./SourceCards";

/** "0.3.1" vs "0.3.0": a device on an older app version than the companion's (PLAN §4.3.18 → Devices). */
export function older(version: string, than: string): boolean {
  const parse = (v: string) => v.split(/[.-]/).map((n) => Number.parseInt(n, 10) || 0);
  const [a, b] = [parse(version), parse(than)];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) < (b[i] ?? 0);
  }
  return false;
}

const DAY_MS = 24 * 60 * 60_000;
/** How long before a source runs out the dashboard starts saying so. */
const EXPIRY_NOTICE_DAYS = 30;

const shortDate = (ms: number) => new Date(ms).toLocaleDateString([], { month: "short", day: "numeric" });

/**
 * Sources running out, and what's set to record after they do. The provider gives the date in
 * seconds. A recording on such a channel can still find another source, so it says may.
 */
export function expiryWarnings(
  sources: { name: string; id: string; enabled: boolean; expiresAt: number | null; accounts?: { name: string; expiresAt: number | null }[] }[],
  recordings: Pick<Recording, "state" | "title" | "streamId" | "startMs" | "programStart">[],
  now: number,
): string[] {
  const notice = (name: string, at: number) => {
    if (at <= now) return [`${name} expired ${shortDate(at)}`];
    if (at - now > EXPIRY_NOTICE_DAYS * DAY_MS) return [];
    const days = Math.ceil((at - now) / DAY_MS);
    return [`${name} expires in ${days === 1 ? "1 day" : `${days} days`} (${shortDate(at)})`];
  };
  return sources.flatMap((s) => {
    if (!s.enabled) return [];
    // Extra logins are separate subscriptions: one running out leaves the others, so only the source's own warns about recordings.
    const logins = (s.accounts ?? []).flatMap((a) => (a.expiresAt ? notice(`${s.name} login ${a.name}`, a.expiresAt * 1000) : []));
    if (!s.expiresAt) return logins;
    const at = s.expiresAt * 1000;
    const after = recordings.filter((r) => r.state === "SCHEDULED" && r.streamId.startsWith(`${s.id}:`) && (r.programStart ?? r.startMs) >= at);
    const missing = after.map((r) => `${s.name} expires ${shortDate(at)}, before ${r.title} on ${shortDate(r.programStart ?? r.startMs)}: it may not record`);
    return [...notice(s.name, at), ...missing, ...logins];
  });
}

/**
 * The landing page (PLAN §4.3.18 → Dashboard): the health of everything, each linking to its page.
 * VOD enrichment and the provider switch join with their milestones.
 */
export default function Dashboard({ status, failed }: { status: Status | null; failed: boolean }) {
  const { profile } = useProfiles();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [recordings, setRecordings] = useState<Recording[]>([]);
  const [sync, setSync] = useState<SyncStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () => {
      getOverview(profile).then((o) => { setOverview(o); setError(null); }).catch((e: Error) => setError(e.message));
      getRecordings(profile).then((v) => setRecordings(v.recordings)).catch(() => undefined);
      getSync().then(setSync).catch(() => undefined);
    };
    load();
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [profile]);

  const attention: { text: string; to: string }[] = [];
  if (overview) {
    const unmapped = overview.coverage.total - overview.coverage.mapped;
    for (const text of expiryWarnings(overview.sources, recordings, Date.now())) attention.push({ text, to: "/sources" });
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
          <Detail lines={status ? [`Database ${status.database === "ok" ? "connected" : status.database}`, `Encryption key ${status.secretKey ? "set" : "missing"}`] : []} empty="Signal's NAS companion" />
        </div>
        {overview && (
          <Link to="/sources" className="card">
            <h2>Sources</h2>
            <div className="value">{overview.sources.length ? `${overview.sources.reduce((n, s) => n + s.streams, 0).toLocaleString()} channels` : "None"}</div>
            <Detail lines={overview.sources.map((s) => `${s.name}: ${s.refreshing ? "refreshing" : s.lastRefreshedAt ? `refreshed ${ago(s.lastRefreshedAt)}` : "not refreshed"}`)} empty="Add one here or on a TV" />
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
            <Detail
              lines={overview.devices.map((d) => `${d.name}: ${d.lastSyncAt ? `synced ${ago(d.lastSyncAt)}` : "not synced"}${older(d.appVersion, overview.latestAppVersion) ? `, app ${d.appVersion} has an update` : ""}`)}
              empty="Pair a TV from its setup screen"
            />
          </Link>
        )}
        <Link to="/backups" className="card">
          <h2>Last backup</h2>
          <div className="value">{latest ? ago(latest.createdAt) : "None yet"}</div>
          <Detail lines={latest ? [latest.reason, `${latest.items} items`] : []} empty="Nightly after the guide build" />
        </Link>
      </div>

      <SourceCards />

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
