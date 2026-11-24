package scholarchain.rag.runtime

import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  InferenceConfiguration,
  Message,
  SpecificToolChoice,
  SystemContentBlock,
  Tool,
  ToolChoice,
  ToolConfiguration,
  ToolInputSchema,
  ToolSpecification,
}
import scholarchain.core.*
import scholarchain.rag.{HttpReply, JdkVoyageTransport, SdkBedrockInvoker, VoyageTransport}

/** The name of the single output tool both providers are forced to call. The tool's input schema is
  * the stage's JSON schema, so the model's answer arrives as schema-shaped JSON.
  */
private[runtime] object OutputTool:
  val Name: String        = "emit_result"
  val Description: String =
    "Return the result as JSON that satisfies the input schema. This is the only way to answer."

// ---- Anthropic Messages API (approved fallback) ---------------------------------------------

/** Anthropic Messages API client on the JDK `HttpClient` (through the injected transport). The
  * request forces the single output tool, so the answer is the tool input JSON.
  *
  * KEY HANDLING. The key is read from the environment variable whose NAME is configured
  * (`rag.model.anthropic-api-key-env-var`, default `SCHOLARCHAIN_ANTHROPIC_API_KEY`) through the
  * injected `env` lookup, at call time (never at construction), held in a local value, and sent
  * only as the `x-api-key` header of the request handed to the transport. It is in no `toString`,
  * no `ModelError` reason, no exception message and no audit record. Failures carry a fixed phrase
  * and an HTTP status class only; a reply body is parsed and never echoed.
  *
  * NOT RUN against the live API in Phase 09 (no network call is allowed); it is exercised only
  * through a fake transport.
  */
final class AnthropicModelClient(
    val modelId: String,
    endpoint: String,
    apiKeyEnvVar: String,
    env: String => Option[String] = name => sys.env.get(name),
    transport: VoyageTransport = new JdkVoyageTransport(),
    val apiVersion: String = AnthropicModelClient.ApiVersion,
) extends ModelClient:
  // L2: the only valid key-variable name is SCHOLARCHAIN_ANTHROPIC_API_KEY (an allowlist). Checked
  // at construction, so it holds even if the config check is bypassed. The message does not echo
  // the configured text (a key pasted into the name setting must not leak) and the environment is
  // not read here.
  require(
    AnthropicModelClient.isAllowedKeyVar(apiKeyEnvVar),
    "the configured anthropic key variable name is not allowed; only the SCHOLARCHAIN_ prefixed name for the anthropic key is valid",
  )

  def provider: ModelProvider = ModelProvider.Anthropic

  def complete(request: ModelRequest): ModelResult[ModelResponse] =
    ModelRequest.validate(request) match
      case Left(e)  => Left(ModelError.InvalidRequest(e.code))
      case Right(_) =>
        if !endpoint.startsWith("https://") then
          Left(ModelError.InvalidRequest("endpoint must be https"))
        else
          readKey() match
            case Left(e)    => Left(e)
            case Right(key) =>
              val body    = AnthropicModelClient.requestBody(modelId, request)
              val headers = Map(
                "x-api-key"         -> key,
                "anthropic-version" -> apiVersion,
                "content-type"      -> "application/json",
                "accept"            -> "application/json",
              )
              transport.post(endpoint, headers, body) match
                case Left(kind) => Left(ModelError.Unavailable(s"transport $kind"))
                case Right(rep) => interpret(rep)

  private def trimBlanks(s: String): String =
    val blank = (c: Char) => c == ' ' || c == '\t' || c == '\r' || c == '\n'
    s.dropWhile(blank).reverse.dropWhile(blank).reverse

  private def readKey(): Either[ModelError, String] =
    env(apiKeyEnvVar).map(trimBlanks).filter(_.nonEmpty) match
      case None      => Left(ModelError.Failed(s"environment variable $apiKeyEnvVar is not set"))
      case Some(key) =>
        if key.exists(c => c.isControl || c.isWhitespace) then
          Left(ModelError.Failed(s"environment variable $apiKeyEnvVar is malformed"))
        else Right(key)

  private def interpret(rep: HttpReply): ModelResult[ModelResponse] =
    if rep.status == 429 then Left(ModelError.Throttled("http 429"))
    else if rep.status == 529 || rep.status == 408 || rep.status >= 500 then
      Left(ModelError.Unavailable(s"http ${rep.status / 100}xx"))
    else if rep.status < 200 || rep.status >= 300 then
      Left(ModelError.Failed(s"http ${rep.status / 100}xx"))
    else
      Try(ujson.read(rep.body)).toOption.flatMap(_.objOpt) match
        case None    => Left(ModelError.MalformedOutput("reply is not a JSON object"))
        case Some(o) =>
          val stop = o.value.get("stop_reason").flatMap(_.strOpt)
          val tool = o.value
            .get("content")
            .flatMap(_.arrOpt)
            .flatMap(
              _.find(b =>
                b.objOpt.exists(_.value.get("type").flatMap(_.strOpt).contains("tool_use"))
              )
            )
            .flatMap(_.objOpt)
            .flatMap(_.value.get("input"))
          val usage = o.value.get("usage").flatMap(_.objOpt).flatMap { u =>
            for
              i <- u.value.get("input_tokens").flatMap(_.numOpt)
              t <- u.value.get("output_tokens").flatMap(_.numOpt)
            yield ModelTokenUsage(i.toInt, t.toInt)
          }
          if stop.contains("max_tokens") then Left(ModelError.MalformedOutput("output truncated"))
          else
            tool match
              case Some(input: ujson.Obj) =>
                Right(ModelResponse(ujson.write(input), modelId, usage))
              case _ => Left(ModelError.MalformedOutput("reply has no tool result"))

  override def toString: String =
    s"AnthropicModelClient(modelId=$modelId, apiKey=<redacted>)"

