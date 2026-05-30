package actors.root

import actors.authentication.{AuthActor, AuthProtocol}
import actors.authentication.AuthProtocol.Register
import actors.cluster.ClusterProtocol.{ClusterMemberCommand, RegisterMonitor}
import actors.cluster.timer.ClusterTimers
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import config.{AppConfig, ConfigLoader, FileConfig}
import domain.network.{Feature, Model, ModelBuilder}
import domain.training.LossFunction
import domain.training.Strategies.{Optimizers, Regularizers}
import domain.authentication.{AuthAction, Credentials, NodeRole, User}
import actors.monitor.MonitorActor
import actors.monitor.MonitorActor.MonitorCommand
import actors.cluster.{ClusterManager, ClusterProtocol, ClusterState}
import actors.discovery.{DiscoveryActor, DiscoveryProtocol, GossipPeerState}
import actors.gossip.GossipActor
import actors.gossip.GossipProtocol.GossipCommand
import actors.gossip.configuration.{ConfigurationActor, ConfigurationProtocol}
import actors.model.ModelActor
import actors.model.ModelActor.ModelCommand
import actors.root.RootProtocol.RootCommand
import actors.trainer.TrainerActor
import actors.trainer.TrainerActor.TrainerCommand
import actors.trainer.TrainerActor.TrainingConfig
import actors.discovery.DiscoveryProtocol.DiscoveryCommand
import actors.gossip.configuration.ConfigurationProtocol.ConfigurationCommand
import actors.gossip.consensus.{ConsensusActor, ConsensusProtocol}
import actors.gossip.dataset_distribution.DatasetDistributionActor
import actors.gossip.dataset_distribution.DatasetDistributionProtocol
import actors.gossip.dataset_distribution.DatasetDistributionProtocol.DatasetDistributionCommand
import actors.root.RootProtocol.RootCommand.{WrappedAuthListing, WrappedAuthenticateReply, WrappedRegisterReply, WrappedValidateTokenReply}
import akka.actor.typed.receptionist.Receptionist
import domain.data.LabeledPoint2D
import com.typesafe.config.Config
import domain.authentication.NodeRole.Seed
import domain.data.dataset.{DataModelFactory, DatasetGenerator, shuffle}
import domain.data.util.Space
import view.*
import domain.serialization.PersistenceManager

import java.util.UUID
import domain.serialization.ModelSerializers.given
import domain.serialization.NetworkSerializers.given
import domain.serialization.TrainingSerializers.given
import domain.serialization.DatasetSerializers.given
import domain.serialization.LinearAlgebraSerializers.given
import domain.network.Activations.given

import scala.concurrent.duration.DurationInt

/**
 * Encapsulates the behavior logic for the RootActor.
 *
 * @param context     The actor context providing access to the actor system.
 * @param role        The specific role of this node.
 * @param configPath  Optional file path to the configuration file used.
 * @param akkaConfig  Application configuration of application.conf.
 * @param action      Action (login, registration) to be performed on the seed actor that handles authentication.
 * @param username    Username of the client performing the authentication.
 * @param password    Password of the client performing the authentication.
 * @param fullName    Optional full name of the client performing the authentication.
 * @param appConfig   Implicit application configuration.
 */
