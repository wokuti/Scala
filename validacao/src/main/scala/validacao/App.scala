package validacao

import java.io.FileNotFoundException

import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.Breaks._

object App {

  val _appName: String = "Validacao"
  val _hadoopUserNameDefault: String = "cloudera"
  val _hadoopErrorPathDefault: String = "hdfs://127.0.0.1:8020/user/cloudera/erro/" // /sistemas/sli/isban/erro

  val _hadoopValidadoPathDefault: String = "hdfs://127.0.0.1:8020/user/cloudera/validado/" // /sistemas/sli/isban/validado

  val _sepDefault: String = ";"
  val _headDefault: Boolean = false
  val _hadoopPathDefault: String = "hdfs://127.0.0.1:8020/user/cloudera/" // /sistemas/sli/isban/inbox
  val _hadoopConfDefault: String = "hdfs://127.0.0.1:8020/user/cloudera/valida_arquivos.cfg"

  var _hadoopPath: String = _hadoopPathDefault
  var _hadoopConf: String = _hadoopConfDefault
  var _sep: String = _sepDefault
  var _head: Boolean = _headDefault

  implicit val formats = DefaultFormats // Json4s needs
  case class Field(name: String, `type`: String)
  case class File(file: String, /*table: String,*/ fields: List[Field])
  case class Json(files: List[File])

  var nomeArquivo: String = ""

//  val dados: ListBuffer[ListBuffer[Any]] = new ListBuffer[ListBuffer[Any]]

  // Json Teste
//  var rawJson = """{"files": [{"file":"indicadores_manuais.csv","fields":[{"name":"ano_mes_ref", "type":"string"},{"name":"empresa_responsavel", "type":"string"},{"name":"cliente", "type":"string"},{"name":"sigla_indicador", "type":"string"},{"name":"valor_indicador", "type":"float"},{"name":"indicador_global", "type":"float"}]}]}"""

  @transient
  val config = new SparkConf().setAppName(_appName)

  @transient
  val sc = new SparkContext(config)

  @transient
  val hiveContext = new HiveContext(sc)

  def main(args: Array[String]): Unit = {

    if (!tratarArgs(args))
      return

    System.setProperty("HADOOP_USER_NAME", Util._hadoopUserName)

    val rawJson = sc.textFile(_hadoopConf)

    val parseJson = parse(rawJson.collect.mkString)

    val jsonConfig = parseJson.extract[Json]

    jsonConfig.files.foreach(validarArquivo)

  }

  def validarArquivo(arquivo: File): Unit = {

//    dados.clear()

    nomeArquivo = arquivo.file

    val path = getPath(arquivo.file)
//    val table = arquivo.table
//      getTable(arquivo.arquivo)

    var result: Boolean = false


    try {

      // Extraction data
//      val file = Source.fromFile(path).getLines.toList
      val file = sc.textFile(path).collect.toList

      // Transformation

      breakable {
        file.filter(_.toString.trim != "").zipWithIndex.foreach {
          case (line, i) =>

            if (_head && i == 0) {
              if (!validarHead(line, arquivo.fields)) {
                result = false
                break // Next File Cabeçalho errado
              }
            }
            else {
              if (validarDado(line, i, arquivo.fields)) {
                result = false
                break
              }

            }
        }
      }

    } catch {
      case e: FileNotFoundException => Util.Erro(s"Arquivo $nomeArquivo não foi encontrado: $path")
      case _: Exception => println
    }

    // Load Data on Hive
    if (result)
      Util.move(path, s"${_hadoopValidadoPathDefault}${nomeArquivo}")
    else
      Util.gravarErro(nomeArquivo, path)
  }

  def getPath(file: String): String = {
    var path = file

    if (!path.contains('/'))
      if (_hadoopPath.last.equals('/'))
        path = _hadoopPath.concat(path)
      else
        path = _hadoopPath.concat("/").concat(path)

    path
  }

  def getTable(name: String): String = {

    var table: String = name

    if (table.contains("/"))
      table = table.reverse.substring(0, table.reverse.indexOf("/")).reverse

    if (table.contains("."))
      table = table.substring(0, table.indexOf("."))

    table

  }

