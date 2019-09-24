package org.yupana.hbase

import java.nio.ByteBuffer

import com.typesafe.scalalogging.StrictLogging
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client.{ Table => _, _ }
import org.apache.hadoop.hbase.filter._
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding
import org.apache.hadoop.hbase.util.{Bytes, Pair}
import org.yupana.api.query.DataPoint
import org.yupana.api.schema._
import org.yupana.core.dao.DictionaryProvider

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.collection.JavaConverters._

object HBaseUtils extends StrictLogging {
  val tableNamePrefix: String = "ts_"
  val rollupStatusFamily: Array[Byte] = "v".getBytes
  val tsdbSchemaFamily: Array[Byte] = "m".getBytes
  val rollupStatusField: Array[Byte] = "st".getBytes
  val tsdbSchemaField: Array[Byte] = "meta".getBytes
  val rollupSpecialKey: Array[Byte] = "\u0000".getBytes
  val tsdbSchemaKey: Array[Byte] = "\u0000".getBytes
  val NULL_VALUE: Long = 0L
  val TAGS_POSITION_IN_ROW_KEY: Int = Bytes.SIZEOF_LONG
  val tsdbSchemaTableName: String = tableNamePrefix + "table"

  def baseTime(time: Long, table: Table): Long = {
    time - time % table.rowTimeSpan
  }

  def restTime(time: Long, table: Table): Long = {
    time % table.rowTimeSpan
  }

  def createTsdRows(dataPoints: Seq[DataPoint], dictionaryProvider: DictionaryProvider): Seq[(Table, Seq[TSDInputRow[Long]])] = {

    dataPoints.groupBy(_.table).map { case (table, points) =>

      val grouped = points.groupBy(rowKey(_, table, dictionaryProvider))
      table -> grouped.map { case (key, dps) =>
        TSDInputRow(key, TSDRowValues(table, dps))
      }.toSeq
    }.toSeq
  }

  def createPutOperation(row: TSDInputRow[Long]): Put = {
    val put = new Put(rowKeyToBytes(row.key))
    row.values.valuesByGroup.foreach { case (group, values) =>
      values.foreach { case (time, bytes) =>
        val timeBytes = Bytes.toBytes(time)
        put.addColumn(family(group), timeBytes, bytes)
      }
    }
    put
  }

  def tableNameString(namespace: String, table: Table): String = {
    tableName(namespace, table).getNameAsString
  }

  def tableName(namespace: String, table: Table): TableName = {
    TableName.valueOf(namespace, tableNamePrefix + table.name)
  }

  def getTsdRowFromResult(table: Table, result: Result): TSDOutputRow[Long] = {
    val row = result.getRow
    TSDOutputRow(
      parseRowKey(row, table),
      getValuesFromResult(result)
    )
  }

  def parseRowKey(bytes: Array[Byte], table: Table): TSDRowKey[Long] = {
    val baseTime = Bytes.toLong(bytes)

    val tagsIds = Array.ofDim[Option[Long]](table.dimensionSeq.size)

    var i = 0
    while (i < tagsIds.length) {
      val value = Bytes.toLong(bytes, TAGS_POSITION_IN_ROW_KEY + i * Bytes.SIZEOF_LONG)
      val v = if (value != NULL_VALUE) Some(value) else None
      tagsIds(i) = v
      i += 1
    }
    TSDRowKey(baseTime, tagsIds)
  }

  private def getTimeOffset(cell: Cell): Long = {
    Bytes.toLong(cell.getQualifierArray, cell.getQualifierOffset, cell.getQualifierLength)
  }

