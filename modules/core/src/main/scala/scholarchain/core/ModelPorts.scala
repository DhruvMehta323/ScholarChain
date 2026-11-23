package scholarchain.core

import scala.util.Try

/** Phase 09 model-client port. Plain Scala types only: no AWS SDK type, no HTTP type, no exception
  * as control flow (same style as `StoragePorts` and `EmbeddingPorts`). Adapters (Bedrock Converse,
  * Anthropic Messages, the deterministic fake) translate their own failures into `ModelError`.
  *
  * A `ModelError` must NEVER carry a request body, prompt or retrieved text, model output, header,
  * URL with credentials, API key or configuration value: `reason` is a short fixed or
  * adapter-composed description (for example an HTTP status class), nothing more.
  */
enum ModelError:
  /** The request is invalid (blank prompt ID, bad schema, empty content). Not retryable. */
  case InvalidRequest(reason: String)

  /** Rate or capacity rejection. Retryable with backoff. */
  case Throttled(reason: String)

  /** Service or network unavailable or timed out. Retryable with backoff. */
  case Unavailable(reason: String)

  /** The response was not a JSON text. Retryable within the bounded malformed-output retry
    * (`ModelCall.MaxRetries`).
    */
  case MalformedOutput(reason: String)

  /** A context, token or call budget would be exceeded. Not retryable. */
  case BudgetExceeded(reason: String)

  /** Any other failure. Not retryable. */
  case Failed(reason: String)

  def retryable: Boolean = this match
    case _: Throttled | _: Unavailable | _: MalformedOutput => true
    case _                                                  => false

  def code: String = outcome.wire.replace('_', '-')

  def detail: String = this match
    case InvalidRequest(r)  => r
    case Throttled(r)       => r
    case Unavailable(r)     => r
    case MalformedOutput(r) => r
    case BudgetExceeded(r)  => r
    case Failed(r)          => r

  def message: String = s"model error ($code): $detail"

  /** The audit outcome recorded in `ModelCall.outcome`. */
  def outcome: ModelCallOutcome = this match
    case _: InvalidRequest  => ModelCallOutcome.InvalidRequest
    case _: Throttled       => ModelCallOutcome.Throttled
    case _: Unavailable     => ModelCallOutcome.Unavailable
    case _: MalformedOutput => ModelCallOutcome.MalformedOutput
    case _: BudgetExceeded  => ModelCallOutcome.BudgetExceeded
    case _: Failed          => ModelCallOutcome.Failed

type ModelResult[A] = Either[ModelError, A]

/** Whether a content segment is an instruction or retrieved data. Retrieved text (titles,
  * abstracts, summaries, excerpts, evidence) is always `Data`: it is labeled and never part of the
  * instructions.
  */
enum ModelSegmentKind:
  case Instruction
  case Data

/** One labeled content segment. `label` is a token naming the segment (for example
  * `retrieved_evidence`); an adapter must wrap `Data` text in a delimiter that carries the label
  * and tells the model it is untrusted data.
  */
final case class ModelSegment(kind: ModelSegmentKind, label: String, text: String)

/** A structured request. The `jsonSchema` is the JSON Schema text the output must satisfy; the
  * caller validates the response against it (the client only checks that the text is JSON).
  *
  * Rules (`ModelRequest.validate`): `promptId`, `promptVersion`, `schemaId`, `schemaVersion` are
  * tokens of at most 128 characters; `jsonSchema` is a JSON object of at most `MaxJsonSchemaLength`
  * (65,536) characters; `system` is non-empty and contains only `Instruction` segments (retrieved
  * text can never be a system segment); `user` is non-empty; every segment has a token label (at
  * most 64) and non-blank text of at most `MaxSegmentLength` (200,000); `maxOutputTokens` is in
  * 1..`MaxOutputTokensCap` (8,192); at most `MaxSegments` (64) segments per list.
  */
final case class ModelRequest(
    promptId: String,
    promptVersion: String,
    schemaId: String,
    schemaVersion: String,
    jsonSchema: String,
    system: List[ModelSegment],
    user: List[ModelSegment],
    maxOutputTokens: Int,
)

object ModelRequest:
  val MaxJsonSchemaLength: Int = 65536
  val MaxSegmentLength: Int    = 200000
  val MaxSegments: Int         = 64
  val MaxOutputTokensCap: Int  = 8192

  def validate(r: ModelRequest): Either[ValidationError, ModelRequest] =
    def segs(field: String, xs: List[ModelSegment], instructionOnly: Boolean): V.R =
      V.all(
        V.nonEmpty(field, xs),
        RuntimeChecks.listMax(field, xs, MaxSegments),
        V.each(xs) { s =>
          V.all(
            RuntimeChecks.token(s"$field.label", s.label, 64),
            V.nonBlank(s"$field.text", s.text),
            if s.text.length <= MaxSegmentLength then V.ok
            else
              Left(
                ValidationError.InvalidField(
                  s"$field.text",
                  s"must be at most $MaxSegmentLength characters",
                )
              )
            ,
            if instructionOnly && s.kind != ModelSegmentKind.Instruction then
              Left(ValidationError.InvalidField(field, "must contain instruction segments only"))
            else V.ok,
          )
        },
      )
    V.all(
      RuntimeChecks.token("promptId", r.promptId, 128),
      RuntimeChecks.token("promptVersion", r.promptVersion, 128),
      RuntimeChecks.token("schemaId", r.schemaId, 128),
      RuntimeChecks.token("schemaVersion", r.schemaVersion, 128),
      if r.jsonSchema.length <= MaxJsonSchemaLength &&
        Try(ujson.read(r.jsonSchema)).toOption.exists(_.objOpt.isDefined)
      then V.ok
      else
        Left(
          ValidationError.InvalidField(
            "jsonSchema",
            s"must be a JSON object of at most $MaxJsonSchemaLength characters",
          )
        )
      ,
      segs("system", r.system, instructionOnly = true),
      segs("user", r.user, instructionOnly = false),
      if r.maxOutputTokens >= 1 && r.maxOutputTokens <= MaxOutputTokensCap then V.ok
      else
        Left(
          ValidationError.InvalidField(
            "maxOutputTokens",
            s"must be in 1..$MaxOutputTokensCap",
          )
        ),
    ).map(_ => r)

/** Token usage, present only when the provider reported it. */
final case class ModelTokenUsage(inputTokens: Int, outputTokens: Int)

/** A structured response: the raw JSON text (not yet schema-validated), the model that produced it
  * and the token usage when known.
  */
final case class ModelResponse(
    rawJson: String,
    modelId: String,
    usage: Option[ModelTokenUsage],
)

/** Calls a model once and returns structured JSON text. No retry, no schema validation and no
  * budget policy here: those are workflow concerns driven by config. Never throws.
  */
trait ModelClient:
  def provider: ModelProvider
  def modelId: String
  def complete(request: ModelRequest): ModelResult[ModelResponse]
