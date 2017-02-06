package com.semantix.lake

import java.io.FileNotFoundException
import java.sql.Timestamp
import java.util.Date

import com.semantix.{Field, File, Json, Util}
import org.apache.hadoop.conf.Configuration
import org.apache.log4j.LogManager
import org.apache.spark.sql.Row
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable.ListBuffer
import scala.util.Try

object App {

  val _appName: String = "Lake"

  val _hadoopUserNameDefault: String = "slihbach"
  val _hadoopErrorPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/erro/"

  val _hadoopValidadoPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/validado/"

  val _sepDefault: String = ";(?=([^\\\"]*\\\"[^\\\"]*\\\")*[^\\\"]*$)"
  val _headDefault: Boolean = false
  val _hadoopPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/validado/"
  val _hadoopConfDefault: String = Util._hdfsPath + "/sistemas/sli/isban/proc/valida_arquivos.cfg"

  var _hadoopPath: String = _hadoopPathDefault
  var _hadoopConf: String = _hadoopConfDefault
  var _sep: String = _sepDefault
  var _head: Boolean = _headDefault

  var _hadoopUserName: String = _hadoopUserNameDefault
  var _hadoopErrorPath: String = _hadoopErrorPathDefault
  var _hadoopValidadoPath: String = _hadoopValidadoPathDefault


  val data = new Date()
  val dataStamp = new Timestamp(data.getTime)

  implicit val formats = DefaultFormats // Json4s needs

  var nomeArquivo: String = ""

  val dados: ListBuffer[ListBuffer[Any]] = new ListBuffer[ListBuffer[Any]]

  @transient
  val config = new SparkConf().setAppName(_appName)

  @transient
  val sc = new SparkContext(config)

  @transient
  val conf = new Configuration()

  @transient
  val hiveContext = new HiveContext(sc)

  import hiveContext.implicits._

  def main(args: Array[String]): Unit = {

    Util.log.info("Início")

    if (!tratarArgs(args))
      return

    System.setProperty("HADOOP_USER_NAME", _hadoopUserName)

    val rawJson = sc.textFile(_hadoopConf)

    val parseJson = parse(rawJson.collect.mkString)

    val jsonConfig = parseJson.extract[Json]

    jsonConfig.files.foreach(processarArquivo)


    Util.log.info("Início Fim")

  }

  def processarArquivo(arquivo: File): Unit = {


    dados.clear

    nomeArquivo = Util.getFileName(arquivo.file)
    val path = Util.getLakePath(arquivo.file, _hadoopPath)

    Util.log.info(s"\nArquivo: $nomeArquivo \nPath: $path\n")

    if (Util.exist(path)) {

      Util.log.info("\nProcessando...\n")

      var result: Boolean = true

      try {
        val file = sc.textFile(path).collect().toList

        // Validação dos dados
        file.filter(_.toString.trim != "").zipWithIndex.foreach {
          case (line, i) =>
            if (result) {
              if (!(_head && i == 0))
                insert(line, i, arquivo.fields, arquivo.timestamp)
            }
        }

      } catch {
        case _: FileNotFoundException =>
        case e: Exception =>
          Util.Erro("ProcessarArquivo:\n" + e.getStackTraceString)
          result = false
      }

      // Load Data on Hive
      if (result) {
        if (dados.nonEmpty) {

          try {

            val schema = Util.getShema(arquivo.fields, arquivo.timestamp)

            val rows = dados.map{x => Row(x:_*)}

            val rdd =  sc.makeRDD(rows)

            val df = hiveContext.createDataFrame(rdd, schema)

            Util.insertData(arquivo, df, hiveContext)
            Util.delete(path)

          } catch {
            case e: Exception =>
              Util.Erro(e.getStackTraceString)
              Util.gravarErro(nomeArquivo, path, _appName, _hadoopErrorPath)
          }
        }
      }
      else {
        if (Util.error.nonEmpty)
          Util.gravarErro(nomeArquivo, path, _appName, _hadoopErrorPath)
      }
    }
  }

  def insert(line: String, index: Long, fields: List[Field], timeStamp: Boolean): Unit = {

    val dataLine = line.split(_sep, -1).toList

    var list: ListBuffer[Any] = new ListBuffer[Any]

    fields.zipWithIndex.foreach {

      case (field, i) =>

        field.`type`.toLowerCase match {
          case "int" =>
            if (dataLine(i).trim == "")
              list += 0
            else {
              if (Try(dataLine(i).toInt).isSuccess)
                list += dataLine(i).toInt
            }
          case "decimal" | "float" =>
            if (dataLine(i).trim == "")
              list += 0
            else {
              if (Try(dataLine(i).toFloat).isSuccess)
                list += dataLine(i).toFloat
            }
          case "date" =>
            if (dataLine(i).trim == "")
              list += ""
            else {
              if (Util.isData(dataLine(i)))
                list += Util.toDate(dataLine(i))
            }
          case _ => list += dataLine(i).replace("\"", "")
        }
    }

    if (timeStamp)
      list += dataStamp

    dados += list

  }

