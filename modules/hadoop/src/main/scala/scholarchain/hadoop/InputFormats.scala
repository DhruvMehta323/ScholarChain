package scholarchain.hadoop

import java.io.{ByteArrayOutputStream, InputStream}

import org.apache.hadoop.fs.{FileSystem, Path, PathFilter}
import org.apache.hadoop.io.{BytesWritable, LongWritable, Text}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}

/** Input format for a DBLP XML file: key = byte offset of the record's start tag, value = the raw
  * bytes of one complete top-level record. A record belongs to the split its start tag begins in;
  * the reader reads past the split end to finish it, so no record is ever split across mappers.
  * Compressed files are not splittable: they form exactly one split, are read whole (no split-end
  * cut applies) and the key is then the offset in the DECOMPRESSED stream.
  */
class DblpRecordInputFormat extends FileInputFormat[LongWritable, BytesWritable]:
  override def isSplitable(context: JobContext, file: Path): Boolean =
    new CompressionCodecFactory(context.getConfiguration).getCodec(file) == null

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext,
  ): RecordReader[LongWritable, BytesWritable] = new DblpRecordReader

object DblpRecordReader:
  /** Records larger than this are cut; the mapper sees `length == limit` and rejects the record. */
  val MaxRecordBytesKey: String  = "scholarchain.hadoop.dblp.max-record-bytes"
  val DefaultMaxRecordBytes: Int = 16 * 1024 * 1024

  private def pack(s: String): Long = s.foldLeft(0L)((a, c) => (a << 8) | c.toLong)
  val CdataOpen: Long               = pack("![CDATA[") // preceded by '<'
  val CommentOpen: Long             = pack("<!--")
  val CdataClose: Long              = pack("]]>")
  val CommentClose: Long            = pack("-->")

