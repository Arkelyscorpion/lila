package lila.relay

import chess.format.pgn.*

type TeamName = String

private class RelayTeams(val text: String):

  def sortedText = text.linesIterator.toList.sorted.mkString("\n")

  lazy val teams: Map[TeamName, List[PlayerName]] = text.linesIterator
    .take(1000)
    .toList
    .flatMap: line =>
      line.split(';').map(_.trim) match
        case Array(team, player) => Some(team -> player)
        case _                   => none
    .groupBy(_._1)
    .view
    .mapValues(_.map(_._2))
    .toMap

  private lazy val playerTeams: Map[PlayerName, TeamName] =
    teams.flatMap: (team, players) =>
      players.map(_ -> team)

  def update(games: RelayGames): RelayGames = games.map: game =>
    game.copy(tags = update(game.tags))

  private def update(tags: Tags): Tags =
    chess.Color.all.foldLeft(tags): (tags, color) =>
      tags
        .players(color)
        .flatMap(playerTeams.get)
        .fold(tags): team =>
          tags + Tag(color.fold(Tag.WhiteTeam, Tag.BlackTeam), team)

final class RelayTeamTable(chapterRepo: lila.study.ChapterRepo, cacheApi: lila.memo.CacheApi)(using Executor):

  import play.api.libs.json.*

  def tableJson(relay: RelayRound): Fu[JsonStr] = cache.get(relay.studyId)

  private val cache = cacheApi[StudyId, JsonStr](256, "relay.teamTable"):
    _.expireAfterWrite(3 seconds).buildAsyncFuture(impl.makeJson)

  private object impl:

    import chess.{ Color, Outcome }

    def makeJson(studyId: StudyId): Fu[JsonStr] =
      chapterRepo idAndTagsByStudyId studyId map: chapters =>
        import json.given
        JsonStr(Json.stringify(Json.obj("table" -> makeTable(chapters))))

    case class TeamWithPoints(name: String, points: Float = 0):
      def add(o: Option[Outcome], as: Color) =
        copy(points = points + o.so(_.winner match
          case Some(w) if w == as => 1
          case None               => 0.5f
          case _                  => 0
        ))
    case class TeamPlayer(name: String, rating: Option[Int])
    case class Pair[A](a: A, b: A):
      def is(p: Pair[A])                 = (a == p.a && b == p.b) || (a == p.b && b == p.a)
      def map[B](f: A => B)              = Pair(f(a), f(b))
      def bimap[B](f: A => B, g: A => B) = Pair(f(a), g(b))
      def reverse                        = Pair(b, a)
    case class TeamGame(
        id: StudyChapterId,
        players: Pair[TeamPlayer],
        p0Color: Color,
        outcome: Option[chess.Outcome]
    )
    case class TeamMatch(teams: Pair[TeamWithPoints], games: List[TeamGame]):
      def is(teamNames: Pair[TeamName]) = teams.map(_.name) is teamNames
      def add(id: StudyChapterId, playerAndTeam: Pair[(TeamPlayer, TeamName)], outcome: Option[Outcome]) =
        val t0Color = Color.fromWhite(playerAndTeam.a._2 == teams.a.name)
        val sorted  = if t0Color.white then playerAndTeam else playerAndTeam.reverse
        copy(
          games = TeamGame(id, sorted.map(_._1), t0Color, outcome) :: games,
          teams = teams.bimap(_.add(outcome, t0Color), _.add(outcome, !t0Color))
        )

    def makeTable(chapters: List[lila.study.Chapter.IdAndTags]): List[TeamMatch] =
      chapters.foldLeft(List.empty[TeamMatch]): (table, chap) =>
        (for
          teams                  <- chap.tags.teams.tupled.map(Pair.apply)
          (whiteName, blackName) <- chap.tags.players.tupled
          elos        = chap.tags.elos
          whitePlayer = TeamPlayer(whiteName, elos.white)
          blackPlayer = TeamPlayer(blackName, elos.black)
          outcome     = chap.tags.outcome
          m0          = table.find(_.is(teams)) | TeamMatch(teams.map(TeamWithPoints(_)), Nil)
          m1          = m0.add(chap.id, Pair(whitePlayer -> teams.a, blackPlayer -> teams.b), outcome)
          newTable    = m1 :: table.filterNot(_.is(teams))
        yield newTable) | table

    object json:
      import lila.common.Json.given
      given Writes[Outcome] = Writes: o =>
        JsString(o.winner.fold("draw")(_.name))
      given [A: Writes]: Writes[Pair[A]] = Writes: p =>
        Json.arr(p.a, p.b)
      given Writes[TeamPlayer]     = Json.writes[TeamPlayer]
      given Writes[TeamGame]       = Json.writes[TeamGame]
      given Writes[TeamWithPoints] = Json.writes[TeamWithPoints]
      given Writes[TeamMatch]      = Json.writes[TeamMatch]
