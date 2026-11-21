package scholarchain.er

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration

import scala.collection.mutable
import scala.util.Try

import scholarchain.core.*

/** The Anthropic API key. It is read from `SCHOLARCHAIN_ANTHROPIC_API_KEY` only (never
  * `ANTHROPIC_API_KEY`, which would switch Claude Code itself to API billing). `toString` is
  * masked, and the value is revealed in one place only: the request header of `AnthropicClient`.
  */
final class ApiKey private (private val value: String):
  override def toString: String  = "ApiKey(***)"
  private[er] def reveal: String = value

object ApiKey:
  val EnvName = "SCHOLARCHAIN_ANTHROPIC_API_KEY"

  /** The error names the variable, never a value. */
  def fromEnv(env: Map[String, String]): Either[String, ApiKey] =
    env
      .get(EnvName)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(s"environment variable $EnvName is not set")
      .flatMap { v =>
        // an HTTP header value must be visible ASCII; anything else would make request
        // construction throw. The message names the variable only, never the value.
        if v.forall(c => c >= '!' && c <= '~') then Right(new ApiKey(v))
        else Left(s"environment variable $EnvName holds a character that is not visible ASCII")
      }

final case class LlmUsage(inputTokens: Long, outputTokens: Long)
final case class LlmReply(text: String, usage: LlmUsage)

/** A client failure. `kind` is a fixed code; `status` is an HTTP status. No response body, request
  * body or header is ever carried here, so an error can never leak the key.
  */
final case class LlmError(kind: String, status: Option[Int] = None):
  def message: String = status.fold(kind)(s => s"$kind (http $s)")

trait LlmClient:
  def model: String
  def complete(system: String, user: String): Either[LlmError, LlmReply]

/** Test double. `respond` maps (system, user) to the model text or an error. Counts calls. */
final class FakeLlmClient(
    val model: String,
    respond: (String, String) => Either[LlmError, String],
) extends LlmClient:
  private val n  = java.util.concurrent.atomic.AtomicInteger(0)
  def calls: Int = n.get
  def complete(system: String, user: String): Either[LlmError, LlmReply] =
    n.incrementAndGet()
    respond(system, user).map(t =>
      LlmReply(t, LlmUsage(((system.length + user.length) / 4).toLong, (t.length / 4).toLong))
    )

/** Anthropic Messages API over the JDK `HttpClient`. Temperature 0. */
final class AnthropicClient(
    key: ApiKey,
    val model: String,
    maxTokens: Int,
    timeout: Duration,
    endpoint: URI = URI.create("https://api.anthropic.com/v1/messages"),
) extends LlmClient:
  private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

  def complete(system: String, user: String): Either[LlmError, LlmReply] =
    val body = ujson.Obj(
      "model"       -> model,
      "max_tokens"  -> maxTokens,
      "temperature" -> 0,
      "system"      -> system,
      "messages"    -> ujson.Arr(ujson.Obj("role" -> "user", "content" -> user)),
    )
    try
      // built inside the try: a rejected header value is a fixed error code, never a stack trace
      val req = HttpRequest
        .newBuilder(endpoint)
        .timeout(timeout)
        .header("content-type", "application/json")
        .header("anthropic-version", "2023-06-01")
        .header("x-api-key", key.reveal)
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode != 200 then Left(LlmError("http_status", Some(resp.statusCode)))
      else parse(resp.body)
    catch
      case _: java.io.IOException      => Left(LlmError("transport_error"))
      case _: InterruptedException     => Left(LlmError("interrupted"))
      case _: IllegalArgumentException => Left(LlmError("bad_request"))

  private def parse(body: String): Either[LlmError, LlmReply] =
    Try {
      val j    = ujson.read(body)
      val text = j("content").arr
        .filter(b => b.obj.get("type").exists(_.str == "text"))
        .map(_("text").str)
        .mkString
      val u = j("usage")
      LlmReply(text, LlmUsage(u("input_tokens").num.toLong, u("output_tokens").num.toLong))
    }.toEither.left.map(_ => LlmError("bad_response"))

