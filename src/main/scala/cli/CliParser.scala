package cli

import domain.authentication.{AuthAction, NodeRole}

import scala.annotation.tailrec

sealed trait ParseResult

object ParseResult:
  final case class Success(options: CliOptions) extends ParseResult
  case object Help extends ParseResult
  final case class Failure(message: String) extends ParseResult

object CliParser:

  private val HelpText =
    s"""
       |Usage:
       |  Master (Seed): run --role ${NodeRole.Seed.toString} --cluster <name> [--config <file>] --port <local-port>
       |  Worker (Client): run --role ${NodeRole.Client.toString} --cluster <name> --seedAddress <seed-ip-port> --port <current-node-port> --action <login|register> --username <user> --password <pass> [--fullname <name>]
       |
       |Options:
       |  --role <${NodeRole.validOptions}>   Defines the node role.
       |  --config <path>                     Path to the simulation configuration.
       |  --cluster <name>                    Cluster name.
       |  --seedAddress <address>             Address of the seed node.
       |  --port <port>                       Local port to bind.
       |  --action <login|register>           Authentication action (Client only).
       |  --username <string>                 Username for authentication.
       |  --password <string>                 Password for authentication.
       |  --fullName <string>                 Full name (Required for registration).
       |""".stripMargin

  def getHelpText: String = HelpText

  def parse(args: List[String]): ParseResult =
    if args.isEmpty || args.contains("--help") then
      ParseResult.Help
    else
      parseRec(args, CliOptions())

  @tailrec
  private def parseRec(args: List[String], current: CliOptions): ParseResult =
    args match
      case Nil =>
        ParseResult.Success(current)

      case "--role" :: value :: tail =>
        NodeRole.fromString(value) match
          case Some(r) => parseRec(tail, current.copy(role = Some(r)))
          case None    => ParseResult.Failure(s"Invalid role: '$value'. Must be one of: ${NodeRole.validOptions}.")

      case "--config" :: path :: tail =>
        parseRec(tail, current.copy(configFile = Some(path)))

      case "--cluster" :: name :: tail =>
        parseRec(tail, current.copy(cluster = Some(name)))

      case "--seedAddress" :: value :: tail =>
        parseRec(tail, current.copy(seedAddress = Some(value)))

      case "--port" :: value :: tail =>
        value.toIntOption match
          case Some(p) => parseRec(tail, current.copy(port = Some(p)))
          case None    => ParseResult.Failure(s"Invalid port number: $value")

      case "--action" :: value :: tail =>
        AuthAction.fromString(value) match
          case Some(act) => parseRec(tail, current.copy(action = Some(act)))
          case None      => ParseResult.Failure(s"Invalid action: '$value'. Must be one of: ${AuthAction.validOptions}.")

      case "--username" :: value :: tail =>
        parseRec(tail, current.copy(username = Some(value)))

      case "--password" :: value :: tail =>
        parseRec(tail, current.copy(password = Some(value)))

      case "--fullName" :: value :: tail =>
        parseRec(tail, current.copy(fullName = Some(value)))

      case unknown :: _ =>
        ParseResult.Failure(s"Unknown argument: $unknown")