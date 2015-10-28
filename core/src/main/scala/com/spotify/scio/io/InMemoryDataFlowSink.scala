package com.spotify.scio.io

import java.lang.Iterable

import com.google.cloud.dataflow.sdk.coders.Coder
import com.google.cloud.dataflow.sdk.io.Sink
import com.google.cloud.dataflow.sdk.io.Sink.{WriteOperation, Writer}
import com.google.cloud.dataflow.sdk.options.PipelineOptions
import com.google.cloud.dataflow.sdk.runners.DirectPipelineRunner
import com.google.cloud.dataflow.sdk.util.CoderUtils
import com.spotify.scio.coders.KryoAtomicCoder

import scala.collection.JavaConverters._
import scala.collection.mutable.{ListBuffer, Map => MMap}

private[scio] class InMemoryDataFlowSink[T](private val id: String) extends Sink[T] {
  override def createWriteOperation(options: PipelineOptions): WriteOperation[T, ListBuffer[Array[Byte]]] =
    new InMemoryWriteOperation(this, id)

  override def validate(options: PipelineOptions): Unit = {
    require(classOf[DirectPipelineRunner] isAssignableFrom  options.getRunner)
  }
}

private class InMemoryWriteOperation[T](private val sink: Sink[T], private val id: String)
  extends WriteOperation[T, ListBuffer[Array[Byte]]] {

  private val coder: Coder[T] = KryoAtomicCoder[T]

  override def finalize(writerResults: Iterable[ListBuffer[Array[Byte]]], options: PipelineOptions): Unit =
    writerResults.asScala.foreach { lb =>
      InMemorySinkManager.put(id, lb.map(CoderUtils.decodeFromByteArray(coder, _)))
    }
  override def initialize(options: PipelineOptions): Unit = {}
  override def getSink: Sink[T] = sink
  override def createWriter(options: PipelineOptions): Writer[T, ListBuffer[Array[Byte]]] = new InMemoryWriter(this)

  override def getWriterResultCoder: Coder[ListBuffer[Array[Byte]]] = KryoAtomicCoder[ListBuffer[Array[Byte]]]

}

private class InMemoryWriter[T](private val writeOperation: WriteOperation[T, ListBuffer[Array[Byte]]])
  extends Writer[T, ListBuffer[Array[Byte]]] {

  private val buffer: ListBuffer[Array[Byte]] = ListBuffer.empty
  private val coder: Coder[T] = KryoAtomicCoder[T]

  override def getWriteOperation: WriteOperation[T, ListBuffer[Array[Byte]]] = writeOperation
  override def write(value: T): Unit = buffer.append(CoderUtils.encodeToByteArray(coder, value))
  override def close(): ListBuffer[Array[Byte]] = buffer
  override def open(uId: String): Unit = {}

}

private[scio] object InMemorySinkManager {

  private val cache: MMap[String, ListBuffer[Any]] = MMap.empty

  def put[T](id: String, value: TraversableOnce[T]): Unit = {
    if (!cache.contains(id)) {
      cache.put(id, ListBuffer.empty)
    }
    cache(id).appendAll(value)
  }

  def put[T](id: String, value: T): Unit =
    if (!cache.contains(id)) {
      cache.put(id, ListBuffer(value))
    } else {
      cache(id).append(value)
    }

  def get[T](id: String): ListBuffer[T] = cache(id).asInstanceOf[ListBuffer[T]]

}