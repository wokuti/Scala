
package com.semantix.move

import java.io.FileNotFoundException

import com.semantix.Util
import org.apache.log4j.LogManager
import org.apache.spark.{SparkConf, SparkContext}

object App {

  val _appName: String = "Move"

  val _hadoopUserNameDefault: String = "slihbach"
  val _hadoopSourceDefault: String = Util._hdfsPath + "/sistemas/sli/isban/inbox/" //"/user/teste/"
  val _hadoopDestDefault: String = Util._hdfsPath + "/sistemas/sli/isban/proc/"    //hdfs://127.0.0.1/user/teste2/"

  var _hadoopUserName: String = _hadoopUserNameDefault
  var _hadoopSource: String = _hadoopSourceDefault
  var _hadoopDest: String = _hadoopDestDefault

  @transient
  val config = new SparkConf().setAppName(_appName)

  @transient
  val sc = new SparkContext(config)

  def main(args: Array[String]): Unit = {

    if (!tratarArgs(args))
      return

    System.setProperty("HADOOP_USER_NAME", _hadoopUserName)

    try {

      val arqs = Util.getFiles(_hadoopSource)

      arqs.foreach {
        arq =>
          try {
            val path = arq.getPath
            val directory = Util.getDir(arq.getPath.toString)
            val fileName = Util.getFileName(arq.getPath.toString)

            if (fileName.reverse.substring(0, 4) == ".csv".reverse)
              Util.move(path.toString, _hadoopDestDefault + directory + "/" + fileName)

          } catch {
            case _: FileNotFoundException =>
            case e: Exception => e.printStackTrace()
          }
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }

  }

  def help(): Unit = {

    println("\n*********** Argumentos ***********\n")
    println(s"-u:<arg> || --user:<arg>  - Usuário do HDFS \n    Default: ${_hadoopUserNameDefault}")
    println(s"-o:<arg> || --origem:<arg>   - Origem dos dados    \n    Default: ${_hadoopSourceDefault}")
    println(s"-d:<arg> || --destino:<arg>  - Destino dos dados   \n    Default: ${_hadoopDestDefault}")
    println("\n*********************************\n")

    Util.log.info("\n*********** Argumentos ***********\n")
    Util.log.info(s"-u:<arg> || --user:<arg>  - Usuário do HDFS \n    Default: ${_hadoopUserNameDefault}")
    Util.log.info(s"-o:<arg> || --origem:<arg>   - Origem dos dados    \n    Default: ${_hadoopSourceDefault}")
    Util.log.info(s"-d:<arg> || --destino:<arg>  - Destino dos dados   \n    Default: ${_hadoopDestDefault}")
    Util.log.info("\n*********************************\n")

  }

  def tratarArgs(args: Array[String]): Boolean ={

    args.foreach(_.toLowerCase match {

      case arg
        if arg.contains("-u:") ||
          arg.contains("--user:") =>

        arg.split(":").zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopUserName = ""
            else {
              if (i != arg.split(":").length - 1)
                _hadoopUserName += a + ":"
              else
                _hadoopUserName += a
            }
        }
      case arg
        if arg.contains("-o:") ||
          arg.contains("--origem:") =>

        arg.split(":").zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopSource = ""
            else {
              if (i != arg.split(":").length - 1)
                _hadoopSource += a + ":"
              else
                _hadoopSource += a
            }
        }

      case arg
        if arg.contains("-d:") ||
          arg.contains("--destino:") =>

        arg.split(":").zipWithIndex.foreach{
          case (a, i) =>

            if (i == 0)
              _hadoopDest = ""
            else  {
              if (i != arg.split(":").length - 1)
                _hadoopDest += a + ":"
              else
                _hadoopDest += a
            }

        }

      case "-h" | "--help" | _ =>
        help()
        return false
    })

    true

  }
}
