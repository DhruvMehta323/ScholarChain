package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Hadoop-internal and Hadoop-owned records. They carry `schemaVersion` 1 like the core records. */

/** A reference from a record to an identifier. `idType` is doi, arxiv, orcid, openalex, or `none`
  * (a work that carries no external identifier; counted as MISSING_KEY). `ids` holds the work's
  * other authoritative identifiers, used to detect disagreement.
  */
final case class IdRef(
    idType: String,
    value: String,
    ref: String,
    source: SourceType,
    ids: Map[String, String],
) derives ReadWriter

final case class DblpAuthor(name: String, orcid: Option[String]) derives ReadWriter

/** Result of parsing one DBLP publication record. Venue fields and links are returned here for the
  * pure API; the core contracts have no field for them (see JOB-CONTRACTS.md, gaps).
  */
final case class DblpWork(
    key: String,
    workId: String,
    recordType: String,
    title: String,
    year: Int,
    authors: List[DblpAuthor],
    droppedDuplicateAuthors: Int,
    editors: List[String],
    journal: Option[String],
    booktitle: Option[String],
    volume: Option[String],
    number: Option[String],
    pages: Option[String],
    crossref: Option[String],
    ees: List[String],
    urls: List[String],
    doi: Option[String],
    arxiv: Option[String],
) derives ReadWriter

/** A DBLP `www` (homepage) record: name variants plus any ORCID and URL. */
final case class DblpPerson(
    key: String,
    names: List[String],
    orcids: List[String],
    urls: List[String],
) derives ReadWriter

final case class ParsedDblp(offset: Long, work: DblpWork) derives ReadWriter
final case class ParsedPerson(offset: Long, person: DblpPerson) derives ReadWriter

/** One output row of a normalizer: destination table, sort key and the record JSON. */
final case class Row(table: String, sortKey: String, json: String) derives ReadWriter

/** Everything one enrichment source record produced. */
final case class Bundle(
    source: SourceType,
    sourceRecordId: String,
    retrievedAt: String,
    fileName: String,
    fileSha256: String,
    rows: List[Row],
) derives ReadWriter

/** Why a record or file was not accepted. */
final case class ParseFailure(
    reason: RejectReason,
    message: String,
    sourceRecordId: Option[String] = None,
)
