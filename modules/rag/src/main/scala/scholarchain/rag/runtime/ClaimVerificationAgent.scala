package scholarchain.rag.runtime

import scala.util.matching.Regex

import scholarchain.core.*

/** The deterministic verdict on one proposed claim. */
final case class DeterministicVerdict(
    status: ClaimVerificationStatus,
    reasonCodes: List[String],
    checkedEvidenceIds: List[String],
)

/** One proposed claim with its code-assigned ID, its deterministic verdict and its final verdict
  * (after the optional LLM downgrade).
  */
final case class VerifiedClaim(
    claimId: String,
    claim: ProposedClaim,
    deterministic: DeterministicVerdict,
    verification: ClaimVerification,
)

final case class VerificationResult(
    all: List[VerifiedClaim],
    kept: List[AnswerClaim],
    removed: List[VerifiedClaim],
    llmUsed: Boolean,
    reasonCodes: List[String],
    modelCalls: List[ModelCall],
):
  def verifications: List[ClaimVerification] = all.map(_.verification)

/** Stage 9: claim verification, deterministic first.
  *
  * The deterministic checks (`deterministic`) use ONLY the evidence bundle. A claim is VERIFIED
  * only when all of these hold; the first group decides `unsupported` or `conflicting`, the second
  * caps the claim at `partially_supported`:
  *
  * Unsupported or conflicting:
  *   1. it cites at least one evidence ID and every cited ID exists (`no_evidence_cited`,
  *      `unknown_evidence_id`);
  *   2. every entity reference in the text occurs in a cited deterministic item (its text) or is
  *      the source entity of a cited item; semantic excerpts never supply a reference
  *      (`reference_not_in_cited_evidence`);
  *   3. every window label in the text occurs in a cited fact and equals the window of one of the
  *      claim's assertions or a stored window value they state (`window_not_in_cited_evidence`,
  *      `window_not_bound_to_assertion`);
  *   4. every assertion matches a cited deterministic fact of the same kind, subject and EXACT
  *      window (a window-less assertion matches only a window-less fact): the same value is fine, a
  *      different value is `conflicting`, a missing fact or field is `unsupported`;
  *   5. every number in the text (digits, and number words such as "twelve") is bound to an
  *      assertion: the assertion states exactly that number, its field is the one the nearest
  *      wording names (a field lexicon, "coauthors", "team size", "component", ...) refined by
  *      position ("from 8 to 12 coauthors": previous, then current) and by qualifier ("average" or
  *      "maximum" team size, "previous" coauthors), its window is one the text uses, and its
  *      subject is the owner: a researcher-subject assertion needs that researcher as owner (the
  *      nearest preceding researcher mention, or the only researcher mentioned); any other subject
  *      needs the owner among the researchers it is about (embedded in the subject, or named by an
  *      assertion on it: researcherA/B, from/to, hops, order, coauthorIds); an unowned number in a
  *      text with several researchers needs all of them among those; a joint count needs both
  *      researchers of the pair before the number. A digit run inside an ID, a name, a label or a
  *      hop label ("hop 2") never covers a number (`number_without_matching_assertion`); ranking
  *      values are bound by position;
  *   6. path hop order written with arrows must equal the asserted `hops` (`hop_order_mismatch`,
  *      `conflicting`); an edge ID or work ID stated for a hop ("first hop", "hop 2", or right
  *      after the arrow endpoints) must equal that hop's asserted `edgeIds` entry or one of its
  *      `hopWorkIds` (`hop_evidence_mismatch`, `conflicting`; `hop_evidence_not_asserted`);
  *   7. a claim citing only semantic context (`TextChunk`) can carry no number, assertion or window
  *      and is at best `partially_supported` (`semantic_context_only`); 7a. closed wording:
  *      negation ("not", "no", "never", "neither", "none", "without", n't) must be bound to an
  *      assertion that states it (no path with `found=false`; not a bridge with `classification`),
  *      else `unsupported` (`negation_without_assertion`), or `conflicting` when a true assertion
  *      contradicts it (`negation_contradicts_assertion`); bridge and hub wording is bound to
  *      `classification` (`classification_contradicts_assertion`, `classification_not_asserted`);
  *      ordinal and superlative wording ("first", "top", "highest", "most", "last", "second", ...)
  *      is bound to the order of a ranking or neighborhood assertion, or to a `max*` field the
  *      claim asserts (`rank_wording_contradicts_assertion`, `ordinal_without_assertion`); 7b. a
  *      display name beside a researcher ID that is not that ID's stored name conflicts
  *      (`display_name_mismatch`); a pairwise relation claim (two or more researchers and
  *      "coauthored", "path", "between", ...) with no assertion covering all of them, or with an
  *      uncovered reference, is `unsupported` (`relation_without_assertion`,
  *      `reference_without_assertion`).
  *
  * Capped at partially_supported (only for claims that cite deterministic evidence):
  *   8. every entity mention (an ID, or a stored display name of a cited identity fact) is covered
  *      by an assertion (as its subject or inside its value) (`reference_without_assertion`; on a
  *      pairwise relation claim this is `unsupported`, rule 7b);
  *   9. a capitalized word that is not a stored name of a cited identity and does not start a
  *      sentence is an unverified proper noun (`unverified_proper_noun`);
  *   10. entities must appear in the order of every asserted ordered list
  *       (`reference_order_mismatch`);
  *   11. a relation between two or more researchers ("coauthored", "path", "between", ...) needs a
  *       coauthor-edge, neighborhood, path or ranking assertion that covers all of them; relation
  *       wording (bridge, community, component, path, coauthor, publication, team, identity) needs
  *       an assertion of a kind that can state it (`relation_without_assertion`);
  *   12. the text must be anchored by at least one covered reference or bound number
  *       (`text_not_anchored_to_assertion`); a claim with no assertion is capped
  *       (`reference_without_assertion`, or `qualitative_wording_unchecked` with no entity at all).
  *
  * Free model wording is therefore verified only where it is structurally tied to assertions; the
  * template synthesizer's wording satisfies all rules by construction. The optional LLM check
  * (`llmCheck`, prompt `claim-verification-v1`) sees only claims that passed the deterministic
  * checks and judges wording. Its verdict can only DOWNGRADE: the final status is the more severe
  * of the two (verified < partially_supported < unsupported < conflicting), a deterministic
  * `unsupported` or `conflicting` is never sent to it and never changed, and a missing or failed
  * LLM verdict leaves the deterministic status. Unsupported and conflicting claims are removed from
  * the answer; they stay in the audit record.
  *
  * Known limits (also in `RUNTIME-AGENTS.md`): the field lexicon and the relation families are
  * English wording lists, not understanding; the negation, ordinal and qualifier lists are closed
  * and finite (a negation or superlative written in a way the lists miss is not seen); relations
  * phrased without any listed word and without a reference or number are caught only by the
  * optional LLM check; a metric stated for the wrong window is caught only through the window
  * rules.
  */
