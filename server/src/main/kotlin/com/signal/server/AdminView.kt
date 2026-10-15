package com.signal.server

import com.signal.data.Settings
import com.signal.guide.GuideResolver
import com.signal.match.NameCleanup
import com.signal.match.SearchText
import com.signal.model.ChannelSort
import com.signal.model.EpgSource
import com.signal.model.GroupSort
import com.signal.model.GuideRef
import com.signal.model.Source
import com.signal.sync.SyncFields
import com.signal.sync.SyncItem
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.bool
import com.signal.sync.long
import com.signal.sync.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The synced items as one profile sees them (PLAN §9.1): household items plus that profile's. The
 * web admin's pages are built from this and the catalog, the same way the app joins user.db.
 */
class AdminView(items: List<SyncItem>, val profileId: String) {
    private val household = items.filter { it.scope == SyncScope.HOUSEHOLD }
    private val mine = items.filter { it.scope == SyncScope.PROFILE && it.owner == profileId }
    private fun h(type: String) = household.filter { it.type == type }
    private fun p(type: String) = mine.filter { it.type == type }

    /** Sources in the order the app lists them (created first). */
    val sources: List<Pair<Source, Long>> = h(SyncType.SOURCE).map { SyncFields.source(it.id, it.fields) to (it.fields.long("created_at") ?: 0) }.sortedBy { it.second }
    val sourceOrder: Map<String, Int> = sources.withIndex().associate { (i, s) -> s.first.id to i }
    val epgSources: List<EpgSource> = h(SyncType.EPG_SOURCE).map { SyncFields.epgSource(it.id, it.fields) }.sortedBy { it.position }

    val householdSettings: Map<String, String> = h(SyncType.SETTING).mapNotNull { s -> s.fields.str("value")?.let { s.id to it } }.toMap()
    val profileSettings: Map<String, String> = p(SyncType.SETTING).mapNotNull { s -> s.fields.str("value")?.let { s.id to it } }.toMap()
    val defaultEpgId: String? = GuideResolver.defaultId(householdSettings[Settings.Key.DEFAULT_EPG_SOURCE.id], epgSources)
    val nameCleanup: NameCleanup = NameCleanup.parse(profileSettings[Settings.Key.NAME_CLEANUP.id])

    class Link(val userChannelId: String, val streamId: String, val preferred: Boolean)
    private val links = h(SyncType.CHANNEL_LINK).mapNotNull { l ->
        val uc = l.fields.str("user_channel_id") ?: return@mapNotNull null
        val stream = l.fields.str("stream_id") ?: return@mapNotNull null
        Link(uc, stream, l.fields.bool("preferred") != false)
    }
    val userChannelByStream: Map<String, String> = links.associate { it.streamId to it.userChannelId }
    val streamsByUserChannel: Map<String, List<Link>> = links.groupBy { it.userChannelId }.mapValues { (_, l) -> l.sortedByDescending { it.preferred } }

    val mappings: Map<String, GuideResolver.Mapping> = h(SyncType.GUIDE_MAPPING).mapNotNull { m ->
        m.fields.str("epg_source_id")?.let { m.id to GuideResolver.Mapping(it, m.fields.str("guide_channel_id")) }
    }.toMap()

    val channelProfiles: Map<String, JsonObject> = p(SyncType.CHANNEL_PROFILE).associate { it.id to it.fields }
    val favorites: Map<String, String> = p(SyncType.FAVORITE).associate { it.id to (it.fields.str("sort_key") ?: "") }

