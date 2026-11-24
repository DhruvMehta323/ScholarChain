package scholarchain.rag.runtime

import scala.util.Try

/** A small, strict JSON Schema validator for the model-output schemas in `prompts/schemas/`.
  *
  * Supported keywords (anything else in a schema is ignored, and the repository schemas use only
  * these): `type` (object, array, string, number, integer, boolean, null, or a list of them),
  * `properties`, `required`, `additionalProperties` (boolean), `enum`, `const`, `items`,
  * `minItems`, `maxItems`, `minLength`, `maxLength`, `minimum`, `maximum`, `pattern` (unanchored
  * search, as in JSON Schema), `uniqueItems`, `oneOf`, `anyOf`.
  *
  * `validate` returns the violations as `path: reason` strings. Paths and reasons never contain the
  * offending value, so a violation is safe to log and to audit.
  */
object JsonSchemaValidator:
  private val MaxDepth = 32

  def parse(schemaText: String): Either[String, ujson.Value] =
    Try(ujson.read(schemaText)).toOption match
      case Some(o: ujson.Obj) => Right(o)
      case _                  => Left("schema is not a JSON object")

  def validate(schema: ujson.Value, value: ujson.Value): List[String] =
    check(schema, value, "$", 0)

  def validateText(schemaText: String, valueText: String): List[String] =
    parse(schemaText) match
      case Left(e)       => List(s"$$: $e")
      case Right(schema) =>
        Try(ujson.read(valueText)).toOption match
          case None    => List("$: not JSON")
          case Some(v) => validate(schema, v)

  private def typeName(v: ujson.Value): String = v match
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case ujson.Null    => "null"

  private def isType(t: String, v: ujson.Value): Boolean = t match
    case "integer" =>
      v match
        case ujson.Num(d) => d.isWhole && !d.isInfinite
        case _            => false
    case "number" => v.isInstanceOf[ujson.Num]
    case other    => typeName(v) == other

  private def check(schema: ujson.Value, v: ujson.Value, path: String, depth: Int): List[String] =
    if depth > MaxDepth then List(s"$path: nesting too deep")
    else
      schema match
        case s: ujson.Obj =>
          val m        = s.value
          val typeErrs = m.get("type") match
            case Some(ujson.Str(t))  => if isType(t, v) then Nil else List(s"$path: expected $t")
            case Some(ujson.Arr(ts)) =>
              val names = ts.toList.collect { case ujson.Str(t) => t }
              if names.exists(isType(_, v)) then Nil
              else List(s"$path: expected one of ${names.mkString("|")}")
            case _ => Nil
          if typeErrs.nonEmpty then typeErrs
          else
            enumErrs(m, v, path) ++ constErrs(m, v, path) ++ stringErrs(m, v, path) ++
              numberErrs(m, v, path) ++ objectErrs(m, v, path, depth) ++
              arrayErrs(m, v, path, depth) ++ oneOfErrs(m, v, path, depth)
        case ujson.Bool(false) => List(s"$path: not allowed")
        case _                 => Nil

  private def enumErrs(m: scala.collection.Map[String, ujson.Value], v: ujson.Value, p: String) =
    m.get("enum") match
      case Some(ujson.Arr(vs)) =>
        if vs.exists(_ == v) then Nil else List(s"$p: not an allowed value")
      case _ => Nil

  private def constErrs(m: scala.collection.Map[String, ujson.Value], v: ujson.Value, p: String) =
    m.get("const") match
      case Some(c) => if c == v then Nil else List(s"$p: does not equal the required constant")
      case None    => Nil

  private def stringErrs(m: scala.collection.Map[String, ujson.Value], v: ujson.Value, p: String) =
    v match
      case ujson.Str(s) =>
        val cps = s.codePointCount(0, s.length)
        val min = m.get("minLength").collect { case ujson.Num(n) => n.toInt }
        val max = m.get("maxLength").collect { case ujson.Num(n) => n.toInt }
        val pat = m.get("pattern").collect { case ujson.Str(r) => r }
        min.filter(cps < _).map(_ => s"$p: shorter than minLength").toList ++
          max.filter(cps > _).map(_ => s"$p: longer than maxLength").toList ++
          pat
            .flatMap(r => Try(r.r).toOption.filter(rx => rx.findFirstIn(s).isEmpty))
            .map(_ => s"$p: does not match the pattern")
            .toList
      case _ => Nil

  private def numberErrs(m: scala.collection.Map[String, ujson.Value], v: ujson.Value, p: String) =
    v match
      case ujson.Num(d) =>
        val min = m.get("minimum").collect { case ujson.Num(n) => n }
        val max = m.get("maximum").collect { case ujson.Num(n) => n }
        min.filter(d < _).map(_ => s"$p: below minimum").toList ++
          max.filter(d > _).map(_ => s"$p: above maximum").toList
      case _ => Nil

  private def objectErrs(
      m: scala.collection.Map[String, ujson.Value],
      v: ujson.Value,
      p: String,
      depth: Int,
  ): List[String] =
    v match
      case o: ujson.Obj =>
        val props: Map[String, ujson.Value] =
          m.get("properties").flatMap(_.objOpt).map(_.value.toMap).getOrElse(Map.empty)
        val required = m
          .get("required")
          .collect { case ujson.Arr(rs) =>
            rs.toList.collect { case ujson.Str(r) => r }
          }
          .getOrElse(Nil)
        val missing = required.filterNot(o.value.contains).map(r => s"$p.$r: required")
        val extra   = m.get("additionalProperties") match
          case Some(ujson.Bool(false)) =>
            o.value.keys.toList.filterNot(props.contains).sorted.map(k => s"$p.$k: not allowed")
          case _ => Nil
        val nested = props.toList.sortBy(_._1).flatMap { (k, sub) =>
          o.value.get(k).toList.flatMap(x => check(sub, x, s"$p.$k", depth + 1))
        }
        missing ++ extra ++ nested
      case _ => Nil

  private def arrayErrs(
      m: scala.collection.Map[String, ujson.Value],
      v: ujson.Value,
      p: String,
      depth: Int,
  ): List[String] =
    v match
      case ujson.Arr(items) =>
        val min  = m.get("minItems").collect { case ujson.Num(n) => n.toInt }
        val max  = m.get("maxItems").collect { case ujson.Num(n) => n.toInt }
        val uniq = m.get("uniqueItems") match
          case Some(ujson.Bool(true)) =>
            if items.toList.distinct.size == items.size then Nil
            else List(s"$p: items are not unique")
          case _ => Nil
        val each = m.get("items") match
          case Some(sub) =>
            items.toList.zipWithIndex.flatMap((x, i) => check(sub, x, s"$p[$i]", depth + 1))
          case None => Nil
        min.filter(items.size < _).map(_ => s"$p: fewer than minItems").toList ++
          max.filter(items.size > _).map(_ => s"$p: more than maxItems").toList ++ uniq ++ each
      case _ => Nil

  private def oneOfErrs(
      m: scala.collection.Map[String, ujson.Value],
      v: ujson.Value,
      p: String,
      depth: Int,
  ): List[String] =
    val one = m
      .get("oneOf")
      .collect { case ujson.Arr(subs) =>
        val ok = subs.toList.count(s => check(s, v, p, depth + 1).isEmpty)
        if ok == 1 then Nil else List(s"$p: must match exactly one alternative")
      }
      .getOrElse(Nil)
    val any = m
      .get("anyOf")
      .collect { case ujson.Arr(subs) =>
        if subs.toList.exists(s => check(s, v, p, depth + 1).isEmpty) then Nil
        else List(s"$p: must match at least one alternative")
      }
      .getOrElse(Nil)
    one ++ any
