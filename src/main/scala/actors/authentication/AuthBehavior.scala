package actors.authentication

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import actors.authentication.AuthProtocol.*
import domain.authentication.NodeRole.Seed
import domain.authentication.{AuthenticationService, Credentials, Crypto, Token, User, UserDatabase}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
 * Encapsulates the behavior logic for the AuthActor.
 *
 * @param context  The actor context.
 * @param database The database responsible for storing and retrieving user records.
 * @param service  The service used to generate and validate authentication tokens.
 */
private[authentication] class AuthBehavior(
                                            context: ActorContext[AuthCommand],
                                            database: UserDatabase,
                                            service:  AuthenticationService
                                          ):

  /**
   * Main state: Handles incoming authentication and user management commands.
   *
   * @param activeSessions currently connected users
   */
  def active(activeSessions: Map[String, Token] = Map.empty): Behavior[AuthCommand] =
    Behaviors.receive: (context, message) =>
      message match
        case Register(rawUser, replyTo) =>
          handleRegister(context, rawUser, replyTo)

        case GetUser(id, tokenOpt, replyTo) =>
          handleGetUser(id, tokenOpt, replyTo)

        case CheckPassword(credentials, replyTo) =>
          handleCheckPassword(credentials, replyTo)

        case Authenticate(credentials, duration, replyTo) =>
          handleAuthenticate(credentials, duration, replyTo, activeSessions)

        case ValidateToken(token, replyTo) =>
          handleValidateToken(token, replyTo)

  /**
   * Handles the registration of a new user by securely hashing their password
   * and storing the record in the database.
   *
   * @param context The actor context for emitting logs.
   * @param rawUser The unencrypted user data provided for registration.
   * @param replyTo The reference of the actor who will receive the registration result.
   */
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

  /**
   * Processes a request to retrieve a user's details.
   *
   * @param id       The unique ID of the user to find.
   * @param tokenOpt An optional token provided by the requester.
   * @param replyTo  The reference to reply with the user details or an error.
   */
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

  /**
   * Processes a request to verify a user's password without issuing a token.
   *
   * @param credentials The ID and plaintext password to verify.
   * @param replyTo     The reference for responding with the test result.
   */
  private def handleCheckPassword(
                                   credentials: Credentials,
                                   replyTo: ActorRef[CheckPasswordReply]
                                 ): Behavior[AuthCommand] =
    val valid = database.checkPassword(credentials)
    context.log.debug(s"AuthActor: CheckPassword for '${credentials.id}' → $valid")
    replyTo ! (if valid then CheckPasswordReply.Valid else CheckPasswordReply.Invalid)
    Behaviors.same

  /**
   * Processes an authentication request.
   * If credentials are valid, it issues a token with the specified duration.
   *
   * @param credentials The ID and plaintext password to verify.
   * @param duration    The lifetime of the generated token.
   * @param replyTo     The reference to reply with the generated token or a failure reason.
   * @param activeSessions currently connected users
   */
  private def handleAuthenticate(
                                  credentials: Credentials,
                                  duration: FiniteDuration,
                                  replyTo: ActorRef[AuthenticateReply],
                                  activeSessions: Map[String, Token]
                                ): Behavior[AuthCommand] =

    activeSessions.get(credentials.id) match
      case Some(existingToken) if !existingToken.isExpired =>
        context.log.warn(s"AuthActor: Login denied for '${credentials.id}'. The user already has an active session.")
        replyTo ! AuthenticateReply.AuthFailed("User already connected from another terminal.")
        active(activeSessions)
      case _ =>
        service.authenticate(credentials, duration) match
          case Right(token) =>
            context.log.info(s"AuthActor: User '${credentials.id}' authenticated — token issued (expires ${token.expiration}).")
            replyTo ! AuthenticateReply.Authenticated(token)
            active(activeSessions + (credentials.id -> token))

          case Left(reason) =>
            context.log.warn(s"AuthActor: Authentication failed for '${credentials.id}': $reason")
            replyTo ! AuthenticateReply.AuthFailed(reason)
            active(activeSessions)

  /**
   * Processes a request to validate an existing token.
   *
   * @param token   The token to be validated.
   * @param replyTo The reference to reply with the validation status.
   */
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