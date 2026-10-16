import { useEffect, type ReactNode } from "react";

/**
 * A side panel over the page (PLAN §4.3.18): a click outside it, or Escape, closes it (user,
 * 2026-10-16), as well as its own Close link.
 */
export default function Drawer({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape" && !e.defaultPrevented) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="drawer" aria-label={label} role="dialog">
        <button className="link close" onClick={onClose}>Close</button>
        {children}
      </aside>
    </>
  );
}
