package org.yupana.core

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging
import org.yupana.api.query._
import org.yupana.api.schema.{Dimension, ExternalLink}
import org.yupana.core.dao.{DictionaryProvider, TSReadingDao}
import org.yupana.core.model.{InternalQuery, InternalRow, InternalRowBuilder, KeyData}
import org.yupana.core.operations.Operations
import org.yupana.core.utils.ConditionUtils
import org.yupana.core.utils.metric.MetricQueryCollector

import scala.language.higherKinds

/**
  * Core of time series database processing pipeline.
  */
trait TsdbBase extends StrictLogging {

  /**
    * Type of collection used in this TSDB instance and the DAO. The default implementation uses Iterator as a collection type.
    * Spark based implementation uses RDD.
    */
  type Collection[_]

  /** MapReducible instance for Collection type */
  def mr: MapReducible[Collection]

  // TODO: it should work with different DAO Id types
  def dao: TSReadingDao[Collection, Long]

  def dictionaryProvider: DictionaryProvider

  /** Batch size for reading values from external links */
  val extractBatchSize: Int

  def dictionary(dimension: Dimension): Dictionary = dictionaryProvider.dictionary(dimension)

  def registerExternalLink(catalog: ExternalLink, catalogService: ExternalLinkService[_ <: ExternalLink]): Unit

  def linkService(catalog: ExternalLink): ExternalLinkService[_ <: ExternalLink]

  def prepareQuery: Query => Query

  def applyWindowFunctions(queryContext: QueryContext, keysAndValues: Collection[(KeyData, InternalRow)]): Collection[(KeyData, InternalRow)]

  def createMetricCollector(query: Query): MetricQueryCollector

  def finalizeQuery(value: Collection[Array[Option[Any]]], metricCollector: MetricQueryCollector): Collection[Array[Option[Any]]]

  protected implicit val operations: Operations = Operations

  def queryCollection(query: Query): (Collection[Array[Option[Any]]], QueryContext) = {
    logger.info(s"TSDB query with ${query.uuidLog} start: " + query)

    val metricQueryCollector = createMetricCollector(query)
    val queryContext = createContext(query, metricQueryCollector)
    (queryPipeline(queryContext, metricQueryCollector), queryContext)
  }

  def createContext(query: Query, metricCollector: MetricQueryCollector): QueryContext= {
    val prepared = prepareQuery(query)
    logger.debug(s"Prepared query is $prepared")

    val simplified = ConditionUtils.simplify(prepared.filter)

    val substitutedCondition = substituteLinks(simplified, metricCollector)
    logger.debug(s"Substituted condition: $substitutedCondition")

    val postCondition = ConditionUtils.split(substitutedCondition)(dao.isSupportedCondition)._2

    logger.debug(s"Post condition: $postCondition")

    QueryContext(prepared, substitutedCondition, postCondition)
  }

  /**
    * Query data extraction pipeline.
    *
    * - creates queries for DAO
    * - call DAO query to get [[Collection]] of rows
    * - fills the rows with external links values
    * - extract KeyData and ValueData
    * - apply value filters
    * - window function application
    * - apply aggregation: map, reduce, post-map
    * - post reduce arithmetics
    * - extract field values
    *
    * The pipeline is not responsible for limiting. This means that collection have to be lazy, to avoid extra
    * calculations if limit is defined.
    */
  def queryPipeline(
    queryContext: QueryContext,
    metricCollector: MetricQueryCollector
  ): Collection[Array[Option[Any]]] = {

    val daoExprs = queryContext.bottomExprs.collect {
      case e: DimensionExpr => e
      case e: MetricExpr[_] => e
      case TimeExpr => TimeExpr
    }

    val internalQuery = InternalQuery(queryContext.query.table, daoExprs.toSet, queryContext.condition)

    val rows = dao.query(internalQuery, new InternalRowBuilder(queryContext), metricCollector)

    val processedRows = new AtomicInteger(0)
    val processedDataPoints = new AtomicInteger(0)
    val resultRows = new AtomicInteger(0)

    val withExternalFields = mr.batchFlatMap(rows)(extractBatchSize, values => {
      val c = processedRows.incrementAndGet()
      if (c % 100000 == 0) logger.trace(s"${queryContext.query.uuidLog} -- Fetched $c tsd rows")
      readExternalLinks(queryContext, values)
    })

    val valuesEvaluated = mr.map(withExternalFields)(values => evaluateExpressions(queryContext, values, metricCollector))

    val keysAndValues = mr.map(valuesEvaluated)(valueData => new KeyData(queryContext, valueData.data) -> valueData)

    val keysAndValuesFiltered = queryContext.postCondition.map(c =>
      mr.filter(keysAndValues)(kv => ExpressionCalculator.evaluateCondition(c, queryContext, kv._2).getOrElse(false))
    ).getOrElse(keysAndValues)

    val isWindowFunctionPresent = metricCollector.windowFunctionsCheck.measure {
      queryContext.query.fields.exists(_.expr.isInstanceOf[WindowFunctionExpr])
    }

    val keysAndValuesWinFunc = if (isWindowFunctionPresent) {
      metricCollector.windowFunctions.measure {
        applyWindowFunctions(queryContext, keysAndValuesFiltered)
      }
    } else {
      keysAndValuesFiltered
    }

    val reduced = if (queryContext.query.groupBy.nonEmpty && !isWindowFunctionPresent) {
      val keysAndMappedValues = mr.map(keysAndValuesWinFunc) { case (key, values) =>
        key -> metricCollector.mapOperation.measure {
          val c = processedDataPoints.incrementAndGet()
          if (c % 100000 == 0) logger.trace(s"${queryContext.query.uuidLog} -- Extracted $c data points")

          applyMapOperation(queryContext, values)
        }
      }

      val r = mr.reduceByKey(keysAndMappedValues)((a, b) =>
        metricCollector.reduceOperation.measure {
          applyReduceOperation(queryContext, a, b)
        }
      )

      mr.map(r)(kv =>
        metricCollector.postMapOperation.measure {
          kv._1 -> applyPostMapOperation(queryContext, kv._2)
        })
    } else {
      keysAndValuesWinFunc
    }

    val calculated = mr.map(reduced) {
      case (k, v) => (k, evalExprsOnAggregatesAndWindows(queryContext, v))
    }

    val postFiltered = queryContext.query.postFilter.map(c =>
      metricCollector.postFilter.measure {
        mr.filter(calculated)(kv => ExpressionCalculator.evaluateCondition(c, queryContext, kv._2).getOrElse(false))
      }
    ).getOrElse(calculated)

    val limited = queryContext.query.limit.map(mr.limit(postFiltered)).getOrElse(postFiltered)

    val result = mr.map(limited) { case (_, valueData) =>
      metricCollector.collectResultRows.measure {
        val c = resultRows.incrementAndGet()
        val d = if (c <= 100000) 10000 else 100000
        if (c % d == 0) {
          logger.trace(s"${queryContext.query.uuidLog} -- Created $c result rows")
        }
        valueData.data
      }
    }

    finalizeQuery(result, metricCollector)
  }

