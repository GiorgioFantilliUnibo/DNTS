package domain.authentication

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

import java.time.Instant
import scala.util.Try
import scala.concurrent.duration.FiniteDuration

/**
 * Utility codec for encoding and decoding JSON Web Tokens (JWT) used in authentication.
 * It handles the creation of secure tokens containing user identity details and
 * verifies incoming tokens against tampering and expiration.
 *
 * @param secret The private key used to sign and verify the tokens.
 */
final class JwtCodec(secret: String):

  /**
   * The cryptographic signing algorithm initialized with the provided secret key.
   */
  private val algorithm: Algorithm = Algorithm.HMAC256(secret)

  /**
   * The JWT verifier instance configured with the expected algorithm and issuer.
   */
  private val verifier =
    JWT.require(algorithm)
      .withIssuer("dnts")
      .build()

  /**
   * Custom claim key for storing the user's full name.
   */
  private val ClaimFullName = "fullName"

  /**
   * Custom claim key for storing the user's role identifier.
   */
  private val ClaimRole     = "role"

  /**
   * Encodes a [[User]] profile and a validity duration into a signed [[Token]].
   *
   * @param user     The user profile to embed within the token claims (excluding the password).
   * @param duration The lifespan of the token.
   * @return A completed [[Token]] instance wrapping the signed JWT string.
   */
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

  /**
   * Decodes and validates a raw JWT string back into a [[Token]] instance.
   *
   * @param raw The raw encoded JWT string to verify.
   * @return A `Right` containing the reconstructed [[Token]] if validation succeeds,
   *         or a `Left` containing an error message if verification fails.
   */
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

