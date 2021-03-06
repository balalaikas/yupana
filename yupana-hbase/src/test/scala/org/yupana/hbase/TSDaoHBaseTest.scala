package org.yupana.hbase

import java.nio.ByteBuffer
import java.util.Properties

import org.apache.hadoop.hbase.client.{ Scan, Result => HResult }
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter
import org.apache.hadoop.hbase.util.Bytes
import org.scalamock.function.{ FunctionAdapter1, MockFunction1 }
import org.scalamock.scalatest.MockFactory
import org.scalatest._
import org.yupana.api.Time
import org.yupana.api.query.{ DimIdInExpr, DimIdNotInExpr, Expression }
import org.yupana.api.schema.{ Dimension, Table }
import org.yupana.api.utils.SortedSetIterator
import org.yupana.core.cache.CacheFactory
import org.yupana.core.dao.{ DictionaryDao, DictionaryProvider, DictionaryProviderImpl }
import org.yupana.core.model._
import org.yupana.core.utils.metric.{ MetricQueryCollector, NoMetricCollector }
import org.yupana.core.{ MapReducible, TestDims, TestSchema, TestTableFields }

import scala.collection.JavaConverters._

class TSDaoHBaseTest
    extends FlatSpec
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with OptionValues {

  import HBaseTestUtils._
  import TestSchema._
  import org.yupana.api.query.syntax.All._

  type QueryRunner = MockFunction1[Seq[Scan], Iterator[HResult]]

  override protected def beforeAll(): Unit = {
    val properties = new Properties()
    properties.load(getClass.getClassLoader.getResourceAsStream("app.properties"))
    CacheFactory.init(properties, "test")
  }

  override protected def beforeEach(): Unit = {
    CacheFactory.flushCaches()
  }

  private def baseTime(time: Long) = {
    time - (time % testTable.rowTimeSpan)
  }

  private def scan(from: Long, to: Long) = {
    where { (scans: Seq[Scan]) =>
      val scan = scans.head
      baseTime(from) == Bytes.toLong(scan.getStartRow) &&
      baseTime(to) == (Bytes.toLong(scan.getStopRow) - 1)
    }
  }

  def scan(table: Table, from: Long, to: Long, range: Seq[Any]): FunctionAdapter1[Seq[Scan], Boolean] = {
    scanMultiRanges(table, from, to, Set(range))
  }

  def scanMultiRanges(
      table: Table,
      from: Long,
      to: Long,
      ranges: Set[Seq[Any]]
  ): FunctionAdapter1[Seq[Scan], Boolean] = {
    where { (scans: Seq[Scan]) =>
      val scan = scans.head
      val filter = scan.getFilter.asInstanceOf[MultiRowRangeFilter]
      val rowRanges = filter.getRowRanges.asScala

      val rangesChecks = for {
        time <- (baseTime(from) to baseTime(to) by testTable.rowTimeSpan)
        range <- ranges
      } yield {
        rowRanges.exists { rowRange =>
          var offset = 8
          range.zip(table.dimensionSeq).zipWithIndex.forall {
            case ((id, dim), idx) =>
              val start = dim.rStorable.read(ByteBuffer.wrap(rowRange.getStartRow, offset, dim.rStorable.size))
              val stop = dim.rStorable.read(ByteBuffer.wrap(rowRange.getStopRow, offset, dim.rStorable.size))
              offset += dim.rStorable.size

              val tid = id.asInstanceOf[dim.R]
              dim.rOrdering.gte(tid, start) && (if (idx == range.size - 1) dim.rOrdering.lt(tid, stop)
                                                else dim.rOrdering.lte(tid, stop))
          } &&
          time == Bytes.toLong(rowRange.getStartRow) &&
          time == Bytes.toLong(rowRange.getStopRow)
        }
      }

      rangesChecks.forall(b => b) &&
      baseTime(from) == Bytes.toLong(scan.getStartRow) &&
      baseTime(to) == Bytes.toLong(scan.getStopRow)
    }
  }

  "TSDaoHBase" should "execute time bounded queries" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_A), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))
    val pointTime = 2000

    queryRunner
      .expects(scan(from, to))
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test1"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .hbaseRow
        )
      )

    val res = dao
      .query(
        InternalQuery(testTable, exprs.toSet, and(ge(time, const(Time(from))), lt(time, const(Time(to))))),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res.size shouldEqual 1

    val r = res.head
    r.get[Time](0).value shouldEqual Time(pointTime)
    r.get[Time](1).value shouldEqual "test1"
    r.get[Time](2).value shouldEqual 1d
  }

  it should "skip values with fields not defined in schema" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs =
      Seq[Expression](time, dimension(TestDims.DIM_A), dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))
    val pointTime = 2000

    queryRunner
      .expects(scan(testTable, from, to, Seq(dimAHash("test1"))))
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test1"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 3d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .cell("d1", (pointTime + 1) % testTable.rowTimeSpan)
            .field(111, 1d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _).expects(TestDims.DIM_A, Set("test1")).returning(Map("test1" -> (1, 1L)))

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(ge(time, const(Time(from))), lt(time, const(Time(to))), equ(dimension(TestDims.DIM_A), const("test1")))
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res.size shouldEqual 2

    val r1 = res(0)
    r1.get(0).value shouldEqual Time(pointTime)
    r1.get(1).value shouldEqual "test1"
    r1.get(2).value shouldEqual 2.toShort
    r1.get(3).value shouldEqual 3d
    val r2 = res(1)
    r2.get(0).value shouldEqual Time(pointTime + 1)
    r2.get(1) shouldEqual None // should be "test1" but storage format does not allow this
    r2.get(2).value shouldEqual 2.toShort // should be "test22" but storage format does not allow this
    r2.get(3) shouldEqual None
  }

  it should "set tag filter for equ" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs =
      Seq[Expression](time, dimension(TestDims.DIM_A), dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))
    val pointTime = 2000

    queryRunner
      .expects(scan(testTable, from, to, Seq(dimAHash("test1"))))
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test1"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _).expects(TestDims.DIM_A, Set("test1")).returning(Map("test1" -> 1L))

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(ge(time, const(Time(from))), lt(time, const(Time(to))), equ(dimension(TestDims.DIM_A), const("test1")))
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res.size shouldEqual 1

    val r = res.head
    r.get(0).value shouldEqual Time(pointTime)
    r.get(1).value shouldEqual "test1"
    r.get(2).value shouldEqual 2.toShort
    r.get(3).value shouldEqual 1d
  }

