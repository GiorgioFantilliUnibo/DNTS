package domain.serialization

import akka.actor.Address
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.charset.StandardCharsets
import scala.util.Try
import domain.serialization.Exporters.*
import scala.jdk.CollectionConverters.*
import domain.serialization.Exporters.addressSetExporter

class FileNodeRepository(path: Path):

  def getKnownNodes: Set[Address] =
    if !Files.exists(path) then
      Set.empty
    else
      val content =
        Try(Files.readString(path, StandardCharsets.UTF_8)).getOrElse("[]")

      AddressJson.parse(content) // parser sotto

  def saveNodes(nodes: Set[Address]): Unit =
    //    val json = summon[Exporter[Set[Address]]].jsonExport(nodes)
    val json = nodes.jsonExport

    Try {
      Files.writeString(
        path,
        json,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    }.recover {
      case e => println(s"[WARN] Failed to save nodes: ${e.getMessage}")
    }

  def addNode(node: Address): Unit =
    val updated = getKnownNodes + node
    saveNodes(updated)

  def clear(): Unit =
    Try(Files.deleteIfExists(path))