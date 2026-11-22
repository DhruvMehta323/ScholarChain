package scholarchain.rag

import scala.collection.mutable.ArrayBuffer

import scholarchain.core.EmbeddingDocument

/** Chunking policy for long text. UNIT: UTF-16 characters (`String.length`), not tokens.
  *   - `maxChars`: the largest chunk, in 200..`ChunkPolicy.MaxMaxChars` (20,000).
  *   - `overlapChars`: the nominal overlap between consecutive chunks, in 0 until `maxChars / 2`.
  *     The real overlap is at most this value because the next chunk starts at a token start.
  *
  * Default 2,000 characters with 200 overlap: well below the Titan V2 limit of 8,192 tokens.
  */
final case class ChunkPolicy(maxChars: Int, overlapChars: Int)

object ChunkPolicy:
  val MaxMaxChars: Int     = 20000
  val Default: ChunkPolicy = ChunkPolicy(2000, 200)

  def validate(p: ChunkPolicy): Either[String, ChunkPolicy] =
    if p.maxChars < 200 || p.maxChars > MaxMaxChars then
      Left(s"maxChars must be in 200..$MaxMaxChars")
    else if p.overlapChars < 0 || p.overlapChars >= p.maxChars / 2 then
      Left("overlapChars must be in 0 until maxChars/2")
    else Right(p)

/** Deterministic chunker.
  *
  * Rules (all positional, no randomness, no locale):
  *   1. A text of at most `maxChars` characters is one chunk (ordinal 0, count 1), returned
  *      verbatim.
  *   1. Otherwise chunk k starts at `start` and ends at `min(start + maxChars, length)`. If that
  *      end is not the end of the text, it moves back to the nearest whitespace boundary (a cut
  *      right before or right after a whitespace character) that lies in the second half of the
  *      window, so a token (and therefore a number) is never split. Only when the window has no
  *      whitespace boundary at all is the cut hard (and never inside a surrogate pair).
  *   1. The next chunk starts at the first token start (a non-whitespace character after
  *      whitespace) at or after `end - overlapChars`; if none exists before `end` there is no
  *      overlap. The start always advances, so chunking terminates.
  *   1. Chunks are exact substrings of the input (no trimming), in order. Their ordinals are
  *      0-based; `chunkCount` is the number of chunks, at most `EmbeddingDocument.MaxChunkCount`.
  */
object Chunker:
  def chunk(text: String, policy: ChunkPolicy): Either[String, Vector[String]] =
    for
      p <- ChunkPolicy.validate(policy)
      _ <- if text.isBlank then Left("text is blank") else Right(())
      chunks = split(text, p)
      _ <-
        if chunks.size <= EmbeddingDocument.MaxChunkCount then Right(())
        else Left(s"more than ${EmbeddingDocument.MaxChunkCount} chunks")
      _ <- if chunks.forall(!_.isBlank) then Right(()) else Left("a chunk is blank")
    yield chunks

  private def split(text: String, p: ChunkPolicy): Vector[String] =
    val len = text.length
    if len <= p.maxChars then Vector(text)
    else
      val out   = ArrayBuffer.empty[String]
      var start = 0
      var done  = false
      while !done do
        val hardEnd = math.min(start + p.maxChars, len)
        val end     = if hardEnd == len then len else cutBack(text, start, hardEnd, p.maxChars)
        out += text.substring(start, end)
        if end >= len then done = true
        else start = nextStart(text, start, end, p.overlapChars)
      out.toVector

  private def cutBack(text: String, start: Int, hardEnd: Int, max: Int): Int =
    val floor = start + max / 2
    var i     = hardEnd
    while i > floor && !(text(i - 1).isWhitespace || text(i).isWhitespace) do i -= 1
    if i > floor then i
    else if text(hardEnd - 1).isHighSurrogate then hardEnd - 1
    else hardEnd

  private def nextStart(text: String, start: Int, end: Int, overlap: Int): Int =
    var i = math.max(end - overlap, start + 1)
    while i < end && !(text(i - 1).isWhitespace && !text(i).isWhitespace) do i += 1
    i
