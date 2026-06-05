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
import actors.trainer.TrainerActor
import actors.trainer.TrainerActor.TrainerCommand
import actors.trainer.TrainerProtocol.TrainingConfig
import domain.network.{Activations, Feature, Model, ModelBuilder, Regularization}
import domain.training.Strategies.Optimizers.SGD
import domain.training.Strategies.Regularizers
import domain.data.{Label, LabeledPoint2D, Point2D}
import config.{AppConfig, ProductionConfig}

import domain.serialization.PersistenceManager.*
import domain.network.Activations.given
import domain.serialization.NetworkSerializers.given
import domain.serialization.LinearAlgebraSerializers.given
import domain.serialization.ModelSerializers.given
import domain.serialization.TrainingSerializers.given
import domain.serialization.DatasetSerializers.given


class SnapshotTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike with Matchers with BeforeAndAfterEach:

  private object TestConfig extends AppConfig:
    override val metricsInterval: FiniteDuration              = ProductionConfig.metricsInterval
    override val batchInterval: FiniteDuration                = ProductionConfig.batchInterval
    override val gossipInterval: FiniteDuration               = ProductionConfig.gossipInterval
    override val netLogFileName: String                       = ProductionConfig.netLogFileName
    override val space: domain.data.util.Space                = ProductionConfig.space
    override val lossFunction: domain.training.LossFunction   = ProductionConfig.lossFunction
    override val consensusInterval: FiniteDuration            = ProductionConfig.consensusInterval
    override val gossipRequestConfig: FiniteDuration          = ProductionConfig.gossipRequestConfig
    override val clusterNodesLogFileName: String              = ProductionConfig.clusterNodesLogFileName
    override val configurationInterval: FiniteDuration        = ProductionConfig.configurationInterval
    override val snapshotInterval: FiniteDuration             = 500.millis
    override def modelSnapshotPath(port: Int): String         = s"test_model_snapshot_$port.bin"
    override def trainingSnapshotPath(port: Int): String      = s"test_training_snapshot_$port.bin"

    override val clusterNodesLogFileName: String = ProductionConfig.clusterNodesLogFileName
    override val configurationInterval: FiniteDuration = ProductionConfig.configurationInterval

  given AppConfig = TestConfig

  private val dummyModel = ModelBuilder.fromInputs(Feature.X)
    .addLayer(1, Activations.Sigmoid)
    .withSeed(42L)
    .build()

  private val testOptimizer = new SGD(
    learningRate = 0.01,
    reg = Regularizers.fromConfig(Regularization.None)
  )

  private val dummyDataset = List(
    LabeledPoint2D(Point2D(0.5, 0.5), Label.Positive),
    LabeledPoint2D(Point2D(-0.5, -0.5), Label.Negative)
  )

  private val dummyConfig = TrainingConfig(
    simulationId = "",
    trainSet = dummyDataset,
    testSet = dummyDataset,
    features = List(Feature.X),
    hp = domain.network.HyperParams(0.01, Regularization.None),
    epochs = 10,
    batchSize = 2,
    seed = Some(123L)
  )

  override def afterEach(): Unit =
    val mPath = Paths.get(TestConfig.modelSnapshotPath(0))
    val tPath = Paths.get(TestConfig.trainingSnapshotPath(0))
    if Files.exists(mPath) then Files.delete(mPath)
    if Files.exists(tPath) then Files.delete(tPath)

  private def setup(): (ActorRef[ModelCommand], akka.actor.testkit.typed.scaladsl.TestProbe[TrainerCommand]) =
    val trainerProbe = createTestProbe[TrainerCommand]()
    val modelActor   = spawn(ModelActor())
    (modelActor, trainerProbe)


  test("ModelActor should create a model snapshot file on disk after the snapshot interval") {
    val (modelActor, trainerProbe) = setup()

    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)

    Thread.sleep(TestConfig.snapshotInterval.toMillis + 500)

    val snapshotFile = Paths.get(TestConfig.modelSnapshotPath(0))
    Files.exists(snapshotFile) shouldBe true
    Files.size(snapshotFile)   should be > 0L
    
    modelActor ! ModelCommand.StopSimulation
  }

  test("TrainerActor should persist the TrainingConfig on Start") {
    val modelProbe = createTestProbe[ModelCommand]()
    given domain.training.LossFunction = TestConfig.lossFunction
    val trainerActor = spawn(TrainerActor(modelProbe.ref))
    
    val monitorProbe = createTestProbe[actors.monitor.MonitorActor.MonitorCommand]()
    val gossipProbe = createTestProbe[actors.gossip.GossipActor.GossipCommand]()
    val configProbe = createTestProbe[actors.gossip.configuration.ConfigurationProtocol.ConfigurationCommand]()
    val consensusProbe = createTestProbe[actors.gossip.consensus.ConsensusProtocol.ConsensusCommand]()
    
    trainerActor ! TrainerCommand.RegisterServices(monitorProbe.ref, gossipProbe.ref, configProbe.ref, consensusProbe.ref)
    trainerActor ! TrainerCommand.SetTrainConfig(dummyConfig)
    
    trainerActor ! TrainerCommand.Start(dummyConfig.trainSet, dummyConfig.testSet)
    
    Thread.sleep(500)
    
    val snapshotFile = Paths.get(TestConfig.trainingSnapshotPath(0))
    Files.exists(snapshotFile) shouldBe true
    Files.size(snapshotFile) should be > 0L
    
    trainerActor ! TrainerCommand.Stop
  }

  test("System should successfully recover complete state from disk") {
    val mPath = TestConfig.modelSnapshotPath(0)
    val tPath = TestConfig.trainingSnapshotPath(0)
    dummyModel.saveToFile(mPath)
    dummyConfig.saveToFile(tPath)
    
    val recoveredModel = loadFromFile[Model](mPath).get
    val recoveredConfig = loadFromFile[TrainingConfig](tPath).get
    
    recoveredModel.network shouldBe dummyModel.network
    recoveredModel.maturity shouldBe dummyModel.maturity
    
    recoveredConfig.trainSet should contain theSameElementsAs dummyConfig.trainSet
    recoveredConfig.testSet should contain theSameElementsAs dummyConfig.testSet
    recoveredConfig.hp.learningRate shouldBe dummyConfig.hp.learningRate
    recoveredConfig.epochs shouldBe dummyConfig.epochs
  }

  test("ModelActor should clear both snapshots on ClearSnapshots command") {
    val mPath = TestConfig.modelSnapshotPath(0)
    val tPath = TestConfig.trainingSnapshotPath(0)
    dummyModel.saveToFile(mPath)
    dummyConfig.saveToFile(tPath)
    
    val (modelActor, trainerProbe) = setup()
    modelActor ! ModelCommand.Initialize(dummyModel, testOptimizer, trainerProbe.ref)
    modelActor ! ModelCommand.ClearSnapshots
    
    Thread.sleep(500)
    
    Files.exists(Paths.get(mPath)) shouldBe false
    Files.exists(Paths.get(tPath)) shouldBe false
    
    modelActor ! ModelCommand.StopSimulation
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
    
    modelActor ! ModelCommand.StopSimulation
  }
