import { describe, expect, it } from "vitest";
import { ago, cleanCode, health, itemSummary, size } from "./api";

describe("health", () => {
  it("is ok only with the database and the secret key", () => {
    expect(health({ version: "1", database: "ok", secretKey: true }, false)).toBe("ok");
    expect(health({ version: "1", database: "unreachable", secretKey: true }, false)).toBe("warn");
    expect(health({ version: "1", database: "ok", secretKey: false }, false)).toBe("warn");
    expect(health(null, true)).toBe("error");
  });
});

describe("cleanCode", () => {
  it("keeps the six digits", () => {
    expect(cleanCode("123 456")).toBe("123456");
    expect(cleanCode("12-34-567")).toBe("123456");
    expect(cleanCode("abc")).toBe("");
  });
});

describe("ago", () => {
  const now = 1_000_000_000_000;
  it("reads like a person would say it", () => {
    expect(ago(now - 10_000, now)).toBe("just now");
    expect(ago(now - 5 * 60_000, now)).toBe("5 min ago");
    expect(ago(now - 3 * 3_600_000, now)).toBe("3 h ago");
  });
});

describe("size", () => {
  it("picks KB or MB", () => {
    expect(size(830 * 1024)).toBe("830 KB");
    expect(size(14.2 * 1024 * 1024)).toBe("14.2 MB");
    expect(size(10)).toBe("1 KB");
  });
});

describe("itemSummary", () => {
  it("adds up each type across scopes", () => {
    expect(itemSummary({ "source/household": 2, "favorite/profile": 12, "setting/profile": 4 })).toBe("2 sources · 12 favorites");
    expect(itemSummary({ "reminder/profile": 1 })).toBe("1 reminder");
    expect(itemSummary({})).toBe("Nothing synced yet");
  });
});
