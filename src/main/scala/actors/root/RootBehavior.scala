package actors.root

import actors.cluster.ClusterProtocol.{ClusterMemberCommand, RegisterMonitor}
import actors.cluster.timer.ClusterTimers
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import config.{AppConfig, ConfigLoader, FileConfig}
import domain.network.{Feature, Model, ModelBuilder}
import domain.training.LossFunction
import domain.training.Strategies.{Optimizers, Regularizers}
import actors.monitor.MonitorActor
import actors.monitor.MonitorActor.MonitorCommand
import actors.cluster.{ClusterManager, ClusterProtocol, ClusterState}
import actors.discovery.{DiscoveryActor, DiscoveryProtocol, GossipPeerState}
import actors.gossip.GossipActor
import actors.gossip.GossipProtocol.GossipCommand
import actors.gossip.configuration.{ConfigurationActor, ConfigurationProtocol}
import actors.model.ModelActor
import actors.model.ModelActor.ModelCommand
import actors.root.RootProtocol.{NodeRole, RootCommand}
import actors.trainer.TrainerActor
import actors.trainer.TrainerActor.TrainerCommand
import actors.trainer.TrainerActor.TrainingConfig
import actors.discovery.DiscoveryProtocol.DiscoveryCommand
import actors.gossip.configuration.ConfigurationProtocol.ConfigurationCommand
import actors.gossip.consensus.{ConsensusActor, ConsensusProtocol}
import actors.gossip.dataset_distribution.DatasetDistributionActor
import actors.gossip.dataset_distribution.DatasetDistributionProtocol
import actors.gossip.dataset_distribution.DatasetDistributionProtocol.DatasetDistributionCommand
import domain.data.LabeledPoint2D
import com.typesafe.config.Config
import domain.data.dataset.{DataModelFactory, DatasetGenerator, shuffle}
import domain.data.util.Space
import view.*
import domain.serialization.PersistenceManager

import domain.serialization.ModelSerializers.given
import domain.serialization.NetworkSerializers.given
import domain.serialization.TrainingSerializers.given
import domain.serialization.DatasetSerializers.given
import domain.serialization.LinearAlgebraSerializers.given
import domain.network.Activations.given

/**
 * Encapsulates the behavior logic for the RootActor.
 *
 * @param context     The actor context providing access to the actor system.
 * @param role        The specific role of this node.
 * @param configPath  Optional file path to the configuration file used.
 * @param appConfig   Implicit global application configuration.
 */
