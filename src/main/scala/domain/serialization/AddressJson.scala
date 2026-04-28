package domain.serialization

import akka.actor.Address
import scala.util.Try
import scala.util.matching.Regex

object AddressJson:

  private val entryRegex: Regex =
    """\{\s*"protocol":\s*"([^"]+)",\s*"system":\s*"([^"]+)",\s*"host":\s*(null|"[^"]+"),\s*"port":\s*(null|\d+)\s*\}""".r

  def parse(json: String): Set[Address] =
    entryRegex.findAllMatchIn(json).flatMap { m =>
      Try {
        val protocol = m.group(1)
        val system   = m.group(2)

        val host =
          Option(m.group(3))
            .filter(_ != "null")
            .map(_.replaceAll("\"", ""))

        val port =
          Option(m.group(4))
            .filter(_ != "null")
            .map(_.toInt)

        Address(protocol, system, host.get, port.get)
      }.toOption
    }.toSet
