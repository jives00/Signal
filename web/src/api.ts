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
  profileId: string;
  lastSyncAt: number;
}

/** The companion's own wording for errors is meant for people, so pass it through. */
export async function send<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { "Content-Type": "application/json", ...init?.headers } });
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
  if (response.status === 202 || response.status === 204) return undefined as T;
  return (await response.json()) as T;
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

/** The NAS guide build (PLAN §5.2 → Jobs). Times are epoch milliseconds. */
export interface GuideMeta {
  builtAt: number;
  schema: number;
  sizeBytes: number;
  sha256: string;
  channels: number;
  programs: number;
  outcomes: Record<string, { channels: number; programs: number; error?: string | null; carriedOver?: boolean }>;
}

export interface JobRun {
  id: number;
  job: string;
  startedAt: number;
  finishedAt: number | null;
  result: string | null;
  detail: string | null;
}

export interface GuideSchedule {
  time: string;
  timeZone: string;
}

export interface GuideStatus {
  meta: GuideMeta | null;
  running: boolean;
  lastRun: JobRun | null;
  schedule: GuideSchedule;
  nextRunAt: number | null;
  epgSources: string[];
  inputsFrom: string | null;
  inputsAt: number | null;
}

export const getGuide = () => send<GuideStatus>("/api/guide");
/** One EPG source, or every one without [epgId]. */
export const buildGuide = (epgId?: string) => send<void>(`/api/guide/build${epgId ? `?epg=${encodeURIComponent(epgId)}` : ""}`, { method: "POST" });
export const setGuideSchedule = (schedule: GuideSchedule) => send<void>("/api/guide/schedule", { method: "PUT", body: JSON.stringify(schedule) });

/** 14.2 MB, 830 KB. */
export function size(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** Settings sync (PLAN §9): profiles and backups. */
export interface ProfileSummary {
  id: string;
  name: string;
  createdAt: number;
  devices: string[];
  items: number;
}

export interface Snapshot {
  id: number;
  createdAt: number;
  reason: string;
  items: number;
}

export interface SyncStatus {
  rev: number;
  counts: Record<string, number>;
  profiles: ProfileSummary[];
  snapshots: Snapshot[];
}

export const getSync = () => send<SyncStatus>("/api/sync");
export const createProfile = (name: string, copyFrom: string | null) => send<ProfileSummary>("/api/profiles", { method: "POST", body: JSON.stringify({ name, copyFrom }) });
export const renameProfile = (id: string, name: string) => send<void>(`/api/profiles/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify({ name }) });
export const deleteProfile = (id: string) => send<void>(`/api/profiles/${encodeURIComponent(id)}`, { method: "DELETE" });
export const setDeviceProfile = (id: string, profileId: string) => send<void>(`/api/devices/${encodeURIComponent(id)}/profile`, { method: "PUT", body: JSON.stringify({ profileId }) });
export const backUpNow = () => send<Snapshot>("/api/snapshots", { method: "POST", body: JSON.stringify({ reason: "Manual" }) });
export const restoreSnapshot = (id: number) => send<{ changes: number }>(`/api/snapshots/${id}/restore`, { method: "POST" });
export const deleteSnapshot = (id: number) => send<void>(`/api/snapshots/${id}`, { method: "DELETE" });

/** "3 sources · 12 favorites": the headline counts from a sync status, for people. */
export function itemSummary(counts: Record<string, number>): string {
  const total = (type: string) => Object.entries(counts).filter(([k]) => k.startsWith(`${type}/`)).reduce((n, [, v]) => n + v, 0);
  const parts: [string, string][] = [["source", "source"], ["favorite", "favorite"], ["playlist", "playlist"], ["reminder", "reminder"]];
  const shown = parts.map(([type, word]) => [total(type), word] as const).filter(([n]) => n > 0).map(([n, word]) => `${n} ${word}${n === 1 ? "" : "s"}`);
  return shown.length ? shown.join(" · ") : "Nothing synced yet";
}
