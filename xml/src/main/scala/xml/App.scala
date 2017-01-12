import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.logging.{Level, Logger}
import java.util.{Date, GregorianCalendar}
import javax.xml.bind.{JAXBContext, Marshaller}
import javax.xml.datatype.{DatatypeFactory, XMLGregorianCalendar}

import org.apache.flume.source.avro.AvroFlumeEvent
import org.apache.hadoop.hbase.client.{ConnectionFactory, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.spark.SparkConf
import org.apache.spark.streaming.flume.FlumeUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import xml.{Multiplas, ObjectFactory}

import scala.collection.mutable.ListBuffer
import scala.util.Try

object App {

  val _appName: String = "XML"

  val _defaultDuracao      : Int    = 10
  val _defaultPorta        : Int    = 10010
  val _defaultHost         : String = "127.0.0.1"
  val _defaultTableName    : String = "arquivo"
  val _defaultColumnFamily : String = "dado"
  val _defaultColumnName   : String = "xml"

//  val _defaultUserName  : String = "cloudera"
//  val _defaultPasswd    : String = ""

//  val _defaultHadoopUserName = "cloudera"
//  val _defaultHadoopPath     = "/user/cloudera/xml/out"

  val _defaultExtension = ".xml"

  var file: ListBuffer[String] = new ListBuffer[String]()
  var fileName: String   = ""
  var arqProcessado: Int = 0

  var duracao: Int      = _defaultDuracao
  var porta: Int        = _defaultPorta
  var hostname: String  = _defaultHost
  var extension: String = _defaultExtension

  //HBase
  var tableName: String    = _defaultTableName
  var columnFamily: String = _defaultColumnFamily
  var columnName: String = _defaultColumnName
  val config = HBaseConfiguration.create()
  //  var userName: String = _defaultUserName
  //  var passwd: String = _defaultPasswd

  var hadoopUserName: String = _defaultHadoopUserName
  var hadoopPath: String     = _defaultHadoopPath

  val formatDate = new SimpleDateFormat("yyyyMMdd")

  //XML
  val context = JAXBContext.newInstance("xml")
  val jaxbMarshaller = context.createMarshaller


  def tryToInt(s : String) = Try(s.toInt).toOption

  def slice(s: String, inicio: Int, fim: Int) = s.substring(inicio, fim).trim

//  def main(args: Array[String]): Unit = {
//
//    tratarArgs(args)
//
////    val in = new FileInputStream("/home/hideki/Documentos/Projetos/projeto_tivit/data_examples/xml_arquivo_original.txt")
//
//    var sw: StringWriter = new StringWriter()
//
//    val file = Source.fromFile("/home/hideki/Documentos/Projetos/projeto_tivit/data_examples/xml_arquivo_original.txt").getLines.toList
//
//    val m = getMultiplas(file)
//
//    val context = JAXBContext.newInstance("xml")
//    val jaxbMarshaller = context.createMarshaller
//
//    jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)
//
//    jaxbMarshaller.marshal(m, sw)
//
//    println(sw)
//
//  }

  def main(args: Array[String]): Unit = {

    tratarArgs(args)

    val sparkConfig = new SparkConf().setAppName(_appName)
    val batchInterval = Seconds(duracao)
    jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)

    val ssc = new StreamingContext(sparkConfig, batchInterval)

    val flumeStream = FlumeUtils.createPollingStream(ssc, hostname, porta)

    flumeStream.count().map(c =>
      "Recebidos: " + c + "\n" +
        "Arquivos: " + arqProcessado
    ).print

    flumeStream.map(e => configArquivo(e.event)).print

    ssc.start()
    ssc.awaitTermination()

  }

  def tratarArgs (args: Array[String]) {

    for (arg <- args) {
      if (arg.contains("-d:")) {
        arg.replace("]", "").split(':')
        duracao = tryToInt(arg.replace("]", "").split(':')(1)).getOrElse(_defaultDuracao)
      } else if (arg.contains("-port:")) {
        porta = tryToInt(arg.replace("]", "").split(':')(1)).getOrElse(_defaultPorta)
      } else if (arg.contains("-host:")) {
        hostname = arg.replace("]", "").split(':')(1)
//      } else if (arg.contains("-u:")) {
//        userName = arg.replace("]", "").split(':')(1)
      } else if (arg.contains("-t:")) {
        tableName = arg.replace("]", "").split(':')(1)
//      } else if (arg.contains("-pw:")) {
//        passwd = arg.replace("]", "").split(':')(1)
      }
    }

  }

  def configArquivo(event : AvroFlumeEvent): Unit = {

    val nomeArquivo = event.getHeaders.toString
      .substring(1, event.get(0).toString.length - 1)
      .split('=')(1)

    val corpoArquivo = new String(event.getBody.array)

    if (!nomeArquivo.isEmpty && !nomeArquivo.equals(fileName) && !fileName.isEmpty) {

      arqProcessado += 1

      val dateFormat = new SimpleDateFormat("yyyy-mm-dd;hh:mm:ss")
      Logger.getLogger(getClass.getName).log(Level.INFO, arqProcessado + ";" + dateFormat.format(new Date()))

      file.clear()

    }

    fileName = nomeArquivo
    file += corpoArquivo + "\n"

  }

//  HDFS
//  def setFile(file: List[String]): Unit = {
//
//    System.setProperty("HADOOP_USER_NAME", hadoopUserName)
//    val conf = new Configuration()
//    val fs = FileSystem.get(URI.create(hadoopPath), conf)
//    val out = fs.create(new Path(hadoopPath + "/" + fileName + extension))
//
//    try {
//
//      val m = getMultiplas(file)
//      jaxbMarshaller.marshal(m, out)
//      out.flush()
//
//    } finally {
//
//      out.close()
//
//    }
//  }

// HBase
  def setFile(file: List[String]): Unit = {

    val m = getMultiplas(file)

    val sw: StringWriter = new StringWriter()

    jaxbMarshaller.marshal(m, sw)

    val connection = ConnectionFactory.createConnection(config)

    val table = connection.getTable(TableName.valueOf(tableName))

    try {

      val p = new Put(Bytes.toBytes(formatDate.format(m.getDataGeracaoArq) + "|" + fileName + extension))

      p.addColumn(
        Bytes.toBytes(columnFamily),
        Bytes.toBytes(columnName),
        Bytes.toBytes(sw.toString)
      )

      table.put(p)

    } finally {
      table.close()
      connection.close()
    }
  }

  def getXMLGregorianCalendar(year: String, month: String, day: String): Option[XMLGregorianCalendar] = {

    try{

      val date = new GregorianCalendar(
        year.toInt,
        month.toInt,
        day.toInt)

      Option(DatatypeFactory.newInstance().newXMLGregorianCalendar(date))

    } catch {
      case e: Exception =>
        e.printStackTrace()
        None
    }
  }

  def getCodigoProduto(value: String): Int = {

    value match {
      case "01" => 44
      case "03" => 8
      case "05" => 45
      case "08" => 5
      case "30" => 46
      case "31" => 31
      case _ =>  0
    }

    //01=44;02==8;03==4;05=45;08=5;30=46;31=31

  }

  def getCodigoFinalidade(value: String): String = {

    value match {
      case "01" => "1"
      case "03" => "2"
      case "09" => "3"
      case _ => "0"
    }

    //01=1;03=2;09=3
  }

  def getBarra(value: String, codBarra: Boolean = true): String = {

    var retorno = ""
    val posicao = slice(value, 263, 264)

    //se modalidade pagamento == 30 ou 31 => posição = 399,399
    if (posicao.equals("30") || posicao.equals("31")) {
      if (codBarra)
        retorno = slice(value, 399, 399)
    } else {
      if (!codBarra)
        retorno = slice(value, 373, 412)
    }

    retorno

  }

  def getMultiplas(file: List[String]): Multiplas = {

    //File f = new File(outputFile)

    val header = file.head
    //    val trailler = file.lift(file.length - 1)
    var numeroLote = 1

    ///////////////////
    //   MULTIPLAS   //
    ///////////////////
    val factory = new ObjectFactory
    val multiplas = factory.createMultiplas
    multiplas.setTipoArquivo("1")
    multiplas.setTipoInscricao(  slice(header, 9, 10) )
    multiplas.setNumeroInscricao( slice(header, 10, 25) )
    multiplas.setCodConvenio( slice(header, 1, 9) )
    multiplas.setNumParanTransmicao("0")
    multiplas.setDsAmbienteCliente("")
    multiplas.setNumeroBanco(237)
    multiplas.setNumeroDigAgencia("0")
    multiplas.setNumeroConta("0")
    multiplas.setDsDigConta(" ")
    multiplas.setDsNomeEmpresa(slice(header, 25, 65))
    multiplas.setDsNomeBanco("BANCO BRADESCO SA")
    multiplas.setNumeroNsa( slice(header, 69, 73).toInt  )
    multiplas.setDsResEmpresa(" ")
    multiplas.setNumeroVersao("0")
    multiplas.setHoraGeracaoArq(slice(header, 86, 94))

    multiplas.setDataGeracaoArq(
      getXMLGregorianCalendar(
        year = slice(header, 78, 82),
        month = slice(header, 82, 84),
        day = slice(header, 84, 86)
        ).orNull)

    multiplas.setNumeroAgencia("0")
    multiplas.setStatus(0)
    multiplas.setTipoOrigemSistema("")
    multiplas.setConteudoRemessa(slice(header, 0, 499))

    var listaLotes = multiplas.getLotes
    multiplas.setNumQtdeLotes(listaLotes.size)

    //////////////
    //   LOTE   //
    //////////////
    var lote = factory.createMultiplasLotes()

    numeroLote += 1

    lote.setNumeroLoteServico(numeroLote)
    lote.setCodTipoServico(0)
    lote.setCodFormaLancamento(0)
    //        Integer.parseInt(slice(header, 9, 10))
    lote.setCodTipoInscricao(0)
    lote.setNumeroInscricao( slice(header, 10, 25) )
    lote.setCodConvenio( slice(header, 1, 9) )
    lote.setCodTipoCompromisso(0)
    lote.setCodCompromisso(0)
    lote.setDsParanTransmissao(0)
    //maybe here
    lote.setNumeroAgencia(0)
    lote.setNumeroDigAgencia("0")
    lote.setNumeroConta("0")
    lote.setDsDigConta("0")
    lote.setDsNomeEmpresa(slice(header, 25,65))
    lote.setDsMsgAviso(" ")
    lote.setDsLogradouro(" ")
    lote.setNumeroLocal("")
    lote.setDsComplemento(" ")
    lote.setDsCidade(" ")
    lote.setNumeroCep("")
    lote.setDsSiglaEstado("")
    lote.setNumeroVersao(0)
    //        BigDecimal bigD = new BigDecimal( slice(trailler, 7, 23) );
    lote.setNumeroValorTotal(null)
    lote.setNumeroValorTotalValido(null)
    lote.setNumeroQtdeRegistro(0)
    lote.setConteudoLote(slice(header, 0, 499))
    val listaPagamentos = lote.getPagamentos

    listaLotes.add(lote)

    for(i <- 1 to file.length - 2) {
      var transacao = file.lift(i).getOrElse("")

      ///////////////////
      //   PAGAMENTO   //
      ///////////////////
      var pagamento = factory.createMultiplasLotesPagamentos()
      pagamento.setCodConvenio( slice(header, 1, 9) )
      pagamento.setCodSituacao(1)
      pagamento.setCodProduto(getCodigoProduto(transacao.substring(263, 265)))
      pagamento.setCodFinalidade(1)
      pagamento.setNumeroBancoCred(transacao.substring(95, 98))
      //            Integer.parseInt(transacao.substring(104, 107))
      pagamento.setNumeroAgenciaCread(0)
      pagamento.setNumeroAcAgenciaCred(transacao.substring(103, 104))
      pagamento.setNumeroContaCred(transacao.substring(104, 117))
      pagamento.setNumeroAcContaCred(transacao.substring(117, 118))
      pagamento.setDsNomeFavorecido(transacao.substring(17, 47))
      pagamento.setDsCpfCnpjFavorecido(transacao.substring(2, 17))
      //            Integer.parseInt(transacao.substring(1,2))
      pagamento.setNumeroTipoPessoaFavorecido(0)

      //            Double.parseDouble(transacao.substring(204,219))
      pagamento.setNumeroValor(0.0)

      pagamento.setNumeroNossoNumero(transacao.substring(138, 150))
      pagamento.setNumeroSisNumero(transacao.substring(119, 135))
      pagamento.setNumeroSeuNumero(transacao.substring(150, 165))
      pagamento.setCodLote(0)
      pagamento.setFinalidadeDoc(getCodigoFinalidade(transacao.substring(382,383)))
      pagamento.setNumeroLocal("")
      pagamento.setDsComplemento("")
      pagamento.setDsBairro("")
      pagamento.setDsCidade("")
      //            Integer.parseInt(transacao.substring(87,92))
      pagamento.setNumeroCep(0)
      pagamento.setNumeroCompleCep(transacao.substring(92, 95))
      pagamento.setDsSiglaEstado("")
      pagamento.setTipoConta( transacao.substring(478, 478) )
      pagamento.setDsLogradouro( transacao.substring(47, 86) )
      //            Integer.parseInt(transacao.substring(288,289))
      pagamento.setCodMovimento(0)
      pagamento.setCodIdentProduto("")
      pagamento.setCodSegEmpresa("")
      pagamento.setCodSicap(transacao.substring(249, 250))
      pagamento.setDSNomePagador(slice(header, 25, 65))
      pagamento.setDsCampoLivreBarra( transacao.substring(377, 402) )


      pagamento.setNumDVCodBarras(getBarra(transacao))
      //            new BigDecimal(transacao.substring(194, 204))
      pagamento.setNumeroValorDocumento(null)

      pagamento.setNumeroValorTitulo(null)
      //            new BigDecimal(transacao.substring(219, 234))
      pagamento.setNumeroValorDesconto(null)

      pagamento.setNumeroValorMulta(null)
      pagamento.setFatorVencimento(transacao.substring(190, 194))
      pagamento.setCamara( transacao.substring(472, 476) )
      pagamento.setInfo01( transacao.substring(289, 291) )
      pagamento.setInfo02( transacao.substring(415, 442) )

      //System.out.println(transacao.substring(265, 269));
      //System.out.print(transacao.substring(269, 271));
      //System.out.print(transacao.substring(271, 273));

      pagamento.setDataPagamento(
        getXMLGregorianCalendar(
          year = transacao.substring(265, 269),
          month = transacao.substring(269, 271),
          day = transacao.substring(271, 273)
        ).orNull
      )


      pagamento.setDataVencimento(
        getXMLGregorianCalendar(
          year = transacao.substring(165, 169),
          month = transacao.substring(169, 171),
          day = transacao.substring(171, 173)
        ).orNull
      )


      pagamento.setQtdParcelas(0)
      pagamento.setIndBloqueio("")
      pagamento.setIndParcelamento("")
      pagamento.setPeriodoVencimento(0)
      pagamento.setNroParcela(0)
      pagamento.setAvisoFavorecido( getBarra( transacao, false ) ) //se nao transacao.substring(373,412)
      pagamento.setOcorrencia("")
      pagamento.setAutenticacao("")
      pagamento.setNumeroQtdeRegistroDetalhe(0)
      pagamento.setSegmento("1")
      pagamento.setConteudoPagamento( transacao.substring(0, 499) )

      val listaDetalhesB = pagamento.getDetalhesB
      val detalhesB = factory.createMultiplasLotesPagamentosDetalhesB
      val listaConteudoB =  detalhesB.getConteudo
      listaConteudoB.add("null")
      listaDetalhesB.add(detalhesB)

      val listaDetalhesC = pagamento.getDetalhesC
      val detalhesC = factory.createMultiplasLotesPagamentosDetalhesC
      val listaConteudoC =  detalhesC.getConteudo
      listaDetalhesC.add(detalhesC)

      val listaDetalhesD = pagamento.getDetalhesD
      val detalhesD = factory.createMultiplasLotesPagamentosDetalhesD
      val listaConteudoD =  detalhesD.getConteudo
      listaDetalhesD.add(detalhesD)

      val listaDetalhesE = pagamento.getDetalhesE
      val detalhesE = factory.createMultiplasLotesPagamentosDetalhesE
      val listaConteudoE =  detalhesE.getConteudo
      listaDetalhesE.add(detalhesE)

      val listaDetalhesF = pagamento.getDetalhesF
      val detalhesF = factory.createMultiplasLotesPagamentosDetalhesF
      val listaConteudoF =  detalhesF.getConteudo
      listaDetalhesF.add(detalhesF)

      val listaDetalhesH = pagamento.getDetalhesH
      val detalhesH = factory.createMultiplasLotesPagamentosDetalhesH
      val listaConteudoH =  detalhesH.getConteudo
      listaDetalhesH.add(detalhesH)

      val listaDetalhesI = pagamento.getDetalhesI
      val detalhesI = factory.createMultiplasLotesPagamentosDetalhesI
      val listaConteudoI =  detalhesI.getConteudo
      listaDetalhesI.add(detalhesI)

      val listaDetalhesJ = pagamento.getDetalhesJ
      val detalhesJ = factory.createMultiplasLotesPagamentosDetalhesJ
      val listaConteudoJ =  detalhesJ.getConteudo
      listaDetalhesJ.add(detalhesJ)

      val listaDetalhesL = pagamento.getDetalhesL
      val detalhesL = factory.createMultiplasLotesPagamentosDetalhesL
      val listaConteudoL =  detalhesL.getConteudo
      listaDetalhesL.add(detalhesL)

      val listaDetalhesM = pagamento.getDetalhesM
      val detalhesM = factory.createMultiplasLotesPagamentosDetalhesM
      val listaConteudoM =  detalhesM.getConteudo
      listaDetalhesM.add(detalhesM)

      val listaDetalhesP = pagamento.getDetalhesP
      val detalhesP = factory.createMultiplasLotesPagamentosDetalhesP
      val listaConteudoP =  detalhesP.getConteudo
      listaDetalhesP.add(detalhesP)

      val listaDetalhesQ = pagamento.getDetalhesQ
      val detalhesQ = factory.createMultiplasLotesPagamentosDetalhesQ
      val listaConteudoQ =  detalhesQ.getConteudo
      listaDetalhesQ.add(detalhesQ)

      val listaDetalhesR = pagamento.getDetalhesR
      val detalhesR = factory.createMultiplasLotesPagamentosDetalhesR
      val listaConteudoR =  detalhesR.getConteudo
      listaDetalhesR.add(detalhesR)

      val listaDetalhesS = pagamento.getDetalhesS
      val detalhesS = factory.createMultiplasLotesPagamentosDetalhesS
      val listaConteudoS =  detalhesS.getConteudo
      listaDetalhesS.add(detalhesS)

      val listaDetalhesT = pagamento.getDetalhesT
      val detalhesT = factory.createMultiplasLotesPagamentosDetalhesT
      val listaConteudoT =  detalhesT.getConteudo
      listaDetalhesT.add(detalhesT)

      val listaDetalhesU = pagamento.getDetalhesU
      val detalhesU = factory.createMultiplasLotesPagamentosDetalhesU
      val listaConteudoU =  detalhesU.getConteudo
      listaDetalhesU.add(detalhesU)

      val listaDetalhesW = pagamento.getDetalhesW
      val detalhesW = factory.createMultiplasLotesPagamentosDetalhesW
      val listaConteudoW =  detalhesW.getConteudo
      listaDetalhesW.add(detalhesW)

      val listaDetalhesY = pagamento.getDetalhesY
      val detalhesY = factory.createMultiplasLotesPagamentosDetalhesY
      val listaConteudoY =  detalhesY.getConteudo
      listaDetalhesY.add(detalhesY)

      val listaDetalhesZ = pagamento.getDetalhesZ
      val detalhesZ = factory.createMultiplasLotesPagamentosDetalhesZ
      val listaConteudoZ =  detalhesZ.getConteudo
      listaDetalhesZ.add(detalhesZ)

      listaPagamentos.add(pagamento)
    }

    multiplas

  }

}