    class Playlist(val id: String, val name: String, val sortKey: String)
    val playlists: List<Playlist> = p(SyncType.PLAYLIST).map { Playlist(it.id, it.fields.str("name") ?: "Playlist", it.fields.str("sort_key") ?: "") }.sortedBy { it.sortKey }
    /** Playlist ID → (user channel, sort key), in order. */
    val playlistItems: Map<String, List<Pair<String, String>>> = p(SyncType.PLAYLIST_ITEM).mapNotNull { i ->
        val pl = i.fields.str("playlist_id") ?: return@mapNotNull null
        val uc = i.fields.str("user_channel_id") ?: return@mapNotNull null
        Triple(pl, uc, i.fields.str("sort_key") ?: "")
    }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, l) -> l.sortedBy { it.second } }
    private val playlistNamesByChannel: Map<String, List<String>> = playlistItems.flatMap { (pl, items) ->
        val name = playlists.firstOrNull { it.id == pl }?.name ?: return@flatMap emptyList()
        items.map { it.first to name }
    }.groupBy({ it.first }, { it.second })
    fun playlistsOf(uc: String?): List<String> = uc?.let { playlistNamesByChannel[it] }.orEmpty()

    /** By "sourceId|groupName". */
    val groupOverrides: Map<String, JsonObject> = p(SyncType.GROUP_OVERRIDE).associate { it.id to it.fields }
    val reminders: List<SyncItem> = p(SyncType.REMINDER)
    val library: List<SyncItem> = p(SyncType.LIBRARY_ITEM)

    val groupSort: GroupSort get() = profileSettings[Settings.Key.GROUP_SORT.id]?.let { runCatching { GroupSort.valueOf(it) }.getOrNull() } ?: GroupSort.PROVIDER
    val channelSort: ChannelSort get() = profileSettings[Settings.Key.CHANNEL_SORT.id]?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: ChannelSort.PROVIDER
    /** Null is the manual order, as `Settings.favoritesSort()` reads it on the TV. */
    val favoritesSort: ChannelSort? get() = profileSettings[Settings.Key.FAVORITES_SORT.id]?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() }?.takeIf { it != ChannelSort.PROVIDER }

    fun groupKey(sourceId: String, group: String) = "$sourceId|$group"
}

@Serializable
data class GuideStatusView(
    val epgSourceId: String,
    val epgSourceName: String,
    val guideChannelId: String,
    val guideChannelName: String? = null,
    /** "manual" when a mapping names the guide channel, else "auto" (tvg-id or name). */
    val how: String,
    /** The channel has an EPG source override. */
    val override: Boolean,
    val fallback: Boolean,
)

/** One row of the Channels table (PLAN §4.3.18 → Channels). */
@Serializable
data class ChannelRow(
    val streamId: String,
    val sourceId: String,
    val sourceName: String,
    val group: String,
    val rawName: String,
    val name: String,
    val number: Int,
    val logo: String?,
    val tvgId: String?,
    val userChannelId: String?,
    val favorite: Boolean,
    val playlists: List<String>,
    val hidden: Boolean,
    val groupHidden: Boolean,
    val customName: String?,
    val customLogo: String?,
    val customNumber: Int?,
    val guide: GuideStatusView?,
    val now: ProgramBrief? = null,
    val next: ProgramBrief? = null,
    /** A user channel whose streams are all gone (PLAN §2.1 "unavailable"). */
    val unavailable: Boolean = false,
)

@Serializable
data class ChannelPage(val total: Int, val offset: Int, val rows: List<ChannelRow>)

/** Name cleanup's live preview (PLAN §4.3.18 → Name cleanup): unsaved rules against the real names. */
/** [target] VOD previews movie and series titles; the counts then mean titles and categories. */
@Serializable data class CleanupPreviewRequest(val rules: NameCleanup, val target: NameCleanup.Target = NameCleanup.Target.CHANNELS)
@Serializable data class CleanupPresetView(val id: String, val label: String, val example: String)
@Serializable data class CleanupSample(val before: String, val after: String)
@Serializable
data class CleanupPreview(
    val presets: List<CleanupPresetView>,
    val channels: Int,
    val channelsChanged: Int,
    val groups: Int,
    val groupsChanged: Int,
    /** Changed names spread across the list, distinct, before and after. */
    val channelSamples: List<CleanupSample>,
    val groupSamples: List<CleanupSample>,
    /** Regex rules that don't compile, as typed; the TV skips them. */
    val invalidRules: List<String> = emptyList(),
)

data class ChannelFilter(
    val query: String? = null,
    val sourceId: String? = null,
    val group: String? = null,
    val favorites: Boolean = false,
    val playlistId: String? = null,
    /** "hidden" or "visible". */
    val hidden: String? = null,
    val customized: Boolean = false,
    /** "mapped" or "unmapped". */
    val guide: String? = null,
)

/** The Channels table's column sorts; ties keep the app's order. */
enum class ChannelColumn { NUMBER, NAME, SOURCE, GUIDE, LISTS }

/** Channel rows: the catalog's streams joined with a profile's view and the guide. */
class AdminChannels(private val companion: Companion) {
    private var guideCache: Triple<Any?, Map<String, GuideRef?>, Int>? = null

    fun view(profileId: String) = AdminView(companion.sync.liveItems(), profileId)

