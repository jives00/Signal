import type { Status } from "../api";
import BackupsCard from "./BackupsCard";
import GuideCard from "./GuideCard";

/**
 * The landing page (PLAN §4.3.18 → Dashboard). For now: the companion itself. The guide, sources,
 * devices and "needs attention" cards arrive as each part of milestone 3 lands.
 */
export default function Dashboard({ status, failed }: { status: Status | null; failed: boolean }) {
  return (
    <div className="page">
      <h1>Dashboard</h1>
      <div className="cards">
        <div className="card">
          <h2>Companion</h2>
          <div className="value">{failed ? "Unreachable" : status?.version ?? "…"}</div>
          <div className="detail">Signal's NAS companion</div>
        </div>
        <div className="card">
          <h2>Database</h2>
          <div className="value">{status ? (status.database === "ok" ? "Connected" : status.database) : "…"}</div>
          <div className="detail">The signal database in the shared MySQL</div>
        </div>
        <div className="card">
          <h2>Encryption key</h2>
          <div className="value">{status ? (status.secretKey ? "Set" : "Missing") : "…"}</div>
          <div className="detail">SIGNAL_SECRET_KEY, for provider credentials at rest</div>
        </div>
      </div>
      <GuideCard />
      <BackupsCard />
    </div>
  );
}
