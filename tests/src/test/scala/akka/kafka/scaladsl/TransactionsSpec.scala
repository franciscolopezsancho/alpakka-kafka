/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.scaladsl

import akka.Done
import akka.kafka.ConsumerMessage.PartitionOffset
import akka.kafka.Subscriptions.TopicSubscription
import akka.kafka.{ProducerMessage, _}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class TransactionsSpec extends SpecBase(kafkaPort = KafkaPorts.TransactionsSpec) {

  def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort,
                        zooKeeperPort,
                        Map(
                          "offsets.topic.replication.factor" -> "1"
                        ))

  "A consume-transform-produce cycle" must {

    "complete" in {
      assertAllStagesStopped {
        val sourceTopic = createTopicName(1)
        val sinkTopic = createTopicName(2)
        val group = createGroupId(1)

        givenInitializedTopic(sourceTopic)
        givenInitializedTopic(sinkTopic)

        Await.result(produce(sourceTopic, 1 to 100), remainingOrDefault)

        val consumerSettings = consumerDefaults.withGroupId(group)

        val control = transactionalCopyStream(consumerSettings, sourceTopic, sinkTopic, group)
          .toMat(Sink.ignore)(Keep.left)
          .run()

        val probeConsumerGroup = createGroupId(2)

        val probeConsumer = valuesProbeConsumer(probeConsumerSettings(probeConsumerGroup), sinkTopic)

        probeConsumer
          .request(100)
          .expectNextN((1 to 100).map(_.toString))

        probeConsumer.cancel()
        Await.result(control.shutdown(), remainingOrDefault)
      }
    }

    "complete when messages are filtered out" in assertAllStagesStopped {
      val sourceTopic = createTopicName(1)
      val sinkTopic = createTopicName(2)
      val group = createGroupId(1)

      givenInitializedTopic(sourceTopic)
      givenInitializedTopic(sinkTopic)

      Await.result(produce(sourceTopic, 1 to 100), remainingOrDefault)

      val consumerSettings = consumerDefaults.withGroupId(group)

      val control = Transactional
        .source(consumerSettings, TopicSubscription(Set(sourceTopic), None))
        .filterNot(_.record.value() == InitialMsg)
        .map { msg =>
          if (msg.record.value.toInt % 10 == 0) {
            ProducerMessage.passThrough[String, String, ConsumerMessage.PartitionOffset](msg.partitionOffset)
          } else {
            ProducerMessage.single(new ProducerRecord(sinkTopic, msg.record.key, msg.record.value), msg.partitionOffset)
          }
        }
        .via(Transactional.flow(producerDefaults, group))
        .toMat(Sink.ignore)(Keep.left)
        .run()

      val probeConsumerGroup = createGroupId(2)

      val probeConsumer = valuesProbeConsumer(probeConsumerSettings(probeConsumerGroup), sinkTopic)

      probeConsumer
        .request(100)
        .expectNextN((1 to 100).filterNot(_ % 10 == 0).map(_.toString))

      probeConsumer.cancel()
      Await.result(control.shutdown(), remainingOrDefault)
    }

    "complete with transient failure causing an abort with restartable source" in {
      assertAllStagesStopped {
        val sourceTopic = createTopicName(1)
        val sinkTopic = createTopicName(2)
        val group = createGroupId(1)

        givenInitializedTopic(sourceTopic)
        givenInitializedTopic(sinkTopic)

        Await.result(produce(sourceTopic, 1 to 1000), remainingOrDefault)

        val consumerSettings = consumerDefaults.withGroupId(group)

        var restartCount = 0
        var innerControl = null.asInstanceOf[Control]

        val restartSource = RestartSource.onFailuresWithBackoff(
          minBackoff = 0.1.seconds,
          maxBackoff = 1.seconds,
          randomFactor = 0.2
        ) { () =>
          restartCount += 1
          Transactional
            .source(consumerSettings, TopicSubscription(Set(sourceTopic), None))
            .filterNot(_.record.value() == InitialMsg)
            .map { msg =>
              if (msg.record.value().toInt == 500 && restartCount < 2) {
                // add a delay that equals or exceeds EoS commit interval to trigger a commit for everything
                // up until this record (0 -> 500)
                Thread.sleep(producerDefaults.eosCommitInterval.toMillis + 10)
              }
              if (msg.record.value().toInt == 501 && restartCount < 2) {
                throw new RuntimeException("Uh oh.. intentional exception")
              } else {
                ProducerMessage.single(new ProducerRecord(sinkTopic, msg.record.key, msg.record.value),
                                       msg.partitionOffset)
              }
            }
            // side effect out the `Control` materialized value because it can't be propagated through the `RestartSource`
            .mapMaterializedValue(innerControl = _)
            .via(Transactional.flow(producerDefaults, group))
        }

        restartSource.runWith(Sink.ignore)

        val probeGroup = createGroupId(2)

        val probeConsumer = valuesProbeConsumer(probeConsumerSettings(probeGroup), sinkTopic)

        probeConsumer
          .request(1000)
          .expectNextN((1 to 1000).map(_.toString))

        probeConsumer.cancel()
        Await.result(innerControl.shutdown(), remainingOrDefault)
      }
    }

    "complete with messages filtered out and transient failure causing an abort with restartable source" in assertAllStagesStopped {
      val sourceTopic = createTopicName(1)
      val sinkTopic = createTopicName(2)
      val group = createGroupId(1)

      givenInitializedTopic(sourceTopic)
      givenInitializedTopic(sinkTopic)

      Await.result(produce(sourceTopic, 1 to 100), remainingOrDefault)

      val consumerSettings = consumerDefaults.withGroupId(group)

      var restartCount = 0
      var innerControl = null.asInstanceOf[Control]

      val restartSource = RestartSource.onFailuresWithBackoff(
        minBackoff = 0.1.seconds,
        maxBackoff = 1.seconds,
        randomFactor = 0.2
      ) { () =>
        restartCount += 1
        Transactional
          .source(consumerSettings, TopicSubscription(Set(sourceTopic), None))
          .filterNot(_.record.value() == InitialMsg)
          .map { msg =>
            if (msg.record.value().toInt == 50 && restartCount < 2) {
              // add a delay that equals or exceeds EoS commit interval to trigger a commit for everything
              // up until this record (0 -> 500)
              Thread.sleep(producerDefaults.eosCommitInterval.toMillis + 10)
            }
            if (msg.record.value().toInt == 51 && restartCount < 2) {
              throw new RuntimeException("Uh oh..")
            } else {
              ProducerMessage.Message(new ProducerRecord(sinkTopic, msg.record.key, msg.record.value),
                                      msg.partitionOffset)
            }
          }
          .map { msg =>
            if (msg.record.value.toInt % 10 == 0) {
              ProducerMessage.passThrough[String, String, PartitionOffset](msg.passThrough)
            } else msg
          }
          // side effect out the `Control` materialized value because it can't be propagated through the `RestartSource`
          .mapMaterializedValue(innerControl = _)
          .via(Transactional.flow(producerDefaults, group))
      }

      restartSource.runWith(Sink.ignore)

      val probeGroup = createGroupId(2)

      val probeConsumer = valuesProbeConsumer(probeConsumerSettings(probeGroup), sinkTopic)

      probeConsumer
        .request(100)
        .expectNextN((1 to 100).filterNot(_ % 10 == 0).map(_.toString))

      probeConsumer.cancel()
      Await.result(innerControl.shutdown(), remainingOrDefault)
    }

    "provide consistency when using multiple transactional streams" in {
      val sourceTopic = createTopicName(1)
      val sinkTopic = createTopic(2, partitions = 4)
      val group = createGroupId(1)

      givenInitializedTopic(sourceTopic)
      givenInitializedTopic(sinkTopic)

      val elements = 50
      val batchSize = 10
      Await.result(produce(sourceTopic, 1 to elements), remainingOrDefault)

      val consumerSettings = consumerDefaults.withGroupId(group)

      def runStream(id: String): Consumer.Control = {
        val control: Control = transactionalCopyStream(consumerSettings, sourceTopic, sinkTopic, s"$group-$id")
          .toMat(Sink.ignore)(Keep.left)
          .run()
        control
      }

      val controls: Seq[Control] = (0 until elements / batchSize)
        .map(_.toString)
        .map(runStream)

      val probeConsumerGroup = createGroupId(2)

      val probeConsumer = valuesProbeConsumer(probeConsumerSettings(probeConsumerGroup), sinkTopic)

      probeConsumer
        .request(elements)
        .expectNextUnorderedN((1 to elements).map(_.toString))

      probeConsumer.cancel()

      val futures: Seq[Future[Done]] = controls.map(_.shutdown())
      Await.result(Future.sequence(futures), remainingOrDefault)
    }
  }

  private def transactionalCopyStream(consumerSettings: ConsumerSettings[String, String], sourceTopic: String,
                                      sinkTopic: String, transactionalId: String):
  Source[ProducerMessage.Results[String, String, PartitionOffset], Control] = {
    Transactional
      .source(consumerSettings, TopicSubscription(Set(sourceTopic), None))
      .filterNot(_.record.value() == InitialMsg)
      .map { msg =>
        ProducerMessage.single(new ProducerRecord[String, String](sinkTopic, msg.record.value), msg.partitionOffset)
      }
      .via(Transactional.flow(producerDefaults, transactionalId))
  }

  private def probeConsumerSettings(groupId: String): ConsumerSettings[String, String] = {
    consumerDefaults
      .withGroupId(groupId)
      .withProperties(ConsumerConfig.ISOLATION_LEVEL_CONFIG -> "read_committed")
  }

  private def valuesProbeConsumer(settings: ConsumerSettings[String, String], topic: String):
  TestSubscriber.Probe[String] = {
    Consumer
        .plainSource(settings, TopicSubscription(Set(topic), None))
        .filterNot(_.value == InitialMsg)
        .map(_.value())
        .runWith(TestSink.probe)
  }
}
