package actors.model

import actors.model.ModelActor.ModelCommand
import actors.monitor.MonitorActor.MonitorCommand
import actors.trainer.TrainerActor.TrainerCommand
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import config.AppConfig
import domain.model.ModelTasks
import domain.training.Optimizer
import domain.network.Model
import domain.network.Activations.given
import domain.serialization.Exporters.given
import domain.serialization.PersistenceManager.*
import domain.serialization.ModelSerializers.given
import domain.serialization.NetworkSerializers.given
import domain.serialization.LinearAlgebraSerializers.given
import ModelConstants.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

private object ModelConstants:
  val InitialEpoch: Int = 0
  val InitialConsensus: Double = 0.0

/**
 * Encapsulates the behavior logic for the ModelActor.
 *
 * @param context The actor context.
 * @param timers  The scheduler for managing periodic snapshot ticks.
 * @param config  Global application configuration.
 *
 */
private[model] class ModelBehavior(
  context: ActorContext[ModelCommand],
  timers: TimerScheduler[ModelCommand],
  config: AppConfig
):

  /**
   * Initial state: Waiting for the model and optimizer initialization.
   */
  def idle(): Behavior[ModelCommand] =
    Behaviors.receive: (ctx, msg) =>
      msg match
        case ModelCommand.Initialize(model, optimizer, trainerActor) =>
          ctx.log.info("Model: Model initialized. Switching to active state.")

          given Optimizer = optimizer

          timers.startTimerWithFixedDelay(
            ModelCommand.TakeSnapshot,
            ModelCommand.TakeSnapshot,
            config.snapshotInterval
          )

          active(
            currentModel = model,
            currentEpoch = InitialEpoch,
            currentConsensus = InitialConsensus,
            trainerActor
          )

        case ModelCommand.StopSimulation =>
          Behaviors.stopped

        case _ => Behaviors.unhandled

  /**
   * Main state: Model updates and synchronization.
   *
   * @param currentModel     The current model of the Neural Network.
   * @param currentEpoch     The current training epoch count.
   * @param currentConsensus The current consensus metric (divergence from peers).
   * @param trainerActor     Reference to the associated TrainerActor.
   */
  private def active(
    currentModel: Model,
    currentEpoch: Int,
    currentConsensus: Double,
    trainerActor: ActorRef[TrainerCommand]
  )(using Optimizer): Behavior[ModelCommand] =
    Behaviors.receive: (_, message) =>
      message match
        case ModelCommand.ApplyGradients(grads) =>
          val (newModel, _) = ModelTasks.applyGradients(grads).run(currentModel)
          active(
            currentModel = newModel.copy(maturity = currentModel.maturity + 1),
            currentEpoch = currentEpoch,
            currentConsensus = currentConsensus,
            trainerActor = trainerActor
          )

        case ModelCommand.SyncModel(remoteModel) =>
          val (newModel, _) = ModelTasks.mergeWith(remoteModel).run(currentModel)
          active(newModel, currentEpoch, currentConsensus, trainerActor)

        case ModelCommand.UpdateConsensus(consensusValue) =>
          context.log.debug(f"Model: Global consensus updated → $consensusValue%.6f")
          active(currentModel, currentEpoch, consensusValue, trainerActor)

        case ModelCommand.GetPrediction(point, replyTo) =>
          val prediction = currentModel.predict(point)(using config.space)
          replyTo ! prediction
          Behaviors.same

        case ModelCommand.GetModel(replyTo) =>
          replyTo ! currentModel
          Behaviors.same

        case ModelCommand.GetMetrics(replyTo) =>
          trainerActor ! TrainerCommand.CalculateMetrics(
            currentModel,
            replyTo = context.messageAdapter(m => ModelCommand.InternalMetricsResult(m, replyTo))
          )
          Behaviors.same

        case ModelCommand.InternalMetricsResult(metrics, monitorReplyTo) =>
          monitorReplyTo ! MonitorCommand.ViewUpdateResponse(
            epoch = metrics.epoch,
            model = currentModel,
            trainLoss = metrics.trainLoss,
            testLoss = metrics.testLoss,
            consensus = currentConsensus
          )
          Behaviors.same

        case ModelCommand.ExportToFile =>
          val jsonModel = currentModel.jsonExport
          val fileName = config.netLogFileName
          val path = Paths.get(fileName)
          try {
            Files.write(path, jsonModel.getBytes(StandardCharsets.UTF_8))
            context.log.info(s"Model exported in: $fileName")
          } catch {
            case e: Exception =>
              context.log.error(s"Error in exportation of $fileName: ${e.getMessage}")
          }
          Behaviors.same

        case ModelCommand.StopSimulation =>
          timers.cancelAll()
          Behaviors.stopped

        case ModelCommand.TakeSnapshot =>
          currentModel.saveToFile(config.modelSnapshotPath) match
            case scala.util.Success(_) =>
              context.log.debug(s"Model: Snapshot saved to '${config.modelSnapshotPath}' (maturity=${currentModel.maturity}).")
            case scala.util.Failure(ex) =>
              context.log.warn(s"Model: Failed to save snapshot to '${config.modelSnapshotPath}': ${ex.getMessage}")
          Behaviors.same

        case _ => Behaviors.unhandled