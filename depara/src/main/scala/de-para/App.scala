
import java.text.SimpleDateFormat
import java.util.logging.{Level, Logger}
import java.util.{Calendar, Date}

import org.apache.flume.source.avro.AvroFlumeEvent
import org.apache.hadoop.hbase.client.{ConnectionFactory, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.spark.SparkConf
import org.apache.spark.streaming.flume.FlumeUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.util.Try

/**
  * Main
  */
object App {

  val _appName: String = "De-Para"

  val _defaultDuracao: Int = 10
  val _defaultPorta: Int = 10010
  val _defaultHost: String = "127.0.0.1"
//  val _defaultHadoopUserName = "cloudera"
//  val _defaultHadoopPath = "/user/cloudera/depara/out"
  val _defaultExtension = ".DOCCMP"

  val _defaultTableName    : String = "arquivo"
  val _defaultColumnFamily : String = "dado"
  val _defaultColumnName   : String = "txt"

  var file: StringBuffer = new StringBuffer()
  var fileName: String = ""
  var arqProcessado: Int = 0
  var duracao: Int = _defaultDuracao
  var porta: Int = _defaultPorta
  var hostname: String = _defaultHost
//  var hadoopUserName: String = _defaultHadoopUserName
//  var hadoopPath: String = _defaultHadoopPath
  val extension: String = _defaultExtension

  //HBase
  var tableName: String    = _defaultTableName
  var columnFamily: String = _defaultColumnFamily
  var columnName: String = _defaultColumnName

  val config = HBaseConfiguration.create()
  //  var userName: String = _defaultUserName
  //  var passwd: String = _defaultPasswd

  val formatDate = new SimpleDateFormat("yyyyMMdd")

  def main(args: Array[String]): Unit = {

    tratarArgs(args)

    val sparkConfig = new SparkConf().setAppName(_appName)
    val batchInterval = Seconds(duracao)

    val ssc = new StreamingContext(sparkConfig, batchInterval)

    val flumeStream = FlumeUtils.createPollingStream(ssc, hostname, porta)

    flumeStream.count.map(c =>
      "Recebidos: " + c + "\n" +
        "Arquivos: " + arqProcessado
    ).print

    flumeStream.map(e => configArquivo(e.event)).print
//    flumeStream.map(e => println("\n\nteste xxx\n\n")).print

    ssc.start()
    ssc.awaitTermination()

  }

  def tryToInt(s : String) = Try(s.toInt).toOption

  def tratarArgs(args: Array[String]): Unit = {

    for (arg <- args) {
      if (arg.contains("-i:")) {
        arg.replace("]", "").split(':')
        duracao = tryToInt(arg.replace("]", "").split(':')(1)).getOrElse(_defaultDuracao)
      } else if (arg.contains("-p:")) {
        porta = tryToInt(arg.replace("]", "").split(':')(1)).getOrElse(_defaultPorta)
      } else if (arg.contains("-H:")) {
        hostname = arg.replace("]", "").split(':')(1)
//      } else if (arg.contains("-user:")) {
//        hadoopUserName = arg.replace("]", "").split(':')(1)
//      } else if (arg.contains("-path:")) {
//        hadoopPath = arg.replace("]", "").split(':')(1)
      } else if (arg.equals("-h") || arg.equals("--help")) {
        help()
        return
      }
    }

  }

  def help(): Unit = {
    println("-i:<Int> - Intervalo de Duração em segundos")
    println("-p:<Int> - Porta de acesso ao Flume")
    println("-H:<String> - Host de acesso ao Flume")
    println("-user:<String> - Host de acesso ao Flume")
    println("-path:<String> - Host de acesso ao Flume")
    println("-h - Ajuda")
  }

  def configArquivo(event : AvroFlumeEvent): Unit = {

    val nomeArquivo = event.getHeaders.toString
      .substring(1, event.get(0).toString.length - 1)
      .split('=')(1)

    val corpoArquivo = new String(event.getBody.array)

    if (
      (
        nomeArquivo.isEmpty ||
        (!nomeArquivo.isEmpty && !nomeArquivo.equals(fileName))
      ) &&
        !fileName.isEmpty
    ) {

      arqProcessado += 1

      setFile(file.toString)

      val dateFormat = new SimpleDateFormat("yyyy-mm-dd;hh:mm:ss")
      Logger.getLogger(getClass.getName).log(Level.INFO, arqProcessado + ";" + dateFormat.format(new Date()))

      file.setLength(0)

    }

    fileName = nomeArquivo
    file.append(corpoArquivo + "\n")

  }

//  def setFile(file: String): Unit = {
//
//    System.setProperty("HADOOP_USER_NAME", hadoopUserName)
//    val conf = new Configuration()
//    val fs = FileSystem.get(URI.create(hadoopPath), conf)
//    val out = fs.create(new Path(hadoopPath + "/" + fileName + extension))
//
//    try {
//
//      out.write(file.toString.getBytes)
//
//      out.flush()
//
//    } finally {
//
//      out.close()
//
//    }
//  }

  // HBase
  def setFile(file: String): Unit = {

    val connection = ConnectionFactory.createConnection(config)

    val table = connection.getTable(TableName.valueOf(tableName))

    try {

      val p = new Put(Bytes.toBytes(formatDate.format(Calendar.getInstance.getTime) + "|" + fileName + extension))

      p.addColumn(
        Bytes.toBytes(columnFamily),
        Bytes.toBytes(columnName),
        Bytes.toBytes(file)
      )

      table.put(p)

    } catch {

      case e: Exception => println(e)

    } finally {
      table.close()
      connection.close()
    }
  }

}
