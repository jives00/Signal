import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import {
  addEpgSource, addSource, deleteEpgSource, deleteSource, getSources, moveEpgSource, refreshSource, revealPassword, setDefaultEpg,
  setSourceEnabled, testSource, updateEpgSource, updateSource, type SourceForm,
} from "../admin";
import { ago, buildGuide } from "../api";
import { useToast } from "../context";
import type { EpgSourceView, SourceView, SourcesView } from "../schema";

const blank: SourceForm = { name: "", kind: "XTREAM", url: "", username: "", password: "", userAgent: "", referrer: "", enabled: true, liveFormat: "TS", connectionLimit: 2 };

/**
 * Sources (PLAN §4.3.18 → 2): the sources and EPG sources, shared by every device. Credentials are
 * masked, with a reveal button. Refresh results are the companion's own copy (its nightly refresh).
 */
export default function Sources() {
  const { save, say } = useToast();
  const [data, setData] = useState<SourcesView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ id: string | null; form: SourceForm } | null>(null);
  const [revealed, setRevealed] = useState<Record<string, string>>({});
  const [epgForm, setEpgForm] = useState<{ name: string; url: string } | null>(null);

  const load = useCallback(() => {
    getSources().then((d) => { setData(d); setError(null); }).catch((e: Error) => setError(e.message));
  }, []);
  const busy = !!data && (data.guideBuilding || data.sources.some((s) => s.refreshing));
  useEffect(() => {
    load();
    // Faster while something refreshes.
    const timer = setInterval(load, busy ? 3_000 : 30_000);
    return () => clearInterval(timer);
  }, [load, busy]);

  function edit(s: SourceView) {
    setEditing({
      id: s.id,
      form: { name: s.name, kind: s.kind === "DEMO" ? "M3U" : s.kind, url: s.url, username: s.username, password: "", userAgent: s.userAgent ?? "", referrer: s.referrer ?? "", enabled: s.enabled, liveFormat: s.liveFormat === "HLS" ? "HLS" : "TS", connectionLimit: s.connectionLimit },
    });
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    const ok = await save(editing.id ? "Saved · every device" : "Source added · every device loads it", () => (editing.id ? updateSource(editing.id, editing.form) : addSource(editing.form)), load);
    if (ok) setEditing(null);
  }

  async function test() {
    if (!editing) return;
    say("Testing…");
    try { say(`Connected: ${(await testSource(editing.form)).result}`); } catch (e) { say((e as Error).message, true); }
  }

  async function reveal(s: SourceView) {
    if (revealed[s.id]) { setRevealed(({ [s.id]: _, ...rest }) => rest); return; }
    try { const { password } = await revealPassword(s.id); setRevealed((r) => ({ ...r, [s.id]: password })); } catch (e) { say((e as Error).message, true); }
  }

  async function addEpg(event: FormEvent) {
    event.preventDefault();
    if (!epgForm) return;
    if (await save("EPG source added · the guide rebuilds", () => addEpgSource(epgForm.name, epgForm.url), load)) setEpgForm(null);
  }

  /** Downloads one EPG source again, or all of them, and republishes the guide. */
  function refreshGuide(epgId?: string) {
    buildGuide(epgId).then(() => { say("Refreshing the guide…"); setTimeout(load, 500); }).catch((e: Error) => say(e.message, true));
  }

  function shift(e: EpgSourceView) {
    const text = window.prompt("Time shift in minutes (negative moves programs earlier)", String(e.timeShiftMinutes));
    if (text === null) return;
    const n = Number(text);
    if (!Number.isInteger(n)) { say("Enter a whole number of minutes.", true); return; }
    save("Saved", () => updateEpgSource(e.id, { timeShiftMinutes: n }), load);
  }

  const f = editing?.form;
  const set = (patch: Partial<SourceForm>) => setEditing((e) => (e ? { ...e, form: { ...e.form, ...patch } } : e));

  return (
    <div className="page">
      <h1>Sources</h1>
      {error && <p className="error-text">{error}</p>}

      <section className="section first">
        <div className="row heading"><h2 className="section-title">IPTV sources</h2><button onClick={() => setEditing({ id: null, form: blank })}>Add source</button></div>
        {data && data.sources.length === 0 && <p className="detail">No sources yet. Add one here or on a TV.</p>}
        {data && data.sources.length > 0 && (
          <table className="table">
            <thead><tr><th>Name</th><th>Type</th><th>Login</th><th>Channels</th><th>Last refresh</th><th /></tr></thead>
            <tbody>
              {data.sources.map((s) => (
                <tr key={s.id} className={s.enabled ? "" : "dim"}>
                  <td>{s.name}{!s.enabled && <span className="tag">Disabled</span>}<div className="detail small">{s.url}</div></td>
                  <td>{s.kind === "XTREAM" ? "Xtream Codes" : s.kind}</td>
                  <td>
                    {s.username || "—"}
                    {s.hasPassword && <> · <span className="mono">{revealed[s.id] ?? "••••••"}</span> <button className="link" onClick={() => reveal(s)}>{revealed[s.id] ? "Hide" : "Reveal"}</button></>}
                  </td>
                  <td>{s.streams.toLocaleString()}</td>
                  <td>
                    {s.refreshing ? "Refreshing…" : s.lastRefreshedAt ? ago(s.lastRefreshedAt) : "Never"}
                    {s.lastError && <div className="error-text small">{s.lastError}</div>}
                  </td>
                  <td className="actions">
                    <button onClick={() => { refreshSource(s.id).then(() => { say("Refreshing…"); setTimeout(load, 500); }).catch((e: Error) => say(e.message, true)); }} disabled={s.refreshing || !s.enabled}>Refresh now</button>
                    {s.kind !== "DEMO" && <button onClick={() => edit(s)}>Edit</button>}
                    <button onClick={() => save(s.enabled ? "Disabled" : "Enabled", () => setSourceEnabled(s.id, !s.enabled), load)}>{s.enabled ? "Disable" : "Enable"}</button>
                    <button onClick={() => { if (window.confirm(`Remove ${s.name}? Its channels leave every list on every device, including Favorites and playlists.`)) save("Removed", () => deleteSource(s.id), load); }}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {f && (
          <form className="card form" onSubmit={submit}>
            <h2>{editing?.id ? "Edit source" : "Add source"}</h2>
            <label>Name<input value={f.name} onChange={(e) => set({ name: e.target.value })} required /></label>
            <label>Type
              <select value={f.kind} onChange={(e) => set({ kind: e.target.value as SourceForm["kind"] })}>
                <option value="XTREAM">Xtream Codes</option>
                <option value="M3U">M3U playlist</option>
              </select>
            </label>
            <label>{f.kind === "XTREAM" ? "Server URL" : "Playlist URL"}<input value={f.url} onChange={(e) => set({ url: e.target.value })} required placeholder={f.kind === "XTREAM" ? "http://host:8080" : "http://…/playlist.m3u"} /></label>
            {f.kind === "XTREAM" && <>
              <label>Username<input value={f.username} onChange={(e) => set({ username: e.target.value })} autoComplete="off" /></label>
              <label>Password<input type="password" value={f.password} onChange={(e) => set({ password: e.target.value })} autoComplete="new-password" placeholder={editing?.id ? "Unchanged" : ""} /></label>
              <label>Live format
                <select value={f.liveFormat} onChange={(e) => set({ liveFormat: e.target.value as "TS" | "HLS" })}>
                  <option value="TS">TS (faster zapping)</option>
                  <option value="HLS">HLS (more stable on some providers)</option>
                </select>
              </label>
            </>}
            {f.kind === "M3U" && <label>Connections allowed<input type="number" min={1} max={50} value={f.connectionLimit} onChange={(e) => set({ connectionLimit: Number(e.target.value) })} /></label>}
            <label>User agent (optional)<input value={f.userAgent} onChange={(e) => set({ userAgent: e.target.value })} /></label>
            <label>Referrer (optional)<input value={f.referrer} onChange={(e) => set({ referrer: e.target.value })} /></label>
            <div className="row">
              <button className="primary" type="submit">Save</button>
              <button type="button" onClick={test}>Test connection</button>
              <button type="button" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        )}
      </section>

      <section className="section">
        <div className="row heading"><h2 className="section-title">EPG sources</h2><button onClick={() => setEpgForm({ name: "", url: "" })}>Add EPG source</button></div>
        <p className="detail">The guide uses the default first, then the others in this order for channels the default doesn't cover.</p>
        {data && data.epgSources.length > 0 && (
          <table className="table">
            <thead><tr><th>Order</th><th>Name</th><th>Guide</th><th>Time shift</th><th>Last refresh</th><th /></tr></thead>
            <tbody>
              {data.epgSources.map((e, i) => (
                <tr key={e.id} className={e.enabled ? "" : "dim"}>
                  <td className="nowrap">
                    <button className="link" disabled={i === 0} onClick={() => save("Moved", () => moveEpgSource(e.id, i - 1), load)} aria-label="Move up">↑</button>
                    <button className="link" disabled={i === data.epgSources.length - 1} onClick={() => save("Moved", () => moveEpgSource(e.id, i + 1), load)} aria-label="Move down">↓</button>
                  </td>
                  <td>{e.name}{e.isDefault && <span className="tag">Default</span>}{!e.enabled && <span className="tag">Disabled</span>}<div className="detail small">{e.kind === "XMLTV" ? e.url : e.kind === "XTREAM" ? "The source's own xmltv.php" : "Demo"}</div></td>
                  <td>{e.error ? <span className="error-text">{e.error}</span> : `${e.channels.toLocaleString()} channels · ${e.programs.toLocaleString()} programs`}</td>
                  <td>{e.timeShiftMinutes ? `${e.timeShiftMinutes > 0 ? "+" : ""}${e.timeShiftMinutes} min` : "None"}</td>
                  <td>{data.guideBuilding && e.enabled ? "Refreshing…" : e.refreshedAt ? ago(e.refreshedAt) : "Never"}</td>
                  <td className="actions">
                    <button onClick={() => refreshGuide(e.id)} disabled={data.guideBuilding || !e.enabled}>Refresh now</button>
                    {!e.isDefault && e.enabled && <button onClick={() => save("Default EPG source set", () => setDefaultEpg(e.id), load)}>Make default</button>}
                    <button onClick={() => shift(e)}>Time shift</button>
                    {e.kind === "XMLTV" && <button onClick={() => { const name = window.prompt("Name", e.name)?.trim(); if (name) save("Renamed", () => updateEpgSource(e.id, { name }), load); }}>Rename</button>}
                    <button onClick={() => save(e.enabled ? "Disabled" : "Enabled", () => updateEpgSource(e.id, { enabled: !e.enabled }), load)}>{e.enabled ? "Disable" : "Enable"}</button>
                    {e.kind === "XMLTV" && <button onClick={() => { if (window.confirm(`Remove ${e.name}? Channels mapped to it go back to automatic.`)) save("Removed", () => deleteEpgSource(e.id), load); }}>Remove</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && data.epgSources.some((e) => e.enabled) && (
          <div className="row">
            <button onClick={() => refreshGuide()} disabled={data.guideBuilding}>Refresh all</button>
            <span className="detail">The guide also refreshes nightly · <Link className="inline" to="/settings/companion">schedule</Link></span>
          </div>
        )}
        {epgForm && (
          <form className="card form" onSubmit={addEpg}>
            <h2>Add EPG source</h2>
            <label>Name<input value={epgForm.name} onChange={(e) => setEpgForm({ ...epgForm, name: e.target.value })} placeholder="Guide" /></label>
            <label>XMLTV URL, plain or .gz<input value={epgForm.url} onChange={(e) => setEpgForm({ ...epgForm, url: e.target.value })} required /></label>
            <div className="row"><button className="primary" type="submit">Add</button><button type="button" onClick={() => setEpgForm(null)}>Cancel</button></div>
          </form>
        )}
      </section>
    </div>
  );
}
