package com.semantix.validate

import java.io.FileNotFoundException

import com.semantix.{Field, File, Json, Util}
import org.apache.spark.{SparkConf, SparkContext}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.util.Try
import scala.util.control.Breaks._

object App {

  val _appName: String = "Validate"

  val _hadoopUserNameDefault: String = "slihbach"
  val _hadoopErrorPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/erro/" //"hdfs://127.0.0.1:8020/user/cloudera/erro/"

  val _hadoopValidadoPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/validado/"//"hdfs://127.0.0.1:8020/user/cloudera/validado/" //

  val _sepDefault: String = ";(?=([^\\\"]*\\\"[^\\\"]*\\\")*[^\\\"]*$)"
  val _headDefault: Boolean = false
  val _hadoopPathDefault: String = Util._hdfsPath + "/sistemas/sli/isban/inbox/" // "hdfs://127.0.0.1:8020/user/cloudera/" //
  val _hadoopConfDefault: String = Util._hdfsPath + "/sistemas/sli/isban/proc/valida_arquivos.cfg" // "hdfs://127.0.0.1:8020/user/cloudera/valida_arquivos.cfg"

  var _hadoopPath: String = _hadoopPathDefault
  var _hadoopConf: String = _hadoopConfDefault
  var _sep: String = _sepDefault
  var _head: Boolean = _headDefault

  var _hadoopUserName: String = _hadoopUserNameDefault
  var _hadoopErrorPath: String = _hadoopErrorPathDefault
  var _hadoopValidadoPath: String = _hadoopValidadoPathDefault

  implicit val formats = DefaultFormats // Json4s needs

  var nomeArquivo: String = ""

  @transient
  val config = new SparkConf().setAppName(_appName)

  @transient
  val sc = new SparkContext(config)

  def main(args: Array[String]): Unit = {

    if (!tratarArgs(args))
      return

    System.setProperty("HADOOP_USER_NAME", _hadoopUserName)

    val rawJson = sc.textFile(_hadoopConf)

    val parseJson = parse(rawJson.collect.mkString)

    val jsonConfig = parseJson.extract[Json]

    jsonConfig.files.foreach(validarArquivo)

  }

  def validarArquivo(arquivo: File): Unit = {

    nomeArquivo = Util.getFileName(arquivo.file)

    val path = Util.getPath(arquivo.file, _hadoopPath)

    Util.log.info(s"\nArquivo: $nomeArquivo \nPath: $path\n")

    var result: Boolean = true

    if (Util.exist(path)) {

      Util.log.info("\nProcessando...\n")

      try {

        // Extraction data
        //      val file = Source.fromFile(path).getLines.toList
        val file = sc.textFile(path).collect.toList

        // Validação dos dados
        breakable {
          file.filter(_.toString.trim != "").zipWithIndex.foreach {
            case (line, i) =>
              if (result) {
                if (_head && i == 0) {
                  if (!validarHead(line, arquivo.fields)) {
                    result = false
                    break // Next File Cabeçalho errado
                  }
                }
                else {
                  if (!validarDado(line, i, arquivo.fields)) {
                    result = false
                    break
                  }
                }
              }
          }
        }

      } catch {
        case _: FileNotFoundException => println(s"File Not Found: $path") //Util.Erro(s"Arquivo $nomeArquivo não foi encontrado: $path")
        case e: Exception =>
          Util.Erro(e.getStackTraceString)
          result = false
      }

      // Load Data on Hive
      if (result)
        Util.move(path, _hadoopValidadoPathDefault + nomeArquivo)
      else {
        if (Util.error.nonEmpty)
          Util.gravarErro(nomeArquivo, path, _appName, _hadoopErrorPath)
      }
    }
  }

  def validarDado(line: String, index: Int, fields: List[Field]): Boolean = {

    val dataLine = line.split(_sep, -1).toList

    if (dataLine.size != fields.size) {
      Util.Erro(s"Quantidade de registos diferente do configurado $nomeArquivo na linha $index \n$line")
      return false
    } else {

      fields.zipWithIndex.foreach {
        case (field, i) =>
          field.`type`.toLowerCase match {
            case "int" =>
              if (Try(dataLine(i).toInt).isFailure) {
                Util.Erro(s"Dado inválido (int) $nomeArquivo [linha: ${index + 1} coluna: ${i + 1} valor encontrado: ${dataLine(i)}]")
                return  false
              }
            case "decimal" | "float" =>
              if (Try(dataLine(i).toFloat).isFailure) {
                Util.Erro(s"Dado inválido (float) $nomeArquivo [linha: ${index + 1} coluna: ${i + 1} valor encontrado: ${dataLine(i)}]")
                return false
              }
            case "date" =>
              if (!Util.isData(dataLine(i))) {
                Util.Erro(s"Dado inválido (date)[yyyy-MM-dd] $nomeArquivo [linha: ${index + 1} coluna: ${i + 1} valor encontrado: ${dataLine(i)}]")
                return false
              }
            case _ =>
          }
      }

    }

    true

  }


  def validarHead(line: String, head: List[Field]): Boolean = {

    val headLine = line.split(_sep, -1).toList

    if (headLine.size != head.size) {
      Util.Erro(s"Os campos do cabeçalho estão diferentes do configurado: $nomeArquivo")
      return false
    } else {

      head.zipWithIndex.foreach{
        case (h, i) =>
          if (h.name.toLowerCase != headLine(i).toLowerCase) {
            Util.Erro(s"Campo errado no cabeçado do arquivo: $nomeArquivo. Configurado: ${headLine(i)} Esperado: ${h.name}")
            return false
          }
      }
    }

    true

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

  def tratarArgs(args: Array[String]): Boolean ={

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
          arg.contains("--sep:") => _sep = arg.split(":", -1)(1)
      case arg
        if arg.contains("-t:") ||
          arg.contains("--title:") => _head = arg.split(":", -1)(1).toLowerCase == "t" ||
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
