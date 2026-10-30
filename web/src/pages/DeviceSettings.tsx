import { useCallback, useEffect, useState } from "react";
import { getSources } from "../admin";
import { send, type Device } from "../api";
import { EditResult, type SourceView } from "../schema";
import { useToast } from "../context";
import { useSorted } from "../sort";

const labels: Record<string, string> = {
  last_played_channel: "Last played channel",
  recent_searches: "Recent searches",
  search_filter: "Search filter",
  update_dismissed_tag: "Dismissed update",
  speech_model: "Speech model",
};

/**
 * Settings → Playback's device part on the TV (PLAN §4.3.15 → 5): subtitle style and the technical
 * settings, with the TV's choices. [null] is the TV's default. Mirrors SettingsPlayback.kt.
 */
const playback: { key: string; label: string; options: [string | null, string][] }[] = [
  { key: "subtitle_size", label: "Subtitle size", options: [["SMALL", "Small"], [null, "Normal"], ["LARGE", "Large"], ["EXTRA_LARGE", "Extra large"]] },
  { key: "subtitle_color", label: "Subtitle color", options: [[null, "White"], ["YELLOW", "Yellow"], ["CYAN", "Cyan"]] },
  { key: "subtitle_background", label: "Subtitle background", options: [[null, "None, outlined text"], ["SHADED", "Shaded box"], ["BLACK", "Black box"]] },
  { key: "frame_rate_match", label: "Auto frame rate", options: [["OFF", "Off"], [null, "On"], ["ON_RESOLUTION", "On, also switch resolution"]] },
  { key: "video_decoder", label: "Video decoder", options: [["HARDWARE", "Hardware"], [null, "Hardware, software fallback"], ["SOFTWARE", "Software"]] },
  { key: "audio_passthrough", label: "Audio passthrough", options: [[null, "Auto"], ["false", "Off"]] },
  { key: "tunneling", label: "Tunneled playback", options: [[null, "Off"], ["true", "On"]] },
  { key: "live_buffer", label: "Live buffer", options: [["LOW_LATENCY", "Low latency"], [null, "Balanced"], ["STABLE", "Stable"]] },
  { key: "vod_buffer", label: "Movie and episode buffer", options: [[null, "Normal"], ["LARGE", "Large"]] },
  { key: "stream_timeout", label: "Stream timeout", options: [["10", "10 seconds"], [null, "20 seconds"], ["30", "30 seconds"]] },
  { key: "stream_retries", label: "Retries", options: [["0", "None"], [null, "1 retry"], ["2", "2 retries"], ["3", "3 retries"]] },
];
const playbackKeys = new Set(playback.map((p) => p.key));
/** Provider accounts (PLAN §9.1): source ID → the login this TV plays from; a source not in it uses its own. */
const ACCOUNTS = "source_accounts";

function parseAccounts(value: string | undefined): Record<string, string> {
  try { return value ? (JSON.parse(value) as Record<string, string>) : {}; } catch { return {}; }
}

/**
 * One device's own settings (PLAN §4.3.18 → Devices), synced only between it and the companion.
 * Changes apply on the device at its next sync: playback settings from its next channel or video.
 */
export default function DeviceSettings({ device, onClose }: { device: Device; onClose: () => void }) {
  const { save } = useToast();
  const [settings, setSettings] = useState<Record<string, string> | null>(null);
  const load = useCallback(() => {
    send<{ settings: Record<string, string> }>(`/api/devices/${encodeURIComponent(device.id)}/settings`).then((r) => setSettings(r.settings)).catch(() => setSettings({}));
  }, [device.id]);
  useEffect(load, [load]);
  const [sources, setSources] = useState<SourceView[]>([]);
  useEffect(() => { getSources().then((d) => setSources(d.sources.filter((s) => s.accounts.length > 0))).catch(() => setSources([])); }, []);

  const table = useSorted(settings ? Object.entries(settings).filter(([k]) => !playbackKeys.has(k) && k !== ACCOUNTS) : null, { key: ([k]) => labels[k] ?? k, value: ([, v]) => v });
  const put = (key: string, value: string | null) => save("Saved · applies on the TV", async () => EditResult.parse(
    await send<unknown>(`/api/devices/${encodeURIComponent(device.id)}/settings`, { method: "PUT", body: JSON.stringify({ key, value }) }),
  ), load);

  const clear = (key: string) => save("Cleared on the device", async () => EditResult.parse(
    await send<unknown>(`/api/devices/${encodeURIComponent(device.id)}/settings`, { method: "PUT", body: JSON.stringify({ key, value: null }) }),
  ), load);

  return (
    <div className="card form section">
      <div className="row heading">
        <h2>{device.name}: device settings</h2>
        <span className="spacer" />
        {/* The last logs the TV sent: Settings → About & updates → Send logs on the TV. */}
        <a className="link" href={`/api/devices/${encodeURIComponent(device.id)}/logs`} title="The last logs this TV sent from Settings → About & updates → Send logs">Logs</a>
        <button className="link" onClick={onClose}>Close</button>
      </div>
      {settings && <>
        <h3>Playback</h3>
        <div className="settings">
          {playback.map((p) => (
            <label key={p.key}>
              <span>{p.label}</span>
              <select value={settings[p.key] ?? ""} onChange={(e) => put(p.key, e.target.value || null)}>
                {p.options.map(([v, l]) => <option key={l} value={v ?? ""}>{l}</option>)}
              </select>
            </label>
          ))}
        </div>
        {sources.length > 0 && <>
          <h3>Provider accounts</h3>
          <p className="detail">The login this TV plays from. If another TV is using it when this one starts watching, it borrows a free one.</p>
          <div className="settings">
            {sources.map((s) => {
              const chosen = parseAccounts(settings[ACCOUNTS]);
              const choose = (id: string) => {
                const next = { ...chosen };
                if (id) next[s.id] = id; else delete next[s.id];
                put(ACCOUNTS, Object.keys(next).length ? JSON.stringify(next) : null);
              };
              return (
                <label key={s.id}>
                  <span>{s.name}</span>
                  <select value={chosen[s.id] ?? ""} onChange={(e) => choose(e.target.value)}>
                    <option value="">{s.name}'s own ({s.username})</option>
                    {s.accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                  </select>
                </label>
              );
            })}
          </div>
        </>}
        <h3>Other</h3>
      </>}
      {settings && table.rows.length === 0 && <p className="detail">None synced yet.</p>}
      {settings && table.rows.length > 0 && (
        <table className="table">
          <thead><tr>{table.header("key", "Setting")}{table.header("value", "Value")}<th /></tr></thead>
          <tbody>
            {table.rows.map(([k, v]) => (
              <tr key={k}>
                <td>{labels[k] ?? k}</td>
                <td className="detail small mono">{v.length > 80 ? `${v.slice(0, 80)}…` : v}</td>
                <td className="actions"><button onClick={() => clear(k)}>Clear</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
