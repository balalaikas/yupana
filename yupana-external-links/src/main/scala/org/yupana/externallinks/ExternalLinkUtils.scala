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
import org.yupana.core.utils.ConditionMatchers.{ Equ, Lower, Neq }
import org.yupana.core.utils.{ CollectionUtils, Table, TimeBoundedCondition }

object ExternalLinkUtils {

  /**
    * Extracts external link fields from time bounded condition
    * @note this function doesn't care if the field condition case sensitive or not
    *
    * @param simpleCondition condition to extract values from
    * @param linkName the external link name.
    * @return list of the fields and field values to be included, list of fields and field values to be excluded and
    *         unmatched part of the condition.
    */
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

          case Equ(ConstantExpr(v: String), LinkExpr(c, field)) if c.linkName == linkName =>
            ((field, Set(v)) :: cat, neg, oth)

          case InExpr(LinkExpr(c, field), cs) if c.linkName == linkName =>
            ((field, cs.asInstanceOf[Set[String]]) :: cat, neg, oth)

          case Neq(LinkExpr(c, field), ConstantExpr(v: String)) if c.linkName == linkName =>
            (cat, (field, Set(v)) :: neg, oth)

          case Neq(ConstantExpr(v: String), LinkExpr(c, field)) if c.linkName == linkName =>
            (cat, (field, Set(v)) :: neg, oth)

          case NotInExpr(LinkExpr(c, field), cs) if c.linkName == linkName =>
            (cat, (field, cs.asInstanceOf[Set[String]]) :: neg, oth)

          case Equ(Lower(LinkExpr(c, field)), ConstantExpr(v: String)) if c.linkName == linkName =>
            ((field, Set(v)) :: cat, neg, oth)

          case Equ(ConstantExpr(v: String), Lower(LinkExpr(c, field))) if c.linkName == linkName =>
            ((field, Set(v)) :: cat, neg, oth)

          case InExpr(Lower(LinkExpr(c, field)), cs) if c.linkName == linkName =>
            ((field, cs.asInstanceOf[Set[String]]) :: cat, neg, oth)

          case Neq(Lower(LinkExpr(c, field)), ConstantExpr(v: String)) if c.linkName == linkName =>
            (cat, (field, Set(v)) :: neg, oth)

          case Neq(ConstantExpr(v: String), Lower(LinkExpr(c, field))) if c.linkName == linkName =>
            (cat, (field, Set(v)) :: neg, oth)

          case NotInExpr(Lower(LinkExpr(c, field)), cs) if c.linkName == linkName =>
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
    val allFieldsValues = fieldValuesForDimValues(fields, dimValues)
    val linkExprsIdx = linkExprs.toSeq.map(e => e -> exprIndex(e))
    rows.foreach { row =>
      row.get[R](dimExprIdx).foreach { dimValue =>
        val rowValues = allFieldsValues.row(dimValue)
        updateRow(row, linkExprsIdx, rowValues)
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
    val dimExpr = DimensionExpr(externalLink.dimension.aux)
    val fields = linkExprs.map(_.linkField)

    def extractDimValueWithTime(r: InternalRow): Option[(R, Time)] = {
      for {
        d <- r.get[R](exprIndex, dimExpr)
        t <- r.get[Time](exprIndex, TimeExpr)
      } yield (d, t)
    }

    val dimValuesWithTimes = rows.flatMap(extractDimValueWithTime)
    val allFieldsValues = fieldValuesForDimValuesAndTimes(fields, dimValuesWithTimes.toSet)
    val linkExprsIdx = linkExprs.toSeq.map(e => e -> exprIndex(e))

    rows.foreach { row =>
      extractDimValueWithTime(row).foreach { dimValueAtTime =>
        val values = allFieldsValues.row(dimValueAtTime)
        updateRow(row, linkExprsIdx, values)
      }
    }
  }

  private def updateRow(row: InternalRow, exprIndex: Seq[(LinkExpr, Int)], values: Map[String, String]): Unit = {
    exprIndex.foreach {
      case (expr, idx) =>
        values.get(expr.linkField).foreach { value =>
          if (value != null) {
            row.set(idx, Some(value))
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