  def help(): Unit = {

    println("\n*********** Argumentos ***********\n")
    println(s"-u:<arg> || --user:<arg>  - Usuário do HDFS \n    Default: ${_hadoopUserNameDefault}")
    println(s"-e:<arg> || --error:<arg> - Caminho para Gravar o log de erro \n    Default: ${_hadoopErrorPathDefault}")
    println(s"-v:<arg> || --validado:<arg> - Caminho para Gravar as planilhas validadas \n    Default: ${_hadoopValidadoPathDefault}")
    println(s"-s:<arg> || --sep:<arg>   - Separador do arquivo CSV \n    default: ${_sepDefault}")
    println(s"-t:<arg> || --title:<arg> - Considerar o título do CSV (s|n) \n    Default: ${_headDefault}")
    println(s"-p:<arg> || --path:<arg>  - Caminho padrão dos arquivos de dados \n    Default: ${_hadoopPath}")
    println(s"-c:<arg> || --conf:<arg>  - Arquivo de configuração \n    Default: ${_headDefault}")
    println("\n*********************************\n")

    Util.log.info("\n*********** Argumentos ***********\n")
    Util.log.info(s"-u:<arg> || --user:<arg>  - Usuário do HDFS \n    Default: ${_hadoopUserNameDefault}")
    Util.log.info(s"-e:<arg> || --error:<arg> - Caminho para Gravar o log de erro \n    Default: ${_hadoopErrorPathDefault}")
    Util.log.info(s"-v:<arg> || --validado:<arg> - Caminho para Gravar as planilhas validadas \n    Default: ${_hadoopValidadoPathDefault}")
    Util.log.info(s"-s:<arg> || --sep:<arg>   - Separador do arquivo CSV \n    default: ${_sepDefault}")
    Util.log.info(s"-t:<arg> || --title:<arg> - Considerar o título do CSV (s|n) \n    Default: ${_headDefault}")
    Util.log.info(s"-p:<arg> || --path:<arg>  - Caminho padrão dos arquivos de dados \n    Default: ${_hadoopPath}")
    Util.log.info(s"-c:<arg> || --conf:<arg>  - Arquivo de configuração \n    Default: ${_headDefault}")
    Util.log.info("\n*********************************\n")

  }

  def tratarArgs(args: Array[String]): Boolean = {

    args.foreach(_.toLowerCase match {

      case arg
        if arg.contains("-u:") ||
          arg.contains("--user:") =>

        arg.split(":", -1).zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopUserName = ""
            else {
              if (i != arg.split(":", -1).length - 1)
                _hadoopUserName += a + ":"
              else
                _hadoopUserName += a
            }
        }
      case arg
        if arg.contains("-e:") ||
          arg.contains("--error:") =>

        arg.split(":", -1).zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopErrorPath = ""
            else {
              if (i != arg.split(":", -1).length - 1)
                _hadoopErrorPath += a + ":"
              else
                _hadoopErrorPath += a
            }
        }

      case arg
        if arg.contains("-v:") ||
          arg.contains("--validado:") =>

        arg.split(":", -1).zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopValidadoPath = ""
            else {
              if (i != arg.split(":", -1).length - 1)
                _hadoopValidadoPath += a + ":"
              else
                _hadoopValidadoPath += a
            }
        }

      case arg
        if arg.contains("-s:") ||
          arg.contains("--sep:") => _sep = arg.split(":")(1)
      case arg
        if arg.contains("-t:") ||
          arg.contains("--title:") => _head = arg.split(":")(1).toLowerCase == "t" ||
        arg.split(":", -1)(1).toLowerCase == "true" ||
        arg.split(":", -1)(1).toLowerCase == "s" ||
        arg.split(":", -1)(1).toLowerCase == "sim"
      case arg
        if arg.contains("-p:") ||
          arg.contains("--path:") =>

        arg.split(":", -1).zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopPath = ""
            else {
              if (i != arg.split(":", -1).length - 1)
                _hadoopPath += a + ":"
              else
                _hadoopPath += a
            }

        }
      case arg
        if arg.contains("-c:") ||
          arg.contains("--conf:") =>

        arg.split(":", -1).zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopConf = ""
            else {
              if (i != arg.split(":", -1).length - 1)
                _hadoopConf += a + ":"
              else
                _hadoopConf += a
            }
        }
      case "-h" | "--help" | _ =>
        help()
        return false
    })

    true

  }

}