//  it should "support not create queries if dimension value is not found" in withMock { (dao, dictionary, queryRunner) =>
//    val from = 1000
//    val to = 5000
//    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
//    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))
//
//    queryRunner.expects(Seq.empty).returning(Iterator.empty)

//    (dictionary.getIdsByValues _).expects(TestDims.DIM_A, Set("test1")).returning(Map.empty)
//
//    val res = dao
//      .query(
//        InternalQuery(
//          testTable,
//          exprs.toSet,
//          and(ge(time, const(Time(from))), lt(time, const(Time(to))), equ(dimension(TestDims.DIM_A), const("test1")))
//        ),
//        valueDataBuilder,
//        NoMetricCollector
//      )
//      .toList
//
//    res shouldBe empty
//  }

  it should "support IN operation for tags" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 2000
    val pointTime2 = 2200

    queryRunner
      .expects(scanMultiRanges(testTable, from, to, Set(Seq(dimAHash("test1")), Seq(dimAHash("test2")))))
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test2"), 5.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 7d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .cell("d1", pointTime2 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "test1")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("test1", "test2"))
//      .returning(Map("test1" -> 1L, "test2" -> 2L))

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            in(dimension(TestDims.DIM_A), Set("test1", "test2"))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res.size shouldEqual 2

    res(0).get(0).value shouldEqual Time(pointTime1)
    res(0).get(1).value shouldEqual 5.toShort
    res(0).get(2).value shouldEqual 7d

    res(1).get(0).value shouldEqual Time(pointTime2)
    res(1).get(1).value shouldEqual 5.toShort
    res(1).get(2).value shouldEqual 5d
  }

  it should "do nothing if IN values are empty" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    queryRunner.expects(Seq()).returning(Iterator.empty)

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(ge(time, const(Time(from))), lt(time, const(Time(to))), in(dimension(TestDims.DIM_A), Set()))
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res shouldBe empty
  }

  it should "intersect different conditions for same tag" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 2000

    queryRunner
      .expects(
        scan(testTable, from, to, Seq(dimAHash("test2"), 21.toShort))
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test2"), 21.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 3d)
            .field(Table.DIM_TAG_OFFSET, "test2")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _).expects(TestDims.DIM_A, Set("test2")).returning(Map("test2" -> 2L))

    dao.query(
      InternalQuery(
        testTable,
        exprs.toSet,
        and(
          ge(time, const(Time(from))),
          lt(time, const(Time(to))),
          in(dimension(TestDims.DIM_A), Set("test1", "test2")),
          equ(dimension(TestDims.DIM_B), const(21.toShort)),
          in(dimension(TestDims.DIM_A), Set("test2", "test3"))
        )
      ),
      valueDataBuilder,
      NoMetricCollector
    )
  }

  it should "cross join different IN conditions for different tags" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs =
      Seq[Expression](time, dimension(TestDims.DIM_A), dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime = 2000

    queryRunner
      .expects(
        scanMultiRanges(
          testTable,
          from,
          to,
          Set(
            Seq(dimAHash("A 1"), 1.toShort),
            Seq(dimAHash("A 1"), 2.toShort),
            Seq(dimAHash("A 2"), 1.toShort),
            Seq(dimAHash("A 2"), 2.toShort),
            Seq(dimAHash("A 3"), 1.toShort),
            Seq(dimAHash("A 3"), 2.toShort)
          )
        )
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 1"), 1.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "A 1")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 2"), 1.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 3d)
            .field(Table.DIM_TAG_OFFSET, "A 2")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 2"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 4d)
            .field(Table.DIM_TAG_OFFSET, "A 2")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 3"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 6d)
            .field(Table.DIM_TAG_OFFSET, "A 3")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("A 1", "A 2", "A 3"))
