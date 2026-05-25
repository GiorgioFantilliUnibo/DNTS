package actors.authentication

import domain.authentication.User
import domain.authentication.Credentials
import domain.authentication.Token

import akka.actor.typed.ActorRef

import scala.concurrent.duration.FiniteDuration

/**
 * Defines the public API for the Authentication component.
 */
object AuthProtocol:

  /** Replies for the Register command. */
  enum RegisterReply:
    case Registered
    case AlreadyExists(reason: String)
    case RegisterError(reason: String)

  /** Replies for the GetUser command. */
  enum GetUserReply:
    case Found(user: User)
    case NotFound(id: String)
    case Unauthorized(reason: String)

  /** Replies for the CheckPassword command. */
  enum CheckPasswordReply:
    case Valid
    case Invalid

  /** Replies for the Authenticate command. */
  enum AuthenticateReply:
    case Authenticated(token: Token)
    case AuthFailed(reason: String)

  /** Replies for the ValidateToken command. */
  enum ValidateTokenReply:
    case TokenValid(token: Token)
    case TokenInvalid(reason: String)

  /**
   * Root trait for all messages handled by the AuthActor.
   */
  sealed trait AuthCommand

  /**
   * Requests the registration of a new user.
   *
   * @param user    The user details to be registered.
   * @param replyTo The actor reference that will receive the RegisterReply.
   */
  final case class Register(user: User, replyTo: ActorRef[RegisterReply]) extends AuthCommand

  /**
   * Requests the retrieval of user details by their ID.
   * Requires a valid admin token to authorize the action.
   *
   * @param id      The unique identifier of the user to retrieve.
   * @param token   An optional authorization token (must belong to a seed user).
   * @param replyTo The actor reference that will receive the GetUserReply.
   */
  final case class GetUser(id: String, token: Option[Token], replyTo: ActorRef[GetUserReply]) extends AuthCommand

  /**
   * Checks if the provided credentials (ID and password) match a user in the database.
   *
   * @param credentials The user ID and plaintext password to verify.
   * @param replyTo     The actor reference that will receive the CheckPasswordReply.
   */
  final case class CheckPassword(credentials: Credentials, replyTo: ActorRef[CheckPasswordReply]) extends AuthCommand

  /**
   * Authenticates a user and issues a token if the credentials are valid.
   *
   * @param credentials The user ID and plaintext password to authenticate.
   * @param duration    The validity duration for the issued token.
   * @param replyTo     The actor reference that will receive the AuthenticateReply.
   */
  final case class Authenticate(credentials: Credentials, duration: FiniteDuration, replyTo: ActorRef[AuthenticateReply]) extends AuthCommand

  /**
   * Validates an existing authentication token.
   *
   * @param token   The token to validate.
   * @param replyTo The actor reference that will receive the ValidateTokenReply.
   */
  final case class ValidateToken(token: Token, replyTo: ActorRef[ValidateTokenReply]) extends AuthCommand