object AnthropicModelClient:
  val ApiVersion: String = "2023-06-01"

  /** The one valid key-variable name. */
  val KeyVarName: String = "SCHOLARCHAIN_ANTHROPIC_API_KEY"

  /** Exact match only: no trimming, no case folding, no other name. */
  def isAllowedKeyVar(name: String): Boolean = name == KeyVarName

  /** The request body: the system text, one user message holding the labeled segments, the output
    * tool and a forced tool choice. Temperature 0.
    */
  def requestBody(modelId: String, r: ModelRequest): String =
    val schema = ujson.read(r.jsonSchema)
    ujson
      .Obj(
        "model"       -> modelId,
        "max_tokens"  -> r.maxOutputTokens,
        "temperature" -> 0,
        "system"      -> ModelWrapping.render(r.system),
        "messages"    -> ujson.Arr(
          ujson.Obj(
            "role"    -> "user",
            "content" -> ujson.Arr(
              ujson.Obj("type" -> "text", "text" -> ModelWrapping.render(r.user))
            ),
          )
        ),
        "tools" -> ujson.Arr(
          ujson.Obj(
            "name"         -> OutputTool.Name,
            "description"  -> OutputTool.Description,
            "input_schema" -> schema,
          )
        ),
        "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> OutputTool.Name),
      )
      .render()

// ---- Bedrock Converse (default) -------------------------------------------------------------

/** What the Bedrock adapter sends, in plain types (no SDK class), so a test can drive the adapter
  * with a fake invoker and never touch the SDK.
  */
final case class ConverseCall(
    modelId: String,
    system: String,
    user: String,
    toolName: String,
    toolDescription: String,
    schemaJson: String,
    maxTokens: Int,
)

final case class ConverseReply(
    toolInputJson: Option[String],
    stopReason: Option[String],
    inputTokens: Option[Int],
    outputTokens: Option[Int],
)

trait ConverseInvoker:
  def converse(call: ConverseCall): Either[ModelError, ConverseReply]

/** The AWS SDK invoker (Bedrock Converse). The `BedrockRuntimeClient` is created lazily, on the
  * first `converse`, and only here. Credentials are injected (no default credential chain) and the
  * client uses the hermetic override configuration of the embedder (an empty profile file), so no
  * shared AWS file is read. Every client construction increments `clientsConstructed`; it stays 0
  * unless a live profile makes a call. Nothing in Phase 09 calls it.
  */
