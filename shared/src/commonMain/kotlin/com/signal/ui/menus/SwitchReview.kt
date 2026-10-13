package com.signal.ui.menus

import androidx.compose.ui.focus.FocusRequester
import com.signal.app.AppController
import com.signal.match.ChannelMatcher
import com.signal.match.ProviderSwitch
import com.signal.model.Source
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import kotlinx.coroutines.launch

/**
 * The TV's simpler provider switch review (PLAN §4.5, step 3), for when the NAS is down: link the
 * confident matches in one go, confirm or reject suggestions one at a time, and see what would
 * become unavailable. The web admin's Provider switch page is the full version.
 */
object SwitchReview {
    /** Builds the report, then opens it in the side panel. */
    fun open(app: AppController, target: Source, returnTo: FocusRequester?) {
        app.scope.launch {
            val report = app.switches.report(target.id)
            app.panel.open(page(app, target, report, returnTo), returnTo)
        }
    }

    private fun page(app: AppController, target: Source, r: ProviderSwitch.Report, returnTo: FocusRequester?): PanelPage.Actions {
        val reopen = { open(app, target, returnTo) }
        val actions = buildList {
            if (r.confident.isNotEmpty()) {
                add(PanelAction("Link ${r.confident.size} confident matches", detail = r.confident.take(3).joinToString { it.userChannel.name } + if (r.confident.size > 3) "…" else "") {
                    app.scope.launch { app.switches.acceptAll(r.confident); reopen() }
                    null
                })
            }
            for (p in r.suggested.take(MAX_SHOWN)) {
                add(PanelAction(p.userChannel.name, detail = "Maybe ${p.best.stream.rawName} · ${p.best.reasons.joinToString()}") { suggestion(app, target, p, returnTo) })
            }
            if (r.suggested.size > MAX_SHOWN) add(PanelAction("${r.suggested.size - MAX_SHOWN} more to review in the web admin") { null })
            if (r.unmatched.isNotEmpty()) {
                add(PanelAction("${r.unmatched.size} with no match", detail = "Unavailable once the old source is off: " + r.unmatched.take(4).joinToString { it.name }) { null })
            }
            if (r.confident.isEmpty() && r.suggested.isEmpty() && r.unmatched.isEmpty()) {
                add(PanelAction("Nothing to review", detail = "${r.alreadyLinked} channels already have a stream on ${target.name}") { null })
            }
        }
        return PanelPage.Actions("Match channels to ${target.name}", actions)
    }

    private fun suggestion(app: AppController, target: Source, p: ProviderSwitch.Proposal, returnTo: FocusRequester?): PanelPage.Actions {
        val from = p.userChannel.streams.firstOrNull { it.sourceId != target.id }
        return PanelPage.Actions(p.userChannel.name, p.matches.map { m ->
            PanelAction("Link ${m.stream.rawName}", detail = m.reasons.joinToString()) {
                app.scope.launch { app.switches.accept(p.userChannel.id, from, m.copy(confidence = ChannelMatcher.Confidence.SUGGESTED)); open(app, target, returnTo) }
                null
            }
        } + PanelAction("Not this channel", detail = "Don't suggest these again") {
            app.scope.launch { p.matches.forEach { app.switches.reject(p.userChannel.id, it.stream.id) }; open(app, target, returnTo) }
            null
        })
    }

    private const val MAX_SHOWN = 30
}
