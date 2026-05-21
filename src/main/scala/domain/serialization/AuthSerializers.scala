package domain.serialization

import actors.authentication.AuthProtocol.*
import domain.authentication.{User, NodeRole}
import akka.actor.typed.ActorRefResolver
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * Binary serializers for Authentication messages.
 */
object AuthSerializers:

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

      def readString(): String =
        val len = buffer.getInt
        val arr = new Array[Byte](len)
        buffer.get(arr)
        new String(arr, StandardCharsets.UTF_8)

      val username = readString()
      val fullName = readString()
      val roleStr  = readString()
      val password = readString()

      User(username, fullName, NodeRole.fromString(roleStr).get, password)
    }

  given authCommandSerializer(using userSer: Serializer[User], resolver: ActorRefResolver): Serializer[AuthCommand] with
    extension (cmd: AuthCommand)
      def serialize: Array[Byte] = cmd match
        case Register(user, replyTo) =>
          val uBytes = user.serialize
          val refBytes = resolver.toSerializationFormat(replyTo).getBytes(StandardCharsets.UTF_8)

          val buffer = ByteBuffer.allocate(1 + 4 + uBytes.length + 4 + refBytes.length)
          buffer.put(1.toByte) // Identificatore per Register
          buffer.putInt(uBytes.length).put(uBytes)
          buffer.putInt(refBytes.length).put(refBytes)
          buffer.array()

        case _ => throw new UnsupportedOperationException(s"Serialization not implemented for $cmd")

    def deserialize(bytes: Array[Byte]): Try[AuthCommand] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      val cmdType = buffer.get()

      cmdType match
        case 1 => // Register
          val uLen = buffer.getInt
          val uBytes = new Array[Byte](uLen)
          buffer.get(uBytes)
          val user = userSer.deserialize(uBytes).get

          val refLen = buffer.getInt
          val refBytes = new Array[Byte](refLen)
          buffer.get(refBytes)
          val refStr = new String(refBytes, StandardCharsets.UTF_8)
          val replyTo = resolver.resolveActorRef[RegisterReply](refStr)

          Register(user, replyTo)

        case _ => throw new IllegalArgumentException("Unknown AuthCommand type byte")
    }

  given registerReplySerializer: Serializer[RegisterReply] with
    extension (reply: RegisterReply)
      def serialize: Array[Byte] = reply match
        case RegisterReply.Registered =>
          Array(1.toByte)
        case RegisterReply.AlreadyExists(reason) =>
          val rBytes = reason.getBytes(StandardCharsets.UTF_8)
          ByteBuffer.allocate(1 + 4 + rBytes.length).put(2.toByte).putInt(rBytes.length).put(rBytes).array()
        case RegisterReply.RegisterError(reason) =>
          val rBytes = reason.getBytes(StandardCharsets.UTF_8)
          ByteBuffer.allocate(1 + 4 + rBytes.length).put(3.toByte).putInt(rBytes.length).put(rBytes).array()

    def deserialize(bytes: Array[Byte]): Try[RegisterReply] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      buffer.get() match
        case 1 => RegisterReply.Registered
        case 2 =>
          val len = buffer.getInt
          val arr = new Array[Byte](len)
          buffer.get(arr)
          RegisterReply.AlreadyExists(new String(arr, StandardCharsets.UTF_8))
        case 3 =>
          val len = buffer.getInt
          val arr = new Array[Byte](len)
          buffer.get(arr)
          RegisterReply.RegisterError(new String(arr, StandardCharsets.UTF_8))
    }