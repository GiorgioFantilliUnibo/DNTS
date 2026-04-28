package domain.serialization

import scala.util.Try

/**
 * Type class interface defining the contract for binary serialization.
 *
 * @tparam A The type of the object to be serialized.
 */
trait Serializer[A]:

  extension (value: A) 
    /** Converts the domain object into a compact byte array. */
    def serialize: Array[Byte]

  /**
   * Attempts to reconstruct the domain object from a byte array.
   * Returns a [[Try]] to safely handle data corruption or version mismatches.
   */
  def deserialize(bytes: Array[Byte]): Try[A]

object Serializer:
  
  extension (bytes: Array[Byte])
    /**
     * Attempts to reconstruct the domain object from a byte array.
     * Returns a [[Try]] to safely handle data corruption or version mismatches.
     */
    def deserialize[A](using s: Serializer[A]): Try[A] = s.deserialize(bytes)

/**
 * Interface for converting domain objects into human-readable text formats.
 */
trait Exporter[A]:
  
  extension (value: A) 
    /** Generates a structured JSON string representation of the object. */
    def jsonExport: String