final class ClaimVerificationAgent(
    caller: Option[StructuredModelCaller],
    prompts: PromptRepository,
    llmCheck: Boolean,
):
  import ClaimVerificationAgent.*

  def verify(queryId: String, set: EvidenceSet, proposed: List[ProposedClaim]): VerificationResult =
    val det = proposed.zipWithIndex.map { (c, i) =>
      (claimIdOf(queryId, i, c), c, deterministic(set, c))
    }
    val (llm, calls, codes) =
      if llmCheck && caller.isDefined then llmVerdicts(queryId, set, det)
      else (Map.empty[String, (ClaimVerificationStatus, List[String])], Nil, Nil)
    val verified = det.map { (id, c, d) =>
      val (status, reasons) = llm.get(id) match
        case Some((ls, lr))
            if d.status == ClaimVerificationStatus.Verified || d.status == ClaimVerificationStatus.PartiallySupported =>
          val s = moreSevere(d.status, ls)
          (
            s,
            (d.reasonCodes ++ (if s != d.status then lr.map(r => ("llm_" + r).take(64))
                               else Nil) ++ (if s != d.status then List("llm_downgrade")
                                             else Nil)).distinct,
          )
        case _ => (d.status, d.reasonCodes)
      val reasons2 = if reasons.isEmpty && status != ClaimVerificationStatus.Verified then
        List("no_reason_recorded")
      else reasons
      val v = ClaimVerification(
        SchemaVersion.Current,
        id,
        status,
        d.checkedEvidenceIds,
        reasons2,
        Provenance(
          SchemaVersion.Current,
          SourceType.ScholarChain,
          List(
            InputRef(
              "evidence-bundle",
              queryId,
              Some(Hashing.textHash(set.bundle.items.map(_.evidenceId).mkString(","))),
            )
          ),
          RuntimeCode.Version,
          Map("llmCheck" -> (llmCheck && caller.isDefined).toString),
        ),
      )
      // A verified claim needs a checked evidence ID: an empty list cannot be verified.
      val safe =
        if status == ClaimVerificationStatus.Verified && d.checkedEvidenceIds.isEmpty then
          v.copy(
            status = ClaimVerificationStatus.Unsupported,
            reasonCodes = List("no_evidence_cited"),
          )
        else v
      VerifiedClaim(id, c, d, safe)
    }
    val kept = verified.filter(v => keep(v.verification.status)).flatMap(v => answerClaim(set, v))
    VerificationResult(
      verified,
      kept,
      verified.filterNot(v => keep(v.verification.status)),
      llmCheck && caller.isDefined,
      codes,
      calls,
    )

  private def keep(s: ClaimVerificationStatus): Boolean =
    s == ClaimVerificationStatus.Verified || s == ClaimVerificationStatus.PartiallySupported

  private def answerClaim(set: EvidenceSet, v: VerifiedClaim): Option[AnswerClaim] =
    val items = v.claim.evidenceIds.distinct.flatMap(set.itemsById.get)
    val c     = AnswerClaim(
      SchemaVersion.Current,
      v.claimId,
      v.claim.text,
      v.claim.evidenceIds.distinct,
      toV1(v.verification.status),
      if v.verification.status == ClaimVerificationStatus.Verified then 1.0 else 0.5,
      Provenance(
        SchemaVersion.Current,
        SourceType.ScholarChain,
        if items.nonEmpty then
          items.map(i => InputRef(i.evidenceId, "evidence", Some(i.contentHash)))
        else List(InputRef("evidence-bundle", "none", None)),
        RuntimeCode.Version,
        Map(
          "verification" -> v.verification.status.wire,
          "reasons"      -> v.verification.reasonCodes.mkString(","),
        ),
      ),
    )
    summon[Contract[AnswerClaim]].validate(c).toOption

  // ---- deterministic checks ------------------------------------------------------------------

  def deterministic(set: EvidenceSet, claim: ProposedClaim): DeterministicVerdict =
    val cited                                                 = claim.evidenceIds.distinct
    val byId                                                  = set.itemsById
    val existing                                              = cited.filter(byId.contains)
    def done(s: ClaimVerificationStatus, codes: List[String]) =
      DeterministicVerdict(s, codes.distinct, existing)
    if cited.isEmpty then done(ClaimVerificationStatus.Unsupported, List("no_evidence_cited"))
    else if existing.size != cited.size then
      done(ClaimVerificationStatus.Unsupported, List("unknown_evidence_id"))
    else
      val items     = existing.flatMap(byId.get)
      val detItems  = items.filter(i => set.graphEvidenceIds.contains(i.evidenceId))
      val facts     = detItems.flatMap(i => i.text.flatMap(Fact.parse))
      val detTexts  = detItems.flatMap(_.text)
      val asserts   = claim.assertions
      val conflict  = scala.collection.mutable.ListBuffer.empty[String]
      val missing   = scala.collection.mutable.ListBuffer.empty[String]
      val partial   = scala.collection.mutable.ListBuffer.empty[String]
      var pairClaim = false

      // display names of the cited identity facts (a name is only used when it is unique)
      val namePairs = facts
        .filter(_.kind == Fact.Identity)
        .flatMap(f =>
          f.fields.value
            .get("displayName")
            .flatMap(_.strOpt)
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(
              _ -> f.subject
            )
        )
      val nameToId = namePairs.groupBy(_._1).collect {
        case (n, ps) if ps.map(_._2).distinct.size == 1 => n -> ps.head._2
      }
      val idToName = namePairs.map((n, id) => id -> n).toMap
      val comp     = compact(claim.text, nameToId)
      val textRefs = comp.ents.filterNot(_.isName).map(_.id).distinct
      val mentions = comp.ents.map(_.id).distinct
      val windows  = comp.windows.distinct
      val numToks  = numberTokens(comp.text).filterNot(t => hopLabel(comp.text, t))

      // references must occur, as whole tokens, in a cited deterministic item (its text or source
      // entity); the source entity of any cited item counts (metadata, not an excerpt)
      val refPool = expandRefs(
        (detItems.flatMap(i => refsIn(i.text.getOrElse(""))) ++ items.map(_.sourceEntityId) ++
          cited).toSet
      )
      if textRefs.exists(r => !refPool.contains(r)) then
        missing += "reference_not_in_cited_evidence"
      // windows must occur in cited facts
      val winPool = facts.flatMap(_.window).toSet ++ detTexts.flatMap(t =>
        WindowRx.findAllIn(stripRefs(t)).toList
      )
      if windows.exists(w => !winPool.contains(w)) then missing += "window_not_in_cited_evidence"

      // assertions: same kind, subject and exact window as a cited deterministic fact
      asserts.foreach { a =>
        val matching =
          facts.filter(f => f.kind == a.kind && f.subject == a.subject && f.window == a.window)
        if matching.isEmpty then
          missing += (if detItems.isEmpty then "semantic_evidence_cannot_support_assertion"
                      else "assertion_without_evidence")
        else
          val stored = matching.flatMap(_.fields.value.get(a.field))
          if stored.isEmpty then missing += "assertion_field_absent"
          else if stored.exists(s => sameValue(s, a.value)) then ()
          else conflict += "assertion_value_mismatch"
      }

      // windows in the text must equal the window of an assertion (or a stored window value)
      val assertWindows = asserts.flatMap(_.window).toSet ++ asserts.flatMap(a =>
        stringsIn(a.value).filter(s => WindowRx.matches(s))
      )
      if windows.exists(w => !assertWindows.contains(w)) then
        missing += "window_not_bound_to_assertion"

      // numbers (digits and number words) must be bound to an assertion: same field, subject and
      // window; no digit run inside an identifier or a label counts
      val lex       = lexiconHits(comp.text)
      val bound     = scala.collection.mutable.ListBuffer.empty[Int]
      val uncovered = numToks.filterNot { t =>
        val ok = numberBound(t, comp, lex, asserts, windows)
        if ok then bound += t.start
        ok
      }
      if uncovered.nonEmpty then
        if detItems.isEmpty then missing += "semantic_evidence_cannot_support_number"
        else missing += "number_without_matching_assertion"
      if detItems.isEmpty && windows.nonEmpty then
        missing += "semantic_evidence_cannot_support_window"

      // closed wording: negation, enumerated and boolean values, ordinal and superlative wording,
      // and the edge or work a hop is said to have (only for claims that cite deterministic items)
      if detItems.nonEmpty then
        val (wc, wm) = wordingChecks(comp, asserts, lex)
        conflict ++= wc
        missing ++= wm
        val (hc, hm) = hopChecks(comp, asserts)
        conflict ++= hc
        missing ++= hm

      // path hop order stated with arrows must equal the asserted hops
      val hopArrays = asserts.filter(_.field == "hops").map(a => strArray(a.value))
      val chain     = arrowChain(comp)
      if chain.size >= 2 && hopArrays.nonEmpty && !hopArrays.contains(chain) then
        conflict += "hop_order_mismatch"

      if detItems.nonEmpty then
        val covered = expandRefs(asserts.flatMap(a => a.subject :: stringsIn(a.value)).toSet)
        // every entity reference (an ID, or a stored display name) is covered by an assertion
        val entityMentions = mentions.filterNot(_.startsWith("evidence:"))
        if entityMentions.exists(m => !covered.contains(m)) then
          partial += "reference_without_assertion"
        // display names must match the stored identity of the ID they stand beside
        if nameMismatch(comp, idToName) then conflict += "display_name_mismatch"
        // names that no cited identity fact states are not verified
        if unknownProperNoun(comp.text) then partial += "unverified_proper_noun"
        // the order in which entities appear must follow an asserted ordered list
        if orderMismatch(comp, asserts) then partial += "reference_order_mismatch"
        // a relation between several researchers needs an assertion over all of them
        val researchers = comp.ents.map(_.id).filter(_.startsWith("researcher:")).distinct
        pairClaim = researchers.size >= 2 && PairRx.findFirstIn(comp.text).isDefined
        if pairClaim then
          val pairCover = expandRefs(
            asserts
              .filter(a => PairKinds.contains(a.kind))
              .flatMap(a => a.subject :: stringsIn(a.value))
              .toSet
          )
          if researchers.exists(r => !pairCover.contains(r)) then
            partial += "relation_without_assertion"
        // relation wording needs an assertion of a kind that can state it
        if Families.exists((rx, kinds) =>
            rx.findFirstIn(comp.text).isDefined && !asserts.exists(a => kinds.contains(a.kind))
          )
        then partial += "relation_without_assertion"
        // the text must be anchored to an assertion by a covered reference or a bound number
        val anchored = bound.nonEmpty || entityMentions.exists(covered.contains)
        if asserts.nonEmpty && !anchored then partial += "text_not_anchored_to_assertion"
        // a pairwise relation claim without an assertion that states it is not reported
        if pairClaim then
          val unsup = partial.filter(PairUnsupported.contains).toList
          if unsup.nonEmpty then
            missing ++= unsup
            partial --= unsup
          if asserts.isEmpty then missing += "reference_without_assertion"

      if conflict.nonEmpty then
        done(ClaimVerificationStatus.Conflicting, (conflict ++ missing).toList)
      else if missing.nonEmpty then done(ClaimVerificationStatus.Unsupported, missing.toList)
      else if detItems.isEmpty then
        done(ClaimVerificationStatus.PartiallySupported, List("semantic_context_only"))
      else if asserts.isEmpty then
        done(
          ClaimVerificationStatus.PartiallySupported,
          if mentions.nonEmpty then List("reference_without_assertion")
          else List("qualitative_wording_unchecked"),
        )
      else if partial.nonEmpty then done(ClaimVerificationStatus.PartiallySupported, partial.toList)
      else done(ClaimVerificationStatus.Verified, List("deterministic_checks_passed"))

  // ---- optional LLM check (downgrade only) ---------------------------------------------------

  private def llmVerdicts(
      queryId: String,
      set: EvidenceSet,
      det: List[(String, ProposedClaim, DeterministicVerdict)],
  ): (Map[String, (ClaimVerificationStatus, List[String])], List[ModelCall], List[String]) =
    val eligible = det
      .filter((_, _, d) =>
        d.status == ClaimVerificationStatus.Verified || d.status == ClaimVerificationStatus.PartiallySupported
      )
      .take(20)
    if eligible.isEmpty then (Map.empty, Nil, Nil)
    else
      prompts.load(PromptRepository.VerificationId, PromptRepository.Version) match
        case Left(_)     => (Map.empty, Nil, List("llm_check_prompt_unavailable"))
        case Right(spec) =>
          val claimsJson = ujson.write(
            ujson.Arr.from(eligible.map((id, c, _) => ujson.Obj("claimId" -> id, "text" -> c.text)))
          )
          val ids    = eligible.flatMap((_, c, _) => c.evidenceIds).distinct
          val evJson = ujson.write(
            ujson.Arr.from(ids.flatMap(set.itemsById.get).map(AnswerSynthesisAgent.itemJson))
          )
          val user = List(
            ModelSegment(
              ModelSegmentKind.Instruction,
              "task",
              "Judge the wording of each claim against its cited evidence.",
            ),
            ModelSegment(ModelSegmentKind.Data, "claims_to_check", claimsJson),
            ModelSegment(ModelSegmentKind.Data, "cited_evidence", evJson),
          )
          caller.get.call(queryId, ModelStage.ClaimVerification, spec, user, 1024) match
            case Left(f) =>
              (Map.empty, List(f.call), List(s"llm_check_failed_${f.error.code.replace('-', '_')}"))
            case Right(s) =>
              val verdicts = s.json.objOpt
                .flatMap(_.value.get("verdicts"))
                .flatMap(_.arrOpt)
                .map(_.toList)
                .getOrElse(Nil)
                .flatMap { v =>
                  for
                    o      <- v.objOpt
                    id     <- o.value.get("claimId").flatMap(_.strOpt)
                    status <- o.value
                      .get("status")
                      .flatMap(_.strOpt)
                      .flatMap(w => ClaimVerificationStatus.values.find(_.wire == w))
                    rs = o.value
                      .get("reasonCodes")
                      .flatMap(_.arrOpt)
                      .map(_.toList.flatMap(_.strOpt))
                      .getOrElse(Nil)
                  yield id -> (status, rs)
                }
              (verdicts.toMap, List(s.call), Nil)

