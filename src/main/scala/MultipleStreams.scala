import java.util.{Arrays, Properties}

import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import util.{EmbeddedKafkaServer, SimpleKafkaClient, SparkKafkaSink}

/**
  * This example creates two streams based on a single consumer group, so they divide up the data.
  * There's an interesting partitioning interaction here as the streams each get data from two fo the four
  * topic partitions, and each produce RDDs with two partitions each.
  */

object MultipleStreams {

  /**
    * Publish some data to a topic. Encapsulated here to ensure serializability.
    * @param max
    * @param sc
    * @param topic
    * @param config
    */
  def send(max: Int, sc: SparkContext, topic: String, config: Properties): Unit = {

    // put some data in an RDD and publish to Kafka
    val numbers = 1 to max
    val numbersRDD = sc.parallelize(numbers, 4)

    val kafkaSink = sc.broadcast(SparkKafkaSink(config))

    println("*** producing data")

    numbersRDD.foreach { n =>
      kafkaSink.value.send(topic, "key_" + n, "string_" + n)
    }
  }

  def main (args: Array[String]) {

    val topic = "foo"

    val kafkaServer = new EmbeddedKafkaServer()
    kafkaServer.start()
    kafkaServer.createTopic(topic, 4)

    val conf = new SparkConf().setAppName("MultipleStreams").setMaster("local[4]")
    val sc = new SparkContext(conf)

    // streams will produce data every second
    val ssc = new StreamingContext(sc, Seconds(1))

    val max = 1000


    //
    // the first stream subscribes to the default consumer group in our SParkKafkaClient class
    //

    val props: Properties = SimpleKafkaClient.getBasicStringStringConsumer(kafkaServer)

    val kafkaStream1 =
    KafkaUtils.createDirectStream(
      ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](
        Arrays.asList(topic),
        props.asInstanceOf[java.util.Map[String, Object]]
      )

    )

    kafkaStream1.foreachRDD(r => {
      println("*** [stream 1] got an RDD, size = " + r.count())
      r.foreach(s => println("*** [stream 1] " + s))
      if (r.count() > 0) {
        println("*** [stream 1] " + r.getNumPartitions + " partitions")
        r.glom().foreach(a => println("*** [stream 1] partition size = " + a.size))
      }
    })

    //
    // a second stream, uses the same props and hence the same consumer group
    //

    val kafkaStream2 =
      KafkaUtils.createDirectStream(
        ssc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[String, String](
          Arrays.asList(topic),
          props.asInstanceOf[java.util.Map[String, Object]]
        )

      )

    kafkaStream2.foreachRDD(r => {
      println("*** [stream 2] got an RDD, size = " + r.count())
      r.foreach(s => println("*** [stream 2] " + s))
      if (r.count() > 0) {
        println("*** [stream 2] " + r.getNumPartitions + " partitions")
        r.glom().foreach(a => println("*** [stream 2] partition size = " + a.size))
      }
    })

    ssc.start()

    println("*** started termination monitor")

    // streams seem to need some time to get going
    Thread.sleep(5000)

    val producerThread = new Thread("Streaming Termination Controller") {
      override def run() {
        val client = new SimpleKafkaClient(kafkaServer)

        send(max, sc, topic, client.basicStringStringProducer)
        Thread.sleep(5000)
        println("*** requesting streaming termination")
        ssc.stop(stopSparkContext = false, stopGracefully = true)
      }
    }
    producerThread.start()

    try {
      ssc.awaitTermination()
      println("*** streaming terminated")
    } catch {
      case e: Exception => {
        println("*** streaming exception caught in monitor thread")
      }
    }

    // stop Spark
    sc.stop()

    // stop Kafka
    kafkaServer.stop()

    println("*** done")
  }
}