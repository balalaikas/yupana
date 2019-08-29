package org.yupana.jdbc

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.{ByteBuffer, ByteOrder}
import java.util.logging.Logger

import org.yupana.api.query.{Result, SimpleResult}
import org.yupana.api.types.DataType
import org.yupana.jdbc.build.BuildInfo
import org.yupana.proto._
import org.yupana.proto.util.ProtocolVersion

class YupanaTcpClient(val host: String, val port: Int) extends AutoCloseable {

  private val logger = Logger.getLogger(classOf[YupanaTcpClient].getName)

  private val CHUNK_SIZE = 1024 * 100

  private var channel: SocketChannel = _

  private def ensureConnected(): Unit = {
    if (channel == null || !channel.isConnected) {
      channel = SocketChannel.open()
      channel.configureBlocking(true)
      channel.connect(new InetSocketAddress(host, port))
    }
  }

  def query(query: String, params: Map[Int, ParameterValue]): Result = {
    val request = createProtoQuery(query, params)
    execRequestQuery(request)
  }

  def ping(reqTime: Long): Option[Version] = {
    val request = createProtoPing(reqTime)
    val response = execPing(request)
    if (response.reqTime != reqTime) {
      throw new Exception("got wrong ping response")
    }
    channel.close()
    response.version
  }

  private def execPing(request: Request): Pong = {
    ensureConnected()
    sendRequest(request)
    val pong = fetchResponse()

    pong.resp match {
      case Response.Resp.Pong(r) =>
        if (r.getVersion.protocol != ProtocolVersion.value) {
          error(s"Incompatible protocol versions: ${r.getVersion.protocol} on server and ${ProtocolVersion.value} in this driver")
          null
        } else {
          r
        }

      case Response.Resp.Error(msg) =>
        error(s"Got error response on ping, '$msg'")
        null

      case _ =>
        error("Unexpected response on ping")
        null
    }
  }

  private def execRequestQuery(request: Request): Result = {
    ensureConnected()
    sendRequest(request)
    val it = new FramingChannelIterator(channel, CHUNK_SIZE + 4)
      .map(bytes => Response.parseFrom(bytes).resp)

    val header = it.map( resp =>
      handleResultHeader(resp)
    ).find(_.isDefined).flatten

    header match  {
      case Some(Right(h)) =>
        val r = resultIterator(it)
        extractProtoResult(h, r)

      case Some(Left(e)) =>
        channel.close()
        throw new IllegalArgumentException(e)

      case None =>
        channel.close()
        throw new IllegalArgumentException(error("Result not received"))
    }
  }

  private def sendRequest(request: Request): Unit = {
    channel.write(createChunks(request.toByteArray))
  }

  private def createChunks(data: Array[Byte]): Array[ByteBuffer] = {
    data.sliding(CHUNK_SIZE, CHUNK_SIZE).map { ch =>
      val bb = ByteBuffer.allocate(ch.length + 4).order(ByteOrder.BIG_ENDIAN)
      bb.putInt(ch.length)
      bb.put(ch)
      bb.flip()
      bb
    }.toArray
  }

  private def fetchResponse(): Response = {
    val bb = ByteBuffer.allocate(CHUNK_SIZE + 4).order(ByteOrder.BIG_ENDIAN)
    channel.read(bb)
    bb.flip()
    val chunkSize = bb.getInt()
    val bytes = Array.ofDim[Byte](chunkSize)
    bb.get(bytes)
    Response.parseFrom(bytes)
  }

  private def handleResultHeader(resp: Response.Resp): Option[Either[String, ResultHeader]] = {
    resp match {
      case Response.Resp.ResultHeader(h) =>
        logger.info("Received result header " + h)
        Some(Right(h))

      case Response.Resp.Result(_) =>
        Some(Left(error("Data chunk received before header")))

      case Response.Resp.Pong(_) =>
        Some(Left(error("Unexpected TspPong response")))

      case Response.Resp.Heartbeat(time) =>
        heartbeat(time)

      case Response.Resp.Error(e) =>
        channel.close()
        Some(Left(error(e)))

      case Response.Resp.ResultStatistics(_) =>
        Some(Left(error("Unexpected ResultStatistics response")))

      case Response.Resp.Empty =>
        None
    }
  }

  private def error(e: String): String = {
    logger.warning(s"Got error message: $e")
    e
  }


  private def heartbeat(time: String) = {
    val msg = s"Heartbeat($time)"
    logger.info(msg)
    None
  }

  private def resultIterator(responses: Iterator[Response.Resp]): Iterator[ResultChunk] = {
    new Iterator[ResultChunk] {

      var statistics: Option[ResultStatistics] = Option.empty
      var current: Option[ResultChunk] = Option.empty
      var errorMessage: Option[String] = Option.empty

      readNext()

      override def hasNext: Boolean = responses.hasNext && statistics.isEmpty && errorMessage.isEmpty

      override def next(): ResultChunk = {
        val result = current.get
        readNext()
        result
      }

      private def readNext(): Unit = {
        current = None
        do {
          responses.next() match {
            case Response.Resp.Result(result) =>
              current = Some(result)

            case Response.Resp.ResultHeader(_) =>
              errorMessage = Some(error("Duplicate header received"))

            case Response.Resp.Pong(_) =>
              errorMessage = Some(error("Unexpected TspPong response"))

            case Response.Resp.Heartbeat(time) =>
              heartbeat(time)

            case Response.Resp.Error(e) =>
              errorMessage = Some(error(e))

            case Response.Resp.ResultStatistics(stat) =>
              logger.fine(s"Got statistics $stat")
              statistics = Some(stat)

            case Response.Resp.Empty =>
          }
        } while (current.isEmpty && statistics.isEmpty && errorMessage.isEmpty && responses.hasNext)

        if (statistics.nonEmpty || errorMessage.nonEmpty) {
          channel.close()
          errorMessage.foreach { e =>
            throw new IllegalArgumentException(e)
          }
        }
      }

    }
  }

  override def close(): Unit = {
    channel.close()
  }

  private def createProtoPing(reqTime: Long): Request = {
    Request(Request.Req.Ping(Ping(
      reqTime,
      Some(Version(ProtocolVersion.value, BuildInfo.majorVersion, BuildInfo.minorVersion, BuildInfo.version))
    )))
  }

  private def extractProtoResult(header: ResultHeader, res: Iterator[ResultChunk]): Result = {
    val names = header.fields.map(_.name)
    val dataTypes = header.fields.map { resultField =>
      DataType.bySqlName(resultField.`type`)
    }

    val values = res.flatMap { row =>

      val v = dataTypes.zip(row.values).map { case (rt, bytes) =>

        if (bytes.isEmpty) {
          None
        } else {
          Some[Any](rt.readable.read(bytes.toByteArray))
        }
      }.toArray
      Some(v)
    }

    SimpleResult(names, dataTypes, values)
  }

  private def createProtoQuery(query: String, params: Map[Int, ParameterValue]): Request = {
    Request(
      Request.Req.SqlQuery(
        SqlQuery(query, params.map {
          case (i, v) => ParameterValue(i, createProtoValue(v))
        }.toSeq)
      )
    )
  }

  private def createProtoValue(value: ParameterValue): Value = {
    value match {
      case NumericValue(n) => Value(Value.Value.DecimalValue(n.toString()))
      case StringValue(s) => Value(Value.Value.TextValue(s))
      case t: TimestampValue => Value(Value.Value.TimeValue(t.millis))
    }
  }
}