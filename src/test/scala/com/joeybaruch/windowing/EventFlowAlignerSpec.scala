package com.joeybaruch.windowing

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import com.joeybaruch.TestUtils._
import com.joeybaruch.datamodel.LogEvent.SentinelEOFEvent
import com.joeybaruch.datamodel.{LogEvent, LogEventImpl}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

class EventFlowAlignerSpec extends AnyFlatSpec with Matchers with BeforeAndAfter {

  implicit val system: ActorSystem = ActorSystem()
  var config: Config = _
  var allowedDelay: Long = _
  var minUnalowedDelay: Long = _

  before {
    config = ConfigFactory.load("test-conf")
    allowedDelay = config.getInt("windowing.late-data.delay-allowed.seconds").seconds.toSeconds
    minUnalowedDelay = allowedDelay + 1
  }

  behavior of "timeAligned"
  it should "output events in order" in {
    val unOrderedInputData = addTriggeringEvent(unorderedEventSequenceWith3SecMaxLateness)
    val orderedExpectedOutput = unorderedEventSequenceWith3SecMaxLateness.sortBy(_.timestamp)

    val flowUnderTest = EventFlowAligner.timeAligned(config)
    val future = Source(unOrderedInputData).via(flowUnderTest).runWith(Sink.seq)
    val result = Await.result(future, 3.seconds)

    result should contain theSameElementsInOrderAs orderedExpectedOutput
  }

  it should "discard late data" in {
    var eventSequenceWithLateData = orderedEventSequence :+ addOffsetToEvent(orderedEventSequence.last, minUnalowedDelay)
    eventSequenceWithLateData = addTriggeringEvent(eventSequenceWithLateData)

    val flowUnderTest = EventFlowAligner.timeAligned(config)
    val future = Source(eventSequenceWithLateData).via(flowUnderTest).runWith(Sink.seq)

    val result = Await.result(future, 3.seconds)
    result should contain theSameElementsInOrderAs orderedEventSequence
  }


  def addOffsetToEvent(event: LogEvent, offset: Long) = {
    val timesamp = event.timestamp - offset
    copyEventWithNewTimestamp(event, timesamp)
  }

  private def copyEventWithNewTimestamp(event: LogEvent, timesamp: Long) = {
    LogEventImpl(event.host, event.rfc931, event.authUser, timesamp, event.request, event.status, event.bytes)
  }

  def addTriggeringEvent(seq: Seq[LogEvent]): Seq[LogEvent] = seq :+ SentinelEOFEvent

  lazy val orderedEventSequence = Seq(logEvent1, logEvent2, logEvent3, logEvent4, logEvent5)
  lazy val unorderedEventSequenceWith3SecMaxLateness = Seq(logEvent3, logEvent1, logEvent4, logEvent5, logEvent2)

}
