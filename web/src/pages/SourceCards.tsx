import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getActivity } from "../admin";
import type { LoginActivityView, SourceActivityView, UseView } from "../schema";

const WHO: Record<UseView["who"], string> = { TV: "TV", VIEWER: "Viewer", WEB: "Web", RECORDING: "Recording" };

/** How long it's been going: "just started", "12 min", "1 h 5 min". */
export function lasting(since: number, now: number): string {
  const minutes = Math.floor((now - since) / 60_000);
  if (minutes < 1) return "just started";
  if (minutes < 60) return `${minutes} min`;
  const rest = minutes % 60;
  return `${Math.floor(minutes / 60)} h${rest ? ` ${rest} min` : ""}`;
}

/** What a use is playing, in a line: a channel and its program, a recording's program and channel, or a title. */
export function playingLine(u: UseView): string {
  const what = (() => {
    if (u.who === "RECORDING") return [u.program, u.title && `on ${u.title}`].filter(Boolean).join(" ") || "A channel";
    if (u.kind === "LIVE") return [u.title, u.program].filter(Boolean).join(" · ") || "A channel";
    if (u.kind === "EPISODE") return [u.title, u.subtitle].filter(Boolean).join(" · ") || "An episode";
    return u.title ?? "A movie";
  })();
  return u.paused ? `${what} (paused)` : what;
}

const uses = (s: SourceActivityView) => s.logins.reduce((n, l) => n + l.uses.length, 0);

/** The card's big line: "Idle", "1 of 2 in use", or "3 in use" when the limit isn't known. */
export function inUseValue(s: SourceActivityView): string {
  const n = uses(s);
  if (n === 0) return "Idle";
  const limit = s.limit ?? (s.logins.every((l) => l.providerLimit) ? s.logins.reduce((sum, l) => sum + (l.providerLimit ?? 0), 0) : null);
  return limit ? `${n} of ${limit} in use` : `${n} in use`;
}

/** Over the limit: the provider will cut someone off. */
export function overLimit(s: SourceActivityView): boolean {
  return (s.limit != null && uses(s) > s.limit) || s.logins.some((l) => l.providerLimit != null && l.uses.length > l.providerLimit);
}

/**
 * When the provider counts more streams than Signal knows of: something else is on the login, or a
 * stream that just stopped, which the provider keeps counting for a while.
 */
export function providerNote(l: LoginActivityView): string | null {
  if (l.providerActive == null || l.providerActive <= l.uses.length) return null;
  const extra = l.providerActive - l.uses.length;
  const counted = l.providerLimit ? `${l.providerActive} of ${l.providerLimit}` : `${l.providerActive}`;
  return `The provider counts ${counted} in use: ${extra === 1 ? "1 isn't" : `${extra} aren't`} from Signal, or just stopped.`;
}

function Use({ u, now }: { u: UseView; now: number }) {
  const line = (
    <>
      <div><span className="tag quiet">{WHO[u.who]}</span><strong>{u.who === "RECORDING" ? "Recording" : u.name}</strong></div>
      <div>{playingLine(u)}</div>
      <div className="detail">{lasting(u.since, now)}</div>
    </>
  );
  return u.recordingId ? <Link to="/recordings" className="use">{line}</Link> : <div className="use">{line}</div>;
}

/** One card per source (source-activity.md): what's using it right now, and how many streams the provider counts. */
export default function SourceCards() {
  const [view, setView] = useState<SourceActivityView[] | null>(null);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    let live = true;
    const load = () => getActivity().then((v) => { if (live) { setView(v.sources); setNow(Date.now()); } }).catch(() => undefined);
    load();
    const timer = setInterval(load, 10_000);
    return () => { live = false; clearInterval(timer); };
  }, []);

  if (!view || view.length === 0) return null;
  return (
    <section className="section">
      <h2 className="section-title">Sources in use</h2>
      <div className="cards">
        {view.map((s) => (
          <div key={s.id} className="card activity">
            <h2>{s.name}</h2>
            <div className={`value${overLimit(s) ? " warn-text" : ""}`}>{inUseValue(s)}</div>
            {s.logins.map((l) => {
              const note = providerNote(l);
              if (!l.uses.length && !note && s.logins.length === 1) return null;
              return (
                <div key={l.id} className="login">
                  {s.logins.length > 1 && <div className="login-name">{l.name}{!l.uses.length && <span className="detail"> · idle</span>}</div>}
                  {l.uses.map((u, i) => <Use key={i} u={u} now={now} />)}
                  {note && <div className="detail warn-text">{note}</div>}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </section>
  );
}
