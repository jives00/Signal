import { useEffect, useState, type FormEvent } from "react";
import { previewCleanup, putSetting, type NameCleanupRules } from "../admin";
import { profileNames, useProfiles, useToast } from "../context";
import type { CleanupPreview } from "../schema";

const KEY = "name_cleanup";
type Where = "START" | "END" | "ANYWHERE";
const whereLabel: Record<Where, string> = { START: "at the start", END: "at the end", ANYWHERE: "anywhere" };
const none: NameCleanupRules = { presets: [], custom: [], groups: false };

function parse(raw: string | undefined): NameCleanupRules {
  if (!raw) return none;
  try {
    const r = JSON.parse(raw) as Partial<NameCleanupRules>;
    return { presets: r.presets ?? [], custom: r.custom ?? [], groups: r.groups ?? false };
  } catch {
    return none;
  }
}

/** What the TV stores: nothing when there are no rules, like NameCleanup.encode(). */
function encode(r: NameCleanupRules): string | null {
  const custom = r.custom.filter((c) => c.text.trim());
  return r.presets.length || custom.length || r.groups ? JSON.stringify({ ...r, custom }) : null;
}

/**
 * Name cleanup (PLAN §4.3.18 → 6): common prefixes and suffixes to remove, plus the user's own,
 * with a live preview on the real names. Edits are a draft until Save, so trying patterns doesn't
 * fill the Undo history. Displayed names only: matching and the guide keep the raw name.
 */
export default function NameCleanup({ saved, onSaved }: { saved: string | undefined; onSaved: () => void }) {
  const { profile, applyTo, profiles } = useProfiles();
  const { save } = useToast();
  const [rules, setRules] = useState<NameCleanupRules>(() => parse(saved));
  const [preview, setPreview] = useState<CleanupPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [at, setAt] = useState<Where>("START");
  const [regex, setRegex] = useState(false);

  useEffect(() => setRules(parse(saved)), [saved]);

  // Debounced, so typing a rule doesn't send a preview per key.
  useEffect(() => {
    const t = setTimeout(() => {
      previewCleanup(rules, profile).then((p) => { setPreview(p); setError(null); }).catch((e: Error) => setError(e.message));
    }, 300);
    return () => clearTimeout(t);
  }, [rules, profile]);

  const dirty = encode(rules) !== encode(parse(saved));
  const samples = preview ? [...preview.groupSamples.map((s) => ({ ...s, kind: "Group" })), ...preview.channelSamples.map((s) => ({ ...s, kind: "Channel" }))] : [];
  const togglePreset = (id: string) =>
    setRules({ ...rules, presets: rules.presets.includes(id) ? rules.presets.filter((p) => p !== id) : [...rules.presets, id] });

  function add(event: FormEvent) {
    event.preventDefault();
    if (!text.trim()) return;
    setRules({ ...rules, custom: [...rules.custom, regex ? { text: text.trim(), at, regex } : { text: text.trim(), at }] });
    setText("");
  }

  return (
    <section className="section">
      <h2 className="section-title">Name cleanup</h2>
      <p className="detail">Removes text from channel names on the TV: at the start, at the end or anywhere. Only what's shown changes: guide matching and search still use the provider's name, and a channel you renamed keeps your name.</p>

      <fieldset>
        <legend>Common patterns</legend>
        {(preview?.presets ?? []).map((p) => (
          <label key={p.id} className="check" style={{ display: "flex", marginBottom: 6 }}>
            <input type="checkbox" checked={rules.presets.includes(p.id)} onChange={() => togglePreset(p.id)} />
            <span>{p.label} <span className="detail small">{p.example}</span></span>
          </label>
        ))}
      </fieldset>

      <fieldset style={{ marginTop: 12 }}>
        <legend>Your own</legend>
        {rules.custom.length === 0 && <p className="detail small">None yet. Matched ignoring case; <span className="mono">*</span> is any text and <span className="mono">?</span> one character, so <span className="mono">|*|</span> removes |US| and |UK|. Tick Regex for a full regular expression.</p>}
        <ul className="ordered">
          {rules.custom.map((c, i) => (
            <li key={`${i}:${c.at}:${c.text}`}>
              <span className="grow">
                Remove {c.regex ? "pattern " : ""}<span className="mono">{c.regex ? c.text : `"${c.text}"`}</span> {whereLabel[c.at]}
                {c.regex && preview?.invalidRules.includes(c.text) && <span className="error-text"> · not a valid regular expression, skipped</span>}
              </span>
              <button type="button" className="link" onClick={() => setRules({ ...rules, custom: rules.custom.filter((_, j) => j !== i) })}>Delete</button>
            </li>
          ))}
        </ul>
        <form className="row" onSubmit={add} style={{ marginTop: 8 }}>
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder={regex ? String.raw`\(\d{4}-\d\d-\d\d.*\)` : "US: or HD"} aria-label="Text to remove" className={regex ? "mono" : undefined} />
          <select value={at} onChange={(e) => setAt(e.target.value as Where)} aria-label="Where">
            <option value="START">At the start</option>
            <option value="END">At the end</option>
            <option value="ANYWHERE">Anywhere</option>
          </select>
          <label className="check"><input type="checkbox" checked={regex} onChange={(e) => setRegex(e.target.checked)} /> Regex</label>
          <button type="submit" disabled={!text.trim()}>Add</button>
        </form>
      </fieldset>

      <label className="check" style={{ marginTop: 12 }}>
        <input type="checkbox" checked={rules.groups} onChange={(e) => setRules({ ...rules, groups: e.target.checked })} /> Clean group names too
      </label>

      <div className="row" style={{ marginTop: 12 }}>
        <button
          className="primary"
          disabled={!dirty}
          onClick={() => save(`Saved · applied to ${profileNames(applyTo, profiles)}`, () => putSetting("profile", KEY, encode(rules), applyTo), onSaved)}
        >
          Save
        </button>
        {dirty && <button className="link" onClick={() => setRules(parse(saved))}>Discard changes</button>}
      </div>

      {error && <p className="error-text">{error}</p>}
      {preview && (
        <>
          <p className="detail">
            {preview.channelsChanged.toLocaleString()} of {preview.channels.toLocaleString()} channel names change
            {rules.groups && `, and ${preview.groupsChanged.toLocaleString()} of ${preview.groups.toLocaleString()} group names`}
            {dirty && " (not saved yet)"}.
          </p>
          {samples.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Before</th><th>After</th>{rules.groups && <th />}</tr></thead>
                <tbody>
                  {samples.map((s) => (
                    <tr key={`${s.kind}:${s.before}`}>
                      <td className="dim">{s.before}</td>
                      <td>{s.after}</td>
                      {rules.groups && <td className="detail small">{s.kind}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
