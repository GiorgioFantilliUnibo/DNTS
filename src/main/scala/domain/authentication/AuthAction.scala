package domain.authentication

enum AuthAction(val id: String):

  case Register extends AuthAction("register")

  case Login extends AuthAction("login")

  override def toString: String = id

object AuthAction:

  private val lookup: Map[String, AuthAction] =
    values.map(action => action.id -> action).toMap

  def fromString(s: String): Option[AuthAction] =
    lookup.get(s.toLowerCase)

  def validOptions: String = values.map(_.id).mkString("|")