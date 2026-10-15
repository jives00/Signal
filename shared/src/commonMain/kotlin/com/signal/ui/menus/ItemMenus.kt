package com.signal.ui.menus

import com.signal.app.AppController
import com.signal.data.GroupEntry
import com.signal.model.Channel
import com.signal.data.Settings
import com.signal.model.ChannelGroup
import com.signal.model.ChannelSort
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import kotlinx.coroutines.launch

/**
 * The item menus opened by long-press OK (PLAN §4.2 → What long-press OK opens). Only actions that
 * apply are shown. Features from later milestones (multi-view, the logo
 * picker) join these menus when they land.
 */
class ItemMenus(private val app: AppController) {
    private fun launch(block: suspend () -> Unit) {
        app.scope.launch { block() }
    }

    /** The streams [channel] can play, when it has more than one (PLAN §4.5), or null. */
    fun linkedStreams(channel: Channel): List<com.signal.data.ChannelRepository.LinkedStream>? =
        channel.userChannelId?.let { app.linkedStreams.value[it] }?.takeIf { it.size > 1 }

    private fun streamAction(channel: Channel): PanelAction? {
        val links = linkedStreams(channel) ?: return null
        val playing = links.firstOrNull { it.streamId == channel.stream.id } ?: links.firstOrNull { it.preferred }
        return PanelAction("Stream", detail = playing?.let { "${it.sourceName} · ${it.name}" }) { streamPage(channel) }
    }

    /**
     * Stream (PLAN §4.2 → channel menu, §4.3.3 → Options row): which linked stream plays. The choice
     * is remembered per channel for every device, and a playing channel switches at once.
     */
    fun streamPage(channel: Channel): PanelPage.Actions {
        val links = linkedStreams(channel).orEmpty()
        val uc = channel.userChannelId
        return PanelPage.Actions("Stream", links.map { l ->
            PanelAction("${l.sourceName} · ${l.name}", detail = l.group, selected = l.preferred) {
                launch {
                    if (uc == null) return@launch
                    app.channels.choosePreferred(uc, l.streamId)
                    val now = app.playback.current.value
                    if (now?.userChannelId == uc) app.channels.channelByStream(l.streamId)?.let { app.playback.switchStream(it) }
                }
                null
            }
        })
    }

    /**
     * @param currentGroup where the channel is shown, which decides "Remove from this playlist" and Move.
     * @param onMove enters move mode in Favorites or a playlist; null elsewhere.
     */
    fun channelMenu(channel: Channel, currentGroup: ChannelGroup?, onMove: (() -> Unit)?): PanelPage.Actions {
        val actions = buildList {
            add(
                if (channel.isFavorite) PanelAction("Remove from Favorites") { launch { app.channels.setFavorite(channel, false) }; null }
                else PanelAction("Add to Favorites") { launch { app.channels.setFavorite(channel, true) }; null },
            )
            add(PanelAction("Add to playlist…") { addToPlaylistPage { listOf(channel) } })
            if (currentGroup is ChannelGroup.Playlist && channel.userChannelId != null) {
                add(PanelAction("Remove from this playlist") { launch { app.channels.removeFromPlaylist(channel.userChannelId, currentGroup.id) }; null })
            }
            // Sorted Favorites have no manual order to move within.
            val sortedFavorites = currentGroup == ChannelGroup.Favorites && app.settings.favoritesSort() != null
            if (onMove != null && !sortedFavorites) add(PanelAction("Move") { onMove(); null })
            add(PanelAction("Rename") {
                PanelPage.TextInput("Rename ${channel.displayName}", channel.displayName) { name -> launch { app.channels.rename(channel, name) } }
            })
            add(PanelAction("Voice names") {
                PanelPage.TextInput(
                    "Voice names for ${channel.displayName}", "",
                    hint = "Other names to say it by in a voice command, separated by commas. For example: fox, fox chicago",
                    load = { app.channels.voiceNames(channel).joinToString(", ") },
                ) { names -> launch { app.channels.setVoiceNames(channel, names.split(',')) } }
            })
            add(PanelAction("Set channel number", detail = channel.number.toString()) {
                PanelPage.TextInput("Channel number", channel.number.toString(), digitsOnly = true) { n ->
                    launch { app.channels.setNumber(channel, n.toIntOrNull()) }
                }
            })
            guideSourceAction(channel)?.let(::add)
            streamAction(channel)?.let(::add)
            add(PanelAction("Hide channel") {
                launch { app.channels.setHidden(channel, true) }
                null
            })
            if (channel.userChannelId != null) {
                add(PanelAction("Reset to provider defaults") {
                    PanelPage.Confirm(
                        "Reset ${channel.displayName}?",
                        "This clears the rename and channel number. Favorites and playlists aren't touched.",
                        "Reset",
                    ) { launch { app.channels.resetToProviderDefaults(channel) } }
                })
            }
        }
        return PanelPage.Actions(channelTitle(channel), actions)
    }

