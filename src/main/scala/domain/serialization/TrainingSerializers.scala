package domain.serialization

import actors.trainer.TrainerActor.TrainingConfig
import domain.data.LabeledPoint2D
import domain.network.{Feature, HyperParams, Regularization}
import domain.network.Regularization.{ElasticNet, L1, L2, None as RegNone}

import java.nio.ByteBuffer
import scala.util.Try
import java.nio.charset.StandardCharsets


/**
 * Binary serializers for training configurations and related components.
 */
object TrainingSerializers:

  /**
   * Serializer for [[Regularization]] strategies.
   */
  given regularizationSerializer: Serializer[Regularization] with
    extension (reg: Regularization) 
      def serialize: Array[Byte] =
        val buffer = ByteBuffer.allocate(24)
        reg match
          case RegNone =>
            buffer.putInt(0)
          case L2(l) =>
            buffer.putInt(1); buffer.putDouble(l)
          case L1(l) =>
            buffer.putInt(2); buffer.putDouble(l)
          case ElasticNet(l1, l2) =>
            buffer.putInt(3); buffer.putDouble(l1); buffer.putDouble(l2)

        buffer.array().take(buffer.position())

    def deserialize(bytes: Array[Byte]): Try[Regularization] = Try {
      val buffer = ByteBuffer.wrap(bytes)
      buffer.getInt match
        case 0 => RegNone
        case 1 => L2(buffer.getDouble)
        case 2 => L1(buffer.getDouble)
        case 3 => ElasticNet(buffer.getDouble, buffer.getDouble)
        case _ => throw new IllegalArgumentException("Unknown Regularization type")
    }

  /**
   * Serializer for [[TrainingConfig]].
   * Orchestrates the serialization of the training configuration, including simulation ID,
   * datasets, features, hyperparameters, and training parameters.
   *
   * @param featSer The implicit [[Serializer]] for the list of [[Feature]].
   * @param regSer  The implicit [[Serializer]] for [[Regularization]].
   * @param dataSer The implicit [[Serializer]] for the dataset (List of [[LabeledPoint2D]]).
   */
  given trainingConfigSerializer(
    using
      featSer: Serializer[List[Feature]],
      regSer: Serializer[Regularization],
      dataSer: Serializer[List[LabeledPoint2D]]
  ): Serializer[TrainingConfig] with

    extension (conf: TrainingConfig) 
      def serialize: Array[Byte] =
        val idBytes = conf.simulationId.getBytes(StandardCharsets.UTF_8)
        val featBytes = conf.features.serialize
        val regBytes = conf.hp.regularization.serialize
        val trainBytes = conf.trainSet.serialize
        val testBytes = conf.testSet.serialize

        val userBytes = conf.username.map(_.getBytes(StandardCharsets.UTF_8))
        val passBytes = conf.password.map(_.getBytes(StandardCharsets.UTF_8))
        
        val userSize = userBytes.map(b => 4 + b.length).getOrElse(0)
        val passSize = passBytes.map(b => 4 + b.length).getOrElse(0)

        val totalSize =
          4 + idBytes.length +
          4 + trainBytes.length +
          4 + testBytes.length +
          4 + featBytes.length +
          8 +
          4 + regBytes.length +
          4 +
          4 +
          9 +
          1 + userSize +
          1 + passSize

        val buffer = ByteBuffer.allocate(totalSize)

        buffer.putInt(idBytes.length)
        buffer.put(idBytes)

        buffer.putInt(trainBytes.length)
        buffer.put(trainBytes)
        buffer.putInt(testBytes.length)
        buffer.put(testBytes)

        buffer.putInt(featBytes.length)
        buffer.put(featBytes)

        buffer.putDouble(conf.hp.learningRate)
        buffer.putInt(regBytes.length)
        buffer.put(regBytes)

        buffer.putInt(conf.epochs)
        buffer.putInt(conf.batchSize)

        conf.seed match
          case Some(s) => buffer.put(1.toByte); buffer.putLong(s)
          case None    => buffer.put(0.toByte); buffer.putLong(0L)

        conf.username match
          case Some(u) => 
            val b = u.getBytes(StandardCharsets.UTF_8)
            buffer.put(1.toByte)
            buffer.putInt(b.length)
            buffer.put(b)
          case None => 
            buffer.put(0.toByte)

        conf.password match
          case Some(p) => 
            val b = p.getBytes(StandardCharsets.UTF_8)
            buffer.put(1.toByte)
            buffer.putInt(b.length)
            buffer.put(b)
          case None => 
            buffer.put(0.toByte)

        buffer.array()

    def deserialize(bytes: Array[Byte]): Try[TrainingConfig] = Try {
      val buffer = ByteBuffer.wrap(bytes)

      val idLen = buffer.getInt
      val idBytes = new Array[Byte](idLen)
      buffer.get(idBytes)
      val simulationId = new String(idBytes, StandardCharsets.UTF_8)

      val trainLen = buffer.getInt
      val trainBytes = new Array[Byte](trainLen)
      buffer.get(trainBytes)
      val trainSet = dataSer.deserialize(trainBytes).get

      val testLen = buffer.getInt
      val testBytes = new Array[Byte](testLen)
      buffer.get(testBytes)
      val testSet = dataSer.deserialize(testBytes).get

      val featLen = buffer.getInt
      val featBytes = new Array[Byte](featLen)
      buffer.get(featBytes)
      val features = featSer.deserialize(featBytes).get

      val lr = buffer.getDouble
      val regLen = buffer.getInt
      val regBytes = new Array[Byte](regLen)
      buffer.get(regBytes)
      val reg = regSer.deserialize(regBytes).get

      val hp = HyperParams(lr, reg)

      val epochs = buffer.getInt
      val batchSize = buffer.getInt

      val hasSeed = buffer.get() == 1.toByte
      val seedVal = buffer.getLong
      val seed = if (hasSeed) Some(seedVal) else None

      val hasUser = buffer.get() == 1.toByte
      val username = if (hasUser) {
        val len = buffer.getInt
        val bytes = new Array[Byte](len)
        buffer.get(bytes)
        Some(new String(bytes, StandardCharsets.UTF_8))
      } else None

      val hasPass = buffer.get() == 1.toByte
      val password = if (hasPass) {
        val len = buffer.getInt
        val bytes = new Array[Byte](len)
        buffer.get(bytes)
        Some(new String(bytes, StandardCharsets.UTF_8))
      } else None

      TrainingConfig(simulationId, trainSet, testSet, features, hp, epochs, batchSize, seed, password, username)
    }
