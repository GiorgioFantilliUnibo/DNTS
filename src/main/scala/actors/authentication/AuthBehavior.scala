package actors.authentication

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import actors.authentication.AuthProtocol.*
import domain.authentication.NodeRole.Seed
import domain.authentication.{AuthenticationService, Credentials, Crypto, Token, User, UserDatabase}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

private[authentication] class AuthBehavior(
                                            context: ActorContext[AuthCommand],
                                            database: UserDatabase,
                                            service:  AuthenticationService
                                          ):

  def active(): Behavior[AuthCommand] =
    Behaviors.receive: (context, message) =>
      message match
        case Register(rawUser, replyTo) =>
          handleRegister(context, rawUser, replyTo)

        case GetUser(id, tokenOpt, replyTo) =>
          handleGetUser(id, tokenOpt, replyTo)

        case CheckPassword(credentials, replyTo) =>
          handleCheckPassword(credentials, replyTo)

        case Authenticate(credentials, duration, replyTo) =>
          handleAuthenticate(credentials, duration, replyTo)

        case ValidateToken(token, replyTo) =>
          handleValidateToken(token, replyTo)

  private def handleRegister(
                              context: ActorContext[AuthCommand],
                              rawUser: User,
                              replyTo: ActorRef[RegisterReply]
                            ): Behavior[AuthCommand] =
    val hashedUser = rawUser.copy(password = Crypto.sha256(rawUser.password))

    Try(database.addUser(hashedUser)) match
      case Success(_) =>
        context.log.info(s"AuthActor: User '${rawUser.username}' registered successfully.")
        replyTo ! RegisterReply.Registered

      case Failure(ex: IllegalArgumentException) =>
        context.log.warn(s"AuthActor: Registration failed — ${ex.getMessage}")
        replyTo ! RegisterReply.AlreadyExists(ex.getMessage)

      case Failure(ex: Exception) =>
        context.log.error(s"AuthActor: Unexpected registration error — ${ex.getMessage}")
        replyTo ! RegisterReply.RegisterError(ex.getMessage)

    Behaviors.same

  private def handleGetUser(
                             id: String,
                             tokenOpt: Option[Token],
                             replyTo: ActorRef[GetUserReply]
                           ): Behavior[AuthCommand] =
    val authorized = tokenOpt.exists { token =>
      service.validateToken(token) && token.user.role == Seed
    }
    if !authorized then
      context.log.warn(s"AuthActor: Unauthorized GetUser attempt for id='$id'.")
      replyTo ! GetUserReply.Unauthorized("Valid admin token required to retrieve user data.")
    else
      database.getUser(id) match
        case Some(user) =>
          context.log.debug(s"AuthActor: GetUser '$id' → found.")
          replyTo ! GetUserReply.Found(user.withoutPassword)
        case None =>
          context.log.debug(s"AuthActor: GetUser '$id' → not found.")
          replyTo ! GetUserReply.NotFound(id)
    Behaviors.same

  private def handleCheckPassword(
                                   credentials: Credentials,
                                   replyTo: ActorRef[CheckPasswordReply]
                                 ): Behavior[AuthCommand] =
    val valid = database.checkPassword(credentials)
    context.log.debug(s"AuthActor: CheckPassword for '${credentials.id}' → $valid")
    replyTo ! (if valid then CheckPasswordReply.Valid else CheckPasswordReply.Invalid)
    Behaviors.same

  private def handleAuthenticate(
                                  credentials: Credentials,
                                  duration: FiniteDuration,
                                  replyTo: ActorRef[AuthenticateReply]
                                ): Behavior[AuthCommand] =
    service.authenticate(credentials, duration) match
      case Right(token) =>
        context.log.info(s"AuthActor: User '${credentials.id}' authenticated — token issued (expires ${token.expiration}).")
        replyTo ! AuthenticateReply.Authenticated(token)
      case Left(reason) =>
        context.log.warn(s"AuthActor: Authentication failed for '${credentials.id}': $reason")
        replyTo ! AuthenticateReply.AuthFailed(reason)
    Behaviors.same

  private def handleValidateToken(
                                   token: Token,
                                   replyTo: ActorRef[ValidateTokenReply]
                                 ): Behavior[AuthCommand] =
    if service.validateToken(token) then
      context.log.debug(s"AuthActor: Token for '${token.user.username}' is valid.")
      replyTo ! ValidateTokenReply.TokenValid(token)
    else
      val reason = if token.isExpired then "Token expired" else "Invalid signature"
      context.log.warn(s"AuthActor: Token validation failed for '${token.user.username}': $reason")
      replyTo ! ValidateTokenReply.TokenInvalid(reason)
    Behaviors.same