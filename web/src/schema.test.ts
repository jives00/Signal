// The contract test (PLAN §5.2 → Types): fixtures written by server/ContractTest.kt from the real
// Kotlin classes must parse with the Zod mirror. A Kotlin field renamed without schema.ts fails here.
import { describe, expect, it } from "vitest";
import type { ZodTypeAny } from "zod";
import * as S from "./schema";
import channelPage from "./contract/channel-page.json";
import editResult from "./contract/edit-result.json";
import groups from "./contract/groups.json";
import lists from "./contract/lists.json";
import overview from "./contract/overview.json";
import queue from "./contract/queue.json";
import settings from "./contract/settings.json";
import settingsFile from "./contract/settings-file.json";
import sources from "./contract/sources.json";

const cases: [string, ZodTypeAny, unknown][] = [
  ["settings-file", S.SettingsFile, settingsFile],
  ["edit-result", S.EditResult, editResult],
  ["channel-page", S.ChannelPage, channelPage],
  ["sources", S.SourcesView, sources],
  ["groups", S.GroupsView, groups],
  ["lists", S.ListsView, lists],
  ["queue", S.QueueView, queue],
  ["settings", S.SettingsView, settings],
  ["overview", S.Overview, overview],
];

describe("contract", () => {
  it.each(cases)("%s parses with its schema", (_, schema, fixture) => {
    const result = schema.safeParse(fixture);
    expect(result.success ? "ok" : JSON.stringify(result.error.issues)).toBe("ok");
  });

  it("schemas don't silently drop fields the Kotlin side sends", () => {
    // Every key in a fixture row is one the mirror knows.
    const row = (channelPage as { rows: Record<string, unknown>[] }).rows[0];
    const known = Object.keys(S.ChannelRow.shape);
    expect(Object.keys(row).filter((k) => !known.includes(k))).toEqual([]);
  });
});
