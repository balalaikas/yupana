/*
 * Copyright 2019 Rusexpertiza LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.yupana.externallinks

import org.yupana.api.Time
import org.yupana.api.query.Expression.Condition
import org.yupana.api.query.{ ConstantExpr, DimensionExpr, Expression, InExpr, LinkExpr, NotInExpr, TimeExpr }
import org.yupana.api.schema.ExternalLink
import org.yupana.core.model.InternalRow
import org.yupana.core.utils.ConditionMatchers.{ Equ, Neq }
import org.yupana.core.utils.{ CollectionUtils, Table, TimeBoundedCondition }
import org.yupana.externallinks.ExternalLinkUtils.updateRow

object ExternalLinkUtils {
  def extractCatalogFields(
      simpleCondition: TimeBoundedCondition,
      linkName: String
  ): (List[(String, Set[String])], List[(String, Set[String])], List[Condition]) = {
    simpleCondition.conditions.foldLeft(
      (List.empty[(String, Set[String])], List.empty[(String, Set[String])], List.empty[Condition])
    ) {
      case ((cat, neg, oth), cond) =>
        cond match {
          case Equ(LinkExpr(c, field), ConstantExpr(v: String)) if c.linkName == linkName =>
            ((field, Set(v)) :: cat, neg, oth)

          case InExpr(LinkExpr(c, field), cs) if c.linkName == linkName =>
            ((field, cs.asInstanceOf[Set[String]]) :: cat, neg, oth)

          case Neq(LinkExpr(c, field), ConstantExpr(v: String)) if c.linkName == linkName =>
            (cat, (field, Set(v)) :: neg, oth)

          case NotInExpr(LinkExpr(c, field), cs) if c.linkName == linkName =>
            (cat, (field, cs.asInstanceOf[Set[String]]) :: neg, oth)

          case _ => (cat, neg, cond :: oth)
        }
    }
  }

  def transformCondition(
      linkName: String,
      condition: Condition,
      includeCondition: Seq[(String, Set[String])] => Condition,
      excludeCondition: Seq[(String, Set[String])] => Condition
  ): Condition = {
    val tbcs = TimeBoundedCondition(condition)

    val r = tbcs.map { tbc =>
      val (includeValues, excludeValues, other) = extractCatalogFields(tbc, linkName)

      val include = if (includeValues.nonEmpty) {
        includeCondition(includeValues)
      } else {
        ConstantExpr(true)
      }

      val exclude = if (excludeValues.nonEmpty) {
        excludeCondition(excludeValues)
      } else {
        ConstantExpr(true)
      }

      TimeBoundedCondition(tbc.from, tbc.to, include :: exclude :: other)
    }

    TimeBoundedCondition.merge(r).toCondition
  }

  def setLinkedValues[R](
      externalLink: ExternalLink.Aux[R],
      exprIndex: scala.collection.Map[Expression, Int],
      rows: Seq[InternalRow],
      linkExprs: Set[LinkExpr],
      fieldValuesForDimValues: (Set[String], Set[R]) => Table[R, String, String]
  ): Unit = {
    val dimExprIdx = exprIndex(DimensionExpr(externalLink.dimension))
    val fields = linkExprs.map(_.linkField)
    val dimValues = rows.flatMap(r => r.get[R](dimExprIdx)).toSet

    //val rowsByDimValues = rows.groupBy(r => r.get[String](dimExprIdx).getOrElse(""))
    val allFieldsValues = fieldValuesForDimValues(fields, dimValues)
    /*updateRows2(
      rowsByDimValues,
      exprIndex,
      allFieldValues.asInstanceOf[SparseTable[String, String, String]].values,
      linkExprs.map(e => e.linkField -> e).toMap
    )*/
    val linkExprsMap = linkExprs.map(e => e.linkField -> e).toMap

    rows.foreach { row =>
      row.get[R](dimExprIdx).foreach { dimValue =>
        allFieldsValues.row(dimValue).foreach {
          case (field, value) => updateRow(row, linkExprsMap, exprIndex, field, value)
        }
      }
    }
  }

  def setLinkedValuesTimeSensitive[R](
      externalLink: ExternalLink.Aux[R],
      exprIndex: scala.collection.Map[Expression, Int],
      rows: Seq[InternalRow],
      linkExprs: Set[LinkExpr],
      fieldValuesForDimValuesAndTimes: (Set[String], Set[(R, Time)]) => Table[(R, Time), String, String]
  ): Unit = {
    val dimExprIdx = exprIndex(DimensionExpr(externalLink.dimension))
    val timeExprIdx = exprIndex(TimeExpr)
    val fields = linkExprs.map(_.linkField)
    def extractDimValueWithTime(r: InternalRow): Option[(R, Time)] = {
      for {
        d <- r.get[R](dimExprIdx)
        t <- r.get[Time](timeExprIdx)
      } yield (d, t)
    }
    val dimValuesWithTimes = rows.flatMap(extractDimValueWithTime)
    val allFieldsValues = fieldValuesForDimValuesAndTimes(fields, dimValuesWithTimes.toSet)
    val linkExprsMap = linkExprs.map(e => e.linkField -> e).toMap
    rows.foreach { row =>
      extractDimValueWithTime(row).foreach { dimValueAtTime =>
        allFieldsValues.row(dimValueAtTime).foreach {
          case (field, value) => updateRow(row, linkExprsMap, exprIndex, field, value)
        }
      }
    }
  }

  def updateRow(
      row: InternalRow,
      linkExprsMap: Map[String, LinkExpr],
      exprIndex: scala.collection.Map[Expression, Int],
      field: String,
      value: String
  ): Unit = {
    val linkExpr = linkExprsMap(field)
    if (value != null && exprIndex.contains(linkExpr)) {
      row.set(exprIndex, linkExpr, Some(value))
    }
  }

  // Optimization candidate.
  // Idea is to keep dimValue -> Seq[InternalRow] map instead of extracting dimValue from each row twice
  // see 1. val dimValues = rows.flatMap(r => r.get[String](dimExprIdx)).toSet in setLinkedValues
  // and 2. row.get[String](dimExprIdx).foreach { dimValue => in updateRows
  // Shows good perforance boost in case when we have long sequences Seq[InternalRow]...
  // But degrades in worst case, if we have one row per dimValue.
  // See InternallRow's generation in benchmark state setup .set(dimExpr, Some((i - (i % 100)).toString))
  // One dimValue per 100 rows is good, one per one is bad for this approach.
  private def updateRows2(
      rowsByDims: Map[String, Seq[InternalRow]],
      exprIndex: scala.collection.Map[Expression, Int],
      allFieldsValues: Map[String, Map[String, String]],
      linkExprs: Map[String, LinkExpr]
  ): Unit = {
    allFieldsValues foreach {
      case (dimValue, fieldValues) =>
        rowsByDims(dimValue).foreach { row =>
          fieldValues foreach {
            case (field, value) =>
              val linkExpr = linkExprs(field)
              if (value != null && exprIndex.contains(linkExpr)) {
                row.set(exprIndex, linkExpr, Some(value))
              }
          }
        }
    }
  }

  def crossJoinFieldValues[T](fieldsValues: Seq[(String, Set[T])]): List[Map[String, T]] = {
    val flatValues = fieldsValues
      .groupBy(_._1)
      .map {
        case (k, vs) =>
          CollectionUtils.intersectAll(vs.map(_._2)).toList.map(k -> _)
      }
      .toList

    CollectionUtils.crossJoin(flatValues).map(_.toMap)
  }
}
