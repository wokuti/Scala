package validacao

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.hive.HiveContext
import validacao.App.{Field, File}

import scala.collection.mutable.ListBuffer

/**
  * Created by hideki on 27/01/17.
  */
object Util {


  var _hadoopUserName: String = ""
  var _hadoopErrorPath: String = ""
  var _hadoopValidadoPath: String = ""

  val conf = new Configuration()
  val fs = FileSystem.get(conf)

  var error: ListBuffer[String] = new ListBuffer[String]

  def Erro(str: String): Unit ={

    error += str
  }

  def gravarErro(file: String, path: String): Unit = {

    write(_hadoopErrorPath, s"${file}_error", error.mkString.getBytes)
    move(path, _hadoopErrorPath + file)

  }

  def write(uri: String, filePath: String, data: Array[Byte]): Unit = {

    //    conf.set("fs.defaultFS", uri)

    val path = new Path(filePath)


    try {

      val os = fs.create(path)

      os.write(data)
      os.flush()

    } finally {
      fs.close()
    }

  }

  def move(src: String, dest: String): Unit = {

    val origem  = new Path(src)
    val destino = new Path(dest)

    fs.copyFromLocalFile(origem, destino)

  }

//  def LoadData(arquivo: File, table: String, dados: ListBuffer[ListBuffer[Any]], hiveContext: HiveContext): Unit = {
//    if (dados.nonEmpty) {
//
//      var sql: String = "insert into table " + table
//
//      sql += getData(arquivo.fields, dados: ListBuffer[ListBuffer[Any]])
//
//      // Gravar dados
//      hiveContext.sql(sql)
//
//    }
//  }
//
//  def getHeader(fields: List[Field]): String = {
//
//    var sql: String = ""
//
//    fields.zipWithIndex.foreach {
//
//      case (f, i) =>
//
//        if (i == 0)
//          sql += " ("
//
//        sql += f.name
//
//        if (i == fields.size - 1)
//          sql += ") "
//        else
//          sql += ","
//    }
//
//    sql
//  }
//
//  def getData(dados: ListBuffer[ListBuffer[Any]]): String = {
//
//    var sql: String = " "
//
//    dados.zipWithIndex.foreach {
//
//      case (dado, i) =>
//
//        dado.toList.zipWithIndex.foreach {
//
//          case (d, j) =>
//
//            if (j == 0)
//              sql += " ("
//
//            sql += f(d)
//
//            if (j == dado.size - 1)
//              sql += ") "
//            else
//              sql += ","
//        }
//
//        // Virgula para separar o próximo registro a ser inserido
//        // Ex.: (valor1), (valor2)
//        if (i != dados.size - 1)
//          sql += ","
//
//    }
//
//    sql
//
//  }
//
//  def getData(fields: List[Field], dados: ListBuffer[ListBuffer[Any]]): String = {
//
//    var sql: String = " select * from ("
//
//    dados.zipWithIndex.foreach {
//
//      case (dado, i) =>
//
//        sql += " select "
//
//        dado.toList.zipWithIndex.foreach {
//
//          case (d, j) =>
//
//            sql += f(d)
//
//            sql += s" ${fields(i).name}"
//
//            if (j != dado.size - 1)
//              sql += ","
//        }
//
//        if (i != dados.size - 1)
//          sql += "\nunion all\n"
//        else
//          sql += ") s1"
//
//
//
//    }
//
//    sql
//
//  }
//
//  def f[T](d: T): String = d match {
//    case _: String => "'" + d + "'"
//    case _         => d.toString
//  }

  def isData(str: String): Boolean = {
    true
  }

}
