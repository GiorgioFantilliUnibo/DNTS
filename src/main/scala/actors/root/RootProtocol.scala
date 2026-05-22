package actors.root

import domain.network.Model
import actors.trainer.TrainerActor.TrainingConfig
import actors.authentication.AuthProtocol.{RegisterReply, AuthenticateReply, ValidateTokenReply}
import domain.data.LabeledPoint2D
import akka.actor.typed.receptionist.Receptionist.Listing

/**
 * Defines the public API for the Root component.
 */
object RootProtocol:

  /**
   * Commands handled by the RootActor.
   */
  sealed trait RootCommand

  /** Protocol for the RootActor. */
  object RootCommand:

    /**
     * Triggered by the Seed Node to start the simulation.
     */
    case object SeedStartSimulation extends RootCommand

    final case class DistributedDataset(
      trainShard: List[LabeledPoint2D],
      testSet: List[LabeledPoint2D]
    ) extends RootCommand

    final case class ConfirmInitialConfiguration(
      seedID: String,
      model: Model,
      trainConfig: TrainingConfig
    ) extends RootCommand

    final case class WrappedAuthListing(listing: Listing) extends RootCommand

    final case class WrappedRegisterReply(reply: RegisterReply) extends RootCommand

    final case class WrappedAuthenticateReply(reply: AuthenticateReply) extends RootCommand

    final case class WrappedValidateTokenReply(reply: ValidateTokenReply) extends RootCommand

    /**
     * Triggered in case the cluster connection has been confirmed
     */
    case object ClusterReady extends RootCommand

    /**
     * Triggered in case the cluster is not reachable.
     * */
    case object ClusterFailed extends RootCommand

    case object InvalidCommandInBootstrap extends RootCommand

    case object InvalidCommandInJoining extends  RootCommand

    case object StopSimulation extends RootCommand
