package scholarchain.core

/** Total, typed error model for every contract check in `core`. No contract function throws. */
enum ValidationError:
  case MissingField(field: String)
  case Malformed(detail: String)
  case UnsupportedSchemaVersion(found: Int, supported: Set[Int])
  case InvalidField(field: String, reason: String)
  case MalformedId(kind: String, value: String)
  case InvalidDoi(input: String, reason: String)
  case InvalidOrcid(input: String, reason: String)
  case InvalidConfidence(field: String, value: Double)
  case InvalidYear(year: Int)
  case InvalidWindow(startYear: Int, endYear: Int)
  case InvalidPair(first: String, second: String, reason: String)
  case InvalidConfig(configKey: String, reason: String)

  /** Stable machine-readable name, safe to assert on and to log. */
  def code: String = this match
    case _: MissingField             => "missing-field"
    case _: Malformed                => "malformed"
    case _: UnsupportedSchemaVersion => "unsupported-schema-version"
    case _: InvalidField             => "invalid-field"
    case _: MalformedId              => "malformed-id"
    case _: InvalidDoi               => "invalid-doi"
    case _: InvalidOrcid             => "invalid-orcid"
    case _: InvalidConfidence        => "invalid-confidence"
    case _: InvalidYear              => "invalid-year"
    case _: InvalidWindow            => "invalid-window"
    case _: InvalidPair              => "invalid-pair"
    case _: InvalidConfig            => "invalid-config"

  /** Human-readable text. Never includes configuration values, only keys. */
  def message: String = this match
    case MissingField(f)                => s"missing required field '$f'"
    case Malformed(d)                   => s"malformed input: $d"
    case UnsupportedSchemaVersion(v, s) =>
      s"unsupported schema version $v (supported: ${s.toList.sorted.mkString(",")})"
    case InvalidField(f, r)      => s"invalid field '$f': $r"
    case MalformedId(k, v)       => s"malformed $k id '$v'"
    case InvalidDoi(i, r)        => s"invalid DOI '$i': $r"
    case InvalidOrcid(i, r)      => s"invalid ORCID '$i': $r"
    case InvalidConfidence(f, v) => s"confidence '$f'=$v is outside [0,1]"
    case InvalidYear(y)          => s"year $y is outside the four-digit range"
    case InvalidWindow(s, e)     => s"time window start year $s is greater than end year $e"
    case InvalidPair(a, b, r)    => s"invalid pair ($a, $b): $r"
    case InvalidConfig(k, r)     => s"invalid configuration key '$k': $r"
