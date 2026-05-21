package actors.authentication

import actors.discovery.DiscoveryProtocol.DiscoveryCommand
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.ServiceKey
import domain.authentication.{AuthenticationService, InMemoryAuthenticationService, InMemoryUserDatabase, UserDatabase}
import config.AppConfig

object AuthActor:
  export AuthProtocol.*

  val AuthServiceKey: ServiceKey[AuthCommand] = ServiceKey[AuthCommand]("auth-service")

  def apply()(using config: AppConfig): Behavior[AuthCommand] =

    val database: UserDatabase = InMemoryUserDatabase()
    val service: AuthenticationService = InMemoryAuthenticationService(database, "secret")

    Behaviors.setup: context =>
      Behaviors.withTimers: timers =>
        AuthBehavior(context, database, service).active()