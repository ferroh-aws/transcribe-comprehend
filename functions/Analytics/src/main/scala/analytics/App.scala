package analytics

import java.io.{BufferedInputStream, File, FileInputStream, FileOutputStream, PrintWriter}
import java.nio.file.Paths
import java.util.UUID

import org.json4s._
import org.json4s.jackson.{Serialization, parseJson, prettyJson}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import org.apache.commons.compress.archivers.{ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.CompressorStreamFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeAction, AttributeValue, AttributeValueUpdate, UpdateItemRequest}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}

import scala.jdk.CollectionConverters._

/**
 * Class used to process the outputs from Comprehend.
 */
class App extends RequestHandler[S3Event, String] {
  /** DynamoDB client used to update the items. */
  val dynamoDbClient: DynamoDbClient = DynamoDbClient.builder().build()
  /** Client to interact with S3 service. */
  val s3Client: S3Client = S3Client.builder().build()
  /** DynamoDB process status table. */
  val tableName: String = sys.env.getOrElse("TABLE_NAME", "transcribe-sentiment-poc-table")
  /** Formats used for JSON Serialization. */
  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  /**
   * Process the S3 event generated after the comprehend process finished.
   *
   * @param input the S3 event.
   * @param context of the execution.
   * @return Success after the processing.
   */
  override def handleRequest(input: S3Event, context: Context): String = {
    val logger = context.getLogger
    logger.log("Processing S3 events.")
    for (record <- input.getRecords.asScala) {
      val s3Key = record.getS3.getObject.getKey
      val eventType = s3Key.substring(0, s3Key.indexOf('/'))
      logger.log(s3Key)
      logger.log(eventType)
      eventType match {
        case "keyPhrases" => processKeyPhrases(record.getS3.getBucket.getName, s3Key, logger)
        case "sentiment" => processSentiment(record.getS3.getBucket.getName, s3Key, logger)
        case "entities" => processEntities(record.getS3.getBucket.getName, s3Key, logger)
        case _ => logger.log(s"Unknown event type $eventType")
      }
    }
    "Ok"
  }

  /**
   * Process the key phrases generated by comprehend.
   *
   * @param bucketName to upload the key phrases.
   * @param key of the Comprehend output file.
   * @param logger used for logging.
   */
  def processKeyPhrases(bucketName: String, key: String, logger: LambdaLogger): Unit = {
    logger.log(s"Processing key phrases")
    val archiveInputStream = readTarFile(bucketName, key, logger)
    archiveInputStream.getNextEntry
    val result = parseJson(archiveInputStream)
    val keyPhrases = result \ "KeyPhrases"
    val start = key.indexOf('/') + 1
    val jobId = key.substring(start, key.indexOf('/', start))
    val tableKey = Map.newBuilder[String, AttributeValue]
    tableKey += "id" -> AttributeValue.builder().s(jobId).build()
    val item = Map.newBuilder[String, AttributeValueUpdate]
    val keyPhrasesList = List.newBuilder[AttributeValue]
    val tempFile = File.createTempFile("KeyPhrases-", ".csv")
    val writer = new PrintWriter(tempFile)
    writer.println(s"JobId,Phrase,Score")
    keyPhrases match {
      case JArray(arr) => for (value <- arr.toList) {
        val text = (value \ "Text").extract[String]
        val score = (value \ "Score").extract[Double]
        writer.println(s"$jobId,$text,$score")
        val phraseMap = Map.newBuilder[String, AttributeValue]
        phraseMap += "Text" -> AttributeValue.builder().s(text).build()
        phraseMap += "Score" -> AttributeValue.builder().n(score.toString).build()
        keyPhrasesList += AttributeValue.builder().m(phraseMap.result().asJava).build()
      }
      case _ => logger.log("No phrases found.")
    }
    writer.flush()
    writer.close()
    uploadS3File(tempFile, bucketName, s"analytics/keyPhrases/$jobId.csv")
    item += "KeyPhrases" -> AttributeValueUpdate.builder()
      .action(AttributeAction.PUT)
      .value(AttributeValue.builder().l(keyPhrasesList.result().asJava).build())
      .build()
    val updateItemRequest = UpdateItemRequest.builder()
      .attributeUpdates(item.result().asJava)
      .key(tableKey.result.asJava)
      .tableName(tableName)
      .build()
    dynamoDbClient.updateItem(updateItemRequest)
  }

