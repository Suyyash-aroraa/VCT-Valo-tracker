package com.vcttracker.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Duration
import java.time.Instant
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Turns vlr.gg pages into models. Every function is pure so it can be tested against saved pages. */
object VlrParser {

    private val VCT_EVENT = Regex(
        "(champions tour|\\bvct\\b|valorant masters|valorant champions|lock//in)",
        RegexOption.IGNORE_CASE,
    )

    fun isVctEvent(name: String): Boolean = VCT_EVENT.containsMatchIn(name)

    // ---------------------------------------------------------------- season

    fun parseSeason(html: String, year: Int): Season {
        val doc = Jsoup.parse(html, VLR)
        val raw = doc.select("a.event-item").mapNotNull { a ->
            val id = idFromHref(a.attr("href")) ?: return@mapNotNull null
            val name = a.selectFirst(".event-item-title").txt()
            val statusEl = a.selectFirst(".event-item-desc-item-status")
            val status = when {
                statusEl?.hasClass("mod-ongoing") == true -> MatchStatus.LIVE
                statusEl?.hasClass("mod-upcoming") == true -> MatchStatus.UPCOMING
                else -> MatchStatus.COMPLETED
            }
            EventSummary(
                id = id,
                name = name,
                status = status,
                prize = a.selectFirst(".mod-prize").own(),
                dates = a.selectFirst(".mod-dates").own(),
                flag = a.selectFirst(".mod-location").flag(),
                logo = a.selectFirst(".event-item-thumb").img(),
                region = Region.fromName(name),
                stage = Stage.OTHER,
            )
        }.distinctBy { it.id }
        return Season(year, assignStages(raw))
    }

    private fun startMonth(dates: String): Int {
        val word = dates.trim().take(3)
        return Month.entries.firstOrNull {
            it.getDisplayName(TextStyle.SHORT, Locale.US).equals(word, true)
        }?.value ?: 13
    }

    private fun assignStages(events: List<EventSummary>): List<EventSummary> {
        val masters = events
            .filter { it.name.contains("Masters", true) }
            .sortedWith(compareBy({ startMonth(it.dates) }, { it.id.toIntOrNull() ?: 0 }))
        return events.map { e ->
            val n = e.name.lowercase(Locale.US)
            val stage = when {
                "kickoff" in n || "lock-in" in n || "lock//in" in n -> Stage.KICKOFF
                "masters" in n -> if (masters.indexOf(e) <= 0) Stage.MASTERS_1 else Stage.MASTERS_2
                "stage 1" in n || n.endsWith("league") -> Stage.STAGE_1
                "stage 2" in n || "last chance" in n || "qualifier" in n -> Stage.STAGE_2
                "valorant champions" in n || ("champions" in n && "champions tour" !in n) -> Stage.CHAMPIONS
                else -> Stage.OTHER
            }
            val region = if (stage == Stage.MASTERS_1 || stage == Stage.MASTERS_2 || stage == Stage.CHAMPIONS) {
                Region.INTERNATIONAL
            } else e.region
            e.copy(stage = stage, region = region)
        }
    }

    /** "Valorant Masters London 2026" -> "London"; "Champions Tour 2023: Masters Tokyo" -> "Tokyo". */
    fun mastersCity(name: String): String =
        name.substringAfterLast("Masters", "").replace(Regex("\\b20\\d\\d\\b"), "").trim().ifEmpty { "Masters" }

    // ---------------------------------------------------------------- match lists

