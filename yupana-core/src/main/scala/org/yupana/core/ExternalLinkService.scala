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

package org.yupana.core

import org.yupana.api.query.Expression.Condition
import org.yupana.api.query._
import org.yupana.api.schema.ExternalLink
import org.yupana.core.model.InternalRow

trait ExternalLinkService[T <: ExternalLink] {

  def externalLink: T

  /**
    * Sets requested external link expressions values into a batch of ValueData
    *
    * @param exprIndex expression index for provided ValueData
    * @param rows rows to be updated
    * @param exprs expressions to be set
    */
  def setLinkedValues(
      exprIndex: scala.collection.Map[Expression, Int],
      rows: Seq[InternalRow],
      exprs: Set[LinkExpr]
  ): Unit

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
      case BinaryOperationExpr(op, LinkExpr(c, _), ConstantExpr(_))
          if Set("==", "!=").contains(op.name) && c.linkName == externalLink.linkName =>
        true
      case InExpr(LinkExpr(c, _), _) if c.linkName == externalLink.linkName    => true
      case NotInExpr(LinkExpr(c, _), _) if c.linkName == externalLink.linkName => true
      case _                                                                   => false
    }
  }
}
