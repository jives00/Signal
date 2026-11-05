// The viewer's side of the companion (server/ViewerRoutes.kt, web-player.md). It pairs like a TV, and
// its key is what says which profile it shows.
import type { z } from "zod";
import * as S from "../schema";
import type { WatchApi } from "../watch/WatchScreen";
import { WEB_VIEWER } from "./model";

const KEY = "signal.viewer.key";
const ID = "signal.viewer.id";

export const savedKey = () => localStorage.getItem(KEY);
export const saveKey = (key: string) => localStorage.setItem(KEY, key);

/** This browser's device ID. crypto.randomUUID needs HTTPS, and the viewer is on plain HTTP. */
function deviceId(): string {
  const saved = localStorage.getItem(ID);
  if (saved) return saved;
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  localStorage.setItem(ID, id);
  return id;
}

export interface Pairing { pairingId: string; code: string; expiresInSeconds: number }
export interface PairStatus { state: "PENDING" | "APPROVED" | "EXPIRED"; apiKey?: string | null }

async function plain<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
  return (await response.json()) as T;
}

export const startPairing = (name: string) => plain<Pairing>("/device/pair", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ deviceId: deviceId(), name, model: WEB_VIEWER, appVersion: "web" }),
});
export const pairingStatus = (id: string) => plain<PairStatus>(`/device/pair/${encodeURIComponent(id)}`);

/** The calls a paired viewer makes. A 401 means it was forgotten in the web admin: [onForgotten]. */
export function viewerApi(key: string, onForgotten: () => void): WatchApi {
  const call = async (path: string, init?: RequestInit) => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${key}` } });
    if (response.status === 401) {
      localStorage.removeItem(KEY);
      onForgotten();
    }
    if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
    return response;
  };
  const read = async <T extends z.ZodTypeAny>(schema: T, path: string): Promise<z.infer<T>> => schema.parse(await (await call(path)).json());
  const enc = encodeURIComponent;
  return {
    lists: () => read(S.ListsView, "/viewer/lists"),
    groups: () => read(S.GroupsView, "/viewer/groups"),
    channels: (f) => {
      const p = new URLSearchParams({ limit: String(f.limit) });
      if (f.q) p.set("q", f.q);
      if (f.source) p.set("source", f.source);
      if (f.group) p.set("group", f.group);
      return read(S.ChannelPage, `/viewer/channels?${p}`).then((page) => page.rows);
    },
    channel: (id) => read(S.ChannelRow, `/viewer/channels/${enc(id)}`),
    stream: (id) => read(S.WatchStream, `/viewer/watch/${enc(id)}`),
    playing: () => { call("/viewer/playing", { method: "POST" }).catch(() => undefined); },
  };
}
