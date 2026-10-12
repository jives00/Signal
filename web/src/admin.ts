// The web admin's page endpoints (server/AdminRoutes.kt). Reads are parsed with the Zod mirror;
// edits return an EditResult whose undo ops the toast's Undo sends back.
import type { z } from "zod";
import { send } from "./api";
import * as S from "./schema";

async function read<T extends z.ZodTypeAny>(schema: T, path: string): Promise<z.infer<T>> {
  return schema.parse(await send<unknown>(path));
}

const post = (path: string, body: unknown) => send<unknown>(path, { method: "POST", body: JSON.stringify(body) }).then((r) => S.EditResult.parse(r));
const put = (path: string, body: unknown) => send<unknown>(path, { method: "PUT", body: JSON.stringify(body) }).then((r) => S.EditResult.parse(r));
const del = (path: string) => send<unknown>(path, { method: "DELETE" }).then((r) => S.EditResult.parse(r));
const q = (profile: string) => `profile=${encodeURIComponent(profile)}`;
const enc = encodeURIComponent;

export const undo = (ops: S.AdminOp[]) => post("/api/edits/undo", { ops });

// ---- Dashboard, jobs ----
export const getOverview = (profile: string) => read(S.Overview, `/api/overview?${q(profile)}`);
export const getJobs = () => read(S.JobRun.array(), "/api/jobs");

// ---- Channels ----
export interface ChannelQuery {
  q?: string;
  source?: string;
  group?: string;
  favorites?: boolean;
  playlist?: string;
  hidden?: "" | "hidden" | "visible";
  customized?: boolean;
  guide?: "" | "mapped" | "unmapped";
  offset?: number;
  limit?: number;
}

export function channelQueryString(profile: string, f: ChannelQuery): string {
  const p = new URLSearchParams({ profile });
  for (const [k, v] of Object.entries(f)) {
    if (v === undefined || v === "" || v === false) continue;
    p.set(k, String(v));
  }
  return p.toString();
}

export const getChannels = (profile: string, f: ChannelQuery) => read(S.ChannelPage, `/api/channels?${channelQueryString(profile, f)}`);
export const getChannel = (profile: string, streamId: string) => read(S.ChannelRow, `/api/channels/${enc(streamId)}?${q(profile)}`);

export type ChannelAction = "favorite" | "unfavorite" | "hide" | "unhide" | "reset" | "addToPlaylist" | "removeFromPlaylist" | "set";
export const editChannels = (streamIds: string[], profiles: string[], action: ChannelAction, extra: { playlistId?: string; values?: Record<string, string | number | null> } = {}) =>
  post("/api/channels/edit", { streamIds, profiles, action, ...extra });

// ---- Favorites & playlists ----
export const getLists = (profile: string) => read(S.ListsView, `/api/lists?${q(profile)}`);
export const moveFavorite = (uc: string, profile: string, toIndex: number) => post(`/api/favorites/${enc(uc)}/move`, { profile, toIndex });
export const removeFavorite = (uc: string, profiles: string[]) => post(`/api/favorites/${enc(uc)}/remove`, { profiles });
export const createPlaylist = (name: string, profiles: string[]) => post("/api/playlists", { name, profiles });
export const renamePlaylist = (id: string, profile: string, name: string) => put(`/api/playlists/${enc(id)}?${q(profile)}`, { name });
export const deletePlaylist = (id: string, profile: string) => del(`/api/playlists/${enc(id)}?${q(profile)}`);
export const movePlaylist = (id: string, profile: string, toIndex: number) => post(`/api/playlists/${enc(id)}/move`, { profile, toIndex });
export const addToPlaylist = (id: string, profile: string, streamIds: string[]) => post(`/api/playlists/${enc(id)}/add`, { profile, streamIds });
export const removeFromPlaylist = (id: string, profile: string, userChannelIds: string[]) => post(`/api/playlists/${enc(id)}/remove`, { profile, userChannelIds });
export const movePlaylistItem = (id: string, uc: string, profile: string, toIndex: number) => post(`/api/playlists/${enc(id)}/items/${enc(uc)}/move`, { profile, toIndex });

// ---- Groups ----
export const getGroups = (profile: string) => read(S.GroupsView, `/api/groups?${q(profile)}`);
export const editGroup = (body: { sourceId: string; group: string; profiles: string[]; hidden?: boolean; name?: string; clearName?: boolean; sort?: string }) =>
  post("/api/groups/edit", body);
export const moveGroup = (sourceId: string, group: string, profile: string, toIndex: number) => post("/api/groups/move", { sourceId, group, profile, toIndex });

