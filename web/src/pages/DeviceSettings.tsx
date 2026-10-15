import { useCallback, useEffect, useState } from "react";
import { send, type Device } from "../api";
import { EditResult } from "../schema";
import { useToast } from "../context";
import { useSorted } from "../sort";

const labels: Record<string, string> = {
  last_played_channel: "Last played channel",
  recent_searches: "Recent searches",
  search_filter: "Search filter",
  update_dismissed_tag: "Dismissed update",
};

/**
 * One device's own settings (PLAN §4.3.18 → Devices), synced only between it and the companion.
 * Changes apply on the device at its next sync. Technical playback and subtitle style join here
 * when the app has them.
 */
export default function DeviceSettings({ device, onClose }: { device: Device; onClose: () => void }) {
  const { save } = useToast();
  const [settings, setSettings] = useState<Record<string, string> | null>(null);
  const load = useCallback(() => {
    send<{ settings: Record<string, string> }>(`/api/devices/${encodeURIComponent(device.id)}/settings`).then((r) => setSettings(r.settings)).catch(() => setSettings({}));
  }, [device.id]);
  useEffect(load, [load]);

  const table = useSorted(settings ? Object.entries(settings) : null, { key: ([k]) => labels[k] ?? k, value: ([, v]) => v });

  const clear = (key: string) => save("Cleared on the device", async () => EditResult.parse(
    await send<unknown>(`/api/devices/${encodeURIComponent(device.id)}/settings`, { method: "PUT", body: JSON.stringify({ key, value: null }) }),
  ), load);

  return (
    <div className="card form section">
      <div className="row heading"><h2>{device.name}: device settings</h2><span className="spacer" /><button className="link" onClick={onClose}>Close</button></div>
      {settings && Object.keys(settings).length === 0 && <p className="detail">None synced yet.</p>}
      {settings && Object.keys(settings).length > 0 && (
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
