package org.yupana.externallinks.items

import org.yupana.api.Time
import org.yupana.api.query._
import org.yupana.core.model.InternalRow
import org.yupana.core.utils.{CollectionUtils, TimeBoundedCondition}
import org.yupana.core.{ExternalLinkService, TsdbBase}
import org.yupana.schema.{Dimensions, Tables}
import org.yupana.schema.externallinks.{ItemsInvertedIndex, RelatedItemsCatalog}

class RelatedItemsCatalogImpl(tsdb: TsdbBase,
                              override val externalLink: RelatedItemsCatalog)
  extends ExternalLinkService[RelatedItemsCatalog] {

  import org.yupana.api.query.syntax.All._

  def includeCondition(fieldsValues: Seq[(String, Set[String])], from: Long, to: Long): Condition = {
    val info = createFilter(fieldsValues).map(c => getTransactions(c, from, to).toSet)
    val tuples = CollectionUtils.intersectAll(info)
    in(tuple(time, dimension(Dimensions.KKM_ID_TAG)), tuples)
  }

  def excludeCondition(fieldsValues: Seq[(String, Set[String])], from: Long, to: Long): Condition = {
    val info = createFilter(fieldsValues).map(c => getTransactions(c, from, to).toSet)
    val tuples = info.fold(Set.empty)(_ union _)
    notIn(tuple(time, dimension(Dimensions.KKM_ID_TAG)), tuples)
  }

  override def condition(condition: Condition): Condition = {
    val tbcs = TimeBoundedCondition(condition)

    val r = tbcs.map { tbc =>
      val from = tbc.from.getOrElse(throw new IllegalArgumentException(s"FROM time is not defined for condition ${tbc.toCondition}"))
      val to = tbc.to.getOrElse(throw new IllegalArgumentException(s"TO time is not defined for condition ${tbc.toCondition}"))

      val (includeValues, excludeValues, other) = ExternalLinkService.extractCatalogFields(tbc, externalLink.linkName)

      // TODO: Here we can take KKM related conditions from other, to speed up transactions request

      val include = if (includeValues.nonEmpty) {
        includeCondition(includeValues, from, to)
      } else {
        EmptyCondition
      }

      val exclude = if (excludeValues.nonEmpty) {
        excludeCondition(excludeValues, from, to)
      } else {
        EmptyCondition
      }

      TimeBoundedCondition(tbc.from, tbc.to, include :: exclude :: other)
    }

    TimeBoundedCondition.merge(r).toCondition
  }

  protected def createFilter(fieldValues: Seq[(String, Set[String])]): Seq[Condition] = {
    fieldValues.map { case (f, vs) => createFilter(f, vs) }
  }

  protected def createFilter(field: String, values: Set[String]): Condition = {
    field match {
      case externalLink.ITEM_FIELD => in(dimension(Dimensions.ITEM_TAG), values)
      case externalLink.PHRASE_FIELDS => in(link(ItemsInvertedIndex, ItemsInvertedIndex.PHRASE_FIELD), values)
      case f  => throw new IllegalArgumentException(s"Unsupported field $f")
    }
  }

  private def getTransactions(filter: Condition, from: Long, to: Long): Seq[(Time, String)] = {
    val q = Query(
      table = Tables.itemsKkmTable,
      from = const(Time(from)),
      to = const(Time(to)),
      fields = Seq(dimension(Dimensions.KKM_ID_TAG).toField, time.toField),
      filter = filter
    )

    val result = tsdb.query(q)

    val timeIdx = result.queryContext.exprsIndex(time)
    val kkmIdIdx = result.queryContext.exprsIndex(dimension(Dimensions.KKM_ID_TAG))

    val extracted = tsdb.mr.flatMap(result.rows) { a =>
      for {
        kkmId <- a(kkmIdIdx)
        time <- a(timeIdx)
      } yield Set((time.asInstanceOf[Time], kkmId.asInstanceOf[String]))
    }

    tsdb.mr.fold(extracted)(Set.empty)(_ ++ _).toSeq
  }

  override def setLinkedValues(exprIndex: scala.collection.Map[Expression, Int], valueData: Seq[InternalRow], exprs: Set[LinkExpr]): Unit = {
    // may be throw exception here?
  }
}
