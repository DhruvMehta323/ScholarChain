package scholarchain.er

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Everything the feature functions need to know about one entity (an author mention or a work),
  * flattened from the curated tables. Wire name: `entity-view` JSON Lines, one record per line.
  *
  * Researcher view (`kind = researcher`, `id = mention:...`): `name` is the raw author string,
  * `position` the 0-based author position, `workId`/`title`/`year`/`venue`/`doi` describe the work
  * that holds the mention, `authors` are the OTHER authors of that work (coauthors). Work view
  * (`kind = work`, `id = work:...`): `title`/`year`/`venue` and the work's ids; `authors` are all
  * author names in order.
  */
final case class EntityView(
    id: String,
    kind: EntityKind,
    source: SourceType,
    name: Option[String] = None,
    position: Option[Int] = None,
    orcid: Option[String] = None,
    workId: Option[String] = None,
    title: Option[String] = None,
    year: Option[Int] = None,
    venue: Option[String] = None,
    doi: Option[String] = None,
    arxiv: Option[String] = None,
    openAlexId: Option[String] = None,
    authors: List[String] = Nil,
    affiliation: Option[String] = None,
) derives ReadWriter

object EntityViews:
  /** Builds researcher and work views from the curated `works` and `author-mentions` rows. The
    * source of a view is the `provenance.sourceType` of its record. Work DOIs come from
    * `WorkRecord.doi` or a `work:doi:` ID. Venue and affiliation are not persisted in Phase 04, so
    * they are absent (`None`) and the features treat them as not available.
    */
  def build(works: Seq[WorkRecord], mentions: Seq[AuthorMention]): List[EntityView] =
    val byWork = works.map(w => w.workId -> w).toMap
    val team   = mentions.groupBy(_.workId).view.mapValues(_.sortBy(_.position)).toMap
    def doiOf(w: WorkRecord): Option[String] =
      w.doi
        .flatMap(ErIds.doi)
        .orElse(
          Option.when(w.workId.startsWith("work:doi:"))(w.workId.stripPrefix("work:doi:"))
        )
    val workViews = works.toList.sortBy(_.workId).map { w =>
      EntityView(
        id = w.workId,
        kind = EntityKind.Work,
        source = w.provenance.sourceType,
        title = Some(w.title),
        year = Some(w.year),
        venue = w.venueId,
        doi = doiOf(w),
        authors = team.getOrElse(w.workId, Nil).map(_.rawName).toList,
      )
    }
    val mentionViews = mentions.toList.sortBy(_.mentionId).map { m =>
      val w = byWork.get(m.workId)
      EntityView(
        id = m.mentionId,
        kind = EntityKind.Researcher,
        source = m.provenance.sourceType,
        name = Some(m.rawName),
        position = Some(m.position),
        orcid = m.orcid,
        workId = Some(m.workId),
        title = w.map(_.title),
        year = w.map(_.year),
        venue = w.flatMap(_.venueId),
        doi = w.flatMap(doiOf),
        authors =
          team.getOrElse(m.workId, Nil).filter(_.mentionId != m.mentionId).map(_.rawName).toList,
      )
    }
    workViews ++ mentionViews