  def getValuesFromResult(res: Result): Array[(Long, Array[Byte])] = {

    val cells = res.rawCells()
    val totalCells = cells.length

    // place iterators at the beginning of each family
    val iterators = findFamiliesOffsets(cells)
    val familiesCount = iterators.length
    // init times array with the time offset from the first cell of each family
    val times = iterators.map(i => getTimeOffset(cells(i)))
    val buffer = ListBuffer.empty[Cell]
    val values = ArrayBuffer.empty[(Long, Array[Byte])]

    var processed = 0
    while (processed < totalCells) {

      // find the minimal time among current cells
      val minTime = times.min
      var j = 0
      // iterate through families
      while (j < familiesCount) {
        val familyTime = times(j)
        // group cells from different families having the same time in the buffer
        if (familyTime == minTime) {
          val familyIterator = iterators(j)
          if (familyIterator < totalCells) {
            buffer += cells(familyIterator)
            // increment family iterator and also update currently processed time for this family
            val familyIteratorIncremented = familyIterator + 1
            iterators(j) = familyIteratorIncremented
            if (familyIteratorIncremented < cells.length) {
              times(j) = getTimeOffset(cells(familyIteratorIncremented))
            }
            processed += 1
          }
        }
        j += 1
      }

      // place time and concatenated array containing values from all families to the result
      values += (minTime -> cloneCellsValues(buffer))
      buffer.clear()
    }
    values.toArray
  }

  private def findFamiliesOffsets(cells: Array[Cell]): Array[Int] = {
    var i = 1
    val offsets = ArrayBuffer(0)
    var prevFamilyCell = cells(0)
    while (i < cells.length) {
      val cell = cells(i)
      if (!CellUtil.matchingFamily(prevFamilyCell, cell)) {
        prevFamilyCell = cell
        offsets += i
      }
      i += 1
    }
    offsets.toArray
  }

  private def cloneCellsValues(cells: ListBuffer[Cell]): Array[Byte] = {
    if (cells.size == 1) CellUtil.cloneValue(cells.head)
    else {
      var size = 0
      var i = 0
      while (i < cells.size) {
        val cell = cells(i)
        size += cell.getValueLength
        i += 1
      }
      val output = Array.ofDim[Byte](size)
      i = 0
      var offset = 0
      while (i < cells.size) {
        val cell = cells(i)
        CellUtil.copyValueTo(cell, output, offset)
        i += 1
        offset += cell.getValueLength
      }
      output
    }
  }

  def createFuzzyFilter(baseTime: Option[Long], tagsFilter: Array[Option[Long]]): FuzzyRowFilter = {
    val filterRowKey = TSDRowKey(
      baseTime.getOrElse(0l),
      tagsFilter
    )
    val filterKey = rowKeyToBytes(filterRowKey)

    val baseTimeMask: Byte = if (baseTime.isDefined) 0 else 1

    val buffer = ByteBuffer.allocate(TAGS_POSITION_IN_ROW_KEY + tagsFilter.length * Bytes.SIZEOF_LONG)
      .put(Array.fill[Byte](Bytes.SIZEOF_LONG)(baseTimeMask))

    val filterMask = tagsFilter.foldLeft(buffer) { case (buf, v) =>
      if (v.isDefined) {
        buf.put(Array.fill[Byte](Bytes.SIZEOF_LONG)(0))
      } else {
        buf.put(Array.fill[Byte](Bytes.SIZEOF_LONG)(1))
      }
    }.array()

    val filter = new FuzzyRowFilter(List(new Pair(filterKey, filterMask)).asJava)
    filter
  }

  private def checkSchemaDefinition(connection: Connection,
                                    namespace: String,
                                    schema: Schema): SchemaCheckResult = {
    val metaTableName = TableName.valueOf(namespace, tsdbSchemaTableName)
    if (connection.getAdmin.tableExists(metaTableName)) {
      ProtobufSchemaChecker.check(schema, readTsdbSchema(connection, namespace))
    } else {
      logger.info(s"Writing TSDB Schema definition to namespace $namespace")
      val tableDesc = new HTableDescriptor(metaTableName)
        .addFamily(
          new HColumnDescriptor(tsdbSchemaFamily)
            .setDataBlockEncoding(DataBlockEncoding.PREFIX)
        )
      connection.getAdmin.createTable(tableDesc)

      val tsdbSchemaBytes = ProtobufSchemaChecker.toBytes(schema)

      val table = connection.getTable(metaTableName)
      val put = new Put(tsdbSchemaKey).addColumn(tsdbSchemaFamily, tsdbSchemaField, tsdbSchemaBytes)
      table.put(put)

      Success
    }
  }