    /**
     * Parses /matches, /matches/results and /event/matches pages.
     * List times are server-rendered in US Central; upcoming countdowns are used to
     * detect and correct any other zone vlr.gg might render for a given visitor.
     */
    fun parseMatchList(html: String, now: Instant = Instant.now()): List<MatchDay> {
        val doc = Jsoup.parse(html, VLR)
        data class Pending(val day: String, val summary: MatchSummary, val eta: String)

        val pending = mutableListOf<Pending>()
        for (label in doc.select("div.wf-label.mod-large")) {
            val card = label.nextElementSibling() ?: continue
            if (!card.hasClass("wf-card")) continue
            val dayText = label.own().ifEmpty { label.txt() }
            val date = parseListDate(dayText)
            for (a in card.select("a.match-item")) {
                val id = idFromHref(a.attr("href")) ?: continue
                val teams = a.select(".match-item-vs-team")
                if (teams.size < 2) continue
                val ml = a.selectFirst(".ml")
                val statusText = a.selectFirst(".ml-status").txt().lowercase(Locale.US)
                val status = when {
                    ml?.hasClass("mod-live") == true || statusText == "live" -> MatchStatus.LIVE
                    ml?.hasClass("mod-completed") == true || statusText == "completed" -> MatchStatus.COMPLETED
                    else -> MatchStatus.UPCOMING
                }
                val eventEl = a.selectFirst(".match-item-event")
                val summary = MatchSummary(
                    id = id,
                    team1 = listSide(teams[0]),
                    team2 = listSide(teams[1]),
                    status = status,
                    startsAt = listInstant(date, parseListTime(a.selectFirst(".match-item-time").txt())),
                    eventName = eventEl.own(),
                    eventLogo = a.selectFirst(".match-item-icon").img(),
                    series = collapse(eventEl?.selectFirst(".match-item-event-series").txt()),
                )
                pending += Pending(dayText, summary, a.selectFirst(".ml-eta").txt())
            }
        }

        // Estimate how far vlr's rendered zone is from US Central using precise countdowns.
        val corrections = pending.mapNotNull { p ->
            val start = p.summary.startsAt ?: return@mapNotNull null
            if (p.summary.status != MatchStatus.UPCOMING || !etaIsPrecise(p.eta)) return@mapNotNull null
            val expected = now.plus(parseEta(p.eta) ?: return@mapNotNull null)
            val diffMin = Duration.between(start, expected).toMinutes()
            Math.round(diffMin / 15.0) * 15
        }.sorted()
        val shift = corrections.getOrNull(corrections.size / 2)?.takeIf { kotlin.math.abs(it) >= 30 } ?: 0L

        return pending.groupBy { it.day }.map { (day, items) ->
            MatchDay(
                label = collapse(day),
                matches = items.map { p ->
                    if (shift == 0L) p.summary
                    else p.summary.copy(startsAt = p.summary.startsAt?.plus(Duration.ofMinutes(shift)))
                },
            )
        }
    }

    private fun listSide(el: Element): MatchSide {
        val score = el.selectFirst(".match-item-vs-team-score").txt().takeUnless { it.isBlank() || it == "–" || it == "-" }
        return MatchSide(
            team = Team(
                name = el.selectFirst(".match-item-vs-team-name .text-of").txt().ifEmpty { "TBD" },
                flag = el.flag(),
            ),
            score = score,
            isWinner = el.hasClass("mod-winner"),
        )
    }

    // ---------------------------------------------------------------- event

    fun parseEvent(html: String, id: String): EventDetail {
        val doc = Jsoup.parse(html, VLR)
        val meta = doc.select(".event-header-main-meta > div").associate { div ->
            div.selectFirst(".label").txt().lowercase(Locale.US) to div.selectFirst(".value")
        }
        val subPages = doc.select("a.wf-subnav-item").map { a ->
            EventSubPage(
                label = a.selectFirst(".wf-subnav-item-title").txt(),
                dates = a.selectFirst(".ge-text-light").txt(),
                path = a.attr("href"),
                active = a.hasClass("mod-active"),
            )
        }
        return EventDetail(
            id = id,
            name = doc.selectFirst(".event-header-main-title").txt(),
            subtitle = doc.selectFirst(".event-header-main-desc").txt(),
            dates = collapse(meta["dates"].txt()),
            prize = collapse(meta["prize"].txt()),
            location = collapse(meta["location"].txt()),
            flag = meta["location"].flag(),
            logo = doc.selectFirst(".event-header-thumb").img(),
            subPages = subPages,
            sections = parseBrackets(doc),
            groups = parseGroups(doc),
            prizes = parsePrizes(doc),
            teams = parseEventTeams(doc),
        )
    }

