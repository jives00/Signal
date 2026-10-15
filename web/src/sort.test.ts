import { describe, expect, it } from "vitest";
import { nextSort, sortRows } from "./sort";

describe("nextSort", () => {
  it("goes ascending, descending, then back to the page's order", () => {
    expect(nextSort({}, "name")).toEqual({ sort: "name", dir: "asc" });
    expect(nextSort({ sort: "name", dir: "asc" }, "name")).toEqual({ sort: "name", dir: "desc" });
    expect(nextSort({ sort: "name", dir: "desc" }, "name")).toEqual({ sort: undefined, dir: undefined });
    expect(nextSort({ sort: "name", dir: "desc" }, "year")).toEqual({ sort: "year", dir: "asc" });
  });
});

describe("sortRows", () => {
  const rows = [{ name: "Channel 10", year: 2001 }, { name: "channel 2", year: null }, { name: "Alpha", year: 1999 }];
  const keys = { name: (r: (typeof rows)[number]) => r.name, year: (r: (typeof rows)[number]) => r.year };

  it("keeps the order it was given when unsorted", () => {
    expect(sortRows(rows, keys, {})).toEqual(rows);
  });

  it("sorts text naturally, ignoring case", () => {
    expect(sortRows(rows, keys, { sort: "name", dir: "asc" }).map((r) => r.name)).toEqual(["Alpha", "channel 2", "Channel 10"]);
    expect(sortRows(rows, keys, { sort: "name", dir: "desc" }).map((r) => r.name)).toEqual(["Channel 10", "channel 2", "Alpha"]);
  });

  it("puts empty values last either way", () => {
    expect(sortRows(rows, keys, { sort: "year", dir: "asc" }).map((r) => r.year)).toEqual([1999, 2001, null]);
    expect(sortRows(rows, keys, { sort: "year", dir: "desc" }).map((r) => r.year)).toEqual([2001, 1999, null]);
  });
});
