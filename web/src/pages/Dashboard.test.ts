import { describe, expect, it } from "vitest";
import { expiryWarnings } from "./Dashboard";

const DAY = 24 * 60 * 60_000;
const NOW = Date.UTC(2026, 10, 6, 18);
const date = (ms: number) => new Date(ms).toLocaleDateString([], { month: "short", day: "numeric" });
const source = (inDays: number | null, over = {}) => ({
  id: "s1", name: "Main", enabled: true,
  // Seconds, as the provider gives it.
  expiresAt: inDays === null ? null : Math.floor((NOW + inDays * DAY) / 1000), ...over,
});
const recording = (inDays: number, over = {}) => ({
  state: "SCHEDULED" as const, title: "Game", streamId: "s1:x:7", startMs: NOW + inDays * DAY, programStart: NOW + inDays * DAY, ...over,
});

describe("expiry warnings", () => {
  it("stays quiet until 30 days out", () => {
    expect(expiryWarnings([source(31)], [], NOW)).toEqual([]);
    expect(expiryWarnings([source(30)], [], NOW)).toEqual([`Main expires in 30 days (${date(NOW + 30 * DAY)})`]);
  });

  it("reads naturally at the end, and says when it's gone", () => {
    expect(expiryWarnings([source(0.5)], [], NOW)[0]).toMatch(/^Main expires in 1 day /);
    expect(expiryWarnings([source(-2)], [], NOW)).toEqual([`Main expired ${date(NOW - 2 * DAY)}`]);
  });

  it("skips what can't expire or isn't in use", () => {
    expect(expiryWarnings([source(null), source(0)], [], NOW).length).toBe(1);
    expect(expiryWarnings([source(5, { enabled: false })], [], NOW)).toEqual([]);
  });

  it("names a scheduled recording past the date, even with the source far from expiring", () => {
    const warnings = expiryWarnings([source(60)], [recording(70)], NOW);
    expect(warnings).toEqual([`Main expires ${date(NOW + 60 * DAY)}, before Game on ${date(NOW + 70 * DAY)}: it may not record`]);
  });

  it("ignores recordings before the date, on another source, or not scheduled", () => {
    expect(expiryWarnings([source(60)], [recording(10)], NOW)).toEqual([]);
    expect(expiryWarnings([source(60)], [recording(70, { streamId: "s2:x:7" })], NOW)).toEqual([]);
    expect(expiryWarnings([source(60)], [recording(70, { state: "DONE" })], NOW)).toEqual([]);
    expect(expiryWarnings([source(60, { id: "s" })], [recording(70)], NOW)).toEqual([]);
  });
});
