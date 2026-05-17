package actors.authentication

import domain.authentication.User
import domain.authentication.Credentials
import domain.authentication.Token

import akka.actor.typed.ActorRef

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

object AuthProtocol:

  enum RegisterReply:
    case Registered
    case AlreadyExists(reason: String)
    case RegisterError(reason: String)

  enum GetUserReply:
    case Found(user: User)
    case NotFound(id: String)
    case Unauthorized(reason: String)

  enum CheckPasswordReply:
    case Valid
    case Invalid

  enum AuthenticateReply:
    case Authenticated(token: Token)
    case AuthFailed(reason: String)

  enum ValidateTokenReply:
    case TokenValid(token: Token)
    case TokenInvalid(reason: String)

  sealed trait AuthCommand

  final case class Register(user: User, replyTo: ActorRef[RegisterReply]) extends AuthCommand

  final case class GetUser(id: String, token: Option[Token], replyTo: ActorRef[GetUserReply]) extends AuthCommand

  final case class CheckPassword(credentials: Credentials, replyTo: ActorRef[CheckPasswordReply]) extends AuthCommand

  final case class Authenticate(credentials: Credentials, duration: FiniteDuration, replyTo: ActorRef[AuthenticateReply]) extends AuthCommand

  final case class ValidateToken(token: Token, replyTo: ActorRef[ValidateTokenReply]) extends AuthCommand
