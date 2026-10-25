// The hand-written Zod mirror of the companion's Kotlin types (PLAN §5.2 → Types). Responses are
// parsed with these, so a drift fails loudly here instead of half-rendering a page. The contract
// test (schema.test.ts) checks them against fixtures the Kotlin side writes (ContractTest.kt).
import { z } from "zod";

const json = z.union([z.string(), z.number(), z.boolean(), z.null()]);

/** shared/sync/SyncProtocol.kt → SyncItem. */
export const SyncItem = z.object({
  type: z.string(),
  id: z.string(),
  scope: z.enum(["household", "profile", "device"]),
  owner: z.string().default(""),
  fields: z.record(json).default({}),
  deleted: z.boolean().default(false),
  rev: z.number().default(0),
  updatedAt: z.number().default(0),
});

/** SettingsFile: the export and import format (PLAN §9.2 → Fallback). */
export const SettingsFile = z.object({
  format: z.literal("signal-settings"),
  schema: z.number(),
  exportedAt: z.number(),
  items: z.array(SyncItem),
});

/** server/SyncService.kt → AdminOp and EditResult. */
export const AdminOp = z.object({
  type: z.string(),
  id: z.string(),
  scope: z.string(),
  owner: z.string().default(""),
  fields: z.record(json).default({}),
  deleted: z.boolean().default(false),
});
export const EditResult = z.object({ rev: z.number(), changed: z.number(), undo: z.array(AdminOp) });

export const ProgramBrief = z.object({ title: z.string(), start: z.number(), stop: z.number() });

export const GuideStatusView = z.object({
  epgSourceId: z.string(),
  epgSourceName: z.string(),
  guideChannelId: z.string(),
  guideChannelName: z.string().nullable().optional(),
  how: z.enum(["manual", "auto"]),
  override: z.boolean(),
  fallback: z.boolean(),
});

/** server/AdminView.kt → ChannelRow. */
export const ChannelRow = z.object({
  streamId: z.string(),
  sourceId: z.string(),
  sourceName: z.string(),
  group: z.string(),
  rawName: z.string(),
  name: z.string(),
  number: z.number(),
  logo: z.string().nullable(),
  tvgId: z.string().nullable(),
  userChannelId: z.string().nullable(),
  favorite: z.boolean(),
  playlists: z.array(z.string()),
  hidden: z.boolean(),
  groupHidden: z.boolean(),
  customName: z.string().nullable(),
  customLogo: z.string().nullable(),
  customNumber: z.number().nullable(),
  /** Names to say it by in a voice command, comma-separated (PLAN §4.3.14 → Voice names). */
  voiceNames: z.string().nullable().optional(),
  guide: GuideStatusView.nullable(),
  now: ProgramBrief.nullable().optional(),
  next: ProgramBrief.nullable().optional(),
  unavailable: z.boolean().default(false),
});
export const ChannelPage = z.object({ total: z.number(), offset: z.number(), rows: z.array(ChannelRow) });
/** server/WatchRoutes.kt → WatchStream: the provider's own URL for the Watch page. */
export const WatchStream = z.object({ url: z.string(), hls: z.boolean(), headersDropped: z.boolean() });

export const SourceView = z.object({
  id: z.string(),
  name: z.string(),
  kind: z.enum(["M3U", "XTREAM", "DEMO"]),
  url: z.string(),
  username: z.string(),
  hasPassword: z.boolean(),
  userAgent: z.string().nullable(),
  referrer: z.string().nullable(),
  enabled: z.boolean(),
  liveFormat: z.string(),
  connectionLimit: z.number(),
  lastRefreshedAt: z.number(),
  lastError: z.string().nullable(),
  streams: z.number(),
  refreshing: z.boolean(),
  providerConnections: z.number().nullable().default(null),
  expiresAt: z.number().nullable().default(null),
  /** Sources per profile (PLAN §9.1): the profiles that leave this source out. */
  hiddenFor: z.array(z.string()).default([]),
});
export const EpgSourceView = z.object({
  id: z.string(),
  name: z.string(),
  kind: z.enum(["XMLTV", "XTREAM", "DEMO"]),
  url: z.string(),
  sourceId: z.string().nullable(),
  enabled: z.boolean(),
  timeShiftMinutes: z.number(),
  isDefault: z.boolean(),
  channels: z.number(),
  programs: z.number(),
  error: z.string().nullable(),
  refreshedAt: z.number().nullable(),
  refreshing: z.boolean().default(false),
});
export const SourcesView = z.object({ sources: z.array(SourceView), epgSources: z.array(EpgSourceView), guideBuilding: z.boolean() });

export const GroupView = z.object({
  sourceId: z.string(),
  sourceName: z.string(),
  name: z.string(),
  title: z.string(),
  count: z.number(),
  hidden: z.boolean(),
  channelSort: z.string().nullable(),
  customName: z.string().nullable(),
});
export const GroupsView = z.object({ groupSort: z.string(), channelSort: z.string(), groups: z.array(GroupView) });

