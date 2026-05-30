package actors.root

import domain.authentication.{AuthAction, NodeRole}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.Behavior
import com.typesafe.config.Config
import config.AppConfig


/**
 * Actor responsible for bootstrapping the entire application
 * hierarchy based on the provided [[NodeRole]].
 */
object RootActor:

  export RootProtocol.*

  /**
   * Creates the RootActor behavior.
   *
   * @param role       The role passed via CLI.
   * @param configPath Optional path to the configuration file.
   * @param akkaConfig Application configuration of application.conf.
   * @param action     Action (login, registration) to be performed on the seed actor that handles authentication.
   * @param username   Username of the client performing the authentication.
   * @param password   Password of the client performing the authentication.
   * @param fullName   Optional full name of the client performing the authentication.
   */
  def apply(
    role: NodeRole,
    configPath: Option[String],
    akkaConfig: Config,
    action: AuthAction,
    username: Option[String],
    password: Option[String],
    fullName: Option[String] = None
  )(using appConfig: AppConfig): Behavior[RootCommand] =
    Behaviors.setup: context =>
      new RootBehavior(
        context,
        role,
        configPath,
        akkaConfig,
        action,
        username,
        password,
        fullName).start()
