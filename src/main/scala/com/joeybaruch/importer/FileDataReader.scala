package com.joeybaruch.importer

import java.io.{File, FileInputStream}
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink, StreamConverters}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.joeybaruch.datamodel.{LogEvent, LogLine, Reading, ValidReading}
import com.joeybaruch.repository.ReadingRepository
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.util.Properties

class FileDataReader(config: Config, logLineParser: LogLineParser, readingRepository: ReadingRepository)
                    (implicit system: ActorSystem) extends LazyLogging {

  import system.dispatcher

  private val importDirectory = Paths.get(config.getString("importer.import-directory")).toFile
  private val concurrentFiles = config.getInt("importer.concurrent-files")
  private val concurrentWrites = config.getInt("importer.concurrent-writes")
  private val nonIOParallelism = config.getInt("importer.non-io-parallelism")


  def parseLine(filePath: String)(line: String): Future[LogLine] = Future(logLineParser.parse(line))

  private val osLineSeparator = Properties.lineSeparator
  val lineDelimiter: Flow[ByteString, ByteString, NotUsed] =
    Framing.delimiter(ByteString(osLineSeparator), 128, allowTruncation = true)

  val parseFile: Flow[File, LogLine, NotUsed] =
    Flow[File].flatMapConcat { file =>
      val fileInputStream = new FileInputStream(file)

      StreamConverters.fromInputStream(() => fileInputStream)
        .via(lineDelimiter)
        .map(_.utf8String)
        //todo: separate first element and assert is a Headers type
        .mapAsync(parallelism = nonIOParallelism)(parseLine(file.getPath))
    }

  val computeOneSecondStats: Flow[LogLine, LogEvent, NotUsed] =
    Flow[LogLine].grouped(2).mapAsyncUnordered(parallelism = nonIOParallelism) { readings =>
      Future {
        val validReadings = readings.collect { case r: ValidReading => r }
        val average = if (validReadings.nonEmpty) validReadings.map(_.value).sum / validReadings.size else -1
        ValidReading(readings.head.id, average)
      }
    }

  val storeReadings: Sink[ValidReading, Future[Done]] =
    Flow[ValidReading]
      .mapAsyncUnordered(concurrentWrites)(readingRepository.save)
      .toMat(Sink.ignore)(Keep.right)

  val processSingleFile: Flow[File, LogLine, NotUsed] =
    Flow[File]
      .via(parseFile)
  //      .via(computeAverage)

  //  def importFromFiles = {
  //    implicit val materializer = ActorMaterializer()
  //
  //    val files = importDirectory.listFiles.toList
  //    logger.info(s"Starting import of ${files.size} files from ${importDirectory.getPath}")
  //
  //    val startTime = System.currentTimeMillis()
  //
  //    val balancer = GraphDSL.create() { implicit builder =>
  //      import GraphDSL.Implicits._
  //
  //      val balance = builder.add(Balance[File](concurrentFiles))
  //      val merge = builder.add(Merge[ValidReading](concurrentFiles))
  //
  //      (1 to concurrentFiles).foreach { _ =>
  //        balance ~> processSingleFile ~> merge
  //      }
  //
  //      FlowShape(balance.in, merge.out)
  //    }
  //
  //    Source(files)
  //      .via(balancer)
  //      .withAttributes(ActorAttributes.supervisionStrategy { e =>
  //        logger.error("Exception thrown during stream processing", e)
  //        Supervision.Resume
  //      })
  //      .runWith(storeReadings)
  //      .andThen {
  //        case Success(_) =>
  //          val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
  //          logger.info(s"Import finished in ${elapsedTime}s")
  //        case Failure(e) => logger.error("Import failed", e)
  //      }
  //  }
}