object LlmClients:
  /** The provider named in config. `bedrock` is deliberately not implemented in Phase 05. */
  def create(cfg: LlmConfig, env: Map[String, String]): Either[String, LlmClient] =
    cfg.provider match
      case "anthropic" =>
        ApiKey
          .fromEnv(env)
          .map(k =>
            AnthropicClient(
              k,
              cfg.model,
              cfg.maxOutputTokens,
              Duration.ofSeconds(cfg.timeoutSeconds),
            )
          )
      case "bedrock" => Left("LLM provider 'bedrock' is not implemented in Phase 05")
      case other     => Left(s"unknown LLM provider '$other'")

/** A versioned prompt (resource `er-prompts/<version>.txt`, sections `[[SYSTEM]]`, `[[USER]]`). */
final case class PromptTemplate(version: String, system: String, userTemplate: String):
  def render(
      entityKind: String,
      leftId: String,
      rightId: String,
      left: String,
      right: String,
  ): String =
    val values = Map(
      "ENTITY_KIND"  -> entityKind,
      "LEFT_ID"      -> leftId,
      "RIGHT_ID"     -> rightId,
      "LEFT_RECORD"  -> left,
      "RIGHT_RECORD" -> right,
    )
    // single pass: text substituted in is never scanned again for placeholders
    PromptTemplate.Placeholder.replaceAllIn(
      userTemplate,
      m =>
        values.get(m.group(1)) match
          case Some(v) => scala.util.matching.Regex.quoteReplacement(v)
          case None    => scala.util.matching.Regex.quoteReplacement(m.matched),
    )

object PromptTemplate:
  val V1 = "er-adjudication-v1"

  private[er] val Placeholder = """\{\{([A-Z_]+)\}\}""".r

  def parse(version: String, text: String): Either[String, PromptTemplate] =
    val s = text.indexOf("[[SYSTEM]]")
    val u = text.indexOf("[[USER]]")
    if s < 0 || u < s then Left(s"prompt '$version' lacks [[SYSTEM]] and [[USER]] sections")
    else
      Right(
        PromptTemplate(
          version,
          text.substring(s + "[[SYSTEM]]".length, u).trim,
          text.substring(u + "[[USER]]".length).trim,
        )
      )

  def load(version: String = V1): Either[String, PromptTemplate] =
    Option(getClass.getResourceAsStream(s"/er-prompts/$version.txt"))
      .toRight(s"prompt resource er-prompts/$version.txt not found")
      .flatMap { in =>
        val text = try String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
        parse(version, text)
      }

/** What the model sees. Record text is data: it is rendered as JSON with `<`, `>` and `&` escaped
  * (`<` ...), so it cannot close the `<record>` delimiter or open markup, and the template tells
  * the model to treat it as data.
  */
object AdjudicationInput:
  private val MaxAuthors = 12

  /** The fields of a view shown to the model; only fields that are present. */
  def fields(v: EntityView): ujson.Obj =
    val o = ujson.Obj()
    o("source") = v.source.wire
    v.name.foreach(o("name") = _)
    v.position.foreach(o("position") = _)
    v.title.foreach(o("title") = _)
    v.year.foreach(o("year") = _)
    v.venue.foreach(o("venue") = _)
    val ids = ujson.Obj()
    v.doi.foreach(ids("doi") = _)
    v.arxiv.foreach(ids("arxiv") = _)
    v.openAlexId.foreach(ids("openalex") = _)
    v.orcid.foreach(ids("orcid") = _)
    if ids.value.nonEmpty then o("ids") = ids
    if v.authors.nonEmpty then
      o(if v.kind == EntityKind.Researcher then "coauthors" else "authors") =
        ujson.Arr.from(v.authors.take(MaxAuthors))
    v.affiliation.foreach(o("affiliation") = _)
    o

  def recordJson(v: EntityView): String =
    ujson.write(fields(v)).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")

  /** The citable field names of a pair: `left.<field>` and `right.<field>`. */
  def allowedCitations(l: EntityView, r: EntityView): Set[String] =
    fields(l).value.keys.map("left." + _).toSet ++ fields(r).value.keys.map("right." + _).toSet

  /** SHA-256 hex of the exact model input (kind, ids and both rendered records). */
  def inputHash(c: MatchCandidate, l: EntityView, r: EntityView): String =
    Hashing.digest(
      "scholarchain.er.adjudication-input.v1",
      Seq(c.entityKind.wire, c.leftId, c.rightId, recordJson(l), recordJson(r)),
    )

  def render(t: PromptTemplate, c: MatchCandidate, l: EntityView, r: EntityView): (String, String) =
    (t.system, t.render(c.entityKind.wire, c.leftId, c.rightId, recordJson(l), recordJson(r)))