    /** Every stream's guide, cached until the catalog, the guide build or the household items change. */
    @Synchronized private fun guideRefs(view: AdminView): Map<String, GuideRef?> {
        val key = listOf(companion.catalog.version, companion.guideReader.version(), companion.sync.currentRev())
        guideCache?.takeIf { it.first == key }?.let { return it.second }
        val refs = companion.catalog.streams.associate { s ->
            val uc = view.userChannelByStream[s.id]
            s.id to companion.guideReader.resolve(uc?.let { view.mappings[it] }, s.tvgId, s.rawName, view.epgSources, view.defaultEpgId)
        }
        guideCache = Triple(key, refs, 0)
        return refs
    }

    fun row(view: AdminView, s: CatalogStream, refs: Map<String, GuideRef?>, withPrograms: Boolean, now: Long): ChannelRow {
        val uc = view.userChannelByStream[s.id]
        val cp = uc?.let { view.channelProfiles[it] }
        val customName = cp?.str("custom_name")
        val customNumber = cp?.long("custom_number")?.toInt()
        val ref = refs[s.id]
        val mapping = uc?.let { view.mappings[it] }
        val epgName = { id: String -> view.epgSources.firstOrNull { it.id == id }?.name ?: id }
        val guide = ref?.let {
            GuideStatusView(
                it.epgSourceId, epgName(it.epgSourceId), it.guideChannelId, null,
                how = if (mapping?.guideChannelId != null && mapping.epgSourceId == it.epgSourceId && mapping.guideChannelId == it.guideChannelId) "manual" else "auto",
                override = mapping != null,
                fallback = it.isFallback,
            )
        }
        val programs = if (withPrograms && ref != null) companion.guideReader.nowAndNext(ref.epgSourceId, ref.guideChannelId, ref.timeShiftMs, now) else null to null
        return ChannelRow(
            streamId = s.id,
            sourceId = s.sourceId,
            sourceName = view.sources.firstOrNull { it.first.id == s.sourceId }?.first?.name ?: s.sourceId,
            group = s.groupName,
            rawName = s.rawName,
            name = customName ?: view.nameCleanup.apply(s.displayName),
            number = customNumber ?: s.providerNumber ?: (s.position + 1),
            logo = cp?.str("custom_logo") ?: s.logoUrl,
            tvgId = s.tvgId,
            userChannelId = uc,
            favorite = uc != null && uc in view.favorites,
            playlists = view.playlistsOf(uc),
            hidden = cp?.bool("hidden") == true,
            groupHidden = view.groupOverrides[view.groupKey(s.sourceId, s.groupName)]?.bool("hidden") == true,
            customName = customName,
            customLogo = cp?.str("custom_logo"),
            customNumber = customNumber,
            guide = guide,
            now = programs.first,
            next = programs.second,
        )
    }

    /** A user channel with no stream left: shown in lists so it can be removed (PLAN §2.1). */
    fun unavailableRow(view: AdminView, uc: String): ChannelRow {
        val cp = view.channelProfiles[uc]
        return ChannelRow(
            streamId = view.streamsByUserChannel[uc]?.firstOrNull()?.streamId ?: uc, sourceId = "", sourceName = "", group = "", rawName = "",
            name = cp?.str("custom_name") ?: "Unavailable channel", number = cp?.long("custom_number")?.toInt() ?: 0, logo = cp?.str("custom_logo"),
            tvgId = null, userChannelId = uc, favorite = uc in view.favorites, playlists = view.playlistsOf(uc), hidden = cp?.bool("hidden") == true,
            groupHidden = false, customName = cp?.str("custom_name"), customLogo = cp?.str("custom_logo"), customNumber = cp?.long("custom_number")?.toInt(),
            guide = null, unavailable = true,
        )
    }

    /** Streams in the order the app shows them: source order, then provider position. */
    fun orderedStreams(view: AdminView): List<CatalogStream> =
        companion.catalog.streams.filter { it.sourceId in view.sourceOrder }.sortedWith(compareBy({ view.sourceOrder[it.sourceId] }, { it.position }))

    fun cleanupPreview(profileId: String, rules: NameCleanup, samples: Int = 80): CleanupPreview {
        val streams = orderedStreams(view(profileId))
        fun changes(names: List<String>) = names.mapNotNull { n -> rules.apply(n).takeIf { it != n }?.let { CleanupSample(n, it) } }
        fun spread(l: List<CleanupSample>) = if (l.size <= samples) l else List(samples) { l[it * l.size / samples] }
        val channels = streams.map { it.displayName }
        val channelChanges = changes(channels)
        val groups = streams.map { it.groupName }.distinct()
        val groupChanges = if (rules.groups) changes(groups) else emptyList()
        return CleanupPreview(
            presets = NameCleanup.presets(NameCleanup.Target.CHANNELS).map { CleanupPresetView(it.name, it.label, it.example) },
            channels = channels.size,
            channelsChanged = channelChanges.size,
            groups = groups.size,
            groupsChanged = groupChanges.size,
            channelSamples = spread(channelChanges.distinctBy { it.before }),
            groupSamples = spread(groupChanges),
            invalidRules = rules.invalidRules().map { it.text },
        )
    }