final class SdkConverseInvoker(
    region: String,
    credentials: AwsCredentialsProvider,
    timeoutMillis: Long,
) extends ConverseInvoker:
  private lazy val client: BedrockRuntimeClient =
    SdkConverseInvoker.constructed.incrementAndGet()
    val cfg = SdkBedrockInvoker.hermetic.toBuilder
      .apiCallTimeout(Duration.ofMillis(timeoutMillis))
      .build()
    BedrockRuntimeClient
      .builder()
      .region(Region.of(region))
      .credentialsProvider(credentials)
      .overrideConfiguration(cfg)
      .httpClientBuilder(UrlConnectionHttpClient.builder())
      .build()

  def converse(call: ConverseCall): Either[ModelError, ConverseReply] =
    try
      val schemaDoc = SdkConverseInvoker.toDocument(ujson.read(call.schemaJson))
      val tool      = Tool
        .builder()
        .toolSpec(
          ToolSpecification
            .builder()
            .name(call.toolName)
            .description(call.toolDescription)
            .inputSchema(ToolInputSchema.builder().json(schemaDoc).build())
            .build()
        )
        .build()
      val toolConfig = ToolConfiguration
        .builder()
        .tools(tool)
        .toolChoice(
          ToolChoice
            .builder()
            .tool(SpecificToolChoice.builder().name(call.toolName).build())
            .build()
        )
        .build()
      val req = ConverseRequest
        .builder()
        .modelId(call.modelId)
        .system(SystemContentBlock.builder().text(call.system).build())
        .messages(
          Message
            .builder()
            .role(ConversationRole.USER)
            .content(ContentBlock.fromText(call.user))
            .build()
        )
        .inferenceConfig(
          InferenceConfiguration.builder().maxTokens(call.maxTokens).temperature(0.0f).build()
        )
        .toolConfig(toolConfig)
        .build()
      val resp  = client.converse(req)
      val input = resp
        .output()
        .message()
        .content()
        .asScala
        .toList
        .flatMap(b => Option(b.toolUse()))
        .headOption
        .map(t => ujson.write(SdkConverseInvoker.fromDocument(t.input())))
      Right(
        ConverseReply(
          input,
          Option(resp.stopReasonAsString()),
          Option(resp.usage()).flatMap(u => Option(u.inputTokens()).map(_.intValue)),
          Option(resp.usage()).flatMap(u => Option(u.outputTokens()).map(_.intValue)),
        )
      )
    catch
      case e: software.amazon.awssdk.awscore.exception.AwsServiceException =>
        val code = e.statusCode()
        if code == 429 || e.isThrottlingException then Left(ModelError.Throttled("http 429"))
        else if code == 408 || code >= 500 then
          Left(ModelError.Unavailable(s"http ${code / 100}xx"))
        else Left(ModelError.Failed(s"http ${code / 100}xx"))
      case _: SdkClientException => Left(ModelError.Unavailable("client or network error"))
      case NonFatal(_)           => Left(ModelError.Failed("unexpected error"))

object SdkConverseInvoker:
  private val constructed = new AtomicInteger(0)

  /** How many Converse-capable `BedrockRuntimeClient`s this adapter has built (0 unless live). */
  def clientsConstructed: Int = constructed.get()

  private[runtime] def toDocument(v: ujson.Value): Document = v match
    case ujson.Str(s)  => Document.fromString(s)
    case ujson.Bool(b) => Document.fromBoolean(b)
    case ujson.Null    => Document.fromNull()
    case ujson.Num(d)  =>
      if d.isWhole && d.abs < 9.0e15 then Document.fromNumber(d.toLong) else Document.fromNumber(d)
    case ujson.Arr(xs) => Document.fromList(xs.toList.map(toDocument).asJava)
    case ujson.Obj(m)  =>
      val out = new java.util.LinkedHashMap[String, Document]()
      m.foreach((k, x) => out.put(k, toDocument(x)))
      Document.fromMap(out)

  private[runtime] def fromDocument(d: Document): ujson.Value =
    if d.isNull then ujson.Null
    else if d.isBoolean then ujson.Bool(d.asBoolean())
    else if d.isString then ujson.Str(d.asString())
    else if d.isNumber then ujson.Num(d.asNumber().doubleValue())
    else if d.isList then ujson.Arr.from(d.asList().asScala.toList.map(fromDocument))
    else if d.isMap then
      ujson.Obj.from(d.asMap().asScala.toList.map((k, x) => k -> fromDocument(x)))
    else ujson.Null

