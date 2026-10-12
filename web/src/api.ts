// The companion's API. The web admin is only ever served by the companion itself, so paths are
// relative; in development Vite proxies them (vite.config.ts).

export interface Status {
  version: string;
  database: "ok" | "unreachable" | "not configured";
  secretKey: boolean;
}

export async function getStatus(): Promise<Status> {
  const response = await fetch("/api/status");
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as Status;
}

/** One word for the top bar's dot: ok, warn or error. */
export function health(status: Status | null, failed: boolean): "ok" | "warn" | "error" {
  if (failed || !status) return "error";
  if (status.database !== "ok" || !status.secretKey) return "warn";
  return "ok";
}

/** A paired device (PLAN §4.3.18 → Devices & profiles). Times are epoch milliseconds. */
export interface Device {
  id: string;
  name: string;
  model: string;
  appVersion: string;
  pairedAt: number;
  lastSeenAt: number;
}

/** The companion's own wording for errors is meant for people, so pass it through. */
async function send<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { "Content-Type": "application/json", ...init?.headers } });
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
  return (response.status === 204 ? undefined : await response.json()) as T;
}

export const getDevices = () => send<Device[]>("/api/devices");
export const approveDevice = (code: string) => send<Device>("/api/devices/approve", { method: "POST", body: JSON.stringify({ code }) });
export const renameDevice = (id: string, name: string) => send<void>(`/api/devices/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ name }) });
export const forgetDevice = (id: string) => send<void>(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE" });

/** Digits only, at most six: the code as the TV shows it, whatever got pasted around it. */
export function cleanCode(input: string): string {
  return input.replace(/\D/g, "").slice(0, 6);
}

/** "just now", "5 min ago", "3 h ago", or the date. */
export function ago(at: number, now: number = Date.now()): string {
  const minutes = Math.floor((now - at) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} h ago`;
  return new Date(at).toLocaleDateString();
}