class DblpRecordReader extends RecordReader[LongWritable, BytesWritable]:
  private var in: InputStream = scala.compiletime.uninitialized
  private var splitStart      = 0L
  private var splitEnd        = 0L
  private var maxBytes        = DblpRecordReader.DefaultMaxRecordBytes
  private val buf             = new Array[Byte](64 * 1024)
  private var bufLen          = 0
  private var bufIdx          = 0
  private var nextPos         = 0L // absolute offset of the next byte read from `buf`
  private val pbBytes = new Array[Int](64) // pushback stack (lookahead is at most 34 bytes)
  private val pbPoss  = new Array[Long](64)
  private var pbN     = 0
  private var raw: org.apache.hadoop.fs.FSDataInputStream = scala.compiletime.uninitialized
  private var compressed                                  = false
  private var fileLen                                     = 0L
  private var finished                                    = false
  private val curKey                                      = new LongWritable
  private val curVal                                      = new BytesWritable
  private var lastPos                                     = 0L

  override def initialize(split: InputSplit, ctx: TaskAttemptContext): Unit =
    val fs: FileSplit = split.asInstanceOf[FileSplit]
    val conf          = ctx.getConfiguration
    maxBytes =
      conf.getInt(DblpRecordReader.MaxRecordBytesKey, DblpRecordReader.DefaultMaxRecordBytes)
    val path  = fs.getPath
    val codec = new CompressionCodecFactory(conf).getCodec(path)
    raw = path.getFileSystem(conf).open(path)
    splitStart = fs.getStart
    splitEnd = fs.getStart + fs.getLength
    fileLen = fs.getLength
    if codec == null then
      raw.seek(splitStart)
      in = raw
      nextPos = splitStart
    else
      // One split, read whole: the split length is the COMPRESSED size, while offsets count
      // decompressed bytes, so the split-end cut must not apply.
      compressed = true
      splitStart = 0L
      splitEnd = Long.MaxValue
      in = codec.createInputStream(raw)
      nextPos = 0L

  /** Next byte, or -1 at end of stream. Tracks the absolute offset of the byte just returned. */
  private def read(): Int =
    if pbN > 0 then
      pbN -= 1
      lastPos = pbPoss(pbN)
      pbBytes(pbN)
    else
      if bufIdx >= bufLen then
        bufLen = in.read(buf, 0, buf.length)
        bufIdx = 0
      if bufLen <= 0 then
        bufLen = 0
        -1
      else
        val b = buf(bufIdx) & 0xff
        bufIdx += 1
        lastPos = nextPos
        nextPos += 1
        b

  private def unread(b: Int, pos: Long): Unit =
    pbBytes(pbN) = b
    pbPoss(pbN) = pos
    pbN += 1

  private def isLetter(b: Int): Boolean = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
  private def isDelim(b: Int): Boolean  =
    b == ' ' || b == '>' || b == '/' || b == '\t' || b == '\r' || b == '\n'

  override def nextKeyValue(): Boolean =
    if finished then return false
    var found = false
    while !found && !finished do
      val b = read()
      if b < 0 then finished = true
      else if b == '<' then
        val tagStart = lastPos
        if tagStart >= splitEnd then finished = true
        else
          val name = new StringBuilder
          var c    = read()
          while c >= 0 && isLetter(c) && name.length < 32 do
            name.append(c.toChar)
            c = read()
          if c >= 0 then unread(c, lastPos)
          val n = name.toString
          if DblpParser.RecordTags(n) && (c < 0 || isDelim(c)) then
            readRecord(tagStart, n)
            found = true
    found

  /** After a consumed `<` at `ltPos`: true when a top-level record start tag follows. Everything
    * consumed (including the `<`) is pushed back either way, so reading continues unchanged.
    */
  private def startsTopLevelRecord(ltPos: Long): Boolean =
    val got = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
    val sb  = new StringBuilder
    var c   = read()
    while c >= 0 && isLetter(c) && sb.length < 32 do
      got += ((c, lastPos))
      sb.append(c.toChar)
      c = read()
    val delimPos = lastPos
    if c >= 0 then got += ((c, delimPos))
    val hit = c >= 0 && isDelim(c) && DblpParser.RecordTags(sb.toString)
    got.reverseIterator.foreach((b, p) => unread(b, p))
    unread('<', ltPos)
    if hit then true
    else
      read() // consume the `<` again; the caller treats it as an ordinary byte
      false

  /** Reads the rest of the record whose `<name` has been consumed. */
  private def readRecord(tagStart: Long, name: String): Unit =
    val out               = new ByteArrayOutputStream(1024)
    var size              = 0
    var cut               = false
    val endTag            = s"</$name>".getBytes("US-ASCII")
    val tail              = new Array[Byte](endTag.length)
    var tailN             = 0
    def put(b: Int): Unit =
      if size < maxBytes then { out.write(b); size += 1 }
      else cut = true
      // rolling window of the last endTag.length bytes, independent of the cut
      if tailN < tail.length then { tail(tailN) = b.toByte; tailN += 1 }
      else
        System.arraycopy(tail, 1, tail, 0, tail.length - 1)
        tail(tail.length - 1) = b.toByte
    def tailMatches: Boolean = tailN == tail.length && java.util.Arrays.equals(tail, endTag)
    "<".getBytes("US-ASCII").foreach(b => out.write(b))
    size = 1
    name.getBytes("US-ASCII").foreach(b => { out.write(b); size += 1 })
    // start tag remainder, honoring quotes, up to the closing '>'
    var quote  = -1
    var prev   = -1
    var closed = false
    var eof    = false
    while !closed && !eof do
      val b = read()
      if b < 0 then eof = true
      else
        put(b)
        if quote >= 0 then { if b == quote then quote = -1 }
        else if b == '"' || b == '\'' then quote = b
        else if b == '>' then closed = true
        if !closed then prev = b
    var done = eof || (closed && prev == '/')
    if !done then
      tailN = 0
      // lit: 0 = markup, 1 = inside <![CDATA[ ... ]]>, 2 = inside <!-- ... -->. Inside a literal
      // section neither an end tag nor a start tag counts. (w0, w1) hold the last 9 bytes.
      var lit = 0
      var w0  = 0
      var w1  = 0L
      while !done do
        val b = read()
        if b < 0 then { done = true; eof = true }
        else if lit == 0 && b == '<' && startsTopLevelRecord(lastPos) then
          // An unclosed record: stop before the next top-level start tag (left unread, so the next
          // call reads it as its own record). The truncated record is rejected by the parser.
          done = true
        else
          put(b)
          w0 = (w1 >>> 56).toInt
          w1 = (w1 << 8) | b
          if lit == 0 then
            if w0 == '<' && w1 == DblpRecordReader.CdataOpen then lit = 1
            else if (w1 & 0xffffffffL) == DblpRecordReader.CommentOpen then lit = 2
            else if b == '>' && tailMatches then done = true
          else
            val close =
              if lit == 1 then DblpRecordReader.CdataClose else DblpRecordReader.CommentClose
            if (w1 & 0xffffffL) == close then lit = 0
    if eof then finished = true
    curKey.set(tagStart)
    val bytes = out.toByteArray
    curVal.set(bytes, 0, bytes.length)

  override def getCurrentKey: LongWritable    = curKey
  override def getCurrentValue: BytesWritable = curVal
  override def getProgress: Float             =
    if compressed then
      if finished || fileLen == 0 then 1f
      else math.min(1f, math.max(0f, raw.getPos.toFloat / fileLen.toFloat))
    else if splitEnd == splitStart then 1f
    else
      math.min(1f, math.max(0f, (nextPos - splitStart).toFloat / (splitEnd - splitStart).toFloat))
  override def close(): Unit = if in != null then in.close()