    private fun parseBrackets(doc: Document): List<BracketSection> =
        doc.select("div.wf-card.mod-bracket").map { card ->
            val title = card.previousElementSibling().txt()
            val brackets = card.select(".bracket-container").map { container ->
                Bracket(
                    isLower = container.hasClass("mod-lower"),
                    columns = container.select("> .bracket-col").map { col ->
                        BracketColumn(
                            label = col.selectFirst(".bracket-col-label").txt(),
                            matches = col.select(".bracket-item").map(::bracketMatch),
                        )
                    },
                )
            }
            BracketSection(title, brackets)
        }

    private fun bracketMatch(item: Element): BracketMatch {
        val teams = item.select(".bracket-item-team").map { t ->
            val nameEl = t.selectFirst(".bracket-item-team-name")
            BracketTeam(
                team = Team(
                    name = nameEl?.selectFirst("span").txt().ifEmpty { "TBD" },
                    logo = nameEl.img(),
                    id = t.attr("data-team-id").ifBlank { null },
                ),
                score = t.selectFirst(".bracket-item-team-score").txt().let { if (it == "-") "" else it },
                isWinner = t.hasClass("mod-winner"),
                isLoser = t.hasClass("mod-loser"),
            )
        }
        val ts = item.selectFirst("[data-utc-ts]")?.attr("data-utc-ts")?.toLongOrNull()
        return BracketMatch(
            matchId = if (item.tagName() == "a") idFromHref(item.attr("href")) else null,
            team1 = teams.firstOrNull() ?: BracketTeam(Team("TBD"), "", false, false),
            team2 = teams.getOrNull(1),
            startsAt = ts?.let { Instant.ofEpochSecond(it) },
        )
    }

    private fun parseGroups(doc: Document): List<GroupTable> =
        doc.select("table.wf-table.mod-group").map { table ->
            GroupTable(
                title = table.selectFirst("th.mod-title").txt(),
                rows = table.select("tbody tr").map { tr ->
                    val a = tr.selectFirst("a.event-group-team")
                    val stats = tr.select("td.mod-stat").map { collapse(it.text()).replace(" ", "") }
                    GroupRow(
                        team = Team(
                            name = a?.selectFirst(".event-group-team-name").own(),
                            logo = tr.img(),
                            id = idFromHref(a?.attr("href")),
                        ),
                        country = tr.selectFirst(".event-group-team-region").txt(),
                        record = stats.getOrElse(0) { "" },
                        maps = stats.getOrElse(1) { "" },
                        rounds = stats.getOrElse(2) { "" },
                        diff = stats.getOrElse(3) { "" },
                        advanced = tr.hasClass("mod-adv"),
                        eliminated = tr.hasClass("mod-elim"),
                    )
                },
            )
        }

    private fun parsePrizes(doc: Document): List<PrizeRow> {
        val table = doc.selectFirst(".wf-ptable--standings") ?: return emptyList()
        val rows = table.select("> .row")
        val header = rows.firstOrNull()?.select("> .cell")?.map { it.txt().lowercase(Locale.US) } ?: return emptyList()
        val pointsIdx = header.indexOf("points")
        val noteIdx = header.indexOf("note")
        return rows.drop(1).map { row ->
            val cells = row.select("> .cell")
            val teamCell = cells.getOrNull(2)
            val link = teamCell?.selectFirst("a")
            val nameBlock = link?.selectFirst(".text-of")
            PrizeRow(
                place = collapse(cells.getOrNull(0).txt()),
                prize = cells.getOrNull(1).txt(),
                team = link?.let {
                    Team(name = nameBlock.own(), logo = it.img(), id = idFromHref(it.attr("href")))
                },
                country = nameBlock?.selectFirst(".ge-text-light").txt().ifBlank { null },
                points = cells.getOrNull(pointsIdx)?.txt()?.replace(" ", "")?.ifBlank { null }?.takeIf { pointsIdx >= 0 },
                note = cells.getOrNull(noteIdx)?.txt()?.ifBlank { null }?.takeIf { noteIdx >= 0 },
            )
        }
    }

