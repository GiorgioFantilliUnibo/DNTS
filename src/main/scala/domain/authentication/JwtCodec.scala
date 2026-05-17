package domain.authentication

import domain.authentication.User
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException

import java.time.Instant
import scala.util.Try
import scala.concurrent.duration.FiniteDuration

final class JwtCodec(secret: String):

  private val algorithm: Algorithm = Algorithm.HMAC256(secret)

  private val verifier =
    JWT.require(algorithm)
      .withIssuer("dnts")
      .build()

  private val ClaimFullName = "fullName"
  private val ClaimRole     = "role"

  def encode(user: User, duration: FiniteDuration): Token =
    val expiration = Instant.now().plusMillis(duration.toMillis)
    val raw = JWT.create()
      .withIssuer("dnts")
      .withSubject(user.username)
      .withClaim(ClaimFullName, user.fullName)
      .withClaim(ClaimRole,     user.role.id)
      .withExpiresAt(expiration)
      .sign(algorithm)
    Token(raw, user, expiration)

  def decode(raw: String): Either[String, Token] =
    Try(verifier.verify(raw)).toEither.left.map(_.getMessage).flatMap { decoded =>
      val username = decoded.getSubject
      val fullName = decoded.getClaim(ClaimFullName).asString()
      val roleId   = decoded.getClaim(ClaimRole).asString()
      val exp      = decoded.getExpiresAtAsInstant

      NodeRole.fromString(roleId) match
        case None =>
          Left(s"Unknown role in JWT: $roleId")
        case Some(role) =>
          val user = User(username, fullName, role, password = "")
          Right(Token(raw, user, exp))
    }

