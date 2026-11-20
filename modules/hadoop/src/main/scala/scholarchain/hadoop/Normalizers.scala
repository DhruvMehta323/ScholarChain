package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.WirePickle.{Writer, given}

/** What one enrichment source record produced. */
final case class Normalized(
    source: SourceType,
    sourceRecordId: String,
    retrievedAt: String,
    fileName: String,
    fileSha256: String,
    rows: List[Row],
    upstreamMissing: Boolean,
    yearMissing: Boolean,
    warnings: List[String],
):
  def bundle: Bundle = Bundle(source, sourceRecordId, retrievedAt, fileName, fileSha256, rows)

/** Pure normalizers for the Phase 03 cached envelopes (see JOB-CONTRACTS.md for the format). Each
  * takes the file name, the file bytes and the run context, and returns either a typed result or a
  * [[ParseFailure]] (a reject). No I/O, no clock, no shared state.
  *
  * Identifier and name rules are those of `core` (Doi, Orcid, OpenAlexId, Titles, Ids, Hashing).
  */
object Normalizers:
  val JobId = "hj2-source-normalize"

  private final case class Env(
      body: ujson.Value,
      retrievedAt: String,
      sha: String,
      identifier: Option[String],
      missing: Boolean,
  )

  extension (v: ujson.Value)
    private def field(k: String): Option[ujson.Value] =
      v.objOpt.flatMap(_.get(k)).filter(_ != ujson.Null)
    private def asStr: Option[String] = v match
      case ujson.Str(s) => Some(s)
      case _            => None
    private def asArr: List[ujson.Value] = v match
      case ujson.Arr(a) => a.toList
      case _            => Nil
    private def asInt: Option[Int] = v match
      case ujson.Num(d) if d.isWhole && d.abs < Int.MaxValue.toDouble => Some(d.toInt)
      case ujson.Str(s)                                               => s.trim.toIntOption
      case _                                                          => None

  private def fail(reason: RejectReason, msg: String, id: Option[String] = None) =
    Left(ParseFailure(reason, msg, id))

  /** Removes inline markup, unescapes the common HTML entities and collapses whitespace. */
  def plainText(s: String): String =
    s.replaceAll("<[^>]*>", "")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&amp;", "&")
      .replaceAll("\\s+", " ")
      .trim

  private def envelope(bytes: Array[Byte]): Either[ParseFailure, Env] =
    try
      val js = ujson.read(bytes)
      if js.objOpt.isEmpty then fail(RejectReason.MalformedRecord, "envelope is not a JSON object")
      else
        val at = js.field("retrieved_at").flatMap(_.asStr)
        if at.isEmpty || Try(Instant.parse(at.get)).isFailure then
          fail(RejectReason.SchemaViolation, "retrieved_at is missing or not an ISO-8601 instant")
        else
          Right(
            Env(
              js.field("body").getOrElse(ujson.Null),
              at.get,
              Hashing.sha256Hex(bytes),
              js.field("identifier").flatMap(_.asStr),
              js.field("missing").exists(_ == ujson.True),
            )
          )
    catch
      case NonFatal(e) =>
        fail(RejectReason.MalformedRecord, s"not valid JSON: ${e.getClass.getSimpleName}")

  private def rowOf[A](table: String, sortKey: String, a: A)(using
      c: Contract[A],
      w: Writer[A],
  ): Either[ParseFailure, Row] =
    Wire
      .write(a)
      .left
      .map(e => ParseFailure(RejectReason.SchemaViolation, s"$table: ${e.code}"))
      .map(Row(table, sortKey, _))

  private def idRow(r: IdRef): Row =
    Row("id-refs", s"${r.idType}|${r.value}|${r.ref}", WirePickle.write(r))

  private def digestId(tag: String, name: String): String =
    Hashing.digest(tag, Seq(Titles.titleKey(name)))

  private def fileInput(fileName: String, env: Env): InputRef =
    InputRef(fileName, env.retrievedAt, Some(env.sha))

  private def sequence[A](xs: List[Either[ParseFailure, A]]): Either[ParseFailure, List[A]] =
    xs.foldLeft[Either[ParseFailure, List[A]]](Right(Nil))((acc, x) =>
      acc.flatMap(l => x.map(_ :: l))
    ).map(_.reverse)

  private def upstream(src: SourceType, fileName: String, env: Env): Normalized =
    Normalized(
      src,
      env.identifier.getOrElse(fileName),
      env.retrievedAt,
      fileName,
      env.sha,
      Nil,
      true,
      false,
      Nil,
    )

  private def firstYear(parts: ujson.Value): Option[Int] =
    parts
      .field("date-parts")
      .flatMap(_.asArr.headOption)
      .flatMap(_.asArr.headOption)
      .flatMap(_.asInt)
      .filter(y => PublicationYear.validate(y).isRight)

  private def mentionRows(
      workId: String,
      names: List[(String, Option[String])],
      prov: Provenance,
  ): Either[ParseFailure, List[(AuthorMention, Row)]] =
    sequence(names.zipWithIndex.map { case ((name, orcid), i) =>
      Ids
        .mentionId(workId, i)
        .left
        .map(e => ParseFailure(RejectReason.SchemaViolation, e.code))
        .flatMap { mid =>
          val m = AuthorMention(SchemaVersion.Current, mid, workId, i, name, orcid, prov)
          rowOf("author-mentions", mid, m).map(m -> _)
        }
    })

  // ---- Crossref ---------------------------------------------------------------------------

  def crossref(
      fileName: String,
      bytes: Array[Byte],
      run: RunContext,
  ): Either[ParseFailure, Normalized] =
    envelope(bytes).flatMap { env =>
      if env.missing then Right(upstream(SourceType.Crossref, fileName, env))
      else
        val warnings = ListBuffer.empty[String]
        val msg      = env.body.field("message")
        for
          m   <- msg.toRight(ParseFailure(RejectReason.MalformedRecord, "body.message is missing"))
          raw <- m
            .field("DOI")
            .flatMap(_.asStr)
            .toRight(ParseFailure(RejectReason.InvalidIdentifier, "message.DOI is missing"))
          doi <- Doi
            .normalize(raw)
            .left
            .map(e => ParseFailure(RejectReason.InvalidIdentifier, e.code))
          workId <- Ids
            .workIdFromSource("crossref", doi)
            .left
            .map(e => ParseFailure(RejectReason.InvalidIdentifier, e.code))
          title <- m
            .field("title")
            .flatMap(_.asArr.headOption)
            .flatMap(_.asStr)
            .map(plainText)
            .filter(_.nonEmpty)
            .toRight(
              ParseFailure(RejectReason.MissingTitle, "title is missing or blank", Some(doi))
            )
          prov = run.prov(
            SourceType.Crossref,
            Seq("crossref"),
            JobId,
            List(fileInput(fileName, env)),
            Map.empty,
            Some(env.retrievedAt),
          )
          year = List("issued", "published", "published-print", "published-online").iterator
            .flatMap(k => m.field(k).flatMap(firstYear))
            .nextOption()
          authors = m.field("author").map(_.asArr).getOrElse(Nil).flatMap { a =>
            val name = a
              .field("name")
              .flatMap(_.asStr)
              .filter(_.trim.nonEmpty)
              .getOrElse(
                List(a.field("given"), a.field("family")).flatten.flatMap(_.asStr).mkString(" ")
              )
            val clean = DblpParser.clean(name)
            if clean.isEmpty then None
            else
              val orcid = a
                .field("ORCID")
                .flatMap(_.asStr)
                .flatMap(o => Orcid.parse(o).toOption.orElse { warnings += "invalid_orcid"; None })
              val affs = a
                .field("affiliation")
                .map(_.asArr)
                .getOrElse(Nil)
                .flatMap(_.field("name"))
                .flatMap(_.asStr)
                .map(DblpParser.clean)
                .filter(_.nonEmpty)
              Some((clean, orcid, affs))
          }
          topicLabels = m
            .field("subject")
            .map(_.asArr)
            .getOrElse(Nil)
            .flatMap(_.asStr)
            .map(DblpParser.clean)
            .filter(_.nonEmpty)
            .distinct
          topicIds = topicLabels
            .map(t => s"topic:name:${digestId("scholarchain.topic.name.v1", t)}")
            .distinct
            .sorted
          mentions <- mentionRows(workId, authors.map(a => (a._1, a._2)), prov)
          work     <- year match
            case Some(y) =>
              rowOf(
                "works",
                workId,
                WorkRecord(SchemaVersion.Current, workId, title, y, Some(doi), None, topicIds, prov),
              ).map(List(_))
            case None => Right(Nil)
          insts <- sequence(
            authors
              .flatMap(_._3)
              .distinct
              .map { n =>
                val id = s"institution:name:${digestId("scholarchain.institution.name.v1", n)}"
                rowOf(
                  "institutions",
                  s"$id|$workId",
                  Institution(SchemaVersion.Current, id, n, None, prov),
                )
              }
              .groupBy(_.map(_.sortKey).toOption)
              .toList
              .sortBy(_._1)
              .flatMap(_._2.take(1))
          )
          tops <- sequence(
            topicLabels
              .map { t =>
                val id = s"topic:name:${digestId("scholarchain.topic.name.v1", t)}"
                rowOf("topics", s"$id|$workId", Topic(SchemaVersion.Current, id, t, prov))
              }
              .groupBy(_.map(_.sortKey).toOption)
              .toList
              .sortBy(_._1)
              .flatMap(_._2.take(1))
          )
        yield
          val refs = IdRef("doi", doi, workId, SourceType.Crossref, Map("doi" -> doi)) ::
            mentions.flatMap((mm, _) =>
              mm.orcid.map(o => IdRef("orcid", o, mm.mentionId, SourceType.Crossref, Map.empty))
            )
          Normalized(
            SourceType.Crossref,
            doi,
            env.retrievedAt,
            fileName,
            env.sha,
            (work ++ mentions.map(_._2) ++ insts ++ tops ++ refs.map(idRow)).distinctBy(r =>
              (r.table, r.sortKey)
            ),
            false,
            year.isEmpty,
            warnings.toList,
          )
    }

  // ---- OpenAlex ---------------------------------------------------------------------------

  def openalex(
      fileName: String,
      bytes: Array[Byte],
      run: RunContext,
  ): Either[ParseFailure, Normalized] =
    envelope(bytes).flatMap { env =>
      if env.missing then Right(upstream(SourceType.OpenAlex, fileName, env))
      else
        val warnings = ListBuffer.empty[String]
        val b        = env.body
        for
          rawId <- b
            .field("id")
            .flatMap(_.asStr)
            .toRight(ParseFailure(RejectReason.InvalidIdentifier, "body.id is missing"))
          oaId <- OpenAlexId
            .normalize(rawId)
            .left
            .map(e => ParseFailure(RejectReason.InvalidIdentifier, e.code))
          workId <- Ids
            .workIdFromSource("openalex", oaId)
            .left
            .map(e => ParseFailure(RejectReason.InvalidIdentifier, e.code))
          title <- b
            .field("title")
            .orElse(b.field("display_name"))
            .flatMap(_.asStr)
            .map(plainText)
            .filter(_.nonEmpty)
            .toRight(
              ParseFailure(RejectReason.MissingTitle, "title is missing or blank", Some(oaId))
            )
          prov = run.prov(
            SourceType.OpenAlex,
            Seq("openalex"),
            JobId,
            List(fileInput(fileName, env)),
            Map.empty,
            Some(env.retrievedAt),
          )
          year = b
            .field("publication_year")
            .flatMap(_.asInt)
            .filter(y => PublicationYear.validate(y).isRight)
          doi = b
            .field("doi")
            .flatMap(_.asStr)
            .flatMap(d =>
              Doi.normalize(d).toOption.orElse { warnings += "invalid_identifier"; None }
            )
          auths = b.field("authorships").map(_.asArr).getOrElse(Nil).flatMap { a =>
            val au   = a.field("author")
            val name = au
              .flatMap(_.field("display_name"))
              .orElse(a.field("raw_author_name"))
              .flatMap(_.asStr)
              .map(DblpParser.clean)
              .getOrElse("")
            if name.isEmpty then None
            else
              val orcid = au
                .flatMap(_.field("orcid"))
                .flatMap(_.asStr)
                .flatMap(o => Orcid.parse(o).toOption.orElse { warnings += "invalid_orcid"; None })
              val aid = au
                .flatMap(_.field("id"))
                .flatMap(_.asStr)
                .flatMap(i => OpenAlexId.normalize(i).toOption)
              val insts = a.field("institutions").map(_.asArr).getOrElse(Nil).flatMap { i =>
                for
                  iid <- i.field("id").flatMap(_.asStr).flatMap(OpenAlexId.normalize(_).toOption)
                  nm  <- i
                    .field("display_name")
                    .flatMap(_.asStr)
                    .map(DblpParser.clean)
                    .filter(_.nonEmpty)
                yield (
                  iid,
                  nm,
                  i.field("country_code").flatMap(_.asStr).filter(_.matches("^[A-Z]{2}$")),
                )
              }
              Some((name, orcid, aid, insts))
          }
          topics = b
            .field("topics")
            .map(_.asArr)
            .getOrElse(Nil)
            .flatMap { t =>
              for
                id <- t.field("id").flatMap(_.asStr).flatMap(OpenAlexId.normalize(_).toOption)
                nm <- t
                  .field("display_name")
                  .flatMap(_.asStr)
                  .map(DblpParser.clean)
                  .filter(_.nonEmpty)
              yield (s"topic:openalex:$id", nm)
            }
            .distinct
          mentions <- mentionRows(workId, auths.map(a => (a._1, a._2)), prov)
          work     <- year match
            case Some(y) =>
              rowOf(
                "works",
                workId,
                WorkRecord(
                  SchemaVersion.Current,
                  workId,
                  title,
                  y,
                  doi,
                  None,
                  topics.map(_._1).sorted,
                  prov,
                ),
              ).map(List(_))
            case None => Right(Nil)
          insts <- sequence(auths.flatMap(_._4).distinctBy(_._1).sortBy(_._1).map { (iid, nm, cc) =>
            val id = s"institution:openalex:$iid"
            rowOf(
              "institutions",
              s"$id|$workId",
              Institution(SchemaVersion.Current, id, nm, cc, prov),
            )
          })
          tops <- sequence(
            topics
              .sortBy(_._1)
              .map((id, nm) =>
                rowOf("topics", s"$id|$workId", Topic(SchemaVersion.Current, id, nm, prov))
              )
          )
        yield
          val own  = Map("openalex" -> oaId) ++ doi.map("doi" -> _)
          val refs = IdRef("openalex", oaId, workId, SourceType.OpenAlex, own) ::
            doi.toList.map(d => IdRef("doi", d, workId, SourceType.OpenAlex, own)) ++
            mentions.zip(auths).flatMap { case ((mm, _), a) =>
              mm.orcid
                .map(o => IdRef("orcid", o, mm.mentionId, SourceType.OpenAlex, Map.empty))
                .toList ++
                a._3
                  .map(x => IdRef("openalex", x, mm.mentionId, SourceType.OpenAlex, Map.empty))
                  .toList
            }
          Normalized(
            SourceType.OpenAlex,
            oaId,
            env.retrievedAt,
            fileName,
            env.sha,
            (work ++ mentions.map(_._2) ++ insts ++ tops ++ refs.map(idRow)).distinctBy(r =>
              (r.table, r.sortKey)
            ),
            false,
            year.isEmpty,
            warnings.toList,
          )
    }

  // ---- ORCID (works section) --------------------------------------------------------------

  def orcid(
      fileName: String,
      bytes: Array[Byte],
      run: RunContext,
  ): Either[ParseFailure, Normalized] =
    envelope(bytes).flatMap { env =>
      if env.missing then Right(upstream(SourceType.Orcid, fileName, env))
      else
        val warnings = ListBuffer.empty[String]
        for
          raw <- env.identifier.toRight(
            ParseFailure(RejectReason.InvalidIdentifier, "identifier is missing")
          )
          id <- Orcid.parse(raw).left.map(e => ParseFailure(RejectReason.InvalidIdentifier, e.code))
          groups <- Right(env.body.field("group").map(_.asArr).getOrElse(Nil))
          _      <-
            if env.body.objOpt.isEmpty then
              fail(RejectReason.MalformedRecord, "body is not an object", Some(id))
            else Right(())
          prov = run.prov(
            SourceType.Orcid,
            Seq("orcid"),
            JobId,
            List(fileInput(fileName, env)),
            Map.empty,
            Some(env.retrievedAt),
          )
          works <- sequence(groups.flatMap { g =>
            g.field("work-summary").flatMap(_.asArr.headOption).flatMap { ws =>
              val title = ws
                .field("title")
                .flatMap(_.field("title"))
                .flatMap(_.field("value"))
                .flatMap(_.asStr)
                .map(plainText)
                .filter(_.nonEmpty)
              val year = ws
                .field("publication-date")
                .flatMap(_.field("year"))
                .flatMap(_.field("value"))
                .flatMap(_.asInt)
                .filter(y => PublicationYear.validate(y).isRight)
              val exts = (g.field("external-ids").toList ++ ws.field("external-ids").toList)
                .flatMap(_.field("external-id"))
                .flatMap(_.asArr)
              val typed = exts.flatMap(e =>
                e.field("external-id-type")
                  .flatMap(_.asStr)
                  .zip(e.field("external-id-value").flatMap(_.asStr))
              )
              val doi = typed.collectFirst {
                case ("doi", v) if Doi.normalize(v).isRight => Doi.normalize(v).toOption.get
              }
              val arx = typed.collectFirst {
                case ("arxiv", v) if ArxivId.normalize(v).isRight =>
                  ArxivId.normalize(v).toOption.get
              }
              (title, year) match
                case (Some(t), Some(y)) =>
                  val wid = doi
                    .map(d => Ids.workIdFromSource("doi", d))
                    .getOrElse(Ids.workIdFallback(t, y, id))
                  Some(
                    wid.left
                      .map(e => ParseFailure(RejectReason.SchemaViolation, e.code))
                      .map(w => (w, t, y, doi, arx))
                  )
                case (Some(_), None) => warnings += "missing_year"; None
                case _               => None
            }
          })
          rows <- sequence(works.distinctBy(_._1).sortBy(_._1).flatMap { case (w, t, y, doi, arx) =>
            val ids = doi.map("doi" -> _).toMap ++ arx.map("arxiv" -> _)
            Right(()) match
              case _ =>
                rowOf(
                  "works",
                  w,
                  WorkRecord(SchemaVersion.Current, w, t, y, doi, None, Nil, prov),
                ) ::
                  (doi.toList.map(d => IdRef("doi", d, w, SourceType.Orcid, ids)) ++
                    arx.toList.map(a => IdRef("arxiv", a, w, SourceType.Orcid, ids)))
                    .map(r => Right(idRow(r)))
          })
        yield
          val own = idRow(IdRef("orcid", id, s"orcid:$id", SourceType.Orcid, Map.empty))
          Normalized(
            SourceType.Orcid,
            id,
            env.retrievedAt,
            fileName,
            env.sha,
            (own :: rows).distinctBy(r => (r.table, r.sortKey)),
            false,
            false,
            warnings.toList,
          )
    }
