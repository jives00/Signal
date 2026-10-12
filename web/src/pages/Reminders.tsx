import { useCallback, useEffect, useState } from "react";
import { clock, deleteReminder, getReminders } from "../admin";
import { profileNames, useProfiles, useToast } from "../context";
import type { ReminderView } from "../schema";

/** Reminders (PLAN §4.3.18 → 10): the shown profile's upcoming reminders, with remove. */
export default function Reminders() {
  const { profile, profiles } = useProfiles();
  const { save } = useToast();
  const [rows, setRows] = useState<ReminderView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    getReminders(profile).then((r) => { setRows(r); setError(null); }).catch((e: Error) => setError(e.message));
  }, [profile]);
  useEffect(load, [load]);

  return (
    <div className="page">
      <h1>Reminders</h1>
      <p className="detail">Set on the TV from the guide. Shown for {profileNames([profile], profiles)}.</p>
      {error && <p className="error-text">{error}</p>}
      {rows && rows.length === 0 && <p className="detail">No reminders.</p>}
      {rows && rows.length > 0 && (
        <table className="table">
          <thead><tr><th>When</th><th>Program</th><th>Channel</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{new Date(r.start).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })} {clock(r.start)}–{clock(r.stop)}</td>
                <td>{r.title}</td>
                <td>{r.channelName}</td>
                <td className="actions"><button onClick={() => save("Reminder removed", () => deleteReminder(r.id, profile), load)}>Remove</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