    /**
     * The channel's EPG source (PLAN §6 → Per-channel override): which source its guide comes from now,
     * marked when that's a fallback, and a picker to override it or go back to the default.
     */
    private fun guideSourceAction(channel: Channel): PanelAction? {
        val state = app.guide.state.value
        val sources = state.sources.filter { it.enabled }
        if (sources.isEmpty()) return null
        val ref = app.guide.resolve(channel.stream.id, channel.userChannelId, channel.stream.tvgId, channel.stream.rawName)
        val inUse = ref?.let { r -> sources.firstOrNull { it.id == r.epgSourceId } }
        val overridden = channel.userChannelId?.let { state.mappings[it] } != null
        val detail = when {
            inUse == null -> "No guide data"
            ref.isFallback -> "${inUse.name} (fallback)"
            else -> inUse.name
        } + if (overridden) " · override" else ""
        return PanelAction("Guide source", detail = detail) {
            val default = sources.firstOrNull { it.id == state.defaultId }
            PanelPage.Actions("Guide source", buildList {
                add(PanelAction("Default${default?.let { " (${it.name})" }.orEmpty()}", selected = !overridden) {
                    launch { app.channels.userChannelIdFor(channel)?.let { app.epg.removeMapping(it) } }
                    null
                })
                sources.forEach { s ->
                    val chosen = channel.userChannelId?.let { state.mappings[it]?.epgSourceId } == s.id
                    add(PanelAction(s.name, selected = chosen) {
                        launch { app.epg.setMapping(app.channels.ensureUserChannel(channel), s.id, null) }
                        null
                    })
                }
            })
        }
    }

    private fun channelTitle(c: Channel) = app.channelLabel(c)

    /** "Add to playlist…": the existing playlists plus New playlist (PLAN §4.2). */
    fun addToPlaylistPage(channels: suspend () -> List<Channel>): PanelPage {
        val playlists = app.groups.value.mapNotNull { it.group as? ChannelGroup.Playlist }
        val actions = playlists.map { p ->
            PanelAction(p.title) { launch { app.channels.addAllToPlaylist(channels(), p.id) }; null }
        } + PanelAction("New playlist") {
            PanelPage.TextInput("New playlist", "", confirmLabel = "Create") { name ->
                if (name.isNotBlank()) launch {
                    val id = app.channels.createPlaylist(name)
                    app.channels.addAllToPlaylist(channels(), id)
                }
            }
        }
        return PanelPage.Actions("Add to playlist", actions)
    }