//      .returning(Map("A 1" -> 1L, "A 2" -> 2L, "A 3" -> 3L))

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            in(dimension(TestDims.DIM_A), Set("A 1", "A 2", "A 3")),
            in(dimension(TestDims.DIM_B), Set(1.toShort, 2.toShort))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res should have size 4
  }

  it should "cross join in and eq for different tags" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs =
      Seq[Expression](time, dimension(TestDims.DIM_A), dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime = 2000

    queryRunner
      .expects(
        scanMultiRanges(
          testTable,
          from,
          to,
          Set(
            Seq(dimAHash("A 1"), 1.toShort),
            Seq(dimAHash("A 2"), 1.toShort),
            Seq(dimAHash("A 3"), 1.toShort)
          )
        )
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 1"), 1.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "A 1")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("A 2"), 1.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 3d)
            .field(Table.DIM_TAG_OFFSET, "A 2")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("A 1", "A 2", "A 3"))
//      .returning(Map("A 1" -> 1L, "A 2" -> 2L, "A 3" -> 3L))

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            in(dimension(TestDims.DIM_A), Set("A 1", "A 2", "A 3")),
            equ(dimension(TestDims.DIM_B), const(1.toShort))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res should have size 2
  }

////  it should "use post filter if there are too many combinations" in withMock { (dao, dictionary, queryRunner) =>
////    val from = 1000
////    val to = 5000
////    val exprs = Seq[Expression](time, dimension(TestTable.DIM_A), dimension(TestTable.DIM_B), metric(TestTable.TEST_FIELD))
////    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap)
////
////    val pointTime1 = 2000
////    val pointTime2 = 2500
////
////    val manyAs = (1 to 200).map(_.toString)
////    val manyBs = (1 to 3000).map(_.toString)
////
////
////    queryRunner.expects(where { tsdQueries: Seq[TSDQuery] =>
////      tsdQueries.size  == 200 &&
////      tsdQueries.sortBy(_.dimensionFilter(0)).zipWithIndex.forall { case (tsdQuery, idx) =>
////        (tsdQuery.dimensionFilter sameElements idx + 1), None)) &&
////        tsdQuery.from == from &&
////        tsdQuery.to == to }
////    }).returning(
////      Iterator(
////        TSDOutputRow(
////          pointTime1 - (pointTime1 % TestTable.rowTimeSpan), 2, 2))),
////          Array(
////            pointTime1 % TestTable.rowTimeSpan ->  tagged(1, 1d),
////            pointTime2 % TestTable.rowTimeSpan ->  tagged(1, 5d)
////          )
////        ),
////        TSDOutputRow(
////          pointTime1 - (pointTime1 % TestTable.rowTimeSpan), 5, 6000))),
////          Array(
////            pointTime1 % TestTable.rowTimeSpan ->  tagged(1, 2d),
////            pointTime2 % TestTable.rowTimeSpan ->  tagged(1, 3d)
////          )
////        )
////      )
////    )
////
////    (dictionary.getIdsByValues _)
////      .expects(where { (tag, values) => tag == TestTable.DIM_A && values == manyAs.toSet })
////      .returning(manyBs.map(x => x -> x.toLong).toMap)
////    (dictionary.getIdsByValues _)
////      .expects(where { (tag, values) => tag == TestTable.DIM_B && values == manyBs.toSet })
////      .returning(manyBs.map(x => x -> x.toLong).toMap)
////
////    (dictionary.getValuesByIds _).expects(TestTable.DIM_A, Set(2l)).returning(Map(2l -> "2"))
////    (dictionary.getValuesByIds _).expects(TestTable.DIM_B, Set(2l)).returning(Map(2l -> "2"))
////
////    val res = dao.query(
////      InternalQuery(
////        TestTable,
////        exprs.toSet,
////        and(
////          ge(time, const(Time(from))),
////          lt(time, const(Time(to))),
////          in(dimension(TestTable.DIM_A), manyAs.toSet),
////          in(dimension(TestTable.DIM_B), manyBs.toSet)
////        )
////      ),
////      valueDataBuilder,
////      NoMetricCollector
////    ).toList
////
////    res should have size 2
////
////    res(0).get(0).value shouldEqual Time(pointTime1)
////    res(0).get(1).value shouldEqual "2"
////    res(0).get(2).value shouldEqual "2"
////    res(0).get(3).value shouldEqual 1d
////
////    res(1).get(0).value shouldEqual Time(pointTime2)
////    res(1).get(1).value shouldEqual "2"
////    res(1).get(2).value shouldEqual "2"
////    res(1).get(3).value shouldEqual 5d
////  }
////
  it should "exclude NOT IN from IN" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 2000

    queryRunner
      .expects(
        scan(from, to)
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), (2, 2L), 1.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "doesn't matter")
            .hbaseRow
        )
      )

    dao.query(
      InternalQuery(
        testTable,
        exprs.toSet,
        and(
          ge(time, const(Time(from))),
          lt(time, const(Time(to))),
          in(dimension(TestDims.DIM_B), Set(1.toShort, 2.toShort)),
          notIn(dimension(TestDims.DIM_B), Set(2.toShort, 3.toShort))
        )
      ),
      valueDataBuilder,
      NoMetricCollector
    )
  }

  it should "filter by exclude conditions" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime = 2000

    queryRunner
      .expects(
        scan(from, to)
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test11"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "test11")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test12"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 2d)
            .field(Table.DIM_TAG_OFFSET, "test12")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test13"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 3d)
            .field(Table.DIM_TAG_OFFSET, "test13")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test14"), 3.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 4d)
            .field(Table.DIM_TAG_OFFSET, "test14")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test15"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "test15")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("test11", "test12", "test14", "test15"))
