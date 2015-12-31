package org.apache.spark.sql

import org.apache.spark.sql.aqp.DefaultPlanner
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.planning.{ExtractEquiJoinKeys, PhysicalOperation}
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.columnar.InMemoryAppendableColumnarTableScan
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.streaming._

/**
 * This trait is an extension to SparkPlanner and introduces number of enhancements specific to Snappy.
 */
private[sql] trait SnappyStrategies {

  self: DefaultPlanner =>

  //val snappyContext: SnappyContext

  object SnappyStrategies extends Strategy {

    /* def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case s@StratifiedSample(options, child, _) =>
        s.getExecution(planLater(child)) :: Nil
      case PhysicalOperation(projectList, filters,
      mem: columnar.InMemoryAppendableRelation) =>
        pruneFilterProject(
          projectList,
          filters,
          identity[Seq[Expression]], // All filters still need to be evaluated
          InMemoryAppendableColumnarTableScan(_, filters, mem)) :: Nil
      case _ => Nil
    }*/


    def apply(plan: LogicalPlan): Seq[SparkPlan] = {
      val x: PartialFunction[LogicalPlan, Seq[SparkPlan]] = {
        case PhysicalOperation(projectList, filters,
        mem: columnar.InMemoryAppendableRelation) =>
          pruneFilterProject(
            projectList,
            filters,
            identity[Seq[Expression]], // All filters still need to be evaluated
            InMemoryAppendableColumnarTableScan(_, filters, mem)) :: Nil
      }

      x.orElse(sampleSnappyCase)(plan)

    }
  }

  /** Stream related strategies to map stream specific logical plan to physical plan */
  object StreamQueryStrategy extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case WindowLogicalPlan(d, s, l@LogicalRelation(t: StreamPlan, _)) =>
        val child = PhysicalDStreamPlan(l.output, t.stream)
        WindowPhysicalPlan(d, s, child) :: Nil
      case _ => Nil
    }
  }

  /** Stream related strategies DDL stratgies */
  case class StreamDDLStrategy(sampleTablePopulation: Option[(SQLContext) => Unit],
      sampleStreamCase: PartialFunction[LogicalPlan,
          Seq[SparkPlan]]) extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = {

      val x1: PartialFunction[LogicalPlan, Seq[SparkPlan]] = {
        case CreateStreamTable(streamName, userColumns, provider, options) =>
          ExecutedCommand(
            CreateStreamTableCmd(streamName, userColumns, provider, options)) :: Nil
      }

      val x2: PartialFunction[LogicalPlan, Seq[SparkPlan]] = {
        case StreamOperationsLogicalPlan(action, batchInterval) =>
          ExecutedCommand(
            SnappyStreamingActionsCommand(action, batchInterval, sampleTablePopulation)) :: Nil

      }
      x1.orElse(x2).orElse(sampleStreamCase)(plan)
    }
  }

    object LocalJoinStrategies extends Strategy {
      def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
        case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, left, CanLocalJoin(right)) =>
          makeLocalHashJoin(leftKeys, rightKeys, left, right, condition, joins.BuildRight)
        case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, CanLocalJoin(left), right) =>
          makeLocalHashJoin(leftKeys, rightKeys, left, right, condition, joins.BuildLeft)
        case _ => Nil
      }
    }

    object CanLocalJoin {
      def unapply(plan: LogicalPlan): Option[LogicalPlan] = plan match {
        case PhysicalOperation(projects, filters,
        l@LogicalRelation(t: PartitionedDataSourceScan, _)) =>
          if (t.numPartitions == 1) Some(plan) else None
        case _ => None
      }
    }

  private[this] def makeLocalHashJoin(
                                       leftKeys: Seq[Expression],
                                       rightKeys: Seq[Expression],
                                       left: LogicalPlan,
                                       right: LogicalPlan,
                                       condition: Option[Expression],
                                       side: joins.BuildSide): Seq[SparkPlan] = {

    val localHashJoin = execution.joins.LocalJoin(
      leftKeys, rightKeys, side, planLater(left), planLater(right))
    condition.map(Filter(_, localHashJoin)).getOrElse(localHashJoin) :: Nil
  }

}