  def readExternalLinks(queryContext: QueryContext, rows: Seq[InternalRow]): Seq[InternalRow] = {
    queryContext.linkExprs.groupBy(_.link).foreach { case (c, exprs) =>
      val catalog = linkService(c)
      catalog.setLinkedValues(queryContext.exprsIndex, rows, exprs.toSet)
    }

    rows
  }

  def evaluateExpressions(queryContext: QueryContext,
                          valueData: InternalRow,
                          metricCollector: MetricQueryCollector): InternalRow = {
    metricCollector.extractDataComputation.measure {
      queryContext.bottomExprs.foreach { expr =>
        valueData.set(
          queryContext.exprsIndex(expr),
          ExpressionCalculator.evaluateExpression(expr, queryContext, valueData)
        )
      }

      queryContext.topRowExprs.foreach { expr =>
        valueData.set(
          queryContext.exprsIndex(expr),
          ExpressionCalculator.evaluateExpression(expr, queryContext, valueData)
        )
      }
    }

    valueData
  }

  def applyMapOperation(queryContext: QueryContext, values: InternalRow): InternalRow = {
    queryContext.aggregateExprs.foreach { ae =>
      val oldValue = values.get[ae.expr.Out](queryContext, ae.expr)
      val newValue = oldValue.map(v => ae.aggregation.map(v))
      values.set(queryContext, ae, newValue)
    }
    values
  }

  def applyReduceOperation(queryContext: QueryContext, a: InternalRow, b: InternalRow): InternalRow = {
    val reduced = a.copy
    queryContext.aggregateExprs.foreach { aggExpr =>
      val agg = aggExpr.aggregation
      val aValue = a.get[agg.Interim](queryContext, aggExpr)
      val bValue = b.get[agg.Interim](queryContext, aggExpr)

      val newValue = aValue match {
        case Some(av)  =>
          bValue.map(bv => agg.reduce(av, bv)).orElse(aValue)
        case None => bValue
      }
      reduced.set(queryContext, aggExpr, newValue)
    }

    reduced
  }

  def applyPostMapOperation(queryContext: QueryContext, data: InternalRow): InternalRow = {

    queryContext.aggregateExprs.foreach { aggExpr =>
      val agg = aggExpr.aggregation
      val newValue = data.get[agg.Interim](queryContext, aggExpr).map(agg.postMap)
        data.set(queryContext, aggExpr, newValue)
    }
    data
  }

  def evalExprsOnAggregatesAndWindows(queryContext: QueryContext, data: InternalRow): InternalRow = {
    queryContext.exprsOnAggregatesAndWindows.foreach { e =>
      val nullWindowExpressionsExists = e.flatten.exists {
        case w: WindowFunctionExpr => data.get(queryContext, w).isEmpty
        case _ => false
      }
      val evaluationResult = if (nullWindowExpressionsExists) None
                             else ExpressionCalculator.evaluateExpression(e, queryContext, data)
      data.set(queryContext, e, evaluationResult)
    }
    data
  }

  def substituteLinks(condition: Condition, metricCollector: MetricQueryCollector): Condition = {
    val linkServices = condition.exprs.flatMap(_.flatten).collect {
      case LinkExpr(c, _) => linkService(c)
    }

    val substituted = linkServices.map(service =>
      metricCollector.dynamicMetric(s"create_queries.link.${service.externalLink.linkName}").measure {
        service.condition(condition)
      }
    )

    if (substituted.nonEmpty) {
      val merged = substituted.reduceLeft(ConditionUtils.merge)
      ConditionUtils.split(merged)(c => linkServices.exists(_.isSupportedCondition(c)))._2
    } else {
      condition
    }
  }
}
