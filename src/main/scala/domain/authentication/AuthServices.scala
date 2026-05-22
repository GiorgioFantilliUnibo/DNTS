package domain.authentication

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

trait UserDatabase:

  def addUser(user: User): Unit

  def getUser(id: String): Option[User]

  def checkPassword(credentials: Credentials): Boolean


trait AuthenticationService:

  def authenticate(credentials: Credentials, duration: FiniteDuration): Either[String, Token]

  def validateToken(token: Token): Boolean


class InMemoryUserDatabase extends UserDatabase:

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


class InMemoryAuthenticationService(
                                     database: UserDatabase,
                                     secret:   String
                                   ) extends AuthenticationService:

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
