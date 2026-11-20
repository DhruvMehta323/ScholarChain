package scholarchain.hadoop

import org.apache.hadoop.mapreduce.TaskInputOutputContext

/** The single counter group of the pipeline (Hadoop names the group after this enum class). Meaning
  * is per job: INPUT_RECORDS = records the mappers read, ACCEPTED = records written to the job's
  * primary output, REJECTED = reject-v1 rows written. See JOB-CONTRACTS.md.
  */
enum PipelineCounter extends java.lang.Enum[PipelineCounter]:
  case INPUT_RECORDS, ACCEPTED, REJECTED, DUPLICATE, CONFLICT, EXACT_LINK
  case OVERSIZE_AUTHOR_TEAM, LARGE_AUTHOR_TEAM, EMITTED_PAIRS, EXPECTED_PAIRS
  case MISSING_KEY, SCHEMA_FAILURE, SELF_PAIR_SKIPPED, DUPLICATE_PAIR
  case INPUT_DBLP, INPUT_CROSSREF, INPUT_OPENALEX, INPUT_ORCID
  case WWW_RECORDS, PERSON_ALIASES, UPSTREAM_MISSING, MISSING_YEAR_KEPT, INVALID_IDENTIFIER_SKIPPED,
    WORK_SKIPPED, WORK_MERGED, ENTITY_MERGED

object Counters:
  /** Group of the per-table row counters ("rows") and per-reason reject counters ("rejects"). */
  val RowsGroup: String    = "rows"
  val RejectsGroup: String = "rejects"
  val EnumGroup: String    = classOf[PipelineCounter].getName

  def inc(ctx: TaskInputOutputContext[?, ?, ?, ?], c: PipelineCounter, n: Long = 1L): Unit =
    ctx.getCounter(c).increment(n)