class RootBehavior(
  context: ActorContext[RootCommand],
  role: NodeRole,
  configPath: Option[String],
  akkaConfig: Config
)(using appConfig: AppConfig):

  private case class SeedPayload(
    model: Model,
    trainConfig: TrainingConfig,
    optimizer: Optimizers.SGD,
    fileConfig: FileConfig
  )


  /**
   * Bootstrap logic: executed immediately upon creation.
   */
  def start(): Behavior[RootCommand] =
    context.log.info(s"Root: Bootstrapping system with role $role...")

    val discoveryActor = context.spawn(DiscoveryActor(GossipPeerState.empty), "discoveryActor")

    context.watch(discoveryActor)
    
    val clusterManager = context.spawn(
      ClusterManager(
        ClusterState.initialState(role),
        ClusterTimers.fromConfig(akkaConfig.getConfig("akka")),
        None,
        discoveryActor,
        context.self
      ),
      "clusterManager")
    context.watch(clusterManager)

    val guiView = GuiView()

    val modelActor = context.spawn(ModelActor(), "modelActor")
    context.watch(modelActor)

    given LossFunction = appConfig.lossFunction

    val trainerActor = context.spawn(TrainerActor(modelActor), "trainerActor")
    context.watch(trainerActor)

    val consensusActor = context.spawn(ConsensusActor(modelActor, discoveryActor), "consensusActor")
    context.watch(consensusActor)

    val configurationActor = context.spawn(ConfigurationActor(discoveryActor), "configurationActor")
    context.watch(configurationActor)

    val distributeDatasetActor = context.spawn(DatasetDistributionActor(context.self, discoveryActor), "distributeDatasetActor")
    context.watch(distributeDatasetActor)

    val gossipActor = context.spawn(
      GossipActor(
        context.self, modelActor, trainerActor, discoveryActor,
        configurationActor, distributeDatasetActor, consensusActor
      ), "gossipActor")
    context.watch(gossipActor)

    configurationActor ! ConfigurationProtocol.RegisterGossip(gossipActor)


    val monitorActor = context.spawn(
      MonitorActor(
        modelActor,
        gossipActor,
        context.self,
        guiView,
        isMaster = role == NodeRole.Seed
      ),
      "monitorActor"
    )
    context.watch(monitorActor)

    trainerActor ! TrainerCommand.RegisterServices(monitorActor, gossipActor, configurationActor, consensusActor)
    clusterManager ! ClusterProtocol.RegisterMonitor(monitorActor)

    configurationActor ! ConfigurationProtocol.StartTickRequest

    waitingForStart(
      None, gossipActor, configurationActor, distributeDatasetActor, consensusActor,
      modelActor, trainerActor, monitorActor, clusterManager, discoveryActor
    )

  /**
   * State: Waiting for the Seed Start Simulation command.
   */
  private def waitingForStart(
    seedPayload: Option[SeedPayload],
    gossipActor: ActorRef[GossipCommand],
    configurationActor: ActorRef[ConfigurationCommand],
    distributeDatasetActor: ActorRef[DatasetDistributionCommand],
    consensusActor: ActorRef[ConsensusProtocol.ConsensusCommand],
    modelActor: ActorRef[ModelCommand],
    trainerActor: ActorRef[TrainerCommand],
    monitorActor: ActorRef[MonitorCommand],
    clusterManager: ActorRef[ClusterMemberCommand],
    discoveryActor: ActorRef[DiscoveryCommand],
  ): Behavior[RootCommand] =

    Behaviors.receive: (ctx, msg) =>
      msg match
        case RootCommand.ConfirmInitialConfiguration(seedID, model, trainConfig) =>
          val regularizationStrategy = Regularizers.fromConfig(trainConfig.hp.regularization)
          val optimizer = Optimizers.SGD(trainConfig.hp.learningRate, regularizationStrategy)
          modelActor ! ModelCommand.Initialize(model, optimizer, trainerActor)
          monitorActor ! MonitorCommand.Initialize(seedID, model, trainConfig)
          trainerActor ! TrainerCommand.SetTrainConfig(trainConfig)
          Behaviors.same

        case RootCommand.DistributedDataset(trainShard, testSet) =>
          clusterManager ! ClusterProtocol.StartSimulation
          trainerActor ! TrainerCommand.Start(trainShard, testSet)
          Behaviors.same

        case RootCommand.SeedStartSimulation =>
          seedPayload match
            case Some(payload) =>
              val dataset = generateDataset(payload.fileConfig)
              val trainSize = (dataset.size * (1.0 - payload.fileConfig.testSplit)).toInt
              val (globalTrain, globalTest) = dataset.splitAt(trainSize)

              context.log.info(s"Root: Data Split - Train: ${globalTrain.size}, Test: ${globalTest.size}")
              distributeDatasetActor ! DatasetDistributionProtocol.RegisterSeed(payload.fileConfig.seed.getOrElse(0))
              distributeDatasetActor ! DatasetDistributionProtocol.DistributeDataset(globalTrain, globalTest)
              Behaviors.same

            case None =>
              context.log.warn("Root: Received Start command but I am a Worker or Payload is missing.")
              Behaviors.same

        case RootCommand.ClusterReady =>
          val myAddress = ctx.system.address.toString
          context.log.info(s"Root: Node $role is now fully connected to the cluster.")

          if role == NodeRole.Seed then
            val path = configPath.getOrElse("simulation.conf")
            val fileConf = ConfigLoader.load(path)
            context.log.info(s"Root: Configuration loaded from $path")

            val (model, tConfig, optimizer) = tryRecoveryOrInitialize(fileConf)
            
            modelActor   ! ModelCommand.Initialize(model, optimizer, trainerActor)
            monitorActor ! MonitorCommand.Initialize(myAddress, model, tConfig)
            
            configurationActor ! ConfigurationProtocol.ShareConfig(myAddress, model, tConfig)
            trainerActor ! TrainerCommand.SetTrainConfig(tConfig)

            waitingForStart(
              Some(SeedPayload(model, tConfig, optimizer, fileConf)),
              gossipActor, configurationActor, distributeDatasetActor, consensusActor,
              modelActor, trainerActor, monitorActor, clusterManager, discoveryActor
            )
          else
            context.log.info(s"Root (CLIENT): Cluster Ready via $myAddress. Waiting for Seed Config...")
            Behaviors.same

        case RootCommand.ClusterFailed |
             RootCommand.InvalidCommandInBootstrap |
             RootCommand.InvalidCommandInJoining =>

          monitorActor ! MonitorCommand.ConnectionFailed(msg.toString)

          context.log.error("Root: Critical failure. Stopping actor.")
          Behaviors.same

        case RootCommand.StopSimulation =>
          clusterManager ! ClusterProtocol.StopSimulation
          trainerActor ! TrainerCommand.Stop
          monitorActor ! MonitorCommand.InternalStop
          modelActor ! ModelCommand.StopSimulation
          discoveryActor ! DiscoveryProtocol.Stop
          configurationActor ! ConfigurationProtocol.Stop
          consensusActor ! ConsensusProtocol.Stop
          distributeDatasetActor ! DatasetDistributionProtocol.Stop

          val children: Set[ActorRef[Nothing]] = Set(
            discoveryActor.unsafeUpcast,
            clusterManager.unsafeUpcast,
            modelActor.unsafeUpcast,
            trainerActor.unsafeUpcast,
            gossipActor.unsafeUpcast,
            monitorActor.unsafeUpcast,
            configurationActor.unsafeUpcast,
            consensusActor.unsafeUpcast,
            distributeDatasetActor.unsafeUpcast
          )
          gracefullyStopping(children)

  /**
   * It coordinates the sequential shutdown of all local child actors. The actor enters
   * a 'waiting' mode, remaining active only until it receives confirmation signals
   * that every child has successfully terminated.
   *
   * @param remainingActors A set of ActorRefs representing the child actors that still need to shut down.
   *
   * @return A Behavior that handles Terminated signals and waits for the remaining child actors to stop.
   */
  private def gracefullyStopping(remainingActors: Set[ActorRef[Nothing]]): Behavior[RootCommand] =
    Behaviors.receiveSignal {
      case (ctx, Terminated(ref)) =>
        val stillAlive = remainingActors - ref
        ctx.log.info(s"Root: Actor ${ref.path.name} stopped. Remaining: ${stillAlive.size}")

        if stillAlive.isEmpty then
          ctx.log.info("Root: All children stopped. Shutting down system.")
          Behaviors.stopped
        else
          gracefullyStopping(stillAlive)
    }

  /**
   * A factory method that builds the neural network Model based on the provided file configuration.
   *
   * @param conf The [[FileConfig]] containing layer definitions, input features, and activation functions.
   *
   * @return A fully initialized [[Model]] instance with the specified architecture and weights.
   */
  private def createModel(conf: FileConfig): Model =
    var builder = ModelBuilder.fromInputs(conf.features *)
    conf.networkLayers.foreach(l => builder = builder.addLayer(l.neurons, l.activation))
    conf.seed.foreach(s => builder = builder.withSeed(s))
    builder.build()

  /**
   * Generates the global dataset that will be used for the training session.
   *
   * @param conf The configuration containing dataset size, distribution type, and the random seed.
   *
   * @return A shuffled list of [[LabeledPoint2D]] (coordinates and labels).
   */
  private def generateDataset(conf: FileConfig): List[domain.data.LabeledPoint2D] =
    val seedPos = conf.seed

    given Space = appConfig.space

    val datasetModel = DataModelFactory.create(conf.datasetConf, seedPos)

    val data = DatasetGenerator.generate(conf.datasetSize, datasetModel).shuffle(seedPos)
    context.log.info(s"Root: Generated Global Dataset with ${data.size} samples.")
    data

  /**
   * Constructs the training hyperparameters and environment settings.
   *
   * @param conf The [[FileConfig]] defining learning rates, batch sizes, epochs, and regularization.
   *
   * @return A [[TrainingConfig]] object containing the serialized hyperparameters and environment setup.
   */
  private def createTrainConfig(conf: FileConfig): TrainingConfig =
    TrainingConfig(
      trainSet = Nil,
      testSet = Nil,
      features = conf.features,
      hp = conf.hyperParams,
      epochs = conf.epochs,
      batchSize = conf.batchSize,
      seed = conf.seed
    )

  /**
   * Attempts to recover the simulation state from local snapshots.
   * If snapshots are missing or corrupted, it falls back to creating a fresh Model and TrainingConfig.
   *
   * @param conf The [[FileConfig]] defining the baseline simulation setup.
   * @return A tuple containing the initialized [[Model]], [[TrainingConfig]], and [[Optimizers.SGD]].
   */
  private def tryRecoveryOrInitialize(conf: FileConfig): (Model, TrainingConfig, Optimizers.SGD) =
    val port = context.system.address.port.getOrElse(0)
    val modelPath = appConfig.modelSnapshotPath(port)
    val trainPath = appConfig.trainingSnapshotPath(port)

    val optimizer = new Optimizers.SGD(
      conf.hyperParams.learningRate,
      Regularizers.fromConfig(conf.hyperParams.regularization)
    )

    // Structural integration for recovery: load if present, otherwise fresh init.
    val recoveredModel = PersistenceManager.loadFromFile[Model](modelPath).toOption
    val recoveredConfig = PersistenceManager.loadFromFile[TrainingConfig](trainPath).toOption

    (recoveredModel, recoveredConfig) match
      case (Some(m), Some(c)) =>
        context.log.info(s"Root: State RECOVERED from snapshots (Maturity: ${m.maturity})")
        (m, c, optimizer)
      case _ =>
        context.log.info("Root: No snapshots found or recovery failed. Initializing fresh state.")
        (createModel(conf), createTrainConfig(conf), optimizer)
