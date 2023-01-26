package greyhound

import com.wixpress.dst.greyhound.core.producer.{Producer, ProducerConfig, ProducerRecord}
import com.wixpress.dst.greyhound.core.{CleanupPolicy, TopicConfig}
import com.wixpress.dst.greyhound.sidecar.api.v1.greyhoundsidecar.ZioGreyhoundsidecar.RCGreyhoundSidecar
import com.wixpress.dst.greyhound.sidecar.api.v1.greyhoundsidecar._
import io.grpc.Status
import zio.{IO, Scope, UIO, ZIO, ZLayer, durationInt}

import java.util.UUID

class SidecarService(register: Register,
                     consumerRegistry: ConsumerRegistry,
                     onProduceListener: ProducerRecord[_, _] => UIO[Unit] = _ => ZIO.unit,
                     kafkaAddress: String
                    ) extends RCGreyhoundSidecar {

  private val producer = Producer.make(ProducerConfig(kafkaAddress, onProduceListener = onProduceListener))

  override def register(request: RegisterRequest): IO[Status, RegisterResponse] =
    register0(request)
      .mapError(Status.fromThrowable)

  private def register0(request: RegisterRequest) = for {
    port <- ZIO.attempt(request.port.toInt)
    tenantId = UUID.randomUUID().toString
    _ <- register.add(tenantId, request.host, port)
  } yield RegisterResponse(registrationId = tenantId)

  override def produce(request: ProduceRequest): IO[Status, ProduceResponse] =
    ZIO.scoped {
      produce0(request)
        .mapBoth(Status.fromThrowable, _ => ProduceResponse())
    }

  private def produce0(request: ProduceRequest) =
    for {
      producer <- producer
      _ <- Produce(request, producer).tapError(e => zio.Console.printLine(e))
    } yield ()

  override def createTopics(request: CreateTopicsRequest): IO[Status, CreateTopicsResponse] =
    createTopics0(request)
      .mapBoth(Status.fromThrowable, _ => CreateTopicsResponse())


  private def createTopics0(request: CreateTopicsRequest) = ZIO.scoped {
    for {
      client <- SidecarAdminClient.admin(kafkaAddress)
      _ <- client.createTopics(request.topics.toSet.map(mapTopic)).provideSomeLayer(DebugMetrics.layer)
    } yield ()
  }

  private def mapTopic(topic: TopicToCreate): TopicConfig =
    TopicConfig(name = topic.name, partitions = topic.partitions.getOrElse(1), replicationFactor = 1, cleanupPolicy = CleanupPolicy.Compact)

  override def stopConsuming(request: StopConsumingRequest): IO[Status, StopConsumingResponse] =
    for {
      _ <- validateStopConsumingRequest(request)
      _ <- stopConsuming0(request)
    } yield StopConsumingResponse()

  override def startConsuming(request: StartConsumingRequest): IO[Status, StartConsumingResponse] =
    for {
      _ <- validateStartConsumingRequest(request)
      _ <- register.get(request.registrationId).flatMap {
        case Some(hostDetails) =>
          startConsuming0(hostDetails, request.consumers, request.batchConsumers, request.registrationId)
            .mapError(Status.fromThrowable)
            .provideSomeLayer(DebugMetrics.layer ++ ZLayer.succeed(Scope.global))

        case None => ZIO.fail(Status.NOT_FOUND.withDescription(s"registrationId ${request.registrationId} not found"))
      }
    } yield StartConsumingResponse()

  private def validateStopConsumingRequest(request: StopConsumingRequest) =
    for {
      maybeConsumerInfo <- consumerRegistry.get(topic = request.topic, consumerGroup = request.group)
      _ <- maybeConsumerInfo match {
        case Some(consumerInfo) if consumerInfo.registrationId == request.registrationId => ZIO.succeed()
        case _ => ZIO.fail(Status.NOT_FOUND)
      }
    } yield ()

  private def stopConsuming0(request: StopConsumingRequest): ZIO[Any, Status, Unit] =
    for {
      maybeConsumerInfo <- consumerRegistry.get(topic = request.topic, consumerGroup = request.group)
      _ <- maybeConsumerInfo match {
        case Some(consumerInfo) =>
          for {
            _ <- consumerInfo.fiber.interrupt.delay(4.seconds)
            result <- consumerRegistry.remove(consumerInfo.topic, consumerInfo.consumerGroup).mapError(Status.fromThrowable)
          } yield result
        case _ =>
          ZIO.fail(Status.NOT_FOUND)
      }
    } yield ()

  private def validateStartConsumingRequest(request: StartConsumingRequest) = {
    val candidateConsumers = request.consumers.map(consumer => (consumer.topic, consumer.group)) ++
      request.batchConsumers.map(consumer => (consumer.topic, consumer.group))
    if (candidateConsumers.size == candidateConsumers.toSet.size) {
      ZIO.forall(candidateConsumers) { candidateConsumer =>
        consumerRegistry.get(candidateConsumer._1, candidateConsumer._2).map(_.isEmpty)
      }.flatMap { success => if (success) ZIO.succeed() else ZIO.fail(Status.ALREADY_EXISTS) }
    } else
      ZIO.fail(Status.INVALID_ARGUMENT
        .withDescription(s"Creating multiple consumers for the same topic + group is not allowed"))
  }

  private def startConsuming0(hostDetails: HostDetails,
                              consumers: Seq[Consumer],
                              batchConsumers: Seq[BatchConsumer],
                              registrationId: String) =
    ZIO.foreachPar(consumers) { consumer =>
      for {
        fiber <- CreateConsumer(
          hostDetails = hostDetails,
          topic = consumer.topic,
          group = consumer.group,
          retryStrategy = consumer.retryStrategy,
          kafkaAddress = kafkaAddress
        ).interruptible.forkDaemon
        _ <- consumerRegistry.add(consumer.topic, consumer.group, registrationId, fiber)
      } yield fiber
    } &>
      ZIO.foreachPar(batchConsumers) { consumer =>
        for {
          fiber <- CreateBatchConsumer(
            hostDetails = hostDetails,
            topic = consumer.topic,
            group = consumer.group,
            retryStrategy = consumer.retryStrategy,
            kafkaAddress = kafkaAddress,
            extraProperties = consumer.extraProperties
          ).interruptible.forkDaemon
          _ <- consumerRegistry.add(consumer.topic, consumer.group, registrationId, fiber)
        } yield fiber
      }

}

object SidecarService {
  val layer: ZLayer[Register with ConsumerRegistry with KafkaInfo, Nothing, SidecarService] = ZLayer.fromZIO {
    for {
      kafkaAddress <- ZIO.service[KafkaInfo].map(_.address)
      register <- ZIO.service[Register]
      consumerRegistry <- ZIO.service[ConsumerRegistry]
    } yield new SidecarService(register, consumerRegistry, kafkaAddress = kafkaAddress)
  }
}


