package org.apache.spark.sql.streaming

import org.apache.spark.rdd.{EmptyRDD, RDD}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{StreamingContextState, Duration, Time}

case class WindowPhysicalPlan(
                               windowDuration: Duration,
                               slideDuration: Option[Duration],
                               child: SparkPlan)
  extends execution.UnaryNode with StreamPlan {

  override def doExecute(): RDD[InternalRow] = {
    import StreamHelper._
    assert(validTime != null)
    // adhoc sql if window clause defined
    /* val ssc = SnappyStreamingContext.getActive().get
    if(ssc.getState() == StreamingContextState.ACTIVE) {
      stream.initializeAfterContextStart(ssc.graph.zeroTime)
      stream.register()
    } */
    stream.getOrCompute(validTime)
      .getOrElse(new EmptyRDD[InternalRow](sparkContext))
  }

  @transient private val wrappedStream =
    new DStream[InternalRow](SnappyStreamingContext.getActive().get){
      override def dependencies = parentStreams.toList

      override def slideDuration: Duration =
        parentStreams.head.slideDuration

      override def compute(validTime: Time): Option[RDD[InternalRow]] =
        Some(child.execute())

      private lazy val parentStreams = {
        def traverse(plan: SparkPlan): Seq[DStream[InternalRow]] = plan match {
          case x: StreamPlan => x.stream :: Nil
          // case LogicalRelation(x: StreamPlan, _) => x.stream :: Nil
          case _ => plan.children.flatMap(traverse)
        }
        val streams = traverse(child)
        streams
      }
    }

  @transient val stream = slideDuration.map(
    wrappedStream.window(windowDuration, _))
    .getOrElse(wrappedStream.window(windowDuration))

  override def output: Seq[Attribute] = child.output
}