/** Strict validation of model output. Every failure is a fixed code; the caller turns it into an
  * abstain with reason codes `invalid_output` and the code. A fabricated ID never gets through.
  */
object AdjudicationValidator:
  private val Keys = Set("leftId", "rightId", "outcome", "confidence", "citedFields", "reasonCodes")
  private val Reason = "^[a-z0-9_]{1,64}$".r

  private def unfence(s: String): String =
    val t = s.trim
    if t.startsWith("```") then
      val body = t.dropWhile(_ != '\n').drop(1)
      (if body.trim.endsWith("```") then body.trim.dropRight(3) else body).trim
    else t

  def parse(
      text: String,
      c: MatchCandidate,
      allowedCitations: Set[String],
      modelId: String,
      promptVersion: String,
      policyVersion: String,
      inputHash: String,
      codeVersion: String,
  ): Either[String, AdjudicationRecord] =
    for
      obj <- Try(ujson.read(unfence(text))).toOption
        .flatMap(_.objOpt)
        .toRight("malformed_json")
      _ <- Either.cond(obj.value.keySet == Keys, (), "schema_violation")
      l <- obj.value("leftId").strOpt.toRight("schema_violation")
      r <- obj.value("rightId").strOpt.toRight("schema_violation")
      _ <- Either.cond(l == c.leftId && r == c.rightId, (), "fabricated_id")
      o <- obj.value("outcome").strOpt.toRight("schema_violation").flatMap {
        case "accept"  => Right(AdjudicationOutcome.Accept)
        case "reject"  => Right(AdjudicationOutcome.Reject)
        case "abstain" => Right(AdjudicationOutcome.Abstain)
        case _         => Left("invalid_outcome")
      }
      conf  <- obj.value("confidence").numOpt.toRight("schema_violation")
      _     <- Either.cond(conf >= 0.0 && conf <= 1.0, (), "confidence_out_of_range")
      cited <- strings(obj.value("citedFields"))
      _     <- Either.cond(cited.forall(allowedCitations.contains), (), "invalid_cited_field")
      codes <- strings(obj.value("reasonCodes"))
      _ <- Either.cond(codes.nonEmpty && codes.forall(Reason.matches), (), "invalid_reason_codes")
      rec = AdjudicationRecord(
        SchemaVersion.Current,
        c.leftId,
        c.rightId,
        c.entityKind,
        o,
        conf,
        cited,
        codes,
        promptVersion,
        modelId,
        policyVersion,
        inputHash,
        provenance(c, modelId, promptVersion, policyVersion, inputHash, codeVersion),
      )
      v <- AdjudicationRecord.validateAgainst(rec, c).left.map(_ => "contract_violation")
    yield v

  private def strings(v: ujson.Value): Either[String, List[String]] =
    v.arrOpt.toRight("schema_violation").flatMap { a =>
      val xs = a.toList.map(_.strOpt)
      if xs.forall(_.isDefined) then Right(xs.flatten) else Left("schema_violation")
    }

  def provenance(
      c: MatchCandidate,
      modelId: String,
      promptVersion: String,
      policyVersion: String,
      inputHash: String,
      codeVersion: String,
  ): Provenance =
    Provenance(
      SchemaVersion.Current,
      SourceType.ScholarChain,
      List(
        InputRef(s"er-candidate:${c.leftId}|${c.rightId}", c.featureSetVersion, Some(inputHash))
      ),
      codeVersion,
      Map(
        "model"         -> modelId,
        "promptVersion" -> promptVersion,
        "policyVersion" -> policyVersion,
        "temperature"   -> "0",
      ),
    )

  /** The record used when the model's output is unusable: abstain, confidence 0. */
  def abstain(
      c: MatchCandidate,
      code: String,
      modelId: String,
      promptVersion: String,
      policyVersion: String,
      inputHash: String,
      codeVersion: String,
  ): AdjudicationRecord =
    AdjudicationRecord(
      SchemaVersion.Current,
      c.leftId,
      c.rightId,
      c.entityKind,
      AdjudicationOutcome.Abstain,
      0.0,
      Nil,
      List("invalid_output", code),
      promptVersion,
      modelId,
      policyVersion,
      inputHash,
      provenance(c, modelId, promptVersion, policyVersion, inputHash, codeVersion),
    )