object ClaimVerificationAgent:
  private val WindowRx = """\b\d{4}-\d{4}\b""".r

  /** Kinds of entity reference that a claim must be able to trace to a cited item. */
  private val RefKinds =
    "researcher|work|community|component|edge|path|ranking|window|topic|venue|institution|evidence"
  private val RefRx: Regex = s"""\\b(?:$RefKinds):[^\\s,;()\\[\\]"'<>]+""".r
  private val NumRx: Regex = """\d+(?:\.\d+)?""".r

  // ---- text analysis helpers (private) ---------------------------------------------------------

  private val RefMark: Char  = '\u0001'
  private val WinMark: Char  = '\u0002'
  private val NameMark: Char = '\u0003'

  /** One entity mention in the compact text: a reference ID, or a stored display name resolved to
    * its researcher ID. `pos` is the index of its placeholder character.
    */
  private final case class Ent(pos: Int, id: String, isName: Boolean)

  /** The claim text with every entity reference, stored display name and window label replaced by
    * one placeholder character, so that distances between words are not inflated by long IDs.
    */
  private final case class Compact(text: String, ents: Vector[Ent], windows: List[String])

  private final case class NumTok(value: BigDecimal, start: Int, end: Int)

  private def compact(text: String, names: Map[String, String]): Compact =
    val nameAlt = names.keys.toList
      .sortBy(n => -n.length)
      .map(n => "(?<![\\p{L}\\p{N}])" + Regex.quote(n) + "(?![\\p{L}\\p{N}])")
    val parts = List("(" + RefRx.regex + ")", "(" + WindowRx.regex + ")") ++
      Option.when(nameAlt.nonEmpty)("(" + nameAlt.mkString("|") + ")")
    val pat  = new Regex(parts.mkString("|"))
    val sb   = new java.lang.StringBuilder
    val ents = Vector.newBuilder[Ent]
    val wins = scala.collection.mutable.ListBuffer.empty[String]
    var last = 0
    pat.findAllMatchIn(text).foreach { m =>
      sb.append(text, last, m.start)
      if m.group(1) != null then
        val raw   = m.group(1)
        val clean = raw.replaceAll("[.:]+$", "")
        ents += Ent(sb.length, clean, false)
        sb.append(RefMark)
        sb.append(raw.substring(clean.length))
      else if m.group(2) != null then
        wins += m.group(2)
        sb.append(WinMark)
      else
        ents += Ent(sb.length, names(m.group(3)), true)
        sb.append(NameMark)
      last = m.end
    }
    sb.append(text, last, text.length)
    Compact(sb.toString, ents.result(), wins.toList)

  private val NumberWords: Map[String, BigDecimal] = Map(
    "zero"      -> 0,
    "one"       -> 1,
    "two"       -> 2,
    "three"     -> 3,
    "four"      -> 4,
    "five"      -> 5,
    "six"       -> 6,
    "seven"     -> 7,
    "eight"     -> 8,
    "nine"      -> 9,
    "ten"       -> 10,
    "eleven"    -> 11,
    "twelve"    -> 12,
    "thirteen"  -> 13,
    "fourteen"  -> 14,
    "fifteen"   -> 15,
    "sixteen"   -> 16,
    "seventeen" -> 17,
    "eighteen"  -> 18,
    "nineteen"  -> 19,
    "twenty"    -> 20,
    "thirty"    -> 30,
    "forty"     -> 40,
    "fifty"     -> 50,
    "sixty"     -> 60,
    "seventy"   -> 70,
    "eighty"    -> 80,
    "ninety"    -> 90,
    "hundred"   -> 100,
    "thousand"  -> 1000,
    "million"   -> 1000000,
    "dozen"     -> 12,
  ).view.mapValues(v => BigDecimal(v)).toMap

  private val NumTokRx: Regex  = """\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?""".r
  private val NumWordRx: Regex =
    ("(?i)\\b(" + NumberWords.keys.mkString("|") + ")\\b").r

  /** Numbers in the compact text: digits (thousands separators allowed) and number words. */
  private def numberTokens(t: String): List[NumTok] =
    NumTokRx.findAllMatchIn(t).toList.flatMap { m =>
      scala.util.Try(BigDecimal(m.matched.replace(",", ""))).toOption.map(NumTok(_, m.start, m.end))
    } ++ NumWordRx
      .findAllMatchIn(t)
      .toList
      .map(m => NumTok(NumberWords(m.group(1).toLowerCase), m.start, m.end))

  private enum Dir:
    case Before, After

  /** A wording that names a stored field: `Before` phrases precede their number ("component 4"),
    * `After` phrases follow it ("12 coauthors").
    */
  private final case class Phrase(rx: Regex, dir: Dir, fields: Set[String])
  private final case class Hit(start: Int, end: Int, phrase: Phrase)

  private val MaxGap: Int = 30

  private def ph(rx: String, dir: Dir, fields: String*): Phrase =
    Phrase(new Regex("(?i)" + rx), dir, fields.toSet)

  private val Lexicon: List[Phrase] = List(
    ph("(?:average|mean) team size", Dir.Before, "avgTeamSize"),
    ph("(?:maximum|largest|max) team(?: size)?", Dir.Before, "maxTeamSize"),
    ph("weighted degree", Dir.Before, "weightedDegree"),
    ph("\\bcomponent\\b", Dir.Before, "componentId"),
    ph("\\bcommunity\\b", Dir.Before, "communityId"),
    ph("(?<!team )\\bsize\\b", Dir.Before, "size"),
    ph("\\blength\\b", Dir.Before, "length"),
    ph("\\bconfidence\\b", Dir.Before, "confidence"),
    ph("cross share", Dir.Before, "crossShare"),
    ph("\\bwork\\b", Dir.Before, "maxTeamWorkId"),
    ph("co-?authors?\\b|collaborators?\\b", Dir.After, "coauthorCount"),
    ph("\\bnew\\b", Dir.After, "newCoauthors"),
    ph("\\blost\\b", Dir.After, "lostCoauthors"),
    ph("\\bretained\\b", Dir.After, "retainedCoauthors"),
    ph("publications?\\b|papers?\\b", Dir.After, "publicationCount", "jointPublicationCount"),
    ph(
      "\\bworks\\b",
      Dir.After,
      "workCount",
      "jointPublicationCount",
      "publicationCount",
    ),
    ph("researchers?\\b|nodes?\\b", Dir.After, "nodeCount"),
    ph("members?\\b", Dir.After, "maxTeamSize", "avgTeamSize"),
    ph("intensity", Dir.After, "weightedDegree"),
    ph("cross-community edges?", Dir.After, "crossEdgeCount"),
    ph("\\boutside\\b", Dir.After, "crossCommunityDegree"),
    ph("\\bhops?\\b", Dir.After, "maxHops", "length"),
  )

  private def lexiconHits(t: String): List[Hit] =
    Lexicon.flatMap(p => p.rx.findAllMatchIn(t).map(m => Hit(m.start, m.end, p)))

  /** The fields named by the wording nearest to a number (within `MaxGap` characters). */
  private def nearFields(hits: List[Hit], t: NumTok): Set[String] =
    val cands = hits
      .flatMap { h =>
        h.phrase.dir match
          case Dir.Before if h.end <= t.start => Some((t.start - h.end) -> h)
          case Dir.After if h.start >= t.end  => Some((h.start - t.end) -> h)
          case _                              => None
      }
      .filter(_._1 <= MaxGap)
    if cands.isEmpty then Set.empty
    else
      val min = cands.map(_._1).min
      cands.filter(_._1 == min).flatMap(_._2.phrase.fields).toSet

  private def strArray(v: ujson.Value): List[String] =
    v.arrOpt.map(_.toList.flatMap(_.strOpt)).getOrElse(Nil)

  /** The researcher the text is talking about at `start`: the nearest researcher mention before. */
  private def ownerOf(comp: Compact, start: Int): Option[String] =
    comp.ents.filter(e => e.pos < start && e.id.startsWith("researcher:")).lastOption.map(_.id)

  private def positional(
      a: ProposedAssertion,
      t: NumTok,
      comp: Compact,
      asserts: List[ProposedAssertion],
  ): Boolean =
    a.kind == Fact.Ranking && a.field == "values" && (for
      order <- asserts
        .find(x =>
          x.kind == Fact.Ranking && x.field == "order" && x.subject == a.subject && x.window == a.window
        )
        .map(x => strArray(x.value))
      o <- ownerOf(comp, t.start)
      i = order.indexOf(o)
      if i >= 0
      v <- a.value.arrOpt.flatMap(_.toList.lift(i)).flatMap(Jnum.value)
    yield Jnum.sameNumber(v, t.value)).getOrElse(false)

  private val NumWordAlt: String = NumberWords.keys.mkString("|")
  private val FromRx: Regex      = """(?i)\bfrom\s+$""".r
  private val FromToRx: Regex    =
    ("(?i)\\bfrom\\s+(?:\\d[\\d,.]*|(?:" + NumWordAlt + "))\\s+to\\s+$").r
  private val ToNumRx: Regex    = ("(?i)^\\s+to\\s+(?:\\d|(?:" + NumWordAlt + ")\\b)").r
  private val AvgQ: Regex       = """(?i)\b(?:average|mean|typical)\b""".r
  private val MaxQ: Regex       = """(?i)\b(?:maximum|largest|max|biggest|greatest|most)\b""".r
  private val PrevBefore: Regex = """(?i)\b(?:previous|prior|earlier|before)\b""".r
  private val PrevAfter: Regex  =
    """(?i)^[^.;:]{0,25}\b(?:previous|prior|earlier|previously)\b""".r

  private val Boundary: String = ".;:!?"

  private def clauseStart(t: String, pos: Int): Int =
    var i = math.min(pos, t.length) - 1
    while i >= 0 && !Boundary.contains(t(i)) do i -= 1
    i + 1

  private val SentEndRx: Regex = """[.!?](?=\s|$)""".r

  /** Start and end of the sentence holding `pos`. */
  private def sentenceBounds(t: String, pos: Int): (Int, Int) =
    val ends = SentEndRx.findAllMatchIn(t).map(_.end).toList
    (ends.filter(_ <= pos).lastOption.getOrElse(0), ends.find(_ > pos).getOrElse(t.length))

  private def embedded(s: String): Set[String] = ResearcherRx.findAllIn(s).toSet

  /** The researchers an assertion is about: those embedded in its subject, and those named by any
    * assertion of the claim on the same kind and subject (researcherA/B, from/to, hops, order,
    * coauthorIds).
    */
  private def membersOf(a: ProposedAssertion, asserts: List[ProposedAssertion]): Set[String] =
    embedded(a.subject) ++ asserts
      .filter(x => x.kind == a.kind && x.subject == a.subject)
      .flatMap(x => stringsIn(x.value))
      .flatMap(embedded)

  /** The fields a number may be bound to, after the wording nearest to it (`near`) is refined by
    * position ("from 8 to 12 coauthors": previous then current) and by qualifier ("average" and
    * "maximum" team size, "previous" coauthors).
    */
  private def refineFields(near: Set[String], t: NumTok, comp: Compact): Set[String] =
    val text   = comp.text
    val before = text.substring(0, t.start)
    val after  = text.substring(t.end)
    val clause = before.substring(clauseStart(before, before.length))
    var out    = near
    if out.contains("coauthorCount") then
      val prevRole = FromRx.findFirstIn(before).isDefined && ToNumRx.findFirstIn(after).isDefined
      val curRole  = FromToRx.findFirstIn(before).isDefined
      val prevQ    = PrevBefore.findFirstIn(clause.takeRight(40)).isDefined ||
        PrevAfter.findFirstIn(after).isDefined
      out = out - "coauthorCount"
      if prevRole || (prevQ && !curRole) then out = out + "prevCoauthorCount"
      else out = out + "coauthorCount"
    if out.contains("avgTeamSize") || out.contains("maxTeamSize") then
      val tail = clause.takeRight(60)
      val avgI = AvgQ.findAllMatchIn(tail).map(_.start).toList.lastOption.getOrElse(-1)
      val maxI = MaxQ.findAllMatchIn(tail).map(_.start).toList.lastOption.getOrElse(-1)
      out = out - "avgTeamSize" - "maxTeamSize"
      if avgI >= 0 && avgI > maxI then out = out + "avgTeamSize"
      else if maxI >= 0 then out = out + "maxTeamSize"
    out

  /** A text number is bound when some assertion of the claim states exactly that number for the
    * field the nearby wording names, about the researcher the text is talking about, in a window
    * the text uses. A researcher-subject assertion needs that researcher as owner (the nearest
    * preceding researcher mention, or the only researcher mentioned). Another subject needs the
    * owner among the researchers it is about; a number with no owner and several researchers in the
    * text needs all of them among those; a joint count needs both researchers of the pair before
    * the number.
    */
  private def numberBound(
      t: NumTok,
      comp: Compact,
      hits: List[Hit],
      asserts: List[ProposedAssertion],
      windows: List[String],
  ): Boolean =
    val researchers = comp.ents.map(_.id).filter(_.startsWith("researcher:")).distinct
    val owner       = ownerOf(comp, t.start).orElse(
      if researchers.size == 1 then researchers.headOption else None
    )
    val near = refineFields(nearFields(hits, t), t, comp)
    asserts.exists { a =>
      val windowOk  = windows.isEmpty || a.window.forall(windows.contains)
      val subjectOk =
        if a.subject.startsWith("researcher:") then owner.contains(a.subject)
        else
          val members = membersOf(a, asserts)
          val ownerOk = owner match
            case Some(o) => members.contains(o)
            case None    => researchers.isEmpty || researchers.forall(members.contains)
          ownerOk && (a.field != "jointPublicationCount" || {
            val (s0, _) = sentenceBounds(comp.text, t.start)
            comp.ents
              .filter(e => e.pos >= s0 && e.pos < t.start && members.contains(e.id))
              .map(_.id)
              .distinct
              .size >= 2
          })
      windowOk && subjectOk && flattenNumbers(a.value).exists(Jnum.sameNumber(_, t.value)) &&
      (near.contains(a.field) || positional(a, t, comp, asserts))
    }

  private val ArrowRx: Regex = """->|→|=>""".r

  /** The researcher ents at the two ends of one arrow. Other references (an edge or work ID in
    * parentheses) beside an endpoint are skipped.
    */
  private def arrowEnds(comp: Compact, m: Regex.Match): (Option[Ent], Option[Ent]) =
    val t    = comp.text
    val skip = comp.ents.filterNot(e => e.isName || e.id.startsWith("researcher:")).map(_.pos).toSet
    def gapOk(from: Int, to: Int, extra: Set[Char]): Boolean =
      (from until to).forall(i =>
        t(i).isWhitespace || t(i) == '(' || t(i) == ')' || extra.contains(t(i)) || skip.contains(i)
      )
    def isR(e: Ent) = e.isName || e.id.startsWith("researcher:")
    val left        = comp.ents
      .filter(e => isR(e) && e.pos < m.start && gapOk(e.pos + 1, m.start, Set.empty))
      .lastOption
    val right = comp.ents.find(e => isR(e) && e.pos >= m.end && gapOk(m.end, e.pos, Set(NameMark)))
    (left, right)

  /** The researchers joined by arrows in the text ("A -> B -> C" gives A, B, C). */
  private def arrowChain(comp: Compact): List[String] =
    val chain = scala.collection.mutable.ListBuffer.empty[String]
    ArrowRx.findAllMatchIn(comp.text).foreach { m =>
      arrowEnds(comp, m) match
        case (Some(l), Some(r)) =>
          if chain.isEmpty || chain.last != l.id then chain += l.id
          chain += r.id
        case _ => ()
    }
    chain.toList

  private def capitalizedWordEndingAt(t: String, end: Int): Boolean =
    var s = end
    while s - 1 >= 0 && (t(s - 1).isLetter || t(s - 1) == '\'' || t(s - 1) == '-') do s -= 1
    t(s).isUpper

  /** A name beside a reference must be the stored name of that reference's ID. */
  private def nameMismatch(comp: Compact, idToName: Map[String, String]): Boolean =
    val t     = comp.text
    val byPos = comp.ents.map(e => e.pos -> e).toMap
    comp.ents.filterNot(_.isName).filter(_.id.startsWith("researcher:")).exists { r =>
      var j = r.pos - 1
      while j >= 0 && t(j).isWhitespace do j -= 1
      if j < 0 || t(j) != '(' then false
      else
        var k = j - 1
        while k >= 0 && t(k).isWhitespace do k -= 1
        if k < 0 then false
        else if t(k) == NameMark then byPos.get(k).exists(_.id != r.id)
        else t(k).isLetter && capitalizedWordEndingAt(t, k)
    }

  private val ProperRx: Regex = """(?<![\p{L}\p{N}'’-])\p{Lu}[\p{L}'’-]*""".r

  /** A capitalized word inside a sentence that is not a stored display name of a cited identity. */
  private def unknownProperNoun(t: String): Boolean =
    ProperRx.findAllMatchIn(t).exists { m =>
      var k = m.start - 1
      while k >= 0 && t(k).isWhitespace do k -= 1
      !(k < 0 || ".!?".contains(t(k)))
    }

  /** The entities, in order of first mention, must follow every asserted ordered list. */
  private def orderMismatch(comp: Compact, asserts: List[ProposedAssertion]): Boolean =
    val seen = comp.ents.map(_.id).distinct.toList
    asserts.map(a => strArray(a.value)).filter(_.size > 1).exists { arr =>
      val idx = seen.filter(arr.contains).map(arr.indexOf)
      idx != idx.sorted
    }

  private val PairKinds: Set[String] =
    Set(Fact.CoauthorEdge, Fact.Neighborhood, Fact.Path, Fact.Ranking)

  private val PairRx: Regex =
    ("(?i)\\bare (?:direct(?:ly)? )?(?:co-?authors|collaborators|connected|linked|colleagues)\\b|" +
      "\\bco-?authored\\b|\\bcollaborat(?:ed|es)\\b|\\bconnected\\b|\\blinked\\b|\\bpath\\b|" +
      "\\bbetween\\b|\\bwith each other\\b|\\bco-?authors of\\b|\\bthrough\\b|\\bvia\\b").r

  /** Relation wording and the fact kinds whose assertions can state it. */
  private val Families: List[(Regex, Set[String])] = List(
    "(?i)\\bbridge\\b|\\bhub\\b|cross-community|outside the community" ->
      Set(Fact.DegreeVsBridge, Fact.Community, Fact.Ranking),
    "(?i)\\bcommunity\\b" ->
      Set(Fact.Community, Fact.Metrics, Fact.DegreeVsBridge, Fact.Ranking),
    "(?i)\\bcomponent\\b"                 -> Set(Fact.Component, Fact.Metrics, Fact.Ranking),
    "(?i)\\bpaths?\\b|\\bhops?\\b"        -> Set(Fact.Path),
    "(?i)co-?author|collaborat|colleague" ->
      Set(
        Fact.CoauthorEdge,
        Fact.Neighborhood,
        Fact.Metrics,
        Fact.DegreeVsBridge,
        Fact.TemporalChange,
        Fact.Path,
        Fact.Ranking,
      ),
    "(?i)\\bpublications?\\b|\\bpapers?\\b|\\bworks\\b" ->
      Set(Fact.Metrics, Fact.CoauthorEdge, Fact.WindowSummary, Fact.Ranking),
    "(?i)\\bteam\\b" -> Set(Fact.Metrics, Fact.WindowSummary, Fact.Ranking),
    "(?i)\\bidentif|\\bnamed\\b|\\bconfidence\\b" -> Set(Fact.Identity),
  ).map((s, k) => (new Regex(s), k))

  private val PairUnsupported: Set[String] =
    Set("relation_without_assertion", "reference_without_assertion")

  // ---- closed wording: negation, enumerated and boolean values, ordinals ----------------------

  private val NegRx: Regex =
    ("(?i)(?<![\\p{L}\\p{N}'’-])(?:not|no|never|neither|nor|none|nothing|nobody|without|cannot|" +
      "\\p{L}+n['’]t)(?![\\p{L}\\p{N}'’-])").r

  private val RatherRx: Regex    = """(?i)\brather than\b""".r
  private val BridgeHubRx: Regex = """(?i)\b(bridges?|hubs?)\b""".r
  private val BoilerRx: Regex    = """(?i)high direct degree alone is not bridge importance""".r
  private val PathNegRx: Regex   =
    ("(?i)\\b(?:no|not|never|without)\\b[^.;:]{0,40}\\b(?:paths?|routes?|connections?|links?)\\b|" +
      "\\b(?:paths?|routes?|connections?)\\b[^.;:]{0,40}\\b(?:not|never)\\b|\\bnot found\\b|" +
      "\\bcould not be found\\b").r
  private val PathWordRx: Regex = """(?i)\bpaths?\b""".r

  private val RankRx: Regex =
    ("(?i)(?<![\\p{L}\\p{N}'’-])(first|1st|top|highest|most|largest|biggest|greatest|best|" +
      "strongest|leading|maximum|max|longest|last|lowest|least|smallest|worst|weakest|minimum|" +
      "min|second|2nd|third|3rd|fourth|4th|fifth|5th|shortest)(?![\\p{L}\\p{N}'’-])").r
  private val HopFollowsRx: Regex   = """(?i)^\s+hops?\b""".r
  private val DescriptorRx: Regex   = """^[\sA-Za-z]{0,24}:""".r
  private val TopWords: Set[String] = Set(
    "first",
    "1st",
    "top",
    "highest",
    "most",
    "largest",
    "biggest",
    "greatest",
    "best",
    "strongest",
    "leading",
    "maximum",
    "max",
    "longest",
  )
  private val BottomWords: Set[String] =
    Set("last", "lowest", "least", "smallest", "worst", "weakest", "minimum", "min")
  private val OrdinalIdx: Map[String, Int] = Map(
    "second" -> 1,
    "2nd"    -> 1,
    "third"  -> 2,
    "3rd"    -> 2,
    "fourth" -> 3,
    "4th"    -> 3,
    "fifth"  -> 4,
    "5th"    -> 4,
  )

  private def nonIncreasing(xs: List[BigDecimal]): Boolean =
    xs.zip(xs.drop(1)).forall((a, b) => a >= b)
  private def nonDecreasing(xs: List[BigDecimal]): Boolean =
    xs.zip(xs.drop(1)).forall((a, b) => a <= b)

  /** Wording checks that tie strings, booleans and ranks to assertions. Negation must be bound to
    * an assertion that states it (no path and `found=false`; not a bridge and `classification`);
    * unbound negation is `unsupported`, and negation that a true assertion contradicts is
    * `conflicting`. Bridge and hub wording is bound to `classification`; ordinal and superlative
    * wording is bound to the order of a ranking (or neighborhood) assertion or to a `max*` field
    * the claim asserts.
    */
  private def wordingChecks(
      comp: Compact,
      asserts: List[ProposedAssertion],
      lex: List[Hit],
  ): (List[String], List[String]) =
    val conflict = scala.collection.mutable.ListBuffer.empty[String]
    val missing  = scala.collection.mutable.ListBuffer.empty[String]
    val t        = BoilerRx.replaceAllIn(comp.text, m => " " * m.matched.length)
    val cls      = asserts.filter(_.field == "classification").flatMap(_.value.strOpt)
    val found    =
      asserts.filter(a => a.kind == Fact.Path && a.field == "found").flatMap(_.value.boolOpt)
    val hasRank = asserts.exists(_.kind == Fact.Ranking)
    val bh      = BridgeHubRx.findAllMatchIn(t).toList
    val pathNeg = PathNegRx.findAllMatchIn(t).toList

    def negBefore(pos: Int): Boolean =
      val seg = t.substring(math.max(clauseStart(t, pos), pos - 40), pos)
      NegRx.findFirstIn(seg).isDefined || RatherRx.findFirstIn(seg).isDefined

    // negation
    NegRx.findAllMatchIn(t).foreach { n =>
      val inPath = pathNeg.exists(p => p.start <= n.start && n.start < p.end)
      val inBH   = bh.exists(m =>
        m.start > n.start && m.start - n.start <= 40 &&
          !t.substring(n.start, m.start).exists(Boundary.contains(_))
      )
      if inPath then
        if found.isEmpty then missing += "negation_without_assertion"
        else if !found.contains(false) then conflict += "negation_contradicts_assertion"
      else if inBH then () // decided by the classification rules below
      else if asserts.exists(a => PairKinds.contains(a.kind)) then
        conflict += "negation_contradicts_assertion"
      else missing += "negation_without_assertion"
    }
    // a path stated as found while `found` is false
    if found.contains(false) && !found.contains(true) then
      PathWordRx.findAllMatchIn(t).foreach { m =>
        if !pathNeg.exists(p => p.start <= m.start && m.start < p.end) then
          conflict += "path_wording_contradicts_assertion"
      }

    // bridge and hub wording is bound to the classification
    bh.foreach { m =>
      val target = if m.group(1).toLowerCase.startsWith("bridge") then "bridge" else "local_hub"
      val neg    = negBefore(m.start)
      if cls.isEmpty then
        if !hasRank then missing += "classification_not_asserted"
      else if neg == cls.contains(target) then conflict += "classification_contradicts_assertion"
    }

    // ordinal and superlative wording
    val aFields = asserts.map(_.field).toSet
    val orders  = asserts
      .filter(a =>
        (a.kind == Fact.Ranking && a.field == "order") ||
          (a.kind == Fact.Neighborhood && a.field == "coauthorIds")
      )
      .map(a => strArray(a.value))
    val rankValues = asserts
      .filter(a => a.kind == Fact.Ranking && a.field == "values")
      .map(a => flattenNumbers(a.value))
    val hasNeigh = asserts.exists(a => a.kind == Fact.Neighborhood && a.field == "coauthorIds")
    RankRx.findAllMatchIn(t).foreach { m =>
      val w     = m.group(1).toLowerCase
      val after = t.substring(m.end)
      if HopFollowsRx.findFirstIn(after).isDefined then () // a hop ordinal: see `hopChecks`
      else if w == "shortest" then
        if !found.contains(true) then missing += "ordinal_without_assertion"
      else if lex.exists(h =>
          (h.phrase.fields intersect aFields).nonEmpty && h.start <= m.start && m.end <= h.end
        )
      then ()
      else if DescriptorRx.findFirstIn(after).isDefined then
        if TopWords(w) then
          if rankValues.exists(nonIncreasing) && orders.nonEmpty || hasNeigh then ()
          else if rankValues.nonEmpty then conflict += "rank_wording_contradicts_assertion"
          else missing += "ordinal_without_assertion"
        else if BottomWords(w) then
          if rankValues.exists(nonDecreasing) && orders.nonEmpty then ()
          else if rankValues.nonEmpty then conflict += "rank_wording_contradicts_assertion"
          else missing += "ordinal_without_assertion"
        else missing += "ordinal_without_assertion"
      else
        val (s0, s1) = sentenceBounds(t, m.start)
        val inSent   =
          comp.ents.filter(e => e.id.startsWith("researcher:") && e.pos >= s0 && e.pos < s1)
        val target = inSent.filter(_.pos < m.start).lastOption.orElse(inSent.find(_.pos > m.start))
        (target, orders) match
          case (_, Nil)      => missing += "ordinal_without_assertion"
          case (None, _)     => missing += "ordinal_without_assertion"
          case (Some(e), os) =>
            val ok = os.exists { o =>
              val idx =
                if TopWords(w) then Some(0)
                else if BottomWords(w) then Some(o.size - 1)
                else OrdinalIdx.get(w)
              idx.exists(i => o.lift(i).contains(e.id))
            }
            if !ok then
              if os.exists(_.contains(e.id)) then conflict += "rank_wording_contradicts_assertion"
              else missing += "ordinal_without_assertion"
    }
    (conflict.toList, missing.toList)

  // ---- the edge or work stated for a hop ----------------------------------------------------

  private val HopWordRx: Regex =
    ("(?i)(?<![\\p{L}])(first|second|third|fourth|fifth|last|final|1st|2nd|3rd|[4-9]th)\\s+hop\\b|" +
      "\\bhop\\s+(\\d+)\\b").r

  /** A number that only labels a hop ("hop 2", "1st hop"): checked by `hopChecks`, not a count. */
  private def hopLabel(t: String, n: NumTok): Boolean =
    HopWordRx.findAllMatchIn(t).exists { m =>
      (1 to 2).exists(g => m.start(g) >= 0 && m.start(g) <= n.start && n.end <= m.end(g))
    }

  /** An edge ID or work ID stated for a specific hop ("first hop", "hop 2", or right after the
    * endpoints of an arrow) must equal that hop's asserted edge or one of its asserted works.
    */
  private def hopChecks(
      comp: Compact,
      asserts: List[ProposedAssertion],
  ): (List[String], List[String]) =
    val conflict = scala.collection.mutable.ListBuffer.empty[String]
    val missing  = scala.collection.mutable.ListBuffer.empty[String]
    val pathAs   = asserts.filter(_.kind == Fact.Path)
    val hops     = pathAs.find(_.field == "hops").map(a => strArray(a.value))
    val edges    = pathAs.find(_.field == "edgeIds").map(a => strArray(a.value))
    val works    = pathAs
      .find(_.field == "hopWorkIds")
      .flatMap(_.value.arrOpt)
      .map(_.toList.map(strArray))
    val nHops = hops
      .map(h => math.max(h.size - 1, 0))
      .orElse(edges.map(_.size))
      .orElse(works.map(_.size))
      .getOrElse(0)
    val t       = comp.text
    val hopRefs = comp.ents.filter(e => e.id.startsWith("edge:") || e.id.startsWith("work:"))

    def check(i: Int, e: Ent): Unit =
      if i < 0 || i >= nHops then conflict += "hop_evidence_mismatch"
      else if e.id.startsWith("edge:") then
        edges match
          case None     => missing += "hop_evidence_not_asserted"
          case Some(es) => if !es.lift(i).contains(e.id) then conflict += "hop_evidence_mismatch"
      else
        works match
          case None     => missing += "hop_evidence_not_asserted"
          case Some(ws) =>
            if !ws.lift(i).exists(_.contains(e.id)) then conflict += "hop_evidence_mismatch"

    val words = HopWordRx.findAllMatchIn(t).toList
    words.zipWithIndex.foreach { (m, k) =>
      val idx = Option(m.group(2)).map(_.toInt - 1).getOrElse {
        m.group(1).toLowerCase match
          case "first" | "1st"  => 0
          case "second" | "2nd" => 1
          case "third" | "3rd"  => 2
          case "fourth" | "4th" => 3
          case "fifth" | "5th"  => 4
          case "last" | "final" => nHops - 1
          case o                => o.take(1).toInt - 1
      }
      val (_, sEnd) = sentenceBounds(t, m.end)
      val limit     = words.lift(k + 1).map(_.start).filter(_ < sEnd).getOrElse(sEnd)
      hopRefs.filter(e => e.pos >= m.end && e.pos < limit).foreach(check(idx, _))
    }

    // references right after the endpoints of an arrow
    hops.foreach { hs =>
      val arrows = ArrowRx.findAllMatchIn(t).toList
      arrows.foreach { m =>
        val (left, right) = arrowEnds(comp, m)
        for
          l <- left
          r <- right
          i = hs.indices.dropRight(1).find(i => hs(i) == l.id && hs(i + 1) == r.id)
          if i.isDefined
        do
          val stop = (
            comp.ents.filter(e => e.pos > r.pos && e.id.startsWith("researcher:")).map(_.pos) ++
              arrows.map(_.start).filter(_ > r.pos) ++
              t.indices.drop(r.pos + 1).find(j => Boundary.contains(t(j))).toList
          ).minOption.getOrElse(t.length)
          hopRefs.filter(e => e.pos > r.pos && e.pos < stop).foreach(check(i.get, _))
      }
    }
    (conflict.toList.distinct, missing.toList.distinct)

  def claimIdOf(queryId: String, index: Int, c: ProposedClaim): String =
    "claim:" + Hashing.digest("scholarchain.rag.claim.v1", List(queryId, index.toString, c.text))

  /** Entity references in a text, with trailing punctuation removed. */
  def refsIn(text: String): List[String] =
    RefRx.findAllIn(text).toList.map(_.replaceAll("[.:]+$", "")).distinct

  private val ResearcherRx: Regex =
    """researcher:(?:sc1:[0-9a-f]{64}|orcid:[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X])""".r

  /** A pool of references plus the researcher IDs embedded in other IDs (a community ID holds its
    * smallest member's researcher ID).
    */
  def expandRefs(refs: Set[String]): Set[String] =
    refs ++ refs.flatMap(r => ResearcherRx.findAllIn(r).toList)

  def stripRefs(text: String): String =
    RefRx.replaceAllIn(text, " ")

  /** Numbers in a text (thousands separators removed first). */
  def numbersIn(text: String): List[BigDecimal] =
    NumRx
      .findAllIn(text.replaceAll("(\\d),(\\d{3})", "$1$2"))
      .toList
      .flatMap(s => scala.util.Try(BigDecimal(s)).toOption)

  def stringsIn(v: ujson.Value): List[String] = v match
    case ujson.Str(s) => List(s)
    case ujson.Arr(a) => a.toList.flatMap(stringsIn)
    case ujson.Obj(o) => o.values.toList.flatMap(stringsIn)
    case _            => Nil

  def flattenNumbers(v: ujson.Value): List[BigDecimal] = v match
    case n: ujson.Num => Jnum.value(n).toList
    case ujson.Arr(a) => a.toList.flatMap(flattenNumbers)
    case _            => Nil

  /** Exact value equality: numbers numerically, strings and booleans exactly, arrays elementwise.
    */
  def sameValue(stored: ujson.Value, claimed: ujson.Value): Boolean = (stored, claimed) match
    case (ujson.Num(a), ujson.Num(b))   => Jnum.sameNumber(BigDecimal(a), BigDecimal(b))
    case (ujson.Str(a), ujson.Str(b))   => a == b
    case (ujson.Bool(a), ujson.Bool(b)) => a == b
    case (ujson.Arr(a), ujson.Arr(b))   =>
      a.size == b.size && a.zip(b).forall((x, y) => sameValue(x, y))
    case _ => false

  private def severity(s: ClaimVerificationStatus): Int = s match
    case ClaimVerificationStatus.Verified           => 0
    case ClaimVerificationStatus.PartiallySupported => 1
    case ClaimVerificationStatus.Unsupported        => 2
    case ClaimVerificationStatus.Conflicting        => 3

  /** The more severe of two statuses: a second opinion can lower a status, never raise it. */
  def moreSevere(a: ClaimVerificationStatus, b: ClaimVerificationStatus): ClaimVerificationStatus =
    if severity(b) > severity(a) then b else a

  /** The v1 status of an answer claim for a runtime status. VERIFIED to `supported`,
    * PARTIALLY_SUPPORTED to `partially_supported`, UNSUPPORTED to `unsupported`, CONFLICTING to
    * `contradicted`.
    */
  def toV1(s: ClaimVerificationStatus): VerificationStatus = s match
    case ClaimVerificationStatus.Verified           => VerificationStatus.Supported
    case ClaimVerificationStatus.PartiallySupported => VerificationStatus.PartiallySupported
    case ClaimVerificationStatus.Unsupported        => VerificationStatus.Unsupported
    case ClaimVerificationStatus.Conflicting        => VerificationStatus.Contradicted