export const ListsView = z.object({
  favorites: z.array(ChannelRow),
  playlists: z.array(z.object({ id: z.string(), name: z.string(), channels: z.array(ChannelRow), sort: z.enum(["A_Z", "NUMBER"]).nullable().optional() })),
  favoritesSort: z.enum(["A_Z", "NUMBER"]).nullable().optional(),
});

export const GuideChannelInfo = z.object({
  epgSourceId: z.string(),
  epgSourceName: z.string(),
  id: z.string(),
  displayName: z.string(),
  score: z.number().default(0),
  now: ProgramBrief.nullable().optional(),
  next: ProgramBrief.nullable().optional(),
});
export const QueueView = z.object({ remaining: z.number(), channel: ChannelRow.nullable(), suggestions: z.array(GuideChannelInfo) });
export const ManualMapping = z.object({
  userChannelId: z.string(),
  channelName: z.string(),
  epgSourceId: z.string(),
  epgSourceName: z.string(),
  guideChannelId: z.string().nullable(),
  guideChannelName: z.string().nullable(),
});

export const ReminderView = z.object({ id: z.string(), title: z.string(), start: z.number(), stop: z.number(), channelName: z.string() });

export const CompanionSettingsView = z.object({
  buildTime: z.string(),
  timeZone: z.string(),
  snapshotsToKeep: z.number(),
  tombstoneDays: z.number(),
  newDeviceProfile: z.enum(["main", "own"]),
  tmdbKeySet: z.boolean().default(false),
  tmdbKey: z.string().nullable().default(null),
  imageCacheGb: z.number().default(20),
  githubTokenSet: z.boolean().default(false),
  githubToken: z.string().nullable().default(null),
});
export const SettingsView = z.object({
  household: z.record(z.string()),
  profile: z.record(z.string()),
  companion: CompanionSettingsView,
});

export const CleanupPreview = z.object({
  presets: z.array(z.object({ id: z.string(), label: z.string(), example: z.string() })),
  channels: z.number(),
  channelsChanged: z.number(),
  groups: z.number(),
  groupsChanged: z.number(),
  channelSamples: z.array(z.object({ before: z.string(), after: z.string() })),
  groupSamples: z.array(z.object({ before: z.string(), after: z.string() })),
  invalidRules: z.array(z.string()).default([]),
});

export const JobRun = z.object({
  id: z.number(),
  job: z.string(),
  startedAt: z.number(),
  finishedAt: z.number().nullable(),
  result: z.string().nullable(),
  detail: z.string().nullable(),
});

export const Device = z.object({
  id: z.string(),
  name: z.string(),
  model: z.string(),
  appVersion: z.string(),
  pairedAt: z.number(),
  lastSeenAt: z.number(),
  profileId: z.string().default("main"),
  lastSyncAt: z.number().default(0),
});

export const Overview = z.object({
  sources: z.array(SourceView),
  coverage: z.object({ mapped: z.number(), total: z.number() }),
  unmappedFavorites: z.number(),
  unavailableFavorites: z.number(),
  failedJobs: z.array(JobRun),
  devices: z.array(Device),
  latestAppVersion: z.string(),
  switchReview: z.number().default(0),
});

export const SnapshotSummary = z.object({ counts: z.record(z.number()), profiles: z.array(z.string()), devices: z.array(z.string()) });

export type ChannelRow = z.infer<typeof ChannelRow>;
export type ChannelPage = z.infer<typeof ChannelPage>;
export type AdminOp = z.infer<typeof AdminOp>;
export type EditResult = z.infer<typeof EditResult>;
export type SourceView = z.infer<typeof SourceView>;
export type EpgSourceView = z.infer<typeof EpgSourceView>;
export type SourcesView = z.infer<typeof SourcesView>;
export type GroupView = z.infer<typeof GroupView>;
export type GroupsView = z.infer<typeof GroupsView>;
export type ListsView = z.infer<typeof ListsView>;
export type GuideChannelInfo = z.infer<typeof GuideChannelInfo>;
export type QueueView = z.infer<typeof QueueView>;
export type ManualMapping = z.infer<typeof ManualMapping>;
export type ReminderView = z.infer<typeof ReminderView>;
export type SettingsView = z.infer<typeof SettingsView>;
export type CompanionSettingsView = z.infer<typeof CompanionSettingsView>;
export type CleanupPreview = z.infer<typeof CleanupPreview>;
export type JobRun = z.infer<typeof JobRun>;
export type Overview = z.infer<typeof Overview>;
export type SnapshotSummary = z.infer<typeof SnapshotSummary>;
export type ProgramBrief = z.infer<typeof ProgramBrief>;