  def validarDado(line: String, index: Int, fields: List[Field]): Boolean = {

    val dataLine = line.split(_sep).toList

    var list: ListBuffer[Any] = new ListBuffer[Any]

    if (dataLine.size != fields.size) {
      Util.Erro(s"Quantidade de registos diferente do configurado $nomeArquivo: $line")
    } else {

      //      breakable {
      fields.zipWithIndex.foreach{

        case(field, i) =>

          field.`type`.toLowerCase match {
            case "int" =>
              if (Try(dataLine(i).toInt).isFailure){
                Util.Erro(s"Dado inválido (int) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
                return false
              }
            case "decimal" | "float" =>
              if (Try(dataLine(i).toFloat).isFailure) {
                Util.Erro(s"Dado inválido (float) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
                return false
              }
            //              case _ => list += dataLine(i)
          }
      }

    }

    true

  }


  def validarHead(line: String, head: List[Field]): Boolean = {

    val headLine = line.split(_sep).toList

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

    println(s"-u:<arg> || --user:<arg>  - Usuário do HDFS default: ${_hadoopUserNameDefault}")
    println(s"-e:<arg> || --error:<arg> - Caminho para Gravar o log de erro default: ${_hadoopErrorPathDefault}")
    println(s"-s:<arg> || --sep:<arg>   - Separador do arquivo CSV default: ${_sepDefault}")
    println(s"-s:<arg> || --sep:<arg>   - Separador do arquivo CSV default: ${_sepDefault}")
    println(s"-t:<arg> || --title:<arg> - Considera o titulo do CSV (s|n) default: ${_headDefault}")
    println(s"-p:<arg> || --path:<arg>  - Caminho dos arquivos de dados default: ${_hadoopPath}")
    println(s"-c:<arg> || --conf:<arg>  - Considera o titulo do CSV (s|n) default: ${_headDefault}")

  }

  def tratarArgs(args: Array[String]): Boolean ={

    Util._hadoopUserName = _hadoopUserNameDefault
    Util._hadoopErrorPath = _hadoopErrorPathDefault

    args.foreach(_.toLowerCase match {

      case arg
        if arg.contains("-u:") ||
          arg.contains("--user:") => Util._hadoopUserName = arg.split(":")(1)
      case arg
        if arg.contains("-e:") ||
          arg.contains("--error:") => Util._hadoopErrorPath = arg.split(":")(1)
      case arg
        if arg.contains("-s:") ||
          arg.contains("--sep:") => _sep = arg.split(":")(1)
      case arg
        if arg.contains("-t:") ||
          arg.contains("--title:") => _head = arg.split(":")(1).toLowerCase == "t" ||
        arg.split(":")(1).toLowerCase == "true" ||
        arg.split(":")(1).toLowerCase == "s" ||
        arg.split(":")(1).toLowerCase == "sim"
      case arg
        if arg.contains("-p:") ||
          arg.contains("--path:") => _hadoopPath = arg.split(":")(1)
      case arg
        if arg.contains("-c:") ||
          arg.contains("--conf:") => _hadoopConf = arg.split(":")(1)
      case "-h" | "--help" =>
        help()
        return false
    })

    true

  }


  //  def validarDado(line: String, index: Int, fields: List[Field]): Unit = {
  //
  //    val dataLine = line.split(_sep).toList
  //
  //    var list: ListBuffer[Any] = new ListBuffer[Any]
  //
  //    if (dataLine.size != fields.size) {
  //      Util.Erro(s"Quantidade de registos diferente do configurado $nomeArquivo: $line")
  //    } else {
  //
  ////      breakable {
  //        fields.zipWithIndex.foreach{
  //
  //          case(field, i) =>
  //
  //            field.`type`.toLowerCase match {
  //              case "int" =>
  //                if (Try(dataLine(i).toInt).isSuccess)
  //                  list += dataLine(i).toInt
  //                else {
  //                  Util.Erro(s"Dado inválido (int) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
  //                  return
  //                }
  //              case "decimal" | "float" =>
  //                if (Try(dataLine(i).toFloat).isSuccess)
  //                  list += dataLine(i).toFloat
  //                else {
  //                  Util.Erro(s"Dado inválido (float) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
  //                  return
  //                }
  ////              case "date" =>
  ////                if (Try(dataLine(i)).isSuccess)
  ////                  list += dataLine(i).toFloat
  ////                else {
  ////                  Util.Erro(s"Dado inválido (float) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
  ////                  return
  ////                }
  //              case _ => list += dataLine(i)
  //            }
  //        }
  //
  //        dados += list
  //
  ////      }
  //
  //    }

}
