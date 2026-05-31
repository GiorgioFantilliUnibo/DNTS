package domain.authentication

/**
 * Represents the possible authentication actions that a client can perform
 * on the seed node's authentication actor.
 *
 * @param id The string identifier of the action.
 */
enum AuthAction(val id: String):

  /**
   * Action indicating a new user registration request to store credentials.
   */
  case Register extends AuthAction("register")

  /**
   * Action indicating a login request to validate existing credentials and receive a token.
   */
  case Login extends AuthAction("login")

  override def toString: String = id

/**
 * Companion object for [[AuthAction]], providing utility methods
 * for string-based parsing and validation of authentication actions.
 */
object AuthAction:

  /**
   * Internal lookup map for efficient string to action resolution.
   */
  private val lookup: Map[String, AuthAction] =
    values.map(action => action.id -> action).toMap

  /**
   * Converts a string identifier into its corresponding [[AuthAction]].
   *
   * @param s The string representation of the action.
   * @return An `Option` containing the matching [[AuthAction]], or `None` if the string is invalid.
   */
  def fromString(s: String): Option[AuthAction] =
    lookup.get(s.toLowerCase)

  /**
   * Retrieves a formatted string of all valid authentication action identifiers.
   *
   * @return A pipe-separated string of valid options.
   */
  def validOptions: String = values.map(_.id).mkString("|")