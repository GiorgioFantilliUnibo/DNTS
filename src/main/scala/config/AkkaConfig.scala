package config

import domain.authentication.NodeRole
import domain.authentication.NodeRole.{Client, Seed}
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

import java.net.NetworkInterface
import scala.jdk.CollectionConverters.*
import java.nio.file.{Files, Paths}
import java.util.UUID


object AkkaConfig :

  private def getLocalIp: String =
    NetworkInterface.getNetworkInterfaces.asScala
      .flatMap(_.getInetAddresses.asScala)
      .collect {
        case addr if !addr.isLoopbackAddress && addr.isInstanceOf[java.net.Inet4Address] =>
          addr.getHostAddress
      }
      .toList
      .lastOption
      .getOrElse("127.0.0.1")

  def load(role: NodeRole, clusterName: String, seedNodeAddress: Option[String], nodePort: Option[Int],
           knownNodes: String): Config =

    val port = nodePort.getOrElse("5082")
    val seed = seedNodeAddress match
      case Some(address) =>
        s""""akka://$clusterName@$address""""
      case None =>
        s""""akka://$clusterName@$getLocalIp:$port"$knownNodes"""

    ConfigFactory.parseString(
      s"""
        akka.remote.artery.canonical.hostname = $getLocalIp
        akka.remote.artery.canonical.port = $port
        akka.cluster.roles = [${role.id}]
        akka.cluster.seed-nodes = [$seed]
      """).withFallback(ConfigFactory.load())