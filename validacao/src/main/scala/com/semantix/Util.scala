package com.semantix

import java.net.URI
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Date

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.log4j.LogManager
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SaveMode}

import scala.collection.mutable.ListBuffer
import scala.util.Try

case class Field(name: String, `type`: String)
case class File(file: String, table: String, overwrite: Boolean, timestamp: Boolean, fields: List[Field])
case class Json(files: List[File])

import org.apache.spark.sql.functions._

// Json Teste
//  var rawJson = """{"files": [{"file":"indicadores_manuais.csv","fields":[{"name":"ano_mes_ref", "type":"string"},{"name":"empresa_responsavel", "type":"string"},{"name":"cliente", "type":"string"},{"name":"sigla_indicador", "type":"string"},{"name":"valor_indicador", "type":"float"},{"name":"indicador_global", "type":"float"}]}]}"""


/**
  * Created by hideki on 27/01/17.
  */
object Util {

//    val _hdfsPath: String = "hdfs://srvbdrirlbr01.santanderbr.pre.corp"
//    val _hdfsPath: String = "hdfs://quickstart.cloudera:8020"
  val _hdfsPath: String = ""

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm")

  val dateStamp = new SimpleDateFormat("yyyy-MM-dd' 'HH:mm")

  val dataBase = new java.text.SimpleDateFormat("yyyy-MM-dd")

  var error: ListBuffer[String] = new ListBuffer[String]

  @transient
  val conf = new Configuration()

  @transient
  lazy val log = LogManager.getLogger("sli_job_carga")

  def Erro(str: String): Unit ={

    error += str
  }

  def gravarErro(file: String, path: String, local: String, errorPath: String): Unit = {

    log.info(s"Gravando error $errorPath")

    write(errorPath, s"${dateFormat.format(new Date())}_$file.${local.toLowerCase}.erro", error.mkString.getBytes)

    move(path, s"$errorPath${dateFormat.format(new Date())}_$file")

    error.clear

  }

  def write(uri: String, filePath: String, data: Array[Byte]): Unit = {

    log.info(s"Write\nuri:$uri\nfilePath:$filePath")

    val fs = FileSystem.get(new URI(uri), conf)

    val path = new Path(uri + filePath)

    Try(fs.delete(path, true))

    val os = fs.create(path)

    try {

      os.write(data)
      os.flush()

    } finally {
      os.close()
      fs.close()
    }

  }

  def move(src: String, dest: String): Unit = {

    log.info(s"Move File\nSrc: $src\nDest: $dest")

    val fs = FileSystem.get(conf)

    try {

      //Remove arquivo antes de inserir
      Try(fs.delete(new Path(dest), true))

      fs.rename(new Path(src), new Path(dest))

    } finally {
      fs.close()
    }

  }

  def exist(src: String): Boolean = {

    val fs = FileSystem.get(conf)

    try {
      fs.exists(new Path(src))
    } finally {
      fs.close()
    }

  }

  def delete(src: String): Unit = {

    val fs = FileSystem.get(conf)

    Try(fs.delete(new Path(src), true))

    fs.close()

  }

  def getFiles(path: String): Array[FileStatus] = {

    val fs = FileSystem.get(conf)

    try {

      val arqs = fs.listStatus(new Path(path))

      arqs.filter(f => !f.isDirectory && !f.isSymlink)

    } finally {

      fs.close()

    }

  }

  def getFileName(file: String): String = {

    var fileName = file

    if (fileName.contains('/'))
      fileName = fileName.reverse.substring(0, fileName.reverse.indexOf("/")).reverse

    fileName

  }

  def getPath(file: String, hadoopSource: String): String = {
    var path = file

    if (!path.contains('/'))
      if (hadoopSource.last.equals('/'))
        path = hadoopSource.concat(path)
      else
        path = hadoopSource.concat("/").concat(path)

    path
  }

  def getLakePath(file: String, hadoopSource: String): String = {
    var path = file

    path = hadoopSource.concat(getFileName(path))

    path
  }

  def getDir(name: String): String = {

    var dir: String = name

    dir = getFileName(name)

    if (dir.contains("."))
      dir = dir.substring(0, dir.indexOf("."))

    dir

  }

  def insertData(arquivo: File, dados: DataFrame, hiveContext: HiveContext): Unit = {

    if (arquivo.overwrite)
      dados.write.mode(SaveMode.Overwrite).saveAsTable(arquivo.table)
    else
      dados.write.mode(SaveMode.Append).saveAsTable(arquivo.table)

  }

  def isData(str: String): Boolean = {

    val arr = str.split("-", -1)

    if (arr.length == 3) { // Têm três elementos
      if (arr(0).length == 4) { // Ano
        if (Try(arr(0).toInt).isSuccess) {
          if (arr(1).length == 2) { // Mês
            if (Try(arr(1).toInt).isSuccess) {
              if (Try(arr(1).toInt).get > 0 &&
                Try(arr(1).toInt).get < 13) {
                if (arr(2).length == 2) { // Dia
                  if (Try(arr(2).toInt).isSuccess) {
                    if (Try(arr(2).toInt).get > 0 && Try(arr(2).toInt).get < 32) {
                      if (Try(toDate(str)).isSuccess) {
                        return true
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    false

  }

  def toDate(str: String): Timestamp = {

    new Timestamp(dataBase.parse(str).getTime)

  }

  def getShema(list: List[Field], timeStamp: Boolean): StructType = {

    log.info("Construindo o Schema")

    var schema: ListBuffer[StructField] = new ListBuffer[StructField]()

    list.foreach{f =>

      f.`type`.toLowerCase match {
        case "int" => schema += StructField(f.name, IntegerType)
        case "decimal" | "float" => schema += StructField(f.name, FloatType)
        case "date" => schema += StructField(f.name, TimestampType)
        case _ => schema += StructField(f.name, StringType)
      }

    }

    if (timeStamp) {
      log.info("Adicionando Data de Última Atualização")
      schema += StructField("dt_ult_atz", TimestampType)
    }

    StructType(schema.toList)

  }

}
