package com.signal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.model.Channel
import com.signal.model.Program
import com.signal.sports.Sport
import com.signal.sports.Team
import com.signal.sports.Teams
import com.signal.ui.LocalApp
import com.signal.ui.theme.SignalTheme

/**
 * A league's image (live-sports.md → Round 2): the household's choice or ESPN's logo, and a badge with
 * the league's short name when there's none or it doesn't load.
 */
@Composable
fun SportBadge(sport: Sport, size: Int, modifier: Modifier = Modifier, onAccent: Boolean = false) {
    val c = SignalTheme.colors
    val url = LocalApp.current.sports.image(sport)
    Box(modifier.size(size.dp).background(if (onAccent) Color.White.copy(alpha = 0.16f) else c.neutral400), contentAlignment = Alignment.Center) {
        ImageOrPlaceholder(url, null, ContentScale.Fit, Modifier.fillMaxSize().padding((size / 10).dp)) {
            val text = sport.short
            SText(text, (size * when { text.length > 4 -> 0.2f; text.length > 3 -> 0.26f; else -> 0.34f }).toInt().coerceAtLeast(14), weight = FontWeight.ExtraBold, color = if (onAccent) c.onAccent else c.secondaryText)
        }
    }
}

/** A team's logo from ESPN, or nothing: one that fails to load takes no room. */
@Composable
fun TeamLogo(team: Team, size: Int, modifier: Modifier = Modifier) {
    val logo = team.logo ?: return
    var failed by remember(logo) { mutableStateOf(false) }
    if (failed) return
    coil3.compose.AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size.dp), onError = { failed = true })
}

/** A team's name with its ranking ("#11 Louisville"). */
fun Team.label(): String = rank?.let { "#$it $name" } ?: name

/** "[logo] Georgia Bulldogs vs. [logo] Ole Miss Rebels" on one line. */
@Composable
fun MatchupLine(teams: Teams, textSize: Int, logoSize: Int, color: Color, weight: FontWeight = FontWeight.SemiBold) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TeamLogo(teams.firstTeam, logoSize)
        SText(teams.firstTeam.label(), textSize, weight = weight, color = color)
        SText("vs.", textSize, weight = weight, color = color)
        TeamLogo(teams.secondTeam, logoSize)
        SText(teams.secondTeam.label(), textSize, weight = weight, color = color)
    }
}

/**
 * The two teams' logos of the game on [channel] now, when ESPN knows it (live-sports.md → Round 2):
 * for the info bar and zap banner. Nothing for anything else.
 */
@Composable
fun GameLogos(channel: Channel, program: Program?, size: Int, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val teams by produceState<Teams?>(null, channel.stream.id, program?.id) {
        value = runCatching { app.sports.teamsOn(channel.stream.id, program, kotlin.time.Clock.System.now().toEpochMilliseconds()) }.getOrNull()
    }
    val t = teams ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(t.firstTeam, size)
        TeamLogo(t.secondTeam, size)
    }
}
