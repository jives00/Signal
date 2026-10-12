package com.signal.ui.menus

import com.signal.app.AppController
import com.signal.data.GroupEntry
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.ChannelSort
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import kotlinx.coroutines.launch

/**
 * The item menus opened by long-press OK (PLAN §4.2 → What long-press OK opens). Only actions that
 * apply are shown. Features from later milestones (guide mapping, stream choice, multi-view, logo
 * picker) join these menus when they land.
 */
class ItemMenus(private val app: AppController) {
    private fun launch(block: suspend () -> Unit) {
        app.scope.launch { block() }
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
            if (onMove != null) add(PanelAction("Move") { onMove(); null })
            add(PanelAction("Rename") {
                PanelPage.TextInput("Rename ${channel.displayName}", channel.displayName) { name -> launch { app.channels.rename(channel, name) } }
            })
            add(PanelAction("Set channel number", detail = channel.number.toString()) {
                PanelPage.TextInput("Channel number", channel.number.toString(), digitsOnly = true) { n ->
                    launch { app.channels.setNumber(channel, n.toIntOrNull()) }
                }
            })
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

    private fun channelTitle(c: Channel) = "${c.number}  ${c.displayName}"

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

    fun favoritesMenu(onReorder: () -> Unit): PanelPage.Actions =
        PanelPage.Actions("Favorites", listOf(
            PanelAction("Reorder channels", detail = "Also sets the Home row order") { onReorder(); null },
        ))
}