/** Bedrock Converse behind the `ModelClient` port (the default provider). The model ID comes from
  * `rag.model.bedrock-model-id`. The single output tool is forced, so the reply is schema-shaped
  * JSON; a reply without a tool result, or one cut off at the token limit, is `MalformedOutput`.
  */
final class BedrockConverseModelClient(invoker: ConverseInvoker, val modelId: String)
    extends ModelClient:
  def provider: ModelProvider = ModelProvider.Bedrock

  def complete(request: ModelRequest): ModelResult[ModelResponse] =
    ModelRequest.validate(request) match
      case Left(e)  => Left(ModelError.InvalidRequest(e.code))
      case Right(_) =>
        invoker
          .converse(
            ConverseCall(
              modelId,
              ModelWrapping.render(request.system),
              ModelWrapping.render(request.user),
              OutputTool.Name,
              OutputTool.Description,
              request.jsonSchema,
              request.maxOutputTokens,
            )
          )
          .flatMap { r =>
            if r.stopReason.contains("max_tokens") then
              Left(ModelError.MalformedOutput("output truncated"))
            else
              r.toolInputJson match
                case None       => Left(ModelError.MalformedOutput("reply has no tool result"))
                case Some(text) =>
                  Right(
                    ModelResponse(
                      text,
                      modelId,
                      for i <- r.inputTokens; o <- r.outputTokens yield ModelTokenUsage(i, o),
                    )
                  )
          }

/** Builds the `ModelClient` the configuration names. AWS and the network are off by default:
  * Bedrock needs an explicit `liveProfile` and injected credentials (otherwise `Left`), the SDK
  * client is created only on the first call, and the Anthropic key is read only when a call is
  * made.
  */
object ModelClientFactory:
  def bedrock(
      s: RuntimeSettings,
      region: String,
      liveProfile: Boolean,
      credentials: AwsCredentialsProvider,
      invokerFor: (String, AwsCredentialsProvider, Long) => ConverseInvoker = (r, c, t) =>
        new SdkConverseInvoker(r, c, t),
  ): Either[ModelError, ModelClient] =
    if !liveProfile then Left(ModelError.Failed("bedrock requires an explicit live profile"))
    else if region.isBlank then Left(ModelError.InvalidRequest("region is blank"))
    else
      Right(
        new BedrockConverseModelClient(
          invokerFor(region, credentials, s.modelTimeoutMillis.toLong),
          s.bedrockModelId,
        )
      )

  def anthropic(
      s: RuntimeSettings,
      env: String => Option[String] = name => sys.env.get(name),
      transport: Option[VoyageTransport] = None,
  ): ModelClient =
    new AnthropicModelClient(
      s.anthropicModelId,
      s.anthropicEndpoint,
      s.anthropicApiKeyEnvVar,
      env,
      transport.getOrElse(
        new JdkVoyageTransport(
          Duration.ofSeconds(10),
          Duration.ofMillis(s.modelTimeoutMillis.toLong),
        )
      ),
    )

  /** Provider by configuration. `fake` must be supplied for the `fake` provider; `bedrockClient`
    * for `bedrock` (built by the caller from `bedrock(...)`, which is `Left` unless live).
    */
  def fromSettings(
      s: RuntimeSettings,
      bedrockClient: => Either[ModelError, ModelClient],
      fake: Option[ModelClient],
      env: String => Option[String] = name => sys.env.get(name),
      transport: Option[VoyageTransport] = None,
  ): Either[ModelError, ModelClient] = s.provider match
    case ModelProvider.Bedrock   => bedrockClient
    case ModelProvider.Anthropic => Right(anthropic(s, env, transport))
    case ModelProvider.Fake      =>
      fake.toRight(ModelError.InvalidRequest("the fake provider needs a fake client"))
