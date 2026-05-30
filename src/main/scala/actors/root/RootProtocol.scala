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

  /** Protocol for the RootActor */
  object RootCommand:

    /**
     * Triggered by the Seed Node to start the simulation.
     */
    case object SeedStartSimulation extends RootCommand

    /**
     * Command containing the partitions of the distributed dataset allocated to this local node.
     * Sent during the data distribution phase to provide the training and test sets.
     *
     * @param trainShard The slice of the training dataset assigned to this node for local gradient computation.
     * @param testSet    Test set assigned to the local node.
     */
    final case class DistributedDataset(
      trainShard: List[LabeledPoint2D],
      testSet: List[LabeledPoint2D]
    ) extends RootCommand

    /**
     * Confirms and applies the initial network configuration parameters propagated from the seed node.
     * Ensures all worker nodes synchronize on the same baseline model architecture and hyperparameters.
     *
     * @param seedID      The unique identifier of the seed node.
     * @param model       The shared initial model.
     * @param trainConfig Hyperparameters, optimization strategies, and context settings for the training session.
     */
    final case class ConfirmInitialConfiguration(
      seedID: String,
      model: Model,
      trainConfig: TrainingConfig
    ) extends RootCommand

    /**
     * Internal adapter message wrapping the Receptionist listing result for the authentication service.
     * Enables the actor to react to changes or discoveries in the availability of [[AuthActor]] instances.
     *
     * @param listing The Akka Receptionist service listing containing references to available AuthActors.
     */
    final case class WrappedAuthListing(listing: Listing) extends RootCommand

    /**
     * Internal adapter message wrapping the response from a user registration request.
     * Maps external authentication replies back into the native [[RootCommand]] protocol.
     *
     * @param reply The response status regarding the user registration outcome.
     */
    final case class WrappedRegisterReply(reply: RegisterReply) extends RootCommand

    /**
     * Internal adapter message wrapping the response from an authentication/login request.
     * Maps token issuance or failure details back into the native [[RootCommand]] protocol.
     *
     * @param reply The response status containing the valid authentication token or an error description.
     */
    final case class WrappedAuthenticateReply(reply: AuthenticateReply) extends RootCommand

    /**
     * Internal adapter message wrapping the response from a token validation request.
     * Maps the validation outcome back into the native [[RootCommand]] protocol.
     *
     * @param reply The validation status of the provided authentication token.
     */
    final case class WrappedValidateTokenReply(reply: ValidateTokenReply) extends RootCommand

    /**
     * Triggered in case the cluster connection has been confirmed
     */
    case object ClusterReady extends RootCommand

    /**
     * Triggered in case the cluster is not reachable.
     * */
    case object ClusterFailed extends RootCommand

    /**
     * Internal safety signal indicating that an invalid message
     * was intercepted during the initial bootstrap state.
     */
    case object InvalidCommandInBootstrap extends RootCommand

    /**
     * Internal safety signal indicating that an invalid message
     * was intercepted while the actor was in the cluster joining state.
     */
    case object InvalidCommandInJoining extends  RootCommand

    /**
     * Triggered when stop is selected.
     */
    case object StopSimulation extends RootCommand