    private fun parseEventTeams(doc: Document): List<EventTeam> =
        doc.select(".event-team").map { card ->
            val a = card.selectFirst("a.event-team-name")
            EventTeam(
                team = Team(
                    name = a.txt(),
                    logo = absImage(card.selectFirst(".event-team-players-mask-team")?.attr("src")),
                    id = idFromHref(a?.attr("href")),
                ),
                players = card.select("a.event-team-players-item").map { p ->
                    PlayerRef(p.txt(), idFromHref(p.attr("href")), p.flag())
                },
                note = card.selectFirst(".event-team-note").txt().ifBlank { null },
            )
        }

    // ---------------------------------------------------------------- match

    fun parseMatch(html: String, id: String): MatchDetail {
        val doc = Jsoup.parse(html, VLR)
        val header = doc.selectFirst(".match-header")
        val eventLink = header?.selectFirst("a.match-header-event")
        val link1 = header?.selectFirst("a.match-header-link.mod-1")
        val link2 = header?.selectFirst("a.match-header-link.mod-2")
        val notes = header?.select(".match-header-vs-note")?.map { it.txt() }.orEmpty()
        val scoreSpans = header?.selectFirst(".match-header-vs-score .js-spoiler, .match-header-vs-score .sp-hide")
            ?.select("span")
            ?.filterNot { it.hasClass("match-header-vs-score-colon") }
            ?.map { it.txt() }
            .orEmpty()
        val statusNote = notes.firstOrNull()?.lowercase(Locale.US).orEmpty()
        val status = when {
            statusNote.contains("live") -> MatchStatus.LIVE
            statusNote.contains("final") -> MatchStatus.COMPLETED
            else -> MatchStatus.UPCOMING
        }
        val team1 = Team(
            name = link1?.selectFirst(".wf-title-med").txt().ifEmpty { "TBD" },
            logo = link1.img(),
            id = idFromHref(link1?.attr("href")),
        )
        val team2 = Team(
            name = link2?.selectFirst(".wf-title-med").txt().ifEmpty { "TBD" },
            logo = link2.img(),
            id = idFromHref(link2?.attr("href")),
        )
        val vetoRaw = header?.selectFirst(".match-header-note").txt().ifBlank { null }

        val navItems = doc.select(".vm-stats-gamesnav-item").filterNot { it.hasClass("mod-all") }
        val disabled = navItems.filter { it.attr("data-disabled") == "1" }.map { it.attr("data-game-id") }.toSet()
        val gameEls = doc.select(".vm-stats-game[data-game-id]")
        var mapNumber = 0
        val games = gameEls.filter { it.attr("data-game-id") != "all" }.map { el ->
            mapNumber++
            parseGame(el, mapNumber, played = el.attr("data-game-id") !in disabled)
        }
        val unplayed = navItems.filter { it.attr("data-game-id") in disabled && gameEls.none { g -> g.attr("data-game-id") == it.attr("data-game-id") } }
            .map { nav ->
                mapNumber++
                Game(
                    id = nav.attr("data-game-id"), number = mapNumber,
                    map = nav.selectFirst("div").own().ifEmpty { nav.own() },
                    pickedBy = 0, duration = "", score1 = "", score2 = "",
                    team1Defense = "", team1Attack = "", team2Defense = "", team2Attack = "",
                    rounds = emptyList(), players1 = emptyList(), players2 = emptyList(), played = false,
                )
            }
        val overall = gameEls.firstOrNull { it.attr("data-game-id") == "all" }?.let { parseGame(it, 0, true) }

        return MatchDetail(
            id = id,
            eventId = idFromHref(eventLink?.attr("href")),
            eventName = eventLink?.selectFirst("div > div").txt(),
            eventLogo = eventLink.img(),
            series = collapse(eventLink?.selectFirst(".match-header-event-series").txt()),
            startsAt = parseMatchTs(header?.selectFirst(".match-header-date [data-utc-ts]")?.attr("data-utc-ts")),
            patch = header?.selectFirst(".match-header-date [style*=italic]").txt().ifBlank { null },
            team1 = team1,
            team2 = team2,
            score1 = scoreSpans.getOrNull(0)?.ifBlank { null },
            score2 = scoreSpans.getOrNull(1)?.ifBlank { null },
            status = status,
            format = notes.firstOrNull { Regex("^Bo\\d+$", RegexOption.IGNORE_CASE).matches(it) },
            veto = parseVeto(vetoRaw),
            vetoRaw = vetoRaw,
            games = (games + unplayed).filterNot { !it.played && it.map.equals("TBD", true) },
            overall = overall,
            streams = doc.select(".sm-pane-streams .sm-btn").mapNotNull { btn ->
                val url = btn.selectFirst("a.sm-ext")?.attr("href") ?: return@mapNotNull null
                StreamLink(btn.selectFirst(".sm-name").txt(), url, btn.flag())
            },
            odds = parseOdds(doc, team1.name, team2.name),
            vods = doc.select(".sm-vod").mapNotNull { v ->
                val url = v.selectFirst("a.sm-ext")?.attr("href") ?: return@mapNotNull null
                val num = v.selectFirst(".sm-vod-num").txt()
                val name = v.selectFirst(".sm-vod-name").txt()
                StreamLink(if (num.isNotEmpty()) "Map $num · $name" else name, url, null)
            },
        )
    }

