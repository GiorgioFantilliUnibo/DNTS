package actors.authentication

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import domain.authentication.{AuthenticationService, InMemoryAuthenticationService, InMemoryUserDatabase, UserDatabase}
import config.AppConfig

object AuthActor:
  export AuthProtocol.*

  def apply()(using config: AppConfig): Behavior[AuthCommand] =

    val database: UserDatabase = InMemoryUserDatabase()
    val service: AuthenticationService = InMemoryAuthenticationService(database, "secret")

    Behaviors.setup: context =>
      Behaviors.withTimers: timers =>
        AuthBehavior(context, database, service).active()