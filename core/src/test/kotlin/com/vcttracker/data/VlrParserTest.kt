package com.vcttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class VlrParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource(name)) { "missing $name" }.readText()

    @Test
    fun seasonAssignsStagesInCircuitOrder() {
        val season = VlrParser.parseSeason(fixture("vct.html"), 2026)
        assertEquals(15, season.events.size)
        val champs = season.events.single { it.stage == Stage.CHAMPIONS }
        assertEquals("2766", champs.id)
        assertEquals(MatchStatus.LIVE, champs.status)
        assertEquals("$2,250,000", champs.prize)
        assertEquals(Region.INTERNATIONAL, champs.region)
        assertEquals("Santiago", VlrParser.mastersCity(season.events.single { it.stage == Stage.MASTERS_1 }.name))
        assertEquals("London", VlrParser.mastersCity(season.events.single { it.stage == Stage.MASTERS_2 }.name))
        assertEquals(4, season.events.count { it.stage == Stage.KICKOFF })
        assertEquals(4, season.events.count { it.stage == Stage.STAGE_1 })
        assertEquals(4, season.events.count { it.stage == Stage.STAGE_2 })
        assertEquals(Region.PACIFIC, season.events.first { it.name.contains("Pacific Stage 2") }.region)
        assertTrue(season.events.all { it.logo?.startsWith("https://") == true })
    }

    @Test
    fun upcomingMatchesUseCentralTime() {
        // 100 Thieves vs T1 is listed at 4:00 AM on Sep 27; bracket timestamp says 1790499600.
        val days = VlrParser.parseMatchList(fixture("matches.html"), now = Instant.ofEpochSecond(1790499600).minus(Duration.ofMinutes(12 * 60 + 47)))
        val m = days.flatMap { it.matches }.first { it.id == "753444" }
        assertEquals("100 Thieves", m.team1.team.name)
        assertEquals("T1", m.team2.team.name)
        assertEquals("us", m.team1.team.flag)
        assertEquals(MatchStatus.UPCOMING, m.status)
        assertEquals("Valorant Champions 2026", m.eventName)
        assertEquals(Instant.ofEpochSecond(1790499600), m.startsAt)
        assertTrue(VlrParser.isVctEvent(m.eventName))
    }

    @Test
    fun matchListCorrectsForAnotherRenderedZone() {
        // Pretend the page was rendered 3h ahead of Central: countdowns disagree by 3h.
        val now = Instant.ofEpochSecond(1790499600).minus(Duration.ofMinutes(12 * 60 + 47)).plus(Duration.ofHours(3))
        val m = VlrParser.parseMatchList(fixture("matches.html"), now).flatMap { it.matches }.first { it.id == "753444" }
        assertEquals(Instant.ofEpochSecond(1790499600).plus(Duration.ofHours(3)), m.startsAt)
    }

    @Test
    fun resultsCarryScoresAndWinners() {
        val days = VlrParser.parseMatchList(fixture("event_matches.html"))
        val m = days.flatMap { it.matches }.first { it.id == "753455" }
        assertEquals("Team Liquid", m.team1.team.name)
        assertEquals("1", m.team1.score)
        assertEquals("2", m.team2.score)
        assertTrue(m.team2.isWinner)
        assertFalse(m.team1.isWinner)
        assertEquals(MatchStatus.COMPLETED, m.status)
        assertEquals("Opening (C)", m.series)
        assertEquals("Thu, September 24, 2026", days.first().label)
        val results = VlrParser.parseMatchList(fixture("results.html")).flatMap { it.matches }
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.status == MatchStatus.COMPLETED })
    }

    @Test
    fun eventBracketsPrizesAndTeams() {
        val e = VlrParser.parseEvent(fixture("event_champions.html"), "2766")
        assertEquals("Valorant Champions 2026", e.name)
        assertEquals("Sep 24 – Oct 18, 2026", e.dates)
        assertEquals("$2,250,000", e.prize)
        assertEquals("Shanghai", e.location)
        assertEquals("cn", e.flag)
        assertEquals(listOf("Playoffs", "Group Stage"), e.subPages.map { it.label })
        assertTrue(e.subPages[1].active)
        assertEquals(listOf("Group A", "Group B", "Group C", "Group D"), e.sections.map { it.title })
        val groupB = e.sections[1]
        assertEquals(2, groupB.brackets.size)
        assertTrue(groupB.brackets[1].isLower)
        val opening = groupB.brackets[0].columns[0]
        assertEquals("Opening", opening.label)
        val first = opening.matches[0]
        assertEquals("753449", first.matchId)
        assertEquals("Team Vitality", first.team2!!.team.name)
        assertTrue(first.team2!!.isWinner)
        assertEquals("2", first.team2!!.score)
        assertEquals(Instant.ofEpochSecond(1790413200), first.startsAt)
        assertEquals(16, e.teams.size)
        assertEquals("100 Thieves", e.teams[0].team.name)
        assertEquals(5, e.teams[0].players.size)
        assertTrue(e.prizes.isNotEmpty())
    }

    @Test
    fun eventGroupTablesAndCircuitPoints() {
        val e = VlrParser.parseEvent(fixture("event_groups.html"), "2977")
        assertTrue(e.groups.isNotEmpty())
        val alpha = e.groups.first()
        assertEquals("Group Alpha", alpha.title)
        val nrg = alpha.rows.first()
        assertEquals("NRG", nrg.team.name)
        assertEquals("United States", nrg.country)
        assertEquals("5–0", nrg.record)
        assertEquals("10/2", nrg.maps)
        assertEquals("+42", nrg.diff)
        assertTrue(nrg.advanced)

        val playoffs = VlrParser.parseEvent(fixture("event_playoffs.html"), "2977")
        val winner = playoffs.prizes.first()
        assertEquals("1st", winner.place)
        assertEquals("$100,000", winner.prize)
        assertEquals("100 Thieves", winner.team!!.name)
        assertEquals("+8", winner.points)
        assertEquals("Champions", winner.note)
    }

    @Test
    fun matchPageMapsRoundsAndPlayers() {
        val m = VlrParser.parseMatch(fixture("match.html"), "753450")
        assertEquals("LOUD", m.team1.name)
        assertEquals("EDward Gaming", m.team2.name)
        assertEquals("2", m.score1)
        assertEquals("0", m.score2)
        assertEquals(MatchStatus.COMPLETED, m.status)
        assertEquals("Bo3", m.format)
        assertEquals("2766", m.eventId)
        assertEquals("Valorant Champions 2026", m.eventName)
        assertEquals("Group Stage: Opening (B)", m.series)
        // 08:30 US Eastern = 12:30 UTC
        assertEquals(Instant.parse("2026-09-26T12:30:00Z"), m.startsAt)
        assertEquals(7, m.veto.size)
        assertEquals(VetoStep("EDG", "pick", "Lotus"), m.veto[2])
        assertEquals(VetoStep("", "remains", "Sunset"), m.veto.last())

        assertEquals(listOf("Lotus", "Summit", "Sunset"), m.games.map { it.map })
        val lotus = m.games[0]
        assertEquals("13", lotus.score1)
        assertEquals("8", lotus.score2)
        assertEquals(2, lotus.pickedBy)
        assertEquals("9", lotus.team1Defense)
        assertEquals("4", lotus.team1Attack)
        assertEquals(21, lotus.rounds.size)
        assertEquals(Round(1, 2, Side.ATTACK, RoundEnd.ELIMINATION), lotus.rounds[0])
        assertEquals(5, lotus.players1.size)
        assertEquals(5, lotus.players2.size)
        val tkzin = lotus.players1[0]
        assertEquals("tkzin", tkzin.name)
        assertEquals(listOf("Neon"), tkzin.agents)
        assertEquals(SideValue("1.65", "0.88", "2.22"), tkzin.stats["rating2"])
        assertEquals("28", tkzin.stats["kills"]!!.both)
        assertFalse(m.games[2].played)
        assertNotNull(m.overall)
        assertEquals(5, m.overall!!.players1.size)
        assertTrue(m.streams.isNotEmpty())
        assertEquals(3, m.vods.size)
        // Completed page: only the winner's pre-match price survives.
        assertEquals(listOf(BookOdds(1.40, null), BookOdds(1.41, null)), m.odds)
    }

    @Test
    fun upcomingMatchHasBothSidesOfTheOdds() {
        val m = VlrParser.parseMatch(fixture("match_upcoming.html"), "753444")
        assertEquals(MatchStatus.UPCOMING, m.status)
        assertEquals(listOf(BookOdds(1.42, 2.74), BookOdds(1.36, 3.00)), m.odds)
        assertEquals("Bo3", m.format)
    }

    @Test
    fun swissTableRecordsComeFromTheirOwnColumns() {
        val e = VlrParser.parseEvent(fixture("event_swiss.html"), "2282")
        val table = e.groups.single()
        assertEquals(8, table.rows.size)
        val genG = table.rows.first()
        assertEquals("Gen.G", genG.team.name)
        assertEquals("2–0", genG.record)
        assertEquals("4/0", genG.maps)
        assertTrue(genG.advanced)
        assertEquals(4, table.rows.count { it.advanced })
    }

    @Test
    fun circuitStandings() {
        val regions = VlrParser.parseStandings(fixture("standings.html"))
        assertEquals(4, regions.size)
        assertEquals("Americas", regions[0].title)
        val top = regions[0].rows[0]
        assertEquals("G2 Esports", top.team.name)
        assertEquals(21, top.points)
        assertTrue(top.qualified)
    }

    @Test
    fun teamPage() {
        val t = VlrParser.parseTeam(fixture("team.html"), "1034")
        assertEquals("NRG", t.name)
        assertEquals("United States", t.country)
        assertEquals("us", t.flag)
        val players = t.roster.filterNot { it.isStaff }
        assertTrue(players.size >= 5)
        assertEquals("Ethan", players[0].name)
        assertEquals("captain", players[0].role)
        assertTrue(t.roster.any { it.isStaff })
        assertEquals("Karmine Corp", t.upcoming.first().opponent.name)
        val last = t.recent.first()
        assertEquals("Nongshim RedForce", last.opponent.name)
        assertEquals(true, last.won)
        assertEquals("2", last.scoreFor)
        assertEquals("Champions 2026", last.eventName)
        assertEquals("Group Stage · Opening (D)", last.series)
        assertEquals("2026/09/25 4:00 am", last.date)
    }

    @Test
    fun playerPage() {
        val p = VlrParser.parsePlayer(fixture("player.html"), "41224")
        assertEquals("tkzin", p.alias)
        assertEquals("Enzo Zimiani", p.realName)
        assertEquals("Brazil", p.country)
        assertEquals("neon", p.agents.first().agent)
        assertEquals("0.93", p.agents.first().rating)
        assertEquals("LOUD", p.currentTeams.first().team.name)
        assertTrue(p.pastTeams.isNotEmpty())
        assertTrue(p.recent.isNotEmpty())
    }

    @Test
    fun etaParsing() {
        assertEquals(Duration.ofMinutes(12 * 60 + 47), parseEta("12h 47m"))
        assertEquals(Duration.ofDays(240), parseEta("8mo"))
        assertTrue(etaIsPrecise("12h 47m"))
        assertFalse(etaIsPrecise("2d 11h"))
        assertFalse(etaIsPrecise("8mo"))
    }
}
