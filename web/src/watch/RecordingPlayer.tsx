import Hls from "hls.js";
import { useEffect, useRef, useState } from "react";
import type { Recording } from "../schema";

/** How often a viewer's place is saved while it plays. */
const SAVE_EVERY_MS = 30_000;

export const canPlay = (r: Recording) => r.durationMs > 0 && r.state !== "SCHEDULED" && r.state !== "FAILED";

/** Where to pick up: nowhere once it's been watched, or at the very end. The TV goes by the same rule. */
export const resumeMs = (r: Recording) => (r.watchedAt == null && r.positionMs > 0 && r.positionMs < r.durationMs ? r.positionMs : 0);

const length = (ms: number) => {
  const minutes = Math.floor(ms / 60_000);
  if (ms < 60_000) return `${Math.floor(ms / 1000)} s`;
  if (minutes < 60) return `${minutes} min`;
  return minutes % 60 === 0 ? `${minutes / 60} hr` : `${Math.floor(minutes / 60)} hr ${minutes % 60} min`;
};

/** What a recording is doing, in a few words, as the TV's Recordings page says it. */
export function recordingLine(r: Recording): string {
  const state: Record<Recording["state"], string | null> = {
    SCHEDULED: "Scheduled",
    WAITING: "Waiting for a free source",
    RECORDING: "Recording now",
    FAILED: r.reason ?? "Failed",
    PARTIAL: `Missing ${length(r.gapMs)}`,
    DONE: null,
  };
  return [state[r.state], canPlay(r) ? length(r.durationMs) : null, r.watchedAt != null ? "Watched" : null].filter(Boolean).join(" · ");
}

/**
 * True when what reached the browser has no sound. hls.js drops AC3 it can't play without an error:
 * the track never arrives, the video shows as muted, and it can't be unmuted (dvr.md, ABC 7, 2026-11-06).
 */
export const noSound = (tracks: { audio?: unknown; audiovideo?: unknown }) => !tracks.audio && !tracks.audiovideo;

/**
 * Plays one recording from the NAS. [headers] carries a viewer's key: on the request, never in the
 * URL (dvr.md). A recording still running is an EVENT playlist, so the start is always said, or it
 * would open near the live end.
 */
export default function RecordingPlayer({ url, startMs, headers, onPosition }: {
  url: string;
  startMs: number;
  headers?: Record<string, string>;
  onPosition?: (positionMs: number) => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    const el = video.current;
    if (!el) return;
    setError(null);
    setNote(null);
    let hls: Hls | null = null;
    const save = () => { if (el.currentTime > 0) onPosition?.(Math.round(el.currentTime * 1000)); };
    const play = () => el.play().catch((e: DOMException) => {
      if (e.name === "NotAllowedError") { el.muted = true; el.play().catch(() => undefined); }
    });
    if (Hls.isSupported()) {
      hls = new Hls({
        startPosition: startMs / 1000,
        xhrSetup: (xhr) => { Object.entries(headers ?? {}).forEach(([k, v]) => xhr.setRequestHeader(k, v)); },
      });
      let recovered = false;
      hls.on(Hls.Events.BUFFER_CODECS, (_e, data) => {
        if (noSound(data)) setNote("This browser can't play this recording's sound, which is usually AC3. It plays with sound on the TV.");
      });
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; hls?.recoverMediaError(); return; }
        hls?.destroy();
        hls = null;
        setError(data.type === Hls.ErrorTypes.NETWORK_ERROR ? "The companion didn't send the recording." : "The browser can't play this recording.");
      });
      hls.loadSource(url);
      hls.attachMedia(el);
      void play();
    } else if (!headers && el.canPlayType("application/vnd.apple.mpegurl")) {
      el.src = url; // Safari plays HLS itself, but can't add a key to its requests.
      el.currentTime = startMs / 1000;
      void play();
    } else setError("This browser can't play recordings.");
    const timer = setInterval(save, SAVE_EVERY_MS);
    el.addEventListener("pause", save);
    window.addEventListener("pagehide", save);
    return () => {
      clearInterval(timer);
      el.removeEventListener("pause", save);
      window.removeEventListener("pagehide", save);
      save();
      hls?.destroy();
      el.removeAttribute("src");
      el.load();
    };
  }, [url, startMs, headers, onPosition]);

  return (
    <>
      <div className="watch-video">
        <video ref={video} controls playsInline />
        {error && <div className="watch-overlay error-text"><p>{error}</p></div>}
      </div>
      {note && <p className="detail small">{note}</p>}
    </>
  );
}