    fun page(profileId: String, filter: ChannelFilter, offset: Int, limit: Int, now: Long, sort: ChannelColumn? = null, descending: Boolean = false): ChannelPage {
        val view = view(profileId)
        val refs = guideRefs(view)
        val words = filter.query?.let { SearchText.words(it) }.orEmpty()
        val playlistMembers = filter.playlistId?.let { id -> view.playlistItems[id].orEmpty().map { it.first }.toSet() }
        val matching = orderedStreams(view).asSequence().filter { s ->
            val uc = view.userChannelByStream[s.id]
            val cp = uc?.let { view.channelProfiles[it] }
            (filter.sourceId == null || s.sourceId == filter.sourceId) &&
                (filter.group == null || s.groupName == filter.group) &&
                (!filter.favorites || (uc != null && uc in view.favorites)) &&
                (playlistMembers == null || (uc != null && uc in playlistMembers)) &&
                when (filter.hidden) {
                    "hidden" -> cp?.bool("hidden") == true || view.groupOverrides[view.groupKey(s.sourceId, s.groupName)]?.bool("hidden") == true
                    "visible" -> cp?.bool("hidden") != true && view.groupOverrides[view.groupKey(s.sourceId, s.groupName)]?.bool("hidden") != true
                    else -> true
                } &&
                (!filter.customized || cp?.str("custom_name") != null || cp?.str("custom_logo") != null || cp?.long("custom_number") != null) &&
                when (filter.guide) {
                    "mapped" -> refs[s.id] != null
                    "unmapped" -> refs[s.id] == null
                    else -> true
                } &&
                (words.isEmpty() || SearchText.fold("${cp?.str("custom_name").orEmpty()} ${s.displayName} ${s.rawName} ${s.tvgId.orEmpty()}").let { text ->
                    val tokens = text.split(' ')
                    words.all { w -> tokens.any { it.startsWith(w) } }
                })
        }.toList().let { if (sort == null) it else sorted(view, it, refs, sort, descending) }
        val rows = matching.drop(offset).take(limit).map { row(view, it, refs, withPrograms = true, now = now) }
        return ChannelPage(matching.size, offset, rows)
    }