  private def readTsdbSchema(connection: Connection, namespace: String): Array[Byte] = {
    val table = connection.getTable(TableName.valueOf(namespace, tsdbSchemaTableName))
    val get = new Get(tsdbSchemaKey).addColumn(tsdbSchemaFamily, tsdbSchemaField)
    table.get(get).getValue(tsdbSchemaFamily, tsdbSchemaField)
  }

  def initStorage(connection: Connection, namespace: String, schema: Schema): Unit = {
    checkNamespaceExistsElseCreate(connection, namespace)

    val dictDao = new DictionaryDaoHBase(connection, namespace)

    schema.tables.values.foreach { t =>
      checkTableExistsElseCreate(connection, namespace, t)
      checkRollupStatusFamilyExistsElseCreate(connection, namespace, t)
      t.dimensionSeq.foreach(dictDao.checkTablesExistsElseCreate)
    }
    checkSchemaDefinition(connection, namespace, schema) match {
      case Success => logger.info("TSDB table definition checked successfully")
      case Warning(msg) => logger.warn("TSDB table definition check warnings: " + msg)
      case Error(msg) => throw new RuntimeException("TSDB table definition check failed: " + msg)
    }
  }

  def checkNamespaceExistsElseCreate(connection: Connection, namespace: String): Unit = {
    if (!connection.getAdmin.listNamespaceDescriptors.exists(_.getName == namespace)) {
      val namespaceDescriptor = NamespaceDescriptor.create(namespace).build()
      connection.getAdmin.createNamespace(namespaceDescriptor)
    }
  }

  def checkTableExistsElseCreate(connection: Connection, namespace: String, table: Table): Unit = {
    val hbaseTable = tableName(namespace, table)
    if (!connection.getAdmin.tableExists(hbaseTable)) {
      val desc = new HTableDescriptor(hbaseTable)
      val fieldGroups = table.metrics.map(_.group).toSet
      fieldGroups foreach(group => desc.addFamily(
        new HColumnDescriptor(family(group))
          .setDataBlockEncoding(DataBlockEncoding.PREFIX)
          .setCompactionCompressionType(Algorithm.SNAPPY)
      ))
      connection.getAdmin.createTable(desc)
    }
  }

  def checkRollupStatusFamilyExistsElseCreate(connection: Connection, namespace: String, table: Table): Unit = {
    val name = tableName(namespace, table)
    val hbaseTable = connection.getTable(name)
    val tableDesc = hbaseTable.getTableDescriptor
    if (!tableDesc.hasFamily(rollupStatusFamily)) {
      connection.getAdmin.addColumn(
        name,
        new HColumnDescriptor(rollupStatusFamily).setDataBlockEncoding(DataBlockEncoding.PREFIX)
      )
    }
  }

  def createRollupStatusPut(time: Long, status: String): Put = {
    new Put(Bytes.toBytes(time)).addColumn(rollupStatusFamily, rollupStatusField, status.getBytes)
  }

  private[hbase] def rowKeyToBytes(rowKey: TSDRowKey[Long]): Array[Byte] = {

    val baseTimeBytes = Bytes.toBytes(rowKey.baseTime)

    val buffer = ByteBuffer.allocate(baseTimeBytes.length + rowKey.dimIds.length * Bytes.SIZEOF_LONG)
      .put(baseTimeBytes)

    rowKey.dimIds.foldLeft(buffer) { case (buf, value) =>
      buf.put(Bytes.toBytes(value.getOrElse(NULL_VALUE)))
    }.array()
  }

  private def rowKey(dataPoint: DataPoint, table: Table, dictionaryProvider: DictionaryProvider): TSDRowKey[Long] = {
    val dimIds = table.dimensionSeq.map { dim =>
      dataPoint.dimensions.get(dim).filter(_.trim.nonEmpty).map(v => dictionaryProvider.dictionary(dim).id(v))
    }.toArray

    TSDRowKey(HBaseUtils.baseTime(dataPoint.time, table), dimIds)
  }

  def family(group: Int): Array[Byte] = s"d$group".getBytes

}
