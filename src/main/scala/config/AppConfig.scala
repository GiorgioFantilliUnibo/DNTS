package config

import domain.data.util.Space
import scala.concurrent.duration.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import domain.training.LossFunction
import domain.training.Strategies.Losses

/**
 * Defines the configuration contract for the system.
 */
trait AppConfig:
  /** The time interval at which the Monitor queries the ModelActor for metrics. */
  def metricsInterval: FiniteDuration

  /** The delay between processing two consecutive training batches. */
  def batchInterval: FiniteDuration

  /** The time interval at which the GossipActor triggers a synchronization with a peer. */
  def gossipInterval: FiniteDuration

  /** The filename used for logging the node network weights. */
  def netLogFileName: String
  
  /** Defines the boundaries of the 2D plane used for data generation and visualization. */
  def space: Space

  /** The loss function used to measure the network performance. */
  def lossFunction: LossFunction

  /**
   * The time interval at which the GossipActor triggers a global consensus round.
   */
  def consensusInterval: FiniteDuration
  
  /**
   * The time interval at which the GossipActor sends a synchronization request to a random peer.
   */
  def gossipRequestConfig: FiniteDuration

  /** The time interval at which the ModelActor persists a snapshot of the current model to disk. */
  def snapshotInterval: FiniteDuration

  /** The file path used to persist the local model snapshot for crash recovery. */
  def modelSnapshotPath: String

  /** The file path used to persist the local training snapshot for crash recovery. */
  def trainingSnapshotPath: String


/**
 * Default Production Configuration.
 */
object ProductionConfig extends AppConfig:
  /** UI and metrics refresh rate. */
  override final val metricsInterval: FiniteDuration = 550.millis

  /** Local training speed (delay between batches). */
  override final val batchInterval: FiniteDuration = 75.millis

  /** P2P synchronization frequency. */
  override final val gossipInterval: FiniteDuration = 4.seconds

  /** Dynamic log filename generation with timestamp. */
  override final val netLogFileName: String =
    val now = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    val timestamp = now.format(formatter)
    s"node_network_$timestamp.log"

  /** Defines a 100x100 coordinate space. */
  override final val space: Space = Space(50.0, 50.0)

  /** Define Mean Squared Error (MSE) as the standard loss metric. */
  override final val lossFunction: LossFunction = Losses.mse

  /**
   * Global consensus round frequency.
   */
  override final val consensusInterval: FiniteDuration = 400.millis

  /**
   * The time interval at which the GossipActor sends a synchronization request to a random peer.
   */
  override final val gossipRequestConfig: FiniteDuration = 3.seconds

  /** The time interval at which the ModelActor persists a snapshot of the current model to disk. */
  override final val snapshotInterval: FiniteDuration = 10.seconds

  /** Default path for the local model snapshot used in crash recovery. */
  override final val modelSnapshotPath: String = "local_model_snapshot.bin"

  /** Default path for the local training snapshot used in crash recovery. */
  override final val trainingSnapshotPath: String = "local_training_snapshot.bin"
