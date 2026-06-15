package domain.serialization

import actors.authentication.AuthProtocol.*
import domain.authentication.{User, Credentials, Token, NodeRole}
import akka.actor.typed.{ActorRef, ActorRefResolver}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

/**
 * Binary serializers for authentication domain objects and protocol messages.
 * This object provides implicit strategies to convert high-level authentication structures
 * (like [[User]], [[Credentials]], [[Token]], and replies) into byte arrays
 * suitable for network transmission.
 */
object AuthSerializers:

  /**
   * Serializer for the [[User]] domain object.
   * Converts user details (username, full name, role, and password) into a byte array
   * encoded in UTF-8, prefixed by their respective lengths.
   */
  given userSerializer: Serializer[User] with
    extension (u: User)
      def serialize: Array[Byte] =
        val uBytes = u.username.getBytes(StandardCharsets.UTF_8)
        val fBytes = u.fullName.getBytes(StandardCharsets.UTF_8)
        val rBytes = u.role.id.getBytes(StandardCharsets.UTF_8)
        val pBytes = u.password.getBytes(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + uBytes.length + 4 + fBytes.length + 4 + rBytes.length + 4 + pBytes.length)
        buffer.putInt(uBytes.length).put(uBytes)
        buffer.putInt(fBytes.length).put(fBytes)
        buffer.putInt(rBytes.length).put(rBytes)
        buffer.putInt(pBytes.length).put(pBytes)
        buffer.array()

    def deserialize(bytes: Array[Byte]): Try[User] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      def readString(): String = { val len = buffer.getInt; val arr = new Array[Byte](len); buffer.get(arr); new String(arr, StandardCharsets.UTF_8) }
      User(readString(), readString(), NodeRole.fromString(readString()).get, readString())
    }

  /**
   * Serializer for the [[Credentials]] domain object.
   * Converts credentials (id and password) into a byte array encoded in UTF-8,
   * prefixed by their respective lengths.
   */
  given credentialsSerializer: Serializer[Credentials] with
    extension (c: Credentials)
      def serialize: Array[Byte] =
        val idBytes = c.id.getBytes(StandardCharsets.UTF_8)
        val pBytes = c.password.getBytes(StandardCharsets.UTF_8)
        ByteBuffer.allocate(4 + idBytes.length + 4 + pBytes.length).putInt(idBytes.length).put(idBytes).putInt(pBytes.length).put(pBytes).array()

    def deserialize(bytes: Array[Byte]): Try[Credentials] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      def readStr(): String = { val l = buffer.getInt; val a = new Array[Byte](l); buffer.get(a); new String(a, StandardCharsets.UTF_8) }
      Credentials(readStr(), readStr())
    }

  /**
   * Serializer for the [[Token]] domain object.
   * Serializes the raw token string, the associated [[User]], and the expiration timestamp.
   *
   * @param userSer The implicit [[Serializer]] used for [[User]] serialization.
   */
  given tokenSerializer(using userSer: Serializer[User]): Serializer[Token] with
    extension (t: Token)
      def serialize: Array[Byte] =
        val rBytes = t.raw.getBytes(StandardCharsets.UTF_8)
        val uBytes = t.user.serialize
        ByteBuffer.allocate(4 + rBytes.length + 4 + uBytes.length + 8)
          .putInt(rBytes.length).put(rBytes)
          .putInt(uBytes.length).put(uBytes)
          .putLong(t.expiration.toEpochMilli).array()

    def deserialize(bytes: Array[Byte]): Try[Token] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      val rLen = buffer.getInt; val rBytes = new Array[Byte](rLen); buffer.get(rBytes)
      val uLen = buffer.getInt; val uBytes = new Array[Byte](uLen); buffer.get(uBytes)
      val exp = Instant.ofEpochMilli(buffer.getLong)
      Token(new String(rBytes, StandardCharsets.UTF_8), userSer.deserialize(uBytes).get, exp)
    }

  /**
   * Serializer for [[AuthCommand]] protocol messages.
   * Handles the serialization of authentication commands such as Register, Authenticate, and ValidateToken,
   * including the resolution of reply-to ActorRefs.
   *
   * @param userSer  The implicit [[Serializer]] used for [[User]] serialization.
   * @param credSer  The implicit [[Serializer]] used for [[Credentials]] serialization.
   * @param tokenSer The implicit [[Serializer]] used for [[Token]] serialization.
   * @param resolver The [[ActorRefResolver]] used to serialize and deserialize actor references.
   */
  given authCommandSerializer(using
                              userSer: Serializer[User],
                              credSer: Serializer[Credentials],
                              tokenSer: Serializer[Token],
                              resolver: ActorRefResolver
                             ): Serializer[AuthCommand] with
    extension (cmd: AuthCommand)
      def serialize: Array[Byte] = cmd match
        case Register(user, replyTo) =>
          val uB = user.serialize
          val refB = resolver.toSerializationFormat(replyTo).getBytes(StandardCharsets.UTF_8)
          ByteBuffer.allocate(1 + 4 + uB.length + 4 + refB.length).put(1.toByte).putInt(uB.length).put(uB).putInt(refB.length).put(refB).array()

        case Authenticate(cred, dur, replyTo) =>
          val cB = cred.serialize
          val refB = resolver.toSerializationFormat(replyTo).getBytes(StandardCharsets.UTF_8)
          ByteBuffer.allocate(1 + 4 + cB.length + 8 + 4 + refB.length).put(2.toByte).putInt(cB.length).put(cB).putLong(dur.toMillis).putInt(refB.length).put(refB).array()

        case ValidateToken(token, replyTo) =>
          val tB = token.serialize
          val refB = resolver.toSerializationFormat(replyTo).getBytes(StandardCharsets.UTF_8)
          ByteBuffer.allocate(1 + 4 + tB.length + 4 + refB.length).put(3.toByte).putInt(tB.length).put(tB).putInt(refB.length).put(refB).array()

        case _ => throw new UnsupportedOperationException(s"Serialization not implemented for $cmd")

    def deserialize(bytes: Array[Byte]): Try[AuthCommand] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      def readRef[T](): ActorRef[T] = { val l = buffer.getInt; val a = new Array[Byte](l); buffer.get(a); resolver.resolveActorRef[T](new String(a, StandardCharsets.UTF_8)) }

      buffer.get() match
        case 1 =>
          val uLen = buffer.getInt; val uB = new Array[Byte](uLen); buffer.get(uB)
          Register(userSer.deserialize(uB).get, readRef[RegisterReply]())
        case 2 =>
          val cLen = buffer.getInt; val cB = new Array[Byte](cLen); buffer.get(cB)
          val dur = FiniteDuration(buffer.getLong, MILLISECONDS)
          Authenticate(credSer.deserialize(cB).get, dur, readRef[AuthenticateReply]())
        case 3 =>
          val tLen = buffer.getInt; val tB = new Array[Byte](tLen); buffer.get(tB)
          ValidateToken(tokenSer.deserialize(tB).get, readRef[ValidateTokenReply]())
        case _ => throw new IllegalArgumentException("Unknown AuthCommand type byte")
    }


  /**
   * Serializer for the [[RegisterReply]] protocol message.
   * Handles the serialization of registration outcomes (Registered, AlreadyExists, RegisterError).
   */
  given registerReplySerializer: Serializer[RegisterReply] with
    extension (reply: RegisterReply)
      def serialize: Array[Byte] = reply match
        case RegisterReply.Registered => Array(1.toByte)
        case RegisterReply.AlreadyExists(reason) => val r = reason.getBytes(StandardCharsets.UTF_8); ByteBuffer.allocate(5 + r.length).put(2.toByte).putInt(r.length).put(r).array()
        case RegisterReply.RegisterError(reason) => val r = reason.getBytes(StandardCharsets.UTF_8); ByteBuffer.allocate(5 + r.length).put(3.toByte).putInt(r.length).put(r).array()

    def deserialize(bytes: Array[Byte]): Try[RegisterReply] = Try {
      val b = ByteBuffer.wrap(bytes); b.get() match
        case 1 => RegisterReply.Registered
        case 2 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); RegisterReply.AlreadyExists(new String(a, StandardCharsets.UTF_8))
        case 3 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); RegisterReply.RegisterError(new String(a, StandardCharsets.UTF_8))
    }

  /**
   * Serializer for the [[AuthenticateReply]] protocol message.
   * Handles the serialization of authentication outcomes (Authenticated with token, or AuthFailed with reason).
   *
   * @param tokenSer The implicit [[Serializer]] used for [[Token]] serialization.
   */
  given authenticateReplySerializer(using tokenSer: Serializer[Token]): Serializer[AuthenticateReply] with
    extension (reply: AuthenticateReply)
      def serialize: Array[Byte] = reply match
        case AuthenticateReply.Authenticated(token) => val t = token.serialize; ByteBuffer.allocate(1 + 4 + t.length).put(1.toByte).putInt(t.length).put(t).array()
        case AuthenticateReply.AuthFailed(reason) => val r = reason.getBytes(StandardCharsets.UTF_8); ByteBuffer.allocate(1 + 4 + r.length).put(2.toByte).putInt(r.length).put(r).array()

    def deserialize(bytes: Array[Byte]): Try[AuthenticateReply] = Try {
      val b = ByteBuffer.wrap(bytes); b.get() match
        case 1 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); AuthenticateReply.Authenticated(tokenSer.deserialize(a).get)
        case 2 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); AuthenticateReply.AuthFailed(new String(a, StandardCharsets.UTF_8))
    }

  /**
   * Serializer for the [[ValidateTokenReply]] protocol message.
   * Handles the serialization of token validation outcomes (TokenValid with token, or TokenInvalid with reason).
   *
   * @param tokenSer The implicit [[Serializer]] used for [[Token]] serialization.
   */
  given validateTokenReplySerializer(using tokenSer: Serializer[Token]): Serializer[ValidateTokenReply] with
    extension (reply: ValidateTokenReply)
      def serialize: Array[Byte] = reply match
        case ValidateTokenReply.TokenValid(token) => val t = token.serialize; ByteBuffer.allocate(1 + 4 + t.length).put(1.toByte).putInt(t.length).put(t).array()
        case ValidateTokenReply.TokenInvalid(reason) => val r = reason.getBytes(StandardCharsets.UTF_8); ByteBuffer.allocate(1 + 4 + r.length).put(2.toByte).putInt(r.length).put(r).array()

    def deserialize(bytes: Array[Byte]): Try[ValidateTokenReply] = Try {
      val b = ByteBuffer.wrap(bytes); b.get() match
        case 1 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); ValidateTokenReply.TokenValid(tokenSer.deserialize(a).get)
        case 2 => val l = b.getInt; val a = new Array[Byte](l); b.get(a); ValidateTokenReply.TokenInvalid(new String(a, StandardCharsets.UTF_8))
    }