final case class CacheEntry(
    key: String,
    record: AdjudicationRecord,
    inputTokens: Long,
    outputTokens: Long,
)

/** Append-only JSONL cache of VALIDATED adjudications. Key = H(inputHash, promptVersion, modelId,
  * policyVersion). A hit makes no call. Unusable output and transport failures are not cached. The
  * default path is under a `target/` directory, which the repository ignores.
  */
final class AdjudicationCache private (
    path: Path,
    entries: mutable.LinkedHashMap[String, CacheEntry],
):
  def size: Int                            = entries.size
  def get(key: String): Option[CacheEntry] = entries.get(key)
  def all: List[CacheEntry]                = entries.values.toList

  def put(e: CacheEntry): Unit =
    Wire.write(e.record).foreach { json =>
      val line = ujson.write(
        ujson.Obj(
          "key"          -> e.key,
          "inputTokens"  -> ujson.Num(e.inputTokens.toDouble),
          "outputTokens" -> ujson.Num(e.outputTokens.toDouble),
          "record"       -> ujson.read(json),
        )
      )
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(
        path,
        (line + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
      )
      entries.update(e.key, e)
    }

object AdjudicationCache:
  def key(
      inputHash: String,
      promptVersion: String,
      modelId: String,
      policyVersion: String,
  ): String =
    Hashing.digest(
      "scholarchain.er.llm-cache.v1",
      Seq(inputHash, promptVersion, modelId, policyVersion),
    )

  private def valid(r: AdjudicationRecord): Boolean =
    summon[Contract[AdjudicationRecord]].validate(r).isRight

  /** The lookup value that a record's own fields produce. */
  def keyOf(r: AdjudicationRecord): String =
    key(r.inputHash, r.promptVersion, r.modelId, r.policyVersion)

  /** True when the record's own fields are exactly the values that make up a lookup. A cache hit
    * whose record differs (another model, prompt, policy or input) is a miss.
    */
  def recordMatches(
      r: AdjudicationRecord,
      inputHash: String,
      promptVersion: String,
      modelId: String,
      policyVersion: String,
  ): Boolean =
    r.inputHash == inputHash && r.promptVersion == promptVersion && r.modelId == modelId &&
      r.policyVersion == policyVersion

  /** Loads the file when it exists. Lines that do not validate, or whose stored lookup value
    * differs from the one recomputed from the record's own fields, are dropped and counted.
    */
  def open(path: Path): (AdjudicationCache, Int) =
    val m       = mutable.LinkedHashMap.empty[String, CacheEntry]
    var skipped = 0
    if Files.exists(path) then
      Files.readAllLines(path, StandardCharsets.UTF_8).forEach { line =>
        if line.trim.nonEmpty then
          val parsed = Try {
            val j = ujson.read(line)
            Wire
              .read[AdjudicationRecord](ujson.write(j("record")))
              .toOption
              .map(r =>
                CacheEntry(
                  j("key").str,
                  r,
                  j("inputTokens").num.toLong,
                  j("outputTokens").num.toLong,
                )
              )
          }.toOption.flatten
          parsed match
            case Some(e) if valid(e.record) && e.key == keyOf(e.record) => m.update(e.key, e)
            case _                                                      => skipped += 1
      }
    (new AdjudicationCache(path, m), skipped)

/** Result of one adjudication request. */
enum AdjudicationResult:
  /** Served from the cache: no call was made. */
  case Hit(record: AdjudicationRecord)

  /** A live call returned a valid answer (now cached). */
  case Fresh(record: AdjudicationRecord)

  /** Every attempt returned unusable output: an abstain with reason `invalid_output`. Not cached.
    */
  case Invalid(record: AdjudicationRecord, code: String)

  /** No answer: a bound was reached or the transport failed. */
  case Skipped(reason: String)

  def answer: Option[AdjudicationRecord] = this match
    case Hit(r)        => Some(r)
    case Fresh(r)      => Some(r)
    case Invalid(r, _) => Some(r)
    case Skipped(_)    => None

object CostEstimate:
  /** Rough token estimate: four characters per token. */
  def tokens(chars: Int): Long = (chars + 3L) / 4

  def usd(cfg: LlmConfig, inputTokens: Long, outputTokens: Long): Double =
    inputTokens * cfg.priceInputUsdPerMTok / 1e6 + outputTokens * cfg.priceOutputUsdPerMTok / 1e6

/** Runs adjudications under the bounds `max-calls` (live HTTP calls, retries included) and
  * `max-cost-usd` (observed usage). Only the caller decides which candidates are eligible (`Policy`
  * result UNRESOLVED and `llmEligible`).
  */
final class Adjudicator(
    client: LlmClient,
    cache: AdjudicationCache,
    cfg: ErConfig,
    prompt: PromptTemplate,
    codeVersion: String,
    maxCalls: Int,
):
  private var live      = 0
  private var hits      = 0
  private var spent     = 0.0
  private var inTok     = 0L
  private var outTok    = 0L
  private var bad       = 0
  def liveCalls: Int    = live
  def cacheHits: Int    = hits
  def invalidCount: Int = bad
  def costUsd: Double   = spent
  def usage: LlmUsage   = LlmUsage(inTok, outTok)

  def adjudicate(c: MatchCandidate, l: EntityView, r: EntityView): AdjudicationResult =
    val ih  = AdjudicationInput.inputHash(c, l, r)
    val key = AdjudicationCache.key(ih, prompt.version, client.model, cfg.policyVersion)
    // a cached record that is not about THIS candidate (or does not validate) is never served
    cache
      .get(key)
      .filter(x =>
        AdjudicationRecord.validateAgainst(x.record, c).isRight &&
          AdjudicationCache
            .recordMatches(x.record, ih, prompt.version, client.model, cfg.policyVersion)
      ) match
      case Some(e) =>
        hits += 1
        AdjudicationResult.Hit(e.record)
      case None =>
        val (sys, user) = AdjudicationInput.render(prompt, c, l, r)
        val allowed     = AdjudicationInput.allowedCitations(l, r)
        var attempt     = 0
        var last        = Option.empty[String]
        var stop        = Option.empty[String]
        var result      = Option.empty[AdjudicationResult]
        while result.isEmpty && stop.isEmpty && attempt < cfg.llm.maxAttempts do
          if live >= maxCalls then stop = Some("max_calls_reached")
          else if spent >= cfg.llm.maxCostUsd then stop = Some("max_cost_reached")
          else
            attempt += 1
            live += 1
            client.complete(sys, user) match
              case Left(e) =>
                last = Some(e.message); if e.kind == "bad_request" then stop = Some(e.message)
              case Right(reply) =>
                inTok += reply.usage.inputTokens
                outTok += reply.usage.outputTokens
                spent += CostEstimate.usd(
                  cfg.llm,
                  reply.usage.inputTokens,
                  reply.usage.outputTokens,
                )
                AdjudicationValidator.parse(
                  reply.text,
                  c,
                  allowed,
                  client.model,
                  prompt.version,
                  cfg.policyVersion,
                  ih,
                  codeVersion,
                ) match
                  case Right(rec) =>
                    cache.put(
                      CacheEntry(key, rec, reply.usage.inputTokens, reply.usage.outputTokens)
                    )
                    result = Some(AdjudicationResult.Fresh(rec))
                  case Left(code) => last = Some(code); bad += 1
        result.getOrElse {
          stop match
            case Some(why) => AdjudicationResult.Skipped(why)
            case None      =>
              val code = last.getOrElse("no_answer")
              if code.contains("(http") || code.endsWith("_error") || code == "interrupted" then
                AdjudicationResult.Skipped(code)
              else
                AdjudicationResult.Invalid(
                  AdjudicationValidator.abstain(
                    c,
                    code,
                    client.model,
                    prompt.version,
                    cfg.policyVersion,
                    ih,
                    codeVersion,
                  ),
                  code,
                )
        }
