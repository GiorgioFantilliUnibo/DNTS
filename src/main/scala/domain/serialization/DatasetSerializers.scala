package domain.serialization

import java.nio.ByteBuffer
import scala.util.Try

import domain.data.{Label, Point2D, LabeledPoint2D}


/**
 * Binary serializers for sets of [[LabeledPoint2D]].
 */
object DatasetSerializers:

  /**
   * Serializer for [[Label]].
   * Encodes a label into a single byte (1 for Positive, 0 for Negative).
   */
  given labelSerializer: Serializer[Label] with

    extension (l: Label) 
      def serialize: Array[Byte] =
        Array(if l == Label.Positive then 1.toByte else 0.toByte)

    def deserialize(bytes: Array[Byte]): Try[Label] = Try {
      if bytes(0) == 1.toByte then Label.Positive else Label.Negative
    }


  /**
   * Serializer for [[Point2D]].
   * Encodes the two double coordinates into a 16-byte array.
   */
  given point2dSerializer: Serializer[Point2D] with

    extension (p: Point2D) 
      def serialize: Array[Byte] =
        ByteBuffer.allocate(16).putDouble(p.x).putDouble(p.y).array()

    def deserialize(bytes: Array[Byte]): Try[Point2D] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      Point2D(buffer.getDouble, buffer.getDouble)
    }


  /**
   * Serializer for [[LabeledPoint2D]].
   * Combines the point and the label into a 17-byte array.
   *
   * @param pSer The implicit serializer for [[Point2D]].
   * @param lSer The implicit serializer for [[Label]].
   */
  given labeledPointSerializer(
    using
      pSer: Serializer[Point2D],
      lSer: Serializer[Label]
  ): Serializer[LabeledPoint2D] with

    extension (lp: LabeledPoint2D) 
      def serialize: Array[Byte] =
        val pBytes = lp.point.serialize
        val lBytes = lp.label.serialize
        ByteBuffer.allocate(17).put(pBytes).put(lBytes).array()

    def deserialize(bytes: Array[Byte]): Try[LabeledPoint2D] = Try {
      val pBytes = bytes.take(16)
      val lBytes = bytes.drop(16)
      LabeledPoint2D(pSer.deserialize(pBytes).get, lSer.deserialize(lBytes).get)
    }


  /**
   * Serializer for a dataset (List of [[LabeledPoint2D]]).
   * Encodes the list size followed by the serialized points.
   *
   * @param lpSer The implicit serializer for [[LabeledPoint2D]].
   */
  given datasetSerializer(using lpSer: Serializer[LabeledPoint2D]): Serializer[List[LabeledPoint2D]] with

    extension (dataset: List[LabeledPoint2D]) 
      def serialize: Array[Byte] =
        val buffer = ByteBuffer.allocate(4 + dataset.size * 17)
        buffer.putInt(dataset.size)
        dataset.foreach(lp => buffer.put(lp.serialize))
        buffer.array()

    def deserialize(bytes: Array[Byte]): Try[List[LabeledPoint2D]] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      val size = buffer.getInt
      (0 until size).map { _ =>
        val lpBytes = new Array[Byte](17)
        buffer.get(lpBytes)
        lpSer.deserialize(lpBytes).get
      }.toList
    }
