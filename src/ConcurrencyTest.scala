import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import sys.process._

object ConcurrencyTest {
  val scale = 30
  // val formats = List("oap", "parquet")
  val formats = List("oap")
  val tables = List("store_sales")
  val filterColumn = "ss_item_sk"
  val indices = List("store_sales_ss_item_sk1_index", "store_sales_ss_ticket_number_index")
  // the last 2 parameters are hints & assertion check function
  val futures = mutable.ArrayBuffer[(Future[String], String, (String) => Unit)]()

  val REFRESH_INDEX = 1
  val DROP_INDEX = 2

  val SCAN_DATA = 1
  val INSERT_DATA = 2
  val DROP_DATA = 3

  val indexHintsMap = HashMap[Int, String](
    REFRESH_INDEX -> "refresh index",
    DROP_INDEX -> "drop index"
  )
  val dataHintsMap = HashMap[Int, String](
    SCAN_DATA -> "scan data",
    INSERT_DATA -> "insert data",
    DROP_DATA -> "drop data"
  )

  def addToFutures[T](func: => String, hints: String, assertion: (String) => Unit = (String) => {}) = {
    futures += ((Future(func), hints, assertion))
  }

  def addPrefix(cmd: String, database: String): Seq[String] = {
    Seq("beeline", "-u", s"jdbc:hive2://localhost:10000/$database", "-e", cmd)
  }

  def testDataAndIndexOperation(
    codeForData: Int,
    codeForIndex: Int,
    index: String,
    table: String,
    database: String,
    dataOperationHint: String,
    indexOperationHint: String) = {

    def scanDataCmd(index: String, table: String, database: String) = {
      val cmd = s"select $filterColumn from $table where $filterColumn < 20 order by $filterColumn"
      val res = addPrefix(cmd, database)
      println(s"running: $res")
      res
    }

    def insertDataCmd(table: String, database: String) = {
      def times24(x: Int) = Seq.fill(24)(x).mkString("(", ",", ")")
      val insertedData = (1 to 100).foldLeft("")((str: String, x: Int) => s"$str, ${times24(x)}").tail
      val cmd = s"insert into table $table values $insertedData"
      val res = addPrefix(cmd, database)
      println(s"running: $res")
      res
    }

    def dropDataCmd(table: String, database: String) = {
      val cmd = s"drop table $table"
      val res = addPrefix(cmd, database)
      println(s"running: $res")
      res
    }

    def showTableCmd(database: String) = {
      val cmd = s"show tables"
      val res = addPrefix(cmd, database)
      println(s"running: $res")
      res
    }

    // Compute first
    val scanDataRightAnswer = if (codeForData == SCAN_DATA) {
      scanDataCmd(index, table, database) !!
    } else {
      "No need"
    }

    println(s"************************ " +
      s"Testing $indexOperationHint & $dataOperationHint ************************")

    codeForData match {
      case SCAN_DATA =>
        addToFutures(scanDataCmd(index, table, database) !!, dataOperationHint,
          (ans: String) => assert(ans == scanDataRightAnswer, "Bong! Scan answer wrong"))
      case INSERT_DATA =>
        addToFutures(insertDataCmd(table, database) !!, dataOperationHint)
      case DROP_DATA =>
        addToFutures(dropDataCmd(table, database) !!, dataOperationHint,
          (ans: String) => assert(! (showTableCmd(database) !!).contains(table), "Bong! Table not dropped"))
    }

    codeForIndex match {
      case REFRESH_INDEX =>
        addToFutures(refreshIndexCmd(index, table, database) !!, indexOperationHint)
      case DROP_INDEX =>
        addToFutures(dropIndexCmd(index, table, database) !!, indexOperationHint,
          (ans: String) => assert(! (showIndexCmd(table, database) !!).contains(index), "Bong! Index not dropped"))
    }

    waitForTheEndAndCheckAssertAndClear
  }

  def showIndexCmd(table: String, database: String) = {
    val cmd = s"show oindex from $table"
    val res = addPrefix(cmd, database)
    println(s"running: $res")
    res
  }

