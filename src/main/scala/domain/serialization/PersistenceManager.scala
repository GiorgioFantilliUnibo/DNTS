package domain.serialization

import java.nio.file.{Files, Paths}
import scala.util.Try

import domain.serialization.Serializer.*


/**
 * Utility for saving and retrieving state from the local file system.
 */
object PersistenceManager:

  /**
   * Extension method to save any object of type T to a file.
   * The context bound [T: Serializer] requires an implicit type class instance in scope.
   */
  extension [T: Serializer](data: T)
    def saveToFile(filePath: String): Try[Unit] = Try {
      val bytes = data.serialize
      Files.write(Paths.get(filePath), bytes)
      ()
    }

  /**
   * Loads a generic object T from a file.
   * Uses a context bound to ensure a [[Serializer]] is available for type T.
   *
   * @param filePath The path of the file to load from.
   * @tparam T       The type of the object to deserialize.
   */
  def loadFromFile[T: Serializer](filePath: String): Try[T] = Try {
    val bytes = Files.readAllBytes(Paths.get(filePath))
    bytes.deserialize[T].get
  }
