package domain.authentication

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

/**
 * Defines the main operations for managing users and validating credentials.
 */
trait UserDatabase:

  /**
   * Registers a new user in the database.
   *
   * @param user The [[User]] instance to be added.
   * @throws IllegalArgumentException if a user with the same ID already exists.
   */
  def addUser(user: User): Unit

  /**
   * Retrieves a user by their unique identifier.
   *
   * @param id The unique identifier of the user.
   * @return An `Option` containing the [[User]] if found, or `None` otherwise.
   */
  def getUser(id: String): Option[User]

  /**
   * Validates the provided credentials against the stored user data.
   *
   * @param credentials The [[Credentials]] (ID and plain-text password) to verify.
   * @return `true` if the credentials are valid and the stored password matches, `false` otherwise.
   */
  def checkPassword(credentials: Credentials): Boolean

/**
 * Defines the operations for handling user authentication and token validation.
 */
trait AuthenticationService:

  /**
   * Authenticates a user based on the provided credentials and generates an access token.
   *
   * @param credentials The user's [[Credentials]] for login.
   * @param duration    The validity duration of the generated token.
   * @return A `Right` containing the generated [[Token]] if authentication is successful,
   *         or a `Left` containing an error message string if it fails.
   */
  def authenticate(credentials: Credentials, duration: FiniteDuration): Either[String, Token]

  /**
   * Verifies the cryptographic validity and expiration of the provided token.
   *
   * @param token The [[Token]] to be validated.
   * @return `true` if the token is valid, `false` otherwise.
   */
  def validateToken(token: Token): Boolean

/**
 * An in-memory, thread-safe implementation of [[UserDatabase]] using a `ConcurrentHashMap`.
 */
class InMemoryUserDatabase extends UserDatabase:

  /**
   * Thread-safe internal storage for users, keyed by their unique IDs.
   */
  private val store = new ConcurrentHashMap[String, User]()

  override def addUser(user: User): Unit =
    if store.containsKey(user.id) then
      throw IllegalArgumentException(s"ID already registered: ${user.id}")
    store.put(user.id, user)

  override def getUser(id: String): Option[User] =
    Option(store.get(id))

  override def checkPassword(credentials: Credentials): Boolean =
    getUser(credentials.id).exists { u =>
      Crypto.safeEquals(u.password, Crypto.sha256(credentials.password))
    }

/**
 * An implementation of [[AuthenticationService]] that uses JSON Web Tokens (JWT) for encoding
 * and validating authentication states.
 *
 * @param database The underlying [[UserDatabase]] used to retrieve and verify user credentials.
 * @param secret   The secret key used for signing and verifying the JWTs.
 */
class InMemoryAuthenticationService(
                                     database: UserDatabase,
                                     secret:   String
                                   ) extends AuthenticationService:

  /**
   * The codec responsible for generating and decoding JWTs using the provided secret.
   */
  private val jwt = JwtCodec(secret)

  override def authenticate(
                             credentials: Credentials,
                             duration:    FiniteDuration
                           ): Either[String, Token] =
    if !database.checkPassword(credentials) then
      Left("Invalid credentials")
    else
      database.getUser(credentials.id) match
        case None       => Left(s"User not found: ${credentials.id}")
        case Some(user) => Right(jwt.encode(user.withoutPassword, duration))

  override def validateToken(token: Token): Boolean =
    jwt.decode(token.raw).isRight
