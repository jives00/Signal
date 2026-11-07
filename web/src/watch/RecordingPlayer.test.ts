import { describe, expect, it } from "vitest";
import type { Recording } from "../schema";
import { ordered } from "../viewer/Recordings";
import { canPlay, recordingLine, resumeMs, silentAudio } from "./RecordingPlayer";

const rec = (over: Partial<Recording> = {}): Recording => ({
  id: "r", profileId: "main", streamId: "s", title: "Show", channelName: "C", startMs: 0, stopMs: 1, state: "DONE",
  loginName: null, endedAt: null, updatedAt: 0, bytes: 1, durationMs: 3_600_000, gaps: 0, gapMs: 0, reason: null,
  expiresAt: null, programStart: null, programStop: null, extraMs: 0, positionMs: 0, watchedAt: null, ...over,
});

describe("recordings in a browser", () => {
  it("plays only what has video", () => {
    expect(canPlay(rec())).toBe(true);
    expect(canPlay(rec({ state: "RECORDING" }))).toBe(true);
    expect(canPlay(rec({ state: "RECORDING", durationMs: 0 }))).toBe(false);
    expect(canPlay(rec({ state: "SCHEDULED" }))).toBe(false);
    expect(canPlay(rec({ state: "FAILED" }))).toBe(false);
  });

  it("picks up where it left off, as the TV does", () => {
    expect(resumeMs(rec({ positionMs: 1_000 }))).toBe(1_000);
    expect(resumeMs(rec({ positionMs: 1_000, watchedAt: 5 }))).toBe(0);
    expect(resumeMs(rec({ positionMs: 3_600_000 }))).toBe(0);
  });

  it("says what matters", () => {
    expect(recordingLine(rec())).toBe("1 hr");
    expect(recordingLine(rec({ state: "PARTIAL", gapMs: 12_000, durationMs: 82 * 60_000 }))).toBe("Missing 12 s · 1 hr 22 min");
    expect(recordingLine(rec({ watchedAt: 1, durationMs: 45 * 60_000 }))).toBe("45 min · Watched");
    expect(recordingLine(rec({ state: "SCHEDULED", durationMs: 0 }))).toBe("Scheduled");
    expect(recordingLine(rec({ state: "FAILED", reason: "No source was free.", durationMs: 0 }))).toBe("No source was free.");
  });

  it("warns only about sound this browser can't play", () => {
    expect(silentAudio("ac-3", () => false)).toBe(true);
    expect(silentAudio("ec-3", () => false)).toBe(true);
    expect(silentAudio("ac-3", () => true)).toBe(false);
    expect(silentAudio("mp4a.40.2", () => false)).toBe(false);
    expect(silentAudio(undefined, () => false)).toBe(false);
  });

  it("orders what's on, what's coming, then newest saved", () => {
    const rows = [rec({ id: "old", startMs: 1 }), rec({ id: "new", startMs: 2 }), rec({ id: "later", state: "SCHEDULED", startMs: 9 }), rec({ id: "soon", state: "SCHEDULED", startMs: 5 }), rec({ id: "on", state: "RECORDING", startMs: 3 })];
    expect(ordered(rows).map((r) => r.id)).toEqual(["on", "soon", "later", "new", "old"]);
  });
});
