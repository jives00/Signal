import { describe, expect, it } from "vitest";
import { health } from "./api";

describe("health", () => {
  it("is ok only with the database and the secret key", () => {
    expect(health({ version: "1", database: "ok", secretKey: true }, false)).toBe("ok");
    expect(health({ version: "1", database: "unreachable", secretKey: true }, false)).toBe("warn");
    expect(health({ version: "1", database: "ok", secretKey: false }, false)).toBe("warn");
    expect(health(null, true)).toBe("error");
  });
});