    private fun sorted(view: AdminView, streams: List<CatalogStream>, refs: Map<String, GuideRef?>, sort: ChannelColumn, descending: Boolean): List<CatalogStream> {
        fun profile(s: CatalogStream) = view.userChannelByStream[s.id]?.let { view.channelProfiles[it] }
        val epgNames = view.epgSources.associate { it.id to it.name }
        val comparator: Comparator<CatalogStream> = when (sort) {
            ChannelColumn.NUMBER -> compareBy { profile(it)?.long("custom_number")?.toInt() ?: it.providerNumber ?: (it.position + 1) }
            ChannelColumn.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { profile(it)?.str("custom_name") ?: view.nameCleanup.apply(it.displayName) }
            ChannelColumn.SOURCE -> compareBy<CatalogStream> { view.sourceOrder[it.sourceId] }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.groupName }
            // Mapped by hand, then automatic, then none; within each, by EPG source.
            ChannelColumn.GUIDE -> compareBy<CatalogStream> { s ->
                val ref = refs[s.id]
                val mapping = view.userChannelByStream[s.id]?.let { view.mappings[it] }
                when {
                    ref == null -> 2
                    mapping?.guideChannelId != null && mapping.epgSourceId == ref.epgSourceId && mapping.guideChannelId == ref.guideChannelId -> 0
                    else -> 1
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { refs[it.id]?.let { r -> epgNames[r.epgSourceId] ?: r.epgSourceId }.orEmpty() }
            // Favorites, then channels in the most playlists, then the rest.
            ChannelColumn.LISTS -> compareBy<CatalogStream> { s ->
                val uc = view.userChannelByStream[s.id]
                if (uc != null && uc in view.favorites) 0 else 1
            }.thenBy { -view.playlistsOf(view.userChannelByStream[it.id]).size }
        }
        return streams.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    /** Favorites in the order the TV shows them: the manual order, or A–Z or by number when the profile sorts them. */
    fun favoriteRows(view: AdminView, now: Long): List<ChannelRow> {
        val rows = rowsFor(view, view.favorites.entries.sortedBy { it.value }.map { it.key }, now)
        return when (view.favoritesSort) {
            null -> rows
            ChannelSort.NUMBER -> rows.sortedBy { it.number }
            else -> rows.sortedBy { it.name.lowercase() }
        }
    }

    /** One channel by stream ID, with programs. */
    fun channel(profileId: String, streamId: String, now: Long): ChannelRow? {
        val view = view(profileId)
        val s = companion.catalog.streams.firstOrNull { it.id == streamId } ?: return null
        return row(view, s, guideRefs(view), withPrograms = true, now = now).let { r ->
            r.copy(guide = r.guide?.copy(guideChannelName = companion.guideReader.displayName(r.guide.epgSourceId, r.guide.guideChannelId)))
        }
    }

    /** Rows for user channels in order (Favorites, a playlist), falling back to "unavailable". */
    fun rowsFor(view: AdminView, userChannels: List<String>, now: Long): List<ChannelRow> {
        val byId = companion.catalog.streams.associateBy { it.id }
        val refs = guideRefs(view)
        return userChannels.map { uc ->
            val stream = view.streamsByUserChannel[uc].orEmpty().firstNotNullOfOrNull { byId[it.streamId]?.takeIf { s -> s.sourceId in view.sourceOrder && view.sources.firstOrNull { x -> x.first.id == s.sourceId }?.first?.enabled != false } }
            if (stream == null) unavailableRow(view, uc) else row(view, stream, refs, withPrograms = false, now = now)
        }
    }

    /** The guide mapping queue (PLAN §4.3.18 → Guide mapping): visible channels with no guide, favorites first. */
    fun unmapped(view: AdminView): List<CatalogStream> {
        val refs = guideRefs(view)
        val inLists = view.favorites.entries.sortedBy { it.value }.map { it.key } + view.playlistItems.values.flatten().map { it.first }
        val listRank = inLists.withIndex().associate { (i, uc) -> uc to i }.let { m -> inLists.distinct().associateWith { m.getValue(it) } }
        return orderedStreams(view).filter { s ->
            val uc = view.userChannelByStream[s.id]
            refs[s.id] == null && uc?.let { view.channelProfiles[it] }?.bool("hidden") != true &&
                view.groupOverrides[view.groupKey(s.sourceId, s.groupName)]?.bool("hidden") != true
        }.withIndex().sortedWith(compareBy({ view.userChannelByStream[it.value.id]?.let { uc -> listRank[uc] } ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** Coverage for the Dashboard: visible channels with guide data, of all visible channels. */
    fun coverage(profileId: String): Pair<Int, Int> {
        val view = view(profileId)
        val refs = guideRefs(view)
        val visible = orderedStreams(view).filter { s ->
            val uc = view.userChannelByStream[s.id]
            uc?.let { view.channelProfiles[it] }?.bool("hidden") != true && view.groupOverrides[view.groupKey(s.sourceId, s.groupName)]?.bool("hidden") != true
        }
        return visible.count { refs[it.id] != null } to visible.size
    }
}

/**
 * The Movies & Series cleanup preview (PLAN §4.3.6 → Categories): how many shown titles and category
 * names [rules] change, the same shape as the channel preview.
 */
suspend fun vodCleanupPreview(companion: Companion, rules: NameCleanup, samples: Int = 80): CleanupPreview {
    val names = companion.catalog.vodItems().map { it.name }
    fun spread(l: List<CleanupSample>) = if (l.size <= samples) l else List(samples) { l[it * l.size / samples] }
    val titleChanges = names.mapNotNull { n ->
        val before = com.signal.data.VodRepository.displayNameOf(n)
        com.signal.data.VodRepository.displayNameOf(n, rules).takeIf { it != before }?.let { CleanupSample(before, it) }
    }
    val categories = companion.catalog.vodCategoryNames()
    val categoryChanges = if (rules.groups) categories.mapNotNull { n -> rules.apply(n).takeIf { it != n }?.let { CleanupSample(n, it) } } else emptyList()
    return CleanupPreview(
        presets = NameCleanup.presets(NameCleanup.Target.VOD).map { CleanupPresetView(it.name, it.label, it.example) },
        channels = names.size,
        channelsChanged = titleChanges.size,
        groups = categories.size,
        groupsChanged = categoryChanges.size,
        channelSamples = spread(titleChanges.distinctBy { it.before }),
        groupSamples = spread(categoryChanges),
        invalidRules = rules.invalidRules().map { it.text },
    )
}
