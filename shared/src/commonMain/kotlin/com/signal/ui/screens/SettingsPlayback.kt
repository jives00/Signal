package com.signal.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.data.Settings
import com.signal.player.DecoderMode
import com.signal.player.FrameRateMode
import com.signal.player.LiveBuffer
import com.signal.player.PlayerSettings
import com.signal.player.SubtitleBackground
import com.signal.player.SubtitleColor
import com.signal.player.SubtitleMode
import com.signal.player.SubtitleSize
import com.signal.player.VodBuffer
import com.signal.ui.components.ListRow
import com.signal.ui.components.SectionTitle
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage

/** Languages offered for audio and subtitles: ISO 639-1 code and name. Streams name theirs the same way. */
internal val PLAYBACK_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
    "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "ar" to "Arabic", "hi" to "Hindi", "zh" to "Chinese",
    "ja" to "Japanese", "ko" to "Korean", "tr" to "Turkish", "el" to "Greek", "sv" to "Swedish",
)

private fun languageName(code: String?) = PLAYBACK_LANGUAGES.firstOrNull { it.first == code }?.second ?: code ?: "None"

/**
 * Settings → Playback (PLAN §4.3.15 → 5). Languages are the profile's; subtitle style and the
 * technical settings are this device's, since they depend on the TV, the receiver and the network.
 * The player follows each change at once, except the decoder, passthrough and buffers, which apply
 * from the next channel or video.
 */
@Composable
internal fun PlaybackSection(app: AppController, returnTo: FocusRequester) {
    val s by app.playerSettings.collectAsState()
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Languages") }
        val audio = s.audioLanguages
        item {
            ListRow("Audio language", detail = audio.getOrNull(0)?.let(::languageName) ?: "The stream's own", onSelect = {
                app.panel.open(languagePage(app, "Audio language", audio.getOrNull(0), "The stream's own") { code ->
                    setAudio(app, listOfNotNull(code) + audio.drop(1).filter { it != code })
                }, returnTo)
            })
        }
        if (audio.isNotEmpty()) item {
            ListRow("Second audio language", detail = audio.getOrNull(1)?.let(::languageName) ?: "None", onSelect = {
                app.panel.open(languagePage(app, "Second audio language", audio.getOrNull(1), "None", exclude = audio.first()) { code ->
                    setAudio(app, listOfNotNull(audio.first(), code))
                }, returnTo)
            })
        }
        choice(app, returnTo, "Subtitles", Settings.Key.SUBTITLES, s.subtitles, SubtitleMode.FORCED, SubtitleMode.entries, { it.label }, { it.detail },
            subtitle = "A channel's own choice, made in its info bar, wins over this.")
        item {
            ListRow("Subtitle language", detail = s.subtitleLanguage?.let(::languageName) ?: "The TV's language", onSelect = {
                app.panel.open(languagePage(app, "Subtitle language", s.subtitleLanguage, "The TV's language") { code ->
                    app.settings.put(Settings.Key.SUBTITLE_LANGUAGE, code)
                }, returnTo)
            })
        }

        item { SectionTitle("Subtitle style · this TV") }
        choice(app, returnTo, "Size", Settings.Key.SUBTITLE_SIZE, s.subtitleSize, SubtitleSize.NORMAL, SubtitleSize.entries, { it.label })
        choice(app, returnTo, "Text color", Settings.Key.SUBTITLE_COLOR, s.subtitleColor, SubtitleColor.WHITE, SubtitleColor.entries, { it.label })
        choice(app, returnTo, "Background", Settings.Key.SUBTITLE_BACKGROUND, s.subtitleBackground, SubtitleBackground.OUTLINE, SubtitleBackground.entries, { it.label })

        item { SectionTitle("Technical · this TV") }
        choice(app, returnTo, "Auto frame rate", Settings.Key.FRAME_RATE_MATCH, s.frameRate, FrameRateMode.ON, FrameRateMode.entries, { it.label }, { it.detail },
            subtitle = "In full screen and in movies and episodes. The TV may go dark for a moment while it switches.")
        choice(app, returnTo, "Video decoder", Settings.Key.VIDEO_DECODER, s.decoder, DecoderMode.HARDWARE_FALLBACK, DecoderMode.entries, { it.label }, { it.detail },
            subtitle = "From the next channel or video.")
        item {
            ListRow("Audio passthrough", detail = if (s.passthrough) "Auto" else "Off", onSelect = {
                app.panel.open(PanelPage.Actions("Audio passthrough", listOf(
                    PanelAction("Auto", detail = "AC3, E-AC3 and DTS go to the receiver when it takes them", selected = s.passthrough) { app.settings.put(Settings.Key.AUDIO_PASSTHROUGH, null); null },
                    PanelAction("Off", detail = "Always decoded on this TV, as stereo or PCM surround", selected = !s.passthrough) { app.settings.put(Settings.Key.AUDIO_PASSTHROUGH, "false"); null },
                ), subtitle = "From the next channel or video."), returnTo)
            })
        }
        item {
            ListRow("Tunneled playback", detail = if (s.tunneling) "On" else "Off", onSelect = {
                app.panel.open(PanelPage.Actions("Tunneled playback", listOf(
                    PanelAction("Off", selected = !s.tunneling) { app.settings.put(Settings.Key.TUNNELING, null); null },
                    PanelAction("On", detail = "Smoother audio and video sync on some TVs; breaks some streams", selected = s.tunneling) { app.settings.put(Settings.Key.TUNNELING, "true"); null },
                ), subtitle = "Only where the device supports it."), returnTo)
            })
        }
        choice(app, returnTo, "Live buffer", Settings.Key.LIVE_BUFFER, s.liveBuffer, LiveBuffer.BALANCED, LiveBuffer.entries, { it.label }, { it.detail },
            subtitle = "From the next channel.")
        choice(app, returnTo, "Movie and episode buffer", Settings.Key.VOD_BUFFER, s.vodBuffer, VodBuffer.NORMAL, VodBuffer.entries, { it.label }, { it.detail },
            subtitle = "From the next video.")
        item {
            ListRow("Stream timeout", detail = "${s.timeoutSeconds} seconds", onSelect = {
                app.panel.open(PanelPage.Actions("Stream timeout", PlayerSettings.TIMEOUTS.map { t ->
                    PanelAction("$t seconds", selected = t == s.timeoutSeconds) { app.settings.put(Settings.Key.STREAM_TIMEOUT, t.takeIf { it != PlayerSettings().timeoutSeconds }?.toString()); null }
                }, subtitle = "How long a stream may take to answer before it counts as failed."), returnTo)
            })
        }
        item {
            ListRow("Retries", detail = retriesLabel(s.retries), onSelect = {
                app.panel.open(PanelPage.Actions("Retries", PlayerSettings.RETRIES.map { n ->
                    PanelAction(retriesLabel(n), selected = n == s.retries) { app.settings.put(Settings.Key.STREAM_RETRIES, n.takeIf { it != PlayerSettings().retries }?.toString()); null }
                }, subtitle = "Tries again on its own before showing the error, or another source's stream for the channel."), returnTo)
            })
        }
    }
}

