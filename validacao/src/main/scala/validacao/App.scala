import java.io.FileNotFoundException

import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try
import util.control.Breaks._

object App {

  val _appName: String = "Validacao"
  val _hadoopUserNameDefault: String = "cloudera"
  val _sepDefault: String = ";"
  val _headDefault: Boolean = true
  val _hadoopPathDefault: String = "hdfs://user/cloudera/"
  val _hadoopConfDefault: String = "hdfs://user/cloudera/valida_arquivos.cfg"

  var _hadoopPath: String = _hadoopPathDefault
  var _hadoopConf: String = _hadoopConfDefault
  var _sep: String = _sepDefault
  val _head: Boolean = _headDefault

  implicit val formats = DefaultFormats
  case class Field(name: String, `type`: String)
  case class File(arquivo: String, fields: List[Field])
  case class Json(arquivos: List[File])

  var nomeArquivo: String = ""

  val dados: ListBuffer[ListBuffer[Any]] = new ListBuffer[ListBuffer[Any]]

  // Json Teste
//  var rawJson = """{"arquivos": [{"arquivo":"indicadores_manuais.csv","fields":[{"name":"ano_mes_ref", "type":"string"},{"name":"empresa_responsavel", "type":"string"},{"name":"cliente", "type":"string"},{"name":"sigla_indicador", "type":"string"},{"name":"valor_indicador", "type":"float"},{"name":"indicador_global", "type":"float"}]}]}"""

//  var rawJson: RDD[String]

  def main(args: Array[String]): Unit = {

    tratarArgs(args)

    val config = new SparkConf().setAppName(_appName)

    val sc = new SparkContext(config)

    val rawJson = sc.textFile(_hadoopConf)

    val parseJson = parse(rawJson.toString)

    val jsonConfig = parseJson.extract[Json]

    jsonConfig.arquivos.foreach(validarArquivo)



    //    val sparkConfig = new SparkConf().setAppName(_appName)
    //
    //    sparkConfig.

    //        System.setProperty("HADOOP_USER_NAME", _hadoopUserName)
    //        val conf = new Configuration()
    //        val fs = FileSystem.get(URI.create(hadoopPath), conf)
    //        val out = fs.create(new Path(hadoopPath + "/" + fileName + extension))



  }

  def tratarArgs(args: Array[String]): Unit ={

  }

  def validarArquivo(arquivo: File): Unit = {

    dados.clear()

    nomeArquivo = arquivo.arquivo

    val path = getPath(arquivo.arquivo)
    val table = getTable(arquivo.arquivo)


    try {

      // Extraction data
      val file = Source.fromFile(path).getLines.toList

      // Transformation
      file.filter(_.toString.trim != "").zipWithIndex.foreach{
        case (line, i) =>
          if (_head && i == 0) {
            if (!validarHead(line, arquivo.fields))
              return // Next File Cabeçalho errado
          }
          else {
            validarDado(line, i, arquivo.fields)
          }
      }

    } catch {
      case e: FileNotFoundException => gravarError("Arquivo não foi encontrado")
      case _: Exception => println
    }

    // Load Data on Hive
    if (dados.nonEmpty) {

      var sql: String = "insert into " + table

      arquivo.fields.zipWithIndex.foreach {

        case (f, i) =>

          if (i == 0)
            sql += " ("

          sql += f.name

          if (i == arquivo.fields.size - 1)
            sql += ") "
          else
            sql += ","
      }

      sql += " values "

      dados.zipWithIndex.foreach {

        case (dado, i) =>

          dado.toList.zipWithIndex.foreach {

            case (d, j) =>

              if (j == 0)
                sql += " ("

              sql += f(d)

              if (j == dado.size - 1)
                sql += ") "
              else
                sql += ","
          }

        if (i != dados.size - 1)
          sql += ","

      }

      // Gravar dados
      gravarDado(sql)

    }


  }

  def f[T](d: T) = d match {
    case _: String => "\"" + d + "\""
    case _         => d
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

  def validarHead(line: String, head: List[Field]): Boolean = {

    val headLine = line.split(_sep).toList

    if (headLine.size != head.size) {
      gravarError(s"Os campos do cabeçalho estão diferentes do configurado: $nomeArquivo")
      return false
    } else {

      head.zipWithIndex.foreach{
        case (h, i) =>
          if (h.name.toLowerCase != headLine(i).toLowerCase) {
            gravarError(s"Campo errado no cabeçado do arquivo: $nomeArquivo. Configurado: ${headLine(i)} Esperado: ${h.name}")
            return false
          }
      }
    }

    true

  }

  def validarDado(line: String, index: Int, fields: List[Field]): Unit = {

    val dataLine = line.split(_sep).toList

    var list: ListBuffer[Any] = new ListBuffer[Any]

    if (dataLine.size != fields.size) {
      gravarError(s"Quantidade de registos diferente do configurado $nomeArquivo: $line")
    } else {

      breakable {
        fields.zipWithIndex.foreach{

          case(field, i) =>

            field.`type`.toLowerCase match {
              case "int" =>
                if (Try(dataLine(i).toInt).isSuccess)
                  list += dataLine(i).toInt
                else {
                  gravarError(s"Dado inválido (int) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
                  break
                }
              case "decimal" | "float" =>
                if (Try(dataLine(i).toFloat).isSuccess)
                  list += dataLine(i).toFloat
                else {
                  println(s"Dado inválido (float) $nomeArquivo [linha: $index campo: $i valor: ${dataLine(i)}]")
                  break
                }
              case _ => list += dataLine(i)
            }
        }

        dados += list

      }

    }

  }

  def gravarError(string: String): Unit = {
    println(string)

  }

  def gravarDado(sql: String): Unit = {
    println(sql)

  }

}