/** server/AdminSwitch.kt → SwitchView (PLAN §4.3.18 → Provider switch). */
export const MatchView = z.object({
  streamId: z.string(),
  name: z.string(),
  group: z.string(),
  score: z.number(),
  confidence: z.enum(["CONFIDENT", "SUGGESTED"]),
  reasons: z.array(z.string()),
  now: ProgramBrief.nullable().optional(),
});
export const ProposalView = z.object({
  userChannelId: z.string(),
  name: z.string(),
  favorite: z.boolean(),
  fromName: z.string(),
  fromSource: z.string(),
  matches: z.array(MatchView),
});
export const SwitchView = z.object({
  targetSourceId: z.string(),
  targetName: z.string(),
  otherSources: z.array(z.object({ id: z.string(), name: z.string(), enabled: z.boolean() })),
  confident: z.array(ProposalView),
  suggested: z.array(ProposalView),
  unmatched: z.array(z.object({ userChannelId: z.string(), name: z.string(), favorite: z.boolean(), fromSource: z.string() })),
  alreadyLinked: z.number(),
  groups: z.array(z.object({ sourceId: z.string(), sourceName: z.string(), group: z.string(), toGroup: z.string().nullable() })),
});
export type SwitchView = z.infer<typeof SwitchView>;
export type ProposalView = z.infer<typeof ProposalView>;

export const LinkView = z.object({ streamId: z.string(), name: z.string(), group: z.string(), sourceName: z.string(), preferred: z.boolean(), playable: z.boolean() });
export type LinkView = z.infer<typeof LinkView>;

/** server/VodRoutes.kt (PLAN §4.3.12): Movies & Series enrichment. */
export const VodStatus = z.object({
  keySet: z.boolean(),
  titles: z.number(),
  matched: z.number(),
  manual: z.number(),
  unmatched: z.number(),
  running: z.boolean(),
  progress: z.string().nullable(),
  lastRun: JobRun.nullable(),
  imageCacheBytes: z.number(),
});
export const VodRow = z.object({
  itemId: z.string(),
  sourceId: z.string(),
  kind: z.enum(["MOVIE", "SERIES"]),
  /** As the profile's TVs show it, after title cleanup. */
  title: z.string(),
  /** The provider's own name. */
  name: z.string(),
  year: z.number().nullable(),
  /** As the TVs show it (renamed or cleaned up); empty when the provider gave none. */
  category: z.string(),
  /** The provider's category name, what the category filter takes. */
  categoryName: z.string(),
  status: z.enum(["matched", "manual", "unmatched", "pending"]),
  tmdbId: z.number().nullable(),
  tmdbTitle: z.string().nullable(),
  tmdbYear: z.number().nullable(),
});
export const VodRows = z.object({ rows: z.array(VodRow), total: z.number() });
/** server/AdminRoutes.kt → VodCategoryView: a provider movie or series category. */
export const VodCategoryView = z.object({
  sourceId: z.string(),
  sourceName: z.string(),
  kind: z.enum(["MOVIE", "SERIES"]),
  categoryId: z.string(),
  name: z.string(),
  title: z.string(),
  count: z.number(),
  hidden: z.boolean(),
  customName: z.string().nullable(),
});
/** server/AdminRoutes.kt → VodCategoriesView: movies then series, each in the column's order. */
export const VodCategoriesView = z.object({
  sort: z.enum(["PROVIDER", "A_Z", "Z_A", "MANUAL"]),
  categories: z.array(VodCategoryView),
});
export const LibraryRow = z.object({
  key: z.string(),
  kind: z.enum(["MOVIE", "SERIES"]),
  title: z.string(),
  year: z.number().nullable(),
  addedAt: z.number(),
  available: z.boolean(),
});
export const TmdbCandidate = z.object({
  id: z.number(),
  title: z.string(),
  originalTitle: z.string().nullable(),
  year: z.number().nullable(),
  poster: z.string().nullable(),
  overview: z.string().nullable(),
});
export type VodStatus = z.infer<typeof VodStatus>;
export type VodRow = z.infer<typeof VodRow>;
export type VodRows = z.infer<typeof VodRows>;
export type VodCategoryView = z.infer<typeof VodCategoryView>;
export type VodCategoriesView = z.infer<typeof VodCategoriesView>;
export type TmdbCandidate = z.infer<typeof TmdbCandidate>;
export type LibraryRow = z.infer<typeof LibraryRow>;

/** server/Server.kt /api/voice/commands: what can be said to Signal (shared/voice/VoiceHelp.kt). */
export const VoiceHelpGroup = z.object({ title: z.string(), intro: z.string().nullable(), items: z.array(z.string()) });
export const VoiceHelp = z.object({ intro: z.string(), groups: z.array(VoiceHelpGroup) });
export type VoiceHelp = z.infer<typeof VoiceHelp>;

/** server/Releases.kt /api/releases (PLAN §4.3.15 → Release channels): recent app releases. */
export const ReleaseView = z.object({ tag: z.string(), title: z.string(), publishedAt: z.number(), stable: z.boolean(), notes: z.string() });
export const ReleasesView = z.object({ releases: z.array(ReleaseView), tokenSet: z.boolean(), error: z.string().nullable() });
export type ReleaseView = z.infer<typeof ReleaseView>;
export type ReleasesView = z.infer<typeof ReleasesView>;
