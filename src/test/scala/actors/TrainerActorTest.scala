package actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import domain.network.{Activations, Feature, ModelBuilder, Regularization, HyperParams}
import domain.data.{Label, LabeledPoint2D, Point2D}
import domain.training.Strategies.Losses.mse
import actors.trainer.TrainerActor.{TrainerCommand, TrainingConfig}
import actors.model.ModelActor.ModelCommand
import actors.trainer.{TrainerActor, TrainerProtocol}
import config.{AppConfig, ProductionConfig}

class TrainerActorTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike with Matchers {

  given AppConfig = ProductionConfig

  private final val dummyFeatures = Feature.X
  
  private final val dummyModel = ModelBuilder.fromInputs(dummyFeatures)
    .addLayer(neurons = 1, activation = Activations.Sigmoid)
    .withSeed(1234L)
    .build()

  private final val dummyData = List(
    LabeledPoint2D(Point2D(0.0, 0.0), Label.Negative),
    LabeledPoint2D(Point2D(1.0, 1.0), Label.Positive)
  )

  private final val dummyConfig = TrainingConfig(
    simulationId = "",
    trainSet = dummyData,
    testSet = Nil,
    features = List(dummyFeatures),
    hp = HyperParams(0.1, Regularization.None),
    epochs = 5,
    batchSize = 2,
    seed = Some(1234L)
  )


  test("TrainerActor should start the training loop upon Start command") {
    val modelProbe = createTestProbe[ModelCommand]()
    val trainer = spawn(TrainerActor(modelProbe.ref))

    trainer ! TrainerCommand.SetTrainConfig(dummyConfig)
    trainer ! TrainerCommand.Start(dummyData, Nil)
    
    modelProbe.expectMessageType[ModelCommand.GetModel]
  }

  test("TrainerActor should perform a full training step: Start -> GetModel -> Reply -> ApplyGradients") {
    val modelProbe = createTestProbe[ModelCommand]()
    val trainer = spawn(TrainerActor(modelProbe.ref))

    trainer ! TrainerCommand.SetTrainConfig(dummyConfig)
    trainer ! TrainerCommand.Start(dummyData, Nil)

    val askMsg1 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg1.replyTo ! dummyModel

    val askMsg2 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg2.replyTo ! dummyModel

    val msg = modelProbe.expectMessageType[ModelCommand.ApplyGradients]

    msg.grads.layers should not be empty
  }

  test("TrainerActor should stop itself when receiving Stop command") {
    val modelProbe = createTestProbe[ModelCommand]()
    val trainer = spawn(TrainerActor(modelProbe.ref))

    trainer ! TrainerCommand.SetTrainConfig(dummyConfig)
    trainer ! TrainerCommand.Start(dummyData, Nil)
    
    modelProbe.expectMessageType[ModelCommand.GetModel]

    trainer ! TrainerCommand.Stop

    modelProbe.expectTerminated(trainer)
  }

  test("TrainerActor should pause processing upon Pause command and resume upon Resume") {
    val modelProbe = createTestProbe[ModelCommand]()
    val trainer = spawn(TrainerActor(modelProbe.ref))

    trainer ! TrainerCommand.SetTrainConfig(dummyConfig)
    trainer ! TrainerCommand.Start(dummyData, Nil)

    val askMsg1 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg1.replyTo ! dummyModel

    val askMsg2 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg2.replyTo ! dummyModel

    modelProbe.expectMessageType[ModelCommand.ApplyGradients]

    trainer ! TrainerCommand.Pause

    modelProbe.expectNoMessage(500.millis)

    trainer ! TrainerCommand.Resume

    val msgAfterResume = modelProbe.expectMessageType[ModelCommand]

    msgAfterResume shouldBe a[ModelCommand]
  }

  test("TrainerActor should transition to finished state and clear snapshots when training completes") {
    val modelProbe = createTestProbe[ModelCommand]()
    val monitorProbe = createTestProbe[actors.monitor.MonitorActor.MonitorCommand]()
    val trainer = spawn(TrainerActor(modelProbe.ref))

    trainer ! TrainerCommand.RegisterServices(
      monitorProbe.ref,
      createTestProbe[actors.gossip.GossipActor.GossipCommand]().ref,
      createTestProbe[actors.gossip.configuration.ConfigurationProtocol.ConfigurationCommand]().ref,
      createTestProbe[actors.gossip.consensus.ConsensusProtocol.ConsensusCommand]().ref
    )

    val completionConfig = dummyConfig.copy(epochs = 1, batchSize = 2)
    trainer ! TrainerCommand.SetTrainConfig(completionConfig)
    trainer ! TrainerCommand.Start(dummyData, Nil)

    val askMsg1 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg1.replyTo ! dummyModel
    val askMsg2 = modelProbe.expectMessageType[ModelCommand.GetModel]
    askMsg2.replyTo ! dummyModel
    modelProbe.expectMessageType[ModelCommand.ApplyGradients]

    monitorProbe.expectMessageType[actors.monitor.MonitorActor.MonitorCommand.StartWithData]
    modelProbe.expectMessage(ModelCommand.ClearSnapshots)
    monitorProbe.expectMessage(actors.monitor.MonitorActor.MonitorCommand.SimulationFinished)

    val replyProbe = createTestProbe[TrainerProtocol.MetricsCalculated]()
    trainer ! TrainerCommand.CalculateMetrics(dummyModel, replyProbe.ref)
    replyProbe.expectMessageType[TrainerProtocol.MetricsCalculated]
  }
}
