import { describe, expect, it } from "vitest";
import type { LoginActivityView, SourceActivityView, UseView } from "../schema";
import { inUseValue, lasting, overLimit, playingLine, providerNote } from "./SourceCards";

const use = (over: Partial<UseView> = {}): UseView => ({
  who: "TV", name: "Living Room", kind: "LIVE", title: "ESPN", subtitle: null, program: null, paused: false, since: 0, recordingId: null, ...over,
});
const login = (over: Partial<LoginActivityView> = {}): LoginActivityView => ({ id: "main", name: "Main", providerActive: null, providerLimit: null, uses: [], ...over });
const source = (logins: LoginActivityView[], limit: number | null = null): SourceActivityView => ({ id: "s", name: "Provider", limit, logins });

describe("source cards", () => {
  it("says what's playing in one line", () => {
    expect(playingLine(use({ program: "College Football" }))).toBe("ESPN · College Football");
    expect(playingLine(use({ who: "RECORDING", title: "FXX", program: "Futurama" }))).toBe("Futurama on FXX");
    expect(playingLine(use({ kind: "EPISODE", title: "The Office", subtitle: "S2 E3 · Office Olympics", paused: true }))).toBe("The Office · S2 E3 · Office Olympics (paused)");
    expect(playingLine(use({ kind: "MOVIE", title: null }))).toBe("A movie");
  });

  it("counts against the limit, or the logins' own when the source has none", () => {
    expect(inUseValue(source([login()], 2))).toBe("Idle");
    expect(inUseValue(source([login({ uses: [use()] })], 2))).toBe("1 of 2 in use");
    expect(inUseValue(source([login({ uses: [use()], providerLimit: 1 }), login({ id: "a", providerLimit: 1 })]))).toBe("1 of 2 in use");
    expect(inUseValue(source([login({ uses: [use(), use()] })]))).toBe("2 in use");
    expect(overLimit(source([login({ uses: [use(), use()], providerLimit: 1 })]))).toBe(true);
    expect(overLimit(source([login({ uses: [use()] })], 1))).toBe(false);
  });

  it("points out streams the provider counts that Signal doesn't know of", () => {
    expect(providerNote(login({ providerActive: 1, uses: [use()] }))).toBeNull();
    expect(providerNote(login({ providerActive: 2, providerLimit: 2, uses: [use()] }))).toBe("The provider counts 2 of 2 in use: 1 isn't from Signal, or just stopped.");
    expect(providerNote(login({ providerActive: 3 }))).toBe("The provider counts 3 in use: 3 aren't from Signal, or just stopped.");
  });

  it("reads durations naturally", () => {
    expect(lasting(0, 30_000)).toBe("just started");
    expect(lasting(0, 12 * 60_000)).toBe("12 min");
    expect(lasting(0, 65 * 60_000)).toBe("1 h 5 min");
    expect(lasting(0, 120 * 60_000)).toBe("2 h");
  });
});
