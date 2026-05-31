package domain.authentication

import java.time.Instant

/**
 * Represents a system user with their profile information, role, and authentication credentials.
 *
 * @param username The unique username of the user.
 * @param fullName The user's full name.
 * @param role     The [[NodeRole]] assigned to the user (Client or Seed).
 * @param password The user's password (typically hashed).
 */
final case class User(username: String, fullName: String, role: NodeRole, password: String):

  /**
   * Gets the unique identifier for the user.
   *
   * @return The username acting as the ID.
   */
  def id: String = username

  /**
   * Creates a safe copy of the user instance with the password field cleared.
   * This is particularly useful for serializing the user data into tokens
   * or transmitting it across the network without exposing sensitive information.
   *
   * @return A new [[User]] instance with an empty password string.
   */
  def withoutPassword: User = copy(password = "")

  override def toString: String =
    s"User(username=$username, fullName=$fullName, role=$role)"

/**
 * Encapsulates the credentials required for user authentication.
 *
 * @param id       The unique identifier of the user (typically the username).
 * @param password The plain-text password provided for validation.
 */
final case class Credentials(id: String, password: String)

/**
 * Represents an authentication token granted to a user after a successful login.
 *
 * @param raw        The raw string representation of the token (an encoded JWT).
 * @param user       The [[User]] associated with this token (usually instantiated without the password).
 * @param expiration The timestamp (`Instant`) at which this token becomes invalid.
 */
final case class Token(raw: String, user: User, expiration: Instant):

  /**
   * Checks whether the current time has passed the token's expiration timestamp.
   *
   * @return `true` if the token is expired, `false` if it is still valid.
   */
  def isExpired: Boolean = Instant.now().isAfter(expiration)

  override def toString: String =
    s"Token(user=${user.username}, expiration=$expiration, valid=${!isExpired})"
