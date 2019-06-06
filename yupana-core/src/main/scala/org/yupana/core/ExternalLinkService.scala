package org.yupana.core


import org.yupana.api.query._
import org.yupana.api.schema.ExternalLink
import org.yupana.core.model.InternalRow
import org.yupana.core.utils.ConditionMatchers.{Equ, Neq}
import org.yupana.core.utils.TimeBoundedCondition

trait ExternalLinkService[T <: ExternalLink]  {

  def externalLink: T

  /**
    * Sets requested external link expressions values into a batch of ValueData
    *
    * @param exprIndex expression index for provided ValueData
    * @param valueData batch of ValueData
    * @param exprs expressions to be set
    */
  def setLinkedValues(exprIndex: scala.collection.Map[Expression, Int], valueData: Seq[InternalRow], exprs: Set[LinkExpr]): Unit

  /**
    * Transforms condition according to filter by this external link conditions.
    * For example:
    *
    * {{{
    *   linkX_fieldY = 'value1' AND
    *     linkY_fieldZ = 'value2' AND
    *     time >= TIMESTAMP '2019-02-01' AND
    *     time < TIMESTAMP '2019-02-28'
    * }}}
    *
    * might be transformed by `ExternalLinkService` for catalogX to:
    *
    * {{{
    *   tagX in (`tagValue1`, `tagValue2`) AND
    *     linkY_fieldZ = 'value2' AND
    *     time >= TIMESTAMP '2019-02-01' AND
    *     time < TIMESTAMP '2019-02-28'
    * }}}
    *
    * So, the main idea is to provide more primitive conditions using values for supported fields (and maybe other fields
    * as a context for catalog).
    *
    * @param condition condition to be transformed
    * @return transformed condition. It should preserve time bounds even if there no conditions supported by this catalog.
    */
  def condition(condition: Condition): Condition

  /**
    * Checks what passed simple condition can be handled by this catalog
    *
    * @param condition condition to be checked
    */
  def isSupportedCondition(condition: Condition): Boolean = {
    condition match {
      case SimpleCondition(BinaryOperationExpr(op, LinkExpr(c, _), ConstantExpr(_))) if Set("==", "!=").contains(op.name)&& c.linkName == externalLink.linkName=> true
      case In(LinkExpr(c, _), _) if c.linkName == externalLink.linkName => true
      case NotIn(LinkExpr(c, _), _) if c.linkName == externalLink.linkName => true
      case _ => false
    }
  }
}

object ExternalLinkService {
  def extractCatalogFields(simpleCondition: TimeBoundedCondition,
                           linkName: String
                          ): (List[(String, Set[String])], List[(String, Set[String])], List[Condition]) = {
    simpleCondition.conditions.foldLeft((List.empty[(String, Set[String])], List.empty[(String, Set[String])], List.empty[Condition])) {
      case ((cat, neg, oth), cond) => cond match {
        case Equ(LinkExpr(c, field), ConstantExpr(v: String)) if c.linkName == linkName =>
          ((field, Set(v)) :: cat, neg, oth)

        case In(LinkExpr(c, field), cs) if c.linkName == linkName =>
          ((field, cs.asInstanceOf[Set[String]]) :: cat, neg, oth)

        case Neq(LinkExpr(c, field), ConstantExpr(v: String)) if c.linkName == linkName =>
          (cat, (field, Set(v)) :: neg, oth)

        case NotIn(LinkExpr(c, field), cs) if c.linkName == linkName =>
          (cat, (field, cs.asInstanceOf[Set[String]]) :: neg, oth)

        case _ => (cat, neg, cond :: oth)
      }
    }
  }
}
