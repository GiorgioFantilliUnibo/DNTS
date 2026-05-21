package app

import akka.actor.typed.ActorSystem
import config.{AkkaConfig, AppConfig, ProductionConfig}
import actors.root.RootActor
import cli.{CliParser, ParseResult}
import config.ProductionConfig.clusterNodesLogFileName
import domain.authentication.AuthAction.Login
import domain.serialization.FileNodeRepository

import java.nio.file.Paths

object Main:

  @main def run(args: String*): Unit =
    val parseResult = CliParser.parse(args.toList)

    parseResult match
      case ParseResult.Help =>
        println(CliParser.getHelpText)
        sys.exit(0)

      case ParseResult.Failure(msg) =>
        System.err.println(s"Error: $msg")
        println(CliParser.getHelpText)
        sys.exit(1)

      case ParseResult.Success(options) =>
        options.validate match
          case Left(errorMsg) =>
            System.err.println(s"Configuration Error: $errorMsg")
            sys.exit(1)

          case Right((role, clusterName, configPath, clusterIp, clusterPort, authOptions)) =>
            println(s">>> Starting Node with Role: $role")

            authOptions.foreach { auth =>
              println(s">>> Authentication requested: Action=${auth.action}, Username=${auth.username}")
            }

            given appConfig: AppConfig = ProductionConfig

            val repo = FileNodeRepository(Paths.get(clusterNodesLogFileName))

            val knownNodes = repo.getKnownNodes
            val knownNodesAddress = knownNodes.map(address => s""","${address.toString}"""").mkString("")

            val akkaConfig = AkkaConfig.load(role, clusterName.get, clusterIp, clusterPort, knownNodesAddress)

            val rootBehavior = RootActor(
              role = role,
              configPath = configPath,
              akkaConfig,
              options.action.getOrElse(Login),
              options.username.getOrElse("default"),
              options.password.getOrElse("secret"),
              options.fullName
            )

            ActorSystem(rootBehavior, clusterName.get, akkaConfig)