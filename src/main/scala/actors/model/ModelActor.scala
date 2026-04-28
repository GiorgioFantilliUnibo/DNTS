package actors.model

import actors.model.ModelActor.ModelCommand
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import config.AppConfig

/**
 * This actor is responsible for maintaining the model's state, applying
 * gradients to update the weights, and managing the synchronization of
 * requests from the Gossip Actor.
 */
object ModelActor:

  export ModelProtocol.*

  /**
   * Creates the initial behavior for the ModelActor.
   *
   * It initializes the actor in an 'idle' state, waiting for the mandatory
   * configuration and initial model structure to be provided via the Initialize command.
   *
   * @param config Implicit global application configuration.
   * @return A Behavior handling ModelCommand messages.
   */
  def apply()(using config: AppConfig): Behavior[ModelCommand] =
    Behaviors.setup: ctx =>
      Behaviors.withTimers: timers =>
        ModelBehavior(ctx, timers, config).idle()