// ---- Guide mapping ----
export const nextUnmapped = (profile: string, skip: string[]) =>
  send<unknown>("/api/mapping/next", { method: "POST", body: JSON.stringify({ profile, skip }) }).then((r) => S.QueueView.parse(r));
export const searchGuide = (text: string) => read(S.GuideChannelInfo.array(), `/api/mapping/search?q=${enc(text)}`);
export const setMapping = (streamId: string, epgSourceId: string, guideChannelId: string | null) => post("/api/mapping", { streamId, epgSourceId, guideChannelId });
export const resetMapping = (uc: string) => del(`/api/mapping/${enc(uc)}`);
export const getManualMappings = () => read(S.ManualMapping.array(), "/api/mapping/manual");

// ---- Reminders ----
export const getReminders = (profile: string) => read(S.ReminderView.array(), `/api/reminders?${q(profile)}`);
export const deleteReminder = (id: string, profile: string) => del(`/api/reminders/${enc(id)}?${q(profile)}`);

// ---- Settings ----
export const getSettings = (profile: string) => read(S.SettingsView, `/api/settings?${q(profile)}`);
export const putSetting = (scope: "household" | "profile", key: string, value: string | null, profiles: string[]) => put("/api/settings", { scope, key, value, profiles });
export const putCompanionSettings = (v: S.CompanionSettingsView) => send<void>("/api/settings/companion", { method: "PUT", body: JSON.stringify(v) });

// ---- Devices & profiles ----
export const ownProfile = (deviceId: string) => send<unknown>(`/api/devices/${enc(deviceId)}/own-profile`, { method: "POST" });
export const copySections = (from: string, to: string[], sections: string[], replace: boolean) => post("/api/profiles/copy", { from, to, sections, replace });

// ---- Backups ----
export const getSnapshotSummary = (id: number) => read(S.SnapshotSummary, `/api/snapshots/${id}/summary`);
export const restorePart = (id: number, part: "all" | "profile" | "device", target?: string) =>
  send<{ changes: number }>(`/api/snapshots/${id}/restore-part`, { method: "POST", body: JSON.stringify({ part, id: target }) });
export const importSettings = (profile: string, file: unknown) => post("/api/sync/import", { profile, file: S.SettingsFile.parse(file) });

// ---- Sources ----
export interface SourceForm {
  name: string;
  kind: "M3U" | "XTREAM";
  url: string;
  username: string;
  password: string;
  userAgent: string;
  referrer: string;
  enabled: boolean;
  liveFormat: "TS" | "HLS";
  connectionLimit: number;
}

export const getSources = () => read(S.SourcesView, "/api/sources");
export const revealPassword = (id: string) => send<{ password: string }>(`/api/sources/${enc(id)}/password`);
export const addSource = (f: SourceForm) => post("/api/sources", f);
export const updateSource = (id: string, f: SourceForm) => put(`/api/sources/${enc(id)}`, f);
export const setSourceEnabled = (id: string, enabled: boolean) => post(`/api/sources/${enc(id)}/enabled`, { enabled });
export const deleteSource = (id: string) => del(`/api/sources/${enc(id)}`);
export const testSource = (f: SourceForm) => send<{ result: string }>("/api/sources/test", { method: "POST", body: JSON.stringify(f) });
export const refreshSource = (id: string) => send<void>(`/api/sources/${enc(id)}/refresh`, { method: "POST" });
export const addEpgSource = (name: string, url: string) => post("/api/epg-sources", { name, url });
export const updateEpgSource = (id: string, f: { name?: string; url?: string; enabled?: boolean; timeShiftMinutes?: number }) => put(`/api/epg-sources/${enc(id)}`, f);
export const moveEpgSource = (id: string, toIndex: number) => post(`/api/epg-sources/${enc(id)}/move`, { profile: "main", toIndex });
export const deleteEpgSource = (id: string) => del(`/api/epg-sources/${enc(id)}`);
export const setDefaultEpg = (id: string) => put("/api/epg-sources/default", { id });

/** "Sports Center" with its invisible and look-alike characters marked by code point (PLAN §4.3.18 → Channels). */
export function revealHidden(text: string): string {
  return Array.from(text).map((ch) => {
    const code = ch.codePointAt(0)!;
    const plainAscii = code >= 0x20 && code < 0x7f;
    return plainAscii ? ch : `⟨U+${code.toString(16).toUpperCase().padStart(4, "0")}⟩`;
  }).join("");
}

/** "6:00 PM", for program times. */
export function clock(at: number): string {
  return new Date(at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

/** Moves item [from] to [to] in a copy of [list]: for optimistic drag-to-reorder. */
export function moved<T>(list: T[], from: number, to: number): T[] {
  const out = list.slice();
  const [item] = out.splice(from, 1);
  out.splice(to, 0, item);
  return out;
}
