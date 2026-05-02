package actors.cluster.effect

import actors.cluster.ClusterState
import actors.cluster.effect.*
import actors.cluster.ClusterProtocol.{ClusterMemberCommand, NodeReachable}
import actors.cluster.timer.ClusterTimers
import actors.discovery.DiscoveryProtocol.DiscoveryCommand
import actors.monitor.MonitorProtocol.MonitorCommand
import actors.monitor.MonitorProtocol.MonitorCommand.PeerCountChanged
import actors.root.RootProtocol.RootCommand
import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import akka.cluster.typed.{Cluster, Down, Leave}
import config.ProductionConfig.clusterNodesLogFileName
import domain.serialization.FileNodeRepository

import java.nio.file.Paths

/**
 * Interpret the [[Effect]] produced by a cluster manager policy.
 */
object ClusterEffects:

  /**
   * Executes an [[Effect]].
   *
   * @param state current node cluster state view
   * @param context Akka typed context
   * @param timers timer scheduler associated to the context
   * @param effect the effect to be interpreted
   * @param timersDuration timing configuration
   * @param monitorActor reference to the monitor actor
   * @param receptionistManager reference to the receptionist actor
   * @param rootActor reference to the root actor
   */
  def apply(
             state: ClusterState,
             context: ActorContext[ClusterMemberCommand],
             timers: TimerScheduler[ClusterMemberCommand],
             effect: Effect,
             timersDuration: ClusterTimers,
             monitorActor: Option[ActorRef[MonitorCommand]],
             receptionistManager: ActorRef[DiscoveryCommand],
             rootActor: ActorRef[RootCommand]
  ): Unit = effect match

      case NotifyRoot(event) =>
        rootActor ! event

      case NotifyMonitor =>
        if monitorActor.isDefined then
          monitorActor.get ! PeerCountChanged(state.view.available, state.view.total)

      case NotifyReceptionist(event) =>
        receptionistManager ! event

      case NotifyCluster(event) =>
        context.self ! event

      case StartTimer(id, event) =>
        timers.startSingleTimer(id, event, timersDuration.unreachableNode)

      case CancelTimer(id) =>
        timers.cancel(id)

      case DownNode(nodeAddress) =>
        val cluster = Cluster(context.system)
        if cluster.state.leader.contains(cluster.selfMember.address) then
          cluster.manager ! Down(nodeAddress)

      case RemoveNodeFromCluster(nodeAddress) =>
        val cluster = Cluster(context.system)
        if cluster.state.leader.contains(cluster.selfMember.address) then
          cluster.manager ! Down(nodeAddress)

      case LogNode(address) =>
        FileNodeRepository(Paths.get(clusterNodesLogFileName)).addNode(address)

      case LeaveCluster =>
        FileNodeRepository(Paths.get(clusterNodesLogFileName)).clear()
        val cluster = Cluster(context.system)
        timers.cancelAll()
        cluster.manager ! Leave(cluster.selfMember.address)

      case StopBehavior =>
        Behaviors.stopped

      case _ => Behaviors.unhandled
