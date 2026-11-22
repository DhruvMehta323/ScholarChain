package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}

/** The ONLY place in the module that brings data to the driver.
  *
  * Allow-list (and nothing else):
  *   - counts and emptiness checks (`count`, `isEmpty`, `longValue`);
  *   - convergence flags (a count of changed rows);
  *   - small aggregates for reports and run summaries (`rows`, bounded by `MaxRows`), always taken
  *     from an already aggregated or `limit`ed frame, never from a full table;
  *   - input file checksums are computed from the file system, not from Spark.
  *
  * No other source file may call `.collect()`, `.collectAsList()` or `.toLocalIterator()`. A test
  * can scan the sources for those tokens outside this file. Methods here refuse to return more than
  * `MaxRows` rows.
  */
object DriverCollect:
  /** Upper bound on rows that `rows` returns. */
  val MaxRows: Int = 1000

  def count(df: DataFrame): Long = df.count()

  def isEmpty(df: DataFrame): Boolean = df.limit(1).count() == 0L

  /** The single Long value of a frame with exactly one row and one column. */
  def longValue(df: DataFrame): Long =
    val r = rows(df, 2)
    require(r.length == 1 && r(0).length == 1, "longValue expects a single value")
    r(0).getLong(0)

  /** At most `maxRows` rows of an aggregated or limited frame. */
  def rows(df: DataFrame, maxRows: Int): Array[Row] =
    require(maxRows >= 0 && maxRows <= MaxRows, s"maxRows must be within 0..$MaxRows")
    df.limit(maxRows).collect()
end DriverCollect