class RootBehavior(
                    context: ActorContext[RootCommand],
                    role: NodeRole,
                    configPath: Option[String],
                    akkaConfig: Config,
                    action: AuthAction,
                    username: Option[String],
                    password: Option[String],
                    fullName: Option[String] = None
                  )(using appConfig: AppConfig):

  private case class SeedPayload(
    model: Model,
    trainConfig: TrainingConfig,
    optimizer: Optimizers.SGD,
    fileConfig: FileConfig
  )

  /**
   * For a seed node, it instantiates the actor responsible for authentication and proceeds
   * with the bootstrap logic. For a known client, based on the specified action,
   * it performs authentication on the seed node.
   */
  def start(): Behavior[RootCommand] =
    role match
      case NodeRole.Seed =>
        context.log.info("Seed node: AuthActor initialization")

        val authActorRef = context.spawn(AuthActor(), "AuthActor")

        context.system.receptionist ! Receptionist.Register(AuthActor.AuthServiceKey, authActorRef)

        ready()

      case NodeRole.Client =>
        context.log.info("Client node: subscription to the Receptionist to find the AuthActor of the Seed")

        val authListingAdapter = context.messageAdapter[Receptionist.Listing](WrappedAuthListing.apply)

        context.system.receptionist ! Receptionist.Subscribe(AuthActor.AuthServiceKey, authListingAdapter)

        if username.nonEmpty && password.nonEmpty then
          waitingForAuthActor(action, username.getOrElse(""), password.getOrElse(""), fullName)
        else
          context.log.error("Client node: Cannot start without username and password.")
          Behaviors.stopped

  /**
   * In the case of registation, the client sends a request to the seed node's AuthActor to store
   * its credentials.
   * In the case of login, it requests validation of the credentials (again to  seed node's
   * AuthActor). If the credentials are present, the actor will generate a token that will
   * allow subsequent authentication by the client.
   */
  private def waitingForAuthActor(
                                   action: AuthAction,
                                   username: String,
                                   password: String,
                                   fullName: Option[String] = None
                                 ): Behavior[RootCommand] =
    Behaviors.receiveMessage:
      case WrappedAuthListing(AuthActor.AuthServiceKey.Listing(listings)) =>
        listings.headOption match
          case Some(remoteAuthActorRef) =>
            if action == AuthAction.Register then
              context.log.info(s"Client node: Received reference to remote AuthActor -> $remoteAuthActorRef")
              val user = User(
                username = username,
                fullName = fullName.getOrElse(""),
                role = role,
                password = password
              )
              val registerAdapter = context.messageAdapter[AuthProtocol.RegisterReply](WrappedRegisterReply.apply)
              remoteAuthActorRef ! AuthProtocol.Register(user, registerAdapter)
              waitingForRegistration()

            else if action == AuthAction.Login then
              context.log.info(s"Client node: Initiating token authentication for the user: '$username'...")
              val credentials = Credentials(id = username, password = password)
              val loginAdapter = context.messageAdapter[AuthProtocol.AuthenticateReply](WrappedAuthenticateReply.apply)
              remoteAuthActorRef ! AuthProtocol.Authenticate(credentials, 2.hours, loginAdapter)
              waitingForAuthentication(remoteAuthActorRef)
            else
              ready()
          case _ =>
            context.log.debug("Client node: Received empty listing update from Receptionist, waiting for seed discovery")
            Behaviors.same
      case _ =>
        Behaviors.same

  /**
   * It receives the response from a new user's registration request, which is
   * the response message from the seed to the client.
   * Successful registration or any errors during this phase are notified to the client.
   */
  private def waitingForRegistration(): Behavior[RootCommand] =
    Behaviors.receiveMessage:
      case WrappedRegisterReply(AuthProtocol.RegisterReply.Registered) =>
        context.log.info("Client node: Registration on AuthActor was successful")

        GuiView.showInfoDialog(
          "Registration Successful",
          "Registration completed successfully! You can now log in to the system."
        )
        Behaviors.stopped

      case WrappedRegisterReply(AuthProtocol.RegisterReply.AlreadyExists(reason)) =>
        context.log.error(s"Client node: Registration failed (User already exists): $reason")

        GuiView.showWarningDialog(
          "Registration Error",
          s"Warning: Registration failed.\nThe user already exists:\n$reason"
        )

        Behaviors.stopped

      case WrappedRegisterReply(AuthProtocol.RegisterReply.RegisterError(reason)) =>
        context.log.error(s"Client node: Registration failed due to an internal error: $reason")

        GuiView.showErrorDialog(
          "System Error",
          s"Critical error during registration:\n$reason"
        )

        Behaviors.stopped

      case _ =>
        Behaviors.same

  /**
   * It receives the response from sending the login credentials.
   * In particular, the seed (via AuthActor) sends the token with which
   * it will be possible to subsequently perform authentication (until its duration is valid).
   */
  private def waitingForAuthentication(remoteAuthActorRef: ActorRef[AuthActor.AuthCommand]): Behavior[RootCommand] =
    Behaviors.receiveMessage:
      case WrappedAuthenticateReply(AuthProtocol.AuthenticateReply.Authenticated(token)) =>
        context.log.info("Client node: Authentication successful. Token extraction and coverage check on the Seed")

        val validateAdapter = context.messageAdapter[AuthProtocol.ValidateTokenReply](WrappedValidateTokenReply.apply)

        remoteAuthActorRef ! AuthProtocol.ValidateToken(token, validateAdapter)

        waitingForTokenValidation()

      case WrappedAuthenticateReply(AuthProtocol.AuthenticateReply.AuthFailed(reason)) =>
        context.log.error(s"Client node: Authentication failed! Error: $reason")
        GuiView.showErrorDialog(
          "Authentication Failed",
          s"Access denied.\nReason: $reason"
        )
        Behaviors.stopped

      case _ =>
        Behaviors.same

  /**
   * Receives the validation response for the token assigned to the client.
   * If the seed (via AuthActor) receives a valid token (i.e., one associated with a user
   * and still valid for a certain period), it will notify the client that it is valid and the
   * bootstrap phase will begin.
   */
  private def waitingForTokenValidation(): Behavior[RootCommand] =
    Behaviors.receiveMessage:
      case WrappedValidateTokenReply(AuthProtocol.ValidateTokenReply.TokenValid(token)) =>
        context.log.info(s"Client node: Token coverage successfully verified by Seed for user: '${token.user.username}'.")
        context.log.info("Client node: The token is valid and active. Final system bootstrap is starting")
        GuiView.showInfoDialog(
          "Authentication Successful",
          "Token validated. System is bootstrapping now..."
        )
        ready()

      case WrappedValidateTokenReply(AuthProtocol.ValidateTokenReply.TokenInvalid(reason)) =>
        context.log.error(s"Client node: Token coverage verification failed! Reason for rejection: $reason")
        GuiView.showErrorDialog(
          "Token Validation Failed",
          s"The authentication token is invalid or expired.\nReason: $reason"
        )
        Behaviors.stopped

      case _ =>
        Behaviors.same
  /**
   * Bootstrap logic: executed immediately upon creation.
   */
  def ready(): Behavior[RootCommand] =
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
          val port = context.system.address.port.getOrElse(0)
          val trainPath = appConfig.trainingSnapshotPath(port)
          val modelPath = appConfig.modelSnapshotPath(port)

          val localConfig = PersistenceManager.loadFromFile[TrainingConfig](trainPath).toOption
          val localModel = PersistenceManager.loadFromFile[Model](modelPath).toOption

          if (localConfig.exists(_.simulationId == trainConfig.simulationId)) {
            context.log.info(s"Root: Peer recovery for simulation ${trainConfig.simulationId}")

            val recoveredConfig = localConfig.get
            val recoveredModel = localModel.getOrElse(model)

            val optimizer = Optimizers.SGD(recoveredConfig.hp.learningRate, Regularizers.fromConfig(recoveredConfig.hp.regularization))

            modelActor ! ModelCommand.Initialize(recoveredModel, optimizer, trainerActor)
            monitorActor ! MonitorCommand.Initialize(seedID, recoveredModel, recoveredConfig)
            trainerActor ! TrainerCommand.SetTrainConfig(recoveredConfig)

            if (recoveredConfig.trainSet.nonEmpty) {
              clusterManager ! ClusterProtocol.StartSimulation
              trainerActor ! TrainerCommand.Start(recoveredConfig.trainSet, recoveredConfig.testSet)
            }
          } else {
            val optimizer = Optimizers.SGD(trainConfig.hp.learningRate, Regularizers.fromConfig(trainConfig.hp.regularization))
            modelActor ! ModelCommand.Initialize(model, optimizer, trainerActor)
            monitorActor ! MonitorCommand.Initialize(seedID, model, trainConfig)
            trainerActor ! TrainerCommand.SetTrainConfig(trainConfig)
          }
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

            val (model, tConfig, optimizer) = initializeFreshState(fileConf)

            modelActor ! ModelCommand.Initialize(model, optimizer, trainerActor)
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
        case _ =>
          Behaviors.unhandled

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
      simulationId = UUID.randomUUID().toString,
      trainSet = Nil,
      testSet = Nil,
      features = conf.features,
      hp = conf.hyperParams,
      epochs = conf.epochs,
      batchSize = conf.batchSize,
      seed = conf.seed
    )

  /**
   * Initializes the core components required for a fresh training session.
   * It sets up the Stochastic Gradient Descent (SGD) optimizer and delegates the creation
   * of the neural network model and the training configuration.
   *
   * @param conf The [[FileConfig]] containing the specifications for the model architecture, hyperparameters, and training settings.
   * @return A tuple containing the newly instantiated [[Model]], the corresponding [[TrainingConfig]], and the initialized [[Optimizers.SGD]] optimizer.
   */
  private def initializeFreshState(conf: FileConfig): (Model, TrainingConfig, Optimizers.SGD) =
    val optimizer = new Optimizers.SGD(
      conf.hyperParams.learningRate,
      Regularizers.fromConfig(conf.hyperParams.regularization)
    )
    (createModel(conf), createTrainConfig(conf), optimizer)
