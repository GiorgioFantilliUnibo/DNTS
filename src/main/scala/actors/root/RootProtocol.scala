package actors.root

import domain.network.Model
import actors.trainer.TrainerActor.TrainingConfig
import domain.data.LabeledPoint2D

/**
 * Defines the public API for the Root component.
 */
object RootProtocol:

  /**
   * Defines the operational role of a node within the cluster architecture.
   *
   * @param id The string identifier associated with the role.
   */
  enum NodeRole(val id: String):
    
    /**
     * Represents the Seed Node within the cluster.
     * Acts as an initial contact point for new members joining the cluster.
     */
    case Seed extends NodeRole("seed")

    /**
     * Represents a Client Node within the cluster.
     */
    case Client extends NodeRole("client")

    
    /** @return The string identifier of this role. */
    override def toString: String = id

  /**
   * Factory and utility methods for [[NodeRole]].
   */
  object NodeRole:

    private val lookup: Map[String, NodeRole] =
      values.map(role => role.id -> role).toMap

    /**
     * Safely parses a string into a NodeRole.
     *
     * @param s The string representation of the role.
     * @return [[Some]]([[NodeRole]]) if the string matches a valid role, [[None]] otherwise.
     */
    def fromString(s: String): Option[NodeRole] =
      lookup.get(s.toLowerCase)

    /**
     * Returns a pipe-separated string of all valid role identifiers.
     *
     * @return A string like "seed|client".
     */
    def validOptions: String = values.map(_.id).mkString("|")


  /**
   * Commands handled by the RootActor.
   */
  sealed trait RootCommand

  /** Protocol for the RootActor. */
  object RootCommand:

    /** Triggered by the Seed Node to start the simulation. */
    case object SeedStartSimulation extends RootCommand

    /**
     * Command carrying the assigned dataset shards for the specific simulation.
     * Receiving this command triggers the actual sets distribution and start of the training process.
     *
     * @param trainShard The portion of the dataset used for training the global simulation.
     * @param testSet    The portion of the dataset used for validating the model.
     */
    final case class DistributedDataset(
      trainShard: List[LabeledPoint2D],
      testSet: List[LabeledPoint2D]
    ) extends RootCommand

    /**
     * Confirms the reception of the initial training configuration and neural network model.
     * It triggers the initialization of the local actors (Model, Trainer, Monitor).
     *
     * @param seedID      The identifier of the seed node that provided the configuration.
     * @param model       The initial state of the neural network model.
     * @param trainConfig The global training hyperparameters and settings.
     */
    final case class ConfirmInitialConfiguration(
      seedID: String,
      model: Model,
      trainConfig: TrainingConfig
    ) extends RootCommand

    /** Triggered in case the cluster connection has been confirmed */
    case object ClusterReady extends RootCommand

    /** Triggered in case the cluster is not reachable. */
    case object ClusterFailed extends RootCommand

    /**
     * Commands the immediate and graceful shutdown of the simulation.
     * It propagates the stop signal to all child actors (Cluster, Trainer, Model, etc.)
     * and waits for their termination before stopping the Root component.
     */
    case object StopSimulation extends RootCommand

    /** Command to simulate a critical hardware/OS crash instantly. */
    case object SimulateCrash extends RootCommand