private fun retriesLabel(n: Int) = when (n) { 0 -> "None"; 1 -> "1 retry"; else -> "$n retries" }

private fun setAudio(app: AppController, codes: List<String>) =
    app.settings.put(Settings.Key.AUDIO_LANGUAGES, codes.distinct().joinToString(",").ifEmpty { null })

private fun languagePage(app: AppController, title: String, current: String?, noneLabel: String, exclude: String? = null, pick: (String?) -> Unit): PanelPage =
    PanelPage.Actions(title, listOf(PanelAction(noneLabel, selected = current == null) { pick(null); null }) +
        PLAYBACK_LANGUAGES.filter { it.first != exclude }.map { (code, name) -> PanelAction(name, selected = code == current) { pick(code); null } })

/** One setting with a fixed list of choices; the default is stored as unset, so a later default change reaches it. */
private fun <E : Enum<E>> LazyListScope.choice(
    app: AppController, returnTo: FocusRequester, title: String, key: Settings.Key, current: E, default: E, options: List<E>,
    label: (E) -> String, detail: ((E) -> String)? = null, subtitle: String? = null,
) {
    item(key = key.id) {
        ListRow(title, detail = label(current), onSelect = {
            app.panel.open(PanelPage.Actions(title, options.map { o ->
                PanelAction(label(o), detail = detail?.invoke(o), selected = o == current) { app.settings.put(key, o.name.takeIf { o != default }); null }
            }, subtitle = subtitle), returnTo)
        })
    }
}
