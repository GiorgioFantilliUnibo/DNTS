package actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

import actors.authentication.AuthActor
import actors.authentication.AuthProtocol.*
import domain.authentication.{Credentials, NodeRole, User}
import config.{AppConfig, ProductionConfig}

class AuthActorTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike with Matchers {

  given AppConfig = ProductionConfig

  private final val dummyUser = User(
    username = "testUser",
    fullName = "Test User",
    role = NodeRole.Client,
    password = "pass123"
  )

  private final val validCredentials = Credentials("testUser", "pass123")
  private final val invalidCredentials = Credentials("testUser", "wrong")

  test("AuthActor should successfully register a new user") {
    val authActor = spawn(AuthActor())
    val probe = createTestProbe[RegisterReply]()

    authActor ! Register(dummyUser, probe.ref)

    probe.expectMessage(RegisterReply.Registered)
  }

  test("AuthActor should prevent registration of an already existing user") {
    val authActor = spawn(AuthActor())
    val probe = createTestProbe[RegisterReply]()

    authActor ! Register(dummyUser, probe.ref)
    probe.expectMessage(RegisterReply.Registered)

    authActor ! Register(dummyUser, probe.ref)

    val reply = probe.expectMessageType[RegisterReply.AlreadyExists]
    reply.reason should include("already registered")
  }

  test("AuthActor should authenticate valid credentials and return a token") {
    val authActor = spawn(AuthActor())
    val regProbe = createTestProbe[RegisterReply]()
    val authProbe = createTestProbe[AuthenticateReply]()

    authActor ! Register(dummyUser, regProbe.ref)
    regProbe.expectMessage(RegisterReply.Registered)

    authActor ! Authenticate(validCredentials, 1.hour, authProbe.ref)

    val reply = authProbe.expectMessageType[AuthenticateReply.Authenticated]
    reply.token.user.username shouldBe dummyUser.username
    reply.token.user.role shouldBe dummyUser.role
  }

  test("AuthActor should reject authentication with an incorrect password") {
    val authActor = spawn(AuthActor())
    val regProbe = createTestProbe[RegisterReply]()
    val authProbe = createTestProbe[AuthenticateReply]()

    authActor ! Register(dummyUser, regProbe.ref)
    regProbe.expectMessage(RegisterReply.Registered)

    authActor ! Authenticate(invalidCredentials, 1.hour, authProbe.ref)

    authProbe.expectMessageType[AuthenticateReply.AuthFailed]
  }

  test("AuthActor should reject authentication for an unregistered user") {
    val authActor = spawn(AuthActor())
    val authProbe = createTestProbe[AuthenticateReply]()

    val unknownUserCredentials = Credentials("ghostuser", "password")

    authActor ! Authenticate(unknownUserCredentials, 1.hour, authProbe.ref)

    authProbe.expectMessageType[AuthenticateReply.AuthFailed]
  }

  test("AuthActor should correctly validate an active token") {
    val authActor = spawn(AuthActor())
    val regProbe = createTestProbe[RegisterReply]()
    val authProbe = createTestProbe[AuthenticateReply]()
    val valProbe = createTestProbe[ValidateTokenReply]()

    authActor ! Register(dummyUser, regProbe.ref)
    regProbe.expectMessage(RegisterReply.Registered)

    authActor ! Authenticate(validCredentials, 1.hour, authProbe.ref)
    val authReply = authProbe.expectMessageType[AuthenticateReply.Authenticated]

    authActor ! ValidateToken(authReply.token, valProbe.ref)

    val valReply = valProbe.expectMessageType[ValidateTokenReply.TokenValid]
    valReply.token shouldBe authReply.token
  }
}