  /**
   * Finish the processing of the sentiment found in the transcript.
   *
   * @param bucketName to upload the outcome.
   * @param key of the output file.
   * @param logger of the lambda.
   */
  def processSentiment(bucketName: String, key: String, logger: LambdaLogger): Unit = {
    logger.log("Processing sentiment")
    val archiveInputStream = readTarFile(bucketName, key, logger)
    archiveInputStream.getNextEntry
    val result = parseJson(archiveInputStream)
    val sentiment = (result \ "Sentiment").extract[String]
    val jobId = (result \ "File").extract[String].substring(0, 36)
    val mixed = (result \ "SentimentScore" \ "Mixed").extract[Double]
    val negative = (result \ "SentimentScore" \ "Negative").extract[Double]
    val neutral = (result \ "SentimentScore" \ "Neutral").extract[Double]
    val positive = (result \ "SentimentScore" \ "Positive").extract[Double]
    val tempFile = File.createTempFile("Sentiment-", ".csv")
    val writer = new PrintWriter(new FileOutputStream(tempFile))
    writer.println("jobId,Sentiment,Mixed,Negative,Neutral,Positive")
    writer.println(s"$jobId,$sentiment,$mixed,$negative,$neutral,$positive")
    writer.flush()
    writer.close()
    uploadS3File(tempFile, bucketName, s"analytics/sentiment/$jobId.csv")
  }

  /**
   * Process the response of detect entities.
   *
   * @param bucketName to upload the result.
   * @param key of the output of Comprehend.
   * @param logger of the lambda.
   */
  def processEntities(bucketName: String, key: String, logger: LambdaLogger): Unit = {
    logger.log("Processing entities")
    val archiveInputStream = readTarFile(bucketName, key, logger)
    archiveInputStream.getNextEntry
    val entitiesFile = File.createTempFile("entities-", ".csv")
    val start = key.indexOf('/') + 1
    val jobId = key.substring(start, key.indexOf('/', start))
    val tableKey = Map.newBuilder[String, AttributeValue]
    tableKey += "id" -> AttributeValue.builder().s(jobId).build()
    val entities = List.newBuilder[AttributeValue]
    val item = Map.newBuilder[String, AttributeValueUpdate]
    val writer = new PrintWriter(new FileOutputStream(entitiesFile))
    writer.println("jobId,type,text,score")
    val result = parseJson(archiveInputStream)
    (result \ "Entities") match {
      case JArray(arr) => for (value <- arr.toList) {
        val text = (value \ "Text").extract[String]
        val score = (value \ "Score").extract[Double]
        val entityType = (value \ "Type").extract[String]
        writer.println(s"$jobId,$entityType,$text,$score")
        val entityMap = Map.newBuilder[String, AttributeValue]
        entityMap += "Text" -> AttributeValue.builder().s(text).build()
        entityMap += "Type" -> AttributeValue.builder().s(entityType).build()
        entityMap += "Score" -> AttributeValue.builder().n(score.toString).build()
        entities += AttributeValue.builder().m(entityMap.result().asJava).build()
      }
      case _ => logger.log("No entities found.")
    }
    writer.flush()
    writer.close()
    uploadS3File(entitiesFile, bucketName, s"analytics/entities/$jobId.csv")
    item += "entities" -> AttributeValueUpdate.builder()
      .action(AttributeAction.PUT)
      .value(AttributeValue.builder().l(entities.result().asJava).build())
      .build()
    dynamoDbClient.updateItem(
      UpdateItemRequest.builder()
        .attributeUpdates(item.result().asJava)
        .key(tableKey.result.asJava)
        .tableName(tableName)
        .build())
  }

  /**
   * Reads the requested file from S3 and parses the JSON file.
   *
   * @param bucketName to retrieve the file.
   * @param key of the file to retrieve.
   * @param logger for logging.
   * @return List of byte arrays with the tar entries.
   */
  def readTarFile(bucketName: String, key: String, logger: LambdaLogger): ArchiveInputStream = {
    logger.log(s"Downloading $key from bucket $bucketName")
    val getObjectRequest = GetObjectRequest.builder()
      .bucket(bucketName)
      .key(key)
      .build()
    val id = UUID.randomUUID().toString
    val tempFile = Paths.get(sys.env.getOrElse("java.io.tmpdir", "/tmp"), s"$id.tar.gz")
    s3Client.getObject(getObjectRequest, tempFile)
    val inputStream = new FileInputStream(tempFile.toFile)
    val uncompressedInputStream = new CompressorStreamFactory()
      .createCompressorInputStream(
        if (inputStream.markSupported())
          inputStream
        else
          new BufferedInputStream(inputStream)
      )
    new ArchiveStreamFactory()
      .createArchiveInputStream(
        if (uncompressedInputStream.markSupported())
          uncompressedInputStream
        else
          new BufferedInputStream(uncompressedInputStream)
      )
  }

  /**
   * Upload the file to S3.
   *
   * @param file to upload.
   * @param bucketName to which the file will be uploaded.
   * @param key for the file.
   */
  def uploadS3File(file: File, bucketName: String, key: String): Unit = {
    val putObjectRequest = PutObjectRequest.builder()
      .bucket(bucketName)
      .key(key)
      .build()
    s3Client.putObject(putObjectRequest, file.toPath)
  }

  /**
   * Term in a topic.
   *
   * @param term the actual word.
   * @param weight of the term.
   */
  sealed case class TopicTerm(term: String, weight: Double)
}