    private fun parseOdds(doc: Document, name1: String, name2: String): List<BookOdds> =
        doc.select("a.match-bet-item").mapNotNull { item ->
            fun price(el: Element?) = el?.txt()?.toDoubleOrNull()?.takeIf { it > 1.0 }
            if (item.hasClass("mod-post-odds")) {
                val short = item.selectFirst(".match-bet-item-return-short") ?: return@mapNotNull null
                val price = price(short.selectFirst(".match-bet-item-odds")) ?: return@mapNotNull null
                when (short.selectFirst(".match-bet-item-teamzzz").txt().lowercase(Locale.US)) {
                    name1.lowercase(Locale.US) -> BookOdds(price, null)
                    name2.lowercase(Locale.US) -> BookOdds(null, price)
                    else -> null
                }
            } else {
                val o1 = price(item.selectFirst(".match-bet-item-odds.mod-1"))
                val o2 = price(item.selectFirst(".match-bet-item-odds.mod-2"))
                if (o1 == null && o2 == null) null else BookOdds(o1, o2)
            }
        }

    fun parseVeto(raw: String?): List<VetoStep> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val words = part.trim().split(' ').filter { it.isNotBlank() }
            when {
                words.size >= 2 && words.last().equals("remains", true) ->
                    VetoStep("", "remains", words.dropLast(1).joinToString(" "))
                words.size >= 3 && words[words.size - 2].lowercase(Locale.US) in setOf("ban", "pick") ->
                    VetoStep(words.dropLast(2).joinToString(" "), words[words.size - 2].lowercase(Locale.US), words.last())
                else -> null
            }
        }
    }

    private fun parseGame(el: Element, number: Int, played: Boolean): Game {
        val header = el.selectFirst(".vm-stats-game-header")
        val teams = header?.select("> .team").orEmpty()
        val mapSpan = header?.selectFirst(".map span")
        val picked = mapSpan?.selectFirst(".picked")
        val pickedBy = when {
            picked == null -> 0
            picked.hasClass("mod-1") -> 1
            picked.hasClass("mod-2") -> 2
            else -> 0
        }
        val rounds = el.select(".vlr-rounds-row-col").mapNotNull { col ->
            val num = col.selectFirst(".rnd-num").txt().toIntOrNull() ?: return@mapNotNull null
            val squares = col.select(".rnd-sq")
            val winnerIdx = squares.indexOfFirst { it.hasClass("mod-win") }
            if (winnerIdx < 0) return@mapNotNull null
            val sq = squares[winnerIdx]
            val icon = sq.selectFirst("img")?.attr("src").orEmpty()
            Round(
                number = num,
                winner = winnerIdx + 1,
                side = when {
                    sq.hasClass("mod-t") -> Side.ATTACK
                    sq.hasClass("mod-ct") -> Side.DEFENSE
                    else -> null
                },
                end = when {
                    "elim" in icon -> RoundEnd.ELIMINATION
                    "defuse" in icon -> RoundEnd.DEFUSE
                    "boom" in icon -> RoundEnd.DETONATE
                    "time" in icon -> RoundEnd.TIME
                    else -> null
                },
            )
        }
        val tables = el.select(".ovw-table")
        val s1 = teams.getOrNull(0)?.selectFirst(".score").txt()
        val s2 = teams.getOrNull(1)?.selectFirst(".score").txt()
        // A map counts as played once a round has been won on it.
        val started = rounds.isNotEmpty() || (s1.toIntOrNull() ?: 0) + (s2.toIntOrNull() ?: 0) > 0
        return Game(
            id = el.attr("data-game-id"),
            number = number,
            map = mapSpan.own().ifEmpty { "All maps" },
            pickedBy = pickedBy,
            duration = header?.selectFirst(".map-duration").txt(),
            score1 = s1,
            score2 = s2,
            team1Defense = teams.getOrNull(0)?.selectFirst("span.mod-ct").txt(),
            team1Attack = teams.getOrNull(0)?.selectFirst("span.mod-t").txt(),
            team2Defense = teams.getOrNull(1)?.selectFirst("span.mod-ct").txt(),
            team2Attack = teams.getOrNull(1)?.selectFirst("span.mod-t").txt(),
            rounds = rounds,
            players1 = tables.getOrNull(0)?.let(::parsePlayers).orEmpty(),
            players2 = tables.getOrNull(1)?.let(::parsePlayers).orEmpty(),
            played = played && (number == 0 || started),
        )
    }

    private fun parsePlayers(table: Element): List<PlayerLine> =
        table.select(".ovw-row:not(.mod-head)").map { row ->
            val a = row.selectFirst(".ovw-player a")
            val stats = LinkedHashMap<String, SideValue>()
            row.select("[data-col]").forEach { cell ->
                stats[cell.attr("data-col")] = SideValue(
                    both = cell.selectFirst(".side.mod-both").txt(),
                    attack = cell.selectFirst(".side.mod-t").txt(),
                    defense = cell.selectFirst(".side.mod-ct").txt(),
                )
            }
            PlayerLine(
                name = row.selectFirst(".ovw-player-name").txt(),
                id = idFromHref(a?.attr("href")),
                flag = row.selectFirst(".ovw-player").flag(),
                agents = row.select(".ovw-agents img").map { it.attr("title").ifBlank { it.attr("alt") } },
                stats = stats,
            )
        }

    // ---------------------------------------------------------------- standings

    fun parseStandings(html: String): List<RegionStanding> {
        val doc = Jsoup.parse(html, VLR)
        return doc.select(".eg-standing-group").map { group ->
            RegionStanding(
                title = group.selectFirst(".wf-label").txt().removeSuffix("Points").trim().removeSuffix("Championship").trim(),
                rows = group.select("tr").mapNotNull { tr ->
                    val a = tr.selectFirst("td.eg-standing-group-team a") ?: return@mapNotNull null
                    StandingRow(
                        team = Team(
                            name = a.selectFirst("div[style*=font-weight]").txt(),
                            logo = a.img(),
                            id = idFromHref(a.attr("href")),
                        ),
                        country = a.selectFirst(".ge-text-light").txt(),
                        points = tr.select("td").getOrNull(1).txt().filter(Char::isDigit).toIntOrNull() ?: 0,
                        qualified = tr.hasClass("mod-adv"),
                    )
                },
            )
        }
    }

    // ---------------------------------------------------------------- team

    fun parseTeam(html: String, id: String): TeamDetail {
        val doc = Jsoup.parse(html, VLR)
        val roster = mutableListOf<RosterMember>()
        var staff = false
        doc.select(".wf-module-label, .team-roster-item").forEach { el ->
            if (el.hasClass("wf-module-label")) {
                staff = el.txt().equals("staff", true)
                return@forEach
            }
            val a = el.selectFirst("a")
            val alias = el.selectFirst(".team-roster-item-name-alias")
            roster += RosterMember(
                name = alias.own(),
                realName = el.selectFirst(".team-roster-item-name-real").txt(),
                id = idFromHref(a?.attr("href")),
                flag = alias.flag(),
                photo = el.selectFirst(".team-roster-item-img").img(),
                role = el.selectFirst(".team-roster-item-name-role").txt().ifBlank {
                    if (alias?.selectFirst(".fa-star") != null) "captain" else null
                }?.ifBlank { null },
                isStaff = staff,
            )
        }
        val country = doc.selectFirst(".team-header-country")
        return TeamDetail(
            id = id,
            name = doc.selectFirst(".team-header-name h1").txt(),
            tag = doc.selectFirst(".team-header-tag").txt(),
            logo = doc.selectFirst(".team-header-logo").img(),
            country = country.txt(),
            flag = country.flag(),
            roster = roster,
            upcoming = mItemsAfter(doc, "Upcoming"),
            recent = mItemsAfter(doc, "Recent Results"),
        )
    }

    private fun mItemsAfter(doc: Document, heading: String): List<TeamMatch> {
        val h = doc.select("h2.wf-label").firstOrNull { it.txt().startsWith(heading, true) } ?: return emptyList()
        val block = h.nextElementSibling() ?: return emptyList()
        return block.select("a.m-item").mapNotNull(::mItem)
    }

    private fun mItem(a: Element): TeamMatch? {
        val id = idFromHref(a.attr("href")) ?: return null
        val event = a.selectFirst(".m-item-event")
        val teams = a.select(".m-item-team")
        val logos = a.select(".m-item-logo")
        val result = a.selectFirst(".m-item-result")
        val scores = result?.select("> span")?.map { it.txt() }.orEmpty()
        val won = when {
            result?.hasClass("mod-win") == true -> true
            result?.hasClass("mod-loss") == true -> false
            else -> null
        }
        val date = a.selectFirst(".m-item-date")
        return TeamMatch(
            matchId = id,
            eventName = event?.children()?.firstOrNull().txt(),
            series = collapse(event.own().replace("⋅", "·")),
            opponent = Team(
                name = teams.getOrNull(1)?.selectFirst(".m-item-team-name").txt().ifEmpty { "TBD" },
                logo = logos.getOrNull(1).img(),
            ),
            scoreFor = scores.getOrNull(0)?.takeIf { won != null },
            scoreAgainst = scores.getOrNull(1)?.takeIf { won != null },
            won = won,
            date = collapse(date?.children()?.firstOrNull().txt() + " " + date.own()),
        )
    }

    // ---------------------------------------------------------------- player

    fun parsePlayer(html: String, id: String): PlayerDetail {
        val doc = Jsoup.parse(html, VLR)
        val header = doc.selectFirst(".player-header")
        val countryEl = header?.select(".ge-text-light")?.lastOrNull { it.selectFirst(".flag") != null }
        val agents = doc.select("table.st-table tbody tr").map { tr ->
            val tds = tr.select("td")
            fun cell(i: Int) = tds.getOrNull(i).txt()
            AgentStat(
                agent = tr.selectFirst("td.mod-agent img")?.attr("alt").orEmpty(),
                usage = cell(1),
                rounds = cell(2),
                rating = cell(3),
                acs = cell(4),
                kd = cell(5),
                kast = cell(6),
                adr = cell(7),
                fkfd = cell(10),
            )
        }
        return PlayerDetail(
            id = id,
            alias = header?.selectFirst("h1.wf-title").txt(),
            realName = header?.selectFirst(".player-real-name").txt(),
            photo = header?.selectFirst(".wf-avatar").img(),
            country = countryEl.txt().lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) },
            flag = countryEl.flag(),
            agents = agents,
            currentTeams = playerTeams(doc, "Current Teams"),
            pastTeams = playerTeams(doc, "Past Teams"),
            recent = mItemsAfter(doc, "Recent Results"),
        )
    }

    private fun playerTeams(doc: Document, heading: String): List<PlayerTeam> {
        val h = doc.select("h2.wf-label").firstOrNull { it.txt().equals(heading, true) } ?: return emptyList()
        val card = h.nextElementSibling() ?: return emptyList()
        return card.select("a.wf-module-item").map { a ->
            val info = a.select("> div").getOrNull(1)
            PlayerTeam(
                team = Team(
                    name = info?.children()?.firstOrNull().txt(),
                    logo = a.img(),
                    id = idFromHref(a.attr("href")),
                ),
                note = info?.select(".ge-text-light")?.joinToString(" · ") { it.txt() }?.trim(' ', '·').orEmpty(),
            )
        }
    }
}
