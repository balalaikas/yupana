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

package org.yupana.api.query.syntax

import org.yupana.api.query.{ Expression, TypeConvertExpr }
import org.yupana.api.types.TypeConverter

trait DataTypeConverterSyntax {
  def byte2BigDecimal(e: Expression.Aux[Byte]): Expression.Aux[BigDecimal] =
    convert[Byte, BigDecimal](e, TypeConverter.byte2BigDecimal)
  def short2BigDecimal(e: Expression.Aux[Short]): Expression.Aux[BigDecimal] =
    convert[Short, BigDecimal](e, TypeConverter.short2BigDecimal)
  def double2bigDecimal(e: Expression.Aux[Double]): Expression.Aux[BigDecimal] =
    convert[Double, BigDecimal](e, TypeConverter.double2BigDecimal)
  def long2BigDecimal(e: Expression.Aux[Long]): Expression.Aux[BigDecimal] =
    convert[Long, BigDecimal](e, TypeConverter.long2BigDecimal)
  def long2Double(e: Expression.Aux[Long]): Expression.Aux[Double] = convert[Long, Double](e, TypeConverter.long2Double)
  def int2Long(e: Expression.Aux[Int]): Expression.Aux[Long] = convert[Int, Long](e, TypeConverter.int2Long)
  def int2bigDecimal(e: Expression.Aux[Int]): Expression.Aux[BigDecimal] =
    convert[Int, BigDecimal](e, TypeConverter.int2BigDecimal)

  private def convert[T, U](e: Expression.Aux[T], typeConverter: TypeConverter[T, U]): TypeConvertExpr[T, U] =
    TypeConvertExpr(typeConverter, e)
}

object DataTypeConverterSyntax extends DataTypeConverterSyntax