    fun groupMenu(entry: GroupEntry, onMoveGroup: () -> Unit): PanelPage.Actions {
        val group = entry.group as ChannelGroup.Provider
        val isRenamed = group.title != group.name
        val actions = buildList {
            add(PanelAction("Hide group") {
                PanelPage.Confirm("Hide ${group.title}?", "Its ${entry.count} channels leave Live TV. Hidden groups can be restored in Settings.", "Hide group") {
                    launch { app.channels.setGroupHidden(group, true) }
                }
            })
            add(PanelAction("Rename group") {
                PanelPage.TextInput("Rename group", group.title) { name -> launch { app.channels.renameGroup(group, name) } }
            })
            if (isRenamed) add(PanelAction("Reset name") { launch { app.channels.renameGroup(group, null) }; null })
            add(PanelAction("Sort channels in this group", detail = sortLabel(entry.channelSort)) { groupSortPage(group, entry.channelSort) })
            add(PanelAction("Move group") { onMoveGroup(); null })
            add(PanelAction("Add all channels to playlist…") { addToPlaylistPage { app.channels.channelsNow(group) } })
        }
        return PanelPage.Actions("Group: ${group.title}", actions)
    }

    private fun sortLabel(sort: ChannelSort?) = when (sort) {
        null -> "Default"
        ChannelSort.PROVIDER -> "Provider order"
        ChannelSort.NUMBER -> "Number"
        ChannelSort.A_Z -> "A–Z"
    }

    private fun groupSortPage(group: ChannelGroup.Provider, current: ChannelSort?): PanelPage {
        val options = listOf(null, ChannelSort.PROVIDER, ChannelSort.NUMBER, ChannelSort.A_Z)
        return PanelPage.Actions("Sort ${group.title}", options.map { sort ->
            PanelAction(sortLabel(sort), selected = sort == current) { launch { app.channels.setGroupChannelSort(group, sort) }; null }
        }, subtitle = "Overrides the default sort for this group only.")
    }

    fun playlistMenu(playlist: ChannelGroup.Playlist, onReorder: () -> Unit, onMovePlaylist: () -> Unit): PanelPage.Actions =
        PanelPage.Actions("Playlist: ${playlist.title}", listOf(
            PanelAction("Rename") { PanelPage.TextInput("Rename playlist", playlist.title) { n -> launch { app.channels.renamePlaylist(playlist.id, n) } } },
            PanelAction("Reorder channels") { onReorder(); null },
            PanelAction("Move playlist") { onMovePlaylist(); null },
            PanelAction("New playlist") {
                PanelPage.TextInput("New playlist", "", confirmLabel = "Create") { n -> if (n.isNotBlank()) launch { app.channels.createPlaylist(n) } }
            },
            PanelAction("Delete playlist") {
                PanelPage.Confirm("Delete ${playlist.title}?", "The playlist is deleted. Its channels aren't affected.", "Delete playlist") {
                    launch { app.channels.deletePlaylist(playlist.id) }
                }
            },
        ))

    fun favoritesMenu(onReorder: () -> Unit): PanelPage.Actions {
        val sort = app.settings.favoritesSort()
        return PanelPage.Actions("Favorites", buildList {
            add(PanelAction("Sort", detail = favoritesSortLabel(sort)) { favoritesSortPage(sort) })
            // Reordering is the manual order; while sorted there's nothing to drag.
            if (sort == null) add(PanelAction("Reorder channels", detail = "Also sets the Home row order") { onReorder(); null })
        })
    }

    private fun favoritesSortLabel(sort: ChannelSort?) = when (sort) {
        null, ChannelSort.PROVIDER -> "Manual"
        ChannelSort.NUMBER -> "Channel number"
        ChannelSort.A_Z -> "A–Z"
    }

    private fun favoritesSortPage(current: ChannelSort?): PanelPage =
        PanelPage.Actions("Sort Favorites", listOf(null, ChannelSort.A_Z, ChannelSort.NUMBER).map { sort ->
            PanelAction(favoritesSortLabel(sort), selected = sort == current) { app.settings.put(Settings.Key.FAVORITES_SORT, sort?.name); null }
        }, subtitle = "Also the Home row. Manual keeps your own order, which A–Z and number sort on top of.")
}
