package com.twitter.summingbird.storm.spout

import backtype.storm.spout.SpoutOutputCollector
import backtype.storm.task.TopologyContext
import backtype.storm.topology.{ IRichSpout, OutputFieldsDeclarer }
import backtype.storm.tuple.Fields
import com.twitter.algebird.Semigroup
import com.twitter.summingbird.online.Externalizer
import com.twitter.summingbird.online.executor.KeyValueShards
import com.twitter.summingbird.online.option.SummerBuilder
import com.twitter.summingbird.storm.Constants._
import com.twitter.tormenta.spout.SpoutProxy
import java.util
import java.util.{ List => JList }
import scala.collection.mutable.{ MutableList => MList }
import com.twitter.summingbird.storm.collector.{ AggregatorOutputCollector, TransformingOutputCollector }

/**
 * This is a spout used when the spout is being followed by summer.
 * It uses a AggregatorOutputCollector on open.
 */

class KeyValueSpout[K, V: Semigroup](val in: IRichSpout, summerBuilder: SummerBuilder, summerShards: KeyValueShards, @transient callOnOpen: (TopologyContext) => Unit) extends SpoutProxy {

  private var adapterCollector: AggregatorOutputCollector[K, V] = _
  val lockedFn = Externalizer(callOnOpen)
  var lastDump = System.currentTimeMillis()

  override def declareOutputFields(declarer: OutputFieldsDeclarer) = {
    declarer.declare(new Fields(AGG_KEY, AGG_VALUE))
  }

  override def open(conf: util.Map[_, _],
    topologyContext: TopologyContext,
    outputCollector: SpoutOutputCollector): Unit = {
    adapterCollector = new AggregatorOutputCollector(outputCollector, _.get(0).asInstanceOf[JList[AnyRef]], summerBuilder, summerShards)
    lockedFn.get(topologyContext)
    in.open(conf, topologyContext, adapterCollector)
  }

  override def nextTuple(): Unit = {
    if (System.currentTimeMillis() - lastDump > 1000) {
      adapterCollector.timerFlush()
      lastDump = System.currentTimeMillis()
    }
    in.nextTuple()
  }

  override def ack(msgId: Object): Unit = {
    val msgIds = convertToList(msgId)
    msgIds.foreach { super.ack(_) }
  }

  override def fail(msgId: Object): Unit = {
    val msgIds = convertToList(msgId)
    msgIds.foreach { super.fail(_) }
  }

  def convertToList(msgId: Object): MList[Object] = {
    msgId match {
      case Some(s) => s.asInstanceOf[MList[Object]]
      case None => MList[Object]()
    }
  }

  override protected def self: IRichSpout = in
}