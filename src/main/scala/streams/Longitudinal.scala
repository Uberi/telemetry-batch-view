package telemetry.streams

import awscala._
import awscala.s3._
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.generic.{GenericRecord, GenericRecordBuilder}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.json4s.jackson.JsonMethods._
import scala.collection.JavaConverters._
import telemetry.{DerivedStream, ObjectSummary}
import telemetry.DerivedStream.s3
import telemetry.heka.{HekaFrame, Message}
import telemetry.parquet.ParquetFile
import scala.util.Random
import collection.JavaConversions._

case class Longitudinal(prefix: String) extends DerivedStream {
  override def streamName: String = "telemetry-release"

  override def filterPrefix: String = prefix

  override def transform(sc: SparkContext, bucket: Bucket, summaries: RDD[ObjectSummary], from: String, to: String) {
    val prefix = s"generationDate=$to"

    if (!isS3PrefixEmpty(prefix)) {
      println(s"Warning: prefix $prefix already exists on S3!")
      return
    }

    val groups = DerivedStream.groupBySize(summaries.collect().toIterator)
    val clientMessages = sc.parallelize(groups, groups.size)
      .flatMap(x => x)
      .flatMap{ case obj =>
        val hekaFile = bucket.getObject(obj.key).getOrElse(throw new Exception("File missing on S3"))
        for (message <- HekaFrame.parse(hekaFile.getObjectContent(), hekaFile.getKey()))  yield message }
      .flatMap{ case message =>
        val fields = HekaFrame.fields(message)
        val clientId = fields.get("clientId")

        clientId match {
          case Some(client: String) => List((client, fields))
          case _ => Nil
        }}
      .groupByKey()

    clientMessages
      .values
      .foreachPartition{ case clientIterator =>
        val schema = buildSchema
        val records = for {
          client <- clientIterator
          record <- buildRecord(client, schema)
        } yield record

        while(!records.isEmpty) {
          val localFile = ParquetFile.serialize(records, schema)
          uploadLocalFileToS3(localFile, prefix)
        }
    }

    println("Number of clients", clientMessages.count())
  }

  private def buildSchema: Schema = {
    val histogramArray = SchemaBuilder.array().items().longType()
    val histogramMap = SchemaBuilder.map().values().longType()

    SchemaBuilder
      .record("Submission")
      .fields
      .name("clientId").`type`().stringType().noDefault()
      .name("os").`type`().stringType().noDefault()
      .name("creationTimestamp").`type`().array().items().doubleType().noDefault()
      .name("simpleMeasurements").`type`().array().items().stringType().noDefault()
      .name("log").`type`().array().items().stringType().noDefault()
      .name("info").`type`().array().items().stringType().noDefault()
      .name("addonDetails").`type`().array().items().stringType().noDefault()
      .name("addonHistograms").`type`().array().items().stringType().noDefault()
      .name("histograms").`type`().array().items().stringType().noDefault()
      .name("keyedHistograms").`type`().array().items().stringType().noDefault()
      .name("settings").`type`().array().items().stringType().noDefault()
      .name("profile").`type`().array().items().stringType().noDefault()
      .name("build").`type`().array().items().stringType().noDefault()
      .name("partner").`type`().array().items().stringType().noDefault()
      .name("system").`type`().array().items().stringType().noDefault()
      .name("histogramArray").`type`().optional().array().items(histogramArray)
      .name("histogramMap").`type`().optional().array().items(histogramMap)
      .name("histogram").`type`().optional().map().values().array().items().longType()
      .endRecord
  }

  private def buildRecord(history: Iterable[Map[String, Any]], schema: Schema): Option[GenericRecord] = {
    // Sort records by timestamp
    val sorted = history
      .toList
      .sortWith((x, y) => {
                 (x("creationTimestamp"), y("creationTimestamp")) match {
                   case (creationX: Double, creationY: Double) =>
                     creationX < creationY
                   case _ =>
                     return None  // Ignore 'unsortable' client
                 }
               })

    def generateRandomArrayHistogram(length: Int, max: Int): Array[Long] = {
      for {
        i <- 1L.to(length).toArray
        r = Random.nextInt(max).toLong
      } yield r
    }

    def generateRandomMapHistogram(length: Int, max: Int): java.util.Map[String, Long] = {
      val histogram = generateRandomArrayHistogram(length, max)
      1.to(length).map(_.toString).zip(histogram).toMap.asJava
    }

    def generateHistogram(length: Int, max: Int, num: Int): java.util.Map[String, Array[Long]] = {
      val h = for {
        index <- 1.to(length).map(_.toString)
      } yield (index, generateRandomArrayHistogram(num, max))
      h.toMap.asJava
    }

    val root = new GenericRecordBuilder(schema)
      .set("clientId", sorted(0)("clientId").asInstanceOf[String])
      .set("os", sorted(0)("os").asInstanceOf[String])
      .set("creationTimestamp", sorted.map(x => x("creationTimestamp").asInstanceOf[Double]).toArray)
      .set("simpleMeasurements", sorted.map(x => x.getOrElse("payload.simpleMeasurements", "").asInstanceOf[String]).toArray)
      .set("log", sorted.map(x => x.getOrElse("payload.log", "").asInstanceOf[String]).toArray)
      .set("info", sorted.map(x => x.getOrElse("payload.info", "").asInstanceOf[String]).toArray)
      .set("addonDetails", sorted.map(x => x.getOrElse("payload.addonDetails", "").asInstanceOf[String]).toArray)
      .set("addonHistograms", sorted.map(x => x.getOrElse("payload.addonHistograms", "").asInstanceOf[String]).toArray)
      .set("histograms", sorted.map(x => x.getOrElse("payload.histograms", "").asInstanceOf[String]).toArray)
      .set("keyedHistograms", sorted.map(x => x.getOrElse("payload.keyedHistograms", "").asInstanceOf[String]).toArray)
      .set("settings", sorted.map(x => x.getOrElse("environment.settings", "").asInstanceOf[String]).toArray)
      .set("profile", sorted.map(x => x.getOrElse("environment.profile", "").asInstanceOf[String]).toArray)
      .set("build", sorted.map(x => x.getOrElse("environment.build", "").asInstanceOf[String]).toArray)
      .set("partner", sorted.map(x => x.getOrElse("environment.partner", "").asInstanceOf[String]).toArray)
      .set("system", sorted.map(x => x.getOrElse("environment.system", "").asInstanceOf[String]).toArray)
      .set("histogramArray", List.concat(sorted.map(x => generateRandomArrayHistogram(100, 10000))).toArray)
      .set("histogramMap", List.concat(sorted.map(x => generateRandomMapHistogram(100, 10000))).toArray)
      .set("histogram", generateHistogram(100, 10000, sorted.length))
      .build

    Some(root)
  }
}