/** Whole-file input: key = file path, value = the file's bytes. Never splits a file. Used for the
  * enrichment envelopes (one JSON document per file).
  */
class WholeFileInputFormat extends FileInputFormat[Text, BytesWritable]:
  override def isSplitable(context: JobContext, file: Path): Boolean = false
  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext,
  ): RecordReader[Text, BytesWritable] = new WholeFileRecordReader

class WholeFileRecordReader extends RecordReader[Text, BytesWritable]:
  private var split: FileSplit                           = scala.compiletime.uninitialized
  private var conf: org.apache.hadoop.conf.Configuration = scala.compiletime.uninitialized
  private var done                                       = false
  private val k                                          = new Text
  private val v                                          = new BytesWritable

  override def initialize(s: InputSplit, ctx: TaskAttemptContext): Unit =
    split = s.asInstanceOf[FileSplit]
    conf = ctx.getConfiguration

  override def nextKeyValue(): Boolean =
    if done then false
    else
      val path = split.getPath
      val in   = path.getFileSystem(conf).open(path)
      val data =
        try in.readAllBytes()
        finally in.close()
      k.set(path.toString)
      v.set(data, 0, data.length)
      done = true
      true

  override def getCurrentKey: Text            = k
  override def getCurrentValue: BytesWritable = v
  override def getProgress: Float             = if done then 1f else 0f
  override def close(): Unit                  = ()

/** HJ2 envelope input: a `*.json` / extension-less file is read whole exactly like
  * `WholeFileInputFormat` (key = file path); a packed `*.envelopes.jsonl` file yields one record
  * per non-empty line (key = `<file path>#<1-based physical line number>`, value = the line bytes
  * without the `\n`). Never splits a file, so line numbers are deterministic whatever the split
  * settings.
  */
class EnvelopeInputFormat extends FileInputFormat[Text, BytesWritable]:
  override def isSplitable(context: JobContext, file: Path): Boolean = false
  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext,
  ): RecordReader[Text, BytesWritable] = new EnvelopeRecordReader

object EnvelopeFiles:
  val PackedSuffix               = ".envelopes.jsonl"
  def isPacked(p: Path): Boolean = p.getName.endsWith(PackedSuffix)

class EnvelopeRecordReader extends RecordReader[Text, BytesWritable]:
  private val whole                   = new WholeFileRecordReader
  private var packed                  = false
  private var path: Path              = scala.compiletime.uninitialized
  private var in: java.io.InputStream = scala.compiletime.uninitialized
  private var lineNo                  = 0L
  private var eof                     = false
  private var length                  = 0L
  private var consumed                = 0L
  private val k                       = new Text
  private val v                       = new BytesWritable

  override def initialize(s: InputSplit, ctx: TaskAttemptContext): Unit =
    val split = s.asInstanceOf[FileSplit]
    path = split.getPath
    packed = EnvelopeFiles.isPacked(path)
    if packed then
      length = split.getLength
      in = new java.io.BufferedInputStream(
        path.getFileSystem(ctx.getConfiguration).open(path),
        1 << 16,
      )
    else whole.initialize(s, ctx)

  /** The next line's bytes (without `\n`), or null at end of file. */
  private def readLine(): Array[Byte] =
    if eof then null
    else
      val out = new java.io.ByteArrayOutputStream
      var b   = in.read()
      if b < 0 then
        eof = true
        null
      else
        while b >= 0 && b != '\n' do
          out.write(b)
          consumed += 1
          b = in.read()
        consumed += 1
        if b < 0 then eof = true
        lineNo += 1
        out.toByteArray

  override def nextKeyValue(): Boolean =
    if !packed then whole.nextKeyValue()
    else
      var line = readLine()
      while line != null && line.isEmpty do line = readLine()
      if line == null then false
      else
        k.set(s"${path.toString}#$lineNo")
        v.set(line, 0, line.length)
        true

  override def getCurrentKey: Text            = if packed then k else whole.getCurrentKey
  override def getCurrentValue: BytesWritable = if packed then v else whole.getCurrentValue
  override def getProgress: Float             =
    if !packed then whole.getProgress
    else if eof || length == 0 then 1f
    else math.min(1f, consumed.toFloat / length.toFloat)
  override def close(): Unit = if packed then { if in != null then in.close() }
  else whole.close()

/** Accepts `*.json` files and extension-less names. The extension-less rule is needed because the
  * filter is also applied to the input path itself (a directory such as `works`); it still skips
  * the fetcher's `errors.jsonl` and any other file with a different extension. It also accepts
  * packed `*.envelopes.jsonl` files (one envelope per line).
  */
class JsonFileFilter extends PathFilter:
  override def accept(p: Path): Boolean =
    val n = p.getName
    n.endsWith(".json") || n.endsWith(EnvelopeFiles.PackedSuffix) || !n.contains('.')