//      .returning(Map("test11" -> 1, "test12" -> 2, "test14" -> 4, "test15" -> 5))

    val results = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            notIn(dimension(TestDims.DIM_A), Set("test11", "test12")),
            notIn(dimension(TestDims.DIM_A), Set("test12", "test15")),
            neq(dimension(TestDims.DIM_A), const("test14"))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    results should have size 1
  }

  it should "do nothing if exclude produce empty set" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    queryRunner.expects(Seq.empty).returning(Iterator.empty)

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("tagValue1"))
//      .returning(Map("tagValue1" -> 1L))
//
//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("tagValue2"))
//      .returning(Map("tagValue2" -> 2L))

    val results = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            notIn(dimension(TestDims.DIM_A), Set("tagValue1", "tagValue2")),
            equ(dimension(TestDims.DIM_A), const("tagValue1"))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    results shouldBe empty
  }

  it should "handle tag ID IN" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 2000
    val pointTime2 = 2200

    queryRunner
      .expects(
        scanMultiRanges(
          testTable,
          from,
          to,
          Set(
            Seq(dimAHash("test12")),
            Seq(dimAHash("test22"))
          )
        )
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test12"), 5.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 7d)
            .field(Table.DIM_TAG_OFFSET, "test12")
            .cell("d1", pointTime2 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "test12")
            .hbaseRow
        )
      )

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            DimIdInExpr(TestDims.DIM_A, SortedSetIterator(dimAHash("test12"), dimAHash("test22")))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res.size shouldEqual 2

    res(0).get(0).value shouldEqual Time(pointTime1)
    res(0).get(1).value shouldEqual 5.toShort
    res(0).get(2).value shouldEqual 7d

    res(1).get(0).value shouldEqual Time(pointTime2)
    res(1).get(1).value shouldEqual 5.toShort
    res(1).get(2).value shouldEqual 5d
  }

  it should "handle tag ID NOT IN condition" in withMock { (dao, dictionary, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_B), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime = 2000

    queryRunner
      .expects(
        scan(testTable, from, to, Seq(dimAHash("test12")))
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("test12"), 2.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 1d)
            .field(Table.DIM_TAG_OFFSET, "test12")
            .hbaseRow
        )
      )

