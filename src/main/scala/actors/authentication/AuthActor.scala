package actors.authentication

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.ServiceKey
import domain.authentication.{AuthenticationService, InMemoryAuthenticationService, InMemoryUserDatabase, UserDatabase}
import config.AppConfig

/**
 * This actor is responsible for maintaining the authentication state,
 * managing user registration, verifying credentials, and issuing/validating tokens.
 */
object AuthActor:

  export AuthProtocol.*

  val AuthServiceKey: ServiceKey[AuthCommand] = ServiceKey[AuthCommand]("auth-service")

  /**
   * Creates the initial behavior for the AuthActor.
   *
   * It initializes an in-memory database and an authentication service,
   * then delegates the message handling to the AuthBehavior in its active state.
   *
   * @param config Implicit global application configuration.
   * @return A Behavior handling AuthCommand messages.
   */
  def apply()(using config: AppConfig): Behavior[AuthCommand] =

    val database: UserDatabase = InMemoryUserDatabase()
    val service: AuthenticationService = InMemoryAuthenticationService(database, "secret")

    Behaviors.setup: context =>
      Behaviors.withTimers: timers =>
        AuthBehavior(context, database, service).active()