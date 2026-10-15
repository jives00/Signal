import { useCallback, useEffect, useState } from "react";
import { acceptLinks, carryGroup, getChannels, getSources, getSwitch, rejectMatches, setSourceEnabled } from "../admin";
import { useProfiles, useToast } from "../context";
import type { ChannelRow, ProposalView, SourcesView, SwitchView } from "../schema";
import { Program } from "./Channels";
import { useSorted } from "../sort";

/**
 * Provider switch (PLAN §4.3.18 → 8, §4.5): add the new source beside the old one, link your
 * channels to its streams here, then disable the old source as a separate, confirmed step.
 * Nothing is unlinked or dropped until then.
 */
export default function Switch() {
  const { profile } = useProfiles();
  const { save } = useToast();
  const [sources, setSources] = useState<SourcesView | null>(null);
  const [target, setTarget] = useState<string>("");
  const [report, setReport] = useState<SwitchView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getSources().then((s) => {
      setSources(s);
      // The newest source is usually the new provider.
      setTarget((t) => t || s.sources[s.sources.length - 1]?.id || "");
    }).catch((e: Error) => setError(e.message));
  }, []);

  const load = useCallback(() => {
    if (!target) return;
    getSwitch(profile, target).then((r) => { setReport(r); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile, target]);
  useEffect(load, [load]);
  const confident = useSorted(report?.confident, { mine: (p) => p.name, theirs: (p) => p.matches[0]?.name, why: (p) => p.matches[0]?.reasons.join(", ") });
  const groups = useSorted(report?.groups, { group: (g) => g.group, theirs: (g) => g.toGroup });

  if (sources && sources.sources.length < 2) {
    return (
      <div className="page">
        <h1>Provider switch</h1>
        <p className="detail">To switch providers, add the new source on the Sources page beside the old one. Its channels get matched to yours here, and nothing changes until you disable the old source.</p>
      </div>
    );
  }

  const acceptAll = () => report && save(`Linked ${report.confident.length} channels to ${report.targetName}`, () => acceptLinks(report.confident.map((p) => ({ userChannelId: p.userChannelId, streamId: p.matches[0].streamId, confident: true }))), load);

  return (
    <div className="page">
      <h1>Provider switch</h1>
      <div className="filters">
        <label>New source
          <select value={target} onChange={(e) => setTarget(e.target.value)}>
            {sources?.sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
      </div>
      {error && <p className="error-text">{error}</p>}
      {report && (
        <>
          <div className="cards">
            <div className="card"><h2>Already linked</h2><div className="value">{report.alreadyLinked}</div><div className="detail">Your channels with a stream on {report.targetName}</div></div>
            <div className="card"><h2>Confident</h2><div className="value">{report.confident.length}</div><div className="detail">Same tvg-id or name</div></div>
            <div className="card"><h2>To review</h2><div className="value">{report.suggested.length}</div><div className="detail">Likely, but check</div></div>
            <div className="card"><h2>No match</h2><div className="value">{report.unmatched.length}</div><div className="detail">Unavailable once the old source is off</div></div>
          </div>

          {report.confident.length > 0 && (
            <section className="section">
              <div className="row heading"><h2 className="section-title">Confident matches</h2><span className="spacer" /><button className="primary" onClick={acceptAll}>Accept all confident</button></div>
              <table className="table">
                <thead><tr>{confident.header("mine", "Your channel")}{confident.header("theirs", `On ${report.targetName}`)}{confident.header("why", "Why", "wide")}</tr></thead>
                <tbody>
                  {confident.rows.map((p) => (
                    <tr key={p.userChannelId}>
                      <td>{p.name}{p.favorite && <span className="tag">★</span>}<div className="detail small">{p.fromSource}: {p.fromName}</div></td>
                      <td>{p.matches[0].name}<div className="detail small">{p.matches[0].group}</div></td>
                      <td className="wide detail small">{p.matches[0].reasons.join(", ")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          {report.suggested.length > 0 && (
            <section className="section">
              <h2 className="section-title">To review</h2>
              {report.suggested.map((p) => <Suggestion key={p.userChannelId} p={p} target={report.targetSourceId} onDone={load} />)}
            </section>
          )}

          {report.unmatched.length > 0 && (
            <section className="section">
              <h2 className="section-title">No match on {report.targetName}</h2>
              <p className="detail">These keep all their settings and come back if a later source has them. Search for one on the Channels page to link it by hand.</p>
              <ul className="attention">{report.unmatched.map((u) => <li key={u.userChannelId}>{u.name}{u.favorite && " ★"} <span className="detail small">{u.fromSource}</span></li>)}</ul>
            </section>
          )}

          {report.groups.length > 0 && (
            <section className="section">
              <h2 className="section-title">Groups with your settings</h2>
              <table className="table">
                <thead><tr>{groups.header("group", "Group")}{groups.header("theirs", `On ${report.targetName}`)}<th /></tr></thead>
                <tbody>
                  {groups.rows.map((g) => (
                    <tr key={`${g.sourceId}|${g.group}`}>
                      <td>{g.group}<div className="detail small">{g.sourceName}</div></td>
                      <td>{g.toGroup ?? <span className="detail">No match</span>}</td>
                      <td className="actions">{g.toGroup && <button onClick={() => save(`Settings carried to ${g.toGroup}`, () => carryGroup(g.sourceId, g.group, report.targetSourceId, g.toGroup!), load)}>Carry settings over</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <section className="section">
            <h2 className="section-title">Last step: turn off the old source</h2>
            <p className="detail">Once you're happy, disable the old source. Linked channels then play from {report.targetName}; ones with no match become unavailable until a source has them again. You can re-enable it any time.</p>
            <div className="row wrap">
              {report.otherSources.filter((s) => s.enabled).map((s) => (
                <button key={s.id} onClick={() => {
                  if (window.confirm(`Disable ${s.name}? ${report.unmatched.length ? `${report.unmatched.length} channels with no match on ${report.targetName} become unavailable. ` : ""}Every device follows within seconds.`)) {
                    save(`${s.name} disabled`, () => setSourceEnabled(s.id, false), () => { load(); getSources().then(setSources).catch(() => undefined); });
                  }
                }}>Disable {s.name}</button>
              ))}
              {report.otherSources.every((s) => !s.enabled) && <span className="detail">The other sources are already off.</span>}
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function Suggestion({ p, target, onDone }: { p: ProposalView; target: string; onDone: () => void }) {
  const { profile } = useProfiles();
  const { save } = useToast();
  const [search, setSearch] = useState("");
  const [found, setFound] = useState<ChannelRow[]>([]);

  useEffect(() => {
    if (search.trim().length < 2) { setFound([]); return; }
    const t = setTimeout(() => getChannels(profile, { q: search, source: target, limit: 8 }).then((r) => setFound(r.rows.filter((c) => !c.userChannelId))).catch(() => setFound([])), 300);
    return () => clearTimeout(t);
  }, [search, profile, target]);

  const link = (streamId: string, name: string) => save(`Linked ${p.name} to ${name}`, () => acceptLinks([{ userChannelId: p.userChannelId, streamId, confident: false }]), onDone);

  return (
    <div className="card suggestion">
      <div className="row heading">
        <strong>{p.name}</strong>{p.favorite && <span className="tag">★</span>}
        <span className="detail small">{p.fromSource}: {p.fromName}</span>
        <span className="spacer" />
        <button onClick={() => save("Won't be suggested again", () => rejectMatches(p.userChannelId, p.matches.map((m) => m.streamId)), onDone)}>Not this channel</button>
      </div>
      <ul className="choices">
        {p.matches.map((m) => (
          <li key={m.streamId}>
            <button onClick={() => link(m.streamId, m.name)}>
              <span className="grow"><strong>{m.name}</strong> <span className="detail small">{m.group} · {m.reasons.join(", ")}</span>{m.now && <><br /><Program p={m.now} label="Now" /></>}</span>
            </button>
          </li>
        ))}
      </ul>
      <input type="search" placeholder="Search for a different stream" value={search} onChange={(e) => setSearch(e.target.value)} aria-label={`Search a stream for ${p.name}`} />
      {found.length > 0 && (
        <ul className="choices">{found.map((c) => <li key={c.streamId}><button onClick={() => link(c.streamId, c.name)}>{c.name} <span className="detail small">{c.group}</span></button></li>)}</ul>
      )}
    </div>
  );
}