//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("test11", "test12"))
//      .returning(Map("test11" -> 1, "test12" -> 2))
//
//    (dictionary.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("test14"))
//      .returning(Map("test14" -> 4))

    val results = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            in(dimension(TestDims.DIM_A), Set("test11", "test12")),
            DimIdNotInExpr(TestDims.DIM_A, SortedSetIterator(dimAHash("test11"), dimAHash("test15"))),
            neq(dimension(TestDims.DIM_A), const("test14"))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    results should have size 1
  }

  it should "support exact time values" in withMock { (dao, dictionaryDao, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_A), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime = 2000

//    (dictionaryDao.getIdsByValues _).expects(TestDims.DIM_A, Set("tag_a")).returning(Map("tag_a" -> 1))

    queryRunner
      .expects(
        scan(testTable, from, to, Seq(dimAHash("tag_a")))
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime - (pointTime % testTable.rowTimeSpan), dimAHash("tag_a"), 5.toShort)
            .cell("d1", pointTime % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 7d)
            .field(Table.DIM_TAG_OFFSET, "tag_a")
            .cell("d1", (pointTime + 1) % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "tag_a")
            .hbaseRow
        )
      )

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            equ(time, const(Time(pointTime))),
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            equ(dimension(TestDims.DIM_A), const("tag_a"))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res should have size 1
    res.head.get(0).value shouldEqual Time(pointTime)
    res.head.get(1).value shouldEqual "tag_a"
    res.head.get(2).value shouldEqual 7d
  }

  it should "support EQ filter for tuples" in withMock { (dao, dictionaryDao, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_A), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 2000
    val pointTime2 = 2500

//    (dictionaryDao.getIdsByValues _).expects(TestDims.DIM_A, Set("test42")).returning(Map("test42" -> 42L))

    queryRunner
      .expects(
        scan(testTable, from, to, Seq(dimAHash("test42")))
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test42"), 5.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 7d)
            .field(Table.DIM_TAG_OFFSET, "test42")
            .cell("d1", pointTime2 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "test42")
            .hbaseRow
        )
      )

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            equ(tuple(time, dimension(TestDims.DIM_A)), const((Time(pointTime2), "test42")))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res should have size 1
    res.head.get(0).value shouldEqual Time(pointTime2)
    res.head.get(1).value shouldEqual "test42"
    res.head.get(2).value shouldEqual 5d

  }

  it should "perform pre-filtering by IN for tuples" in withMock { (dao, dictionaryDao, queryRunner) =>
    val from = 1000
    val to = 5000
    val exprs = Seq[Expression](time, dimension(TestDims.DIM_A), metric(TestTableFields.TEST_FIELD))
    val valueDataBuilder = new InternalRowBuilder(exprs.zipWithIndex.toMap, Some(TestSchema.testTable))

    val pointTime1 = 1010
    val pointTime2 = 1020
    val pointTime3 = 1030

//    (dictionaryDao.getIdsByValues _)
//      .expects(TestDims.DIM_A, Set("test42", "test51"))
//      .returning(Map("test42" -> 42L, "test51" -> 51L))

    queryRunner
      .expects(
        scanMultiRanges(
          testTable,
          from,
          to,
          Set(
            Seq(dimAHash("test42")),
            Seq(dimAHash("test51"))
          )
        )
      )
      .returning(
        Iterator(
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test42"), 5.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 7d)
            .field(Table.DIM_TAG_OFFSET, "test42")
            .cell("d1", pointTime2 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 5d)
            .field(Table.DIM_TAG_OFFSET, "test42")
            .cell("d1", pointTime3 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 8d)
            .field(Table.DIM_TAG_OFFSET, "test42")
            .hbaseRow,
          HBaseTestUtils
            .row(pointTime1 - (pointTime1 % testTable.rowTimeSpan), dimAHash("test51"), 6.toShort)
            .cell("d1", pointTime1 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 15d)
            .field(Table.DIM_TAG_OFFSET, "test51")
            .cell("d1", pointTime2 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 33d)
            .field(Table.DIM_TAG_OFFSET, "test51")
            .cell("d1", pointTime3 % testTable.rowTimeSpan)
            .field(TestTableFields.TEST_FIELD.tag, 43d)
            .field(Table.DIM_TAG_OFFSET, "test51")
            .hbaseRow
        )
      )

    val res = dao
      .query(
        InternalQuery(
          testTable,
          exprs.toSet,
          and(
            ge(time, const(Time(from))),
            lt(time, const(Time(to))),
            in(tuple(time, dimension(TestDims.DIM_A)), Set((Time(pointTime2), "test42"), (Time(pointTime1), "test51")))
          )
        ),
        valueDataBuilder,
        NoMetricCollector
      )
      .toList

    res should have size 4
    res.head.get(0).value shouldEqual Time(pointTime1)
    res.head.get(1).value shouldEqual "test42"
    res.head.get(2).value shouldEqual 7d

    res(1).get(0).value shouldEqual Time(pointTime2)
    res(1).get(1).value shouldEqual "test42"
    res(1).get(2).value shouldEqual 5d

    res(2).get(0).value shouldEqual Time(pointTime1)
    res(2).get(1).value shouldEqual "test51"
    res(2).get(2).value shouldEqual 15d

    res(3).get(0).value shouldEqual Time(pointTime2)
    res(3).get(1).value shouldEqual "test51"
    res(3).get(2).value shouldEqual 33d
  }

  class TestDao(override val dictionaryProvider: DictionaryProvider, queryRunner: QueryRunner)
      extends TSDaoHBaseBase[Iterator] {
    override def mapReduceEngine(metricQueryCollector: MetricQueryCollector): MapReducible[Iterator] =
      MapReducible.iteratorMR

    override def executeScans(
        queryContext: InternalQueryContext,
        from: IdType,
        to: IdType,
        rangeScanDims: Iterator[Map[Dimension, Seq[_]]]
    ): Iterator[HResult] = {
      val scans = rangeScanDims.map { dimIds =>
        val filter = HBaseUtils.multiRowRangeFilter(queryContext.table, from, to, dimIds)
        HBaseUtils.createScan(queryContext, filter, Seq.empty, from, to)
      }
      queryRunner(scans.toSeq)
    }
  }

  def withMock(body: (TestDao, DictionaryDao, QueryRunner) => Unit): Unit = {
    val exec = mockFunction[Seq[Scan], Iterator[HResult]]
    val dictionaryDaoMock = mock[DictionaryDao]
    val dicionaryProvider = new DictionaryProviderImpl(dictionaryDaoMock)
    val dao = new TestDao(dicionaryProvider, exec)
    body(dao, dictionaryDaoMock, exec)
  }
}
