import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import sys.process._
import scala.concurrent.ExecutionContext.Implicits.global

object ConcurrencyTest {
  val scale = 30
//  val formats = List("oap", "parquet")
  val formats = List("oap")
  val tables = List("store_sales")
  val indices = List("store_sales_ss_item_sk1_index", "store_sales_ss_ticket_number_index")

  def addPrefix(cmd: String, database: String): Seq[String] = {
    Seq("beeline", "-u", s"jdbc:hive2://localhost:10000/$database", "-e", cmd)
  }

  def dropTableCmd(table: String, database: String) = {
    val cmd = s"drop $table"
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

  def waitForTheEndAndPrintResAndClear(futures: mutable.ArrayBuffer[(Future[Int], String)]) = {
    futures.foreach {
      future =>
        Await.result(future._1, Duration.Inf)
        future._1.onComplete {
          case Success(value) => println(s"Successfully exec ${future._2}")
          case Failure(e) => println(s"Failed exec ${future._2}"); e.printStackTrace
        }
    }
    futures.clear()
  }

  def dropDataAndRefreshIndex(index: String, table: String, database: String) = {
    val futures = mutable.ArrayBuffer[(Future[Int], String)]()
    futures += Future {
      dropTableCmd(table, database) !
    } -> s"drop table $table, database $database"
    futures += Future {
      refreshIndexCmd(index, table, database) !
    } -> s"Refresh index $index of table $table, database $database"
    waitForTheEndAndPrintResAndClear(futures)
  }

  def dropIndicesFromSameTable(indices: Seq[String], table: String, database: String) = {
    val futures = mutable.ArrayBuffer[(Future[Int], String)]()
    futures += Future {
      dropIndexCmd(indices(0), table, database) !
    } -> s"Drop index ${indices(0)} of table $table, database $database"
    futures += Future {
      dropIndexCmd(indices(1), table, database) !
    } -> s"Drop index ${indices(1)} of table $table, database $database"
    waitForTheEndAndPrintResAndClear(futures)
  }

  def main(args: Array[String]) = {
    for (format <- formats) {
      val database = s"${format}tpcds${scale}"
      for (table <- tables) {
        val index = indices(0)
        dropIndicesFromSameTable(indices, table, database)
//        dropDataAndRefreshIndex(index, table, database)
      }
    }
  }
}
