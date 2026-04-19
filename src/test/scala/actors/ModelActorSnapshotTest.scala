package actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration.*
import java.nio.file.{Files, Paths}

import actors.model.ModelActor
import actors.model.ModelActor.ModelCommand
import actors.trainer.TrainerActor.TrainerCommand
import domain.network.{Activations, Feature, Model, ModelBuilder, Regularization}
import domain.training.Strategies.Optimizers.SGD
import domain.training.Strategies.Regularizers
import config.{AppConfig, ProductionConfig}


class ModelActorSnapshotTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike with Matchers with BeforeAndAfterEach:

  private object TestConfig extends AppConfig:
    override val metricsInterval: FiniteDuration            = ProductionConfig.metricsInterval
    override val batchInterval: FiniteDuration              = ProductionConfig.batchInterval
    override val gossipInterval: FiniteDuration             = ProductionConfig.gossipInterval
    override val netLogFileName: String                     = ProductionConfig.netLogFileName
    override val space: domain.data.util.Space              = ProductionConfig.space
    override val lossFunction: domain.training.LossFunction = ProductionConfig.lossFunction
    override val consensusInterval: FiniteDuration          = ProductionConfig.consensusInterval
    override val gossipRequestConfig: FiniteDuration        = ProductionConfig.gossipRequestConfig
    override val snapshotInterval: FiniteDuration           = 500.millis
    override val modelSnapshotPath: String                  = "test_model_snapshot.bin"

  given AppConfig = TestConfig

  private val dummyModel = ModelBuilder.fromInputs(Feature.X)
    .addLayer(1, Activations.Sigmoid)
    .withSeed(42L)
    .build()

  private val testOptimizer = new SGD(
    learningRate = 0.01,
    reg = Regularizers.fromConfig(Regularization.None)
  )


  override def afterEach(): Unit =
    val path = Paths.get(TestConfig.modelSnapshotPath)
    if Files.exists(path) then Files.delete(path)

  private def setup(): (ActorRef[ModelCommand], akka.actor.testkit.typed.scaladsl.TestProbe[TrainerCommand]) =
    val trainerProbe = createTestProbe[TrainerCommand]()
    val modelActor   = spawn(ModelActor())
    (modelActor, trainerProbe)


  test("ModelActor should create a snapshot file on disk after the snapshot interval") {
    val (modelActor, trainerProbe) = setup()

    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)

    Thread.sleep(TestConfig.snapshotInterval.toMillis + 500)

    val snapshotFile = Paths.get(TestConfig.modelSnapshotPath)
    Files.exists(snapshotFile) shouldBe true
    Files.size(snapshotFile)   should be > 0L
  }

  test("ModelActor should continue responding normally after a snapshot is taken") {
    val (modelActor, trainerProbe) = setup()
    val replyProbe = createTestProbe[Model]()

    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)

    Thread.sleep(TestConfig.snapshotInterval.toMillis + 500)

    modelActor ! ModelCommand.GetModel(replyProbe.ref)
    val received = replyProbe.receiveMessage(3.seconds)
    received.network shouldBe dummyModel.network
  }

  test("ModelActor should increment maturity on every ApplyGradients") {
    val (modelActor, trainerProbe) = setup()
    val replyProbe = createTestProbe[Model]()

    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)

    val layerGradients = dummyModel.network.layers.map: layer =>
      import domain.data.LinearAlgebra.{Matrix, Vector}
      import domain.training.LayerGradient
      LayerGradient(
        Matrix.fill(layer.weights.rows, layer.weights.cols)(0.01),
        Vector.fromList(List.fill(layer.biases.length)(0.01))
      )
    val grads = domain.training.NetworkGradient(layerGradients)

    modelActor ! ModelCommand.ApplyGradients(grads)
    modelActor ! ModelCommand.ApplyGradients(grads)
    modelActor ! ModelCommand.ApplyGradients(grads)

    modelActor ! ModelCommand.GetModel(replyProbe.ref)
    val updatedModel = replyProbe.receiveMessage(3.seconds)

    updatedModel.maturity shouldBe 3
  }

  test("ModelActor should cancel snapshot timer and stop cleanly on StopSimulation") {
    val (modelActor, trainerProbe) = setup()

    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)
    modelActor ! ModelCommand.StopSimulation

    createTestProbe[ModelCommand]().expectTerminated(modelActor, 3.seconds)
  }