  def refreshIndexCmd(index: String, table: String, database: String) = {
    val cmd = s"""
                 |refresh oindex ${index} on ${table}
                 |""".stripMargin
    val res = addPrefix(cmd, database)
    println(s"running: $res")
    res
  }

  def dropIndexCmd(index: String, table: String, database: String) = {
    val cmd = s"""
       |drop oindex ${index} on ${table}
       |""".stripMargin
    val res = addPrefix(cmd, database)
    println(s"running: $res")
    res
  }

  def waitForTheEndAndCheckAssertAndClear = {
    futures.foreach {
      future => try {
        val value = Await.result(future._1, Duration.Inf)
        // Do result check, assertion
        future._3(value)
        println(s"Assertion passed ${future._2}")
      } catch {
           case e: Exception => e.printStackTrace()
      }
    }
    futures.clear()
  }

  def refreshOrDropIndicesFromSameTable(indices: Seq[String], table: String, database: String) = {

    val dropIndexAssertion0 = (ans: String) => {
      assert(! (showIndexCmd(table, database) !!).contains(indices(0)))
    }

    val dropIndexAssertion1 = (ans: String) => {
      assert(! (showIndexCmd(table, database) !!).contains(indices(1)))
    }

    println(s"************************ " +
    s"Testing drop indicies from same table ************************")
    addToFutures(dropIndexCmd(indices(0), table, database) !!,
      s"Drop index ${indices(0)} of table $table, database $database",
        dropIndexAssertion0
    )
    addToFutures(dropIndexCmd(indices(1), table, database) !!,
      s"Drop index ${indices(1)} of table $table, database $database",
        dropIndexAssertion1)
    waitForTheEndAndCheckAssertAndClear
    rebuildIndex

    println(s"************************ " +
    s"Testing drop index and refresh index from same table ************************")
    addToFutures(dropIndexCmd(indices(0), table, database) !!,
      s"Drop index ${indices(0)} of table $table, database $database",
        dropIndexAssertion0)
    addToFutures(refreshIndexCmd(indices(1), table, database) !!,
    s"Refresh index ${indices(1)} of table $table, database $database")
    waitForTheEndAndCheckAssertAndClear
    rebuildIndex

    println(s"************************ " +
    s"Testing refresh indicies from same table ************************")
    addToFutures(refreshIndexCmd(indices(0), table, database) !!,
    s"Refresh index ${indices(0)} of table $table, database $database")
    addToFutures(refreshIndexCmd(indices(1), table, database) !!,
    s"Refresh index ${indices(1)} of table $table, database $database")
    waitForTheEndAndCheckAssertAndClear
    rebuildIndex
  }

  def regenData = {
    println("Regening data")
    regenDataScript !
  }

  def rebuildIndex = {
    println("Rebuilding index")
    rebuildIndexScript !
  }

  var rebuildIndexScript: String = _
  var regenDataScript: String = _

  def main(args: Array[String]) = {

    rebuildIndexScript = args(0)
    regenDataScript = args(1)

    val (testIndexOpsSet, testDataOpsSet) = if (args.length > 2) {
      (Set(args(2).toInt), Set(args(3).toInt))
    } else {
      // Default to test all cases
      (Set(REFRESH_INDEX, DROP_INDEX), Set(SCAN_DATA, INSERT_DATA, DROP_DATA))
    }

    for (format <- formats) {
      val database = s"${format}tpcds${scale}"
      for (table <- tables) {
        val index = indices(0)
        refreshOrDropIndicesFromSameTable(indices, table, database)
        for (indexOps <- testIndexOpsSet) {
          for (dataOps <- testDataOpsSet) {
            val indexHint = indexHintsMap(indexOps)
            val dataHint = dataHintsMap(dataOps)
            testDataAndIndexOperation(dataOps, indexOps, index, table, database, dataHint, indexHint)
            if (indexOps == DROP_INDEX) {
              rebuildIndex
            }
            if (dataOps == DROP_DATA) {
              regenData
              rebuildIndex
            }
          }
        }
      }
    }
  }
}
