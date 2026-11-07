package com.signal.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The image for each league or sport (live-sports.md → Round 2): the household setting sport_images,
 * a JSON object of [Sport] name to URL, edited in the web admin. A league not in it has ESPN's logo
 * by default, linked and never copied into the repo; an empty URL means none. Without an image, or
 * when it fails to load, the app draws a badge with [Sport.short].
 */
object SportImages {
    private const val LEAGUES = "https://a.espncdn.com/i/teamlogos/leagues/500"
    private const val ICONS = "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon"
    private const val NCAA = "https://a.espncdn.com/i/espn/misc_logos/500/ncaa.png"

    val defaults: Map<Sport, String> = mapOf(
        Sport.NFL to "$LEAGUES/nfl.png",
        Sport.COLLEGE_FOOTBALL to NCAA,
        Sport.CFL to "$ICONS-football.png",
        Sport.HIGH_SCHOOL_FOOTBALL to "$ICONS-football.png",
        Sport.AUSSIE_RULES to "$LEAGUES/afl.png",
        Sport.NBA to "$LEAGUES/nba.png",
        Sport.WNBA to "$LEAGUES/wnba.png",
        Sport.G_LEAGUE to "$ICONS-basketball.png",
        Sport.MENS_COLLEGE_BASKETBALL to NCAA,
        Sport.WOMENS_COLLEGE_BASKETBALL to NCAA,
        Sport.INTL_BASKETBALL to "$ICONS-basketball.png",
        Sport.NHL to "$LEAGUES/nhl.png",
        Sport.COLLEGE_HOCKEY to NCAA,
        Sport.INTL_HOCKEY to "$ICONS-hockey.png",
        Sport.MLB to "$LEAGUES/mlb.png",
        Sport.MILB to "$ICONS-baseball.png",
        Sport.COLLEGE_BASEBALL to NCAA,
        Sport.SOCCER to "$ICONS-soccer.png",
        Sport.RUGBY to "$ICONS-rugby.png",
        Sport.CRICKET to "$ICONS-cricket.png",
        Sport.LACROSSE to "$ICONS-lacrosse.png",
        Sport.RACING to "$LEAGUES/f1.png",
        Sport.GOLF to "$ICONS-golf.png",
        Sport.TENNIS to "$ICONS-tennis.png",
        Sport.FIGHTING to "$ICONS-mma.png",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The setting's own choices: a name to a URL, or to "" for none. */
    fun parse(raw: String?): Map<String, String> {
        val obj = raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it.trim() } }.toMap()
    }

    fun encode(chosen: Map<String, String>): String? =
        if (chosen.isEmpty()) null else JsonObject(chosen.mapValues { JsonPrimitive(it.value) }).toString()

    /** The image to show for [sport], or null for the drawn badge. */
    fun url(sport: Sport, chosen: Map<String, String>): String? =
        if (sport.name in chosen) chosen.getValue(sport.name).ifBlank { null } else defaults[sport]
}
