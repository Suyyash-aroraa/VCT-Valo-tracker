package com.vcttracker.data

import java.time.Instant

enum class MatchStatus { LIVE, UPCOMING, COMPLETED }

enum class Region(val label: String, val short: String) {
    AMERICAS("Americas", "AMER"),
    EMEA("EMEA", "EMEA"),
    PACIFIC("Pacific", "PAC"),
    CHINA("China", "CN"),
    INTERNATIONAL("International", "INTL");

    companion object {
        fun fromName(name: String): Region = when {
            name.contains("Americas", true) -> AMERICAS
            name.contains("EMEA", true) -> EMEA
            name.contains("Pacific", true) -> PACIFIC
            name.contains("China", true) -> CHINA
            else -> INTERNATIONAL
        }
    }
}

/** The fixed order a VCT season is played in. */
enum class Stage(val label: String) {
    KICKOFF("Kickoff"),
    MASTERS_1("Masters"),
    STAGE_1("Stage 1"),
    MASTERS_2("Masters"),
    STAGE_2("Stage 2"),
    CHAMPIONS("Champions"),
    OTHER("Other"),
}

data class Team(
    val name: String,
    val logo: String? = null,
    val id: String? = null,
    val flag: String? = null,
)

data class MatchSide(
    val team: Team,
    val score: String?,
    val isWinner: Boolean,
)

data class MatchSummary(
    val id: String,
    val team1: MatchSide,
    val team2: MatchSide,
    val status: MatchStatus,
    val startsAt: Instant?,
    val eventName: String,
    val eventLogo: String?,
    val series: String,
    /** Model's pre-match chance that team 1 wins, when a forecast is available. */
    val forecast: Double? = null,
)

data class MatchDay(val label: String, val matches: List<MatchSummary>)

data class EventSummary(
    val id: String,
    val name: String,
    val status: MatchStatus,
    val prize: String,
    val dates: String,
    val flag: String?,
    val logo: String?,
    val region: Region,
    val stage: Stage,
)

data class Season(val year: Int, val events: List<EventSummary>)

data class EventSubPage(val label: String, val dates: String, val path: String, val active: Boolean)

data class BracketTeam(val team: Team, val score: String, val isWinner: Boolean, val isLoser: Boolean)

data class BracketMatch(
    val matchId: String?,
    val team1: BracketTeam,
    val team2: BracketTeam?,
    val startsAt: Instant?,
)

data class BracketColumn(val label: String, val matches: List<BracketMatch>)

data class Bracket(val isLower: Boolean, val columns: List<BracketColumn>)

data class BracketSection(val title: String, val brackets: List<Bracket>)

data class GroupRow(
    val team: Team,
    val country: String,
    val record: String,
    val maps: String,
    val rounds: String,
    val diff: String,
    val advanced: Boolean,
    val eliminated: Boolean,
)

data class GroupTable(val title: String, val rows: List<GroupRow>)

data class PrizeRow(
    val place: String,
    val prize: String,
    val team: Team?,
    val country: String?,
    val points: String?,
    val note: String?,
)

data class PlayerRef(val name: String, val id: String?, val flag: String?)

data class EventTeam(val team: Team, val players: List<PlayerRef>, val note: String?)

data class EventDetail(
    val id: String,
    val name: String,
    val subtitle: String,
    val dates: String,
    val prize: String,
    val location: String,
    val flag: String?,
    val logo: String?,
    val subPages: List<EventSubPage>,
    val sections: List<BracketSection>,
    val groups: List<GroupTable>,
    val prizes: List<PrizeRow>,
    val teams: List<EventTeam>,
)

enum class Side { ATTACK, DEFENSE }

enum class RoundEnd { ELIMINATION, DEFUSE, DETONATE, TIME }

data class Round(val number: Int, val winner: Int, val side: Side?, val end: RoundEnd?)

data class SideValue(val both: String, val attack: String, val defense: String)

data class PlayerLine(
    val name: String,
    val id: String?,
    val flag: String?,
    val agents: List<String>,
    val stats: Map<String, SideValue>,
)

data class Game(
    val id: String,
    val number: Int,
    val map: String,
    val pickedBy: Int,
    val duration: String,
    val score1: String,
    val score2: String,
    val team1Defense: String,
    val team1Attack: String,
    val team2Defense: String,
    val team2Attack: String,
    val rounds: List<Round>,
    val players1: List<PlayerLine>,
    val players2: List<PlayerLine>,
    val played: Boolean,
)

data class StreamLink(val label: String, val url: String, val flag: String?)

data class VetoStep(val team: String, val action: String, val map: String)

/**
 * One bookmaker's pre-match decimal odds. Before a match both sides are listed;
 * afterwards vlr.gg only keeps the winner's price, so the other side is null.
 */
data class BookOdds(val team1: Double?, val team2: Double?)

data class MatchDetail(
    val id: String,
    val eventId: String?,
    val eventName: String,
    val eventLogo: String?,
    val series: String,
    val startsAt: Instant?,
    val patch: String?,
    val team1: Team,
    val team2: Team,
    val score1: String?,
    val score2: String?,
    val status: MatchStatus,
    val format: String?,
    val veto: List<VetoStep>,
    val vetoRaw: String?,
    val games: List<Game>,
    val overall: Game?,
    val streams: List<StreamLink>,
    val vods: List<StreamLink>,
    val odds: List<BookOdds> = emptyList(),
)

data class StandingRow(val team: Team, val country: String, val points: Int, val qualified: Boolean)

data class RegionStanding(val title: String, val rows: List<StandingRow>)

data class TeamMatch(
    val matchId: String,
    val eventName: String,
    val series: String,
    val opponent: Team,
    val scoreFor: String?,
    val scoreAgainst: String?,
    val won: Boolean?,
    val date: String,
)

data class RosterMember(
    val name: String,
    val realName: String,
    val id: String?,
    val flag: String?,
    val photo: String?,
    val role: String?,
    val isStaff: Boolean,
)

data class TeamDetail(
    val id: String,
    val name: String,
    val tag: String,
    val logo: String?,
    val country: String,
    val flag: String?,
    val roster: List<RosterMember>,
    val upcoming: List<TeamMatch>,
    val recent: List<TeamMatch>,
)

data class AgentStat(
    val agent: String,
    val usage: String,
    val rounds: String,
    val rating: String,
    val acs: String,
    val kd: String,
    val kast: String,
    val adr: String,
    val fkfd: String,
)

data class PlayerTeam(val team: Team, val note: String)

data class PlayerDetail(
    val id: String,
    val alias: String,
    val realName: String,
    val photo: String?,
    val country: String,
    val flag: String?,
    val agents: List<AgentStat>,
    val currentTeams: List<PlayerTeam>,
    val pastTeams: List<PlayerTeam>,
    val recent: List<TeamMatch>,
)

data class HistoricEvent(
    val name: String,
    val dates: String,
    val prize: String,
    val location: String,
    val participants: String,
    val winner: String,
    val runnerUp: String,
    val isInternational: Boolean,
)

data class HistoryYear(val year: String, val events: List<HistoricEvent>)
