package org.yupana.core

import com.typesafe.scalalogging.StrictLogging
import org.yupana.api.Time
import org.yupana.api.query._
import org.yupana.api.schema.{ExternalLink, Table}
import org.yupana.core.dao.{DictionaryProvider, TSDao}
import org.yupana.core.model.{InternalRow, KeyData}
import org.yupana.core.utils.metric.{ConsoleMetricQueryCollector, MetricQueryCollector, NoMetricCollector}

import scala.collection.AbstractIterator

// NOTE: dao is TSDaoHBase because TSDB has put and rollup related method.  Possible it better to not have them here
class TSDB(override val dao: TSDao[Iterator, Long],
           override val dictionaryProvider: DictionaryProvider,
           override val prepareQuery: Query => Query,
           override val extractBatchSize: Int = 10000,
           collectMetrics: Boolean = false
          )
  extends TsdbBase with StrictLogging {

  private var catalogs = Map.empty[ExternalLink, ExternalLinkService[_ <: ExternalLink]]

  override type Collection[X] = Iterator[X]

  override val mr: MapReducible[Iterator] = MapReducible.iteratorMR

  def registerExternalLink(catalog: ExternalLink, catalogService: ExternalLinkService[_ <: ExternalLink]): Unit = {
    catalogs += (catalog -> catalogService)
  }

  def put(dataPoints: Seq[DataPoint]): Unit = {
    loadTagsIds(dataPoints)
    dao.put(dataPoints)
  }

  override def createMetricCollector(query: Query): MetricQueryCollector = {
    if (collectMetrics) new ConsoleMetricQueryCollector(query, "query") else NoMetricCollector
  }

  override def finalizeQuery(data: Iterator[Array[Option[Any]]], metricCollector: MetricQueryCollector): Iterator[Array[Option[Any]]] = {
    new AbstractIterator[Array[Option[Any]]] {
      var hasEnded = false

      override def hasNext: Boolean = {
        val n = data.hasNext
        if (!n && !hasEnded) {
          hasEnded = true
          metricCollector.finish()
          // TODO: Get statistics somehow
          //          logger.trace(s"${queryContext.query.uuidLog}, End query. Processed rows: $processedRows, " +
          //            s"dataPoints: $processedDataPoints, resultRows: $resultRows, " +
          //            s"time: ${System.currentTimeMillis() - startProcessingTime}")
        }
        n
      }

      override def next(): Array[Option[Any]] = data.next()
    }
  }

  def query(query: Query): Result = {
    logger.info("TSDB query start: " + query)
    val metricCollector = createMetricCollector(query)
    val queryContext = createContext(query, metricCollector)
    new TsdbServerResult(queryContext, queryPipeline(queryContext, metricCollector))
  }

  override def applyWindowFunctions(queryContext: QueryContext, keysAndValues: Iterator[(KeyData, InternalRow)]): Iterator[(KeyData, InternalRow)] = {
    val seq = keysAndValues.zipWithIndex.toList

    val grouped = seq
      .groupBy(_._1._1)
      .map { case (keyData, group) =>

        val (values, rowNumbers) = group.map { case ((_, valuedata), rowNumber) => (valuedata, rowNumber) }
          .toArray
          .sortBy(_._1.get[Time](queryContext, TimeExpr))
          .unzip

        keyData -> ((values, rowNumbers.zipWithIndex.toMap))
      }

    val winFieldsAndGroupValues = queryContext.query.fields.map(_.expr).collect {
      case winFuncExpr: WindowFunctionExpr =>
        val values = grouped.mapValues { case (vs, rowNumIndex) =>
           val funcValues = vs.map(_.get[winFuncExpr.expr.Out](queryContext, winFuncExpr.expr))
          (funcValues, rowNumIndex)
        }
        winFuncExpr -> values
    }

    seq.map { case ((keyData, valueData), rowNumber) =>
      winFieldsAndGroupValues.foreach { case (winFuncExpr, groups) =>
        val (group, rowIndex) = groups(keyData)
        rowIndex.get(rowNumber).map { index =>
          val value = winFuncExpr.operation(group.asInstanceOf[Array[Option[winFuncExpr.expr.Out]]], index)
          valueData.set(queryContext, winFuncExpr, value)
        }
      }
      keyData -> valueData
    }.toIterator
  }

  def putRollupStatuses(statuses: Seq[(Long, String)], table: Table): Unit = {
    if (statuses.nonEmpty) {
      dao.putRollupStatuses(statuses, table)
    }
  }

  def getRollupSpecialField(fieldName: String, table: Table): Option[Long] = {
    dao.getRollupSpecialField(fieldName, table)
  }

  private def loadTagsIds(dataPoints: Seq[DataPoint]): Unit = {
    dataPoints.groupBy(_.table).foreach { case (table, points) =>
      table.dimensionSeq.map { tag =>
        val values = points.flatMap { dp =>
          dp.dimensions.get(tag).filter(_.trim.nonEmpty)
        }
        dictionary(tag).findIdsByValues(values.toSet)
      }
    }
  }

  override def linkService(catalog: ExternalLink): ExternalLinkService[_ <: ExternalLink] = {
    catalogs.getOrElse(catalog, throw new Exception(s"Can't find catalog ${catalog.linkName}: ${catalog.fieldsNames}"))
  }
}

