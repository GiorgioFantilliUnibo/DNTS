package domain.authentication

import java.time.Instant

final case class User(username: String, fullName: String, role: NodeRole, password: String):

  def id: String = username

  def withoutPassword: User = copy(password = "")

  override def toString: String =
    s"User(username=$username, fullName=$fullName, role=$role)"

final case class Credentials(id: String, password: String)

final case class Token(raw: String, user: User, expiration: Instant):

  def isExpired: Boolean = Instant.now().isAfter(expiration)

  override def toString: String =
    s"Token(user=${user.username}, expiration=$expiration, valid=${!isExpired})"
