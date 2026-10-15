import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { getSync, type ProfileSummary } from "./api";
import { undo as undoEdit } from "./admin";
import type { EditResult } from "./schema";

// ---- Profiles (PLAN §4.3.18 → Layout): the top bar's picker is the profile pages read, and the
// default "Apply to" target for profile-scope edits (PLAN §9.1). ----

interface ProfileState {
  profiles: ProfileSummary[];
  /** The profile pages show. */
  profile: string;
  setProfile: (id: string) => void;
  /** Where profile-scope edits land: the picked profile unless more are ticked. */
  applyTo: string[];
  setApplyTo: (ids: string[]) => void;
  reloadProfiles: () => void;
}

const ProfileContext = createContext<ProfileState | null>(null);

export function stored(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
export function store(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* private window: per-visit only */ }
}

export function ProfileProvider({ children }: { children: ReactNode }) {
  const [profiles, setProfiles] = useState<ProfileSummary[]>([]);
  const [profile, setProfileState] = useState(() => stored("signal.profile") ?? "main");
  const [applyTo, setApplyTo] = useState<string[]>([profile]);

  const reloadProfiles = useCallback(() => {
    getSync().then((s) => setProfiles(s.profiles)).catch(() => undefined);
  }, []);
  useEffect(reloadProfiles, [reloadProfiles]);

  // A deleted profile falls back to Main.
  useEffect(() => {
    if (profiles.length && !profiles.some((p) => p.id === profile)) setProfile("main");
  });

  const setProfile = useCallback((id: string) => {
    setProfileState(id);
    setApplyTo([id]);
    store("signal.profile", id);
  }, []);

  const value = useMemo(() => ({ profiles, profile, setProfile, applyTo, setApplyTo, reloadProfiles }), [profiles, profile, setProfile, applyTo, reloadProfiles]);
  return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>;
}

export function useProfiles(): ProfileState {
  const ctx = useContext(ProfileContext);
  if (!ctx) throw new Error("useProfiles outside ProfileProvider");
  return ctx;
}

/** "Main", "Main and Kids", "all 3 profiles". */
export function profileNames(ids: string[], profiles: ProfileSummary[]): string {
  const names = ids.map((id) => profiles.find((p) => p.id === id)?.name ?? id);
  if (profiles.length > 2 && ids.length === profiles.length) return `all ${profiles.length} profiles`;
  return names.length <= 1 ? names.join("") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

// ---- Saving (PLAN §4.3.18 → Saving): edits save at once, with a short toast and Undo. ----

interface Toast {
  id: number;
  text: string;
  undo?: EditResult["undo"];
  error?: boolean;
}

interface ToastState {
  /** Runs an edit, then says so with Undo; [after] reloads the page. */
  save: (what: string, edit: () => Promise<EditResult | void>, after?: () => void) => Promise<boolean>;
  say: (text: string, error?: boolean) => void;
}

const ToastContext = createContext<ToastState | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const afterRef = useRef<(() => void) | undefined>(undefined);

  const show = useCallback((t: Omit<Toast, "id">) => {
    window.clearTimeout(timer.current);
    setToast({ ...t, id: Date.now() });
    timer.current = window.setTimeout(() => setToast(null), t.undo?.length ? 8000 : 4000);
  }, []);

  const say = useCallback((text: string, error = false) => show({ text, error }), [show]);

  const save = useCallback(async (what: string, edit: () => Promise<EditResult | void>, after?: () => void) => {
    try {
      const result = await edit();
      afterRef.current = after;
      show({ text: what, undo: result && result.undo.length ? result.undo : undefined });
      after?.();
      return true;
    } catch (e) {
      show({ text: (e as Error).message, error: true });
      return false;
    }
  }, [show]);

  async function runUndo(ops: EditResult["undo"]) {
    try {
      await undoEdit(ops);
      show({ text: "Undone" });
      afterRef.current?.();
    } catch (e) {
      show({ text: (e as Error).message, error: true });
    }
  }

  const value = useMemo(() => ({ save, say }), [save, say]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {toast && (
        <div className={`toast${toast.error ? " error" : ""}`} role="status" key={toast.id}>
          <span>{toast.text}</span>
          {toast.undo && <button onClick={() => runUndo(toast.undo!)}>Undo</button>}
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastState {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast outside ToastProvider");
  return ctx;
}

/**
 * "Apply to" (PLAN §9.1): which profiles a profile-scope edit lands in. Each profile with its
 * devices, plus All. Hidden when there's only Main.
 */
export function ApplyTo() {
  const { profiles, applyTo, setApplyTo, profile } = useProfiles();
  if (profiles.length < 2) return null;
  const toggle = (id: string) => {
    const next = applyTo.includes(id) ? applyTo.filter((x) => x !== id) : [...applyTo, id];
    setApplyTo(next.length ? next : [profile]);
  };
  const all = applyTo.length === profiles.length;
  return (
    <div className="apply-to" role="group" aria-label="Apply to">
      <span className="detail">Apply to</span>
      {profiles.map((p) => (
        <label key={p.id} className={applyTo.includes(p.id) ? "chip on" : "chip"} title={p.devices.length ? p.devices.join(", ") : "No devices"}>
          <input type="checkbox" checked={applyTo.includes(p.id)} onChange={() => toggle(p.id)} />
          {p.name}{p.devices.length ? `: ${p.devices.join(", ")}` : ""}
        </label>
      ))}
      <button className={all ? "chip on" : "chip"} onClick={() => setApplyTo(all ? [profile] : profiles.map((p) => p.id))}>All</button>
    </div>
  );
}
